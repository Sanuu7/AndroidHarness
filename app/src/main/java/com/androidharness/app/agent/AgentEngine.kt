package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Diff
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.CheckpointStore
import com.androidharness.app.data.ImageStore
import com.androidharness.app.llm.LlmProvider
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.StreamEvent
import com.androidharness.app.tools.FuzzyEdit
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolRegistry
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class PermissionMode(val label: String) {
    CONFIRM_ALL("Confirm everything"),
    CONFIRM_RISKY("Confirm risky actions"),
    FULL_AUTO("Full auto"),
}

/** ACT executes tools; PLAN only allows inspection and must end with a plan. */
enum class AgentMode { ACT, PLAN }

class ApprovalRequest(
    val call: ToolCallData,
    val toolDescription: String,
    val diffPreview: String? = null,
) {
    val response = CompletableDeferred<Boolean>()
}

class QuestionRequest(
    val callId: String,
    val question: String,
    val options: List<String>,
) {
    val response = CompletableDeferred<String>()
}

/** The agent wants to run commands that need the Linux environment. */
class EnvironmentRequest(
    val call: ToolCallData,
    val command: String,
    val hints: List<String>,
) {
    val response = CompletableDeferred<Boolean>()
}

/** Estimated token breakdown of the request about to be sent (chars / 4). */
data class ContextEstimate(
    val messagesTokens: Int,
    val systemTokens: Int,
    val toolsTokens: Int,
    val metaTokens: Int = 256,
) {
    val total: Int get() = messagesTokens + systemTokens + toolsTokens + metaTokens
}

sealed interface AgentEvent {
    data class Text(val delta: String) : AgentEvent
    data class Thinking(val delta: String) : AgentEvent
    data class ToolStarted(val call: ToolCallData) : AgentEvent
    data class ToolFinished(val callId: String, val result: ToolResult) : AgentEvent
    data class ApprovalNeeded(val request: ApprovalRequest) : AgentEvent
    data class QuestionNeeded(val request: QuestionRequest) : AgentEvent
    data class EnvironmentNeeded(val request: EnvironmentRequest) : AgentEvent
    data class AssistantCommitted(val message: ChatMessage) : AgentEvent
    data class ToolMessageCommitted(val message: ChatMessage) : AgentEvent
    data class UserMessageInjected(val text: String) : AgentEvent
    data class Usage(
        val inputTokens: Int,
        val outputTokens: Int,
        val cachedInputTokens: Int,
        val cacheWriteTokens: Int = 0,
    ) : AgentEvent
    /** A transient provider failure will be retried after [delayMs]. */
    data class Retrying(val attempt: Int, val delayMs: Long, val reason: String) : AgentEvent
    data class EstimatedContext(val estimate: ContextEstimate) : AgentEvent
    data class Compacting(val reason: String) : AgentEvent
    data class Compacted(val summary: String) : AgentEvent
    data class Error(val message: String) : AgentEvent

    /** One progress line from inside a running subagent (the task tool). */
    data class SubagentStep(val toolCallId: String, val line: String) : AgentEvent

    /**
     * The run ended. [reason] explains an abnormal end (the model produced no
     * visible answer — e.g. it burned everything on reasoning); null on a
     * normal stop.
     */
    data class Finished(val reason: String? = null) : AgentEvent
}

/**
 * The harness loop: stream the model, execute requested tool calls (gated by
 * the permission mode), feed results back, repeat until the model stops.
 */
class AgentEngine(
    private val providerFactory: (ProviderConfig) -> LlmProvider,
    private val registry: ToolRegistry,
    private val checkpointer: CheckpointStore,
    private val imageStore: ImageStore,
    private val linuxEnv: com.androidharness.app.data.env.LinuxEnvironmentManager,
    private val shizuku: com.androidharness.app.data.env.ShizukuManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun run(
        sessionId: String,
        turnId: String,
        config: ProviderConfig,
        apiKey: String,
        history: List<ChatMessage>,
        /**
         * Re-read before every tool round, so switching the permission mode
         * mid-run applies immediately instead of on the next message.
         */
        permissionMode: suspend () -> PermissionMode,
        sessionAllowedTools: MutableSet<String>,
        workspace: WorkspaceFs,
        options: RequestOptions,
        maxContextTokens: Int,
        mode: AgentMode,
        userInjections: Channel<String>? = null,
        /** Hard cap on tool-call iterations; 0 or negative = unlimited. */
        maxIterations: Int = 0,
    ): Flow<AgentEvent> = channelFlow {
        // Parallel subagents emit from async children — plain flow{} forbids
        // cross-coroutine emission even when serialized, channelFlow exists
        // for exactly this. The local shim keeps every emit(...) call site.
        suspend fun emit(event: AgentEvent) = send(event)
        val systemPrompt = systemPrompt(workspace, mode)
        val tools = registry.schemas(readOnlyOnly = mode == AgentMode.PLAN)
        val working = trimHistory(
            history.map { it.withImagesResolved() },
            maxContextTokens, options.maxOutputTokens,
        ).toMutableList()
        val provider = providerFactory(config)
        // Stable per-session cache routing for providers that support it.
        val requestOptions = options.copy(cacheKey = sessionId)
        var iterations = 0
        // Termination code of the most recent streamed request, and how many
        // times a silent (reasoning-only, no answer) model has been asked to
        // actually produce its answer.
        var lastFinishReason: String? = null
        var answerNudges = 0

        while (true) {
            if (maxIterations > 0 && iterations++ >= maxIterations) {
                emit(AgentEvent.Error("Stopped after $maxIterations tool iterations (safety limit)."))
                break
            } else if (maxIterations <= 0) {
                iterations++ // tracked for nothing — unlimited mode
            }

            // Messages typed while the agent was running steer the next turn.
            userInjections?.let { channel ->
                while (true) {
                    val queued = channel.tryReceive().getOrNull() ?: break
                    working += ChatMessage(role = Role.USER, text = queued)
                    emit(AgentEvent.UserMessageInjected(queued))
                }
            }

            // Auto-compact before the request grows past the context budget.
            val estimate = estimateContext(working, systemPrompt)
            emit(AgentEvent.EstimatedContext(estimate))
            if (estimate.total > (maxContextTokens * 0.8).toInt() && working.size > 6) {
                val compacted = compact(provider, config, apiKey, working, maxContextTokens) { emit(it) }
                if (compacted != null) {
                    working.clear()
                    working.addAll(compacted)
                }
            }

            var text = StringBuilder()
            var thinking = StringBuilder()
            var calls = mutableListOf<ToolCallData>()
            var failure: String? = null

            // Request attempt loop: transient failures (429/5xx/network) are
            // retried with backoff, but ONLY while nothing has streamed yet —
            // re-emitting deltas the UI already showed would duplicate output.
            var attempt = 0
            while (true) {
                text = StringBuilder()
                thinking = StringBuilder()
                calls = mutableListOf()
                failure = null
                lastFinishReason = null
                var cause: Throwable? = null

                try {
                    provider.streamChat(config, apiKey, systemPrompt, working, tools, requestOptions)
                        .collect { event ->
                            when (event) {
                                is StreamEvent.TextDelta -> {
                                    text.append(event.text)
                                    emit(AgentEvent.Text(event.text))
                                }
                                is StreamEvent.ThinkingDelta -> {
                                    thinking.append(event.text)
                                    emit(AgentEvent.Thinking(event.text))
                                }
                                is StreamEvent.ToolCallReady -> calls += event.call
                                is StreamEvent.ToolCallBatch -> calls += event.calls
                                is StreamEvent.Batch -> event.events.forEach { nested ->
                                    when (nested) {
                                        is StreamEvent.TextDelta -> {
                                            text.append(nested.text)
                                            emit(AgentEvent.Text(nested.text))
                                        }
                                        is StreamEvent.ThinkingDelta -> {
                                            thinking.append(nested.text)
                                            emit(AgentEvent.Thinking(nested.text))
                                        }
                                        is StreamEvent.ToolCallReady -> calls += nested.call
                                        is StreamEvent.ToolCallBatch -> calls += nested.calls
                                        else -> {}
                                    }
                                }
                                is StreamEvent.Usage -> emit(
                                    AgentEvent.Usage(
                                        event.inputTokens, event.outputTokens,
                                        event.cachedInputTokens, event.cacheWriteTokens,
                                    )
                                )
                                is StreamEvent.Failure -> failure = event.message
                                is StreamEvent.Done -> lastFinishReason = event.finishReason
                            }
                        }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    cause = e
                    failure = e.message ?: e.javaClass.simpleName
                }

                val retryable = failure != null &&
                    attempt < RetryPolicy.MAX_RETRIES &&
                    text.isEmpty() && thinking.isEmpty() && calls.isEmpty() &&
                    RetryPolicy.isRetryable(cause, failure)
                if (!retryable) break
                attempt++
                val delayMs = RetryPolicy.delayMs(attempt)
                emit(AgentEvent.Retrying(attempt, delayMs, failure!!.take(200)))
                delay(delayMs)
            }

            // A turn that produced reasoning but no answer and no tool calls is
            // NOT committed: replaying an empty assistant message would hand
            // Anthropic an empty content array (a 400) and teaches the model
            // that silence was acceptable. Instead, nudge it once to actually
            // answer; if it still won't, end with a visible reason.
            val answerless = text.isBlank() && calls.isEmpty()
            val wantsNudge = failure == null && answerless &&
                thinking.isNotBlank() && answerNudges < MAX_ANSWER_NUDGES
            if (wantsNudge) {
                answerNudges++
                working += ChatMessage(role = Role.USER, text = ANSWER_NUDGE)
            } else if (!answerless) {
                val assistant = ChatMessage(
                    role = Role.ASSISTANT,
                    text = text.toString(),
                    toolCalls = calls.toList(),
                    thinking = thinking.toString(),
                )
                working += assistant
                emit(AgentEvent.AssistantCommitted(assistant))
            }

            when {
                failure != null -> {
                    emit(AgentEvent.Error(failure!!))
                    break
                }
                wantsNudge -> continue
                calls.isEmpty() -> {
                    emit(AgentEvent.Finished(emptyAnswerReason(text.toString(), thinking.toString(), lastFinishReason)))
                    break
                }
            }

            // Subagents are read-only, independent and slow — run every task
            // call in the batch CONCURRENTLY so research branches don't queue
            // behind each other. Ordinary tools keep strict sequential order.
            val subagentCalls = calls.filter { it.name == "task" }
            val otherCalls = calls.filterNot { it.name == "task" }
            val results = LinkedHashMap<String, ToolResult>()

            if (subagentCalls.isNotEmpty()) {
                // FlowCollector.emit is not concurrency-safe; parallel subagents
                // share one mutex-protected emitter.
                val emitLock = Mutex()
                val serialEmit: suspend (AgentEvent) -> Unit = { event ->
                    emitLock.withLock { emit(event) }
                }
                subagentCalls.forEach { call -> emit(AgentEvent.ToolStarted(call)) }
                coroutineScope {
                    subagentCalls.map { call ->
                        async {
                            call.id to executeWithPermission(
                                call, permissionMode(), sessionAllowedTools, workspace,
                                sessionId, turnId, mode, requestOptions, config, apiKey,
                                serialEmit,
                            )
                        }
                    }.awaitAll().forEach { (id, result) -> results[id] = result }
                }
            }

            for (call in otherCalls) {
                emit(AgentEvent.ToolStarted(call))
                results[call.id] = executeWithPermission(
                    call, permissionMode(), sessionAllowedTools, workspace,
                    sessionId, turnId, mode, requestOptions, config, apiKey,
                ) { emit(it) }
            }

            // Commit tool messages in the model's original call order.
            for (call in calls) {
                val result = results.getValue(call.id)
                val toolMessage = ChatMessage(
                    role = Role.TOOL,
                    text = result.output,
                    toolCallId = call.id,
                    toolName = call.name,
                    isError = !result.ok,
                )
                working += toolMessage
                emit(AgentEvent.ToolMessageCommitted(toolMessage))
                emit(AgentEvent.ToolFinished(call.id, result))
            }
        }
    }

    // ------------------------------------------------------------------

    private fun ChatMessage.withImagesResolved(): ChatMessage =
        if (images.isEmpty()) this
        else copy(imageData = images.mapNotNull { ref -> imageStore.resolve(ref) })

    /**
     * Executes one tool call with the current permission gating. [emitEvent]
     * is injected rather than taken from a FlowCollector receiver so parallel
     * subagents can share a serialized emitter.
     */
    private suspend fun executeWithPermission(
        call: ToolCallData,
        mode: PermissionMode,
        sessionAllowedTools: MutableSet<String>,
        workspace: WorkspaceFs,
        sessionId: String,
        turnId: String,
        agentMode: AgentMode,
        requestOptions: RequestOptions,
        config: ProviderConfig,
        apiKey: String,
        emitEvent: suspend (AgentEvent) -> Unit,
    ): ToolResult {
        val tool = registry.get(call.name)
            ?: return ToolResult(false, "Unknown tool: ${call.name}")

        // The model sometimes tries to modify files in plan mode — refuse cleanly.
        if (agentMode == AgentMode.PLAN && !tool.isReadOnly && call.name != "ask_user") {
            return ToolResult(
                false,
                "Plan mode is active: ${call.name} was rejected. Finish exploring and present your plan.",
            )
        }

        // task spawns a nested read-only agent; handled here, never executed.
        if (call.name == "task") {
            val args = runCatching {
                json.parseToJsonElement(call.argumentsJson).jsonObject
            }.getOrNull()
            val prompt = args?.get("prompt")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return ToolResult(false, "task requires a prompt.")
            return runSubagent(prompt, call.id, config, apiKey, workspace, requestOptions, emitEvent)
        }

        // Commands that need real toolchains (git/python/node/…) prompt the user
        // to install the bundled Linux environment straight from the chat.
        if ((call.name == "shell" || call.name == "shell_background") && !linuxEnv.isReady) {
            val args = runCatching {
                json.parseToJsonElement(call.argumentsJson).jsonObject
            }.getOrNull()
            val command = args?.get("command")?.jsonPrimitive?.content.orEmpty()
            val hints = detectToolchainHints(command)
            if (hints.isNotEmpty()) {
                // A previous install already failed — tell the model clearly and
                // do not re-prompt, so it stops retrying git/python/node commands.
                val failed = linuxEnv.state.value as? com.androidharness.app.data.env.EnvState.Failed
                if (failed != null) {
                    return ToolResult(
                        false,
                        "The Linux environment install failed earlier: ${failed.message}. " +
                            "Do NOT retry git/python/node commands in this session. " +
                            "Stick to toybox-compatible commands (ls, cat, grep, sed, mkdir, cp, mv…) " +
                            "or tell the user they can retry from Settings → Linux environment.",
                    )
                }
                val request = EnvironmentRequest(call, command, hints)
                emitEvent(AgentEvent.EnvironmentNeeded(request))
                if (!request.response.await()) {
                    return ToolResult(
                        false,
                        "The user declined installing the Linux environment right now. " +
                            "Do NOT retry git/python/node commands in this session — use only " +
                            "toybox-compatible commands (ls, cat, grep, sed, mkdir, cp, mv…) " +
                            "or continue with tasks that do not need them.",
                    )
                }
                // approved: install ran; fall through and execute with the new environment
            }
        }

        // ask_user is handled by the UI, never executed here.
        if (call.name == "ask_user") {
            val args = runCatching {
                json.parseToJsonElement(call.argumentsJson).jsonObject
            }.getOrNull()
            val question = args?.let { a ->
                a["question"]?.jsonPrimitive?.contentOrNull
                    ?: a["text"]?.jsonPrimitive?.contentOrNull
                    ?: a["query"]?.jsonPrimitive?.contentOrNull
            }?.takeIf { it.isNotBlank() }
                ?: return ToolResult(false, "ask_user requires a question.")
            val options = parseAskUserOptions(args["options"])
            val request = QuestionRequest(call.id, question, options.take(4))
            emitEvent(AgentEvent.QuestionNeeded(request))
            val answer = request.response.await()
            return ToolResult(true, "The user answered: $answer")
        }

        val approved = when {
            call.name in sessionAllowedTools -> true
            mode == PermissionMode.FULL_AUTO -> true
            mode == PermissionMode.CONFIRM_RISKY && tool.isReadOnly -> true
            else -> {
                val preview = computeDiffPreview(call, workspace)
                val request = ApprovalRequest(call, tool.description, preview)
                emitEvent(AgentEvent.ApprovalNeeded(request))
                request.response.await()
            }
        }
        if (!approved) {
            return ToolResult(false, "The user denied permission to run ${call.name}.")
        }

        val args = try {
            json.parseToJsonElement(call.argumentsJson).jsonObject
        } catch (e: Exception) {
            return ToolResult(false, "Invalid tool arguments: ${e.message}")
        }

        // Snapshot everything this tool might touch before it runs.
        checkpointTargets(call.name, args).forEach { path ->
            runCatching { checkpointer.snapshot(sessionId, turnId, workspace, path) }
        }

        return try {
            tool.execute(args, ToolContext(workspace))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ToolResult(false, e.message ?: "${call.name} failed")
        }
    }

    /** Files a tool call is about to touch, for pre-execution snapshots. */
    private fun checkpointTargets(toolName: String, args: kotlinx.serialization.json.JsonObject): List<String> {
        fun str(name: String) = args[name]?.jsonPrimitive?.content
        return when (toolName) {
            "write_file", "edit_file", "multi_edit", "delete_file", "create_dir" ->
                listOfNotNull(str("path"))
            "move_file" -> listOfNotNull(str("source"), str("destination"))
            "apply_patch" -> str("patch")?.let { patch ->
                Regex("\\+\\+\\+ (?!/dev/null)([^\n]+)").findAll(patch)
                    .map { it.groupValues[1].trim().removePrefix("b/") }
                    .distinct().toList()
            } ?: emptyList()
            else -> emptyList()
        }
    }

    /** Unified diff preview for approval cards on file-modifying tools. */
    private suspend fun computeDiffPreview(call: ToolCallData, workspace: WorkspaceFs): String? {
        return try {
            val args = json.parseToJsonElement(call.argumentsJson).jsonObject
            val path = args["path"]?.jsonPrimitive?.content ?: return null
            val oldText = runCatching {
                val node = workspace.resolve(path)
                if (node.exists && node.isFile) node.readText() else ""
            }.getOrDefault("")

            when (call.name) {
                "write_file" -> {
                    val content = args["content"]?.jsonPrimitive?.content ?: return null
                    Diff.unified(oldText, content, path)
                }
                "edit_file" -> {
                    val old = args["old_string"]?.jsonPrimitive?.content ?: return null
                    val new = args["new_string"]?.jsonPrimitive?.content ?: return null
                    val replaceAll = args["replace_all"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    val updated = when (
                        val r = FuzzyEdit.replace(oldText, old, new, replaceAll)
                    ) {
                        is FuzzyEdit.Result.Ok -> r.newText
                        else -> oldText
                    }
                    Diff.unified(oldText, updated, path)
                }
                "multi_edit" -> {
                    var updated = oldText
                    args["edits"]?.jsonArray?.forEach { el ->
                        val edit = el.jsonObject
                        val o = edit["old_string"]?.jsonPrimitive?.content ?: return@forEach
                        val n = edit["new_string"]?.jsonPrimitive?.content ?: return@forEach
                        val ra = edit["replace_all"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                        when (val r = FuzzyEdit.replace(updated, o, n, ra)) {
                            is FuzzyEdit.Result.Ok -> updated = r.newText
                            else -> {}
                        }
                    }
                    Diff.unified(oldText, updated, path)
                }
                "apply_patch" -> args["patch"]?.jsonPrimitive?.content?.take(6000)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Subagents
    // ------------------------------------------------------------------

    /** Tools a subagent may see: read-only, no ask_user (deadlock), no task (no nesting). */
    private fun subagentTools(): List<com.androidharness.app.llm.ToolSchema> =
        registry.schemas(readOnlyOnly = true).filter {
            it.name != "ask_user" && it.name != "task"
        }

    /**
     * Runs a nested read-only agent to answer [prompt] and returns its final
     * message as the tool result. The subagent explores with read-only tools
     * in its own context; only the final answer comes back to the parent, so
     * broad exploration never bloats the main conversation. Usage is
     * re-emitted so it rolls into the session totals (same as compaction).
     * Each action the subagent takes is emitted as [AgentEvent.SubagentStep]
     * keyed by the parent task call, so the UI can show live progress.
     * Cancellation propagates from the parent run.
     */
    private suspend fun runSubagent(
        prompt: String,
        parentCallId: String,
        config: com.androidharness.app.llm.ProviderConfig,
        apiKey: String,
        workspace: WorkspaceFs,
        requestOptions: RequestOptions,
        emitEvent: suspend (AgentEvent) -> Unit,
    ): ToolResult {
        suspend fun step(line: String) = emitEvent(AgentEvent.SubagentStep(parentCallId, line))
        step("Task: ${prompt.take(80)}")
        val provider = providerFactory(config)
        val system =
            "You are a read-only research subagent inside a coding harness. " +
                "Explore the workspace with the tools you have (read_file, list_dir, " +
                "search_files, grep, web_fetch/search) to answer the task. " +
                "You must not modify anything, and you cannot ask questions — if something " +
                "is ambiguous, state your assumption and continue. " +
                "Finish with a complete, self-contained answer: your final message is the " +
                "ONLY thing returned to the caller, so include file paths, line references " +
                "and concrete details, and no meta-commentary."
        val history = mutableListOf(ChatMessage(role = Role.USER, text = prompt))
        val subTools = subagentTools()
        // No separate budget quota here: capping output made reasoning models
        // burn the cap on thinking before ever answering ("reasoning streamed,
        // no answer"). Subagents get the main loop's full output budget.
        val ctx = ToolContext(workspace)

        var iteration = 0
        var nudged = false
        while (iteration < SUBAGENT_MAX_ITERATIONS) {
            iteration++
            val text = StringBuilder()
            val calls = mutableListOf<ToolCallData>()
            val subThinking = StringBuilder()
            var failure: String? = null
            var attempt = 0

            // Same transient-failure retry policy as the main loop.
            while (true) {
                text.clear()
                calls.clear()
                subThinking.setLength(0)
                failure = null
                var cause: Throwable? = null
                try {
                    provider.streamChat(config, apiKey, system, history, subTools, requestOptions)
                        .collect { event ->
                            when (event) {
                                is StreamEvent.TextDelta -> text.append(event.text)
                                is StreamEvent.ThinkingDelta -> subThinking.append(event.text)
                                is StreamEvent.ToolCallReady -> calls += event.call
                                is StreamEvent.ToolCallBatch -> calls += event.calls
                                is StreamEvent.Batch -> event.events.forEach { nested ->
                                    when (nested) {
                                        is StreamEvent.TextDelta -> text.append(nested.text)
                                        is StreamEvent.ThinkingDelta -> subThinking.append(nested.text)
                                        is StreamEvent.ToolCallReady -> calls += nested.call
                                        is StreamEvent.ToolCallBatch -> calls += nested.calls
                                        else -> {}
                                    }
                                }
                                is StreamEvent.Usage -> emitEvent(
                                    AgentEvent.Usage(
                                        event.inputTokens, event.outputTokens,
                                        event.cachedInputTokens, event.cacheWriteTokens,
                                    )
                                )
                                is StreamEvent.Failure -> failure = event.message
                                else -> {}
                            }
                        }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    cause = e
                    failure = e.message ?: e.javaClass.simpleName
                }
                val retryable = failure != null && attempt < RetryPolicy.MAX_RETRIES &&
                    text.isEmpty() && calls.isEmpty() && RetryPolicy.isRetryable(cause, failure)
                if (!retryable) break
                attempt++
                val delayMs = RetryPolicy.delayMs(attempt)
                emitEvent(AgentEvent.Retrying(attempt, delayMs, "subagent: ${failure!!.take(160)}"))
                delay(delayMs)
            }

            if (text.isBlank() && calls.isEmpty()) {
                // One continuation nudge mirrors the main loop: reasoning models
                // sometimes stop after thinking without emitting their answer.
                if (subThinking.isNotBlank() && !nudged) {
                    nudged = true
                    history += ChatMessage(role = Role.USER, text = ANSWER_NUDGE)
                    continue
                }
                // Reasoning models can burn the whole budget on thinking and
                // stream zero answer tokens — say so instead of "no output".
                val detail = when {
                    failure != null -> failure!!
                    subThinking.isNotBlank() ->
                        "reasoning streamed but no answer was produced (token budget exhausted by thinking)"
                    else -> "no output"
                }
                return ToolResult(
                    false,
                    "Subagent failed: $detail. Do the research yourself with read-only tools.",
                )
            }

            history += ChatMessage(role = Role.ASSISTANT, text = text.toString(), toolCalls = calls.toList())

            if (calls.isEmpty()) {
                // No more tool calls: this is the subagent's final answer.
                if (text.isNotBlank()) step("Writing answer")
                return if (text.isBlank()) {
                    ToolResult(
                        false,
                        if (subThinking.isNotBlank()) "Subagent produced reasoning but no answer."
                        else "Subagent produced no answer.",
                    )
                } else {
                    ToolResult(true, text.toString())
                }
            }

            // Execute requested tools directly. The schema list only contains
            // read-only, non-interactive tools, so no permission gating is
            // needed — but verify defensively and refuse anything else.
            for (call in calls) {
                step(describeToolCall(call))
                val tool = registry.get(call.name)
                val result = if (tool == null || !tool.isReadOnly || call.name == "ask_user" || call.name == "task") {
                    ToolResult(false, "${call.name} is not available to subagents.")
                } else {
                    try {
                        val args = json.parseToJsonElement(call.argumentsJson).jsonObject
                        tool.execute(args, ctx)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        ToolResult(false, e.message ?: "${call.name} failed")
                    }
                }
                history += ChatMessage(
                    role = Role.TOOL,
                    text = result.output,
                    toolCallId = call.id,
                    toolName = call.name,
                    isError = !result.ok,
                )
                if (!result.ok) step("${call.name} failed — adjusting")
            }
        }

        return ToolResult(
            false,
            "Subagent hit its $SUBAGENT_MAX_ITERATIONS-step limit without finishing. " +
                "Do the research yourself with read-only tools.",
        )
    }

    /** Summarize old history into one message when the context is nearly full. */
    private suspend fun compact(
        provider: LlmProvider,
        config: ProviderConfig,
        apiKey: String,
        working: MutableList<ChatMessage>,
        maxContextTokens: Int,
        emitEvent: suspend (AgentEvent) -> Unit,
    ): List<ChatMessage>? {
        emitEvent(AgentEvent.Compacting("Context near ${(maxContextTokens / 1000)}K — summarizing older messages"))

        // keep the most recent messages; never start the kept slice on a TOOL message
        var keep = 8
        while (keep < working.size && working[working.size - keep].role == Role.TOOL) keep++
        val keepCount = keep.coerceAtMost(working.size)
        val older = working.subList(0, working.size - keepCount)
        val recent = working.subList(working.size - keepCount, working.size)
        if (older.isEmpty()) return null

        val summary = StringBuilder()
        var compactError: String? = null
        var attempt = 0
        while (true) {
            var streamFailure: String? = null
            var cause: Throwable? = null
            summary.clear()
            try {
                provider.streamChat(
                    config, apiKey,
                    "Summarize this coding-agent conversation compactly. Preserve: the user's goal, " +
                        "files created/modified and their paths, key decisions, pending work and next steps. " +
                        "Output plain notes only.",
                    older, emptyList(),
                    RequestOptions(maxOutputTokens = 1_500, thinking = ThinkingLevel.OFF),
                ).collect { event ->
                    when (event) {
                        is StreamEvent.TextDelta -> summary.append(event.text)
                        is StreamEvent.Batch -> event.events.forEach { nested ->
                            if (nested is StreamEvent.TextDelta) summary.append(nested.text)
                        }
                        is StreamEvent.Usage -> emitEvent(
                            AgentEvent.Usage(
                                event.inputTokens, event.outputTokens,
                                event.cachedInputTokens, event.cacheWriteTokens,
                            )
                        )
                        is StreamEvent.Failure -> streamFailure = event.message
                        else -> {}
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                cause = e
                streamFailure = e.message ?: e.javaClass.simpleName
            }

            val retryable = streamFailure != null && summary.isBlank() &&
                attempt < RetryPolicy.MAX_RETRIES && RetryPolicy.isRetryable(cause, streamFailure)
            if (!retryable) {
                compactError = streamFailure
                break
            }
            attempt++
            val delayMs = RetryPolicy.delayMs(attempt)
            emitEvent(AgentEvent.Retrying(attempt, delayMs, streamFailure!!.take(200)))
            delay(delayMs)
        }
        compactError?.let { emitEvent(AgentEvent.Error("Auto-compaction failed: $it")) }
        if (summary.isBlank()) return null

        emitEvent(AgentEvent.Compacted(summary.toString()))
        val summaryMessage = ChatMessage(
            role = Role.USER,
            text = "$COMPACTION_PREFIX\n\n$summary",
        )
        return listOf(summaryMessage) + recent
    }

    /**
     * Explanation for a run that ended with no visible answer text; null when
     * the stop looks normal. An answer WITH text is always normal, regardless
     * of how much reasoning accompanied it.
     */
    private fun emptyAnswerReason(text: String, thinking: String?, finishReason: String?): String? {
        if (text.isNotBlank()) return null
        val cutOff = finishReason.equals("length", ignoreCase = true) ||
            finishReason.equals("max_tokens", ignoreCase = true)
        return when {
            !thinking.isNullOrBlank() && cutOff ->
                "Model spent its entire token budget on reasoning without producing an answer."
            !thinking.isNullOrBlank() ->
                "Model stopped after reasoning without producing an answer."
            cutOff -> "Model hit the token limit before producing output."
            else -> "Model returned no output at all — the model may be down, rate-limited, or incompatible."
        }
    }

    private fun trimHistory(
        history: List<ChatMessage>,
        maxContextTokens: Int,
        maxOutputTokens: Int,
    ): List<ChatMessage> {
        // reserve space for system prompt, tool schemas and the model's reply
        val budgetChars = (maxContextTokens - maxOutputTokens - 8_192) * 4
        if (budgetChars <= 0) return history.takeLast(2)
        var total = history.sumOf { it.text.length }
        if (total <= budgetChars) return history
        val kept = history.toMutableList()
        while (total > budgetChars && kept.size > 2) {
            val removed = kept.removeAt(0)
            total -= removed.text.length
            while (kept.firstOrNull()?.role == Role.TOOL && kept.size > 2) {
                total -= kept.removeAt(0).text.length
            }
        }
        return kept
    }

    private fun estimateContext(
        history: List<ChatMessage>,
        systemPrompt: String,
    ): ContextEstimate {
        val messagesChars = history.sumOf {
            it.text.length + it.toolCalls.sumOf { c -> c.argumentsJson.length }
        }
        val toolsChars = registry.schemas().sumOf {
            it.description.length + it.parametersJson.toString().length
        }
        return ContextEstimate(
            messagesTokens = messagesChars / 4,
            systemTokens = systemPrompt.length / 4,
            toolsTokens = toolsChars / 4,
        )
    }

    /**
     * Models pass options in wildly different shapes: string arrays, arrays of
     * objects, or a JSON-encoded string. Accept them all.
     */
    private fun parseAskUserOptions(element: kotlinx.serialization.json.JsonElement?): List<String> {
        if (element == null) return emptyList()
        val asArray = element as? kotlinx.serialization.json.JsonArray
        if (asArray != null) {
            return asArray.mapNotNull { el ->
                when (el) {
                    is kotlinx.serialization.json.JsonPrimitive -> el.contentOrNull
                    is kotlinx.serialization.json.JsonObject ->
                        el["option"]?.jsonPrimitive?.contentOrNull
                            ?: el["label"]?.jsonPrimitive?.contentOrNull
                            ?: el["text"]?.jsonPrimitive?.contentOrNull
                            ?: el["value"]?.jsonPrimitive?.contentOrNull
                    else -> null
                }
            }.filter { it.isNotBlank() }
        }
        // options came as a JSON-encoded string (models do this often)
        val raw = element.jsonPrimitive?.contentOrNull ?: return emptyList()
        return runCatching {
            val nested = json.parseToJsonElement(raw)
            parseAskUserOptions(nested)
        }.getOrDefault(emptyList())
    }

    private fun detectToolchainHints(command: String): List<String> {
        val hints = mutableListOf<String>()
        if (Regex("(^|[\\s;&|])git\\b").containsMatchIn(command)) hints += "git"
        if (Regex("python[0-9.]*|\\bpip[0-9.]*").containsMatchIn(command)) hints += "python"
        if (Regex("\\bnode\\b|\\bnpm\\b|\\bnpx\\b|\\byarn\\b").containsMatchIn(command)) hints += "node"
        if (Regex("\\b(gcc|g\\+\\+|clang|make|cmake|ninja|pkg-config)\\b").containsMatchIn(command)) hints += "compilers"
        if (Regex("\\b(curl|wget|ssh|scp|rsync|jq)\\b").containsMatchIn(command)) hints += "core-tools"
        return hints.distinct()
    }

    private fun systemPrompt(workspace: WorkspaceFs, mode: AgentMode): String {
        val agentsFile = readWorkspaceDoc(workspace, "AGENTS.md")
            ?: readWorkspaceDoc(workspace, "HARNESS.md")
        val memory = readWorkspaceDoc(workspace, "memory")

        val sb = StringBuilder()
        sb.append(
            """
You are AndroidHarness, an autonomous coding agent running inside an Android app.
The user's workspace is: ${workspace.displayPath}

Rules:
- All tool paths are relative to the workspace root. Paths outside the workspace are blocked.
- Use list_dir/search_files/grep/read_file to explore before making changes.
- Prefer edit_file/multi_edit for targeted changes to existing files; use write_file to create or fully rewrite files; use apply_patch for multi-file diffs.
- Use todo_write to track multi-step work and keep statuses current.
- Use ask_user whenever a decision is genuinely the user's to make instead of guessing.
- For broad exploration whose raw output would flood this conversation (finding all usages, mapping a codebase, comparing many files), delegate to the task tool: it runs a read-only subagent and returns only the final answer.

""".trim()
        )
        if (workspace.shellRoot != null) {
            if (linuxEnv.isReady) {
                sb.append("- The shell tool runs a full Linux environment (bash, git, python, node and more) with the workspace as its working directory. Call commands by their plain names (python3, git, node, ls, …) — the harness launches them correctly on every execution tier. Use shell_background for long-running servers.\n")
            } else {
                sb.append("- The shell tool currently runs Android's toybox sh (a real Linux environment can be installed). If a task needs git, python, node, compilers, curl/ssh or similar, do NOT retry with toybox — call the shell tool anyway with the command you need; the harness will show the user an install button in the chat. For everything else use shell_background for long-running servers.\n")
            }
        } else {
            sb.append("- The shell tool runs in the app's shell workspace (${linuxEnv.shellFallbackRoot.absolutePath}) because the active workspace is a picked folder (SAF) that shell cannot access. Use file tools for the picked folder's files; use shell for toolchain/global commands. To run or host a project with the shell (node, python…), create its files inside the shell workspace itself — e.g. via shell heredocs (cat > server.js <<'EOF' …) — then run or shell_background them from there. Alternatively tell the user to switch the workspace in Settings to a real folder.\n")
        }

        // Shizuku guidance — tell the agent the current state so it can guide the user.
        when {
            shizuku.isGranted() -> sb.append("- Shizuku is connected with ADB-shell privileges: the shell tool automatically runs as the shell user whenever the working directory needs it (system paths, shared storage), with the same toolchain. Just use shell normally.\n")
            shizuku.state.value == com.androidharness.app.data.env.ShizukuState.RUNNING_NO_PERMISSION -> sb.append("- Shizuku is running but AndroidHarness hasn't been granted access yet. If a task needs ADB-level shell access (edit system files, access any folder, etc.), tell the user to go to Settings → Terminal and tap \"Grant Shizuku access\".\n")
            shizuku.state.value == com.androidharness.app.data.env.ShizukuState.NOT_RUNNING -> sb.append("- Shizuku is installed but not running. If a task needs ADB-level shell access, tell the user to open the Shizuku app, start the service, then in AndroidHarness go to Settings → Terminal and tap \"Refresh status\" followed by \"Grant Shizuku access\".\n")
            else -> {} // NOT_INSTALLED — no mention; don't distract the agent.
        }
        sb.append(
            "- Shell environment rules (IMPORTANT): always call commands by plain name (ls, grep, head, python3, git, node…). NEVER work around the environment yourself — do not invoke /system/bin/linker64, /apex/.../linker64, or /system/bin/toybox directly, and do not craft alternate PATHs. The harness already makes every toolchain binary runnable in every tier. " +
                "If a basic command fails with \"Permission denied\" or exit code 126/127, the environment is misconfigured on this device — run the env_status tool once, tell the user what it reports, and stop retrying command variants.\n",
        )
        sb.append("- /data/local/tmp is readable only by the shell user — never try to inspect it from the app tier, and never conclude Shizuku/toolchain state from files there; use env_status.\n")
        sb.append("- After tool calls complete, either continue with more tool calls or give the user a concise summary of what you did.\n")
        sb.append("- Never invent file contents you have not read.\n")

        if (mode == AgentMode.PLAN) {
            sb.append(
                "\nPLAN MODE IS ACTIVE:\n" +
                    "- You may only inspect the workspace with read-only tools. Any attempt to modify files will be rejected.\n" +
                    "- Explore what is needed, then finish with a clear, concrete, step-by-step plan the user can approve.\n"
            )
        }
        agentsFile?.let {
            sb.append("\n# AGENTS.md (project instructions)\n").append(it).append('\n')
        }
        memory?.let {
            sb.append("\n# Agent memory (from previous sessions)\n").append(it).append('\n')
        }
        return sb.toString()
    }

    private fun readWorkspaceDoc(workspace: WorkspaceFs, name: String): String? = runCatching {
        when (name) {
            "memory" -> {
                val node = workspace.resolve(com.androidharness.app.tools.MemoryWriteTool.MEMORY_PATH)
                if (node.exists && node.isFile) node.readText() else null
            }
            else -> {
                val node = workspace.resolve(name)
                if (node.exists && node.isFile) node.readText() else null
            }
        }?.take(16_000)
    }.getOrNull()

    companion object {
        const val COMPACTION_PREFIX = "[Auto-compacted context — summary of the earlier conversation]"
        /** Max tool rounds a subagent may take before giving up. */
        const val SUBAGENT_MAX_ITERATIONS = 12

        /** How many times a silent (reasoning-only, no answer) model is asked to answer. */
        const val MAX_ANSWER_NUDGES = 1
        private val ANSWER_NUDGE =
            "Your previous reply ended without any visible answer — it was likely cut off " +
                "mid-generation. Respond now with your actual answer (or the tool calls you intended)."
    }
}
