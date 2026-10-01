package com.androidharness.app.data.env

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import com.androidharness.app.IHarnessService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class ShizukuState {
    NOT_INSTALLED,
    NOT_RUNNING,
    RUNNING_NO_PERMISSION,
    GRANTED,
}

/** Whether the in-server [HarnessUserService] is available for exec calls. */
enum class UserServiceState {
    NOT_BOUND,
    BOUND_READY,
    BIND_FAILED,
}

/** Result of a privileged command executed inside Shizuku's server process. */
data class PrivilegedResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val output: String,
    val stderr: String = "",
)

/**
 * Bridges AndroidHarness to Shizuku. Once Shizuku is running AND the user has
 * granted access, we bind an in-server "user service" ([HarnessUserService])
 * that executes shell commands as the shell (or root) uid, the only reliable
 * way to reach system paths, pm/am, and any folder on the device on modern
 * Android. Also deploys a copy of the app's Linux toolchain to
 * /data/local/tmp/androidharness (a location that uid can exec from).
 */
class ShizukuManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {

    internal val workspaceWarning = shizukuWarningController(
        context.getSharedPreferences("shizuku_notices", Context.MODE_PRIVATE),
    )

    private val _state = MutableStateFlow(ShizukuState.NOT_INSTALLED)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val _serviceState = MutableStateFlow(UserServiceState.NOT_BOUND)
    val serviceState: StateFlow<UserServiceState> = _serviceState.asStateFlow()

    @Volatile private var service: IHarnessService? = null
    private val bindRequested = AtomicBoolean(false)

    /** This build's deployed copy; see [LinuxEnvironmentManager.deployedBaseFor]. */
    private val tmpBase: String = LinuxEnvironmentManager.deployedBaseFor(context.packageName)

    @Volatile private var tmpPrefixDeployed = false

    /**
     * Package-set hash plus staged-tarball content hash the deployed copy was
     * extracted from. Readiness is keyed to it: the tier's environment is built
     * from the APP prefix (PATH, the termux-exec preload, the CA bundle, the
     * OpenSSL config), so a copy that came from other bytes is missing exactly
     * the files that env points at, and binaries started in it die in the
     * linker. Installing a package, or any staging that lagged behind for a
     * moment, makes the copy stale on the spot instead of five minutes later
     * when the periodic verify comes round.
     */
    @Volatile private var deployedTag: String? = null

    /**
     * True while the deploy script is gutting and re-extracting
     * /data/local/tmp/androidharness: during that window the deployed prefix
     * is partially absent even though [isTmpPrefixDeployed] stays true (the
     * hash matched at the last deploy), so status tools must not trust it.
     */
    @Volatile private var deploying = false

    fun isDeployInProgress(): Boolean = deploying

    @Volatile private var deployCheckForced = false

    /**
     * Forces the next deploy check to compare hashes again (instead of
     * trusting the cached deployed-state flag): removing the deployed
     * .harness-hash or the staging marker then takes effect on the next
     * privileged command, without an app restart.
     */
    fun invalidateDeployState() {
        deployCheckForced = true
        tmpPrefixDeployed = false
        deployedTag = null
    }

    private val isDebuggable: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val appVersionCode: Int = runCatching {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        (pInfo.lastUpdateTime and 0x7FFFFFFF).toInt()
    }.getOrDefault(1)

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, HarnessUserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("harness")
        .debuggable(isDebuggable)
        .version(appVersionCode)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) {
                IHarnessService.Stub.asInterface(binder)
            } else null
            _serviceState.value =
                if (service != null) UserServiceState.BOUND_READY else UserServiceState.BIND_FAILED
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bindRequested.set(false)
            _serviceState.value = UserServiceState.NOT_BOUND
            // The server may have restarted, rebind once it is back.
            scope.launch {
                delay(1_500)
                refresh()
            }
        }
    }

    init {
        refresh()

        runCatching {
            Shizuku.addBinderReceivedListener { refresh() }
        }
        runCatching {
            Shizuku.addBinderDeadListener {
                _state.value = ShizukuState.NOT_RUNNING
                service = null
                bindRequested.set(false)
                _serviceState.value = UserServiceState.NOT_BOUND
            }
        }
        runCatching {
            Shizuku.addRequestPermissionResultListener { _, grantResult ->
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    workspaceWarning.onStatus(ShizukuState.GRANTED)
                    _state.value = ShizukuState.GRANTED
                    bindUserService()
                }
            }
        }

        // The Shizuku binder may not be connected yet during Application.onCreate.
        // Retry after a short delay and then once more a bit later.
        scope.launch {
            delay(2_000)
            refresh()
            if (_state.value == ShizukuState.NOT_RUNNING) {
                delay(5_000)
                refresh()
            }
        }
    }

    fun refresh() {
        val ping = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val hasPermission = ping && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val s = when {
            hasPermission -> ShizukuState.GRANTED
            ping -> ShizukuState.RUNNING_NO_PERMISSION
            else -> ShizukuState.NOT_RUNNING
        }
        workspaceWarning.onStatus(s)
        _state.value = s
        if (s == ShizukuState.GRANTED) bindUserService()
    }

    fun isGranted(): Boolean = _state.value == ShizukuState.GRANTED

    fun requestPermission() {
        val granted = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (granted) {
            workspaceWarning.onStatus(ShizukuState.GRANTED)
            _state.value = ShizukuState.GRANTED
            bindUserService()
            return
        }
        runCatching { Shizuku.requestPermission(0) }
    }

    /** Recheck the live binder instead of trusting the last displayed status. */
    internal fun checkWorkspaceWarning() {
        refresh()
        workspaceWarning.checkWorkspace(_state.value)
    }

    /**
     * Binds the in-server user service so privilege exec works. Requires Shizuku
     * API >= 10; silently degrades to Unavailable otherwise (callers fall back).
     */
    fun bindUserService() {
        if (service != null) return
        val hasPermission = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!hasPermission) return
        if (!bindRequested.compareAndSet(false, true)) return
        val ok = runCatching {
            Shizuku.getVersion() >= 10
        }.getOrDefault(false)
        if (!ok) {
            bindRequested.set(false)
            _serviceState.value = UserServiceState.BIND_FAILED
            return
        }
        runCatching {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        }.onFailure {
            bindRequested.set(false)
            _serviceState.value = UserServiceState.BIND_FAILED
        }
    }

    fun unbindUserService() {
        if (service == null && !bindRequested.get()) return
        service = null
        bindRequested.set(false)
        runCatching {
            Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
        }
        _serviceState.value = UserServiceState.NOT_BOUND
    }

    /** True when the in-server service is connected and callable. */
    fun isServiceReady(): Boolean = service != null

    /**
     * Runs [cmd] inside the Shizuku server process (shell/root uid). Returns null
     * when the user service is unavailable so callers can fall back to the app uid.
     */
    suspend fun runPrivileged(
        cmd: Array<String>,
        env: Array<String>?,
        dir: String?,
        timeoutMs: Int,
        maxBytes: Int = 60_000,
    ): PrivilegedResult? = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext null
        val raw = runCatching {
            svc.exec(cmd, withDeployPaths(env), dir, maxBytes, timeoutMs)
        }.getOrNull() ?: return@withContext null
        parseResult(raw)
    }

    /**
     * Adds this build's deployed paths to the environment of every privileged
     * command. The service runs inside Shizuku's process and has no way to know
     * which build called it, so it takes these from the request rather than
     * assuming the legacy shared directory, which another installed build may
     * well have deployed over.
     */
    private fun withDeployPaths(env: Array<String>?): Array<String> {
        val extra = arrayOf(
            "HARNESS_TMP_PREFIX=$tmpBase/linux",
            "HARNESS_TMP_LIB=$tmpBase/linux/lib",
            "HARNESS_TMP_BIN=$tmpBase/linux/bin",
        )
        if (env == null) return extra
        val kept = env.filterNot { entry ->
            extra.any { it.substringBefore('=') == entry.substringBefore('=') }
        }
        return (extra.asList() + kept).toTypedArray()
    }

    private fun parseResult(raw: String): PrivilegedResult {
        val lines = raw.split('\n', limit = 3)
        val exit = lines.getOrNull(0)?.removePrefix("exit=")?.toIntOrNull() ?: -1
        val timedOut = lines.getOrNull(1)?.removePrefix("timeout=")?.toIntOrNull() == 1
        val body = lines.getOrNull(2) ?: ""
        val sep = HarnessUserService.STDERR_SEPARATOR
        val sepIdx = body.indexOf(sep)
        return if (sepIdx >= 0) {
            val stdoutPart = body.substring(0, sepIdx).trimEnd('\n')
            val stderrPart = body.substring(sepIdx + sep.length).removePrefix("\n")
            PrivilegedResult(exit, timedOut, stdoutPart, stderrPart)
        } else {
            PrivilegedResult(exit, timedOut, body)
        }
    }

    /**
     * Deploys (or refreshes) the shell-user toolchain at
     * this build's own directory under /data/local/tmp/androidharness by
     * untarring the staging tarball
     * (shared storage, shell-readable) there. No-op when the deployed copy
     * already matches [tag], which is the package set plus the content hash of
     * the tarball it must have come from.
     */
    suspend fun ensureTmpPrefix(stagingTarPath: String, tag: String): Boolean {
        val base = tmpBase
        // Bug 1 fix: locate the staged CA bundle (next to the tarball) so the
        // deployed toolchain gets trust anchors; fall back gracefully.
        val stagingDir = File(stagingTarPath).parentFile
        val caSource = File(stagingDir, "etc/tls/cacert.pem")
        val caInstall = if (caSource.isFile) {
            "mkdir -p \"$base/linux/etc/tls\" && cp \"${caSource.absolutePath}\" " +
                "\"$base/linux/etc/tls/cacert.pem\" && "
        } else ""
        val check = runPrivileged(
            arrayOf("/system/bin/sh", "-c", "test -x \"$base/linux/bin/bash\" && cat \"$base/.harness-hash\""),
            env = null, dir = null, timeoutMs = 15_000, maxBytes = 2_000,
        ) ?: return false
        if (check.exitCode == 0 && check.output.trim() == tag) {
            tmpPrefixDeployed = true
            deployedTag = tag
            deployCheckForced = false
            return true
        }
        deploying = true
        try {
            val script = buildString {
                append("rm -rf \"$base\" && ")
                append("mkdir -p \"$base\" && ")
                append("tar -xzf \"$stagingTarPath\" -C \"$base\" && ")
                append(caInstall)
                append("chmod -R 755 \"$base\" && ")
                // /data/local/tmp is o+x-traversable and the blanket 755 leaves
                // everything group/other-readable: without this, ANY app that
                // guesses the path could read the token-bearing copies below.
                append("chmod 700 \"$base\" \"$base/linux\" && ")
                // The gh config dirs were left 0777 by the blanket 755; the token
                // FILES inside are 0600, but the dirs cost nothing to tighten.
                append("chmod 700 \"$base/linux/home/.config\" \"$base/linux/home/.config/gh\" 2>/dev/null; ")
                append("chmod 600 \"$base/linux/home/.gh-token\" " +
                    "\"$base/linux/home/.config/gh/hosts.yml\" " +
                    "\"$base/linux/etc/gitconfig\" 2>/dev/null; ")
                append("echo '$tag' > \"$base/.harness-hash\" && ")
                append("test -x \"$base/linux/bin/bash\" && ")
                append("echo DEPLOY_OK")
            }
            val r = runPrivileged(
                arrayOf("/system/bin/sh", "-c", script),
                env = null,
                dir = null,
                timeoutMs = 300_000,
                maxBytes = 20_000,
            )
            val ok = r != null && r.exitCode == 0 && r.output.contains("DEPLOY_OK")
            if (ok) {
                tmpPrefixDeployed = true
                deployedTag = tag
            }
            deployCheckForced = false
            return ok
        } finally {
            deploying = false
        }
    }

    /**
     * Whether the shell-user toolchain can be executed from /data/local/tmp.
     *
     * Pass [expectedTag] (the tag the caller's environment was built for, from
     * `LinuxEnvironmentManager.deployedTag()`) to also require the deployed copy
     * to have come from those exact bytes. Anything that runs the deployed
     * binaries with the app-side environment must ask the strict question: a
     * copy that only answers "bin/bash exists" can be missing the very
     * libraries that environment names, and the linker then refuses every
     * binary with `CANNOT LINK EXECUTABLE ... library "..." not found`.
     */
    suspend fun isTmpPrefixDeployed(expectedTag: String? = null): Boolean {
        // A cached success only counts for the tag it was deployed from;
        // anything else has to be re-checked (and re-deployed by
        // ensureTmpPrefix) before the tier may exec from the copy.
        if (tmpPrefixDeployed && !deployCheckForced &&
            (expectedTag == null || deployedTag == expectedTag)
        ) return true
        val base = tmpBase
        val script = if (expectedTag != null) {
            "test -x \"$base/linux/bin/bash\" && cat \"$base/.harness-hash\""
        } else {
            "test -x \"$base/linux/bin/bash\" && echo OK"
        }
        val r = runPrivileged(
            arrayOf("/system/bin/sh", "-c", script),
            env = null,
            dir = null,
            timeoutMs = 10_000,
            maxBytes = 2_000,
        )
        val matched = r != null && r.exitCode == 0 &&
            if (expectedTag != null) r.output.trim() == expectedTag else r.output.contains("OK")
        if (matched && !deployCheckForced) {
            // A forced check stays un-cached: callers must go through
            // ensureTmpPrefix so the redeploy actually happens.
            tmpPrefixDeployed = true
            if (expectedTag != null) deployedTag = expectedTag
        } else {
            tmpPrefixDeployed = false
            deployedTag = null
        }
        return tmpPrefixDeployed
    }

    /** Checks a path from the privileged side (existence + listable). */
    suspend fun privilegedCanAccess(path: String): Boolean {
        val r = runPrivileged(
            arrayOf("/system/bin/sh", "-c", "test -d '$path' && ls -l \"$path\" >/dev/null 2>&1 && echo OK"),
            env = null,
            dir = null,
            timeoutMs = 10_000,
            maxBytes = 2_000,
        )
        return r != null && r.exitCode == 0 && r.output.contains("OK")
    }

    // ------------------------------------------------------------------
    // Detached background processes (live in the Shizuku server process,
    // so they survive the app being killed)
    // ------------------------------------------------------------------

    /** Returns the pid, or null when the user service is unavailable. */
    suspend fun spawnDetached(
        cmd: Array<String>,
        env: Array<String>?,
        dir: String?,
        logPath: String,
    ): Int? = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext null
        runCatching { svc.spawnDetached(cmd, env, dir, logPath) }.getOrNull()
    }

    suspend fun isProcessAlive(pid: Int): Boolean = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext false
        runCatching { svc.isProcessAlive(pid) }.getOrDefault(false)
    }

    suspend fun killProcess(pid: Int): Boolean = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext false
        runCatching { svc.killProcess(pid) }.getOrDefault(false)
    }
}
