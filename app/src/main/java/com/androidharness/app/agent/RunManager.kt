package com.androidharness.app.agent

import android.content.Context
import com.androidharness.app.AgentService
import com.androidharness.app.PendingPrompt
import com.androidharness.app.RuntimeNotifier
import com.androidharness.app.RunResultNotification
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.CheckpointStore
import com.androidharness.app.data.SessionRepository
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.workspace.WorkspaceManager
import com.androidharness.app.workspace.ChatWorkspaceSelection
import com.androidharness.app.workspace.ChatWorkspaceFs
import com.androidharness.app.workspace.workspacePaths
import com.androidharness.app.workspace.workspacePathsOverlap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Owns agent runs in an application-wide scope, so a run survives minimizing
 * the app, rotating the screen, and navigating away, only process death ends
 * it, and the foreground service + wakelock keep that at bay while a run is
 * active. ChatViewModel renders this state; it no longer owns the loop.
 *
 * All message persistence for a run happens here (via [SessionRepository]) so
 * the DB stays the single source of truth and chat UIs just observe flows.
 */
class RunManager(
    private val context: Context,
    private val engine: AgentEngine,
    private val sessions: SessionRepository,
    private val checkpoints: CheckpointStore,
    private val workspace: WorkspaceManager,
    private val linuxEnv: LinuxEnvironmentManager,
    private val settings: com.androidharness.app.data.SettingsRepository,
    private val todoStore: TodoStore,
    private val providers: com.androidharness.app.data.ProviderRepository,
    /** MCP servers; tools are attached per run. Null in tests without MCP. */
    private val mcp: com.androidharness.app.tools.mcp.McpManager? = null,
    private val repoMap: com.androidharness.app.repomap.RepoMapCache? = null,
    private val chatModels: com.androidharness.app.data.ChatModelRepository =
        com.androidharness.app.data.ChatModelRepository(settings, providers, sessions),
    private val canRun: suspend () -> Boolean = { true },
) {

    val controls = TaskControlStore(java.io.File(context.filesDir, "task-controls"))
    val remoteCompletions = RunCompletionStore(java.io.File(context.filesDir, "telegram/run-completions"))
    private val followUps = RunFollowUps(controls)

    /** Live, per-session run state the UI mirrors. */
    data class LiveRunState(
        val sessionId: String,
        val running: Boolean = false,
        val cancelled: Boolean = false,
        val turnId: String? = null,
        val streamingText: String? = null,
        val streamingThinking: String? = null,
        /** Id of the message currently streaming; the commit writes the row under this same id. */
        val liveMessageId: String? = null,
        /** Id of the most recently committed assistant message, for the UI's live→committed handoff. */
        val lastCommittedId: String? = null,
        val runningCalls: List<ToolCallData> = emptyList(),
        val codeDrafts: Map<String, LiveCodeDraft> = emptyMap(),
        val packageProgress: Map<String, com.androidharness.app.data.env.PackageInstallStatus> = emptyMap(),
        val pendingApproval: ApprovalRequest? = null,
        val pendingQuestion: QuestionRequest? = null,
        val pendingEnvironment: EnvironmentRequest? = null,
        val installingEnvironment: EnvironmentRequest? = null,
        val currentToolAction: String? = null,
        /** Live progress lines per running subagent (task tool), newest last. */
        val subagentSteps: Map<String, List<String>> = emptyMap(),
        val error: String? = null,
        /** Transient "Retrying in Ns…" status while the engine backs off. */
        val retryStatus: String? = null,
        val thinkingTiming: ThinkingTiming = ThinkingTiming(),
        val reasoningTokens: Int? = null,
        /** Wall-clock start of the current turn; drives the unified live thinking+tools timer. */
        val turnStartedAt: Long? = null,
        val queuedMessage: String? = null,
        val pendingPlan: String? = null,
        val estimate: ContextEstimate? = null,
        /** While a compaction is in flight ("Context near 90K, summarizing…"). */
        val compactionNote: String? = null,
    )

    /** Pending streamed deltas, published to the UI at frame cadence by the flusher. */
    private class DeltaBuffers {
        val text = StringBuilder()
        val thinking = StringBuilder()
        var dirty = false
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val workspaceGuard = Mutex()

    /** Keep publication and run starts mutually exclusive for the captured workspace. */
    suspend fun <T> withIdleWorkspace(fs: com.androidharness.app.workspace.WorkspaceFs,
        busyMessage: String = "A task is working in this workspace. Wait for it to finish, then retry publishing.",
        action: suspend () -> T): T = workspaceGuard.withLock {
        check(!workspaceBusy(fs)) {
            busyMessage
        }
        action()
    }

    /** Maintenance holds the same gate as run starts and workspace mutations. */
    suspend fun <T> withIdleStorage(action: suspend () -> T): T = workspaceGuard.withLock {
        check(runningSessionIds.value.isEmpty() && mainRunningSessionIds.value.isEmpty() && subagents.runs.value.none { it.status.active }) { "Wait for running tasks to finish before cleaning storage." }
        action()
    }

    /** Uses the same gate as run starts so a removed chat cannot start working mid-delete. */
    suspend fun <T> withIdleProject(projectId: String, action: suspend () -> T): T = workspaceGuard.withLock {
        check(workspace.projectChats(projectId).none { isRunning(it.id) || hasPendingFollowUp(it.id) }) {
            "Stop running tasks in this project before deleting it."
        }
        action()
    }

    suspend fun assignWorkspace(sessionId: String, projectId: String) = workspaceGuard.withLock {
        if (sessions.session(sessionId)?.projectId == projectId) return@withLock
        check(!isRunning(sessionId) && !hasActiveSubagents(sessionId)) { "Wait for this chat's task to finish before changing its workspace." }
        check(!controls.flow(sessionId).value.resumable) { "Resume or stop this chat's saved task before changing its workspace." }
        check(checkpoints.turnsWithCheckpoints(sessionId).isEmpty() && sessions.fileChangesFor(sessionId).first().isEmpty()) {
            "This chat has saved file changes. Start a new chat to work in another workspace."
        }
        workspace.assignSession(sessionId, projectId)
    }

    private fun workspaceBusy(fs: com.androidharness.app.workspace.WorkspaceFs, except: String? = null): Boolean =
        (mainRunningSessionIds.value + runningSessionIds.value).any { sid -> sid != except && controls.flow(sid).value.let {
            workspacePathsOverlap(fs.workspacePaths(), it.workspacePaths.ifEmpty { listOf(it.workspacePath) })
        } } || subagents.runs.value.any { it.status.active && it.sessionId != except && workspacePathsOverlap(fs.workspacePaths(), it.workspacePaths) }

    suspend fun assignWorkspaces(sessionId: String, selection: ChatWorkspaceSelection) = workspaceGuard.withLock {
        selection.validated()
        check(!isRunning(sessionId) && !hasActiveSubagents(sessionId)) { "Wait for this chat's task to finish before changing workspace access." }
        check(!controls.flow(sessionId).value.resumable) { "Resume or stop this chat's saved task before changing workspace access." }
        val saved = requireNotNull(sessions.session(sessionId)) { "This chat no longer exists." }
        val prior = saved.workspaceSelectionJson?.let { runCatching { ChatWorkspaceSelection.decode(it) }.getOrNull() }
            ?: saved.projectId?.let(ChatWorkspaceSelection::single)
        if (prior == selection) return@withLock
        val hasChanges = checkpoints.turnsWithCheckpoints(sessionId).isNotEmpty() || sessions.fileChangesFor(sessionId).first().isNotEmpty()
        if (hasChanges) {
            check(prior != null && prior.primaryId == selection.primaryId && selection.grants.map { it.projectId }.containsAll(prior.grants.map { it.projectId })) {
                "This chat has saved file changes. Keep its primary and existing workspaces so undo stays available; you can add workspaces or change access."
            }
        }
        workspace.assignSelection(sessionId, selection)
        synchronized(lock) { allowedTools.remove(sessionId) }
    }
    private val states = mutableMapOf<String, MutableStateFlow<LiveRunState>>()
    private val jobs = mutableMapOf<String, Job>()
    private val followUpJobs = mutableMapOf<String, Job>()
    private val queueWrites = mutableMapOf<String, MutableSet<Job>>()
    private val turnIds = mutableMapOf<String, String>()
    private val allowedTools = mutableMapOf<String, MutableSet<String>>()
    private val grantStore = RememberedGrants()
    internal val rememberedGrants = grantStore.grants
    internal fun revokeGrant(grant: RememberedGrants.Grant) = grantStore.revoke(grant)
    private val lingerJobs = mutableMapOf<String, Job>()
    private val deltaBuffers = mutableMapOf<String, DeltaBuffers>()

    private val keepalive = KeepaliveTracker(
        onAcquire = { runCatching { AgentService.acquireKeepalive(context) } },
        onRelease = { runCatching { AgentService.releaseKeepalive(context) } },
        onActiveChanged = { active ->
            RuntimeNotifier.setActive(active)
            if (!active) RuntimeNotifier.update(RuntimeNotifier.IDLE_STATUS)
        },
    )

    val subagents = SubagentRuns(
        java.io.File(context.noBackupFilesDir, "subagent-runs.json"), appScope,
        onEvent = { run, event ->
            when (event) {
                is AgentEvent.SubagentMessageCommitted -> sessions.addMessage(run.sessionId, event.message, run.turnId)
                is AgentEvent.Usage -> {
                    val price = if (event.providerName.startsWith("ChatGPT · ")) null else com.androidharness.app.llm.ModelPrices.estimateAt(
                        model = event.model, totalInputTokens = event.inputTokens.toLong(), outputTokens = event.outputTokens.toLong(),
                        cachedTokens = event.cachedInputTokens.toLong(), cacheWriteTokens = event.cacheWriteTokens.toLong(),
                        providerKey = event.providerKey, atMillis = System.currentTimeMillis())
                    kotlin.coroutines.coroutineContext[TaskBudget]?.let { budget ->
                        budget.add(event.inputTokens, event.outputTokens, price)
                        val usage = budget.usage()
                        controls.update(run.sessionId) {
                            if (it.turnId != run.turnId) it else it.copy(usedTokens = usage.first, usedCost = usage.second, elapsedMs = usage.third)
                        }
                    }
                    handleEvent(run.sessionId, event, run.turnId)
                }
                is AgentEvent.ProviderUsed -> controls.update(run.sessionId) { it.copy(usedProviderIds = it.usedProviderIds + event.providerId) }
                is AgentEvent.SubagentStep -> if (mainRunningSessionIds.value.isEmpty()) RuntimeNotifier.update("${run.name}: ${event.line.take(120)}")
                else -> Unit
            }
        },
        onFinished = { run ->
            if (settings.settings.first().subagentCompletionNotifications && run.status != SubagentStatus.STOPPED) {
                runCatching { com.androidharness.app.RunResultNotifications.post(context, RunResultNotification(
                    sessionId = run.sessionId, title = run.name, ok = run.status == SubagentStatus.COMPLETED,
                    summary = run.output.take(4000), notificationTitle = run.name, subagentCallId = run.id)) }
            }
            if (run.status != SubagentStatus.STOPPED) scheduleSubagentFollowUp(run.sessionId)
        },
        acquireKeepalive = ::acquireKeepalive,
        releaseKeepalive = ::releaseKeepalive,
    )
    private val mainRunningSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val runningSessionIds = MutableStateFlow<Set<String>>(emptySet())
    init {
        appScope.launch {
            combine(mainRunningSessionIds, subagents.activeSessionIds) { main, delegates -> main + delegates }
                .collect { runningSessionIds.value = it }
        }
    }
    private val subagentFollowUps = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val stoppedSubagentSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun scheduleSubagentFollowUp(sessionId: String) {
        if (sessionId in stoppedSubagentSessions) return
        val next = appScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            delay(200)
            synchronized(lock) { jobs[sessionId] }?.join()
            subagents.forSession(sessionId).first { list -> list.none { it.background && it.status.active } }
            if (subagents.pending(sessionId).isEmpty() || sessions.session(sessionId) == null) return@launch
            try {
                workspaceGuard.withLock {
                    if (sessionId in stoppedSubagentSessions || isRunning(sessionId) || subagents.pending(sessionId).isEmpty()) return@withLock
                    val saved = controls.flow(sessionId).value
                    if (!saved.completed || saved.status != "idle" || saved.queue.isNotEmpty() || saved.automationRunId != null) return@withLock
                    val config = saved.provider ?: return@withLock
                    val key = if (config.id == com.androidharness.app.llm.HarnessProvider.ID) providers.harnessApiKey()
                        else config.effectiveApiKey(providers.apiKey(config.id))
                    startRunLocked(sessionId, "", emptyList(), config, key, settings.settings.first().permissionMode,
                        AgentMode.valueOf(saved.mode), saved.maxOutput, saved.maxContext,
                        ThinkingLevel.valueOf(saved.thinking), saved.maxIterations, onlyIfIdle = true, notifyOnFinish = false,
                        subagentContinuation = true)
                }
            } catch (ce: CancellationException) { throw ce }
            catch (_: Exception) { /* Results remain pending for the next user turn. */ }
        }
        subagentFollowUps.put(sessionId, next)?.cancel()
        next.invokeOnCompletion { subagentFollowUps.remove(sessionId, next) }
        next.start()
    }

    private fun stateOf(sessionId: String): MutableStateFlow<LiveRunState> =
        synchronized(lock) {
            states.getOrPut(sessionId) { MutableStateFlow(LiveRunState(sessionId)) }
        }

    private fun buffer(sessionId: String): DeltaBuffers? =
        synchronized(lock) { deltaBuffers[sessionId] }

    /**
     * Publishes buffered stream deltas into [LiveRunState]; a no-op when nothing
     * is pending. Batching deltas here keeps the UI near display cadence no matter
     * how fast the model streams.
     */
    private fun flushDeltas(sessionId: String) {
        val buf = buffer(sessionId) ?: return
        var textOut: String? = null
        var thinkingOut: String? = null
        synchronized(buf) {
            if (!buf.dirty) return
            if (buf.text.isNotEmpty()) {
                textOut = buf.text.toString()
                buf.text.setLength(0)
            }
            if (buf.thinking.isNotEmpty()) {
                thinkingOut = buf.thinking.toString()
                buf.thinking.setLength(0)
            }
            buf.dirty = false
        }
        val text = textOut
        val thinking = thinkingOut
        if (text == null && thinking == null) return
        stateOf(sessionId).update { st ->
            st.copy(
                // An iteration that starts streaming gets a stable id up front;
                // the commit reuses it so the UI's live bubble becomes the row.
                liveMessageId = st.liveMessageId ?: UUID.randomUUID().toString(),
                streamingText = if (text != null) (st.streamingText ?: "") + text else st.streamingText,
                streamingThinking = if (thinking != null) (st.streamingThinking ?: "") + thinking else st.streamingThinking,
            )
        }
    }

    fun live(sessionId: String): StateFlow<LiveRunState> = stateOf(sessionId)

    fun isRunning(sessionId: String?): Boolean =
        sessionId != null && synchronized(lock) { jobs[sessionId]?.isActive == true }

    private fun hasActiveSubagents(sessionId: String) = subagents.runs.value.any { it.sessionId == sessionId && it.status.active }

    fun hasPendingFollowUp(sessionId: String): Boolean = hasActiveSubagents(sessionId) || synchronized(lock) {
        followUpJobs[sessionId]?.isActive == true || jobs[sessionId]?.let { !it.isCompleted } == true
    }

    /** What the run notification should say right now. */
    fun actionText(s: LiveRunState): String? = when {
        s.pendingQuestion != null -> "Waiting for your answer"
        s.pendingApproval != null -> "Waiting for your approval"
        s.installingEnvironment != null -> "Installing Linux environment"
        s.pendingEnvironment != null -> "Linux environment needs your attention"
        s.retryStatus != null -> s.retryStatus
        s.runningCalls.isNotEmpty() -> s.currentToolAction ?: describeToolCall(s.runningCalls.last())
        s.codeDrafts.values.any { it.stage == CodeDraftStage.GENERATING } -> "Generating code…"
        s.streamingThinking != null && s.streamingText.isNullOrEmpty() -> "Thinking…"
        s.streamingText != null -> "Writing response…"
        s.currentToolAction != null -> s.currentToolAction
        s.running -> "Working…"
        else -> null
    }

    // ------------------------------------------------------------------
    // Run lifecycle
    // ------------------------------------------------------------------

    /**
     * Starts an agent run for [text] in [sessionId] (a new session is created
     * when null). Returns the session id. The run executes on an app-wide
     * scope, it is not tied to any ViewModel.
     */
    suspend fun startRun(
        sessionId: String?,
        text: String,
        imageRefs: List<ImageRef>,
        config: ProviderConfig,
        apiKey: String,
        permissionMode: PermissionMode,
        mode: AgentMode,
        maxOutputTokens: Int,
        maxContextTokens: Int,
        thinking: ThinkingLevel,
        maxIterations: Int,
        workspaceOverride: com.androidharness.app.workspace.WorkspaceFs? = null,
        notifyOnFinish: Boolean = true,
        resume: Boolean = false,
        queuedPromptId: String? = null,
        replacementMessageId: String? = null,
        onlyIfIdle: Boolean = false,
        automationRunId: String? = null,
        allowModelFallback: Boolean = true,
    ): String = kotlinx.coroutines.withContext(Dispatchers.IO) { workspaceGuard.withLock {
        startRunLocked(sessionId, text, imageRefs, config, apiKey, permissionMode, mode,
            maxOutputTokens, maxContextTokens, thinking, maxIterations, workspaceOverride,
            notifyOnFinish, resume, queuedPromptId, replacementMessageId,
            onlyIfIdle = onlyIfIdle, automationRunId = automationRunId, allowModelFallback = allowModelFallback)
    } }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun startRunLocked(
        sessionId: String?,
        text: String,
        imageRefs: List<ImageRef>,
        config: ProviderConfig,
        apiKey: String,
        permissionMode: PermissionMode,
        mode: AgentMode,
        maxOutputTokens: Int,
        maxContextTokens: Int,
        thinking: ThinkingLevel,
        maxIterations: Int,
        workspaceOverride: com.androidharness.app.workspace.WorkspaceFs? = null,
        notifyOnFinish: Boolean = true,
        resume: Boolean = false,
        queuedPromptId: String? = null,
        replacementMessageId: String? = null,
        followUpRunId: String? = null,
        onlyIfIdle: Boolean = false,
        automationRunId: String? = null,
        allowModelFallback: Boolean = true,
        subagentContinuation: Boolean = false,
    ): String {
        check(canRun()) { context.getString(com.androidharness.app.R.string.background_run_blocked) }
        val sid = sessionId ?: sessions.createSession(
            text.take(48),
            projectId = workspace.currentProjectOnce().id,
        )
        if (onlyIfIdle && isRunning(sid)) throw RunBusyException()
        if (!subagentContinuation) {
            stoppedSubagentSessions.remove(sid)
            subagentFollowUps.remove(sid)?.cancel()
        }
        if (resume && automationRunId != null) {
            check(controls.flow(sid).value.automationRunId == automationRunId) { "This chat has moved on. Open it to review the interrupted schedule." }
        }
        synchronized(lock) { jobs[sid] }?.let { previous ->
            previous.cancel()
            previous.join()
        }
        val runConfig = providers.resolveHarnessConfig(config)
        val reasoningPrefs = settings.settings.first()
        val customLevels = if (reasoningPrefs.sameModelAcrossChats && com.androidharness.app.data.ConversationConfig.decode(sessions.session(sid)?.conversationJson).agent == null) reasoningPrefs.customThinkingLevels else
            com.androidharness.app.data.ChatModelSelection.decode(sessions.session(sid)?.modelSelectionJson)?.customThinkingLevels.orEmpty()
        check(allowModelFallback || runConfig.model == config.model) {
            "This chat's model is no longer available. Choose another model in the picker."
        }
        val conversation = com.androidharness.app.data.ConversationConfig.decode(sessions.session(sid)?.conversationJson)
        val contextLimit = if (conversation.isChat) ChatContextPolicy.limit(maxContextTokens,
            providers.catalogs.first()[runConfig.id]?.firstOrNull { it.id == runConfig.model }?.contextTokens,
            com.androidharness.app.local.LocalModelCatalog.isLocal(runConfig.id)) else maxContextTokens
        val assignedWorkspace = workspace.forSession(sid)
        require(workspaceOverride == null || workspaceOverride.displayPath == assignedWorkspace.displayPath) {
            "This chat's workspace changed while preparing the request. Send the message again."
        }
        val runWorkspace = workspaceOverride ?: assignedWorkspace
        checkpoints.history?.takeUnless { conversation.isChat }?.missingFiles(sid, runWorkspace)?.takeIf { it.isNotEmpty() }?.let { missing ->
            sessions.addMessage(sid, ChatMessage(role = Role.SYSTEM,
                text = "Saved workspace files are missing: ${missing.take(20).joinToString()}. " +
                    "Durable copies are available through file_history and file_restore. Inspect prior tool results and recover saved versions if the deletion was unintended. Do not replay completed edits.",
                isError = true))
        }
        if (replacementMessageId != null) {
            check(!resume) { "Cannot replace a message while resuming" }
            check(!workspaceBusy(runWorkspace, except = sid)) {
                "Pause other tasks in this workspace before editing or retrying a message"
            }
            if (conversation.isChat) {
                val rows = sessions.messages(sid)
                val question = rows.lastOrNull { it.role == Role.USER }
                if (question?.id == replacementMessageId && question.text == text) {
                    sessions.archiveChatAnswer(sid, replacementMessageId)
                }
            }
            rewindAndTruncate(sid, replacementMessageId)
        }
        val runId = if (followUpRunId == null) followUps.begin(sid)
            else followUps.advance(sid, followUpRunId) ?: return sid
        val prior = controls.flow(sid).value
        require(!resume || prior.workspacePath == runWorkspace.displayPath) { "Open the original workspace to resume this task" }
        val turnId = if (resume || subagentContinuation) prior.turnId else UUID.randomUUID().toString()
        val record = controls.update(sid) { it.copy(
            status = "running", reason = null, provider = runConfig, workspacePath = runWorkspace.displayPath,
            workspacePaths = runWorkspace.workspacePaths(),
            allowModelFallback = allowModelFallback,
            fallbackPrimary = if (resume) it.fallbackPrimary ?: runConfig else runConfig,
            usedProviderIds = (if (resume) it.usedProviderIds else emptySet()) + runConfig.id,
            mode = mode.name, thinking = thinking.name, maxOutput = maxOutputTokens,
            maxContext = contextLimit, maxIterations = maxIterations, turnId = turnId,
            initialPrompt = if (resume || subagentContinuation) it.initialPrompt else text,
            initialPromptId = if (resume || subagentContinuation) it.initialPromptId else queuedPromptId ?: "$turnId-user",
            automationRunId = if (resume) it.automationRunId else automationRunId,
            completed = false,
            images = if (resume) it.images else imageRefs,
            usedTokens = if (resume || subagentContinuation) it.usedTokens else 0,
            usedCost = if (resume || subagentContinuation) it.usedCost else 0.0,
            elapsedMs = if (resume || subagentContinuation) it.elapsedMs else 0,
            autoContinueAttempts = if (resume) it.autoContinueAttempts else 0,
        ) }
        val budget = TaskBudget(record.limits, record.usedTokens, record.usedCost, record.elapsedMs)
        synchronized(lock) {
            turnIds[sid] = turnId
            allowedTools[sid] = grantStore.start(sid, runWorkspace.displayPath)
            deltaBuffers[sid] = DeltaBuffers()
        }

        val live = stateOf(sid)
        // A fresh user prompt starts a fresh task list; the store is
        // session-owned so nothing leaks across chats.
        if (!resume && !subagentContinuation && !conversation.isChat) todoStore.beginRun(sid)
        val recoveryMessages = if (resume) sessions.messages(sid).filter { it.turnId == turnId } else emptyList()
        RunRecovery.missingResults(sessions.messages(sid)).forEach { sessions.addMessage(sid, it, turnId) }
        val initialId = record.initialPromptId.ifEmpty { "$turnId-user" }
        if (!subagentContinuation && sessions.messages(sid).none { it.id == initialId }) {
            sessions.addMessage(sid, ChatMessage(role = Role.USER, text = record.initialPrompt,
                images = record.images, turnId = turnId, id = initialId), turnId)
        }
        if (runConfig.model != config.model) sessions.addMessage(sid, ChatMessage(
            role = Role.ASSISTANT,
            text = "${config.model} is no longer available in Harness. Using ${runConfig.model}.",
        ), turnId)
        if (!resume && conversation.draftText.isNotBlank()) sessions.saveConversation(sid, conversation.copy(draftText = ""))
        controls.update(sid) { it.copy(queue = it.queue.filterNot { q -> q.id == initialId }) }
        dropDeliveredSteering(sid)
        if (resume) sessions.addMessage(sid, ChatMessage(role = Role.USER,
            text = RunRecovery.CONTINUE_PROMPT,
            id = if (conversation.isChat) RunRecovery.CHAT_CONTINUATION_ID_PREFIX + UUID.randomUUID() else null), turnId)
        // A new run replaces any plan approval still pending on this session.
        runCatching { sessions.setPendingPlan(sid, null) }
        live.update {
            it.copy(
                running = true, cancelled = false, error = null, turnId = turnId,
                streamingText = "", streamingThinking = null,
                thinkingTiming = ThinkingTiming(), reasoningTokens = null,
                turnStartedAt = ThinkingTiming.now(),
                codeDrafts = emptyMap(), packageProgress = emptyMap(),
                liveMessageId = UUID.randomUUID().toString(), lastCommittedId = null,
                queuedMessage = null, pendingPlan = null,
            )
        }
        mainRunningSessionIds.update { it + sid }
        runningSessionIds.update { it + sid }
        acquireKeepalive()
        RuntimeNotifier.update("Working…")

        val history = with(sessions) {
            RunRecovery.context(historyFor(sid).second.withoutSubagentTurns(), record)
        }
        var end: RunFollowUps.End? = null
        val startSignal = CompletableDeferred<Unit>()
        // Enter try/finally even if a stop lands before the worker is scheduled.
        val fallback = com.androidharness.app.llm.FallbackRun(record.fallbackPrimary) {
            val chat = controls.flow(sid).value
            com.androidharness.app.llm.effectiveFallback(settings.settings.first().fallbackPolicy, chat.fallbackMode, chat.fallbackOverride)
        }
        val job = appScope.launch(budget + RecoveryLedger(recoveryMessages) + fallback, start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            var promptJob: Job? = null
            var flushJob: Job? = null
            var usageJob: Job? = null
            var completed = false
            var finishedNormally = false
            var recoverableInterruption = false
            try {
                startSignal.await()
                check(canRun()) { context.getString(com.androidharness.app.R.string.background_run_blocked) }
                kotlin.coroutines.coroutineContext.ensureActive()
                // SSE chunks arrive far faster than frames render; batch them so
                // the UI updates at a steady cadence regardless of model speed.
                flushJob = launch {
                    while (isActive) {
                        delay(STREAM_FLUSH_MS)
                        flushDeltas(sid)
                    }
                }
                // Disk persistence must never stall publication of incoming text.
                usageJob = launch {
                    while (isActive) {
                        delay(2_500)
                        val usage = budget.usage()
                        controls.update(sid) { it.copy(usedTokens = usage.first, usedCost = usage.second, elapsedMs = usage.third) }
                    }
                }
                // Mirror blocking prompts into RuntimeNotifier so the foreground
                // service can post answerable notifications while backgrounded.
                val sessionTitle = runCatching { sessions.session(sid)?.title }.getOrNull() ?: "Chat"
                promptJob = launch {
                    live.map { pendingPromptsOf(it, sessionTitle) }
                        .distinctUntilChanged()
                        .collect { RuntimeNotifier.setSessionPrompts(sid, it) }
                }
                // One catalog fetch per run serves every task `model` override;
                // a provider that cannot list models just refuses overrides.
                val modelResolver = SubagentModelResolver {
                    when (val r = com.androidharness.app.llm.ModelCatalog.listModels(runConfig, apiKey)) {
                        is com.androidharness.app.llm.ModelCatalog.Result.Models -> {
                            if (runConfig.id == com.androidharness.app.llm.HarnessProvider.ID)
                                providers.saveCatalog(runConfig.id, r.models)
                            r.models.map { it.id }
                        }
                        is com.androidharness.app.llm.ModelCatalog.Result.Failed -> error(r.message)
                    }
                }
                val repoMapOn = runCatching { settings.settings.first().repoMapEnabled }.getOrDefault(true)
                val projectConfiguration = if (conversation.isChat) com.androidharness.app.config.HarnessConfig() else
                    kotlinx.coroutines.withContext(Dispatchers.IO) { mcp?.configuration(runWorkspace) ?: engine.configuration(runWorkspace) }
                val projectToolsApproved = conversation.isChat || mcp?.isWorkspaceApproved(runWorkspace, projectConfiguration) == true
                engine.run(
                    sessionId = sid,
                    turnId = turnId,
                    config = runConfig,
                    apiKey = apiKey,
                    history = history,
                    // Live read: flipping the permission mode mid-run applies
                    // from the very next tool call, not the next message.
                    permissionMode = {
                        runCatching { settings.settings.first().permissionMode }
                            .getOrDefault(permissionMode)
                    },
                    sessionAllowedTools = allowedTools[sid] ?: mutableSetOf(),
                    workspace = runWorkspace,
                    options = RequestOptions(maxOutputTokens = maxOutputTokens, thinking = thinking,
                        modelCatalogs = providers.catalogs.first(), customReasoning = reasoningPrefs.reasoningConfigs,
                        customThinkingLevels = customLevels),
                    maxContextTokens = contextLimit,
                    mode = if (conversation.isChat) AgentMode.ACT else mode,
                    conversation = conversation,
                    searchDepth = reasoningPrefs.searchDepth,
                    chatImageFrequency = reasoningPrefs.chatImageFrequency,
                    chatInstructions = reasoningPrefs.chatInstructions,
                    maxIterations = maxIterations,
                    // Connected MCP servers ride into this run; a failing
                    // server must never block the run itself.
                    extraTools = if (conversation.isChat || runWorkspace is ChatWorkspaceFs) emptyList() else mcp?.activeTools(runWorkspace, projectConfiguration, settings.settings.first().permissionMode, "$sid:$turnId").orEmpty(),
                    projectConfiguration = projectConfiguration,
                    pluginsApproved = projectToolsApproved,
                    resolveSubagentModel = modelResolver::resolve,
                    repoMapEnabled = repoMapOn,
                    pinnedInstructions = record.pins,
                    subagentRuns = subagents,
                    steering = steeringInbox(sid, turnId),
                    durableEvent = { event ->
                        if (event is AgentEvent.Finished) finishedNormally = event.reason == null
                        if (event is AgentEvent.Error) recoverableInterruption = event.recoverable
                        if (event is AgentEvent.Usage) {
                            val price = if (event.providerName.startsWith("ChatGPT · ")) null else com.androidharness.app.llm.ModelPrices.estimateAt(
                                model = event.model, totalInputTokens = event.inputTokens.toLong(),
                                outputTokens = event.outputTokens.toLong(), cachedTokens = event.cachedInputTokens.toLong(),
                                cacheWriteTokens = event.cacheWriteTokens.toLong(),
                                providerKey = event.providerKey ?: com.androidharness.app.llm.ModelsDev.providerKeyFor(runConfig.baseUrl),
                                atMillis = System.currentTimeMillis(),
                            )
                            budget.add(event.inputTokens, event.outputTokens, price)
                            if (record.limits.cost > 0 && price == null) {
                                controls.update(sid) { it.copy(reason = "Cost unavailable for this model") }
                            }
                        }
                        handleEvent(sid, event)
                        if (event is AgentEvent.ToolMessageCommitted || event is AgentEvent.Usage || event is AgentEvent.AssistantCommitted) {
                            val usage = budget.usage()
                            controls.update(sid) { it.copy(usedTokens = usage.first, usedCost = usage.second, elapsedMs = usage.third) }
                        }
                    },
                ).collect { }
                completed = finishedNormally && live.value.error == null
            } catch (paused: TaskPaused) {
                controls.update(sid) { it.copy(reason = paused.reasonText) }
            } catch (ce: CancellationException) {
                live.update { it.copy(cancelled = true) }
                throw ce
            } catch (e: Exception) {
                live.update { it.copy(error = e.message ?: "Unexpected error") }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                mcp?.releaseWorkspaceTools("$sid:$turnId")
                compactionSnapshots.remove(sid)
                flushJob?.cancel()
                usageJob?.cancel()
                promptJob?.cancel()
                val usage = budget.usage()
                val cancelled = live.value.cancelled
                if (record.initialPromptId.startsWith("telegram-")) runCatching {
                    remoteCompletions.save(RunCompletion(record.initialPromptId, sid, turnId, completed, cancelled,
                        if (completed || cancelled) null else controls.flow(sid).value.reason ?: live.value.error ?: "Task interrupted"))
                }.onFailure { live.update { it.copy(error = "Could not save the remote run outcome. Open this chat to review its results.") } }
                runCatching { controls.update(sid) { it.copy(status = if (completed || cancelled) "idle" else "paused", completed = completed,
                    reason = if (completed || cancelled) null else it.reason ?: live.value.error ?: "Task interrupted",
                    usedTokens = usage.first, usedCost = usage.second, elapsedMs = usage.third) } }
                    .onFailure { failure -> live.update { it.copy(error = "Could not save progress: ${failure.message}") } }
                RuntimeNotifier.setSessionPrompts(sid, emptyList())
                val planText = if (mode == AgentMode.PLAN && completed) {
                    runCatching {
                        sessions.messages(sid).lastOrNull { it.role == Role.ASSISTANT }?.text
                    }.getOrNull()
                } else null
                lingerJobs.remove(sid)?.cancel()
                val pendingPlan = planText?.takeIf { t -> t.isNotBlank() }
                // Persist alongside the live state: the approval card must
                // survive process death, not just this app scope.
                runCatching { sessions.setPendingPlan(sid, pendingPlan) }
                live.update {
                    it.copy(
                        running = false,
                        turnId = null,
                        streamingText = null,
                        streamingThinking = null,
                        turnStartedAt = null,
                        // Keep lastCommittedId: the UI's hold needs it to know
                        // which committed row it is waiting on.
                        liveMessageId = null,
                        runningCalls = emptyList(),
                        codeDrafts = emptyMap(), packageProgress = emptyMap(),
                        pendingApproval = null,
                        pendingQuestion = null,
                        pendingEnvironment = null,
                        currentToolAction = null,
                        subagentSteps = emptyMap(),
                        retryStatus = null,
                        queuedMessage = null,
                        pendingPlan = pendingPlan,
                    )
                }
                mainRunningSessionIds.update { it - sid }
                runningSessionIds.update { if (hasActiveSubagents(sid)) it else it - sid }
                synchronized(lock) {
                    turnIds.remove(sid)
                    allowedTools.remove(sid)
                    grantStore.finish(sid)
                    deltaBuffers.remove(sid)
                }
                try {
                    val result = if (notifyOnFinish) notifyFinished(sid, if (completed) live.value.error else
                        controls.flow(sid).value.reason ?: "Task paused") else null
                    end = RunFollowUps.End(sid, runId,
                        finishedNotification = completed && (!notifyOnFinish || result?.ok == true),
                        recoverableInterruption = !completed && !cancelled && recoverableInterruption)

                } finally { releaseKeepalive() }
                }
            }
        }
        job.invokeOnCompletion {
            synchronized(lock) { if (jobs[sid] === job) jobs.remove(sid) }
            val ended = end ?: return@invokeOnCompletion
            if (record.automationRunId != null) return@invokeOnCompletion
            val successor = appScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                // Wait outside the completed job so starting its successor cannot join itself.
                job.join()
                // A send accepted while busy must reach durable storage before deciding what follows.
                synchronized(lock) { queueWrites[sid]?.toList() }.orEmpty().forEach { it.join() }
                if (ended.recoverableInterruption) delay(RetryPolicy.delayMs(controls.flow(sid).value.autoContinueAttempts + 1))
                kotlinx.coroutines.withContext(Dispatchers.IO) { workspaceGuard.withLock {
                    dropDeliveredSteering(sid)
                    when (val action = followUps.next(ended)) {
                        null -> Unit
                        else -> try {
                            val saved = controls.flow(sid).value
                            val continuing = action is RunFollowUps.Action.Continue
                            if (continuing) TaskBudget(saved.limits, saved.usedTokens, saved.usedCost, saved.elapsedMs).check()
                            val prompt = (action as? RunFollowUps.Action.Send)?.prompt
                            startRunLocked(sid, prompt?.text.orEmpty(), emptyList(), saved.provider ?: runConfig, apiKey,
                                settings.settings.first().permissionMode, mode, maxOutputTokens,
                                maxContextTokens, thinking, maxIterations, workspaceOverride = runWorkspace,
                                notifyOnFinish = notifyOnFinish, resume = continuing, queuedPromptId = prompt?.id,
                                followUpRunId = ended.runId, allowModelFallback = saved.allowModelFallback)
                        } catch (ce: CancellationException) { throw ce }
                        catch (e: Exception) {
                            val reason = "Could not ${if (action is RunFollowUps.Action.Continue) "continue task" else "send queued message"}: ${e.message}"
                            controls.update(sid) { it.copy(status = "paused", reason = reason) }
                            stateOf(sid).update { it.copy(error = reason) }
                            if (notifyOnFinish) notifyFinished(sid, reason)
                        }
                    }
                } }
            }
            synchronized(lock) { followUpJobs[sid] = successor }
            successor.invokeOnCompletion { synchronized(lock) { if (followUpJobs[sid] === successor) followUpJobs.remove(sid) } }
            successor.start()
        }
        synchronized(lock) {
            jobs[sid] = job
            // A stop during setup must also cancel the run whose job was not registered yet.
            if (!followUps.isCurrent(sid, runId)) job.cancel()
            startSignal.complete(Unit)
        }
        return sid
    }

    private val compactionSnapshots = java.util.concurrent.ConcurrentHashMap<String, SessionRepository.CompactionSnapshot>()

    private suspend fun handleEvent(sessionId: String, event: AgentEvent, persistedTurnId: String? = null) {
        val live = stateOf(sessionId)
        val turnId = persistedTurnId ?: synchronized(lock) { turnIds[sessionId] }
        when (event) {
            is AgentEvent.ThinkingProgress -> live.update { it.copy(thinkingTiming = event.timing) }
            is AgentEvent.Text -> {
                val buf = buffer(sessionId) ?: return
                synchronized(buf) {
                    buf.text.append(event.delta)
                    buf.dirty = true
                }
                live.update {
                    // Answer text closes the latest received reasoning span.
                    if (event.delta.isNotEmpty() && it.thinkingTiming.startedAt != null) {
                        it.copy(thinkingTiming = it.thinkingTiming.stop(ThinkingTiming.now()), retryStatus = null)
                    } else if (it.retryStatus != null) {
                        it.copy(retryStatus = null)
                    } else it
                }
            }

            is AgentEvent.Thinking -> {
                val buf = buffer(sessionId) ?: return
                synchronized(buf) {
                    buf.thinking.append(event.delta)
                    buf.dirty = true
                }
                live.update {
                    it.copy(
                        thinkingTiming = if (event.delta.isNotEmpty()) it.thinkingTiming.start(ThinkingTiming.now()) else it.thinkingTiming,
                        retryStatus = null,
                    )
                }
            }

            is AgentEvent.AssistantCommitted -> {
                flushDeltas(sessionId)
                // Commit under the id the live bubble has been using, so the UI
                // swaps live → committed in place instead of remove + insert.
                val id = live.value.liveMessageId ?: UUID.randomUUID().toString()
                val msg = event.message.copy(turnId = turnId, id = id)
                sessions.addMessage(sessionId, msg, turnId)
                live.update {
                    it.copy(
                        streamingText = null, streamingThinking = null,
                        liveMessageId = null, lastCommittedId = id,
                        thinkingTiming = ThinkingTiming(), reasoningTokens = null,
                        codeDrafts = it.codeDrafts.filterValues { draft -> draft.stage != CodeDraftStage.GENERATING },
                    )
                }
            }

            is AgentEvent.CodeDraft -> live.update { st ->
                val current = st.codeDrafts.filterValues { it.stage != CodeDraftStage.SAVED && it.stage != CodeDraftStage.FAILED }
                st.copy(codeDrafts = (current + (event.draft.id to event.draft)).entries.toList().takeLast(8).associate { it.toPair() },
                    thinkingTiming = st.thinkingTiming.stop(ThinkingTiming.now()), retryStatus = null)
            }
            AgentEvent.CodeDraftsCleared -> live.update { st -> st.copy(codeDrafts = st.codeDrafts.filterValues {
                it.stage == CodeDraftStage.SAVED || it.stage == CodeDraftStage.FAILED
            }) }
            is AgentEvent.ToolStarted -> {
                flushDeltas(sessionId)
                lingerJobs.remove(sessionId)?.cancel()
                // A large batch may have evicted this preview. Bring the active file to the end,
                // where a chat following live work can see it, even for one-shot tool responses.
                val preview = live.value.codeDrafts[event.call.id] ?: LiveCodePreview.build(
                    event.call.id, event.call.name, event.call.argumentsJson, complete = true,
                )?.copy(comparisonAvailable = event.call.name != "write_file",
                    responseOutputTokens = live.value.codeDrafts.values.firstOrNull {
                        it.stage != CodeDraftStage.SAVED && it.stage != CodeDraftStage.FAILED
                    }?.responseOutputTokens)
                live.update {
                    it.copy(
                        runningCalls = it.runningCalls + event.call,
                        currentToolAction = describeToolCall(event.call),
                        codeDrafts = if (preview == null) it.codeDrafts else
                            it.codeDrafts.filterValues { draft -> draft.stage != CodeDraftStage.SAVED && draft.stage != CodeDraftStage.FAILED } - event.call.id +
                                (event.call.id to preview.copy(stage = CodeDraftStage.WRITING)),
                    )
                }
            }

            is AgentEvent.PackageProgress -> live.update { st ->
                if (st.runningCalls.any { it.id == event.callId })
                    st.copy(packageProgress = st.packageProgress + (event.callId to event.status)) else st
            }
            is AgentEvent.ApprovalNeeded -> live.update { it.copy(pendingApproval = event.request) }
            is AgentEvent.QuestionNeeded -> live.update { it.copy(pendingQuestion = event.request) }
            is AgentEvent.EnvironmentNeeded -> live.update { it.copy(pendingEnvironment = event.request) }

            is AgentEvent.ToolMessageCommitted -> sessions.addMessage(sessionId, event.message, turnId)

            // Inner subagent turns persist under the parent session; the main
            // chat hides them (assistant rows carry the parent call id).
            is AgentEvent.SubagentMessageCommitted ->
                sessions.addMessage(sessionId, event.message, turnId)

            is AgentEvent.CodeActivity -> sessions.recordCodeActivity(sessionId, event.path, event.added, event.removed, event.activityId)
            is AgentEvent.FileEdited -> {
                live.update { st -> st.copy(codeDrafts = st.codeDrafts.mapValues { (id, draft) ->
                    if (id != event.callId) draft else draft.copy(comparisonAvailable = true, files = draft.files.map { file ->
                        if (com.androidharness.app.workspace.normalizeRelPath(file.path) != event.relPath) file
                        else file.copy(added = event.added.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), removed = event.removed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    })
                }) }
                event.callId?.let { callId -> sessions.recordToolCodeChange(
                    sessionId, event.turnId, callId, event.relPath, event.added, event.removed) }
                repoMap?.invalidate(event.relPath)
                sessions.recordFileEdit(
                    sessionId, event.turnId, event.relPath, event.added, event.removed,
                    activityId = "$sessionId:${event.turnId}:${event.callId ?: UUID.randomUUID()}:${event.relPath}",
                    countForUsage = event.countsKnown,
                )
                // Cumulative per-session tracking for the Files-changed view.
                sessions.recordFileChange(
                    sessionId = sessionId,
                    relPath = event.relPath,
                    added = event.added,
                    removed = event.removed,
                    existedBefore = event.existedBefore,
                    existsAfter = event.existsAfter,
                    beforeText = event.beforeText,
                )
            }

            is AgentEvent.ToolFinished -> {
                val remaining = live.value.runningCalls.filterNot { c -> c.id == event.callId }
                live.update {
                    it.copy(
                        runningCalls = remaining,
                        packageProgress = it.packageProgress - event.callId,
                        pendingApproval = null,
                        subagentSteps = it.subagentSteps - event.callId,
                        codeDrafts = it.codeDrafts.mapValues { (id, draft) ->
                            if (id == event.callId) draft.copy(stage = if (event.result.ok) CodeDraftStage.SAVED else CodeDraftStage.FAILED) else draft
                        },
                    )
                }
                if (remaining.isEmpty()) {
                    // linger the last action briefly so fast edits are perceptible
                    lingerJobs[sessionId] = appScope.launch {
                        delay(1_500)
                        live.update { it.copy(currentToolAction = null) }
                    }
                }
            }

            // Already saved by the steering inbox, before it left the queue.
            is AgentEvent.UserMessageInjected -> live.update { it.copy(queuedMessage = null) }

            is AgentEvent.Usage -> {
                if (event.parentCallId == null) live.update { st -> st.copy(reasoningTokens = event.reasoningTokens, codeDrafts = st.codeDrafts.mapValues { (_, draft) ->
                    if (draft.stage == CodeDraftStage.SAVED || draft.stage == CodeDraftStage.FAILED) draft
                    else draft.copy(responseOutputTokens = event.outputTokens.takeIf { it > 0 })
                }) }
                sessions.addUsage(
                    sessionId,
                    event.inputTokens.toLong(),
                    event.outputTokens.toLong(),
                    event.cachedInputTokens.toLong(),
                    event.cacheWriteTokens.toLong(),
                )
                sessions.recordUsage(
                    sessionId,
                    event.providerName,
                    event.model,
                    event.inputTokens.toLong(),
                    event.outputTokens.toLong(),
                    event.cachedInputTokens.toLong(),
                    event.cacheWriteTokens.toLong(),
                    turnId = turnId,
                    cacheReported = event.cacheReported,
                    cachePrices = event.cachePrices,
                )
            }

            is AgentEvent.ProviderChanged -> {
                controls.flow(sessionId).value.provider?.takeIf { event.persistSelection }?.let { from ->
                    try { chatModels.providerChanged(sessionId, from, event.config) }
                    catch (ce: CancellationException) { throw ce }
                    catch (_: Exception) { /* The running task still records the actual account below. */ }
                }
                controls.update(sessionId) { it.copy(provider = event.config, usedProviderIds = it.usedProviderIds + event.config.id) }
                val noticeId = sessions.addMessage(sessionId, ChatMessage(role = Role.SYSTEM, text = event.reason, turnId = turnId))
                compactionSnapshots[sessionId]?.let { snapshot ->
                    // This known notification is not a conversation edit. Keep the original retention boundary.
                    compactionSnapshots[sessionId] = snapshot.copy(messageIds = snapshot.messageIds + noticeId)
                }
                live.update { it.copy(retryStatus = event.reason) }
            }

            is AgentEvent.ProviderUsed -> controls.update(sessionId) { it.copy(usedProviderIds = it.usedProviderIds + event.providerId) }

            is AgentEvent.Retrying -> {
                val seconds = (event.delayMs / 1000.0).let { if (it < 1) "<1" else "%.0f".format(it) }
                live.update {
                    it.copy(retryStatus = "Retrying in ${seconds}s (attempt ${event.attempt}): ${event.reason}", codeDrafts = emptyMap())
                }
            }

            is AgentEvent.EstimatedContext -> live.update { it.copy(estimate = event.estimate) }
            is AgentEvent.Compacting -> {
                if (!compactionSnapshots.containsKey(sessionId)) compactionSnapshots[sessionId] = sessions.compactionSnapshot(sessionId)
                RuntimeNotifier.update(event.reason)
                live.update { it.copy(compactionNote = event.reason) }
            }

            is AgentEvent.Compacted -> {
                val snapshot = requireNotNull(compactionSnapshots[sessionId])
                val before = sessions.commitCompaction(sessionId, event.summary, snapshot, event.retainedMessageCount)
                compactionSnapshots.remove(sessionId)
                controls.update(sessionId) { it.copy(summaryOverride = event.summary, contextAfter = before) }
                live.update { it.copy(compactionNote = null) }
            }

            is AgentEvent.Error -> {
                if (compactionSnapshots.remove(sessionId) != null) {
                    sessions.addMessage(sessionId, ChatMessage(role = Role.SYSTEM, text = event.message, isError = true))
                }
                live.update { it.copy(error = event.message, compactionNote = null, codeDrafts = emptyMap()) }
            }

            is AgentEvent.SubagentStep -> live.update { st ->
                st.copy(
                    subagentSteps = st.subagentSteps +
                        (event.toolCallId to st.subagentSteps[event.toolCallId].orEmpty() + event.line),
                )
            }
            is AgentEvent.SubagentPreview -> Unit
            is AgentEvent.Finished -> event.reason?.let { reason ->
                // Abnormal end (no visible answer), show why instead of
                // ending the run silently.
                live.update { it.copy(error = reason) }
            }
        }
        actionText(live.value)?.let { RuntimeNotifier.update(it) }
    }

    // ------------------------------------------------------------------
    // User actions during a run
    // ------------------------------------------------------------------

    fun approve(sessionId: String, rememberForSession: Boolean) {
        val state = stateOf(sessionId).value
        val pending = state.pendingApproval ?: return
        resolveApproval(sessionId, state.turnId ?: return, pending.call.id, true, rememberForSession)
    }

    fun deny(sessionId: String) {
        val state = stateOf(sessionId).value
        resolveApproval(sessionId, state.turnId ?: return, state.pendingApproval?.call?.id ?: return, false, false)
    }

    /** A delayed Telegram button can resolve only the exact run and tool shown on that button. */
    fun respondToRemoteApproval(sessionId: String, turnId: String, callId: String, allow: Boolean, fingerprint: String? = null): Boolean =
        resolveApproval(sessionId, turnId, callId, allow, false, fingerprint)

    private fun resolveApproval(sessionId: String, turnId: String, callId: String, allow: Boolean, remember: Boolean, fingerprint: String? = null): Boolean {
        val live = stateOf(sessionId)
        while (true) {
            val current = live.value
            val pending = current.pendingApproval ?: return false
            if (current.turnId != turnId || pending.call.id != callId) return false
            if (fingerprint != null && RemotePromptIdentity.approval(pending) != fingerprint) return false
            if (!live.compareAndSet(current, current.copy(pendingApproval = null))) continue
            if (allow && remember) synchronized(lock) { grantStore.remember(sessionId, pending.grantKey) }
            return pending.response.complete(allow)
        }
    }

    fun answerQuestion(sessionId: String, answer: String) {
        val state = stateOf(sessionId).value
        respondToRemoteQuestion(sessionId, state.turnId ?: return, state.pendingQuestion?.callId ?: return, answer)
    }

    fun respondToRemoteQuestion(sessionId: String, turnId: String, callId: String, answer: String, fingerprint: String? = null): Boolean {
        val live = stateOf(sessionId)
        while (true) {
            val current = live.value
            val pending = current.pendingQuestion ?: return false
            if (current.turnId != turnId || pending.callId != callId) return false
            if (fingerprint != null && RemotePromptIdentity.question(pending) != fingerprint) return false
            if (!live.compareAndSet(current, current.copy(pendingQuestion = null))) continue
            return pending.response.complete(answer.ifBlank { "(no answer given)" })
        }
    }

    /** Installs the full Linux environment and lets the run continue. */
    fun approveEnvironmentInstall(sessionId: String) {
        val state = stateOf(sessionId).value
        respondToRemoteEnvironment(sessionId, state.turnId ?: return, state.pendingEnvironment?.call?.id ?: return, true)
    }

    fun respondToRemoteEnvironment(sessionId: String, turnId: String, callId: String, allow: Boolean, fingerprint: String? = null): Boolean {
        val live = stateOf(sessionId)
        while (true) {
            val current = live.value
            val pending = current.pendingEnvironment ?: return false
            if (current.turnId != turnId || pending.call.id != callId) return false
            if (fingerprint != null && RemotePromptIdentity.environment(pending) != fingerprint) return false
            if (!live.compareAndSet(current, current.copy(pendingEnvironment = null,
                    installingEnvironment = if (allow) pending else current.installingEnvironment))) continue
            if (allow) installEnvironment(sessionId, pending) else pending.response.complete(false)
            return true
        }
    }

    private fun installEnvironment(sessionId: String, pending: EnvironmentRequest) {
        val live = stateOf(sessionId)
        appScope.launch {
            try {
                if (pending.repair && !linuxEnv.needsRepair()) {
                    // Nothing actually broken (e.g. self-heal fixed it first):
                    // answer immediately without a pointless reinstall.
                    pending.response.complete(true)
                    return@launch
                }
                linuxEnv.install(linuxEnv.fullPackages).getOrThrow()
                if (linuxEnv.isReady) {
                    pending.response.complete(true)
                } else {
                    val msg = (linuxEnv.state.value as? com.androidharness.app.data.env.EnvState.Failed)
                        ?.message ?: "Install did not complete"
                    live.update { it.copy(error = "Linux environment install failed: $msg") }
                    pending.response.complete(false)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                live.update { it.copy(error = "Linux environment install failed: ${e.message}") }
                pending.response.complete(false)
            } finally {
                live.update { if (it.installingEnvironment === pending) it.copy(installingEnvironment = null) else it }
            }
        }
    }

    fun denyEnvironmentInstall(sessionId: String) {
        val state = stateOf(sessionId).value
        respondToRemoteEnvironment(sessionId, state.turnId ?: return, state.pendingEnvironment?.call?.id ?: return, false)
    }

    fun stop(sessionId: String) {
        stoppedSubagentSessions += sessionId
        subagentFollowUps.remove(sessionId)?.cancel()
        subagents.stopSession(sessionId)
        followUps.stop(sessionId)
        synchronized(lock) { followUpJobs[sessionId]?.cancel() }
        synchronized(lock) { jobs[sessionId]?.cancel() }
    }

    /** Serializes stopping with run registration, including queued and subagent continuations. */
    internal suspend fun stopIfBackgroundDisabled() = workspaceGuard.withLock {
        if (!canRun()) stopAllAndJoin()
    }

    /** Cancels every live agent run and waits for each run's cleanup. */
    suspend fun stopAllAndJoin() {
        stoppedSubagentSessions += runningSessionIds.value
        subagentFollowUps.values.toList().forEach { it.cancel() }
        followUps.stopAll()
        val successors = synchronized(lock) { followUpJobs.values.toList() }
        successors.forEach { it.cancel() }
        val active = synchronized(lock) { jobs.values.toList() }
        active.forEach { it.cancel() }
        subagents.stopAllAndJoin()
        active.forEach { runCatching { it.join() } }
        successors.forEach { runCatching { it.join() } }
    }

    /**
     * Cancels the run and suspends until its cleanup has finished, so a follow-up
     * [startRun] cannot race the old job's finally block.
     */
    suspend fun stopAndJoin(sessionId: String) {
        stoppedSubagentSessions += sessionId
        subagentFollowUps.remove(sessionId)?.cancelAndJoin()
        followUps.stop(sessionId)
        synchronized(lock) { followUpJobs[sessionId] }?.cancelAndJoin()
        val job = synchronized(lock) { jobs[sessionId] }
        job?.cancel()
        subagents.stopSessionAndJoin(sessionId)
        job?.join()
    }

    /** Dismisses the pending PLAN-mode plan card without executing it. */
    fun clearPendingPlan(sessionId: String) {
        stateOf(sessionId).update { it.copy(pendingPlan = null) }
        appScope.launch { runCatching { sessions.setPendingPlan(sessionId, null) } }
    }

    /**
     * Re-arms a plan approval persisted on the session row after process
     * death wiped the in-memory live state; a live plan always wins.
     */
    fun seedPendingPlan(sessionId: String, plan: String?) {
        if (plan.isNullOrBlank()) return
        stateOf(sessionId).update { if (it.pendingPlan == null) it.copy(pendingPlan = plan) else it }
    }

    /**
     * AH Auto: the running task reads queued messages between steps. Each one
     * is saved to the chat before it leaves the queue. If the process dies in
     * between, [dropDeliveredSteering] finds the saved copy, so it is never
     * sent twice. After task leaves the queue alone for the next turn.
     */
    private fun steeringInbox(sessionId: String, turnId: String) = SteeringInbox {
        if (settings.settings.first().steeringMode != com.androidharness.app.data.SteeringMode.AH_AUTO) return@SteeringInbox emptyList()
        synchronized(lock) { queueWrites[sessionId]?.toList() }.orEmpty().forEach { it.join() }
        val queue = controls.flow(sessionId).value.queue
        if (queue.isEmpty()) return@SteeringInbox emptyList()
        val saved = sessions.messages(sessionId).mapNotNullTo(HashSet()) { it.id }
        val messages = queue.map { prompt ->
            ChatMessage(role = Role.USER, text = prompt.text, id = Steering.messageId(prompt.id), turnId = turnId).also {
                if (it.id !in saved) sessions.addMessage(sessionId, it, turnId)
            }
        }
        val taken = queue.mapTo(HashSet()) { it.id }
        controls.update(sessionId) { record -> record.copy(queue = record.queue.filterNot { it.id in taken }) }
        messages
    }

    private suspend fun dropDeliveredSteering(sessionId: String) {
        val queue = controls.flow(sessionId).value.queue
        if (queue.isEmpty()) return
        val saved = sessions.messages(sessionId).mapNotNullTo(HashSet()) { it.id }
        if (queue.none { Steering.messageId(it.id) in saved }) return
        controls.update(sessionId) { record -> record.copy(queue = record.queue.filterNot { Steering.messageId(it.id) in saved }) }
    }

    fun inject(sessionId: String, text: String) {
        val job = appScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            controls.update(sessionId) { it.copy(queue = it.queue + QueuedPrompt(text = text)) }
        }
        synchronized(lock) { queueWrites.getOrPut(sessionId) { mutableSetOf() }.add(job) }
        job.invokeOnCompletion { synchronized(lock) {
            queueWrites[sessionId]?.let { pending ->
                pending.remove(job)
                if (pending.isEmpty()) queueWrites.remove(sessionId)
            }
        } }
        job.start()
    }

    fun editQueued(sessionId: String, id: String, text: String) {
        appScope.launch { controls.update(sessionId) { it.copy(queue = it.queue.map { q ->
            if (q.id == id) q.copy(text = text.trim()) else q
        }.filter { q -> q.text.isNotBlank() }) } }
    }

    fun moveQueued(sessionId: String, id: String, offset: Int) {
        appScope.launch { controls.update(sessionId) { record ->
            val list = record.queue.toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index >= 0) { val item = list.removeAt(index); list.add((index + offset).coerceIn(0, list.size), item) }
            record.copy(queue = list)
        } }
    }

    fun cancelQueued(sessionId: String) {
        appScope.launch { controls.update(sessionId) { it.copy(queue = emptyList()) } }
    }

    /** Release a completed schedule before advancing messages accepted while it was busy. */
    internal suspend fun finishScheduledRun(sessionId: String, ownerId: String, apiKey: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            synchronized(lock) { queueWrites[sessionId]?.toList() }.orEmpty().forEach { it.join() }
            workspaceGuard.withLock {
                val saved = controls.flow(sessionId).value
                if (saved.automationRunId != ownerId || isRunning(sessionId) || saved.status != "idle" || !saved.completed) return@withLock
                controls.update(sessionId) { it.copy(automationRunId = null) }
                val prompt = saved.queue.firstOrNull() ?: return@withLock
                try {
                    val config = saved.provider ?: error("Saved provider is unavailable")
                    startRunLocked(sessionId, prompt.text, emptyList(), config, apiKey,
                        settings.settings.first().permissionMode, AgentMode.valueOf(saved.mode), saved.maxOutput,
                        saved.maxContext, ThinkingLevel.valueOf(saved.thinking), saved.maxIterations,
                        workspaceOverride = workspace.forSession(sessionId), queuedPromptId = prompt.id, onlyIfIdle = true,
                        allowModelFallback = saved.allowModelFallback)
                } catch (ce: CancellationException) { throw ce }
                catch (e: Exception) {
                    val reason = "Could not send queued message: ${e.message}"
                    controls.update(sessionId) { it.copy(status = "paused", reason = reason) }
                    stateOf(sessionId).update { it.copy(error = reason) }
                }
            }
        }

    suspend fun resumeTask(sessionId: String, apiKey: String, automationRunId: String? = null, notifyOnFinish: Boolean = true) {
        check(!isRunning(sessionId)) { "Task is already running" }
        val saved = controls.flow(sessionId).value
        check(saved.resumable) { "No interrupted task to resume" }
        val config = saved.provider ?: error("Saved provider is unavailable")
        TaskBudget(saved.limits, saved.usedTokens, saved.usedCost, saved.elapsedMs).check()
        val fs = workspace.forSession(sessionId)
        require(fs.displayPath == saved.workspacePath) { "The original workspace has changed" }
        startRun(sessionId, "", emptyList(), config, apiKey, settings.settings.first().permissionMode,
            AgentMode.valueOf(saved.mode), saved.maxOutput, saved.maxContext,
            ThinkingLevel.valueOf(saved.thinking), saved.maxIterations, workspaceOverride = fs, resume = true,
            onlyIfIdle = true, automationRunId = automationRunId, notifyOnFinish = notifyOnFinish,
            allowModelFallback = saved.allowModelFallback)
    }

    // ------------------------------------------------------------------
    // Edit / rewind
    // ------------------------------------------------------------------

    /**
     * Rewinds workspace files to the state before the message [messageId] and
     * deletes that message and everything after it. The caller then re-sends
     * the edited text as a fresh run.
     */
    suspend fun rewindAndTruncate(sessionId: String, messageId: String) {
        stopAndJoin(sessionId)
        engine.sideContexts.clear(sessionId)
        val msgs = sessions.messages(sessionId)
        val index = msgs.indexOfFirst { it.id == messageId }
        check(index >= 0) { "This message no longer exists. Refresh the chat before retrying." }
        check(msgs[index].role == Role.USER) { "Only user messages can be resent" }
        // distinct turns from the edited message onward, newest first
        val turnIds = msgs.drop(index).mapNotNull { it.turnId }.distinct().reversed()
        val fs = workspace.forSession(sessionId)
        check(!workspaceBusy(fs, except = sessionId)) { "Pause other tasks in these workspaces before editing or retrying a message." }
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            for (tid in turnIds) {
                val result = checkpoints.rewind(sessionId, tid, fs)
                check(result.failed == 0) { "Some files could not be restored. History and failed checkpoints were kept; retry undo." }
            }
            sessions.truncateFrom(sessionId, messageId)
        }
    }

    /**
     * Full undo of a checkpointed turn: every file touched in [turnId] or any
     * later turn returns to its pre-turn state (snapshots replayed newest-turn
     * first so the earliest pre-state wins per path), the conversation rolls
     * back to just before that turn's first agent message, the "+N −M" chip
     * rows of the removed turns are dropped, and cumulative change counters
     * are recomputed against the session baseline.
     */
    suspend fun rewindFromTurn(sessionId: String, turnId: String): RewindSummary = kotlinx.coroutines.withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { workspaceGuard.withLock {
        engine.sideContexts.clear(sessionId)
        val fs = workspace.forSession(sessionId)
        check(!workspaceBusy(fs)) {
            "Pause tasks in this workspace before undoing changes"
        }
        val ordered = checkpoints.turnsOrdered(sessionId)
        val idx = ordered.indexOfFirst { it.turnId == turnId }
        val affectedTurns = if (idx >= 0) ordered.drop(idx).map { it.turnId } else listOf(turnId)

        var restored = 0
        var failed = 0
        val paths = LinkedHashSet<String>()
        for (tid in affectedTurns.reversed()) {
            val result = checkpoints.rewind(sessionId, tid, fs)
            restored += result.restored
            failed += result.failed
            result.paths.forEach { paths += com.androidharness.app.workspace.normalizeRelPath(it) }
            // Older snapshots must not overwrite a newer failed restore before retry.
            if (result.failed > 0) break
        }

        // Chat rolls back: delete from this turn's first agent message onward.
        val msgs = sessions.messages(sessionId)
        val boundary = msgs.indexOfFirst { it.turnId == turnId && it.role != Role.USER }
        var messagesDeleted = 0
        if (failed == 0 && boundary >= 0) {
            msgs[boundary].id?.let { boundaryId ->
                messagesDeleted = msgs.size - boundary
                sessions.truncateFrom(sessionId, boundaryId)
            }
        }

        // The removed turns' diff-chip rows point at messages that no longer exist.
        if (failed == 0) sessions.deleteFileEditsForTurns(sessionId, affectedTurns)

        // Keep the Files-changed view honest: recompute restored paths live.
        for (path in paths) {
            runCatching {
                val node = runCatching { fs.resolve(path) }.getOrNull()
                val exists = node?.exists == true && node.isFile
                val text = if (exists && node != null && node.length <= 512_000) {
                    runCatching { node.readText() }.getOrNull()
                } else {
                    null
                }
                sessions.refreshFileChangeAfterRewind(sessionId, path, exists, text)
            }
        }
        RewindSummary(restored, failed, messagesDeleted, paths.size)
    } }

    suspend fun undoSelection(
        sessionId: String,
        change: com.androidharness.app.data.db.SessionFileChangeEntity,
        expected: String,
        expectedExists: Boolean,
        section: com.androidharness.app.core.Diff.UndoSection?,
    ) = kotlinx.coroutines.withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { workspaceGuard.withLock {
        val fs = workspace.forSession(sessionId)
        check(!workspaceBusy(fs)) {
            "Pause tasks in this workspace before undoing changes"
        }
        check(change.hasBase) { "The original file content is unavailable" }
        val node = fs.resolve(change.relPath)
        check(!node.exists || (node.isFile && !node.isBinary() && node.length <= 1000000)) { "File cannot be safely restored" }
        val current = if (node.exists) node.readText() else ""
        check(node.exists == expectedExists && current == expected) { "File changed since this preview. Reopen it before undoing." }
        val base = if (change.isNew) "" else change.baseGzip?.let {
            java.util.zip.GZIPInputStream(it.inputStream()).bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        } ?: error("The original file content is unavailable")
        val restored = if (section == null) base else {
            check(section in com.androidharness.app.core.Diff.undoSections(base, current)) { "Section no longer matches" }
            com.androidharness.app.core.Diff.undoSection(current, expected, section)
        }
        // Retain a checkpoint of the undo itself so this action is recoverable too.
        checkpoints.snapshot(sessionId, "undo-${UUID.randomUUID()}", fs, change.relPath)
        if (section == null && change.isNew) {
            check(!node.exists || node.delete()) { "Could not remove the new file" }
        } else node.writeText(restored)
        checkpoints.saveVersion(sessionId, fs, change.relPath, "after undo", recordMissing = true)
        repoMap?.invalidate(change.relPath)
        sessions.refreshFileChangeAfterRewind(sessionId, change.relPath, node.exists, if (node.exists) restored else null)
        sessions.addMessage(sessionId, ChatMessage(role = Role.USER,
            text = "I undid ${if (section == null) "all changes in" else "one changed section of"} ${change.relPath}. Inspect the current file before editing it again."))
    } }

    data class RewindSummary(
        val filesRestored: Int,
        val filesFailed: Int,
        val messagesDeleted: Int,
        val filesTouched: Int,
    )

    // ------------------------------------------------------------------
    // Keep-alive plumbing
    // ------------------------------------------------------------------

    /** Active work may appear in Live Activities; passive services may not. */
    /** The request the main loop last sent on this chat, while this process still holds it. */
    fun sideContext(sessionId: String): SideContext? = engine.sideContexts[sessionId]

    /**
     * A side context rebuilt from saved history when no live request exists,
     * such as after the app restarted. It has no tools and no warm cache.
     */
    suspend fun coldSideContext(sessionId: String, config: ProviderConfig, apiKey: String, maxContextTokens: Int): SideContext =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val runConfig = providers.resolveHarnessConfig(config)
            val history = with(sessions) { RunRecovery.context(historyFor(sessionId).second.withoutSubagentTurns(), controls.flow(sessionId).value) }
            check(history.any { it.role == Role.USER || it.role == Role.ASSISTANT }) {
                context.getString(com.androidharness.app.R.string.btw_error_empty_chat)
            }
            val outputTokens = minOf(4_096, (maxContextTokens / 8).coerceAtLeast(512))
            val chars = ((maxContextTokens - outputTokens - 2_048) * 3L).coerceIn(8_000L, 600_000L).toInt()
            SideContext(
                sessionId = sessionId, turnId = "", config = runConfig, apiKey = apiKey,
                systemPrompt = SideQuestion.COLD_SYSTEM_PROMPT, tools = emptyList(),
                messages = SideQuestion.coldMessages(history, chars),
                options = RequestOptions(maxOutputTokens = outputTokens, cacheKey = sessionId, modelCatalogs = providers.catalogs.first()),
                registry = com.androidharness.app.tools.ToolRegistry(emptyList()),
                workspace = workspace.forSession(sessionId), sandboxOff = false,
                permissionMode = PermissionMode.CONFIRM_RISKY, runContext = null, warm = false,
            )
        }

    /**
     * Streams a side question. Usage lands in the chat's cost totals under its
     * own turn id, so it never moves a main turn's cache footer, and nothing
     * else about the exchange is written anywhere.
     */
    fun askAside(context: SideContext, question: String): kotlinx.coroutines.flow.Flow<SideEvent> {
        val sid = context.sessionId
        check(!(com.androidharness.app.local.LocalModelCatalog.isLocal(context.config.id) && isRunning(sid))) {
            this.context.getString(com.androidharness.app.R.string.btw_error_local_busy)
        }
        val turnId = SIDE_TURN_PREFIX + UUID.randomUUID()
        val fallback = com.androidharness.app.llm.FallbackRun(context.config) {
            val chat = controls.flow(sid).value
            com.androidharness.app.llm.effectiveFallback(settings.settings.first().fallbackPolicy, chat.fallbackMode, chat.fallbackOverride)
        }
        return engine.askAside(context, question).onEach { event ->
            if (event is SideEvent.Usage) with(event.usage) {
                sessions.addUsage(sid, inputTokens.toLong(), outputTokens.toLong(), cachedInputTokens.toLong(), cacheWriteTokens.toLong())
                sessions.recordUsage(sid, providerName, model, inputTokens.toLong(), outputTokens.toLong(),
                    cachedInputTokens.toLong(), cacheWriteTokens.toLong(), turnId = turnId,
                    cacheReported = cacheReported, cachePrices = cachePrices)
            }
            if (event is SideEvent.ProviderChanged) controls.update(sid) { it.copy(usedProviderIds = it.usedProviderIds + event.config.id) }
        }.flowOn(Dispatchers.IO + fallback)
    }

    fun acquireKeepalive() = keepalive.acquire()
    fun releaseKeepalive() = keepalive.release()
    fun acquireBackgroundKeepalive() = keepalive.acquire(activeTask = false)
    fun releaseBackgroundKeepalive() = keepalive.release(activeTask = false)

    private suspend fun notifyFinished(sessionId: String, error: String?): RunResultNotification {
        val title = runCatching { sessions.session(sessionId)?.title }.getOrNull() ?: "Chat"
        val result = RunResultNotification(
            sessionId = sessionId,
            title = title,
            ok = error == null,
            summary = if (error == null) "Run finished" else "Stopped: ${error.take(120)}",
        )
        RuntimeNotifier.notifyResult(result)
        return result
    }

    companion object {
        /** Usage rows of side questions; never a real turn, so no main footer picks them up. */
        const val SIDE_TURN_PREFIX = "btw:"

        /** UI refreshes at ~30 fps; network chunks are consumed independently. */
        private const val STREAM_FLUSH_MS = 16L

        /**
         * The blocking prompts of [state] as answerable notification payloads.
         * Pure so the receiver/alert pipeline can be unit-tested.
         */
        internal fun pendingPromptsOf(state: LiveRunState, sessionTitle: String): List<PendingPrompt> =
            buildList {
                state.pendingApproval?.let { r ->
                    add(PendingPrompt(
                        sessionId = state.sessionId,
                        kind = PendingPrompt.Kind.APPROVAL,
                        sessionTitle = sessionTitle,
                        headline = "${r.call.name}: ${r.toolDescription}",
                        detail = r.diffPreview,
                    ))
                }
                state.pendingQuestion?.let { q ->
                    add(PendingPrompt(
                        sessionId = state.sessionId,
                        kind = PendingPrompt.Kind.QUESTION,
                        sessionTitle = sessionTitle,
                        headline = q.question,
                        options = q.options,
                    ))
                }
                state.pendingEnvironment?.let { e ->
                    add(PendingPrompt(
                        sessionId = state.sessionId,
                        kind = PendingPrompt.Kind.ENVIRONMENT,
                        sessionTitle = sessionTitle,
                        headline =
                            if (e.repair) "Repair the Linux environment (missing: ${e.missingTool})"
                            else "Install the Linux environment to run: ${e.command.take(120)}",
                    ))
                }
            }
    }
}

class RunBusyException : IllegalStateException("Waiting for this chat to finish")
