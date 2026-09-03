package com.androidharness.app.agent

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
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
import com.androidharness.app.workspace.WorkspaceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Turns a tool call into a Claude Code-style status line ("Editing app.py…"). */
fun describeToolCall(call: ToolCallData): String {
    val args = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(call.argumentsJson).jsonObject
    }.getOrNull()
    fun arg(name: String): String? = runCatching {
        when (val el = args?.get(name)) {
            is kotlinx.serialization.json.JsonPrimitive -> el.content
            is kotlinx.serialization.json.JsonArray -> el.mapNotNull {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            }.joinToString(", ")
            else -> null
        }
    }.getOrNull()

    return when (call.name) {
        "write_file" -> "Creating ${arg("path") ?: "file"}…"
        "edit_file", "multi_edit" -> "Editing ${arg("path") ?: "file"}…"
        "apply_patch" -> "Applying patch…"
        "read_file" -> "Reading ${arg("path") ?: "file"}…"
        "file_info" -> "Inspecting ${arg("path") ?: "file"}…"
        "list_dir" -> "Listing ${arg("path") ?: "."}…"
        "search_files" -> "Finding ${arg("pattern") ?: "files"}…"
        "grep" -> "Searching for ${arg("pattern") ?: "pattern"}…"
        "shell" -> "Running ${arg("command")?.take(48) ?: "command"}…"
        "shell_background" -> "Starting ${arg("command")?.take(48) ?: "server"}…"
        "bg_list" -> "Checking background tasks…"
        "bg_kill" -> "Stopping background task…"
        "create_dir" -> "Creating folder ${arg("path") ?: ""}…"
        "delete_file" -> "Deleting ${arg("path") ?: "path"}…"
        "move_file" -> "Moving ${arg("source") ?: "file"}…"
        "git_status" -> "Checking git status…"
        "git_diff" -> "Reading git diff…"
        "git_commit" -> "Committing…"
        "web_fetch" -> "Fetching ${arg("url")?.take(48) ?: "page"}…"
        "web_search" -> "Searching: ${arg("query")?.take(48) ?: ""}…"
        "http_request" -> "Calling ${arg("url")?.take(48) ?: "API"}…"
        "ask_user" -> "Asking you a question…"
        "task" -> "Delegating: ${arg("title") ?: "research subagent"}…"
        "memory_write" -> "Saving to memory…"
        "todo_write" -> "Updating task list…"
        "skill_view" -> "Loading skill ${arg("name") ?: "…"}…"
        "skills_list" -> "Listing skills…"
        "skill_manage" -> "Updating skill ${arg("name") ?: ""}…".trim()
        "pkg_install" -> "Installing package ${arg("packages") ?: arg("package") ?: "…"}"
        "pkg_search" -> "Searching packages for ${arg("query") ?: "…"}"
        "pkg_list" -> "Listing installed packages…"
        "read_logcat" -> "Reading logcat ${arg("tag") ?: arg("package_name") ?: ""}…".trim()
        else -> "Running ${call.name}…"
    }
}

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
    /** MCP servers; tools are attached per run. Null in tests without MCP. */
    private val mcp: com.androidharness.app.tools.mcp.McpManager? = null,
    private val repoMap: com.androidharness.app.repomap.RepoMapCache? = null,
) {

    /** Live, per-session run state the UI mirrors. */
    data class LiveRunState(
        val sessionId: String,
        val running: Boolean = false,
        val turnId: String? = null,
        val streamingText: String? = null,
        val streamingThinking: String? = null,
        /** Id of the message currently streaming; the commit writes the row under this same id. */
        val liveMessageId: String? = null,
        /** Id of the most recently committed assistant message, for the UI's live→committed handoff. */
        val lastCommittedId: String? = null,
        val runningCalls: List<ToolCallData> = emptyList(),
        val pendingApproval: ApprovalRequest? = null,
        val pendingQuestion: QuestionRequest? = null,
        val pendingEnvironment: EnvironmentRequest? = null,
        val currentToolAction: String? = null,
        /** Live progress lines per running subagent (task tool), newest last. */
        val subagentSteps: Map<String, List<String>> = emptyMap(),
        val error: String? = null,
        /** Transient "Retrying in Ns…" status while the engine backs off. */
        val retryStatus: String? = null,
        /** When this turn's reasoning stream started/ended, drives "Thought for Ns". */
        val thinkingStartedAt: Long? = null,
        val thinkingEndedAt: Long? = null,
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
    private val states = mutableMapOf<String, MutableStateFlow<LiveRunState>>()
    private val jobs = mutableMapOf<String, Job>()
    private val injections = mutableMapOf<String, Channel<String>>()
    private val turnIds = mutableMapOf<String, String>()
    private val allowedTools = mutableMapOf<String, MutableSet<String>>()
    private val lingerJobs = mutableMapOf<String, Job>()
    private val deltaBuffers = mutableMapOf<String, DeltaBuffers>()

    private val keepaliveCount = AtomicInteger(0)

    val runningSessionIds = MutableStateFlow<Set<String>>(emptySet())

    private fun stateOf(sessionId: String): MutableStateFlow<LiveRunState> =
        synchronized(lock) {
            states.getOrPut(sessionId) { MutableStateFlow(LiveRunState(sessionId)) }
        }

    private fun buffer(sessionId: String): DeltaBuffers? =
        synchronized(lock) { deltaBuffers[sessionId] }

    /**
     * Publishes buffered stream deltas into [LiveRunState]; a no-op when nothing
     * is pending. Batching deltas here keeps the UI at ~15 updates/sec no matter
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

    /** What the notification should say for this run right now. */
    private fun actionText(s: LiveRunState): String? = when {
        s.pendingQuestion != null -> "Waiting for your answer"
        s.pendingApproval != null -> "Waiting for your approval"
        s.pendingEnvironment != null -> "Linux environment needs your attention"
        s.retryStatus != null -> s.retryStatus
        s.currentToolAction != null -> s.currentToolAction
        s.runningCalls.isNotEmpty() -> describeToolCall(s.runningCalls.last())
        s.streamingThinking != null && s.streamingText.isNullOrEmpty() -> "Thinking…"
        s.streamingText != null -> "Writing response…"
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
    ): String {
        val sid = sessionId ?: sessions.createSession(
            text.take(48),
            projectId = workspace.currentProjectOnce().id,
        )
        synchronized(lock) { jobs[sid] }?.let { previous ->
            previous.cancel()
            previous.join()
        }
        val turnId = UUID.randomUUID().toString()
        val channel = Channel<String>(Channel.UNLIMITED)
        synchronized(lock) {
            turnIds[sid] = turnId
            injections[sid] = channel
            allowedTools[sid] = mutableSetOf()
            deltaBuffers[sid] = DeltaBuffers()
        }

        val live = stateOf(sid)
        // A fresh user prompt starts a fresh task list; the store is
        // session-owned so nothing leaks across chats.
        todoStore.beginRun(sid)
        sessions.addMessage(sid, ChatMessage(role = Role.USER, text = text, images = imageRefs, turnId = turnId), turnId)
        // A new run replaces any plan approval still pending on this session.
        runCatching { sessions.setPendingPlan(sid, null) }
        live.update {
            it.copy(
                running = true, error = null, turnId = turnId,
                streamingText = "", streamingThinking = null,
                liveMessageId = UUID.randomUUID().toString(), lastCommittedId = null,
                queuedMessage = null, pendingPlan = null,
            )
        }
        runningSessionIds.update { it + sid }
        acquireKeepalive()
        RuntimeNotifier.update("Working…")

        val history = with(sessions) {
            ContextHygiene.forModel(messages(sid).withoutSubagentTurns())
        }
        val job = appScope.launch {
            var promptJob: Job? = null
            try {
                // SSE chunks arrive far faster than frames render; batch them so
                // the UI updates at a steady cadence regardless of model speed.
                launch {
                    while (isActive) {
                        delay(STREAM_FLUSH_MS)
                        flushDeltas(sid)
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
                val runWorkspace = workspace.currentOnce()
                // One catalog fetch per run serves every task `model` override;
                // a provider that cannot list models just refuses overrides.
                val modelResolver = SubagentModelResolver {
                    when (val r = com.androidharness.app.llm.ModelCatalog.listModels(config, apiKey)) {
                        is com.androidharness.app.llm.ModelCatalog.Result.Models -> r.models.map { it.id }
                        is com.androidharness.app.llm.ModelCatalog.Result.Failed -> error(r.message)
                    }
                }
                val repoMapOn = runCatching { settings.settings.first().repoMapEnabled }.getOrDefault(true)
                engine.run(
                    sessionId = sid,
                    turnId = turnId,
                    config = config,
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
                    options = RequestOptions(maxOutputTokens = maxOutputTokens, thinking = thinking),
                    maxContextTokens = maxContextTokens,
                    mode = mode,
                    userInjections = channel,
                    maxIterations = maxIterations,
                    // Connected MCP servers ride into this run; a failing
                    // server must never block the run itself.
                    extraTools = runCatching { mcp?.activeTools(runWorkspace) }.getOrNull().orEmpty(),
                    resolveSubagentModel = modelResolver::resolve,
                    repoMapEnabled = repoMapOn,
                ).collect { event -> handleEvent(sid, event) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                live.update { it.copy(error = e.message ?: "Unexpected error") }
            } finally {
                promptJob?.cancel()
                RuntimeNotifier.setSessionPrompts(sid, emptyList())
                val planText = if (mode == AgentMode.PLAN) {
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
                        // Keep lastCommittedId: the UI's hold needs it to know
                        // which committed row it is waiting on.
                        liveMessageId = null,
                        runningCalls = emptyList(),
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
                runningSessionIds.update { it - sid }
                synchronized(lock) {
                    jobs.remove(sid)
                    injections.remove(sid)
                    turnIds.remove(sid)
                    allowedTools.remove(sid)
                    deltaBuffers.remove(sid)
                }
                notifyFinished(sid, live.value.error)
                releaseKeepalive()
            }
        }
        synchronized(lock) { jobs[sid] = job }
        return sid
    }

    private suspend fun handleEvent(sessionId: String, event: AgentEvent) {
        val live = stateOf(sessionId)
        val turnId = synchronized(lock) { turnIds[sessionId] }
        when (event) {
            is AgentEvent.Text -> {
                val buf = buffer(sessionId) ?: return
                synchronized(buf) {
                    buf.text.append(event.delta)
                    buf.dirty = true
                }
                live.update {
                    // First answer text ends this turn's reasoning phase.
                    if (it.thinkingStartedAt != null && it.thinkingEndedAt == null) {
                        it.copy(thinkingEndedAt = System.currentTimeMillis(), retryStatus = null)
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
                        thinkingStartedAt = it.thinkingStartedAt ?: System.currentTimeMillis(),
                        retryStatus = null,
                    )
                }
            }

            is AgentEvent.AssistantCommitted -> {
                flushDeltas(sessionId)
                // Commit under the id the live bubble has been using, so the UI
                // swaps live → committed in place instead of remove + insert.
                val id = live.value.liveMessageId ?: UUID.randomUUID().toString()
                val thinkingMs = live.value.thinkingStartedAt?.let { start ->
                    (live.value.thinkingEndedAt ?: System.currentTimeMillis()) - start
                } ?: 0L
                val msg = event.message.copy(turnId = turnId, id = id, thinkingMs = thinkingMs)
                sessions.addMessage(sessionId, msg, turnId)
                live.update {
                    it.copy(
                        streamingText = null, streamingThinking = null,
                        liveMessageId = null, lastCommittedId = id,
                        thinkingStartedAt = null, thinkingEndedAt = null,
                    )
                }
            }

            is AgentEvent.ToolStarted -> {
                flushDeltas(sessionId)
                lingerJobs.remove(sessionId)?.cancel()
                live.update {
                    it.copy(
                        runningCalls = it.runningCalls + event.call,
                        currentToolAction = describeToolCall(event.call),
                    )
                }
            }

            is AgentEvent.ApprovalNeeded -> live.update { it.copy(pendingApproval = event.request) }
            is AgentEvent.QuestionNeeded -> live.update { it.copy(pendingQuestion = event.request) }
            is AgentEvent.EnvironmentNeeded -> live.update { it.copy(pendingEnvironment = event.request) }

            is AgentEvent.ToolMessageCommitted -> sessions.addMessage(sessionId, event.message, turnId)

            // Inner subagent turns persist under the parent session; the main
            // chat hides them (assistant rows carry the parent call id).
            is AgentEvent.SubagentMessageCommitted ->
                sessions.addMessage(sessionId, event.message, turnId)

            is AgentEvent.FileEdited -> {
                repoMap?.invalidate(event.relPath)
                sessions.recordFileEdit(
                    sessionId, event.turnId, event.relPath, event.added, event.removed,
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
                        pendingApproval = null,
                        subagentSteps = it.subagentSteps - event.callId,
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

            is AgentEvent.UserMessageInjected -> {
                sessions.addMessage(sessionId, ChatMessage(role = Role.USER, text = event.text), turnId)
                live.update { it.copy(queuedMessage = null) }
            }

            is AgentEvent.Usage -> {
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
                )
            }

            is AgentEvent.Retrying -> {
                val seconds = (event.delayMs / 1000.0).let { if (it < 1) "<1" else "%.0f".format(it) }
                live.update {
                    it.copy(retryStatus = "Retrying in ${seconds}s (attempt ${event.attempt}): ${event.reason}")
                }
            }

            is AgentEvent.EstimatedContext -> live.update { it.copy(estimate = event.estimate) }
            is AgentEvent.Compacting -> {
                RuntimeNotifier.update(event.reason)
                live.update { it.copy(compactionNote = event.reason) }
            }

            is AgentEvent.Compacted -> {
                live.update { it.copy(compactionNote = null) }
                sessions.setCompaction(sessionId, event.summary, System.currentTimeMillis())
                sessions.addMessage(sessionId, ContextHygiene.summaryMessage(event.summary))
                // A visible transcript line, so the fold is not silent.
                sessions.addMessage(sessionId, ContextHygiene.compactionNotice())
            }

            is AgentEvent.Error -> live.update { it.copy(error = event.message) }

            is AgentEvent.SubagentStep -> live.update { st ->
                st.copy(
                    subagentSteps = st.subagentSteps +
                        (event.toolCallId to st.subagentSteps[event.toolCallId].orEmpty() + event.line),
                )
            }
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
        val live = stateOf(sessionId)
        val pending = live.value.pendingApproval ?: return
        if (rememberForSession) synchronized(lock) { allowedTools[sessionId]?.add(pending.grantKey) }
        live.update { it.copy(pendingApproval = null) }
        pending.response.complete(true)
    }

    fun deny(sessionId: String) {
        val live = stateOf(sessionId)
        val pending = live.value.pendingApproval ?: return
        live.update { it.copy(pendingApproval = null) }
        pending.response.complete(false)
    }

    fun answerQuestion(sessionId: String, answer: String) {
        val live = stateOf(sessionId)
        val pending = live.value.pendingQuestion ?: return
        live.update { it.copy(pendingQuestion = null) }
        pending.response.complete(answer.ifBlank { "(no answer given)" })
    }

    /** Installs the full Linux environment and lets the run continue. */
    fun approveEnvironmentInstall(sessionId: String) {
        val live = stateOf(sessionId)
        val pending = live.value.pendingEnvironment ?: return
        appScope.launch {
            try {
                if (pending.repair && !linuxEnv.needsRepair()) {
                    // Nothing actually broken (e.g. self-heal fixed it first):
                    // answer immediately without a pointless reinstall.
                    pending.response.complete(true)
                    return@launch
                }
                linuxEnv.install(linuxEnv.fullPackages)
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
                live.update { it.copy(pendingEnvironment = null) }
            }
        }
    }

    fun denyEnvironmentInstall(sessionId: String) {
        val live = stateOf(sessionId)
        val pending = live.value.pendingEnvironment ?: return
        live.update { it.copy(pendingEnvironment = null) }
        pending.response.complete(false)
    }

    fun stop(sessionId: String) {
        synchronized(lock) { jobs[sessionId]?.cancel() }
    }

    /**
     * Cancels the run and suspends until its cleanup has finished, so a follow-up
     * [startRun] cannot race the old job's finally block.
     */
    suspend fun stopAndJoin(sessionId: String) {
        val job = synchronized(lock) { jobs[sessionId] } ?: return
        job.cancel()
        job.join()
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

    /** Queue a steering message for the running agent. Replaces any previous queued text. */
    fun inject(sessionId: String, text: String) {
        val channel = synchronized(lock) { injections[sessionId] } ?: return
        while (channel.tryReceive().isSuccess) { /* replace */ }
        channel.trySend(text)
        stateOf(sessionId).update { it.copy(queuedMessage = text) }
    }

    /** Clears the queued steering message before the engine consumes it. */
    fun cancelQueued(sessionId: String) {
        val channel = synchronized(lock) { injections[sessionId] }
        if (channel != null) {
            while (channel.tryReceive().isSuccess) { /* drain */ }
        }
        stateOf(sessionId).update { it.copy(queuedMessage = null) }
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
        stop(sessionId)
        val msgs = sessions.messages(sessionId)
        val index = msgs.indexOfFirst { it.id == messageId }
        if (index < 0) return
        // distinct turns from the edited message onward, newest first
        val turnIds = msgs.drop(index).mapNotNull { it.turnId }.distinct().reversed()
        val fs = workspace.currentOnce()
        for (tid in turnIds) {
            runCatching { checkpoints.rewind(sessionId, tid, fs) }
        }
        sessions.truncateFrom(sessionId, messageId)
    }

    /**
     * Full undo of a checkpointed turn: every file touched in [turnId] or any
     * later turn returns to its pre-turn state (snapshots replayed newest-turn
     * first so the earliest pre-state wins per path), the conversation rolls
     * back to just before that turn's first agent message, the "+N −M" chip
     * rows of the removed turns are dropped, and cumulative change counters
     * are recomputed against the session baseline.
     */
    suspend fun rewindFromTurn(sessionId: String, turnId: String): RewindSummary {
        val ordered = runCatching { checkpoints.turnsOrdered(sessionId) }.getOrDefault(emptyList())
        val idx = ordered.indexOfFirst { it.turnId == turnId }
        val affectedTurns = if (idx >= 0) ordered.drop(idx).map { it.turnId } else listOf(turnId)

        val fs = workspace.currentOnce()
        var restored = 0
        var failed = 0
        val paths = LinkedHashSet<String>()
        for (tid in affectedTurns.reversed()) {
            val result = runCatching { checkpoints.rewind(sessionId, tid, fs) }.getOrNull() ?: continue
            restored += result.restored
            failed += result.failed
            result.paths.forEach { paths += com.androidharness.app.workspace.normalizeRelPath(it) }
        }

        // Chat rolls back: delete from this turn's first agent message onward.
        val msgs = sessions.messages(sessionId)
        val boundary = msgs.indexOfFirst { it.turnId == turnId && it.role != Role.USER }
        var messagesDeleted = 0
        if (boundary >= 0) {
            msgs[boundary].id?.let { boundaryId ->
                messagesDeleted = msgs.size - boundary
                sessions.truncateFrom(sessionId, boundaryId)
            }
        }

        // The removed turns' diff-chip rows point at messages that no longer exist.
        sessions.deleteFileEditsForTurns(sessionId, affectedTurns)

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
        return RewindSummary(restored, failed, messagesDeleted, paths.size)
    }

    data class RewindSummary(
        val filesRestored: Int,
        val filesFailed: Int,
        val messagesDeleted: Int,
        val filesTouched: Int,
    )

    // ------------------------------------------------------------------
    // Keep-alive plumbing
    // ------------------------------------------------------------------

    /** Foreground service + wakelock while anything important is running. */
    fun acquireKeepalive() {
        if (keepaliveCount.getAndIncrement() == 0) {
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, AgentService::class.java),
                )
            }
        }
    }

    fun releaseKeepalive() {
        if (keepaliveCount.decrementAndGet() <= 0) {
            keepaliveCount.set(0)
            RuntimeNotifier.update("Working…")
            runCatching { context.stopService(Intent(context, AgentService::class.java)) }
        }
    }

    private suspend fun notifyFinished(sessionId: String, error: String?) {
        val title = runCatching { sessions.session(sessionId)?.title }.getOrNull() ?: "Chat"
        RuntimeNotifier.notifyResult(
            RunResultNotification(
                sessionId = sessionId,
                title = title,
                ok = error == null,
                summary = if (error == null) "Run finished" else "Stopped: ${error.take(120)}",
            ),
        )
    }

    companion object {
        /** UI frame-cadence flush interval for streamed deltas (~15 fps). */
        private const val STREAM_FLUSH_MS = 66L

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
