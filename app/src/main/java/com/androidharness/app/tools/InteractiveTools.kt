package com.androidharness.app.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Placeholder tool: the engine intercepts ask_user before execution and turns
 * it into a UI question; execute() is never actually reached.
 */
class AskUserTool : Tool {
    override val name = "ask_user"
    override val description =
        "Ask the user a clarifying question and wait for their answer. Use this whenever " +
        "a decision is genuinely the user's to make instead of guessing. You may call ask_user " +
        "several times in the same turn to gather everything you need before acting. " +
        "Provide 2-4 short options when possible; the user can also type a free-form answer. " +
        "Set multi_select=true when several answers can be true at once (the user gets " +
        "checkboxes instead of single-choice chips); offer up to 8 options then."
    override val parametersSchema = Schema.obj(
        mapOf(
            "question" to Schema.string("The question to show the user."),
            "options" to Schema.array(
                Schema.string("One possible answer."),
                "Optional suggested answers (2-4, or up to 8 with multi_select).",
            ),
            "multi_select" to Schema.boolean(
                "Allow the user to pick several options at once (checkboxes). Default false.",
            ),
        ),
        required = listOf("question"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        ToolResult(false, "ask_user must be handled by the engine, not executed directly.")
}

/** Persistent agent memory stored in the workspace at .harness/memory.md. */
class MemoryWriteTool : Tool {
    override val name = "memory_write"
    override val description =
        "Write to the agent memory. Without topic: the core memory file (.harness/memory.md), " +
            "which is automatically loaded at the start of every conversation, so keep it to small, " +
            "always-relevant facts (preferences, conventions, decisions). With topic: a topic file " +
            "under .harness/memory/<topic>.md for longer, task-specific notes; topic files are " +
            "listed by name in the system prompt and retrieved with memory_read/memory_search."
    override val parametersSchema = Schema.obj(
        mapOf(
            "content" to Schema.string("Text to write."),
            "mode" to Schema.string("'append' (default) adds to the file; 'replace' overwrites it."),
            "topic" to Schema.string(
                "Optional topic name (already lowercase a-z, 0-9, '-' or '_'; anything else is " +
                    "refused, not renamed). Writes to .harness/memory/<topic>.md instead of the " +
                    "core memory file.",
            ),
        ),
        required = listOf("content"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val content = args["content"]?.jsonPrimitive?.content
            ?: throw ToolFailure("Missing required argument: content")
        val mode = args["mode"]?.jsonPrimitive?.content ?: "append"
        val topic = args["topic"]?.jsonPrimitive?.content?.trim()

        val path = if (topic.isNullOrEmpty()) {
            MEMORY_PATH
        } else {
            com.androidharness.app.agent.MemoryTopics.strictTopicPath(topic)
                ?: throw ToolFailure(
                    "Invalid topic '$topic'. Topics must already be 1-48 chars of lowercase " +
                        "letters, digits, '-' or '_'; invalid names are refused, not renamed. " +
                        "Omit the topic argument to write core memory.",
                )
        }
        val maxChars = if (topic.isNullOrEmpty()) {
            com.androidharness.app.agent.MemoryNotes.MAX_CHARS
        } else {
            com.androidharness.app.agent.MemoryTopics.MAX_TOPIC_CHARS
        }

        val node = ctx.workspace.resolve(path)
        val existing = if (node.exists && node.isFile) node.readText() else ""
        val next = com.androidharness.app.agent.MemoryNotes.write(existing, content, mode, maxChars)
        node.writeText(next)
        return ToolResult(true, "Memory ${if (mode == "replace") "replaced" else "updated"} ($path).")
    }

    companion object {
        const val MEMORY_PATH = ".harness/memory.md"
        const val MAX_MEMORY_CHARS = com.androidharness.app.agent.MemoryNotes.MAX_CHARS
    }
}
