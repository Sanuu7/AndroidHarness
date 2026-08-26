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
        "Provide 2-4 short options when possible; the user can also type a free-form answer."
    override val parametersSchema = Schema.obj(
        mapOf(
            "question" to Schema.string("The question to show the user."),
            "options" to Schema.array(
                Schema.string("One possible answer."),
                "Optional suggested answers (2-4).",
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
        "Write to the agent memory file (.harness/memory.md in the workspace). Use it to record " +
        "user preferences, project conventions and decisions that should survive across sessions. " +
        "The memory is automatically loaded at the start of every conversation."
    override val parametersSchema = Schema.obj(
        mapOf(
            "content" to Schema.string("Text to write."),
            "mode" to Schema.string("'append' (default) adds to the file; 'replace' overwrites it."),
        ),
        required = listOf("content"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val content = args["content"]?.jsonPrimitive?.content
            ?: throw ToolFailure("Missing required argument: content")
        val mode = args["mode"]?.jsonPrimitive?.content ?: "append"

        val path = MEMORY_PATH
        val node = ctx.workspace.resolve(path)
        val existing = if (node.exists && node.isFile) node.readText() else ""
        val next = com.androidharness.app.agent.MemoryNotes.write(existing, content, mode)
        node.writeText(next)
        return ToolResult(true, "Memory ${if (mode == "replace") "replaced" else "updated"} ($path).")
    }

    companion object {
        const val MEMORY_PATH = ".harness/memory.md"
        const val MAX_MEMORY_CHARS = com.androidharness.app.agent.MemoryNotes.MAX_CHARS
    }
}
