package com.androidharness.app.data.env

import android.content.Context
import com.androidharness.app.tools.ToolResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class AccessRequest(
    val projectId: String?, val sessionId: String?, val requireRoot: Boolean = false,
    val forceDevice: Boolean = false, val manual: Boolean = false, val requireShizuku: Boolean = false,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AccessRequest>
    val rootLease = RootLease()
    val receipts = mutableListOf<ExecutionReceipt>()
}

internal class VerifiedDeviceAccess(val backend: DeviceBackend, val uid: Int?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<VerifiedDeviceAccess>
}

class DeviceAccessManager(
    private val context: Context,
    val shizuku: ShizukuManager,
    val linuxEnv: LinuxEnvironmentManager,
    val root: RootManager = RootManager(context),
    private val projectForSession: suspend (String) -> String? = { null },
    private val dhizuku: DhizukuManager? = null,
) {
    val store = DeviceAccessStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val preferences = store.preferences.stateIn(scope, SharingStarted.Eagerly, AccessPreferences())
    private val stopped = MutableStateFlow(false)
    val paused = stopped.asStateFlow()
    private val allowance = MutableStateFlow("Root use is disabled")
    val allowanceLabel = allowance.asStateFlow()
    private val last = MutableStateFlow("No command has run yet.")
    val lastExecution = last.asStateFlow()
    private val szUid = MutableStateFlow<Int?>(null)
    val shizukuUid = szUid.asStateFlow()
    private val temporaryRoot = TemporaryRootAllowance()
    private val active = MutableStateFlow(0)
    val activeCommands = active.asStateFlow()

    init {
        scope.launch {
            combine(shizuku.state, shizuku.serviceState) { _, _ -> shizuku.isServiceReady() }
                .collect { if (!it) szUid.value = null }
        }
        // Package-only; never invokes su. The full check runs after a grant.
        scope.launch { root.refreshManagerDetection() }
    }

    suspend fun grantRoot(projectId: String?, sessionId: String?): Boolean {
        val prefs = store.preferences.first()
        if (prefs.rootUse == RootUse.CHAT && sessionId == null) {
            allowance.value = "Open a chat before enabling root for the current chat."
            return false
        }
        val directRoot = root.requestAccess()
        if (!directRoot) {
            if (probeShizuku() != 0) return false
            root.explainShizukuFallback()
        }
        if (projectId != null) store.allowProject(projectId, true)
        if (prefs.temporaryRoot) enableTemporaryUse(prefs.rootUse, sessionId)
        else allowance.value = "Temporary mode off · root is allowed in every project"
        return resume() == null
    }
    fun enableTemporaryUse(use: RootUse, sessionId: String?) {
        temporaryRoot.grant(use, sessionId)
        allowance.value = when (use) {
            RootUse.SESSION -> "Allowed for this app session"
            RootUse.CHAT -> "Allowed for the selected chat"
            RootUse.NEXT_ACTION -> "Allowed for the next action"
        }
    }
    fun disableRootUse() { temporaryRoot.revoke(); allowance.value = "Root use is disabled" }

    /** True when the current chat/session may use the connected root service right now. */
    fun rootUseActive(sessionId: String?, prefs: AccessPreferences): Boolean =
        !prefs.temporaryRoot || temporaryRoot.activeFor(sessionId)
    suspend fun resume(): String? = try {
        root.resume(); shizuku.resumeCommands(); dhizuku?.resumeCommands(); stopped.value = false; null
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { "Could not resume privileged access: ${e.message}" }

    private fun rootAllowed(request: AccessRequest, prefs: AccessPreferences): Boolean =
        DeviceAccessPolicy.rootPermitted(
            temporaryMode = prefs.temporaryRoot,
            manual = request.manual,
            projectAllowed = request.projectId in prefs.rootProjects,
            allowanceActive = temporaryRoot.allows(request.sessionId, request.rootLease),
        )
    private suspend fun claimRoot(request: AccessRequest): Boolean {
        if (stopped.value) return false
        val prefs = store.preferences.first()
        // Unlimited mode still requires a live root connection and an unpaused
        // service; it only removes the per-project and per-use limits.
        if (!prefs.temporaryRoot) return true
        if (!rootAllowed(request, prefs)) return false
        val claimed = temporaryRoot.claim(request.sessionId, request.rootLease)
        if (claimed && prefs.rootUse == RootUse.NEXT_ACTION) allowance.value = "Next-action allowance used"
        return claimed
    }
    private suspend fun probeShizuku(): Int? {
        if (!shizuku.hasPrivilegedAccess()) { szUid.value = null; return null }
        val probe = shizuku.runPrivileged(arrayOf("/system/bin/sh", "-c", "id -u"), null, null, 2_000, 1024)
        return probe?.takeIf { it.exitCode == 0 }?.output?.trim()?.toIntOrNull()?.takeIf { it == 0 || it == 2000 }.also { szUid.value = it }
    }

    internal suspend fun <T> withRequest(
        projectId: String?, sessionId: String?, requireRoot: Boolean = false, forceDevice: Boolean = false,
        manual: Boolean = false, requireShizuku: Boolean = false, block: suspend () -> T,
    ): T = withContext(AccessRequest(projectId ?: sessionId?.let { projectForSession(it) }, sessionId, requireRoot, forceDevice, manual, requireShizuku)) { block() }

    suspend fun traceTool(projectId: String?, sessionId: String?, requireRoot: Boolean = false, requireShizuku: Boolean = false, block: suspend () -> ToolResult): ToolResult =
        withRequest(projectId, sessionId, requireRoot, requireShizuku = requireShizuku) {
            val request = currentCoroutineContext()[AccessRequest]!!
            val result = block()
            result.copy(execution = result.execution + request.receipts.distinct())
        }

    internal suspend fun select(appWorkspace: Boolean = false, excluded: Set<DeviceBackend> = emptySet()): AccessDecision {
        val request = currentCoroutineContext()[AccessRequest] ?: AccessRequest(null, null)
        val prefs = store.preferences.first()
        if (appWorkspace && !request.forceDevice && !request.requireRoot && !request.requireShizuku) return AccessDecision(DeviceBackend.NORMAL)
        val allowed = rootAllowed(request, prefs)
        val rootReady = !request.requireShizuku && prefs.mode in setOf(AccessMode.AUTO, AccessMode.ROOT, AccessMode.BOTH) && allowed &&
            DeviceBackend.ROOT !in excluded && root.service != null && root.verify()
        val uid = if (prefs.mode == AccessMode.NORMAL || DeviceBackend.SHIZUKU in excluded) null else probeShizuku()
        return DeviceAccessPolicy.choose(prefs.mode, prefs.autoFallback, rootReady, uid,
            allowed, stopped.value, appWorkspace && !request.forceDevice, request.requireRoot, request.requireShizuku)
    }

    /** A null callback result means dispatch never began. Exceptions/results are never replayed. */
    internal suspend fun execute(
        appWorkspace: Boolean = false,
        normal: suspend () -> ShellRunResult,
        shizukuRun: suspend () -> ShellRunResult?,
        rootRun: suspend () -> ShellRunResult?,
    ): ShellRunResult {
        val request = currentCoroutineContext()[AccessRequest] ?: AccessRequest(null, null)
        val excluded = mutableSetOf<DeviceBackend>()
        while (true) {
            val decision = select(appWorkspace, excluded)
            val backend = decision.backend ?: return ShellRunResult(-1, false, "", decision.reason ?: "Selected access is unavailable.", ExecutionTier.TOYBOX, null)
            if ((backend == DeviceBackend.ROOT || request.requireRoot) && !claimRoot(request)) {
                excluded += backend
                continue
            }
            val receipt = ExecutionReceipt(backend, decision.uid, decision.reason,
                transport = if (backend == DeviceBackend.SHIZUKU) shizuku.transportLabel else null)
            active.update { it + 1 }
            val result = try {
                withContext(VerifiedDeviceAccess(backend, decision.uid)) {
                    when (backend) { DeviceBackend.DHIZUKU -> error("Device Owner requires its explicit tool"); DeviceBackend.NORMAL -> normal(); DeviceBackend.SHIZUKU -> shizukuRun(); DeviceBackend.ROOT -> rootRun() }
                }
            } finally { active.update { (it - 1).coerceAtLeast(0) } }
            if (result == null) {
                excluded += backend
                continue
            }
            synchronized(request.receipts) { request.receipts += receipt }
            last.value = receipt.label + (decision.reason?.let { " · $it" } ?: "")
            return result.copy(note = listOfNotNull(result.note, decision.reason?.let { "[Access fallback: $it; using ${receipt.label}]" }).joinToString("\n").ifBlank { null })
        }
    }

    internal fun isAppWorkspace(cwd: File): Boolean = PathClassifier.regionOf(cwd.path, linuxEnv.internalDataRoot.path, context.getExternalFilesDir(null)?.path) == PathClassifier.Region.APP_DATA

    /**
     * True when ordinary commands in [cwd] run as the app user. The private
     * data dir always does, since the ADB shell user cannot enter it; other
     * app-owned folders follow the access mode once the user allows it.
     */
    internal suspend fun pinsAppUser(cwd: File): Boolean {
        if (PathClassifier.regionOf(cwd.path, linuxEnv.internalDataRoot.path) == PathClassifier.Region.APP_DATA) return true
        return isAppWorkspace(cwd) && !store.preferences.first().appWorkspacePrivileged
    }

    internal suspend fun liveTier(cwd: File): ExecutionTier = when (select(pinsAppUser(cwd)).backend) {
        DeviceBackend.ROOT -> ExecutionTier.ROOT
        DeviceBackend.SHIZUKU -> ExecutionTier.PRIVILEGED
        else -> if (linuxEnv.isReady) ExecutionTier.APP_LINUX else ExecutionTier.TOYBOX
    }

    internal suspend fun environmentForAgent(projectId: String?, sessionId: String?): AgentDeviceEnvironment {
        val prefs = store.preferences.first()
        val project = projectId ?: sessionId?.let { projectForSession(it) }
        val state = root.state.value
        return AgentDeviceEnvironment(prefs,
            rootReady = state.status == RootStatus.READY && root.service != null,
            rootState = state.status.label + state.provider?.let { " (${it.label})" }.orEmpty(),
            rootAllowed = rootAllowed(AccessRequest(project, sessionId), prefs),
            shizukuState = shizuku.state.value, shizukuService = shizuku.serviceState.value,
            shizukuReady = shizuku.hasPrivilegedAccess(),
            // A live Wireless ADB runner is the authority for its own UID; the
            // probe value may still describe an earlier Shizuku connection.
            shizukuUid = if (shizuku.wirelessTransportActive) shizuku.liveUid ?: szUid.value
                else szUid.value ?: shizuku.liveUid,
            paused = stopped.value, wirelessPaired = shizuku.wirelessPaired,
            wirelessConnected = shizuku.wirelessConnected, wirelessActive = shizuku.wirelessTransportActive)
    }

    internal suspend fun runRoot(command: String, cwd: File, timeoutMs: Int, maxOutput: Int): ShellRunResult? {
        val builder = linuxEnv.shellProcessBuilder(command)
        val rootEnv = linuxEnv.processEnv() + mapOf("HARNESS_TMP_LIB" to "${linuxEnv.prefix}/lib", "HARNESS_TMP_BIN" to "${linuxEnv.prefix}/bin")
        val result = root.run(builder.command().toTypedArray(), rootEnv.map { "${it.key}=${it.value}" }.toTypedArray(), cwd.path, timeoutMs, maxOutput) ?: return null
        return ShellRunResult(result.exitCode, result.timedOut, result.output, result.stderr, ExecutionTier.ROOT, null)
    }

    /** A self-update is one manual action. Only an unavailable, undispatched call may fall back. */
    internal suspend fun installUpdate(apk: File, requested: DeviceBackend?, onDispatch: (DeviceBackend) -> Unit): Pair<DeviceBackend, PrivilegedResult>? =
        withRequest(null, null, requireRoot = requested == DeviceBackend.ROOT, requireShizuku = requested == DeviceBackend.SHIZUKU,
            forceDevice = true, manual = true) {
            val request = currentCoroutineContext()[AccessRequest]!!
            val excluded = if (requested == DeviceBackend.ROOT) setOf(DeviceBackend.SHIZUKU, DeviceBackend.NORMAL) else emptySet()
            val decision = select(excluded = excluded)
            val backend = decision.backend?.takeIf { it != DeviceBackend.NORMAL } ?: return@withRequest null
            if (backend == DeviceBackend.ROOT && !claimRoot(request)) return@withRequest null
            val command = com.androidharness.app.data.update.UpdateInstallPolicy.command(apk, android.os.Process.myUid() / 100000)
            active.update { it + 1 }
            try {
                withContext(VerifiedDeviceAccess(backend, decision.uid)) {
                    onDispatch(backend)
                    val result = if (backend == DeviceBackend.ROOT) root.run(command, null, null, 120_000, 4000)
                        else shizuku.runPrivileged(command, null, null, 120_000, 4000)
                    result?.let { backend to it }
                }
            } finally { active.update { (it - 1).coerceAtLeast(0) } }
        }

    suspend fun statusForAgent(workspace: com.androidharness.app.workspace.WorkspaceFs, sessionId: String?): String {
        val prefs = store.preferences.first()
        if (workspace is com.androidharness.app.workspace.SshFs) return "SSH commands run on the remote host. Local Android root/Shizuku grants do not grant root on that host."
        val project = (workspace as? com.androidharness.app.workspace.ChatWorkspaceFs)?.primaryId ?: sessionId?.let { projectForSession(it) }
        val allowed = rootAllowed(AccessRequest(project, sessionId), prefs)
        val modeHint = when (prefs.mode) {
            AccessMode.BOTH -> "Both mode prefers Shizuku for ordinary work and the direct root service for root requests, subject to the live connection and grant states above. Add access=\"root\" only to calls that need UID 0; do not switch the whole session just for one root call."
            AccessMode.AUTO -> "Auto prefers direct root when it is allowed and ready, then Shizuku, then the app shell."
            AccessMode.ROOT -> "Direct root is preferred. Shizuku or the app shell is used only when fallback is on and root is unavailable."
            AccessMode.SHIZUKU -> "Shizuku is preferred. The app shell is used only when fallback is on and Shizuku is unavailable."
            AccessMode.NORMAL -> "Only the app shell is used; root and Shizuku are not requested."
        }
        return (workspace as? com.androidharness.app.workspace.ChatWorkspaceFs)?.bindings?.joinToString("; ", postfix = ". ") {
            "${it.name}: root requests ${if (rootAllowed(AccessRequest(it.id, sessionId), prefs)) "allowed" else "not allowed"}"
        }.orEmpty() + "Device access: ${prefs.mode.label}; auto fallback ${if (prefs.mode == AccessMode.AUTO || prefs.autoFallback) "on" else "off"}; " +
            "temporary root mode ${if (prefs.temporaryRoot) "on" else "off (root allowed everywhere while connected)"}; " +
            "direct root ${root.state.value.status.label}${root.state.value.provider?.let { " (${it.label})" } ?: ""}; privileged shell ${if (shizuku.isServiceReady()) "ready (${szUid.value?.let { "UID $it" } ?: "identity checked before execution"}, ${shizuku.transportLabel})" else "unavailable"}; " +
            "root ${if (allowed) "allowed" else "not allowed"} for this project/chat; ${if (prefs.temporaryRoot) allowance.value else "temporary mode is off"}; privileged access ${if (stopped.value) "paused" else "enabled"}. " +
            (dhizuku?.let { "Device Owner: ${it.state.value.status}. Use device_owner_shell only for explicit owner-app execution; it does not provide ADB/root privileges. " } ?: "") +
            modeHint + " Shizuku uses its own authorization; a root-started Shizuku server runs as UID 0 even when direct root use is disabled. " +
            "App workspaces use the app user by default for builds and file work. Use shell access=\"shizuku\" for Android system commands such as /system/bin/am or /system/bin/cmd; it never falls back to direct root or the app UID. Use access=\"root\" only when UID 0 is required; " +
            (if (prefs.temporaryRoot) "project restrictions still apply. " else "root is available in every project while the connection is active. ") +
            "Do not invoke su or sudo directly; request root through the access parameter. " +
            "A dispatched command is never automatically replayed after a connection failure."
    }

    internal suspend fun <T> rootFiles(projectId: String?, sessionId: String?, action: suspend (RootFileClient) -> T): T =
        withRequest(projectId, sessionId, requireRoot = true, forceDevice = true, manual = true) {
            withContext(Dispatchers.IO) {
                val request = currentCoroutineContext()[AccessRequest]!!
                val decision = select()
                check(decision.uid == 0 && claimRoot(request)) { decision.reason ?: "Enable root use and select a root-capable access mode first." }
                val svc = if (decision.backend == DeviceBackend.ROOT) requireNotNull(root.service) { "Reconnect the root service first." } else null
                action(object : RootFileClient {
                    override suspend fun rootList(path: String, offset: Int): String = svc?.rootList(path, offset) ?: shizuku.rootFileOperation("rootList", listOf(path, offset.toString()))
                    override suspend fun rootRead(path: String): String = svc?.rootRead(path) ?: shizuku.rootFileOperation("rootRead", listOf(path))
                    override suspend fun rootWrite(path: String, expectedHash: String, text: String): String = svc?.rootWrite(path, expectedHash, text) ?: shizuku.rootFileOperation("rootWrite", listOf(path, expectedHash, text))
                })
            }
        }

    internal fun recordOwnerExecution() { last.value = "Dhizuku" }

    suspend fun checkConnections(): String {
        dhizuku?.checkConnection()
        root.verify()
        probeShizuku()
        return report()
    }
    fun report(): String = buildString {
        appendLine("AndroidHarness device access")
        appendLine("Mode: ${preferences.value.mode.label}; auto fallback: ${preferences.value.mode == AccessMode.AUTO || preferences.value.autoFallback}")
        appendLine("Direct root: ${root.state.value.status.label}${root.state.value.provider?.let { " (${it.label})" } ?: ""}")
        appendLine("Root manager: ${root.state.value.provider?.label ?: "Not detected"} (${root.state.value.providerEvidence ?: "no evidence"})")
        appendLine("Temporary root mode: ${if (preferences.value.temporaryRoot) "on (per-project and per-use limits)" else "off (root allowed everywhere while connected)"}")
        appendLine(root.state.value.detail)
        appendLine("Root checked at: ${root.state.value.checkedAt.takeIf { it > 0 }?.let(java.time.Instant::ofEpochMilli) ?: "Not checked"}")
        appendLine("Privileged shell ready: ${shizuku.isServiceReady()}; transport: ${when { shizuku.wirelessTransportActive -> "Wireless ADB"; shizuku.isServiceReady() -> shizuku.transportLabel; else -> "none" }}; UID: ${szUid.value ?: "not verified"}")
        appendLine("Temporary use: ${if (preferences.value.temporaryRoot) allowance.value else "off (root allowed everywhere while connected)"}; paused: ${stopped.value}")
        dhizuku?.let { appendLine("Device Owner: ${it.state.value.status}; owner: ${it.state.value.ownerPackage ?: "none"}; not an ADB/root shell") }
        appendLine("Last execution: ${last.value}")
        append("Active tracked calls: ${active.value}")
    }
    var stopBackgroundTasks: suspend () -> String = { "No detached task registry connected." }

    suspend fun stopPrivileged(): String {
        stopped.value = true
        disableRootUse()
        val ownerStop = dhizuku?.stopCommands().orEmpty()
        return coroutineScope {
            val backgroundStop = async { stopBackgroundTasks() }
            val rootStop = async { root.stop() }
            val shizukuStop = async { shizuku.stopActiveCommands() }
            rootStop.await() + "\n" + shizukuStop.await() + "\n" + backgroundStop.await() + "\n" + ownerStop
        }
    }
}
