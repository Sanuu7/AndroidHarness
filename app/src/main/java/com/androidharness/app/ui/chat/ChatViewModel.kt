package com.androidharness.app.ui.chat

import com.androidharness.app.R
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
import com.androidharness.app.data.db.MessagePosition
import com.androidharness.app.data.db.SnippetEntity
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.llm.RequestOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    data class OpenSession(val id: String) : NavEvent
    data class SelectSection(val kind: com.androidharness.app.data.ConversationKind) : NavEvent
}

enum class ModelSelectionTarget {
    ACTIVE,
    PLANNING,
    EXECUTION,
}

data class ChatUiState(
    val sessionId: String? = null,
    val sessionTitle: String = "New chat",
    val conversation: com.androidharness.app.data.ConversationConfig = com.androidharness.app.data.ConversationConfig(),
    val conversationReady: Boolean = true,
    val conversationSaving: Boolean = false,
    val chatSettingsError: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    /** True while messages are being initially loaded from Room for this session. */
    val isLoadingMessages: Boolean = false,
    val hasOlderMessages: Boolean = false,
    val isLoadingOlderMessages: Boolean = false,
    val streamingText: String? = null,
    val streamingThinking: String? = null,
    /** Stable id of the streaming message; the committed row reuses it. */
    val streamingMessageId: String? = null,
    /** True while held content stands in for a just-committed row not yet delivered by Room. */
    val streamingCommitted: Boolean = false,
    /** Turn currently being streamed; keys the live items in the message list. */
    val currentTurnId: String? = null,
    val runningCalls: List<ToolCallData> = emptyList(),
    val codeDrafts: Map<String, com.androidharness.app.agent.LiveCodeDraft> = emptyMap(),
    val packageProgress: Map<String, com.androidharness.app.data.env.PackageInstallStatus> = emptyMap(),
    val showLiveCode: Boolean = true,
    val pendingApproval: ApprovalRequest? = null,
    val pendingQuestion: QuestionRequest? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val permissionMode: PermissionMode = PermissionMode.CONFIRM_RISKY,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    val searchDepth: com.androidharness.app.data.SearchDepth = com.androidharness.app.data.SearchDepth.AUTO,
    val reasoningConfigs: Map<String, com.androidharness.app.agent.CustomReasoningConfig> = emptyMap(),
    val customThinkingLevels: Map<String, String> = emptyMap(),
    val mode: AgentMode = AgentMode.ACT,
    val pendingPlan: String? = null,
    val queuedMessage: String? = null,
    val taskControl: com.androidharness.app.agent.TaskRecord = com.androidharness.app.agent.TaskRecord(),
    val fallbackDefaults: com.androidharness.app.llm.FallbackPolicy = com.androidharness.app.llm.FallbackPolicy(),
    val attachments: List<String> = emptyList(), // display names of picked images
    val fileAttachments: List<FileAttachment> = emptyList(),
    val attachmentImports: List<AttachmentImport> = emptyList(),
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
    val thinkingTiming: com.androidharness.app.agent.ThinkingTiming = com.androidharness.app.agent.ThinkingTiming(),
    val reasoningTokens: Int? = null,
    /** Wall-clock start of the running turn; null once the turn ends. */
    val turnStartedAt: Long? = null,
    /** Per-turn file-edit stats ("+N −M" chips), keyed by turnId. */
    val fileEditsByTurn: Map<String, List<com.androidharness.app.data.db.FileEditEntity>> = emptyMap(),
    /** Per-request cache stats, grouped by the owning task turn. */
    val turnCacheUsage: Map<String, List<com.androidharness.app.data.db.UsageEventEntity>> = emptyMap(),
    /** Per-model token usage breakdown for this chat session. */
    val sessionModelUsage: List<com.androidharness.app.data.db.ModelUsagePojo> = emptyList(),
    /** Model override picked from the active provider's catalog. */
    val activeModel: String? = null,
    val sameModelAcrossChats: Boolean = true,
    val modelSelectionReady: Boolean = false,
    val modelSelectionBusy: Boolean = false,
    val modelSelectionError: String? = null,
    /** Separate-model-per-mode settings, mirrored from AppSettings. */
    val planningModelsEnabled: Boolean = false,
    val dualPlanning: Boolean = false,
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
    val installingEnvironment: com.androidharness.app.agent.EnvironmentRequest? = null,
    val shizukuState: com.androidharness.app.data.env.ShizukuState =
        com.androidharness.app.data.env.ShizukuState.NOT_INSTALLED,
) {
    val activeProvider: ProviderConfig?
        get() = when {
            dualPlanning && mode == AgentMode.PLAN && planningProviderId != null ->
                providers.firstOrNull { it.id == planningProviderId }
                    ?: if (sameModelAcrossChats) providers.firstOrNull { it.id == activeProviderId } else null
            dualPlanning && mode == AgentMode.ACT && executionProviderId != null ->
                providers.firstOrNull { it.id == executionProviderId }
                    ?: if (sameModelAcrossChats) providers.firstOrNull { it.id == activeProviderId } else null
            else -> providers.firstOrNull { it.id == activeProviderId }
        }

    /** Model actually used for requests: catalog pick, else the entry's default. */
    val effectiveModel: String?
        get() = when {
            dualPlanning && mode == AgentMode.PLAN ->
                planningModel?.takeIf { it.isNotBlank() }
                    ?: providers.firstOrNull { it.id == planningProviderId }?.model
                    ?: activeProvider?.model
            dualPlanning && mode == AgentMode.ACT ->
                executionModel?.takeIf { it.isNotBlank() }
                    ?: providers.firstOrNull { it.id == executionProviderId }?.model
                    ?: activeProvider?.model
            else -> activeModel?.takeIf { it.isNotBlank() } ?: activeProvider?.model
        }

    val modelSelectionTarget: ModelSelectionTarget
        get() = when {
            dualPlanning && mode == AgentMode.PLAN -> ModelSelectionTarget.PLANNING
            dualPlanning && mode == AgentMode.ACT -> ModelSelectionTarget.EXECUTION
            else -> ModelSelectionTarget.ACTIVE
        }

    fun selectedProviderIdFor(target: ModelSelectionTarget): String? = when (target) {
        ModelSelectionTarget.PLANNING -> planningProviderId ?: activeProviderId
        ModelSelectionTarget.EXECUTION -> executionProviderId ?: activeProviderId
        ModelSelectionTarget.ACTIVE -> activeProviderId
    }

    fun selectedModelFor(target: ModelSelectionTarget): String? = when (target) {
        ModelSelectionTarget.PLANNING -> planningModel?.takeIf { it.isNotBlank() }
            ?: providers.firstOrNull { it.id == (planningProviderId ?: activeProviderId) }?.model
        ModelSelectionTarget.EXECUTION -> executionModel?.takeIf { it.isNotBlank() }
            ?: providers.firstOrNull { it.id == (executionProviderId ?: activeProviderId) }?.model
        ModelSelectionTarget.ACTIVE -> activeModel?.takeIf { it.isNotBlank() } ?: activeProvider?.model
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
    initialKind: com.androidharness.app.data.ConversationKind = com.androidharness.app.data.ConversationKind.CODE,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ChatUiState(
            sessionId = initialSessionId,
            conversation = com.androidharness.app.data.ConversationConfig(kind = initialKind),
            conversationReady = initialSessionId == null,
            isLoadingMessages = initialSessionId != null,
        )
    )
    val state: StateFlow<ChatUiState> = _state

    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 1)
    val navEvents: SharedFlow<NavEvent> = _navEvents

    private var sessionId: String? = initialSessionId
    private val sessionIdFlow = MutableStateFlow(initialSessionId)
    private val messageWindowStart = MutableStateFlow<MessagePosition?>(null)
    private val historyPaging = Mutex()
    val activeWorkspace = sessionIdFlow.flatMapLatest { c.workspace.projectForChat(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val workspaceSelection = sessionIdFlow.flatMapLatest { c.workspace.selectionForChat(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val workspaceFs = sessionIdFlow.flatMapLatest { c.workspace.forChat(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val webPreviewRefresh = MutableStateFlow(0L)
    val webPreviewAvailable = webPreviewAvailability(workspaceFs, state.map {
        WebPreviewInput(it.messages, it.streamingText, it.fileEditsByTurn.values.flatten().map { edit -> edit.relPath }.toSet())
    }.distinctUntilChanged(), webPreviewRefresh).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    fun refreshWebPreviewSources() { webPreviewRefresh.update { it + 1 } }
    private val sessionCreation = Mutex()

    private suspend fun ensureSession(title: String = c.appContext.getString(R.string.settings_telegram_new_chat)): String = sessionCreation.withLock {
        sessionId ?: c.sessions.createSession(title, c.workspace.currentProjectOnce().id, _state.value.conversation).also { sid ->
            c.chatModels.initialize(sid)
            sessionId = sid
            sessionIdFlow.value = sid
            _state.update { it.copy(sessionId = sid, sessionTitle = title) }
            c.settings.setLastActiveSessionId(sid)
        }
    }

    private suspend fun chatWorkspace() = c.workspace.forSession(ensureSession())
    private var configuredSkills: Pair<String, com.androidharness.app.skills.SkillStore>? = null
    private fun chatSkills(): com.androidharness.app.skills.SkillStore {
        val fs = workspaceFs.value ?: return c.skills.forProject(null)
        return configuredSkills?.takeIf { it.first == fs.displayPath }?.second ?: c.skills.forWorkspace(fs)
    }

    private var pendingAttachments = mutableListOf<ImageRef>()
    private val pendingFileAttachments = mutableListOf<FileAttachment>()
    /** Workspace key the @-mention file list was built for (empty until loaded). */
    private var mentionCacheKey: String? = null
    private var steering = false
    private var selectingAnswerVersion = false
    /** Text of a run paused on the workspace-MCP approval dialog. */
    private var pendingRunText: String? = null
    private var pendingReplacement: ChatMessage? = null
    private var pendingMcpWorkspace: com.androidharness.app.workspace.WorkspaceFs? = null
    private var pendingConfigFingerprint: String? = null
    private var startingRun = false

    /** User approved the workspace .harness/mcp.json: remember it and run. */
    fun approveWorkspaceMcp() {
        val text = pendingRunText
        val replacement = pendingReplacement
        pendingReplacement = null
        pendingRunText = null
        _state.update { it.copy(pendingWorkspaceMcp = null) }
        viewModelScope.launch {
            val approved = runCatching {
                val workspace = requireNotNull(pendingMcpWorkspace)
                check(workspace.displayPath == chatWorkspace().displayPath) { c.appContext.getString(R.string.ui_the_workspace_changed_send_your_message_again) }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val snapshot = c.configuration.load(workspace)
                    check(c.mcp.approvalFingerprint(workspace, snapshot) == pendingConfigFingerprint) {
                        "Project tools changed while approval was open. Send your message again to review them."
                    }
                    c.mcp.approveWorkspace(workspace, snapshot)
                }
            }
            pendingMcpWorkspace = null
            if (approved.isFailure) _state.update { it.copy(error = approved.exceptionOrNull()?.message) }
            else if (text != null) startRun(text, workspaceMcpGate = false, replacement = replacement)
        }
    }

    /** User declined: run without the workspace servers (they stay blocked). */
    fun denyWorkspaceMcp() {
        val text = pendingRunText
        val replacement = pendingReplacement
        pendingMcpWorkspace = null
        pendingReplacement = null
        pendingRunText = null
        _state.update { it.copy(pendingWorkspaceMcp = null) }
        if (text != null) startRun(text, workspaceMcpGate = false, replacement = replacement)
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
    private var compactionJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            sessionIdFlow.flatMapLatest(c.chatModels::observe).collect(::applyModelState)
        }
        viewModelScope.launch {
            c.providers.catalogs.collect { catalogs ->
                _state.update { it.copy(catalogs = catalogs) }
            }
        }
        viewModelScope.launch {
            _state.map { state ->
                state.activeProvider?.takeIf { state.modelSelectionReady }
                    ?.let { it.copy(model = state.effectiveModel ?: it.model) }
            }.distinctUntilChanged().collect { selected ->
                if (selected != null) launch { c.providers.refreshCatalog(selected.id, automatic = true) }
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
            sessionIdFlow.flatMapLatest { sid ->
                if (sid == null) flowOf(emptyList()) else c.sessions.usageEventsFor(sid)
            }.collect { events ->
                _state.update { it.copy(turnCacheUsage = events.filter { row -> row.turnId != null }.groupBy { row -> row.turnId!! }) }
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
                _state.update { st -> st.copy(showLiveCode = it.showLiveCode) }
            }
        }
        viewModelScope.launch {
            activeWorkspace.collect { project ->
                mentionCacheKey = null
                _state.update { it.copy(workspaceName = project?.name ?: "Workspace unavailable", mentionFiles = emptyList()) }
            }
        }
        viewModelScope.launch {
            combine(workspaceFs, c.settings.settings.map { it.disabledSkills }.distinctUntilChanged(), c.configuration.revision) { fs, _, _ -> fs }
                .collectLatest { fs ->
                    if (fs == null) return@collectLatest
                    val store = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val base = c.skills.forWorkspace(fs)
                        try {
                            (if (_state.value.conversation.isChat) base else base.configured(c.configuration.load(fs))).freeze()
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { base.freeze() }
                    }
                    configuredSkills = fs.displayPath to store
                    _state.update { it.copy(skills = store.list()) }
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

        viewModelScope.launch {
            sessionIdFlow.flatMapLatest { sid ->
                if (sid == null) flowOf(com.androidharness.app.agent.TaskRecord()) else c.runManager.controls.flow(sid)
            }.collect { record -> _state.update { it.copy(taskControl = record) } }
        }

        // Messages come straight from the DB flow, RunManager writes them
        // during runs, so the UI is just a live mirror. A held streaming
        // bubble is released the moment its committed row arrives.
        viewModelScope.launch {
            // A finished plan persists on the session row, so re-arm the
            // approval card when a chat is (re)opened after process death.
            sessionIdFlow.filterNotNull().collect { sid ->
                val persisted = runCatching { c.sessions.session(sid) }.getOrNull()?.pendingPlan
                if (!persisted.isNullOrBlank()) c.runManager.seedPendingPlan(sid, persisted)
            }
        }
        viewModelScope.launch {
            sessionIdFlow.flatMapLatest { sid ->
                if (sid == null) {
                    _state.update { it.copy(isLoadingMessages = false) }
                    flowOf(com.androidharness.app.data.SessionRepository.MessageWindow(emptyList(), false))
                } else {
                    flow {
                        messageWindowStart.value = c.sessions.messageWindowStart(sid)
                        emitAll(messageWindowStart.filterNotNull().flatMapLatest { start ->
                            c.sessions.messageWindowFlow(sid, start)
                        })
                    }
                }
            }.collect { window ->
                val msgs = window.messages.filterNot { com.androidharness.app.agent.RunRecovery.isInternalChatContinuation(it.id) }
                // Rewinding may remove the entire loaded suffix. Return to the saved history.
                if (window.messages.isEmpty() && window.hasOlder) {
                    sessionId?.let { messageWindowStart.value = c.sessions.messageWindowStart(it) }
                    return@collect
                }
                val hold = heldStream
                if (hold != null && msgs.any { it.id == hold.messageId }) {
                    heldStream = null
                    _state.update {
                        it.copy(
                            messages = msgs,
                            isLoadingMessages = false,
                            hasOlderMessages = window.hasOlder, isLoadingOlderMessages = false,
                            streamingText = null, streamingThinking = null,
                            streamingMessageId = null, streamingCommitted = false,
                            codeDrafts = emptyMap(),
                        ).withCurrentAction()
                    }
                } else {
                    _state.update { it.copy(messages = msgs, isLoadingMessages = false,
                        hasOlderMessages = window.hasOlder, isLoadingOlderMessages = false) }
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
                        it.copy(sessionTitle = s.title, usage = s.toUsageStats(),
                            conversation = if (it.conversationSaving) it.conversation else
                                com.androidharness.app.data.ConversationConfig.decode(s.conversationJson), conversationReady = true)
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
                        if (_state.value.dualPlanning && _state.value.mode == AgentMode.ACT) {
                            _state.update { it.copy(dualPlanning = false) }
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
                        conversation = if (it.conversationReady) it.conversation else
                            com.androidharness.app.data.ConversationConfig.decode(sess?.conversationJson),
                        conversationReady = true,
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
                        chatWorkspace(),
                        _state.value.mode,
                        _state.value.permissionMode == PermissionMode.FULL_ACCESS,
                        conversation = _state.value.conversation,
                        sessionId = initialSessionId,
                        searchDepth = _state.value.searchDepth,
                        chatInstructions = c.settings.settings.first().chatInstructions,
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
                busy = live.running || compactionJob?.isActive == true,
                streamingText = streamingText,
                streamingThinking = streamingThinking,
                streamingMessageId = streamingMessageId,
                streamingCommitted = committed,
                currentTurnId = live.turnId,
                runningCalls = live.runningCalls,
                codeDrafts = live.codeDrafts,
                packageProgress = live.packageProgress,
                pendingApproval = live.pendingApproval,
                pendingQuestion = live.pendingQuestion,
                pendingEnvironment = live.pendingEnvironment,
                installingEnvironment = live.installingEnvironment,
                currentToolAction = live.currentToolAction,
                subagentSteps = live.subagentSteps,
                thinkingTiming = if (committed) st.thinkingTiming.stop(com.androidharness.app.agent.ThinkingTiming.now()) else live.thinkingTiming,
                reasoningTokens = if (committed) st.reasoningTokens else live.reasoningTokens,
                turnStartedAt = live.turnStartedAt,
                retryStatus = live.retryStatus,
                queuedMessage = live.queuedMessage,
                pendingPlan = live.pendingPlan,
                estimate = live.estimate,
                compactionNote = if (compactionJob?.isActive == true) st.compactionNote else live.compactionNote,
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

    private fun applyModelState(models: com.androidharness.app.data.ChatModelState) {
        val settings = models.settings
        val selection = models.selection
        _state.update { it.copy(
            permissionMode = settings.permissionMode,
            thinkingLevel = selection.thinking,
            searchDepth = settings.searchDepth,
            reasoningConfigs = settings.reasoningConfigs,
            customThinkingLevels = selection.customThinkingLevels,
            maxContextTokens = settings.maxContextTokens,
            maxOutputTokens = settings.maxOutputTokens,
            maxIterations = settings.maxIterations,
            providers = models.providers,
            activeProviderId = selection.active?.providerId,
            activeModel = selection.active?.model,
            sameModelAcrossChats = settings.sameModelAcrossChats && !models.agentChat,
            fallbackDefaults = settings.fallbackPolicy,
            modelSelectionReady = true,
            modelSelectionError = models.error,
            planningModelsEnabled = settings.planningModelsEnabled,
            planningProviderId = selection.planning?.providerId,
            planningModel = selection.planning?.model,
            executionProviderId = selection.execution?.providerId,
            executionModel = selection.execution?.model,
            planningModelsPromoSeen = settings.planningModelsPromoSeen,
            forkPromoSeen = settings.forkPromoSeen,
            voiceEngine = settings.voiceEngine,
            groqWhisperModel = settings.groqWhisperModel,
            voicePromoSeen = settings.voicePromoSeen,
        ) }
    }

    private fun editModelSelection(action: suspend () -> Unit) {
        if (_state.value.modelSelectionBusy) return
        _state.update { it.copy(modelSelectionBusy = true) }
        viewModelScope.launch {
            try {
                action()
                applyModelState(c.chatModels.observe(sessionId).first())
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: c.appContext.getString(R.string.ui_could_not_save_the_model_selection)) } }
            finally { _state.update { it.copy(modelSelectionBusy = false) } }
        }
    }

    fun setSameModelAcrossChats(shared: Boolean) = editModelSelection {
        c.chatModels.setShared(ensureSession(), shared)
    }

    /**
     * Human-readable description of what the agent is doing right now.
     * [ChatUiState.currentToolAction] lingers briefly after a tool finishes so
     * fast local operations ("Editing app.py…") stay visible before "Thinking…".
     * Computed once per state emission, not per recomposition.
     */
    private fun ChatUiState.computeCurrentAction(): String? = when {
        pendingQuestion != null -> c.appContext.getString(R.string.ui_waiting_for_your_answer)
        pendingApproval != null -> "Waiting for your approval"
        installingEnvironment != null -> c.appContext.getString(R.string.ui_installing_linux_environment)
        pendingEnvironment != null -> when {
            envState is com.androidharness.app.data.env.EnvState.NotInstalled -> c.appContext.getString(R.string.ui_waiting_for_your_approval)
            envState is com.androidharness.app.data.env.EnvState.Failed -> c.appContext.getString(R.string.ui_linux_install_failed)
            pendingEnvironment.repair -> when {
                envState is com.androidharness.app.data.env.EnvState.Downloading ||
                    envState is com.androidharness.app.data.env.EnvState.Installing ->
                    c.appContext.getString(R.string.ui_repairing_linux_environment_notice4)
                else -> c.appContext.getString(R.string.ui_waiting_for_your_approval)
            }
            else -> c.appContext.getString(R.string.ui_installing_linux_environment_notice5)
        }
        retryStatus != null -> retryStatus
        runningCalls.isNotEmpty() -> currentToolAction ?: describeToolCall(runningCalls.last())
        codeDrafts.values.any { it.stage == com.androidharness.app.agent.CodeDraftStage.GENERATING } -> c.appContext.getString(R.string.ui_generating_code)
        streamingThinking != null && streamingText.isNullOrEmpty() -> "Thinking…"
        streamingText != null -> c.appContext.getString(R.string.ui_writing_response)
        currentToolAction != null -> currentToolAction
        busy -> if (mode == AgentMode.PLAN) c.appContext.getString(R.string.ui_planning) else c.appContext.getString(R.string.ui_working)
        else -> null
    }

    // ------------------------------------------------------------------
    // Sending & slash commands
    // ------------------------------------------------------------------

    /** True when the composer can consume the draft, including local commands and queued messages. */
    fun send(rawText: String): Boolean {
        if (compactionJob?.isActive == true) {
            _state.update { it.copy(error = c.appContext.getString(R.string.ui_wait_for_compaction_to_finish_or_stop)) }
            return false
        }
        attachmentBlockReason()?.let { reason -> _state.update { it.copy(error = reason) }; return false }
        val trimmed = rawText.trim()
        // File-only sends are legitimate: the attachments carry the message.
        if (trimmed.isEmpty() && _state.value.fileAttachments.isEmpty() && _state.value.attachments.isEmpty()) return false

        val resolved = if (trimmed.startsWith("/") && !_state.value.conversation.isChat) {
            SlashCommands.resolve(
                input = trimmed,
                skillNames = chatSkills().slashNames(),
                snippetBodies = _state.value.snippets.associate { it.name to it.body },
                skillContent = { name -> chatSkills().view(name).getOrNull()?.content },
            )
        } else {
            SlashCommands.Result(SlashCommands.Kind.PLAIN, agentText = trimmed)
        }

        when (resolved.kind) {
            SlashCommands.Kind.CLEAR -> {
                _navEvents.tryEmit(NavEvent.NewChat)
                return true
            }
            SlashCommands.Kind.COMPACT -> {
                if (_state.value.busy || startingRun) {
                    _state.update { it.copy(error = c.appContext.getString(R.string.ui_stop_the_current_task_before_compacting_this)) }
                    return false
                }
                compactionJob = viewModelScope.launch { forceCompact() }
                return true
            }
            SlashCommands.Kind.COST -> {
                _state.update { it.copy(showCostDialog = true) }
                return true
            }
            SlashCommands.Kind.SKILLS -> {
                _state.update { it.copy(showSkillsSheet = true, skills = chatSkills().list()) }
                return true
            }
            SlashCommands.Kind.ENV -> {
                _state.update { it.copy(showEnvSheet = true) }
                return true
            }
            SlashCommands.Kind.UNKNOWN -> {
                _state.update { it.copy(error = resolved.error) }
                return false
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

        val agentText = resolved.agentText ?: return false
        val sid = sessionId
        if (sid != null && c.runManager.isRunning(sid)) {
            if (_state.value.conversation.isChat && (_state.value.attachments.isNotEmpty() || _state.value.fileAttachments.isNotEmpty())) {
                _state.update { it.copy(error = c.appContext.getString(R.string.chat_attachment_wait_for_reply)) }
                return false
            }
            // Queue the expanded text for this chat's successful Run finished event.
            // Local slash commands never reach here, so /cost /skills /clear keep working mid-run.
            val target = SlashCommands.dispatchTarget(isRunning = true, agentText = agentText)
            c.runManager.inject(sid, target.text)
            return true
        }
        return startRun(agentText)
    }

    fun cancelQueuedMessage() {
        val sid = sessionId ?: return
        c.runManager.cancelQueued(sid)
    }

    /**
     * Stops the in-flight run, then sends the queued text as a new turn.
     * Default send-while-busy waits for this chat's Run finished event.
     */
    fun steerQueuedMessage() { _state.value.taskControl.queue.firstOrNull()?.let { sendQueuedNow(it.id) } }

    fun editQueued(id: String, text: String) { sessionId?.let { c.runManager.editQueued(it, id, text) } }
    fun moveQueued(id: String, offset: Int) { sessionId?.let { c.runManager.moveQueued(it, id, offset) } }

    fun sendQueuedNow(id: String) {
        val sid = sessionId ?: return
        if (steering || selectingAnswerVersion) return
        steering = true
        viewModelScope.launch {
            try {
                c.runManager.stopAndJoin(sid)
                c.runManager.controls.update(sid) { record ->
                    val selected = record.queue.firstOrNull { it.id == id }
                    record.copy(queue = listOfNotNull(selected) + record.queue.filterNot { it.id == id })
                }
                val record = c.runManager.controls.flow(sid).value
                val queued = record.queue.firstOrNull() ?: return@launch
                startRun(queued.text, queuedPromptId = queued.id)
            } catch (e: Exception) { _state.update { it.copy(error = e.message) } }
            finally { steering = false }
        }
    }

    private suspend fun resumeTaskNow(sid: String) {
        val record = c.runManager.controls.flow(sid).value
        val provider = record.provider ?: _state.value.activeProvider
            ?.takeIf { it.id == com.androidharness.app.llm.HarnessProvider.ID }
            ?.let { active ->
                _state.value.activeModel?.takeIf { it.isNotBlank() }
                    ?.let { active.copy(model = it) } ?: active
            }
            ?: error(c.appContext.getString(R.string.ui_saved_provider_is_unavailable))
        if (record.provider == null) {
            c.runManager.controls.update(sid) { it.copy(provider = provider) }
        }
        val key = if (provider.id == com.androidharness.app.llm.HarnessProvider.ID) {
            c.providers.harnessApiKey()
        } else provider.effectiveApiKey(c.providers.apiKey(provider.id))
        check(key.isNotBlank()) { c.appContext.getString(R.string.ui_add_the_saved_provider_s_api_key) }
        _state.update { it.copy(error = null) }
        c.runManager.resumeTask(sid, key)
    }

    fun resumeInterruptedTask() {
        val sid = sessionId ?: return
        if (selectingAnswerVersion || steering || compactionJob?.isActive == true || c.runManager.isRunning(sid)) return
        steering = true
        viewModelScope.launch {
            try { resumeTaskNow(sid) }
            catch (e: Exception) { _state.update { it.copy(error = e.message) } }
            finally { steering = false }
        }
    }

    fun saveRecoverySettings(enabled: Boolean, limit: Int) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val sid = ensureSession()
                c.runManager.controls.update(sid) { it.copy(autoContinue = enabled, autoContinueLimit = limit.coerceIn(1, 5)) }
            } catch (e: Exception) { _state.update { it.copy(error = e.message) } }
        }
    }

    suspend fun saveFallbacks(mode: com.androidharness.app.llm.FallbackMode?, policy: com.androidharness.app.llm.FallbackPolicy) {
        require(policy.valid())
        val sid = ensureSession()
        check(!c.runManager.isRunning(sid)) { c.appContext.getString(R.string.ui_pause_the_task_before_editing_its_fallback) }
        c.runManager.controls.update(sid) { it.copy(fallbackMode = mode ?: com.androidharness.app.llm.FallbackMode.DEFAULT,
            fallbackOverride = policy.takeIf { mode == com.androidharness.app.llm.FallbackMode.CUSTOM }) }
    }

    fun saveTaskControls(pins: String, summary: String?, limits: com.androidharness.app.agent.TaskLimits, clearOlder: Boolean) {
        viewModelScope.launch {
            try {
                val sid = ensureSession()
                check(!c.runManager.isRunning(sid) && compactionJob?.isActive != true) { c.appContext.getString(R.string.ui_stop_the_task_or_compaction_before_changing) }
                val cutoff = if (clearOlder) {
                    // Keep the latest user turn and all of its assistant/tool messages together.
                    c.sessions.messages(sid).lastOrNull { it.role == Role.USER }?.createdAt ?: 0
                } else c.runManager.controls.flow(sid).value.contextAfter
                c.runManager.controls.update(sid) { it.copy(pins = pins.trim(), summaryOverride = summary,
                    limits = limits, contextAfter = cutoff) }
            } catch (e: Exception) { _state.update { it.copy(error = e.message) } }
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
        _state.update { it.copy(showSkillsSheet = true, skills = chatSkills().list()) }
    }

    // ------------------------------------------------------------------
    // Attachments
    // ------------------------------------------------------------------

    fun attachImage(uri: Uri) = importAttachment(uri, imageOnly = true)

    fun removeAttachmentImport(id: String) {
        _state.update { it.copy(attachmentImports = it.attachmentImports.filterNot { pending -> pending.id == id }) }
    }

    fun attachmentBlockReason(): String? {
        val state = _state.value
        if (state.attachmentImports.isNotEmpty()) return c.appContext.getString(R.string.chat_attachment_resolve)
        if (state.conversation.isChat && state.attachments.isNotEmpty() && state.imageInputSupport() == false)
            return c.appContext.getString(R.string.chat_attachment_model_unsupported)
        return null
    }

    private class AttachmentProblem(val status: AttachmentImportStatus, message: String) : Exception(message)

    private fun importAttachment(uri: Uri, imageOnly: Boolean = false, mimeHint: String? = null) {
        val id = java.util.UUID.randomUUID().toString()
        val snapshot = _state.value
        _state.update { it.copy(attachmentImports = it.attachmentImports + AttachmentImport(id, uri.lastPathSegment?.substringAfterLast('/').orEmpty())) }
        fun active() = _state.value.attachmentImports.any { it.id == id }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val resolver = c.appContext.contentResolver
                val name = queryDisplayName(resolver, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Attachment"
                val mime = resolver.getType(uri)?.takeUnless { it.contains('*') }
                    ?: mimeHint?.takeUnless { it.contains('*') } ?: "application/octet-stream"
                _state.update { it.copy(attachmentImports = it.attachmentImports.map { item -> if (item.id == id) item.copy(name = name) else item }) }
                val size = querySize(resolver, uri)
                if (size != null && size > FileAttachments.COPY_BYTE_LIMIT) throw AttachmentProblem(
                    AttachmentImportStatus.TOO_LARGE, c.appContext.getString(R.string.chat_attachment_too_large))
                if (imageOnly || mime.startsWith("image/")) {
                    val keepOriginal = if (snapshot.conversationReady || snapshot.sessionId == null) snapshot.conversation.isChat
                        else c.sessions.session(snapshot.sessionId)?.let {
                            com.androidharness.app.data.ConversationConfig.decode(it.conversationJson).isChat
                        } ?: snapshot.conversation.isChat
                    val stored = c.images.import(uri, keepOriginal = keepOriginal) ?: throw IllegalStateException(c.appContext.getString(R.string.ui_could_not_load_that_image))
                    val image = c.images.metadata(ImageRef(stored.file.name, stored.mime, caption = name.take(200), origin = "user_upload"))
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        if (active()) {
                            pendingAttachments += image
                            _state.update { it.copy(attachments = it.attachments + stored.file.name) }
                        } else c.images.delete(stored.file.name)
                    }
                } else {
                    val pdf = FileAttachments.isPdf(name, mime)
                    val pdfExtract = if (pdf) resolver.openInputStream(uri)?.use { PdfTextExtractor.extract(it) } else null
                    val extracted = if (pdf) pdfExtract?.text?.takeIf { it.isNotBlank() }
                        else if (FileAttachments.isTextLike(name, mime)) {
                            try { readTextLimited(resolver, uri) }
                            catch (problem: AttachmentProblem) { if (_state.value.conversation.isChat) throw problem else null }
                        } else null
                    if (!active()) return@launch
                    if (extracted != null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                            if (active()) {
                                val sizeLabel = FileAttachments.humanBytes(size ?: extracted.length.toLong()) +
                                    (pdfExtract?.let { ", " + if (it.pageCount == 1) "1 page" else c.appContext.getString(R.string.ui_pages, it.pageCount.toString()) } ?: "")
                                addFileAttachment(FileAttachment(name, mime, sizeLabel, extracted, null))
                            }
                        }
                    } else if (_state.value.conversation.isChat) {
                        throw AttachmentProblem(AttachmentImportStatus.UNSUPPORTED,
                            c.appContext.getString(if (pdf) R.string.chat_attachment_pdf_unreadable else R.string.chat_file_requires_code))
                    } else copyAttachmentToWorkspace(resolver, uri, name, mime, size, id)
                }
                removeAttachmentImport(id)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(attachmentImports = it.attachmentImports.map { item ->
                    if (item.id == id) item.copy(status = (e as? AttachmentProblem)?.status ?: AttachmentImportStatus.FAILED, detail = e.message) else item
                }) }
            }
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

    fun attachFile(uri: Uri) = importAttachment(uri)

    fun attachSharedFile(uri: Uri, mimeHint: String?) = importAttachment(uri, mimeHint = mimeHint)

    fun removeFileAttachment(index: Int) {
        val removed = _state.value.fileAttachments.getOrNull(index) ?: return
        if (index < pendingFileAttachments.size) pendingFileAttachments.removeAt(index)
        removed.workspacePath?.let { path ->
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { chatWorkspace().resolve(path).delete() }
            }
        }
        _state.update { it.copy(fileAttachments = it.fileAttachments.toMutableList().apply { removeAt(index) }) }
    }

    /** Workspace file list for the @-mention picker, cached per workspace. */
    fun loadMentionFiles() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val fs = runCatching { chatWorkspace() }.getOrNull() ?: return@launch
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
        importId: String,
    ) {
        val cap = FileAttachments.COPY_BYTE_LIMIT
        if (size != null && size > cap) {
            throw IllegalStateException(c.appContext.getString(R.string.ui_file_is_larger_than, (FileAttachments.humanBytes(cap)).toString()))
        }
        val fs = chatWorkspace()
        val dir = fs.resolve(".harness/attachments")
        dir.mkdirs()
        val safe = name.replace(Regex("[^A-Za-z0-9._\\- ()]"), "_").take(80).ifBlank { "file" }
        val stamp = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val unique = "$stamp-${importId.take(8)}-$safe"
        val node = dir.createFile(unique)
        try {
            val bytes = resolver.openInputStream(uri)!!.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (out.size().toLong() + count > cap) throw AttachmentProblem(AttachmentImportStatus.TOO_LARGE,
                        c.appContext.getString(R.string.chat_attachment_too_large))
                    out.write(chunk, 0, count)
                }
                out.toByteArray()
            }
            node.writeBytes(bytes)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                if (_state.value.attachmentImports.any { it.id == importId }) {
                    addFileAttachment(FileAttachment(name, mime, FileAttachments.humanBytes(size ?: bytes.size.toLong()), null, ".harness/attachments/$unique"))
                } else node.delete()
            }
        } catch (e: Exception) { runCatching { node.delete() }; throw e }
    }

    /** Preserve distinct size, unsupported-content, and read-failure states for the composer. */
    private fun readTextLimited(resolver: android.content.ContentResolver, uri: Uri): String {
        return resolver.openInputStream(uri)!!.use { input ->
            val max = FileAttachments.INLINE_CHAR_LIMIT
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (buffer.size() + n > max) throw AttachmentProblem(AttachmentImportStatus.TOO_LARGE,
                    c.appContext.getString(R.string.chat_attachment_text_too_large))
                buffer.write(chunk, 0, n)
            }
            val text = buffer.toString("UTF-8")
            if (text.contains('\u0000')) throw AttachmentProblem(AttachmentImportStatus.UNSUPPORTED,
                c.appContext.getString(R.string.chat_file_requires_code))
            text
        }
    }

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

    /**
     * [retryModel] runs this one turn on another model without changing the chat's own
     * model selection, and disables silent fallback so the answer comes from that model.
     */
    private fun startRun(
        text: String,
        workspaceMcpGate: Boolean = true,
        queuedPromptId: String? = null,
        replacement: ChatMessage? = null,
        retryModel: com.androidharness.app.data.ModelShortcut? = null,
    ): Boolean {
        if (selectingAnswerVersion || startingRun || compactionJob?.isActive == true || !_state.value.conversationReady || _state.value.conversationSaving) return false
        if (replacement == null) attachmentBlockReason()?.let { reason -> _state.update { it.copy(error = reason) }; return false }
        val targetSession = sessionId
        val s0 = _state.value
        val retryChoice = retryModel?.let { pick ->
            s0.providers.firstOrNull { it.id == pick.providerId }?.let { it to pick.modelId }
        }
        if (retryModel != null && retryChoice == null) {
            _state.update { it.copy(error = c.appContext.getString(R.string.chat_retry_model_unavailable)) }
            return false
        }
        if (!s0.modelSelectionReady || s0.modelSelectionBusy) {
            _state.update { it.copy(error = c.appContext.getString(R.string.ui_model_selection_is_being_saved_try_sending)) }
            return false
        }
        if (s0.modelSelectionError != null) {
            _state.update { it.copy(error = s0.modelSelectionError) }
            return false
        }
        val selectedProviderId = when {
            s0.dualPlanning && s0.mode == AgentMode.PLAN -> s0.planningProviderId ?: s0.activeProviderId
            s0.dualPlanning && s0.mode == AgentMode.ACT -> s0.executionProviderId ?: s0.activeProviderId
            else -> s0.activeProviderId
        }
        if (retryChoice == null && !s0.sameModelAcrossChats && selectedProviderId != null && s0.providers.none { it.id == selectedProviderId }) {
            _state.update { it.copy(error = c.appContext.getString(R.string.ui_this_chat_s_provider_is_unavailable_choose)) }
            return false
        }
        if (retryChoice == null && selectedProviderId != null && com.androidharness.app.local.LocalModelCatalog.isLocal(selectedProviderId) &&
            s0.providers.none { it.id == selectedProviderId }) {
            _state.update { it.copy(error = c.appContext.getString(R.string.ui_the_selected_local_model_was_removed_download)) }
            return false
        }
        // Separate planning/execution models: plan-mode runs use the planning
        // slot, everything else the execution one. A slot without a provider
        // falls back to the active provider, keeping its model override.
        val provider = retryChoice?.first ?: when {
            s0.dualPlanning && s0.mode == AgentMode.PLAN && s0.planningProviderId != null ->
                s0.providers.firstOrNull { it.id == s0.planningProviderId } ?: s0.activeProvider
            s0.dualPlanning && s0.mode == AgentMode.ACT && s0.executionProviderId != null ->
                s0.providers.firstOrNull { it.id == s0.executionProviderId } ?: s0.activeProvider
            else -> s0.activeProvider
        } ?: run {
            _state.update { it.copy(error = c.appContext.getString(R.string.ui_no_provider_configured_add_one_from_the)) }
            return false
        }
        val roleModel = retryChoice?.second ?: when {
            s0.dualPlanning && s0.mode == AgentMode.PLAN ->
                s0.planningModel?.takeIf { it.isNotBlank() }
                    ?: s0.providers.firstOrNull { it.id == s0.planningProviderId }?.model
                    ?: provider.model
            s0.dualPlanning && s0.mode == AgentMode.ACT ->
                s0.executionModel?.takeIf { it.isNotBlank() }
                    ?: s0.providers.firstOrNull { it.id == s0.executionProviderId }?.model
                    ?: provider.model
            else -> s0.activeModel?.takeIf { it.isNotBlank() } ?: provider.model
        }
        // Reject a removed pinned Harness model before consuming the draft or attachments.
        if (!s0.sameModelAcrossChats && provider.id == com.androidharness.app.llm.HarnessProvider.ID &&
            com.androidharness.app.llm.HarnessProvider.sanitize(roleModel,
                com.androidharness.app.llm.HarnessProvider.customModelIds) != roleModel) {
            _state.update { it.copy(error = c.appContext.getString(R.string.ui_this_chat_s_model_is_no_longer)) }
            return false
        }
        val apiKey = if (provider.id == com.androidharness.app.llm.HarnessProvider.ID) c.providers.harnessApiKey(s0.providers)
        else provider.effectiveApiKey(c.providers.apiKey(provider.id))
        if (apiKey.isBlank()) {
            _state.update { it.copy(error = c.appContext.getString(R.string.ui_provider_has_no_api_key_edit_it, (provider.name).toString())) }
            return false
        }

        val imageRefs = replacement?.images ?: pendingAttachments.toList()
        val fileItems = if (replacement == null) pendingFileAttachments.toList() else emptyList()
        if (replacement == null) {
            pendingAttachments.clear()
            pendingFileAttachments.clear()
        }
        _state.update { if (replacement == null) it.copy(attachments = emptyList(), fileAttachments = emptyList(), error = null)
            else it.copy(error = null) }
        // File attachments ride after the user's text as model-readable blocks.
        val payload = FileAttachments.buildMessageSuffix(fileItems)
            .takeIf { it.isNotEmpty() }
            ?.let { suffix -> if (text.isBlank()) suffix else "$text\n\n$suffix" }
            ?: text

        startingRun = true
        viewModelScope.launch {
            try {
                val boundSession = targetSession ?: ensureSession(payload.take(48))
                val runWorkspace = c.workspace.forSession(boundSession)
                // Harness free-tier models live behind different wires per model; probe
                // once on first use and pin the winner so later requests route directly.
                // Pooled community models always speak chat/completions, skip the probe.
                if (provider.id == com.androidharness.app.llm.HarnessProvider.ID &&
                    c.providers.wire(roleModel) == null &&
                    !com.androidharness.app.llm.HarnessProvider.isPooled(roleModel) &&
                    c.providers.customModels(provider.id).any { it.id == roleModel }
                ) {
                    val learned = com.androidharness.app.llm.HarnessProvider.probeWire(
                        roleModel, c.providers.harnessApiKey(),
                    )
                    if (learned != null) {
                        c.providers.pinWire(roleModel, learned.name)
                        com.androidharness.app.llm.HarnessProvider.pins =
                            com.androidharness.app.llm.HarnessProvider.pins + (roleModel to learned.name)
                    }
                }
                // Security gate (battery D1): a workspace .harness/mcp.json never
                // spawns commands until this exact file content was approved. The
                // dialog offers approve (and continue) or run without those servers.
                if (workspaceMcpGate && !s0.conversation.isChat) {
                    val unapproved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val snapshot = c.configuration.load(runWorkspace)
                        pendingConfigFingerprint = c.mcp.approvalFingerprint(runWorkspace, snapshot)
                        c.mcp.unapprovedWorkspaceServers(runWorkspace, snapshot)
                    }
                    if (unapproved.isNotEmpty()) {
                        pendingRunText = payload
                        pendingReplacement = replacement
                        pendingMcpWorkspace = runWorkspace
                        _state.update { it.copy(pendingWorkspaceMcp = unapproved.map { s -> s.name }) }
                        return@launch
                    }
                }
                // A model picked from the provider's catalog overrides its default.
                val effectiveConfig = roleModel
                    ?.takeIf { it.isNotBlank() }
                    ?.let { provider.copy(model = it) } ?: provider
                val resolved = c.providers.resolveHarnessConfig(effectiveConfig)
                check(s0.sameModelAcrossChats || resolved.model == effectiveConfig.model) {
                    c.appContext.getString(R.string.ui_this_chat_s_model_is_no_longer)
                }
                if (resolved.model != effectiveConfig.model && retryChoice == null) {
                    when {
                        s0.dualPlanning && s0.mode == AgentMode.PLAN ->
                            c.settings.setPlanningModel(provider.id, resolved.model)
                        s0.dualPlanning && s0.mode == AgentMode.ACT ->
                            c.settings.setExecutionModel(provider.id, resolved.model)
                        else -> c.settings.setActiveModel(resolved.model)
                    }
                }
                if (replacement != null) {
                    check(targetSession != null && targetSession == sessionId) { c.appContext.getString(R.string.ui_the_active_chat_changed) }
                }
                val localLimits = if (com.androidharness.app.local.LocalModelCatalog.isLocal(provider.id)) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.localModels.limits(provider.id.removePrefix(com.androidharness.app.local.LocalModelCatalog.PROVIDER_PREFIX)) }
                } else null
                val sid = c.runManager.startRun(
                    sessionId = boundSession,
                    text = payload,
                    imageRefs = imageRefs,
                    config = effectiveConfig,
                    apiKey = apiKey,
                    permissionMode = s0.permissionMode,
                    mode = if (s0.conversation.isChat) AgentMode.ACT else s0.mode,
                    maxOutputTokens = localLimits?.output ?: s0.maxOutputTokens,
                    maxContextTokens = localLimits?.context ?: s0.maxContextTokens,
                    thinking = s0.thinkingLevel,
                    maxIterations = s0.maxIterations,
                    workspaceOverride = runWorkspace,
                    queuedPromptId = queuedPromptId,
                    replacementMessageId = replacement?.id,
                    allowModelFallback = s0.sameModelAcrossChats && retryChoice == null,
                )
                if (sessionId == null) {
                    sessionId = sid
                    sessionIdFlow.value = sid
                    _state.update { it.copy(sessionId = sid, sessionTitle = payload.take(48)) }
                    viewModelScope.launch {
                        c.settings.setLastActiveSessionId(sid)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: c.appContext.getString(R.string.ui_could_not_start_the_message)) }
            } finally {
                startingRun = false
            }
        }
        return true
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
        compactionJob?.cancel()
        val sid = sessionId ?: return
        c.runManager.stop(sid)
    }

    fun requestSectionSwitch(kind: com.androidharness.app.data.ConversationKind): String? {
        if (kind == _state.value.conversation.kind) return null
        // The drawer covers the conversation's snackbar, so report this guard
        // back to the navigation surface before dispatching the switch.
        if (pendingAttachments.isNotEmpty() || pendingFileAttachments.isNotEmpty() || _state.value.attachmentImports.isNotEmpty())
            return c.appContext.getString(R.string.chat_switch_attachments)
        _navEvents.tryEmit(NavEvent.SelectSection(kind))
        return null
    }

    fun prepareSectionSwitch(draft: String, onReady: () -> Unit) {
        if (!_state.value.conversationReady || _state.value.conversationSaving || startingRun) return
        if (pendingAttachments.isNotEmpty() || pendingFileAttachments.isNotEmpty() || _state.value.attachmentImports.isNotEmpty()) {
            _state.update { it.copy(error = c.appContext.getString(R.string.chat_switch_attachments)) }
            return
        }
        _state.update { it.copy(conversationSaving = true) }
        viewModelScope.launch {
            try {
                val sid = ensureSession()
                val config = com.androidharness.app.data.ConversationConfig.decode(c.sessions.session(sid)?.conversationJson)
                c.sessions.saveConversation(sid, config.copy(draftText = draft))
                onReady()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message) } }
            finally { _state.update { it.copy(conversationSaving = false) } }
        }
    }

    fun continueInCode(projectId: String) {
        if (_state.value.busy || startingRun || _state.value.conversationSaving) return
        _state.update { it.copy(conversationSaving = true) }
        viewModelScope.launch {
            try { _navEvents.emit(NavEvent.OpenSession(c.sessions.continueInCode(ensureSession(), projectId))) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message) } }
            finally { _state.update { it.copy(conversationSaving = false) } }
        }
    }

    fun startAgentChat(profile: com.androidharness.app.data.ChatAgentProfile, draft: String, onOpened: () -> Unit = {}) {
        if (!_state.value.conversationReady || _state.value.conversationSaving || startingRun || _state.value.busy) return
        if (pendingAttachments.isNotEmpty() || pendingFileAttachments.isNotEmpty() || _state.value.attachmentImports.isNotEmpty()) {
            _state.update { it.copy(error = c.appContext.getString(R.string.chat_switch_attachments)) }; return
        }
        draftSaveJob?.cancel()
        _state.update { it.copy(conversationSaving = true) }
        viewModelScope.launch {
            try {
                if (sessionId != null || draft.isNotBlank()) {
                    val sid = ensureSession()
                    val config = com.androidharness.app.data.ConversationConfig.decode(c.sessions.session(sid)?.conversationJson)
                    c.sessions.saveConversation(sid, config.copy(draftText = draft))
                }
                val id = c.startChatAgent(profile)
                onOpened()
                _navEvents.emit(NavEvent.OpenSession(id))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message) } }
            finally { _state.update { it.copy(conversationSaving = false) } }
        }
    }

    /** Persists this chat's instructions, personalization switch, and image appetite. */
    fun setChatSettings(
        instructions: String,
        personalization: Boolean,
        imageFrequency: com.androidharness.app.data.ImageFrequency,
        useGlobalInstructions: Boolean = _state.value.conversation.useGlobalInstructions,
        onSaved: () -> Unit = {},
    ) {
        if (!_state.value.conversationReady || _state.value.conversationSaving || !_state.value.conversation.isChat) return
        val current = _state.value.conversation
        val updated = current.copy(
            instructions = instructions.trim()
                .take(com.androidharness.app.data.ConversationConfig.MAX_INSTRUCTIONS_CHARS),
            personalizationEnabled = personalization,
            imageFrequency = imageFrequency,
            useGlobalInstructions = useGlobalInstructions,
        )
        if (updated == current) { onSaved(); return }
        _state.update { it.copy(conversation = updated, conversationSaving = true, chatSettingsError = null) }
        viewModelScope.launch {
            try {
                c.sessions.saveConversation(ensureSession(), updated)
                onSaved()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: c.appContext.getString(R.string.chat_settings_save_failed)
                _state.update { it.copy(conversation = current, chatSettingsError = reason, error = reason) }
            } finally {
                _state.update { it.copy(conversationSaving = false) }
            }
        }
    }

    /** Every web source in this chat, for the chat-wide source library. */
    internal suspend fun loadChatSources(): List<com.androidharness.app.core.ChatActivityPresentation.Source> {
        val sid = _state.value.sessionId ?: return emptyList()
        return com.androidharness.app.core.ChatActivityPresentation.sources(
            c.sessions.messages(sid),
            limit = 500,
        )
    }

    fun setMode(mode: AgentMode) {
        if (_state.value.mode == mode && !_state.value.dualPlanning) return
        val wasDual = _state.value.dualPlanning
        _state.update { it.copy(mode = mode, dualPlanning = false).withCurrentAction() }
        if (wasDual) {
            _state.update { it.copy(modeToast = "Dual planning off: ${if (mode == AgentMode.PLAN) c.appContext.getString(R.string.ui_plan_mode) else c.appContext.getString(R.string.ui_act_mode)}") }
        } else {
            announceMode(mode)
        }
    }

    fun toggleDualPlanning() {
        val s = _state.value
        if (s.dualPlanning) {
            _state.update { it.copy(dualPlanning = false, mode = AgentMode.ACT).withCurrentAction() }
            _state.update { it.copy(modeToast = c.appContext.getString(R.string.ui_dual_planning_off)) }
            return
        }

        val planModel = s.planningModel?.takeIf { it.isNotBlank() }
            ?: s.providers.firstOrNull { it.id == s.planningProviderId }?.model
            ?: s.activeProvider?.model
        val execModel = s.executionModel?.takeIf { it.isNotBlank() }
            ?: s.providers.firstOrNull { it.id == s.executionProviderId }?.model
            ?: s.activeProvider?.model

        if (planModel == null && execModel == null) {
            _state.update { it.copy(modeToast = c.appContext.getString(R.string.ui_configure_dual_planning_models_in_settings_first)) }
            return
        }

        val planName = planModel?.substringAfterLast('/') ?: "default"
        val execName = execModel?.substringAfterLast('/') ?: "default"

        _state.update {
            it.copy(
                dualPlanning = true,
                mode = AgentMode.PLAN,
            ).withCurrentAction()
        }
        _state.update { it.copy(modeToast = c.appContext.getString(R.string.ui_dual_planning_on, (planName).toString(), (execName).toString())) }
    }

    /** Approve the plan produced by a PLAN-mode run and execute it. */
    fun executePendingPlan() {
        val sid = sessionId ?: return
        c.runManager.clearPendingPlan(sid)
        _state.update { it.copy(mode = AgentMode.ACT, pendingPlan = null).withCurrentAction() }
        startRun("The plan above is approved. Execute it step by step now.")
    }

    private fun announceMode(mode: AgentMode) {
        val label = if (mode == AgentMode.PLAN) c.appContext.getString(R.string.ui_plan_mode) else c.appContext.getString(R.string.ui_act_mode)
        _state.update { it.copy(modeToast = label) }
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
                _state.update { it.copy(modeToast = c.appContext.getString(R.string.ui_branched_into_new_chat)) }
                onForked(newSid)
            } catch (e: Exception) {
                _state.update { it.copy(error = c.appContext.getString(R.string.ui_fork_failed, (e.message).toString())) }
            }
        }
    }

    fun discardPendingPlan() {
        val sid = sessionId ?: return
        c.runManager.clearPendingPlan(sid)
        // Clear the persisted copy on this scope: the plan-seeding collector
        // shares it, so the card cannot be resurrected by a session switch.
        viewModelScope.launch { runCatching { c.sessions.setPendingPlan(sid, null) } }
        _state.update {
            it.copy(
                pendingPlan = null,
                dualPlanning = false,
                mode = AgentMode.ACT,
            ).withCurrentAction()
        }
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
    /** Read-only comparison from this turn's checkpoint to the current workspace file. */
    suspend fun fileDiff(turnId: String, path: String): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val sid = sessionId ?: error(c.appContext.getString(R.string.ui_this_chat_is_no_longer_available))
            val normalized = com.androidharness.app.workspace.normalizeRelPath(path)
            val checkpoint = c.checkpoints.entitiesForTurns(sid, listOf(turnId)).firstOrNull {
                com.androidharness.app.workspace.normalizeRelPath(it.relPath) == normalized
            } ?: error(c.appContext.getString(R.string.ui_no_checkpoint_is_available_for_this_file))
            check(!checkpoint.wasDirectory) { c.appContext.getString(R.string.ui_this_change_is_a_directory_not_a) }
            val before = if (checkpoint.existedBefore) {
                String(android.util.Base64.decode(checkpoint.contentB64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } else ""
            val fs = chatWorkspace()
            val node = fs.resolve(path)
            val current = if (!node.exists) "" else {
                check(node.isFile && node.length <= 1_000_000) { c.appContext.getString(R.string.ui_this_file_is_too_large_for_an) }
                node.readText()
            }
            com.androidharness.app.core.Diff.unified(before, current, path)
        }

    fun loadOlderMessages() {
        if (!_state.value.hasOlderMessages || _state.value.isLoadingOlderMessages) return
        _state.update { it.copy(isLoadingOlderMessages = true) }
        viewModelScope.launch {
            historyPaging.withLock {
                try {
                    val sid = sessionId ?: return@withLock
                    val start = messageWindowStart.value ?: return@withLock
                    messageWindowStart.value = c.sessions.messageWindowStart(sid, before = start)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { _state.update { it.copy(error = c.appContext.getString(R.string.ui_could_not_load_older_messages, (e.message).toString())) } }
                finally { _state.update { it.copy(isLoadingOlderMessages = false) } }
            }
        }
    }

    /** Search can reveal a saved message outside the initial page without altering model context. */
    suspend fun revealMessage(messageId: String): Boolean = historyPaging.withLock {
        val sid = sessionId ?: return@withLock false
        if (_state.value.messages.any { it.id == messageId }) return@withLock true
        val position = c.sessions.messagePosition(sid, messageId) ?: return@withLock false
        val start = messageWindowStart.value
        if (start == null || position.createdAt < start.createdAt ||
            position.createdAt == start.createdAt && position.rowId < start.rowId) {
            messageWindowStart.value = position
        }
        kotlinx.coroutines.withTimeoutOrNull(5_000) {
            state.first { it.messages.any { message -> message.id == messageId } }
        } != null
    }

    /** Load checkpoint labels only when Undo is opened, including turns outside the UI page. */
    suspend fun undoTurns(): List<Pair<String, ChatMessage>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val sid = sessionId ?: return@withContext emptyList()
        val messages = c.sessions.messages(sid)
        _state.value.turnsWithCheckpoints.mapNotNull { tid ->
            val message = messages.lastOrNull { it.turnId == tid && it.role == Role.ASSISTANT && it.toolCallId == null }
                ?: messages.firstOrNull { it.turnId == tid }
            message?.let { tid to it }
        }.sortedByDescending { it.second.createdAt }
    }

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

        val fs = chatWorkspace()
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
        val sid = sessionId ?: return c.appContext.getString(R.string.ui_no_active_chat)
        if (_state.value.busy || sid in c.runManager.runningSessionIds.value) {
            return c.appContext.getString(R.string.ui_stop_the_running_agent_first)
        }
        val summary = runCatching { c.runManager.rewindFromTurn(sid, turnId) }
            .getOrElse { e -> return c.appContext.getString(R.string.ui_rewind_failed, (e.message).toString()) }
        _state.update { it.copy(turnsWithCheckpoints = refreshCheckpoints(sid)) }
        return when {
            summary.filesFailed > 0 ->
                c.appContext.getString(R.string.ui_undo_incomplete_file_s_restored_failed, (summary.filesRestored).toString(), (summary.filesFailed).toString()) +
                    c.appContext.getString(R.string.ui_history_and_failed_checkpoints_were_kept_retry)
            summary.filesTouched == 0 && summary.messagesDeleted == 0 -> "Nothing to rewind for that turn."
            else ->
                c.appContext.getString(R.string.ui_undo_complete_file_s_restored, (summary.filesRestored).toString()) +
                    c.appContext.getString(R.string.ui_message_s_removed, (summary.messagesDeleted).toString())
        }
    }

    /**
     * Editing a past user message: rewinds the workspace to before that
     * message, truncates the conversation there, then resends the edited text
     * as a fresh run. The UI warns before calling this.
     */
    fun editAndResend(message: ChatMessage, newText: String, retryModel: com.androidharness.app.data.ModelShortcut? = null) {
        if (sessionId == null || message.id == null || message.role != Role.USER || newText.isBlank()) return
        startRun(newText, replacement = message, retryModel = retryModel)
    }

    fun retryLatestResponse(retryModel: com.androidharness.app.data.ModelShortcut? = null) {
        if (_state.value.busy || !_state.value.conversation.isChat) return
        val question = _state.value.messages.lastOrNull { it.role == Role.USER } ?: return
        editAndResend(question, question.text, retryModel)
    }

    fun selectAnswerVersion(expectedUserId: String, versionId: String) {
        val sid = sessionId ?: return
        if (selectingAnswerVersion || _state.value.busy || startingRun || steering || compactionJob?.isActive == true) return
        selectingAnswerVersion = true
        viewModelScope.launch {
            try {
                val fs = c.workspace.forSession(sid)
                c.runManager.withIdleWorkspace(fs) {
                    check(sessionId == sid && !startingRun)
                    c.sessions.selectChatAnswer(sid, expectedUserId, versionId)
                    messageWindowStart.value = c.sessions.messageWindowStart(sid)
                    _state.update { it.copy(error = null) }
                    c.runManager.controls.update(sid) { it.copy(status = "idle", reason = null, completed = true) }
                }
            } catch (e: Exception) { _state.update { it.copy(error = e.message) } }
            finally { selectingAnswerVersion = false }
        }
    }

    /** Re-runs the question behind [message], replacing its answer in place. */
    fun regenerate(message: ChatMessage, retryModel: com.androidharness.app.data.ModelShortcut? = null) {
        if (_state.value.busy || message.role != Role.ASSISTANT) return
        val question = com.androidharness.app.core.ChatTurnActions
            .userMessageFor(_state.value.messages, message.turnId) ?: return
        editAndResend(question, question.text, retryModel)
    }

    private var draftSaveJob: kotlinx.coroutines.Job? = null

    /** Stores the composer text so the chat list can mark unsent drafts. */
    fun saveDraft(text: String, immediate: Boolean = false) {
        val sid = sessionId ?: return
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            if (!immediate) kotlinx.coroutines.delay(400)
            val session = c.sessions.session(sid) ?: return@launch
            val config = com.androidharness.app.data.ConversationConfig.decode(session.conversationJson)
            if (config.draftText == text) return@launch
            runCatching { c.sessions.saveConversation(sid, config.copy(draftText = text)) }
        }
    }

    /** Find-in-conversation hits, oldest first, for the in-chat search bar. */
    suspend fun findInConversation(query: String): List<com.androidharness.app.data.MessageHit> {
        val sid = sessionId ?: return emptyList()
        return runCatching { c.sessions.searchMessagesInSession(sid, query) }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    private suspend fun refreshCheckpoints(sessionId: String): Set<String> =
        runCatching { c.checkpoints.turnsWithCheckpoints(sessionId) }.getOrDefault(emptySet())

    private suspend fun saveCompactionNotice(sid: String, message: ChatMessage) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            // A deleted chat or unavailable storage must not turn recovery into an uncaught UI exception.
            try { c.sessions.addMessage(sid, message) } catch (_: Exception) { }
        }
    }

    private suspend fun forceCompact() {
        val sid = sessionId ?: return
        val current = _state.value
        if (!current.modelSelectionReady || current.modelSelectionBusy || current.modelSelectionError != null) {
            _state.update { it.copy(error = current.modelSelectionError ?: "Choose a model before compacting this chat.") }
            return
        }
        val provider = current.activeProvider?.let { it.copy(model = current.effectiveModel ?: it.model) } ?: return
        _state.update { it.copy(busy = true, error = null, compactionNote = "Preparing chat summary…") }
        var keepalive = false
        var committed = false
        try {
            c.runManager.acquireKeepalive()
            keepalive = true
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val key = if (provider.id == com.androidharness.app.llm.HarnessProvider.ID) c.providers.harnessApiKey()
                    else provider.effectiveApiKey(c.providers.apiKey(provider.id))
                check(key.isNotBlank()) { "Add this provider's API key before compacting." }
                val snapshot = c.sessions.compactionSnapshot(sid)
                check(snapshot.history.size >= 4) { "There are too few messages to compact yet." }
                val routing = com.androidharness.app.llm.FallbackRun(provider) {
                    val record = c.runManager.controls.flow(sid).value
                    com.androidharness.app.llm.effectiveFallback(c.settings.settings.first().fallbackPolicy, record.fallbackMode, record.fallbackOverride)
                }
                var compactionProvider = provider
                val summary = kotlinx.coroutines.withContext(routing) { com.androidharness.app.agent.HistoryCompactor.summarize(
                    c.routedProvider(), provider, key, com.androidharness.app.agent.RunRecovery.context(snapshot.history, c.runManager.controls.flow(sid).value),
                    current.maxContextTokens, sid,
                    progress = { note -> if (sessionId == sid) _state.update { it.copy(compactionNote = note) } },
                    event = { event, cfg -> when (event) {
                        is com.androidharness.app.llm.StreamEvent.Usage -> {
                            c.sessions.addUsage(sid, event.inputTokens.toLong(), event.outputTokens.toLong(),
                                event.cachedInputTokens.toLong(), event.cacheWriteTokens.toLong())
                            c.sessions.recordUsage(sid, cfg.name, cfg.model, event.inputTokens.toLong(), event.outputTokens.toLong(),
                                event.cachedInputTokens.toLong(), event.cacheWriteTokens.toLong(),
                                cacheReported = event.cacheReported, cachePrices = com.androidharness.app.llm.ModelsDev.exactCachePrices(cfg.baseUrl, cfg.model))
                        }
                        // Keep the snapshot unchanged until its summary is committed.
                        is com.androidharness.app.llm.StreamEvent.ProviderChanged -> {
                            if (event.persistSelection) c.chatModels.providerChanged(sid, compactionProvider, event.config)
                            compactionProvider = event.config
                            if (sessionId == sid) _state.update { it.copy(compactionNote = event.reason) }
                        }
                        is com.androidharness.app.llm.StreamEvent.Retrying -> if (sessionId == sid)
                            _state.update { it.copy(compactionNote = "Retrying summary · attempt ${event.attempt} · ${event.reason}") }
                        else -> Unit
                    } }) }
                val before = c.sessions.commitCompaction(sid, summary, snapshot)
                committed = true
                c.runManager.controls.update(sid) { it.copy(summaryOverride = summary, contextAfter = before) }
                val fresh = c.engine.estimateFor(com.androidharness.app.agent.ContextHygiene.forModel(
                    c.sessions.historyFor(sid).second), chatWorkspace(), current.mode,
                    current.permissionMode == PermissionMode.FULL_ACCESS, conversation = current.conversation,
                    sessionId = sid, searchDepth = current.searchDepth,
                    chatInstructions = c.settings.settings.first().chatInstructions)
                if (sessionId == sid) _state.update { it.copy(estimate = fresh) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (!committed) saveCompactionNotice(sid, ChatMessage(role = Role.SYSTEM,
                text = "Compaction stopped. History was kept. Retry /compact when ready."))
            throw e
        } catch (e: Exception) {
            val message = if (committed) "Chat compacted, but its task settings or context estimate could not refresh: ${e.message}"
                else "Compaction failed: ${e.message}. History was kept. Retry /compact or choose another model."
            if (sessionId == sid) _state.update { it.copy(error = message) }
            saveCompactionNotice(sid, ChatMessage(role = Role.SYSTEM, text = message, isError = true))
        } finally {
            if (sessionId == sid) _state.update { it.copy(busy = false, compactionNote = null) }
            if (keepalive) c.runManager.releaseKeepalive()
        }
    }

    fun setPermissionMode(mode: PermissionMode) {
        _state.update { it.copy(permissionMode = mode) }
        viewModelScope.launch { c.settings.setPermissionMode(mode) }
    }

    fun setSearchDepth(depth: com.androidharness.app.data.SearchDepth) {
        _state.update { it.copy(searchDepth = depth) }
        viewModelScope.launch { c.settings.setSearchDepth(depth) }
    }

    fun saveReasoningConfig(config: ProviderConfig, custom: com.androidharness.app.agent.CustomReasoningConfig?) {
        editModelSelection { c.settings.setReasoningConfig(com.androidharness.app.agent.CustomReasoning.key(config), custom) }
    }

    fun setCustomThinking(config: ProviderConfig, name: String) {
        editModelSelection {
            val sid = if (_state.value.sameModelAcrossChats) sessionId else ensureSession()
            c.chatModels.setCustomThinking(sid, com.androidharness.app.agent.CustomReasoning.key(config),
                com.androidharness.app.agent.CustomReasoning.wire(name))
        }
    }

    fun setThinkingLevel(level: ThinkingLevel) {
        val current = _state.value
        val requested = if (com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(current.activeProvider?.id)) {
            val model = current.catalogs[current.activeProvider?.id].orEmpty().firstOrNull { it.id == current.effectiveModel }
                ?: com.androidharness.app.llm.ModelEntry(current.effectiveModel.orEmpty())
            com.androidharness.app.chatgpt.ChatGptThinking.selected(model, level) ?: return
        } else level
        editModelSelection {
            val sid = if (_state.value.sameModelAcrossChats) sessionId else ensureSession()
            c.chatModels.setThinking(sid, requested)
        }
    }

    fun selectProviderForTarget(target: ModelSelectionTarget, id: String) {
        selectModelForTarget(target, id, null)
    }

    fun setActiveProvider(id: String) {
        selectProviderForTarget(ModelSelectionTarget.ACTIVE, id)
    }

    fun selectModelForTarget(target: ModelSelectionTarget, providerId: String, model: String?) {
        val s = _state.value
        editModelSelection {
            val sid = if (s.sameModelAcrossChats) sessionId else ensureSession()
            c.chatModels.select(sid, com.androidharness.app.data.ChatModelTarget.valueOf(target.name), providerId, model)
            viewModelScope.launch { c.providers.refreshCatalog(providerId, automatic = true) }
            if (target == ModelSelectionTarget.ACTIVE && s.dualPlanning) {
                val chosen = (model ?: s.providers.firstOrNull { it.id == providerId }?.model)?.substringAfterLast('/') ?: "default"
                _state.update { it.copy(dualPlanning = false, mode = AgentMode.ACT,
                    modeToast = c.appContext.getString(R.string.ui_dual_planning_off_switched_to, (chosen).toString())).withCurrentAction() }
            }
        }
    }

    /** Selects [model] under provider [providerId] (null = its saved default). */
    fun selectModel(providerId: String, model: String?) {
        selectModelForTarget(ModelSelectionTarget.ACTIVE, providerId, model)
    }

    fun addCustomModel(providerId: String, model: String, reasoning: Boolean? = null) {
        viewModelScope.launch {
            c.providers.addCustomModel(providerId, model, reasoning)
        }
    }

    fun deleteCustomModel(providerId: String, model: String) {
        viewModelScope.launch {
            c.providers.removeCustomModel(providerId, model)
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
        apiKey: com.androidharness.app.llm.ProviderKeyPool,
        headers: List<com.androidharness.app.llm.ProviderHeader>,
    ) {
        viewModelScope.launch {
            try {
                if (existing == null) {
                    val created = c.providers.add(name, type, baseUrl, model, apiKey, headers)
                    // Adding a provider switches to it on its own default model; a
                    // stale model override from the previous one must not survive.
                    c.chatModels.select(ensureSession(), com.androidharness.app.data.ChatModelTarget.ACTIVE, created.id, null)
                } else {
                    c.providers.update(
                        existing.copy(name = name, type = type, baseUrl = baseUrl, model = model, headers = headers),
                        apiKey,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = c.appContext.getString(R.string.provider_save_failed)) } }
        }
    }

    fun deleteProvider(providerId: String) {
        viewModelScope.launch { c.providers.delete(providerId) }
    }

    /** Full key list, including disabled keys, for the shared add/edit form. */
    fun providerApiKeys(providerId: String): com.androidharness.app.llm.ProviderKeyPool = c.providers.apiKeys(providerId)

    // ---- Workspace switching (chat overflow + sheet) ------------------------

    /** The shared container, for host-side pickers (AddWorkspaceDialog). */
    val container: AppContainer get() = c

    val workspaces: Flow<List<com.androidharness.app.data.db.ProjectEntity>>
        get() = c.workspace.projects

    fun workspaceDescription(project: com.androidharness.app.data.db.ProjectEntity) = c.workspace.describe(project)

    fun setWorkspace(projectId: String) {
        viewModelScope.launch {
            try { c.runManager.assignWorkspace(ensureSession(), projectId) }
            catch (e: Exception) { _state.update { it.copy(error = e.message) } }
        }
    }

    suspend fun saveWorkspaces(selection: com.androidharness.app.workspace.ChatWorkspaceSelection) {
        c.runManager.assignWorkspaces(ensureSession(), selection)
    }

    fun addSafWorkspace(uri: Uri) {
        viewModelScope.launch {
            try { c.runManager.assignWorkspace(ensureSession(), c.workspace.addPickedFolder(uri).id) }
            catch (e: Exception) { _state.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Refetches a provider's model catalog. Returns an error message on
     * failure, null on success (catalog persisted and published to state).
     */
    suspend fun refreshCatalog(providerId: String): String? {
        return c.providers.refreshCatalog(providerId)
    }

    suspend fun detectReasoningLevels(config: ProviderConfig): List<String> = c.providers.detectReasoningLevels(config)

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        /** Max time a just-committed stream may be held while Room delivers the row. */
        private const val HOLD_TIMEOUT_MS = 2_000L

        fun factory(container: AppContainer, sessionId: String?, kind: com.androidharness.app.data.ConversationKind = com.androidharness.app.data.ConversationKind.CODE) = viewModelFactory {
            initializer { ChatViewModel(container, sessionId, kind) }
        }
    }
}
