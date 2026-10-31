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
        "Give each delegate a short, recognizable name with `name` and a clear task in `prompt`. " +
        "Set `background: true` to continue the main conversation immediately; the result returns a task_id. " +
        "Background delegates explore read-only. Use subagent_status to check real activity, subagent_wait " +
        "when you need an answer, and subagent_stop to cancel one delegate. Completed background results " +
        "are delivered between requests; do not claim a result until it is available. " +
        "Need several independent explorations? Issue ALL task calls in ONE message: " +
        "they run concurrently. Never serialize independent research into separate turns. " +
        "A model chosen in Sub-Agent settings is used for every subagent. Otherwise, " +
        "pass `model` to run the task on a cheaper or faster catalog model than this conversation uses. " +
        "The subagent cannot ask you questions or spawn further subagents."
    override val parametersSchema = Schema.obj(
        mapOf(
            "name" to Schema.string("Short name for this subagent, such as Build checker or UI reviewer. Keep it under 40 characters."),
            "background" to Schema.boolean("Run independently and return a task_id immediately (default false). Requires Allow background delegation in Settings. Background agents use read-only tools."),
            "prompt" to Schema.string(
                "The research question and everything the subagent needs to know; " +
                    "it does NOT share this conversation's context.",
            ),
            "title" to Schema.string(
                "Short label shown on the subagent's progress, e.g. 'Find config sources'.",
            ),
            "agent" to Schema.string("Optional named Code agent profile from harness.json. Its tool restrictions also apply to this task."),
            "model" to Schema.string(
                "Optional model id from this provider's catalog to run the subagent on " +
                    "(defaults to this conversation's model). A model chosen in Sub-Agent settings takes precedence. Unknown ids are rejected " +
                    "with the list of valid ids.",
            ),
        ),
        required = listOf("prompt"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        ToolResult(false, "task is handled by the engine and cannot be executed directly.")
}
