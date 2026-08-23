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
import com.androidharness.app.agent.TodoItem
import com.androidharness.app.agent.describeToolCall
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.data.db.SnippetEntity
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.RequestOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
     * writes), so this counts cache writes as misses — the honest rate.
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
    val maxContextTokens: Int = 1_000_000,
    val maxOutputTokens: Int = 32_768,
    val maxIterations: Int = 0,
    val providers: List<ProviderConfig> = emptyList(),
    val activeProviderId: String? = null,
    val estimate: ContextEstimate? = null,
    val usage: UsageStats = UsageStats(),
    val todos: List<TodoItem> = emptyList(),
    val turnsWithCheckpoints: Set<String> = emptySet(),
    val snippets: List<SnippetEntity> = emptyList(),
    val showCostDialog: Boolean = false,
    val workspaceName: String = "",
    val currentToolAction: String? = null,
    /** Live progress lines per running subagent (task tool), newest last. */
    val subagentSteps: Map<String, List<String>> = emptyMap(),
    /** Precomputed human-readable activity line (computed in the ViewModel, never in composition). */
    val currentAction: String? = null,
    val retryStatus: String? = null,
    val pendingEnvironment: com.androidharness.app.agent.EnvironmentRequest? = null,
    val envState: com.androidharness.app.data.env.EnvState =
        com.androidharness.app.data.env.EnvState.NotInstalled,
    val shizukuState: com.androidharness.app.data.env.ShizukuState =
        com.androidharness.app.data.env.ShizukuState.NOT_INSTALLED,
) {
    val activeProvider: ProviderConfig? get() = providers.firstOrNull { it.id == activeProviderId }

    /** Best available measure of tokens currently inside the context window. */
    val contextUsed: Int
        get() = maxOf(usage.lastInput.toInt(), estimate?.total ?: 0)
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

    /**
     * Streaming content kept on screen while waiting for the committed row to
     * arrive from Room. Without it, the live bubble disappears at commit and
     * the message pops back in a frame or two later — the list bottom shrinks
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
                        )
                    }
                }
        }
        viewModelScope.launch {
            c.todoStore.todos.collect { todos ->
                _state.update { it.copy(todos = todos) }
            }
        }
        viewModelScope.launch {
            c.snippets.snippets.collect { snippets ->
                _state.update { it.copy(snippets = snippets) }
            }
        }
        viewModelScope.launch {
            c.workspace.currentProject.collect { project ->
                _state.update { it.copy(workspaceName = project.name) }
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

        // Messages come straight from the DB flow — RunManager writes them
        // during runs, so the UI is just a live mirror. A held streaming
        // bubble is released the moment its committed row arrives.
        viewModelScope.launch {
            sessionIdFlow.flatMapLatest { sid ->
                if (sid == null) flowOf(emptyList()) else c.sessions.messagesFlow(sid)
            }.collect { msgs ->
                val hold = heldStream
                if (hold != null && msgs.any { it.id == hold.messageId }) {
                    heldStream = null
                    _state.update {
                        it.copy(
                            messages = msgs,
                            streamingText = null, streamingThinking = null,
                            streamingMessageId = null, streamingCommitted = false,
                        ).withCurrentAction()
                    }
                } else {
                    _state.update { it.copy(messages = msgs) }
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
                        // Switched sessions while holding — drop the stale hold.
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
                _state.update {
                    it.copy(
                        sessionId = initialSessionId,
                        sessionTitle = c.sessions.session(initialSessionId)?.title ?: "Chat",
                        turnsWithCheckpoints = refreshCheckpoints(initialSessionId),
                    )
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
                    // Only hold the stream that was actually just committed —
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
                retryStatus = live.retryStatus,
                queuedMessage = live.queuedMessage,
                pendingPlan = live.pendingPlan,
                estimate = live.estimate,
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
        pendingEnvironment != null -> when (envState) {
            is com.androidharness.app.data.env.EnvState.NotInstalled -> "Waiting for your approval"
            is com.androidharness.app.data.env.EnvState.Failed -> "Linux install failed"
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
        if (trimmed.isEmpty()) return

        if (trimmed.startsWith("/")) {
            if (!handleSlashCommand(trimmed)) return
        }

        val sid = sessionId
        if (sid != null && c.runManager.isRunning(sid)) {
            // Queue the message; the engine picks it up before its next turn.
            c.runManager.inject(sid, trimmed)
            return
        }
        startRun(trimmed)
    }

    /** Returns true if the command triggered a normal agent run. */
    private fun handleSlashCommand(input: String): Boolean {
        val parts = input.split(" ", limit = 2)
        val command = parts[0].lowercase()
        val argument = parts.getOrNull(1).orEmpty()

        when (command) {
            "/clear" -> {
                _navEvents.tryEmit(NavEvent.NewChat)
                return false
            }
            "/compact" -> {
                viewModelScope.launch { forceCompact() }
                return false
            }
            "/cost" -> {
                _state.update { it.copy(showCostDialog = true) }
                return false
            }
            "/init" -> {
                startRun(
                    "Explore this workspace thoroughly (files, structure, conventions), then write " +
                        "an AGENTS.md at the workspace root describing the project, how to build/run it, " +
                        "and the coding conventions future agent sessions should follow. " +
                        "If an AGENTS.md already exists, improve it.",
                )
                return false
            }
            else -> {
                val snippet = _state.value.snippets.firstOrNull {
                    "/" + it.name.lowercase() == command
                }
                if (snippet != null) {
                    val body = if (argument.isNotBlank()) {
                        snippet.body.replace("\$ARG", argument)
                    } else snippet.body
                    startRun(body)
                    return false
                }
                _state.update { it.copy(error = "Unknown command: $command") }
                return false
            }
        }
    }

    fun cancelQueuedMessage() {
        val sid = sessionId ?: return
        c.runManager.cancelQueued(sid)
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
    // Run lifecycle (delegated to the app-scoped RunManager)
    // ------------------------------------------------------------------

    private fun startRun(text: String) {
        val provider = _state.value.activeProvider
        if (provider == null) {
            _state.update { it.copy(error = "No provider configured. Add one from the Providers screen first.") }
            return
        }
        val apiKey = c.providers.apiKey(provider.id)
        if (apiKey.isNullOrBlank()) {
            _state.update { it.copy(error = "Provider \"${provider.name}\" has no API key. Edit it on the Providers screen.") }
            return
        }

        val imageRefs = pendingAttachments.toList()
        pendingAttachments.clear()
        _state.update { it.copy(attachments = emptyList(), error = null) }

        val s = _state.value
        viewModelScope.launch {
            val sid = c.runManager.startRun(
                sessionId = sessionId,
                text = text,
                imageRefs = imageRefs,
                config = provider,
                apiKey = apiKey,
                permissionMode = s.permissionMode,
                mode = s.mode,
                maxOutputTokens = s.maxOutputTokens,
                maxContextTokens = s.maxContextTokens,
                thinking = s.thinkingLevel,
                maxIterations = s.maxIterations,
            )
            if (sessionId == null) {
                sessionId = sid
                sessionIdFlow.value = sid
                _state.update { it.copy(sessionId = sid, sessionTitle = text.take(48)) }
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

    fun grantShizuku() {
        c.shizuku.requestPermission()
    }

    fun stop() {
        val sid = sessionId ?: return
        c.runManager.stop(sid)
    }

    fun setMode(mode: AgentMode) {
        _state.update { it.copy(mode = mode).withCurrentAction() }
    }

    /** Approve the plan produced by a PLAN-mode run and execute it. */
    fun executePendingPlan() {
        val sid = sessionId ?: return
        c.runManager.clearPendingPlan(sid)
        _state.update { it.copy(mode = AgentMode.ACT, pendingPlan = null) }
        startRun("The plan above is approved. Execute it step by step now.")
    }

    fun discardPendingPlan() {
        val sid = sessionId ?: return
        c.runManager.clearPendingPlan(sid)
        _state.update { it.copy(pendingPlan = null) }
    }

    // ------------------------------------------------------------------
    // Undo / edit
    // ------------------------------------------------------------------

    fun rewindToTurn(turnId: String) {
        val sid = sessionId ?: return
        viewModelScope.launch {
            val fs = c.workspace.currentOnce()
            val restored = runCatching { c.checkpoints.rewind(sid, turnId, fs) }.getOrDefault(0)
            _state.update {
                it.copy(
                    turnsWithCheckpoints = refreshCheckpoints(sid),
                    error = if (restored == 0) "Nothing to rewind for that turn." else null,
                )
            }
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
        // simplest correct approach — ask the engine for a summary of all history.
        val history = c.sessions.historyFor(sid).second
        if (history.size < 4) {
            _state.update { it.copy(error = "Not enough history to compact.") }
            return
        }
        _state.update { it.copy(busy = true) }
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
                    ChatMessage(
                        role = Role.USER,
                        text = com.androidharness.app.agent.AgentEngine.COMPACTION_PREFIX +
                            "\n\n" + summary,
                    ),
                )
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Compaction failed") }
        } finally {
            _state.update { it.copy(busy = false) }
            c.runManager.releaseKeepalive()
        }
    }

    fun setPermissionMode(mode: PermissionMode) {
        _state.update { it.copy(permissionMode = mode) }
        viewModelScope.launch { c.settings.setPermissionMode(mode) }
    }

    fun setThinkingLevel(level: ThinkingLevel) {
        _state.update { it.copy(thinkingLevel = level) }
        viewModelScope.launch { c.settings.setThinkingLevel(level) }
    }

    fun setActiveProvider(id: String) {
        viewModelScope.launch { c.settings.setActiveProvider(id) }
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
