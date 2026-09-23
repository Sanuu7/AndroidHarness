package com.androidharness.app.tools

import kotlinx.serialization.json.JsonObject

/**
 * Delegates a task to a nested agent with its own context window. The engine
 * intercepts calls to this tool and runs the subagent loop itself. Spawning
 * is read-only; action tools inside the subagent use their own permission gates.
 */
class TaskTool : Tool {
    override val name = "task"
    override val description =
        "Run a subagent with its own context. By default it explores read-only; " +
        "when Subagent action tools is enabled in Settings, it can also edit files and run tools " +
        "in Act mode under the current permission mode. " +
        "Use it for delegated work whose raw output would bloat this conversation: " +
        "you get back only the subagent's final, self-contained answer. " +
        "Need several independent explorations? Issue ALL task calls in ONE message: " +
        "they run concurrently. Never serialize independent research into separate turns. " +
        "Pass `model` to run the task on a cheaper or faster catalog model than " +
        "this conversation uses. " +
        "The subagent cannot ask you questions or spawn further subagents."
    override val parametersSchema = Schema.obj(
        mapOf(
            "prompt" to Schema.string(
                "The research question and everything the subagent needs to know; " +
                    "it does NOT share this conversation's context.",
            ),
            "title" to Schema.string(
                "Short label shown on the subagent's progress, e.g. 'Find config sources'.",
            ),
            "model" to Schema.string(
                "Optional model id from this provider's catalog to run the subagent on " +
                    "(defaults to this conversation's model). Unknown ids are rejected " +
                    "with the list of valid ids.",
            ),
        ),
        required = listOf("prompt"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        ToolResult(false, "task is handled by the engine and cannot be executed directly.")
}
