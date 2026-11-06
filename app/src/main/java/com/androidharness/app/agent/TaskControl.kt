package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.llm.ProviderConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Serializable
data class QueuedPrompt(val id: String = UUID.randomUUID().toString(), val text: String)

@Serializable
data class TaskLimits(val tokens: Long = 0, val cost: Double = 0.0, val minutes: Long = 0) {
    init { require(tokens >= 0 && cost.isFinite() && cost >= 0 && minutes in 0..525600) }
}

@Serializable
data class TaskRecord(
    val status: String = "idle",
    val reason: String? = null,
    val provider: ProviderConfig? = null,
    val allowModelFallback: Boolean = true,
    val fallbackMode: com.androidharness.app.llm.FallbackMode = com.androidharness.app.llm.FallbackMode.DEFAULT,
    val fallbackOverride: com.androidharness.app.llm.FallbackPolicy? = null,
    val fallbackPrimary: ProviderConfig? = null,
    val usedProviderIds: Set<String> = emptySet(),
    val workspacePath: String = "",
    val workspacePaths: List<String> = emptyList(),
    val mode: String = "ACT",
    val thinking: String = "OFF",
    val maxOutput: Int = 32768,
    val maxContext: Int = 1000000,
    /** Null follows app settings; zero explicitly selects automatic (80%) for this chat. */
    val autoCompactTokens: Int? = null,
    val maxIterations: Int = 0,
    val turnId: String = "",
    val initialPrompt: String = "",
    val initialPromptId: String = "",
    val automationRunId: String? = null,
    val completed: Boolean = false,
    val images: List<com.androidharness.app.core.ImageRef> = emptyList(),
    val queue: List<QueuedPrompt> = emptyList(),
    val pins: String = "",
    val summaryOverride: String? = null,
    val contextAfter: Long = 0,
    val limits: TaskLimits = TaskLimits(),
    val usedTokens: Long = 0,
    val usedCost: Double = 0.0,
    val elapsedMs: Long = 0,
    val autoContinue: Boolean = false,
    val autoContinueLimit: Int = 3,
    val autoContinueAttempts: Int = 0,
    /** Null follows the app setting; a non-blank value is sent when this chat resumes, manually or automatically. */
    val resumePrompt: String? = null,
) {
    val resumable: Boolean get() = status == "running" || status == "paused" || status == "interrupted"
}

/** Private app storage, atomic replacement, no credentials. Writes finish before actions proceed. */
class TaskControlStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val flows = mutableMapOf<String, MutableStateFlow<TaskRecord>>()
    @Synchronized fun flow(id: String): MutableStateFlow<TaskRecord> = flows.getOrPut(id) {
        val file = file(id)
        MutableStateFlow(if (file.exists()) runCatching { json.decodeFromString<TaskRecord>(file.readText()).let { saved ->
                // Only disk restoration means a running task lost its process.
                if (saved.status == "running") saved.copy(status = "interrupted") else saved
            } }
            .getOrElse { TaskRecord(status = "paused", reason = "Saved task settings could not be read. Chat history is still available.") }
            else TaskRecord())
    }
    @Synchronized fun update(id: String, transform: (TaskRecord) -> TaskRecord): TaskRecord {
        val flow = flow(id)
        val next = transform(flow.value)
        directory.mkdirs()
        val target = file(id)
        val tmp = File(directory, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(next).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        check(tmp.renameTo(target)) { "Could not save task progress" }
        flow.value = next
        return next
    }
    private fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return File(directory, "$id.json")
    }
}

class TaskPaused(val reasonText: String) : RuntimeException(reasonText)

/** Shared by the parent, compaction, and every subagent through coroutine context. */
class TaskBudget(
    private val limits: TaskLimits,
    private var tokens: Long,
    private var cost: Double,
    private val priorMs: Long,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TaskBudget>
    private val started = clock()
    @Synchronized fun add(input: Int, output: Int, dollars: Double?) {
        tokens += input.toLong() + output
        cost += dollars ?: 0.0
    }
    @Synchronized fun usage(): Triple<Long, Double, Long> = Triple(tokens, cost, priorMs + clock() - started)
    @Synchronized fun check() {
        val u = usage()
        val reason = when {
            limits.tokens > 0 && u.first >= limits.tokens -> "Token limit reached"
            limits.cost > 0 && u.second >= limits.cost -> "Estimated cost limit reached"
            limits.minutes > 0 && u.third >= limits.minutes * 60000 -> "Time limit reached"
            else -> null
        }
        if (reason != null) throw TaskPaused(reason)
    }
}

/** Close orphan tool calls without replaying operations whose outcome is unknown. */
object RunRecovery {
    const val CONTINUE_PROMPT = "Resume the interrupted task from saved progress. Completed tool results are authoritative. Inspect uncertain outcomes before further actions; do not repeat completed operations."
    const val MAX_PROMPT_CHARS = 2000

    /** The chat's own prompt wins, then the app setting, then the built-in text. Blank values never win. */
    fun resolvePrompt(chat: String?, app: String): String =
        chat?.trim()?.takeIf { it.isNotEmpty() } ?: app.trim().takeIf { it.isNotEmpty() } ?: CONTINUE_PROMPT

    /** Text the user typed, trimmed and capped; null when it is empty or just the prompt that would apply anyway. */
    fun customPrompt(typed: String, fallback: String): String? =
        typed.trim().take(MAX_PROMPT_CHARS).trim().takeIf { it.isNotEmpty() && it != fallback.trim() }

    const val CHAT_CONTINUATION_ID_PREFIX = "internal-chat-continuation-"
    fun isInternalChatContinuation(id: String?): Boolean = id?.startsWith(CHAT_CONTINUATION_ID_PREFIX) == true
    fun missingResults(messages: List<ChatMessage>): List<ChatMessage> {
        val results = messages.filter { it.role == Role.TOOL }.mapNotNull { it.toolCallId }.toSet()
        return messages.filter { it.role == Role.ASSISTANT && it.toolCallId == null }
            .flatMap { it.toolCalls }.filter { it.id !in results }.map {
                ChatMessage(role = Role.TOOL, toolCallId = it.id, toolName = it.name, isError = true,
                    text = "Interrupted: outcome unknown. Inspect the current state before taking another action. Do not blindly repeat this operation.")
            }
    }
    fun context(messages: List<ChatMessage>, record: TaskRecord): List<ChatMessage> {
        val slice = messages.filter { it.createdAt >= record.contextAfter ||
            (it.role == Role.SYSTEM && it.text.startsWith(AgentEngine.COMPACTION_PREFIX)) }
        val history = ContextHygiene.forModel(slice)
        return if (record.summaryOverride == null) history else
            listOf(ContextHygiene.summaryMessage(record.summaryOverride)) + history.filterNot {
                it.role == Role.SYSTEM && (it.text.startsWith(AgentEngine.COMPACTION_PREFIX) ||
                    it.text.startsWith(ContextHygiene.COMPACTION_NOTICE_PREFIX))
            }
    }
}

/** Successful writes from the interrupted task are never executed twice. */
class RecoveryLedger(messages: List<ChatMessage>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RecoveryLedger>
    private val outcomes = messages.filter { it.role == Role.TOOL }.associateBy { it.toolCallId }
    private val calls = messages.filter { it.role == Role.ASSISTANT && it.toolCallId == null }
        .flatMap { it.toolCalls }.associateBy { signature(it) }
    fun result(call: com.androidharness.app.core.ToolCallData): com.androidharness.app.tools.ToolResult? {
        // Restoration rechecks missing/current-hash preconditions itself. A
        // previously restored file may have vanished again during interruption.
        if (call.name == "file_restore") return null
        val original = calls[signature(call)] ?: return null
        val outcome = outcomes[original.id]
        if (outcome != null && !outcome.isError) return com.androidharness.app.tools.ToolResult(true,
            "Already completed before interruption; not repeated.\n${outcome.text}")
        if (outcome == null || outcome.text.startsWith("Interrupted: outcome unknown"))
            return com.androidharness.app.tools.ToolResult(false,
                "This operation has an uncertain outcome from interruption. Inspect current state and choose a safe next step; it was not repeated.")
        return null
    }
    private fun signature(call: com.androidharness.app.core.ToolCallData): String =
        call.name + ":" + runCatching { Json.parseToJsonElement(call.argumentsJson).toString() }.getOrDefault(call.argumentsJson)
}
