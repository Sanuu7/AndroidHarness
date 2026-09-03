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
import com.androidharness.app.workspace.UnboundedFileFs
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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
    /** Every sandbox layer off: no approvals, no shell denylist, no path containment. */
    FULL_ACCESS("Full access"),
}

/** ACT executes tools; PLAN only allows inspection and must end with a plan. */
enum class AgentMode { ACT, PLAN }

class ApprovalRequest(
    val call: ToolCallData,
    val toolDescription: String,
    val diffPreview: String? = null,
    /** What "Always" remembers: a shell command signature, or the tool name. */
    val grantKey: String = call.name,
) {
    val response = CompletableDeferred<Boolean>()
}

class QuestionRequest(
    val callId: String,
    val question: String,
    val options: List<String>,
    /** Checkbox UI: the user may pick several options at once. */
    val multiSelect: Boolean = false,
) {
    val response = CompletableDeferred<String>()
}

/** The agent wants to run commands that need the Linux environment. */
class EnvironmentRequest(
    val call: ToolCallData,
    val command: String,
    val hints: List<String>,
    /** True when the environment is installed but broken: the card becomes Repair. */
    val repair: Boolean = false,
    /** The tool that came back "not found" (repair flow only). */
    val missingTool: String? = null,
) {
    val response = CompletableDeferred<Boolean>()
}

/** Headline tools → the binaries that must exist in the prefix for them. */
internal val HEADLINE_TOOL_BINARIES: Map<String, List<String>> = mapOf(
    "git" to listOf("bin/git"),
    "python" to listOf("bin/python3", "bin/python"),
    "node" to listOf("bin/node"),
    "npm" to listOf("bin/npm"),
    "pip" to listOf("bin/pip", "bin/pip3"),
    "bash" to listOf("bin/bash"),
)

/**
 * Detects a failed command whose failure is a MISSING HARNESS TOOL rather than
 * a missing project binary: "npm: not found", "bash: node: command not found",
 * "git: command not found" (exit 127 style). Returns the tool name, or null
 * when the failure is unrelated to the harness toolchain.
 */
internal fun detectMissingHeadlineTool(output: String): String? {
    if (!output.contains("not found", ignoreCase = true)) return null
    for ((tool, _) in HEADLINE_TOOL_BINARIES) {
        val failed = Regex("(^|[\\s/])${Regex.escape(tool)}:\\s*(command )?not found")
        if (failed.containsMatchIn(output)) return tool
    }
    return null
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
        /** Which model burned these tokens (config-effective id), for per-model stats. */
        val model: String = "",
        /** Display name of the provider that served them. */
        val providerName: String = "",
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
     * An inner subagent turn (assistant message or tool result) committed
     * durably, linked to its parent task call so the subagent page can replay
     * it as a chat. RunManager owns persistence; the main chat hides these
     * (assistant rows with a non-null toolCallId are inner turns).
     */
    data class SubagentMessageCommitted(val parentCallId: String, val message: ChatMessage) : AgentEvent

    /**
     * Line-change stats from one successful editing tool call ("+N −M" chips),
     * plus the pre-change state the Files-changed tracker needs: [existedBefore]
     * flags whether the path existed before this call, [beforeText] carries its
     * content when capturable (≤512KB), and [existsAfter] reports survival,
     * together they let RunManager pin the per-session diff baseline.
     */
    data class FileEdited(
        val turnId: String,
        val relPath: String,
        val added: Long,
        val removed: Long,
        val existedBefore: Boolean = false,
        val existsAfter: Boolean = true,
        val beforeText: String? = null,
    ) : AgentEvent

    /**
     * The run ended. [reason] explains an abnormal end (the model produced no
     * visible answer, e.g. it burned everything on reasoning); null on a
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
    private val skills: com.androidharness.app.skills.SkillStore,
    private val todoStore: TodoStore? = null,
    private val repoMap: com.androidharness.app.repomap.RepoMapCache? = null,
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
        /** Run-scoped tools (e.g. connected MCP servers) on top of the registry. */
        extraTools: List<com.androidharness.app.tools.Tool> = emptyList(),
        /**
         * Resolves the task tool's optional `model` override to a catalog id.
         * Null disables overrides: a task that passes `model` is refused with
         * a clear message instead of silently running on the wrong model.
         */
        resolveSubagentModel: (suspend (String) -> SubagentModelResolution)? = null,
        repoMapEnabled: Boolean = true,
    ): Flow<AgentEvent> = channelFlow {
        // Parallel subagents emit from async children, plain flow{} forbids
        // cross-coroutine emission even when serialized, channelFlow exists
        // for exactly this. The local shim keeps every emit(...) call site.
        suspend fun emit(event: AgentEvent) = send(event)
        // Rebuilt when Full access toggles, because the path rules the model
        // is told about change with it.
        var systemPrompt = systemPrompt(workspace, mode, fullAccess = false, repoMapEnabled = repoMapEnabled)
        var promptSandboxOff = false
        val runRegistry = registry.withExtra(extraTools)
        val tools = runRegistry.schemas(readOnlyOnly = mode == AgentMode.PLAN)
        val working = trimHistory(
            ContextHygiene.shrinkToolResults(history.map { it.withImagesResolved() }),
            maxContextTokens, options.maxOutputTokens,
        ).toMutableList()
        // If active model is known to be text-only, strip image data upfront
        if (!com.androidharness.app.llm.visionCapable(config.model)) {
            val sanitized = ContextHygiene.stripImages(working)
            working.clear()
            working.addAll(sanitized)
        }
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
                iterations++ // tracked for nothing, unlimited mode
            }

            // Messages typed while the agent was running steer the next turn.
            userInjections?.let { channel ->
                while (true) {
                    val queued = channel.tryReceive().getOrNull() ?: break
                    working += ChatMessage(role = Role.USER, text = queued)
                    emit(AgentEvent.UserMessageInjected(queued))
                }
            }
            val shrunk = ContextHygiene.shrinkToolResults(working)
            working.clear()
            working.addAll(shrunk)

            // Read once per round, so a mid-run mode switch applies here;
            // Full access additionally lifts every sandbox layer for the
            // tools this round executes.
            val effectiveMode = permissionMode()
            val sandboxOff = effectiveMode == PermissionMode.FULL_ACCESS
            if (sandboxOff != promptSandboxOff) {
                promptSandboxOff = sandboxOff
                systemPrompt = systemPrompt(workspace, mode, fullAccess = sandboxOff, repoMapEnabled = repoMapEnabled)
            }
            // Open path resolution only exists on real-filesystem workspaces;
            // SAF has no shell root and stays inside its picked tree.
            val execWorkspace =
                if (sandboxOff && !workspace.isSaf && workspace.shellRoot != null) {
                    UnboundedFileFs(workspace.shellRoot!!)
                } else {
                    workspace
                }

            // Auto-compact before the request grows past the context budget.
            val estimate = estimateContext(working, systemPrompt)
            emit(AgentEvent.EstimatedContext(estimate))
            if (estimate.total > (maxContextTokens * 0.8).toInt() && working.size > 6) {
                val compacted = compact(provider, config, apiKey, working, maxContextTokens) { emit(it) }
                if (compacted != null) {
                    working.clear()
                    working.addAll(compacted)
                    // The window just shrank: refresh the context panel now
                    // instead of waiting for the next request's usage row.
                    emit(AgentEvent.EstimatedContext(estimateContext(working, systemPrompt)))
                }
            }

            var text = StringBuilder()
            var thinking = StringBuilder()
            var calls = mutableListOf<ToolCallData>()

            val streamEventHandler: suspend (StreamEvent) -> Unit = { event ->
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
                            config.model, config.name,
                        )
                    )
                    is StreamEvent.Done -> lastFinishReason = event.finishReason
                    else -> {}
                }
            }

            // Request attempt loop: transient failures (429/5xx/network) are
            // retried with backoff, but ONLY while nothing has streamed yet,
            // re-emitting deltas the UI already showed would duplicate output.
            var failure = StreamRetrier.run(
                streamFor = {
                    provider.streamChat(config, apiKey, systemPrompt, working, tools, requestOptions)
                },
                onAttemptStart = {
                    text = StringBuilder()
                    thinking = StringBuilder()
                    calls = mutableListOf()
                    lastFinishReason = null
                },
                hasOutput = { text.isNotEmpty() || thinking.isNotEmpty() || calls.isNotEmpty() },
                handleEvent = streamEventHandler,
                retryReason = { f -> f.take(200) },
                emitEvent = { emit(it) },
            )

            // Dynamic vision degradation fallback: if provider rejected image input,
            // strip images from working history and retry without failing the turn.
            if (failure != null && isVisionError(failure) && working.any { it.imageData.isNotEmpty() }) {
                val stripped = ContextHygiene.stripImages(working)
                working.clear()
                working.addAll(stripped)
                failure = StreamRetrier.run(
                    streamFor = {
                        provider.streamChat(config, apiKey, systemPrompt, working, tools, requestOptions)
                    },
                    onAttemptStart = {
                        text = StringBuilder()
                        thinking = StringBuilder()
                        calls = mutableListOf()
                        lastFinishReason = null
                    },
                    hasOutput = { text.isNotEmpty() || thinking.isNotEmpty() || calls.isNotEmpty() },
                    handleEvent = streamEventHandler,
                    retryReason = { f -> f.take(200) },
                    emitEvent = { emit(it) },
                )
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

            // Subagents are read-only, independent and slow, run every task
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
                android.util.Log.d(
                    "HarnessSpawn",
                    "batch of ${subagentCalls.size} task call(s): ${subagentCalls.map { it.id }}",
                )
                coroutineScope {
                    subagentCalls.map { call ->
                        async {
                            android.util.Log.d("HarnessSpawn", "task ${call.id} START ${System.currentTimeMillis()}")
                            val result = executeWithPermission(
                                call, effectiveMode, sessionAllowedTools, execWorkspace,
                                sessionId, turnId, mode, requestOptions, config, apiKey,
                                runRegistry, resolveSubagentModel, serialEmit,
                            )
                            android.util.Log.d("HarnessSpawn", "task ${call.id} END ${System.currentTimeMillis()}")
                            call.id to result
                        }
                    }.awaitAll().forEach { (id, result) -> results[id] = result }
                }
            }

            for (call in otherCalls) {
                emit(AgentEvent.ToolStarted(call))
                results[call.id] = executeWithPermission(
                    call, effectiveMode, sessionAllowedTools, execWorkspace,
                    sessionId, turnId, mode, requestOptions, config, apiKey,
                    runRegistry, resolveSubagentModel,
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
                    images = listOfNotNull(result.image),
                )
                working += toolMessage.withImagesResolved()
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
        registry: com.androidharness.app.tools.ToolRegistry,
        resolveSubagentModel: (suspend (String) -> SubagentModelResolution)?,
        emitEvent: suspend (AgentEvent) -> Unit,
    ): ToolResult {
        val tool = registry.get(call.name)
            ?: return ToolResult(false, "Unknown tool: ${call.name}")

        // The model sometimes tries to modify files in plan mode, refuse cleanly.
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
            val title = args["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val requestedModel = args["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            var taskConfig = config
            if (requestedModel != null) {
                val resolver = resolveSubagentModel
                    ?: return ToolResult(
                        false,
                        "task `model` overrides are not available in this run. " +
                            "Retry the task without `model`.",
                    )
                when (val outcome = resolver(requestedModel)) {
                    is SubagentModelResolution.Resolved ->
                        taskConfig = config.copy(model = outcome.modelId)
                    is SubagentModelResolution.Unknown -> {
                        val listing = if (outcome.available.isEmpty()) {
                            "the provider's catalog is empty or does not support listing models"
                        } else {
                            outcome.available.take(25).joinToString() +
                                if (outcome.available.size > 25) " … (+${outcome.available.size - 25} more)" else ""
                        }
                        return ToolResult(
                            false,
                            "Unknown task model '$requestedModel': $listing. " +
                                "Retry with a listed id, or omit `model` to use ${config.model}.",
                        )
                    }
                    is SubagentModelResolution.Failed ->
                        return ToolResult(
                            false,
                            "Could not verify task model '$requestedModel': ${outcome.message}. " +
                                "Retry the task, or omit `model` to use ${config.model}.",
                        )
                }
            }
            return runSubagent(
                prompt, title, call.id, taskConfig, apiKey, workspace, requestOptions, emitEvent,
                sandboxOff = mode == PermissionMode.FULL_ACCESS,
            )
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
                // A previous install already failed, tell the model clearly and
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
                            "Do NOT retry git/python/node commands in this session: use only " +
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
            val rawQuestion = args?.let { a ->
                a["question"]?.jsonPrimitive?.contentOrNull
                    ?: a["text"]?.jsonPrimitive?.contentOrNull
                    ?: a["query"]?.jsonPrimitive?.contentOrNull
            }?.takeIf { it.isNotBlank() }
                ?: return ToolResult(false, "ask_user requires a question.")
            val multiSelectFlag = (args["multi_select"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull == "true"
            var options = parseAskUserOptions(args["options"])
            var questionText = rawQuestion
            var multiSelect = multiSelectFlag
            if (options.isEmpty()) {
                // Models often bake the choices into the question as a markdown
                // bullet list and leave `options` empty, pull them out so the
                // user gets tappable rows instead of a wall of text.
                val (cleanQuestion, extracted) = extractOptionsFromQuestion(rawQuestion)
                if (extracted.isNotEmpty()) {
                    questionText = cleanQuestion
                    options = extracted
                    if (!multiSelect &&
                        Regex(
                            "all that apply|multiple (answers|choices|select)|select (all|every)",
                            RegexOption.IGNORE_CASE,
                        ).containsMatchIn(rawQuestion)
                    ) multiSelect = true
                }
            }
            val request = QuestionRequest(
                call.id,
                questionText,
                options.take(if (multiSelect) 8 else 6),
                multiSelect = multiSelect,
            )
            emitEvent(AgentEvent.QuestionNeeded(request))
            val answer = request.response.await()
            return ToolResult(true, "The user answered: $answer")
        }

        val command = if (call.name == "shell" || call.name == "shell_background") {
            com.androidharness.app.tools.ShellPolicy.commandOf(call.argumentsJson)
        } else null
        val root = workspace.shellRoot
        // Full access skips the shell denylist entirely, that is the point of the mode.
        if (mode != PermissionMode.FULL_ACCESS) {
            com.androidharness.app.tools.ShellPolicy.denyReason(command.orEmpty(), root, root)?.let { reason ->
                if (call.name == "shell" || call.name == "shell_background") {
                    return ToolResult(false, reason)
                }
            }
        }
        val grantKey = com.androidharness.app.tools.ShellPolicy.grantKey(call.name, command)
        val isPkgInstall = call.name == "pkg_install"
        val approved = when {
            isPkgInstall -> {
                // Mandatory confirmation: even in FULL_ACCESS or FULL_AUTO mode,
                // package installation ALWAYS requires explicit user confirmation (Decline or Allow).
                val preview = computePkgInstallPreview(call)
                val request = ApprovalRequest(call, tool.description, preview, grantKey)
                emitEvent(AgentEvent.ApprovalNeeded(request))
                request.response.await()
            }
            mode == PermissionMode.FULL_ACCESS -> true
            com.androidharness.app.tools.ShellPolicy.isGranted(call.name, command, sessionAllowedTools) -> true
            mode == PermissionMode.FULL_AUTO -> true
            mode == PermissionMode.CONFIRM_RISKY && tool.isReadOnly -> true
            else -> {
                val preview = computeDiffPreview(call, workspace)
                    ?: command?.take(400)
                val request = ApprovalRequest(call, tool.description, preview, grantKey)
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

        // Snapshot everything this tool might touch before it runs, and keep
        // the before-text for "+N −M" diff stats. existed flags distinguish
        // "file absent" (empty baseline) from "file too big to capture".
        val beforeTexts = LinkedHashMap<String, Pair<Boolean, String?>>()
        checkpointTargets(call.name, args).forEach { path ->
            runCatching { checkpointer.snapshot(sessionId, turnId, workspace, path) }
            runCatching {
                val node = workspace.resolve(path)
                beforeTexts[path] =
                    if (node.exists && node.isFile) {
                        true to (if (node.length <= 512_000) node.readText() else null)
                    } else {
                        false to null
                    }
            }
        }

        val startedAt = System.currentTimeMillis()
        val executed = try {
            val raw = tool.execute(args, ToolContext(workspace, mode == PermissionMode.FULL_ACCESS))
            raw.copy(output = com.androidharness.app.tools.SecretRedactor.redact(raw.output))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ToolResult(false, e.message ?: "${call.name} failed")
        }

        // Broken-environment repair: a headline tool died with "not found"
        // while the environment claims Ready. That is the harness's bug (a
        // half-installed toolchain), not the task's, surface a repair card
        // in chat instead of letting the model retry blindly. The presence
        // check keeps project-level failures ("vite: not found") out: those
        // are not fixable by reinstalling the toolchain.
        if (call.name == "shell" && !executed.ok &&
            linuxEnv.isReady && linuxEnv.state.value !is com.androidharness.app.data.env.EnvState.Failed
        ) {
            val missingTool = detectMissingHeadlineTool(executed.output)
            val binaries = missingTool?.let { HEADLINE_TOOL_BINARIES[it] }
            if (binaries != null &&
                binaries.none { java.io.File(linuxEnv.prefix, it).exists() }
            ) {
                val request = EnvironmentRequest(
                    call, command ?: missingTool, listOf(missingTool),
                    repair = true, missingTool = missingTool,
                )
                emitEvent(AgentEvent.EnvironmentNeeded(request))
                if (request.response.await()) {
                    // Repaired: run the exact same command again for the model.
                    val retry = try {
                        tool.execute(args, ToolContext(workspace, mode == PermissionMode.FULL_ACCESS))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        ToolResult(false, e.message ?: "${call.name} failed again after repair")
                    }
                    return retry.copy(
                        output = "[Linux environment repaired: $missingTool is now installed]\n" + retry.output,
                    )
                }
                return executed.copy(
                    output = executed.output +
                        "\n[note: the user declined repairing the Linux environment ($missingTool is missing). " +
                        "Do NOT retry commands that need it this session; continue with what works " +
                        "or tell the user to repair from Settings → Linux environment.]",
                )
            }
        }

        // Surface tool-call latency so the model can tell work from hangs and batch
        // or split accordingly.
        val elapsedMs = System.currentTimeMillis() - startedAt
        val result = if (executed.output.length < 100_000) {
            val secs = elapsedMs / 1000.0
            val note = if (elapsedMs >= 60_000) "[took ${"%.0f".format(secs)}s]" else "[took ${"%.1f".format(secs)}s]"
            executed.copy(output = executed.output + (if (executed.output.isEmpty()) "" else "\n") + note)
        } else {
            executed
        }

        // Diff stats: only when the tool succeeded and actually changed lines.
        // Newly created directories must not surface as file changes.
        if (result.ok && beforeTexts.isNotEmpty()) {
            for ((path, pre) in beforeTexts) {
                val (existedBefore, before) = pre
                runCatching {
                    val node = workspace.resolve(path)
                    val nodeIsFile = node.exists && node.isFile
                    val after =
                        if (nodeIsFile && node.length <= 512_000) node.readText() else ""
                    val (added, removed) = com.androidharness.app.core.Diff.lineCounts(
                        before ?: "", after,
                    )
                    val changedLines = added > 0 || removed > 0
                    val newlyCreated = !existedBefore && !node.isDirectory
                    val deleted = existedBefore && !nodeIsFile
                    when {
                        node.isDirectory && !existedBefore -> Unit
                        changedLines || newlyCreated || deleted -> emitEvent(
                            // Normalized so "./x.html" and "x.html" land in the
                            // same change row instead of splitting the counts.
                            AgentEvent.FileEdited(
                                turnId, com.androidharness.app.workspace.normalizeRelPath(path),
                                added.toLong(), removed.toLong(),
                                existedBefore = existedBefore,
                                existsAfter = nodeIsFile,
                                beforeText = before,
                            ),
                        )
                    }
                }
            }
        }
        return result
    }

    /** Files a tool call is about to touch, for pre-execution snapshots. */
    private fun checkpointTargets(toolName: String, args: kotlinx.serialization.json.JsonObject): List<String> {
        fun str(name: String) = args[name]?.jsonPrimitive?.content
        return when (toolName) {
            "write_file", "edit_file", "multi_edit", "delete_file", "create_dir" ->
                listOfNotNull(str("path"))
            "move_file" -> listOfNotNull(str("source"), str("destination"))
        "apply_patch" -> str("patch")?.let { patch ->
            // Mirror ApplyPatchTool's own path cleanup (a/ b/ prefixes, tab
            // suffixes) and also capture DELETED files ("--- x" + "+++ /dev/null"),
            // which the old regex silently dropped, those were unrecoverable.
            val targets = LinkedHashSet<String>()
            var oldPath: String? = null
            patch.lines().forEach { line ->
                when {
                    line.startsWith("--- ") ->
                        oldPath = line.removePrefix("--- ").trim()
                            .removePrefix("a/").substringBefore('\t').ifBlank { null }
                    line.startsWith("+++ ") -> {
                        val newPath = line.removePrefix("+++ ").trim()
                        if (newPath == "/dev/null") {
                            oldPath?.let { targets += it }
                        } else {
                            targets += newPath.removePrefix("b/").substringBefore('\t')
                        }
                        oldPath = null
                    }
                }
            }
            targets.filter { it.isNotBlank() }.toList()
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

    /** Formats a preview for package installation approval warnings. */
    private fun computePkgInstallPreview(call: ToolCallData): String? = runCatching {
        val args = json.parseToJsonElement(call.argumentsJson).jsonObject
        val pkgs = when (val p = args["packages"]) {
            is kotlinx.serialization.json.JsonArray -> p.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim() }.filter { it.isNotEmpty() }
            else -> listOfNotNull((args["package"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()).filter { it.isNotEmpty() }
        }
        val reason = (args["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        buildString {
            append("Packages to install:\n")
            pkgs.forEach { append("  • ").append(it).append('\n') }
            if (!reason.isNullOrBlank()) {
                append("\nReason: ").append(reason).append('\n')
            }
            append("\nNote: Installing packages downloads data and modifies your Linux environment.")
        }.trim()
    }.getOrNull()

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
        title: String?,
        parentCallId: String,
        config: com.androidharness.app.llm.ProviderConfig,
        apiKey: String,
        workspace: WorkspaceFs,
        requestOptions: RequestOptions,
        emitEvent: suspend (AgentEvent) -> Unit,
        sandboxOff: Boolean = false,
    ): ToolResult {
        suspend fun step(line: String) = emitEvent(AgentEvent.SubagentStep(parentCallId, line))
        val label = if (title.isNullOrBlank()) "Task" else "Task [$title]"
        step("$label: ${prompt.take(80)}")
        val provider = providerFactory(config)
        val system =
            "You are a read-only research subagent inside a coding harness. " +
                "Explore the workspace with the tools you have (read_file, list_dir, " +
                "search_files, grep, file_info, web_fetch/search) to answer the task. " +
                "You must not modify anything, and you cannot ask questions; if something " +
                "is ambiguous, state your assumption and continue. " +
                "When reporting file properties like newlines or byte counts, inspect with file_info rather than inferring from line counts. " +
                "Finish with a complete, self-contained answer: your final message is the " +
                "ONLY thing returned to the caller, so include file paths, line references " +
                "and concrete details, and no meta-commentary."
        val history = mutableListOf(ChatMessage(role = Role.USER, text = prompt))
        val subTools = subagentTools()
        // No separate budget quota here: capping output made reasoning models
        // burn the cap on thinking before ever answering (reasoning streamed,
        // no answer). Subagents get the main loop's full output budget.
        val ctx = ToolContext(workspace, sandboxOff)

        var iteration = 0
        var nudged = false
        // No step cap (user decision): a subagent runs until it answers, fails
        // structurally, or its provider stops responding. long research
        // passes are legitimate, and the parent sees real failures only.
        while (true) {
            iteration++
            val text = StringBuilder()
            val calls = mutableListOf<ToolCallData>()
            val subThinking = StringBuilder()

            // Same transient-failure retry policy as the main loop. Unlike the
            // main loop, thinking output does not block a retry here: the
            // subagent streams no deltas to the UI, so nothing can duplicate.
            val failure = StreamRetrier.run(
                streamFor = {
                    provider.streamChat(config, apiKey, system, history, subTools, requestOptions)
                },
                onAttemptStart = {
                    text.clear()
                    calls.clear()
                    subThinking.setLength(0)
                },
                hasOutput = { text.isNotEmpty() || calls.isNotEmpty() },
                handleEvent = { event ->
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
                                config.model, config.name,
                            )
                        )
                        else -> {}
                    }
                },
                retryReason = { f -> "subagent: ${f.take(160)}" },
                emitEvent = { emitEvent(it) },
            )

            if (text.isBlank() && calls.isEmpty()) {
                // One continuation nudge mirrors the main loop: reasoning models
                // sometimes stop after thinking without emitting their answer.
                if (subThinking.isNotBlank() && !nudged) {
                    nudged = true
                    history += ChatMessage(role = Role.USER, text = ANSWER_NUDGE)
                    continue
                }
                // Reasoning models can burn the whole budget on thinking and
                // stream zero answer tokens. say so instead of "no output".
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

            history += ChatMessage(
                role = Role.ASSISTANT,
                text = text.toString(),
                toolCalls = calls.toList(),
                thinking = subThinking.toString(),
            )
            emitEvent(
                AgentEvent.SubagentMessageCommitted(
                    parentCallId,
                    ChatMessage(
                        role = Role.ASSISTANT,
                        text = text.toString(),
                        toolCalls = calls.toList(),
                        thinking = subThinking.toString(),
                        toolCallId = parentCallId,
                    ),
                )
            )

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
            // needed. but verify defensively and refuse anything else.
            for (call in calls) {
                step(describeToolCall(call))
                val tool = registry.get(call.name)
                val subStartedAt = System.currentTimeMillis()
                val result = if (tool == null || !tool.isReadOnly || call.name == "ask_user" || call.name == "task") {
                    ToolResult(false, "${call.name} is not available to subagents.")
                } else {
                    val executed = try {
                        val args = json.parseToJsonElement(call.argumentsJson).jsonObject
                        val raw = tool.execute(args, ctx)
                        raw.copy(output = com.androidharness.app.tools.SecretRedactor.redact(raw.output))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        ToolResult(false, e.message ?: "${call.name} failed")
                    }
                    val elapsedMs = System.currentTimeMillis() - subStartedAt
                    if (executed.output.length < 100_000) {
                        val secs = elapsedMs / 1000.0
                        val note = if (elapsedMs >= 60_000) "[took ${"%.0f".format(secs)}s]" else "[took ${"%.1f".format(secs)}s]"
                        executed.copy(output = executed.output + (if (executed.output.isEmpty()) "" else "\n") + note)
                    } else {
                        executed
                    }
                }
                val toolMessage = ChatMessage(
                    role = Role.TOOL,
                    text = result.output,
                    toolCallId = call.id,
                    toolName = call.name,
                    isError = !result.ok,
                    images = listOfNotNull(result.image),
                )
                history += toolMessage.withImagesResolved()
                emitEvent(AgentEvent.SubagentMessageCommitted(parentCallId, toolMessage))
                if (!result.ok) step("${call.name} failed. adjusting")
            }
        }
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
        emitEvent(AgentEvent.Compacting("Context near ${(maxContextTokens / 1000)}K. summarizing older messages"))

        // keep the most recent messages; never start the kept slice on a TOOL message
        var keep = 8
        while (keep < working.size && working[working.size - keep].role == Role.TOOL) keep++
        val keepCount = keep.coerceAtMost(working.size)
        val older = working.subList(0, working.size - keepCount)
        val recent = working.subList(working.size - keepCount, working.size)
        if (older.isEmpty()) return null

        val summary = StringBuilder()
        val olderClean = ContextHygiene.stripImages(older)
        val compactError = StreamRetrier.run(
            streamFor = {
                provider.streamChat(
                    config, apiKey,
                    "Summarize this coding-agent conversation compactly. Preserve: the user's goal, " +
                        "files created/modified and their paths, key decisions, pending work and next steps. " +
                        "Output plain notes only.",
                    olderClean, emptyList(),
                    RequestOptions(maxOutputTokens = 1_500, thinking = ThinkingLevel.OFF),
                )
            },
            onAttemptStart = { summary.clear() },
            hasOutput = { summary.isNotBlank() },
            handleEvent = { event ->
                when (event) {
                    is StreamEvent.TextDelta -> summary.append(event.text)
                    is StreamEvent.Batch -> event.events.forEach { nested ->
                        if (nested is StreamEvent.TextDelta) summary.append(nested.text)
                    }
                    is StreamEvent.Usage -> emitEvent(
                        AgentEvent.Usage(
                            event.inputTokens, event.outputTokens,
                            event.cachedInputTokens, event.cacheWriteTokens,
                            config.model, config.name,
                        )
                    )
                    else -> {}
                }
            },
            retryReason = { f -> f.take(200) },
            emitEvent = { emitEvent(it) },
        )
        compactError?.let { emitEvent(AgentEvent.Error("Auto-compaction failed: $it")) }
        if (summary.isBlank()) return null

        emitEvent(AgentEvent.Compacted(summary.toString()))
        return listOf(ContextHygiene.summaryMessage(summary.toString())) + recent
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
            else -> "Model returned no output at all: the model may be down, rate-limited, or incompatible."
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
     * Post-compaction estimate for the context panel (the /compact path runs
     * outside the request loop, so nothing else recomputes it): same math as
     * the run loop, over the model-facing history slice.
     */
    fun estimateFor(
        history: List<ChatMessage>,
        workspace: WorkspaceFs,
        mode: AgentMode,
        fullAccess: Boolean,
        repoMapEnabled: Boolean = true,
    ): ContextEstimate = estimateContext(history, systemPrompt(workspace, mode, fullAccess, repoMapEnabled))

    /**
     * Models pass options in wildly different shapes: string arrays, arrays of
     * objects, or a JSON-encoded string. Accept them all.
     */
    /**
     * Models deliver ask_user options in every shape imaginable: ["a","b"],
     * [{"label":"a"}], {"1":"a","2":"b"}, "a, b, c", or JSON double-encoded as
     * a string. Anything odd used to throw out of jsonPrimitive and the whole
     * question never rendered, this variant never throws and salvages what it
     * can from every shape.
     */
    private fun parseAskUserOptions(element: kotlinx.serialization.json.JsonElement?): List<String> {
        if (element == null || element is kotlinx.serialization.json.JsonNull) return emptyList()
        return runCatching { parseOptionsSafe(element, depth = 0) }.getOrDefault(emptyList())
    }

    private fun parseOptionsSafe(
        element: kotlinx.serialization.json.JsonElement,
        depth: Int,
    ): List<String> {
        if (depth > 3) return emptyList()
        return when (element) {
            is kotlinx.serialization.json.JsonNull -> emptyList()
            is kotlinx.serialization.json.JsonArray ->
                element.flatMap { parseOptionsSafe(it, depth + 1) }

            is kotlinx.serialization.json.JsonPrimitive -> {
                val content = element.contentOrNull?.trim().orEmpty()
                when {
                    content.isEmpty() -> emptyList()
                    // JSON or string-encoded JSON: reparse, fall back to raw text.
                    content.startsWith("[") || content.startsWith("{") -> {
                        val nested = runCatching { json.parseToJsonElement(content) }.getOrNull()
                        if (nested != null) parseOptionsSafe(nested, depth + 1) else listOf(content)
                    }
                    // Delimited plain text: "a | b", "a\nb", "a, b, c" (3+ parts
                    // only, so "Yes, definitely" stays one option).
                    content.contains('|') || content.contains('\n') -> splitOptionText(content)
                    content.contains(", ") && splitOptionText(content).size >= 3 ->
                        splitOptionText(content)
                    else -> listOf(content)
                }
            }

            is kotlinx.serialization.json.JsonObject -> {
                // One option described as an object: take its display text.
                val label = listOf(
                    "option", "label", "text", "value", "title", "name",
                    "answer", "choice", "description",
                ).firstNotNullOfOrNull { key ->
                    (element[key] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.contentOrNull?.takeIf { it.isNotBlank() }
                }
                if (label != null) return listOf(label)
                // Wrapper objects: {"options": [...]} / {"choices": [...]}
                val wrapper = element["options"] ?: element["choices"] ?: element["items"]
                if (wrapper != null) return parseOptionsSafe(wrapper, depth + 1)
                // Map form: {"1": "a", "2": "b"}, the values are the options.
                val values = element.values.mapNotNull { v ->
                    (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                }
                if (values.size >= 2) values else emptyList()
            }
        }
    }

    private fun splitOptionText(text: String): List<String> =
        text.split('|', '\n').flatMap { part ->
            if (part.contains(", ")) part.split(", ") else listOf(part)
        }.map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(12)

    private val OPTION_BULLET = Regex("""^\s*(?:[-*•‣–]|\d{1,2}[.)])\s+(.+)$""")

    /**
     * Splits a trailing markdown bullet/numbered list off the question text:
     * returns the trimmed question and the extracted option labels. Only
     * fires when at least two consecutive trailing lines are bullets.
     */
    private fun extractOptionsFromQuestion(question: String): Pair<String, List<String>> {
        val lines = question.trimEnd().lines()
        val opts = ArrayList<String>()
        var i = lines.lastIndex
        while (i >= 0) {
            val match = OPTION_BULLET.matchEntire(lines[i]) ?: break
            val text = match.groupValues[1].trim()
            if (text.isEmpty()) break
            opts.add(0, text)
            i--
        }
        if (opts.size < 2) return question to emptyList()
        return lines.take(i + 1).joinToString("\n").trimEnd() to opts.take(8)
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

    private fun systemPrompt(
        workspace: WorkspaceFs,
        mode: AgentMode,
        fullAccess: Boolean,
        repoMapEnabled: Boolean = true,
    ): String {
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
- Browser screenshots save under .harness/screenshots/ as timestamped JPEGs (e.g. .harness/screenshots/20260903_120000.jpg). To look up past screenshots, list the directory with list_dir path=".harness/screenshots". Use read_image with the screenshot path or filename to inspect visual content.
- A message may reference files as @path (for example @src/Main.kt): the user is pointing at those exact files, so read them before acting on the request.
- Prefer edit_file/multi_edit for targeted changes to existing files; use write_file to create or fully rewrite files; use apply_patch for multi-file diffs.
- Use todo_write to track multi-step work and keep statuses current.
- Use ask_user whenever a decision is genuinely the user's to make instead of guessing.
- Tool calls in one message run in order, and each sees the workspace as of the END of the previous call: a patch or diff pre-computed before an earlier edit in the same message can be stale by the time it runs. Build patches right before applying them.
- Text files follow the POSIX convention: the last line ends with a newline terminator. When patching or editing, never add an extra empty line for the file's final newline.
- When reporting file properties like newlines or byte counts, verify with a byte check (file_info or shell tail -c 3 | xxd) rather than inferring from line counts.
- For Android logs, exceptions, or app crash investigations, use read_logcat instead of raw shell logcat commands. It supports level, tag, package_name, and buffer filtering.
- For broad exploration whose raw output would flood this conversation (finding all usages, mapping a codebase, comparing many files), delegate to the task tool: it runs a read-only subagent and returns only the final answer. When several independent explorations are needed, issue ALL task calls in the SAME message: they run concurrently.

""".trim()
        )
        if (workspace.shellRoot != null) {
            if (linuxEnv.isReady) {
                sb.append("- The shell tool runs a full Linux environment (bash, git, python, node and more) with the workspace as its working directory. Call commands by their plain names (python3, git, node, ls, …): the harness launches them correctly on every execution tier. Use shell_background for long-running servers. If a required CLI package is missing (e.g. ripgrep, jq, clang, rust, tmux, tree, openjdk-17), search for it with pkg_search and install it with pkg_install (do NOT run 'apt' or 'pkg' directly in shell). Package installation will always prompt the user with a confirmation warning before downloading.\n")
            } else {
                sb.append("- The shell tool currently runs Android's toybox sh (a real Linux environment can be installed). If a task needs git, python, node, compilers, curl/ssh or similar, do NOT retry with toybox: call the shell tool anyway with the command you need; the harness will show the user an install button in the chat. For everything else use shell_background for long-running servers.\n")
            }
        } else {
            sb.append("- This workspace has no real filesystem path (cloud/SAF). File tools still work. Do NOT call shell, shell_background, or git tools, they will fail. Tell the user to switch to a device folder or the app workspace if they need a shell.\n")
        }

        // Shizuku guidance: tell the agent the current state so it can guide the user.
        when {
            shizuku.isGranted() -> sb.append("- Shizuku is connected with ADB-shell privileges: the shell tool automatically runs as the shell user whenever the working directory needs it (system paths, shared storage), with the same toolchain. Just use shell normally.\n")
            shizuku.state.value == com.androidharness.app.data.env.ShizukuState.RUNNING_NO_PERMISSION -> sb.append("- Shizuku is running but AndroidHarness hasn't been granted access yet. If a task needs ADB-level shell access (edit system files, access any folder, etc.), tell the user to go to Settings → Terminal and tap \"Grant Shizuku access\".\n")
            shizuku.state.value == com.androidharness.app.data.env.ShizukuState.NOT_RUNNING -> sb.append("- Shizuku is installed but not running. If a task needs ADB-level shell access, tell the user to open the Shizuku app, start the service, then in AndroidHarness go to Settings → Terminal and tap \"Refresh status\" followed by \"Grant Shizuku access\".\n")
            else -> {} // NOT_INSTALLED: no mention; don't distract the agent.
        }
        sb.append(
            "- Shell environment rules (IMPORTANT): always call commands by plain name (ls, grep, head, python3, git, node…). NEVER work around the environment yourself: do not invoke /system/bin/linker64, /apex/.../linker64, or /system/bin/toybox directly, and do not craft alternate PATHs. The harness already makes every toolchain binary runnable in every tier. " +
                "If a basic command fails with \"Permission denied\" or exit code 126/127, the environment is misconfigured on this device: run the env_status tool once, tell the user what it reports, and stop retrying command variants.\n",
        )
        sb.append("- /data/local/tmp is readable only by the shell user: never try to inspect it from the app tier, and never conclude Shizuku/toolchain state from files there; use env_status.\n")
        if (fullAccess) {
            sb.append(
                "- FULL ACCESS MODE is active: the workspace sandbox is lifted. File tools may read and write ANY path on the device (absolute paths work), the shell has no command denylist, and cwd may be any directory. The user chose this deliberately; no permission prompts will appear. Work outside the workspace only when the task requires it, and stay careful with system directories (/system, /data/system, /vendor): a mistake there can break the device.\n",
            )
        }
        sb.append("- After tool calls complete, either continue with more tool calls or give the user a concise summary of what you did.\n")
        sb.append("- Never invent file contents you have not read.\n")
        sb.append("- Browser automation: when previewing or interacting with web projects via browser tools, start a local dev/http server using shell_background (e.g. python3 -m http.server 8000, npm run dev, or similar) and navigate to http://localhost:<port>.\n")
        sb.append("- Mobile UI formatting: responses are displayed on a phone touchscreen. Format text cleanly and compactly. Use standard markdown tables (| Col 1 | Col 2 |) or concise bullet lists rather than wide ASCII terminal boxes. When creating, editing, or serving HTML/web projects, host them on localhost via shell_background and include an explicit preview directive `::web-preview{target=\"http://localhost:<port>\"}` to offer the user a one-tap in-app web preview button.\n")

        if (mode == AgentMode.PLAN) {
            sb.append(
                "\nPLAN MODE IS ACTIVE:\n" +
                    "- You may only inspect the workspace with read-only tools. Any attempt to modify files will be rejected.\n" +
                    "- Explore what is needed, then finish with a clear, concrete, step-by-step plan the user can approve.\n"
            )
        }
        val catalog = skills.catalog()
        if (catalog.isNotBlank()) sb.append('\n').append(catalog).append('\n')
        agentsFile?.let {
            sb.append("\n# AGENTS.md (project instructions)\n").append(it).append('\n')
        }
        memory?.let {
            sb.append("\n# Agent memory (from previous sessions)\n").append(it).append('\n')
        }
        // Topic files are only listed by name: long-form notes stay on disk
        // and out of the prompt; the model retrieves them on demand.
        val memoryTopics = runCatching {
            com.androidharness.app.tools.listMemoryTopics(workspace)
        }.getOrDefault(emptyList())
        if (memoryTopics.isNotEmpty()) {
            sb.append("\nLong-term memory topic files on disk (retrieve with memory_read, search with memory_search): ")
                .append(memoryTopics.joinToString(", ")).append('\n')
        }
        val todos = TodoPrompt.format(todoStore?.todos?.value.orEmpty())
        if (todos.isNotBlank()) {
            sb.append('\n').append(todos)
        }
        if (repoMapEnabled) {
            val map = runCatching {
                kotlinx.coroutines.runBlocking { repoMap?.getMap(workspace, maxChars = 10_000) }
            }.getOrNull()
            if (!map.isNullOrBlank()) {
                sb.append("\n# Repository Map (codebase index)\n").append(map).append('\n')
            }
        }
        return sb.toString()
    }

    private fun readWorkspaceDoc(workspace: WorkspaceFs, name: String): String? = runCatching {
        when (name) {
            "memory" -> {
                val node = workspace.resolve(com.androidharness.app.tools.MemoryWriteTool.MEMORY_PATH)
                if (node.exists && node.isFile) MemoryNotes.load(node.readText()) else null
            }
            else -> {
                val node = workspace.resolve(name)
                if (node.exists && node.isFile) node.readText() else null
            }
        }?.take(16_000)
    }.getOrNull()

    companion object {
        const val COMPACTION_PREFIX = "[Auto-compacted context: summary of the earlier conversation]"

        /** How many times a silent (reasoning-only, no answer) model is asked to answer. */
        const val MAX_ANSWER_NUDGES = 1
        private val ANSWER_NUDGE =
            "Your previous reply ended without any visible answer: it was likely cut off " +
                "mid-generation. Respond now with your actual answer (or the tool calls you intended)."

        val VISION_ERROR_REGEX = Regex(
            "image|vision|multimodal|inlineData|image_url|unsupported media|expected text only|does not support image",
            RegexOption.IGNORE_CASE,
        )

        fun isVisionError(error: String): Boolean =
            VISION_ERROR_REGEX.containsMatchIn(error)
    }
}
