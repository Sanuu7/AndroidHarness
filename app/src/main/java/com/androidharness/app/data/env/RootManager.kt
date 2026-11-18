package com.androidharness.app.data.env

import android.content.*
import android.os.IBinder
import com.androidharness.app.IHarnessService
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock

internal enum class RootStatus(val label: String) {
    NOT_CHECKED("Not checked"), REQUESTING("Requesting access…"), READY("Ready · UID 0"), DENIED("Access denied"), UNAVAILABLE("Unavailable")
}
internal data class RootState(
    val status: RootStatus = RootStatus.NOT_CHECKED,
    val detail: String = "Enable root to request access.",
    val checkedAt: Long = 0,
    val provider: RootProvider? = null,
    val providerEvidence: String? = null,
)

class RootManager(private val context: Context) {
    companion object { private const val RECONNECT_INTERVAL_MS = 60_000L }
    private val installedPackages: Set<String> by lazy {
        runCatching {
            context.packageManager.getInstalledPackages(0).mapTo(mutableSetOf()) { it.packageName }
        }.getOrDefault(emptySet())
    }
    private val kernelVersion: String by lazy {
        runCatching { java.io.File("/proc/version").readText() }.getOrDefault("")
    }
    private val mutableState = MutableStateFlow(RootState())
    internal val state = mutableState.asStateFlow()
    private val connectedState = MutableStateFlow(false)
    /** True only while a bound runner is attached; cleared the moment the binding is lost. */
    internal val connected = connectedState.asStateFlow()
    @Volatile internal var service: IHarnessService? = null
        private set(value) { field = value; connectedState.value = value != null }

    // Kept in no-backup storage: a grant belongs to this device's root manager only.
    private val grantMarker = java.io.File(context.noBackupFilesDir, "root_granted")
    @Volatile private var lastReconnectAttempt = 0L

    /** Package-only detection, so the settings card can name the manager before any prompt. */
    internal suspend fun refreshManagerDetection() = withContext(Dispatchers.IO) {
        if (mutableState.value.status == RootStatus.READY || mutableState.value.status == RootStatus.REQUESTING) return@withContext
        val detection = RootProviderDetector.classify(installedPackages, kernelVersion, emptySet())
        mutableState.value = mutableState.value.copy(
            detail = detection.provider?.let { "${it.label} detected. Grant root access to verify." }
                ?: "Enable root to request access.",
            provider = detection.provider,
            providerEvidence = detection.evidence.takeIf { detection.provider != null },
        )
    }
    @Volatile private var connection: ServiceConnection? = null
    private var waiting: CompletableDeferred<IHarnessService>? = null
    private val requestLock = kotlinx.coroutines.sync.Mutex()

    internal suspend fun requestAccess(): Boolean {
        requestLock.lock()
        android.util.Log.d("HarnessRoot", "requestAccess: start")
        try {
            mutableState.value = mutableState.value.copy(
                status = RootStatus.REQUESTING, detail = "Waiting for your root manager.", checkedAt = 0,
            )
            return try {
                val existing = service
                if (existing != null && verify()) return true
                bindRunner(resumeCommands = true)
                runCatching { grantMarker.createNewFile() }
                android.util.Log.d("HarnessRoot", "grant: root service bound and verified")
                true
            } catch (e: CancellationException) {
                withContext(NonCancellable) { disconnect() }
                mutableState.value = mutableState.value.copy(
                    status = RootStatus.UNAVAILABLE,
                    detail = "Root request ended before verification.",
                    checkedAt = System.currentTimeMillis(),
                )
                throw e
            }
            catch (e: Exception) {
                android.util.Log.w("HarnessRoot", "requestAccess failed", e)
                disconnect()
                runCatching { grantMarker.delete() }
                mutableState.value = mutableState.value.copy(
                    status = RootStatus.DENIED,
                    detail = e.message ?: "Root access is unavailable.",
                    checkedAt = System.currentTimeMillis(),
                )
                false
            } finally { waiting = null }
        } finally { requestLock.unlock() }
    }

    /**
     * Returns true when a verified runner is attached, re-binding it first when an
     * earlier grant exists but the runner process is gone (app restart, or the
     * `:root` process was reaped). Re-binds at most once per [RECONNECT_INTERVAL_MS]
     * unless [force] is set, and never runs before the user has granted root once.
     */
    internal suspend fun ensureConnected(resumeCommands: Boolean, force: Boolean = false): Boolean {
        if (service != null && verify()) return true
        if (!grantMarker.exists()) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && lastReconnectAttempt != 0L && now - lastReconnectAttempt < RECONNECT_INTERVAL_MS) return false
        return requestLock.withLock {
            // A grant or another reconnect may have finished while this one waited.
            if (service != null && verify()) return@withLock true
            lastReconnectAttempt = android.os.SystemClock.elapsedRealtime()
            android.util.Log.d("HarnessRoot", "ensureConnected: re-binding the root runner")
            mutableState.value = mutableState.value.copy(
                status = RootStatus.REQUESTING, detail = "Reconnecting to the root runner.", checkedAt = 0,
            )
            try {
                bindRunner(resumeCommands)
                true
            } catch (e: CancellationException) {
                withContext(NonCancellable) { disconnect() }
                unavailable("Reconnecting to the root runner was interrupted. Tap Grant root access to connect.")
                throw e
            } catch (e: Exception) {
                android.util.Log.w("HarnessRoot", "ensureConnected failed", e)
                disconnect()
                unavailable("The root runner stopped and could not reconnect" +
                    (e.message?.let { ": $it" } ?: ".") + " Tap Grant root access to connect.")
                false
            } finally { waiting = null }
        }
    }

    /** Opens the su shell, binds the runner and verifies it. Callers hold [requestLock]. */
    private suspend fun bindRunner(resumeCommands: Boolean) {
        disconnect()
        withContext(Dispatchers.IO) {
            Shell.getCachedShell()?.takeIf { !it.isRoot }?.close()
            Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(15))
            check(Shell.getShell().isRoot) { "Root was denied or no working su provider is available." }
        }
        android.util.Log.d("HarnessRoot", "bindRunner: su shell reports root")
        val deferred = CompletableDeferred<IHarnessService>()
        waiting = deferred
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                // Catches a reaped runner even when no disconnect callback arrives.
                runCatching { binder.linkToDeath({ lost(this, "The root service stopped.") }, 0) }
                deferred.complete(IHarnessService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) { lost(this, "The root service disconnected.") }
            override fun onBindingDied(name: ComponentName) { lost(this, "The root service stopped.") }
            override fun onNullBinding(name: ComponentName) { deferred.completeExceptionally(IllegalStateException("Root service could not start.")) }
        }
        connection = conn
        withContext(Dispatchers.Main) { RootService.bind(Intent(context, RootHarnessService::class.java), conn) }
        service = withTimeout(20_000) { deferred.await() }
        // A rebind can reuse a service instance that was stopped earlier.
        if (resumeCommands) runCatching { service?.resumeCommands() }
        check(verify()) { "The connected runner did not verify root access." }
    }

    /** Ignores callbacks from a binding that was already replaced or unbound. */
    private fun lost(conn: ServiceConnection, detail: String) {
        if (connection !== conn) return
        android.util.Log.w("HarnessRoot", detail)
        unavailable("$detail Tap Grant root access to reconnect.")
    }

    /** Never invokes su or prompts: verify only the already connected service. */
    internal suspend fun verify(): Boolean = withContext(Dispatchers.IO) {
        val svc = service
        if (svc == null) {
            unavailable("No active root connection. Tap Grant root access to connect.")
            return@withContext false
        }
        // The exact identity probe stays allowed even while commands are paused.
        val identity = runCatching {
            parsePrivilegedResult(svc.exec(arrayOf("/system/bin/sh", "-c", "id -u"), null, null, 1024, 2_000))
        }.onFailure { android.util.Log.w("HarnessRoot", "identity probe failed", it) }.getOrNull()
        val serviceUid = runCatching { svc.uid }.getOrNull()
        val ready = identity?.exitCode == 0 && identity.output.trim() == "0" &&
            identity.let { runCatching { svc.asBinder().pingBinder() }.getOrDefault(false) } && serviceUid == 0
        android.util.Log.d("HarnessRoot",
            "verify exit=${identity?.exitCode} output=${identity?.output?.trim()?.take(60)} uid=$serviceUid ready=$ready stderr=${identity?.stderr?.trim()?.take(160)}")
        if (!ready) {
            val reason = identity?.stderr?.trim()?.takeIf { it.isNotEmpty() }
            unavailable("Root service is connected but identity verification failed" + (reason?.let { ": $it" } ?: "."))
            return@withContext false
        }
        // Detection is best effort; an unreadable marker path must never turn a
        // verified connection into a failure.
        val markers = runCatching {
            val probe = parsePrivilegedResult(svc.exec(
                arrayOf("/system/bin/sh", "-c", RootProviderDetector.markerScript), null, null, 4096, 5_000))
            if (probe.exitCode == 0) probe.output.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() }.map { it.uppercase() }.toSet() else emptySet()
        }.onFailure { android.util.Log.w("HarnessRoot", "root manager detection failed", it) }.getOrDefault(emptySet())
        val detection = RootProviderDetector.classify(installedPackages, kernelVersion, markers)
        android.util.Log.d("HarnessRoot", "verify detected=${detection.provider?.label} via=${detection.evidence} markers=$markers")
        mutableState.value = RootState(
            RootStatus.READY,
            "${detection.provider?.label ?: "Root"} · identity verified by the running command service.",
            System.currentTimeMillis(),
            detection.provider,
            detection.evidence,
        )
        true
    }

    /**
     * The direct grant was denied, but privileged commands can still run through a
     * root Shizuku. Say so instead of leaving the stale "denied" reason next to a
     * working fallback.
     */
    internal fun explainShizukuFallback() {
        if (mutableState.value.status == RootStatus.READY) return
        mutableState.value = mutableState.value.copy(
            status = RootStatus.UNAVAILABLE,
            detail = "Direct root was denied. Privileged commands run through Shizuku (UID 0) instead.",
            checkedAt = System.currentTimeMillis(),
        )
    }

    internal suspend fun run(cmd: Array<String>, env: Array<String>?, dir: String?, timeoutMs: Int, maxBytes: Int): PrivilegedResult? = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext null
        // Null means dispatch never happened. A binder failure after dispatch is an uncertain result.
        try { parsePrivilegedResult(svc.execCancellable(cmd, env, dir, maxBytes, timeoutMs)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { unavailable("Root connection lost during execution."); PrivilegedResult(-1, false, "", "Root connection lost after dispatch. The command may have run; it was not retried.") }
    }

    internal suspend fun stop(): String = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext "Root: no connected runner; termination cannot be verified."
        runCatching {
            val count = svc.cancelActive()
            repeat(30) { if (svc.activeCommandCount() == 0) return@withContext "Root: stopped $count active commands."; delay(100) }
            "Root: stop requested for $count commands; termination is not confirmed."
        }.getOrElse { "Root: termination could not be verified (${it.message})." }
    }
    internal suspend fun resume() = withContext(Dispatchers.IO) { service?.resumeCommands(); Unit }
    internal suspend fun disconnect() {
        service = null
        val conn = connection; connection = null
        if (conn != null) withContext(Dispatchers.Main) { runCatching { RootService.unbind(conn) } }
    }
    private fun unavailable(detail: String) {
        service = null
        mutableState.value = mutableState.value.copy(
            status = RootStatus.UNAVAILABLE, detail = detail, checkedAt = System.currentTimeMillis(),
        )
    }
}

internal fun parsePrivilegedResult(raw: String): PrivilegedResult {
    val parts = raw.split('\n', limit = 3)
    val body = parts.getOrElse(2) { "" }
    val split = body.split(HarnessUserService.STDERR_SEPARATOR, limit = 2)
    return PrivilegedResult(parts[0].removePrefix("exit=").toIntOrNull() ?: -1,
        parts.getOrNull(1)?.removePrefix("timeout=") == "1", split[0].trimEnd('\n'), split.getOrElse(1) { "" }.trimStart('\n'))
}
