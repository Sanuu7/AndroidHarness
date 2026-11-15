package com.androidharness.app.tools

import com.androidharness.app.agent.ToolManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Meta-tool that lets the model request additional tools on demand.
 * When called, the requested tool (and its family) become active in
 * the next model request.
 */
class RequestToolTool(private val toolManager: ToolManager) : Tool {
    override val name: String = "request_tool"
    override val description: String =
        "Request a tool to be made available. Use this when you need a capability " +
        "not in your current tool list. The tool will be available in the next turn. " +
        "Available families:\n" + ToolManager.familySummary()

    override val parametersSchema: JsonObject = Schema.obj(
        properties = mapOf(
            "name" to Schema.string("The tool name to request (e.g. 'git_log', 'browser_navigate', 'memory_write')"),
            "reason" to Schema.string("Brief reason why you need this tool"),
            "auto_load" to Schema.boolean("Also load other tools from the same family (default true)"),
        ),
        required = listOf("name"),
    )

    override val isReadOnly: Boolean = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = args["name"]?.jsonPrimitive?.content
            ?: return ToolResult(false, "Missing 'name' parameter")
        val reason = args["reason"]?.jsonPrimitive?.content ?: ""
        val autoLoad = args["auto_load"]?.jsonPrimitive?.booleanOrNull ?: true

        // Check if already active
        if (name in toolManager.active) {
            return ToolResult(true, "Tool '$name' is already active.")
        }

        // Check if tool exists
        if (name !in toolManager.available && name !in ToolManager.DEFAULT_ALWAYS_ON) {
            return ToolResult(false, "Unknown tool '$name'. Available: ${toolManager.available.sorted().joinToString(", ")}")
        }

        val loaded = toolManager.activate(name, autoLoad)
        val msg = if (loaded.size == 1) {
            "Loaded: $name"
        } else {
            "Loaded: ${loaded.joinToString(", ")}"
        }
        return ToolResult(true, "$msg. Ready to use in the next turn.")
    }
}
