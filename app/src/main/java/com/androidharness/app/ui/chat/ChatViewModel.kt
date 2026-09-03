package com.androidharness.app.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.androidharness.app.AppContainer
import com.androidharness.app.agent.AgentMode
import com.androidharness.app.agent.ApprovalRequest
import com.androidharness.app.agent.ContextEstimate
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.agent.QuestionRequest
import com.androidharness.app.agent.RunManager
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.agent.ThinkingSpecs
import com.androidharness.app.agent.TodoItem
import com.androidharness.app.agent.describeToolCall
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.data.db.SnippetEntity
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.RequestOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UsageStats(
    val totalInput: Long = 0,
    val totalOutput: Long = 0,
    val totalCached: Long = 0,
    val totalCacheWrite: Long = 0,
    val requests: Long = 0,
    val lastInput: Long = 0,
) {
    /**
     * Share of prompt tokens served from the provider's prompt cache.
     * totalInput is the TOTAL prompt size (uncached + cache reads + cache
     * writes), so this counts cache writes as misses, the honest rate.
     */
    val avgCacheHitRate: Double
        get() = if (totalInput > 0) totalCached.toDouble() / totalInput.toDouble() else 0.0
}

sealed interface NavEvent {
    data object NewChat : NavEvent
}

data class ChatUiState(
    val sessionId: String? = null,
    val sessionTitle: String = "New chat",
    val messages: List<ChatMessage> = emptyList(),
    /** True while messages are being initially loaded from Room for this session. */
    val isLoadingMessages: Boolean = false,
    val streamingText: String? = null,
    val streamingThinking: String? = null,
    /** Stable id of the streaming message; the committed row reuses it. */
    val streamingMessageId: String? = null,
    /** True while held content stands in for a just-committed row not yet delivered by Room. */
    val streamingCommitted: Boolean = false,
    /** Turn currently being streamed; keys the live items in the message list. */
    val currentTurnId: String? = null,
    val runningCalls: List<ToolCallData> = emptyList(),
    val pendingApproval: ApprovalRequest? = null,
    val pendingQuestion: QuestionRequest? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val permissionMode: PermissionMode = PermissionMode.CONFIRM_RISKY,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    val mode: AgentMode = AgentMode.ACT,
    val pendingPlan: String? = null,
    val queuedMessage: String? = null,
    val attachments: List<String> = emptyList(), // display names of picked images
    val fileAttachments: List<FileAttachment> = emptyList(),
    val maxContextTokens: Int = 1_000_000,
    val maxOutputTokens: Int = 32_768,
    val maxIterations: Int = 0,
    val providers: List<ProviderConfig> = emptyList(),
    val activeProviderId: String? = null,
    val estimate: ContextEstimate? = null,
    /** While a compaction runs, its status line for the transcript ("Compacting conversation…"). */
    val compactionNote: String? = null,
    /** Workspace files for the @-mention picker (lazily loaded, per workspace). */
    val mentionFiles: List<String> = emptyList(),
    val usage: UsageStats = UsageStats(),
    val todos: List<TodoItem> = emptyList(),
    val turnsWithCheckpoints: Set<String> = emptySet(),
    val snippets: List<SnippetEntity> = emptyList(),
    val skills: List<com.androidharness.app.skills.SkillMeta> = emptyList(),
    val showSkillsSheet: Boolean = false,
    val showEnvSheet: Boolean = false,
    val showCostDialog: Boolean = false,
    val workspaceName: String = "",
    val currentToolAction: String? = null,
    /** Live progress lines per running subagent (task tool), newest last. */
    val subagentSteps: Map<String, List<String>> = emptyMap(),
    /** When the current turn's reasoning stream started (drives the live "Thinking… Ns"). */
    val thinkingStartedAt: Long? = null,
    /** Per-turn file-edit stats ("+N −M" chips), keyed by turnId. */
    val fileEditsByTurn: Map<String, List<com.androidharness.app.data.db.FileEditEntity>> = emptyMap(),
    /** Per-model token usage breakdown for this chat session. */
    val sessionModelUsage: List<com.androidharness.app.data.db.ModelUsagePojo> = emptyList(),
    /** Model override picked from the active provider's catalog. */
    val activeModel: String? = null,
    /** Separate-model-per-mode settings, mirrored from AppSettings. */
    val planningModelsEnabled: Boolean = false,
    val planningProviderId: String? = null,
    val planningModel: String? = null,
    val executionProviderId: String? = null,
    val executionModel: String? = null,
    /** False until the one-time planning-models dialog is dismissed. */
    val planningModelsPromoSeen: Boolean = false,
    /** True while the one-time planning-models dialog should be up; shown on plan-mode switch. */
    val showPlanningPromo: Boolean = false,
    /** False until the one-time introductory fork dialog is dismissed. */
    val forkPromoSeen: Boolean = false,
    /** True while the one-time fork promo dialog should be shown. */
    val showForkPromo: Boolean = false,
    /** Pending message for which fork was requested while promo was pending. */
    val pendingForkMessage: ChatMessage? = null,
    /** Voice speech-to-text engine ("inbuilt" or "groq"). */
    val voiceEngine: String = AppSettings.VOICE_ENGINE_INBUILT,
    /** Groq Whisper model ("whisper-large-v3" or "whisper-large-v3-turbo"). */
    val groqWhisperModel: String = AppSettings.GROQ_MODEL_WHISPER_V3,
    /** False until the one-time voice configuration promo dialog is dismissed. */
    val voicePromoSeen: Boolean = false,
    /** True while the one-time voice promo dialog should be shown. */
    val showVoicePromo: Boolean = false,
    /** One-shot toast text for mode switches ("Planning mode: …"); consumed by the screen. */
    val modeToast: String? = null,
    /** Workspace .harness/mcp.json server names awaiting user approval before a run. */
    val pendingWorkspaceMcp: List<String>? = null,
    /** Fetched model catalogs per provider id. */
    val catalogs: Map<String, List<com.androidharness.app.llm.ModelEntry>> = emptyMap(),
    /** Precomputed human-readable activity line (computed in the ViewModel, never in composition). */
    val currentAction: String? = null,
    val retryStatus: String? = null,
    val pendingEnvironment: com.androidharness.app.agent.EnvironmentRequest? = null,
    val envState: com.androidharness.app.data.env.EnvState =
        com.androidharness.app.data.env.EnvState.NotInstalled,
    val shizukuState: com.androidharness.app.data.env.ShizukuState =
        com.androidharness.app.data.env.ShizukuState.NOT_INSTALLED,
) {
    val activeProvider: ProviderConfig?
        get() = when {
            planningModelsEnabled && mode == AgentMode.PLAN && planningProviderId != null ->
                providers.firstOrNull { it.id == planningProviderId } ?: providers.firstOrNull { it.id == activeProviderId }
            planningModelsEnabled && mode == AgentMode.ACT && executionProviderId != null ->
                providers.firstOrNull { it.id == executionProviderId } ?: providers.firstOrNull { it.id == activeProviderId }
            else -> providers.firstOrNull { it.id == activeProviderId }
        }

    /** Model actually used for requests: catalog pick, else the entry's default. */
    val effectiveModel: String?
        get() = when {
            planningModelsEnabled && mode == AgentMode.PLAN ->
                planningModel?.takeIf { it.isNotBlank() } ?: activeProvider?.model
            planningModelsEnabled && mode == AgentMode.ACT ->
                executionModel?.takeIf { it.isNotBlank() } ?: activeProvider?.model
            else -> activeModel?.takeIf { it.isNotBlank() } ?: activeProvider?.model
        }

    /**
     * Best available measure of tokens currently inside the context window.
     * A live/last-round estimate wins: it reflects compaction the moment it
     * happens, while usage.lastInput is the previous request's measured size
     * and stays stale until the next one.
     */
    val contextUsed: Int
        get() = estimate?.total ?: usage.lastInput.toInt().coerceAtLeast(0)
}

/**
 * Renders one chat session. The agent run itself lives in [RunManager][com.androidharness.app.agent.RunManager]
 * on an app-wide scope, so minimizing or navigating away no longer kills it;
 * this ViewModel mirrors DB + live-run state into [ChatUiState] for the UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val c: AppContainer,
    initialSessionId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 1)
    val navEvents: SharedFlow<NavEvent> = _navEvents

    private var sessionId: String? = initialSessionId
    private val sessionIdFlow = MutableStateFlow(initialSessionId)
    private var pendingAttachments = mutableListOf<ImageRef>()
    private val pendingFileAttachments = mutableListOf<FileAttachment>()
    /** Workspace key the @-mention file list was built for (empty until loaded). */
    private var mentionCacheKey: String? = null
    private var steering = false
    /** Text of a run paused on the workspace-MCP approval dialog. */
    private var pendingRunText: String? = null

    /** User approved the workspace .harness/mcp.json: remember it and run. */
    fun approveWorkspaceMcp() {
        val text = pendingRunText
        pendingRunText = null
        _state.update { it.copy(pendingWorkspaceMcp = null) }
        viewModelScope.launch {
            runCatching { c.mcp.approveWorkspace(c.workspace.currentOnce()) }
            if (text != null) startRun(text, workspaceMcpGate = false)
        }
    }

    /** User declined: run without the workspace servers (they stay blocked). */
    fun denyWorkspaceMcp() {
        val text = pendingRunText
        pendingRunText = null
        _state.update { it.copy(pendingWorkspaceMcp = null) }
        if (text != null) startRun(text, workspaceMcpGate = false)
    }

    /**
     * Streaming content kept on screen while waiting for the committed row to
     * arrive from Room. Without it, the live bubble disappears at commit and
     * the message pops back in a frame or two later, the list bottom shrinks
     * then regrows, which reads as a flicker/bounce.
     */
    private data class HeldStream(
        val sessionId: String,
        val messageId: String,
        val text: String?,
        val thinking: String?,
    )

    private var heldStream: HeldStream? = null

    init {
        viewModelScope.launch {
            combine(c.settings.settings, c.providers.providers) { s, p -> s to p }
                .collect { (settings, providers) ->
                    _state.update {
                        it.copy(
                            permissionMode = settings.permissionMode,
                            thinkingLevel = settings.thinkingLevel,
                            maxContextTokens = settings.maxContextTokens,
                            maxOutputTokens = settings.maxOutputTokens,
                            maxIterations = settings.maxIterations,
                            providers = providers,
                            activeProviderId = settings.activeProviderId
                                ?: providers.firstOrNull()?.id,
                            activeModel = settings.activeModel,
                            planningModelsEnabled = settings.planningModelsEnabled,
                            planningProviderId = settings.planningProviderId,
                            planningModel = settings.planningModel,
                            executionProviderId = settings.executionProviderId,
                            executionModel = settings.executionModel,
                            planningModelsPromoSeen = settings.planningModelsPromoSeen,
                            forkPromoSeen = settings.forkPromoSeen,
                            voiceEngine = settings.voiceEngine,
                            groqWhisperModel = settings.groqWhisperModel,
                            voicePromoSeen = settings.voicePromoSeen,
                        )
                    }
                }
        }
        viewModelScope.launch {
            c.providers.catalogs.collect { catalogs ->
                _state.update { it.copy(catalogs = catalogs) }
            }
        }
        viewModelScope.launch {
            // Todos are session-owned. sessionIdFlow must be a combine INPUT,
            // a new chat's first run claims the store before the state update
            // lands, and a snapshot read never re-fires (the list stayed empty
            // for the whole run).
            combine(c.todoStore.todos, c.todoStore.owner, sessionIdFlow) { todos, owner, sid ->
                if (owner != null && sid != null && owner == sid) todos else emptyList()
            }.collect { todos ->
                _state.update { it.copy(todos = todos) }
            }
        }

        viewModelScope.launch {
            // "+N −M" chips: per-file edit stats for the bound session.
            sessionIdFlow.flatMapLatest { sid ->
                if (sid == null) flowOf(emptyList())
                else c.sessions.fileEditsFor(sid)
            }.collect { edits ->
                _state.update {
                    it.copy(fileEditsByTurn = edits.groupBy { e -> e.turnId })
                }
            }
        }
        viewModelScope.launch {
            // Exact per-model usage breakdown for this chat session.
            sessionIdFlow.flatMapLatest { sid ->
                if (sid == null) flowOf(emptyList())
                else c.sessions.usageByModelFor(sid)
            }.collect { modelUsage ->
                _state.update { it.copy(sessionModelUsage = modelUsage) }
            }
        }
        viewModelScope.launch {
            c.snippets.snippets.collect { snippets ->
                _state.update { it.copy(snippets = snippets) }
            }
        }
        viewModelScope.launch {
            c.settings.settings.collect {
                _state.update { st -> st.copy(skills = c.skills.list()) }
            }
        }
        viewModelScope.launch {
            c.workspace.currentProject.collect { project ->
                _state.update { it.copy(workspaceName = project.name, skills = c.skills.list()) }
            }
        }
        viewModelScope.launch {
            c.linuxEnv.state.collect { envState ->
                _state.update { it.copy(envState = envState).withCurrentAction() }
            }
        }
        viewModelScope.launch {
            c.shizuku.state.collect { s ->
                _state.update { it.copy(shizukuState = s) }
            }
        }

        // Messages come straight from the DB flow, RunManager writes them
        // during runs, so the UI is just a live mirror. A held streaming
        // bubble is released the moment its committed row arrives.
        viewModelScope.launch {
            sessionIdFlow.flatMapLatest { sid ->
                if (sid == null) {
                    _state.update { it.copy(isLoadingMessages = false) }
                    flowOf(emptyList())
                } else {
                    _state.update { it.copy(isLoadingMessages = it.messages.isEmpty()) }
                    c.sessions.messagesFlow(sid)
                }
            }.collect { msgs ->
                val hold = heldStream
                if (hold != null && msgs.any { it.id == hold.messageId }) {
                    heldStream = null
                    _state.update {
                        it.copy(
                            messages = msgs,
                            isLoadingMessages = false,
                            streamingText = null, streamingThinking = null,
                            streamingMessageId = null, streamingCommitted = false,
                        ).withCurrentAction()
                    }
                } else {
                    _state.update { it.copy(messages = msgs, isLoadingMessages = false) }
                }
            }
        }

        // Session title + usage ride the sessions flow.
        viewModelScope.launch {
            combine(c.sessions.sessions, sessionIdFlow) { list, sid ->
                list.firstOrNull { it.id == sid }
            }.collect { s ->
                if (s != null) {
                    _state.update {
                        it.copy(sessionTitle = s.title, usage = s.toUsageStats())
                    }
                }
            }
        }

        // Live run state from the app-scoped RunManager.
        viewModelScope.launch {
            var wasRunning = false
            sessionIdFlow.flatMapLatest { sid ->
                if (sid == null) flowOf(null) else c.runManager.live(sid)
            }.collect { live ->
                if (live != null) {
                    if (heldStream?.sessionId != null && heldStream?.sessionId != live.sessionId) {
                        // Switched sessions while holding, drop the stale hold.
                        heldStream = null
                    }
                    mirrorLive(live)
                    if (wasRunning && !live.running) {
                        val sid = sessionId
                        if (sid != null) {
                            _state.update {
                                it.copy(turnsWithCheckpoints = refreshCheckpoints(sid))
                            }
                        }
                    }
                    wasRunning = live.running
                }
            }
        }

        if (initialSessionId != null) {
            viewModelScope.launch {
                c.settings.setLastActiveSessionId(initialSessionId)
                val sess = c.sessions.session(initialSessionId)
                _state.update {
                    it.copy(
                        sessionId = initialSessionId,
                        sessionTitle = sess?.title ?: "Chat",
                        turnsWithCheckpoints = refreshCheckpoints(initialSessionId),
                    )
                }
                // If this is a forked/compacted session without a live run yet,
                // compute an initial context estimate so the context bar shows the
                // real size immediately rather than waiting for a turn.
                if (sess != null && (sess.compactionSummary.isNotBlank() || sess.lastInputTokens > 0)) {
                    val fresh = c.engine.estimateFor(
                        com.androidharness.app.agent.ContextHygiene.forModel(
                            with(c.sessions) { historyFor(initialSessionId).second.withoutSubagentTurns() },
                        ),
                        c.workspace.currentOnce(),
                        _state.value.mode,
                        _state.value.permissionMode == PermissionMode.FULL_ACCESS,
                    )
                    _state.update { it.copy(estimate = fresh) }
                }
            }
        }
    }

    private fun SessionEntity.toUsageStats() = UsageStats(
        totalInput = totalInputTokens,
        totalOutput = totalOutputTokens,
        totalCached = totalCachedTokens,
        totalCacheWrite = totalCacheWriteTokens,
        requests = requestCount,
        lastInput = lastInputTokens,
    )

    /**
     * Mirrors a [RunManager.LiveRunState] into [ChatUiState]. When the stream
     * stops (iteration boundary or run end) but the committed row has not yet
     * been delivered by the messages flow, the last content is HELD on screen
     * under the same id, so the live bubble becomes the committed message in
     * place instead of vanishing for a frame.
     */
    private fun mirrorLive(live: RunManager.LiveRunState) {
        _state.update { st ->
            var streamingText = live.streamingText
            var streamingThinking = live.streamingThinking
            var streamingMessageId = live.liveMessageId
            var committed = false
            if (streamingText == null && streamingThinking == null) {
                val hold = heldStream ?: run {
                    // Only hold the stream that was actually just committed,
                    // matches by id so a stale commit id from an earlier
                    // iteration can't freeze the bubble after an error stop.
                    val lastId = live.lastCommittedId
                    val prevText = st.streamingText
                    val prevThinking = st.streamingThinking
                    if (lastId != null && st.streamingMessageId == lastId &&
                        (!prevText.isNullOrBlank() || !prevThinking.isNullOrBlank())
                    ) {
                        HeldStream(live.sessionId, lastId, prevText, prevThinking).also { h ->
                            heldStream = h
                            scheduleHoldRelease(h)
                        }
                    } else null
                }
                if (hold != null && st.messages.none { it.id == hold.messageId }) {
                    streamingText = hold.text
                    streamingThinking = hold.thinking
                    streamingMessageId = hold.messageId
                    committed = true
                } else if (hold != null) {
                    heldStream = null
                }
            } else {
                heldStream = null
            }
            st.copy(
                busy = live.running,
                streamingText = streamingText,
                streamingThinking = streamingThinking,
                streamingMessageId = streamingMessageId,
                streamingCommitted = committed,
                currentTurnId = live.turnId,
                runningCalls = live.runningCalls,
                pendingApproval = live.pendingApproval,
                pendingQuestion = live.pendingQuestion,
                pendingEnvironment = live.pendingEnvironment,
                currentToolAction = live.currentToolAction,
                subagentSteps = live.subagentSteps,
                thinkingStartedAt = live.thinkingStartedAt,
                retryStatus = live.retryStatus,
                queuedMessage = live.queuedMessage,
                pendingPlan = live.pendingPlan,
                estimate = live.estimate,
                compactionNote = live.compactionNote,
                error = live.error ?: st.error,
            ).withCurrentAction()
        }
    }

    /** Failsafe: a held bubble must not outlive a slow/failed DB delivery. */
    private fun scheduleHoldRelease(hold: HeldStream) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(HOLD_TIMEOUT_MS)
            if (heldStream === hold && _state.value.messages.none { it.id == hold.messageId }) {
                heldStream = null
                _state.update {
                    it.copy(
                        streamingText = null, streamingThinking = null,
                        streamingMessageId = null, streamingCommitted = false,
                    ).withCurrentAction()
                }
            }
        }
    }

    private fun ChatUiState.withCurrentAction(): ChatUiState = copy(currentAction = computeCurrentAction())

    /**
     * Human-readable description of what the agent is doing right now.
     * [ChatUiState.currentToolAction] lingers briefly after a tool finishes so
     * fast local operations ("Editing app.py…") stay visible before "Thinking…".
     * Computed once per state emission, not per recomposition.
     */
    private fun ChatUiState.computeCurrentAction(): String? = when {
        pendingQuestion != null -> "Waiting for your answer"
        pendingApproval != null -> "Waiting for your approval"
        pendingEnvironment != null -> when {
            envState is com.androidharness.app.data.env.EnvState.NotInstalled -> "Waiting for your approval"
            envState is com.androidharness.app.data.env.EnvState.Failed -> "Linux install failed"
            pendingEnvironment.repair -> when {
                envState is com.androidharness.app.data.env.EnvState.Downloading ||
                    envState is com.androidharness.app.data.env.EnvState.Installing ->
                    "Repairing Linux environment…"
                else -> "Waiting for your approval"
            }
            else -> "Installing Linux environment…"
        }
        currentToolAction != null -> currentToolAction
        retryStatus != null -> retryStatus
        runningCalls.isNotEmpty() -> describeToolCall(runningCalls.last())
        streamingThinking != null && streamingText.isNullOrEmpty() -> "Thinking…"
        streamingText != null -> "Writing response…"
        busy -> if (mode == AgentMode.PLAN) "Planning…" else "Working…"
        else -> null
    }

    // ------------------------------------------------------------------
    // Sending & slash commands
    // ------------------------------------------------------------------

    fun send(rawText: String) {
        val trimmed = rawText.trim()
        // File-only sends are legitimate: the attachments carry the message.
        if (trimmed.isEmpty() && _state.value.fileAttachments.isEmpty()) return

        val resolved = if (trimmed.startsWith("/")) {
            SlashCommands.resolve(
                input = trimmed,
                skillNames = c.skills.slashNames(),
                snippetBodies = _state.value.snippets.associate { it.name to it.body },
                skillContent = { name -> c.skills.view(name).getOrNull()?.content },
            )
        } else {
            SlashCommands.Result(SlashCommands.Kind.PLAIN, agentText = trimmed)
        }

        when (resolved.kind) {
            SlashCommands.Kind.CLEAR -> {
                _navEvents.tryEmit(NavEvent.NewChat)
                return
            }
            SlashCommands.Kind.COMPACT -> {
                viewModelScope.launch { forceCompact() }
                return
            }
            SlashCommands.Kind.COST -> {
                _state.update { it.copy(showCostDialog = true) }
                return
            }
            SlashCommands.Kind.SKILLS -> {
                _state.update { it.copy(showSkillsSheet = true, skills = c.skills.list()) }
                return
            }
            SlashCommands.Kind.ENV -> {
                _state.update { it.copy(showEnvSheet = true) }
                return
            }
            SlashCommands.Kind.UNKNOWN -> {
                _state.update { it.copy(error = resolved.error) }
                return
            }
            SlashCommands.Kind.PLAIN,
            SlashCommands.Kind.INIT,
            SlashCommands.Kind.DOCTOR,
            SlashCommands.Kind.SKILL,
            SlashCommands.Kind.SNIPPET,
            // /plan is handled after the when: mode flips, then the expanded
            // skill prompt starts/queues like any other agent turn.
            SlashCommands.Kind.PLAN,
            -> Unit
        }

        // /plan flips the header into Plan mode before the run starts, so the
        // engine launches read-only (tool registry restricted to Plan schema).
        if (resolved.kind == SlashCommands.Kind.PLAN) {
            setMode(AgentMode.PLAN)
        }

        val agentText = resolved.agentText ?: return
        val sid = sessionId
        if (sid != null && c.runManager.isRunning(sid)) {
            // Queue the expanded text; the engine picks it up before its next turn.
            // Local slash commands never reach here, so /cost /skills /clear keep working mid-run.
            val target = SlashCommands.dispatchTarget(isRunning = true, agentText = agentText)
            c.runManager.inject(sid, target.text)
            return
        }
        startRun(agentText)
    }

    fun cancelQueuedMessage() {
        val sid = sessionId ?: return
        c.runManager.cancelQueued(sid)
    }

    /**
     * Stops the in-flight run, then sends the queued text as a new turn.
     * Default send-while-busy only injects at the next iteration.
     */
    fun steerQueuedMessage() {
        val sid = sessionId ?: return
        val text = _state.value.queuedMessage?.trim().orEmpty()
        if (text.isEmpty() || steering) return
        steering = true
        c.runManager.cancelQueued(sid)
        viewModelScope.launch {
            try {
                c.runManager.stopAndJoin(sid)
                startRun(text)
            } finally {
                steering = false
            }
        }
    }

    fun addSnippet(name: String, body: String) {
        viewModelScope.launch { c.snippets.add(name, body) }
    }

    fun deleteSnippet(snippet: SnippetEntity) {
        viewModelScope.launch { c.snippets.delete(snippet) }
    }

    fun dismissCostDialog() {
        _state.update { it.copy(showCostDialog = false) }
    }

    fun dismissSkillsSheet() {
        _state.update { it.copy(showSkillsSheet = false) }
    }

    fun openSkillsSheet() {
        _state.update { it.copy(showSkillsSheet = true, skills = c.skills.list()) }
    }

    // ------------------------------------------------------------------
    // Attachments
    // ------------------------------------------------------------------

    fun attachImage(uri: Uri) {
        // Import once, off the main thread (bitmap decode + compress); the
        // stored reference is reused when the message is sent.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val stored = c.images.import(uri)
            if (stored == null) {
                _state.update { it.copy(error = "Could not load that image.") }
                return@launch
            }
            pendingAttachments += ImageRef(stored.file.name, stored.mime)
            _state.update { it.copy(attachments = it.attachments + stored.file.name) }
        }
    }

    fun removeAttachment(index: Int) {
        if (index !in _state.value.attachments.indices) return
        val removed = pendingAttachments.getOrNull(index)
        if (removed != null) {
            pendingAttachments.removeAt(index)
            c.images.delete(removed.name)
        }
        _state.update { it.copy(attachments = it.attachments.toMutableList().apply { removeAt(index) }) }
    }

    // ------------------------------------------------------------------
    // File attachments (non-image): text-like files ride inline in the
    // message, everything else is copied into the workspace so the agent's
    // shell tools can inspect it.
    // ------------------------------------------------------------------

    fun attachFile(uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val resolver = c.appContext.contentResolver
                val name = queryDisplayName(resolver, uri) ?: "file"
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                if (mime.startsWith("image/")) {
                    attachImage(uri)
                    return@launch
                }
                val size = querySize(resolver, uri)
                if (FileAttachments.isPdf(name, mime)) {
                    val pdfExtract = resolver.openInputStream(uri)?.use { stream ->
                        PdfTextExtractor.extract(stream)
                    }
                    if (pdfExtract != null && pdfExtract.text.isNotBlank()) {
                        val pageLabel = if (pdfExtract.pageCount == 1) "1 page" else "${pdfExtract.pageCount} pages"
                        val sizeLabel = "${FileAttachments.humanBytes(size ?: pdfExtract.text.length.toLong())}, $pageLabel"
                        addFileAttachment(
                            FileAttachment(
                                name, "application/pdf", sizeLabel,
                                pdfExtract.text, null,
                            ),
                        )
                        return@launch
                    }
                }
                if (FileAttachments.isTextLike(name, mime)) {
                    val text = readTextLimited(resolver, uri)
                    if (text != null) {
                        addFileAttachment(
                            FileAttachment(
                                name, mime, FileAttachments.humanBytes(size ?: text.length.toLong()),
                                text, null,
                            ),
                        )
                        return@launch
                    }
                }
                copyAttachmentToWorkspace(resolver, uri, name, mime, size)
            }.onFailure {
                _state.update { st -> st.copy(error = "Could not attach that file: ${it.message}") }
            }
        }
    }

    fun removeFileAttachment(index: Int) {
        val removed = _state.value.fileAttachments.getOrNull(index) ?: return
        if (index < pendingFileAttachments.size) pendingFileAttachments.removeAt(index)
        removed.workspacePath?.let { path ->
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { c.workspace.currentOnce().resolve(path).delete() }
            }
        }
        _state.update { it.copy(fileAttachments = it.fileAttachments.toMutableList().apply { removeAt(index) }) }
    }

    /** Workspace file list for the @-mention picker, cached per workspace. */
    fun loadMentionFiles() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val fs = runCatching { c.workspace.currentOnce() }.getOrNull() ?: return@launch
            if (fs.displayPath == mentionCacheKey) return@launch
            val files = runCatching {
                fs.walk("")
                    .filter { it.isFile }
                    .map { it.relPath }
                    .take(600)
                    .toList()
                    .sorted()
            }.getOrDefault(emptyList())
            mentionCacheKey = fs.displayPath
            _state.update { it.copy(mentionFiles = files) }
        }
    }

    private fun addFileAttachment(file: FileAttachment) {
        pendingFileAttachments += file
        _state.update { it.copy(fileAttachments = it.fileAttachments + file) }
    }

    private suspend fun copyAttachmentToWorkspace(
        resolver: android.content.ContentResolver,
        uri: Uri,
        name: String,
        mime: String,
        size: Long?,
    ) {
        val cap = FileAttachments.COPY_BYTE_LIMIT
        if (size != null && size > cap) {
            throw IllegalStateException("file is larger than ${FileAttachments.humanBytes(cap)}")
        }
        val fs = c.workspace.currentOnce()
        val dir = fs.resolve(".harness/attachments")
        dir.mkdirs()
        val safe = name.replace(Regex("[^A-Za-z0-9._\\- ()]"), "_").take(80).ifBlank { "file" }
        val stamp = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val unique = "$stamp-$safe"
        val node = dir.createFile(unique)
        val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
        if (bytes.size > cap) {
            runCatching { node.delete() }
            throw IllegalStateException("file is larger than ${FileAttachments.humanBytes(cap)}")
        }
        node.writeBytes(bytes)
        addFileAttachment(
            FileAttachment(
                name, mime, FileAttachments.humanBytes(size ?: bytes.size.toLong()),
                null, ".harness/attachments/$unique",
            ),
        )
    }

    /** Decoded text up to the inline limit, or null (too big or binary content). */
    private fun readTextLimited(resolver: android.content.ContentResolver, uri: Uri): String? = runCatching {
        resolver.openInputStream(uri)!!.use { input ->
            val max = FileAttachments.INLINE_CHAR_LIMIT
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (buffer.size() + n > max) return null
                buffer.write(chunk, 0, n)
            }
            val text = buffer.toString("UTF-8")
            // Binary misdetected as text would carry NULs; let it take the copy path.
            if (text.contains('\u0000')) null else text
        }
    }.getOrNull()

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

    private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else null
        }
    }.getOrNull()

    // ------------------------------------------------------------------
    // Run lifecycle (delegated to the app-scoped RunManager)
    // ------------------------------------------------------------------

    private fun startRun(text: String, workspaceMcpGate: Boolean = true) {
        val s0 = _state.value
        // Separate planning/execution models: plan-mode runs use the planning
        // slot, everything else the execution one. A slot without a provider
        // falls back to the active provider, keeping its model override.
        val provider = when {
            s0.planningModelsEnabled && s0.mode == AgentMode.PLAN && s0.planningProviderId != null ->
                s0.providers.firstOrNull { it.id == s0.planningProviderId } ?: s0.activeProvider
            s0.planningModelsEnabled && s0.mode == AgentMode.ACT && s0.executionProviderId != null ->
                s0.providers.firstOrNull { it.id == s0.executionProviderId } ?: s0.activeProvider
            else -> s0.activeProvider
        } ?: run {
            _state.update { it.copy(error = "No provider configured. Add one from the Providers screen first.") }
            return
        }
        val roleModel = when {
            s0.planningModelsEnabled && s0.mode == AgentMode.PLAN -> s0.planningModel
            s0.planningModelsEnabled && s0.mode == AgentMode.ACT -> s0.executionModel
            else -> s0.activeModel
        }
        val apiKey = c.providers.apiKey(provider.id)
        if (apiKey.isNullOrBlank()) {
            _state.update { it.copy(error = "Provider \"${provider.name}\" has no API key. Edit it on the Providers screen.") }
            return
        }

        val imageRefs = pendingAttachments.toList()
        pendingAttachments.clear()
        val fileItems = pendingFileAttachments.toList()
        pendingFileAttachments.clear()
        _state.update { it.copy(attachments = emptyList(), fileAttachments = emptyList(), error = null) }
        // File attachments ride after the user's text as model-readable blocks.
        val payload = FileAttachments.buildMessageSuffix(fileItems)
            .takeIf { it.isNotEmpty() }
            ?.let { suffix -> if (text.isBlank()) suffix else "$text\n\n$suffix" }
            ?: text

        viewModelScope.launch {
            // Security gate (battery D1): a workspace .harness/mcp.json never
            // spawns commands until this exact file content was approved. The
            // dialog offers approve (and continue) or run without those servers.
            if (workspaceMcpGate) {
                val unapproved = runCatching {
                    c.mcp.unapprovedWorkspaceServers(c.workspace.currentOnce())
                }.getOrDefault(emptyList())
                if (unapproved.isNotEmpty()) {
                    pendingRunText = payload
                    _state.update { it.copy(pendingWorkspaceMcp = unapproved.map { s -> s.name }) }
                    return@launch
                }
            }
            // A model picked from the provider's catalog overrides its default.
            val effectiveConfig = roleModel
                ?.takeIf { it.isNotBlank() }
                ?.let { provider.copy(model = it) } ?: provider
            val sid = c.runManager.startRun(
                sessionId = sessionId,
                text = payload,
                imageRefs = imageRefs,
                config = effectiveConfig,
                apiKey = apiKey,
                permissionMode = s0.permissionMode,
                mode = s0.mode,
                maxOutputTokens = s0.maxOutputTokens,
                maxContextTokens = s0.maxContextTokens,
                thinking = s0.thinkingLevel,
                maxIterations = s0.maxIterations,
            )
            if (sessionId == null) {
                sessionId = sid
                sessionIdFlow.value = sid
                _state.update { it.copy(sessionId = sid, sessionTitle = payload.take(48)) }
                viewModelScope.launch {
                    c.settings.setLastActiveSessionId(sid)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // User actions (delegated to RunManager)
    // ------------------------------------------------------------------

    fun approve(rememberForSession: Boolean) {
        val sid = sessionId ?: return
        c.runManager.approve(sid, rememberForSession)
    }

    fun deny() {
        val sid = sessionId ?: return
        c.runManager.deny(sid)
    }

    fun answerQuestion(answer: String) {
        val sid = sessionId ?: return
        c.runManager.answerQuestion(sid, answer)
    }

    fun approveEnvironmentInstall() {
        val sid = sessionId ?: return
        c.runManager.approveEnvironmentInstall(sid)
    }

    fun denyEnvironmentInstall() {
        val sid = sessionId ?: return
        c.runManager.denyEnvironmentInstall(sid)
    }

    fun dismissEnvSheet() {
        _state.update { it.copy(showEnvSheet = false) }
    }

    fun grantShizuku() {
        c.shizuku.requestPermission()
    }

    fun stop() {
        val sid = sessionId ?: return
        c.runManager.stop(sid)
    }

    fun setMode(mode: AgentMode) {
        if (_state.value.mode == mode) return
        _state.update { it.copy(mode = mode).withCurrentAction() }
        // Switching into Plan mode is the moment the intro is useful: offer
        // it once, before the mode toast would matter.
        val s = _state.value
        if (mode == AgentMode.PLAN && !s.planningModelsEnabled && !s.planningModelsPromoSeen) {
            _state.update { it.copy(showPlanningPromo = true) }
            return
        }
        announceMode(mode)
    }

    /** Approve the plan produced by a PLAN-mode run and execute it. */
    fun executePendingPlan() {
        val sid = sessionId ?: return
        c.runManager.clearPendingPlan(sid)
        _state.update { it.copy(mode = AgentMode.ACT, pendingPlan = null).withCurrentAction() }
        announceMode(AgentMode.ACT)
        startRun("The plan above is approved. Execute it step by step now.")
    }

    /**
     * One-shot toast naming the model a mode switch will use, so the
     * separate planning/execution slots are visible at the moment they
     * matter. Quiet when the feature is off.
     */
    private fun announceMode(mode: AgentMode) {
        val s = _state.value
        if (!s.planningModelsEnabled) return
        val label = if (mode == AgentMode.PLAN) "Planning mode" else "Execute mode"
        val model = when (mode) {
            AgentMode.PLAN ->
                s.planningModel?.takeIf { it.isNotBlank() }
                    ?: s.providers.firstOrNull { it.id == s.planningProviderId }?.model
                    ?: s.effectiveModel
            AgentMode.ACT ->
                s.executionModel?.takeIf { it.isNotBlank() }
                    ?: s.providers.firstOrNull { it.id == s.executionProviderId }?.model
                    ?: s.effectiveModel
        }?.substringAfterLast('/') ?: "default model"
        _state.update { it.copy(modeToast = "$label: $model") }
    }

    fun clearModeToast() {
        if (_state.value.modeToast != null) {
            _state.update { it.copy(modeToast = null) }
        }
    }

    /** The one-time planning-models dialog was dismissed (either button). */
    fun dismissPlanningPromo() {
        _state.update { it.copy(showPlanningPromo = false) }
        viewModelScope.launch { c.settings.setPlanningModelsPromoSeen(true) }
    }

    /** User tapped fork on an assistant message. Shows promo if not seen yet, otherwise forks. */
    fun requestFork(message: ChatMessage, onForked: (String) -> Unit) {
        val s = _state.value
        if (!s.forkPromoSeen) {
            _state.update { it.copy(showForkPromo = true, pendingForkMessage = message) }
        } else {
            val mid = message.id ?: return
            forkFrom(mid, onForked)
        }
    }

    /** Confirm fork after promo or directly. */
    fun confirmForkFromPromo(onForked: (String) -> Unit) {
        val msg = _state.value.pendingForkMessage
        _state.update { it.copy(showForkPromo = false, pendingForkMessage = null) }
        viewModelScope.launch {
            c.settings.setForkPromoSeen(true)
            val mid = msg?.id ?: return@launch
            forkFrom(mid, onForked)
        }
    }

    fun dismissForkPromo() {
        _state.update { it.copy(showForkPromo = false, pendingForkMessage = null) }
        viewModelScope.launch { c.settings.setForkPromoSeen(true) }
    }

    fun promptVoicePromo() {
        _state.update { it.copy(showVoicePromo = true) }
    }

    fun dismissVoicePromo() {
        _state.update { it.copy(showVoicePromo = false) }
        viewModelScope.launch { c.settings.setVoicePromoSeen(true) }
    }

    fun selectVoiceEngineFromPromo(engine: String) {
        _state.update { it.copy(showVoicePromo = false, voiceEngine = engine) }
        viewModelScope.launch {
            c.settings.setVoiceEngine(engine)
            c.settings.setVoicePromoSeen(true)
        }
    }

    private fun forkFrom(messageId: String, onForked: (String) -> Unit) {
        val sid = sessionId ?: return
        viewModelScope.launch {
            try {
                val newSid = c.sessions.forkSession(sid, messageId)
                _state.update { it.copy(modeToast = "Branched into new chat") }
                onForked(newSid)
            } catch (e: Exception) {
                _state.update { it.copy(error = "Fork failed: ${e.message}") }
            }
        }
    }

    fun discardPendingPlan() {
        val sid = sessionId ?: return
        c.runManager.clearPendingPlan(sid)
        _state.update { it.copy(pendingPlan = null) }
    }

    // ------------------------------------------------------------------
    // Undo / edit
    // ------------------------------------------------------------------

    /** Per-file line in the undo confirmation preview. */
    data class RewindFileStat(
        val relPath: String,
        val added: Long,
        val removed: Long,
        /** The agent created this file during the undone window → rewind deletes it. */
        val willBeDeleted: Boolean,
        /** Whether the file exists on disk right now (missing ⇒ rewind restores it). */
        val existsNow: Boolean,
    )

    /** What one undo tap will do, feeds the confirmation dialog. */
    data class RewindPreview(
        val files: List<RewindFileStat>,
        val messagesDeleted: Int,
        val turns: Int,
    )

    /**
     * Preview for the undo confirmation dialog: cumulative per-file stats of
     * the chosen turn and every later one (undo rewinds through the present),
     * plus how many messages will roll back.
     */
    suspend fun rewindPreview(turnId: String): RewindPreview? {
        val sid = sessionId ?: return null
        val ordered = runCatching { c.checkpoints.turnsOrdered(sid) }.getOrDefault(emptyList())
        val idx = ordered.indexOfFirst { it.turnId == turnId }
        val affected = if (idx >= 0) ordered.drop(idx).map { it.turnId } else listOf(turnId)

        val sums = HashMap<String, LongArray>()
        c.sessions.fileEditsForTurns(sid, affected).forEach { e ->
            val key = com.androidharness.app.workspace.normalizeRelPath(e.relPath)
            val acc = sums.getOrPut(key) { longArrayOf(0, 0) }
            acc[0] += e.added
            acc[1] += e.removed
        }

        // Existed-before from the earliest checkpoint per path: false means the
        // agent created the file inside the undone window.
        val existedBefore = HashMap<String, Boolean>()
        runCatching { c.checkpoints.entitiesForTurns(sid, affected) }.getOrDefault(emptyList())
            .forEach { cp ->
                existedBefore.putIfAbsent(
                    com.androidharness.app.workspace.normalizeRelPath(cp.relPath),
                    cp.existedBefore,
                )
            }

        val fs = c.workspace.currentOnce()
        val files = (sums.keys + existedBefore.keys).map { path ->
            val acc = sums[path] ?: longArrayOf(0, 0)
            val created = existedBefore[path] == false
            val existsNow = runCatching { fs?.resolve(path)?.exists == true }.getOrDefault(true)
            RewindFileStat(path, acc[0], acc[1], willBeDeleted = created && existsNow, existsNow = existsNow)
        }.sortedBy { it.relPath }

        val msgs = runCatching { c.sessions.messages(sid) }.getOrDefault(emptyList())
        val boundary = msgs.indexOfFirst { it.turnId == turnId && it.role != Role.USER }
        val messagesDeleted = if (boundary >= 0) msgs.size - boundary else 0

        return RewindPreview(files, messagesDeleted, affected.size)
    }

    /**
     * Performs the confirmed undo: files back to their pre-turn state, the
     * chat rolled back to before this turn's agent output, derived stats
     * refreshed. Returns a user-facing summary for the snackbar.
     */
    suspend fun performRewind(turnId: String): String {
        val sid = sessionId ?: return "No active chat."
        if (_state.value.busy || sid in c.runManager.runningSessionIds.value) {
            return "Stop the running agent first."
        }
        val summary = runCatching { c.runManager.rewindFromTurn(sid, turnId) }
            .getOrElse { e -> return "Rewind failed: ${e.message}" }
        _state.update { it.copy(turnsWithCheckpoints = refreshCheckpoints(sid)) }
        return when {
            summary.filesTouched == 0 && summary.messagesDeleted == 0 -> "Nothing to rewind for that turn."
            summary.filesFailed > 0 ->
                "Undo done, with issues: ${summary.filesRestored} file(s) restored, " +
                    "${summary.messagesDeleted} message(s) removed, ${summary.filesFailed} could not be restored."
            else ->
                "Undo complete: ${summary.filesRestored} file(s) restored, " +
                    "${summary.messagesDeleted} message(s) removed."
        }
    }

    /**
     * Editing a past user message: rewinds the workspace to before that
     * message, truncates the conversation there, then resends the edited text
     * as a fresh run. The UI warns before calling this.
     */
    fun editAndResend(message: ChatMessage, newText: String) {
        val sid = sessionId ?: return
        val mid = message.id ?: return
        if (newText.isBlank()) return
        viewModelScope.launch {
            runCatching { c.runManager.rewindAndTruncate(sid, mid) }
                .onFailure { e -> _state.update { st -> st.copy(error = "Could not rewind: ${e.message}") } }
            startRun(newText)
        }
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    private suspend fun refreshCheckpoints(sessionId: String): Set<String> =
        runCatching { c.checkpoints.turnsWithCheckpoints(sessionId) }.getOrDefault(emptySet())

    private suspend fun forceCompact() {
        val sid = sessionId ?: return
        val provider = _state.value.activeProvider ?: return
        val apiKey = c.providers.apiKey(provider.id) ?: return
        // Trigger compaction by temporarily pretending the context is full:
        // Simplest correct approach, ask the engine for a summary of all history.
        // Subagent inner turns are excluded (same rule as new runs).
        val history = with(c.sessions) { historyFor(sid).second.withoutSubagentTurns() }
        if (history.size < 4) {
            _state.update { it.copy(error = "Not enough history to compact.") }
            return
        }
        _state.update { it.copy(busy = true, compactionNote = "Compacting conversation…") }
        c.runManager.acquireKeepalive()
        try {
            val summary = StringBuilder()
            com.androidharness.app.llm.ProviderFactory.create(provider.type).streamChat(
                provider, apiKey,
                "Summarize this coding-agent conversation compactly. Preserve: the user's goal, " +
                    "files created/modified and their paths, key decisions, pending work and next steps. " +
                    "Output plain notes only.",
                history, emptyList(),
                RequestOptions(maxOutputTokens = 1_500, thinking = ThinkingLevel.OFF),
            ).collect { ev ->
                when (ev) {
                    is com.androidharness.app.llm.StreamEvent.TextDelta -> summary.append(ev.text)
                    is com.androidharness.app.llm.StreamEvent.Batch -> ev.events.forEach { nested ->
                        if (nested is com.androidharness.app.llm.StreamEvent.TextDelta) {
                            summary.append(nested.text)
                        }
                    }
                    else -> {}
                }
            }
            if (summary.isNotBlank()) {
                c.sessions.setCompaction(sid, summary.toString(), System.currentTimeMillis())
                c.sessions.addMessage(
                    sid,
                    com.androidharness.app.agent.ContextHygiene.summaryMessage(summary.toString()),
                )
                c.sessions.addMessage(sid, com.androidharness.app.agent.ContextHygiene.compactionNotice())
                // Refresh the context panel now: usage.lastInput still carries
                // the pre-compact request size until the next real request.
                val fresh = c.engine.estimateFor(
                    com.androidharness.app.agent.ContextHygiene.forModel(
                        with(c.sessions) { historyFor(sid).second.withoutSubagentTurns() },
                    ),
                    c.workspace.currentOnce(),
                    _state.value.mode,
                    _state.value.permissionMode == PermissionMode.FULL_ACCESS,
                )
                _state.update { it.copy(estimate = fresh) }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Compaction failed") }
        } finally {
            _state.update { it.copy(busy = false, compactionNote = null) }
            c.runManager.releaseKeepalive()
        }
    }

    fun setPermissionMode(mode: PermissionMode) {
        _state.update { it.copy(permissionMode = mode) }
        viewModelScope.launch { c.settings.setPermissionMode(mode) }
    }

    fun setThinkingLevel(level: ThinkingLevel) {
        // Resolve against the active model's real vocabulary (Hermes-style
        // clamp): a non-native rung stores as the nearest weaker native one.
        viewModelScope.launch {
            ThinkingSpecs.setClamped(
                c.settings,
                _state.value.effectiveModel,
                com.androidharness.app.llm.ModelsDev.providerKeyFor(_state.value.activeProvider?.baseUrl),
                level,
            )
            _state.update { it.copy(thinkingLevel = c.settings.settings.firstOrNull()?.thinkingLevel ?: level) }
        }
    }

    fun setActiveProvider(id: String) {
        val s = _state.value
        viewModelScope.launch {
            if (s.planningModelsEnabled) {
                if (s.mode == AgentMode.PLAN) {
                    c.settings.setPlanningModel(id, null)
                } else {
                    c.settings.setExecutionModel(id, null)
                }
            } else {
                // Switching provider resets any model override, the old pick
                // belonged to the previous endpoint's catalog.
                c.settings.setActiveModel(null)
                c.settings.setActiveProvider(id)
            }
            // The new model may not speak the stored thinking tier, adapt.
            val provider = _state.value.providers.firstOrNull { it.id == id }
            ThinkingSpecs.clampStoredLevel(
                c.settings,
                provider?.model,
                com.androidharness.app.llm.ModelsDev.providerKeyFor(provider?.baseUrl),
            )
        }
    }

    /** Selects [model] under provider [providerId] (null = its saved default). */
    fun selectModel(providerId: String, model: String?) {
        val s = _state.value
        viewModelScope.launch {
            if (s.planningModelsEnabled) {
                if (s.mode == AgentMode.PLAN) {
                    c.settings.setPlanningModel(providerId, model)
                } else {
                    c.settings.setExecutionModel(providerId, model)
                }
            } else {
                c.settings.setActiveProvider(providerId)
                c.settings.setActiveModel(model)
            }
            val provider = _state.value.providers.firstOrNull { it.id == providerId }
            ThinkingSpecs.clampStoredLevel(
                c.settings,
                model?.takeIf { it.isNotBlank() } ?: provider?.model,
                com.androidharness.app.llm.ModelsDev.providerKeyFor(provider?.baseUrl),
            )
        }
    }

    /**
     * Add or update a provider (plus its API key) without leaving chat.
     * Mirrors the Providers screen: a first-ever provider becomes active.
     */
    fun upsertProvider(
        existing: ProviderConfig?,
        name: String,
        type: ProviderType,
        baseUrl: String,
        model: String,
        apiKey: String,
    ) {
        viewModelScope.launch {
            if (existing == null) {
                val created = c.providers.add(name, type, baseUrl, model, apiKey)
                if (_state.value.activeProviderId == null) {
                    c.settings.setActiveProvider(created.id)
                }
            } else {
                c.providers.update(
                    existing.copy(name = name, type = type, baseUrl = baseUrl, model = model),
                    apiKey,
                )
            }
        }
    }

    fun deleteProvider(providerId: String) {
        viewModelScope.launch { c.providers.delete(providerId) }
    }

    /** Stored API key for a provider (filled into the edit form). */
    fun providerApiKey(providerId: String): String? = c.providers.apiKey(providerId)

    // ---- Workspace switching (chat overflow + sheet) ------------------------

    /** The shared container, for host-side pickers (AddWorkspaceDialog). */
    val container: AppContainer get() = c

    val workspaces: Flow<List<com.androidharness.app.data.db.ProjectEntity>>
        get() = c.workspace.projects

    val activeWorkspace: Flow<com.androidharness.app.data.db.ProjectEntity>
        get() = c.workspace.currentProject

    fun workspaceDescription(project: com.androidharness.app.data.db.ProjectEntity) =
        c.workspace.describe(project)

    fun setWorkspace(projectId: String) {
        viewModelScope.launch { c.workspace.setActiveProject(projectId) }
    }

    fun addSafWorkspace(uri: Uri) {
        viewModelScope.launch { c.workspace.addPickedFolder(uri) }
    }

    /**
     * Refetches a provider's model catalog. Returns an error message on
     * failure, null on success (catalog persisted and published to state).
     */
    suspend fun refreshCatalog(providerId: String): String? {
        val provider = _state.value.providers.firstOrNull { it.id == providerId }
            ?: return "Unknown provider"
        val apiKey = c.providers.apiKey(providerId)
        if (apiKey.isNullOrBlank()) return "No API key for this provider"
        return when (val result = com.androidharness.app.llm.ModelCatalog.listModels(provider, apiKey)) {
            is com.androidharness.app.llm.ModelCatalog.Result.Models -> {
                c.providers.saveCatalog(providerId, result.models)
                null
            }
            is com.androidharness.app.llm.ModelCatalog.Result.Failed -> result.message
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        /** Max time a just-committed stream may be held while Room delivers the row. */
        private const val HOLD_TIMEOUT_MS = 2_000L

        fun factory(container: AppContainer, sessionId: String?) = viewModelFactory {
            initializer { ChatViewModel(container, sessionId) }
        }
    }
}
