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
 * that executes shell commands as the shell (or root) uid — the only reliable
 * way to reach system paths, pm/am, and any folder on the device on modern
 * Android. Also deploys a copy of the app's Linux toolchain to
 * /data/local/tmp/androidharness (a location that uid can exec from).
 */
class ShizukuManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {

    private val _state = MutableStateFlow(ShizukuState.NOT_INSTALLED)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val _serviceState = MutableStateFlow(UserServiceState.NOT_BOUND)
    val serviceState: StateFlow<UserServiceState> = _serviceState.asStateFlow()

    @Volatile private var service: IHarnessService? = null
    private val bindRequested = AtomicBoolean(false)

    @Volatile private var tmpPrefixDeployed = false

    private val isDebuggable: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val appVersionCode: Int = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
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
            // The server may have restarted — rebind once it is back.
            scope.launch {
                delay(1_500)
                refresh()
            }
        }
    }

    init {
        refresh()

        Shizuku.addBinderReceivedListener { refresh() }
        Shizuku.addBinderDeadListener {
            _state.value = ShizukuState.NOT_RUNNING
            service = null
            bindRequested.set(false)
            _serviceState.value = UserServiceState.NOT_BOUND
        }
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                _state.value = ShizukuState.GRANTED
                bindUserService()
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
        val s = when {
            !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.GRANTED
            else -> ShizukuState.RUNNING_NO_PERMISSION
        }
        _state.value = s
        if (s == ShizukuState.GRANTED) bindUserService()
    }

    fun isGranted(): Boolean = _state.value == ShizukuState.GRANTED

    fun requestPermission() {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            _state.value = ShizukuState.GRANTED
            bindUserService()
            return
        }
        Shizuku.requestPermission(0)
    }

    /**
     * Binds the in-server user service so privilege exec works. Requires Shizuku
     * API >= 10; silently degrades to Unavailable otherwise (callers fall back).
     */
    fun bindUserService() {
        if (service != null) return
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
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
            svc.exec(cmd, env, dir, maxBytes, timeoutMs)
        }.getOrNull() ?: return@withContext null
        parseResult(raw)
    }

    private fun parseResult(raw: String): PrivilegedResult {
        val lines = raw.split('\n', limit = 3)
        val exit = lines.getOrNull(0)?.removePrefix("exit=")?.toIntOrNull() ?: -1
        val timedOut = lines.getOrNull(1)?.removePrefix("timeout=")?.toIntOrNull() == 1
        val body = lines.getOrNull(2) ?: ""
        // Newer services separate stderr with a marker line; older ones
        // return a single merged stream (fall back to treating it as stdout).
        val sep = HarnessUserService.STDERR_SEPARATOR
        val sepLine = "\n$sep\n"
        val sepIdx = body.indexOf(sepLine)
        return when {
            sepIdx >= 0 -> PrivilegedResult(
                exit,
                timedOut,
                body.substring(0, sepIdx),
                body.substring(sepIdx + sepLine.length),
            )
            body.startsWith("$sep\n") -> PrivilegedResult(exit, timedOut, "", body.substring(sep.length + 1))
            else -> PrivilegedResult(exit, timedOut, body)
        }
    }

    /**
     * Deploys (or refreshes) the shell-user toolchain at
     * /data/local/tmp/androidharness/linux by untarring the staging tarball
     * (shared storage, shell-readable) there. No-op when the deployed copy
     * already matches [hash].
     */
    suspend fun ensureTmpPrefix(stagingTarPath: String, hash: String): Boolean {
        val base = LinuxEnvironmentManager.TMP_PREFIX_BASE
        val check = runPrivileged(
            arrayOf("/system/bin/sh", "-c", "test -x \"$base/linux/bin/bash\" && cat \"$base/.harness-hash\""),
            env = null, dir = null, timeoutMs = 15_000, maxBytes = 2_000,
        ) ?: return false
        if (check.exitCode == 0 && check.output.trim() == hash) {
            tmpPrefixDeployed = true
            return true
        }
        val script = buildString {
            append("rm -rf \"$base\" && ")
            append("mkdir -p \"$base\" && ")
            append("tar -xzf \"$stagingTarPath\" -C \"$base\" && ")
            append("chmod -R 755 \"$base\" && ")
            append("echo '$hash' > \"$base/.harness-hash\" && ")
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
        if (ok) tmpPrefixDeployed = true
        return ok
    }

    /** Whether the shell-user toolchain currently exists at /data/local/tmp. */
    suspend fun isTmpPrefixDeployed(): Boolean {
        if (tmpPrefixDeployed) return true
        val base = LinuxEnvironmentManager.TMP_PREFIX_BASE
        val r = runPrivileged(
            arrayOf("/system/bin/sh", "-c", "test -x \"$base/linux/bin/bash\" && echo OK"),
            env = null,
            dir = null,
            timeoutMs = 10_000,
            maxBytes = 2_000,
        )
        if (r != null && r.exitCode == 0 && r.output.contains("OK")) {
            tmpPrefixDeployed = true
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
