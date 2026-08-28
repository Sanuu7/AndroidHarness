package com.androidharness.app.tools.mcp

import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolFailure
import com.androidharness.app.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Bridges one MCP server tool into the harness Tool surface. The exposed name
 * is the sanitized `mcp__<server>__<tool>`; the ORIGINAL name is what goes on
 * the wire. Always non-read-only, so every call goes through the harness's
 * normal permission gating.
 */
class McpToolAdapter(
    private val serverName: String,
    private val info: McpToolInfo,
    private val connection: McpConnection,
    private val onDead: () -> Unit = {},
) : Tool {

    override val name: String = McpNames.toolName(serverName, info.name)
    override val description: String =
        "[MCP server: $serverName] " + info.description.ifBlank {
            "Tool provided by the '$serverName' MCP server."
        }
    override val parametersSchema: JsonObject = normalizeMcpSchema(info.inputSchema)
    override val isReadOnly: Boolean = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!connection.isAlive) {
            onDead()
            return ToolResult(
                false,
                "MCP server '$serverName' is not connected (it exited or crashed). " +
                    "It will be restarted before the next run; retry then, or tell the user " +
                    "to check the server configuration in Settings → MCP servers.",
            )
        }
        return try {
            val (text, isError) = connection.callTool(info.name, args)
            ToolResult(!isError, text)
        } catch (e: ToolFailure) {
            if (!connection.isAlive) onDead()
            ToolResult(false, e.message ?: "MCP tool '${info.name}' failed")
        } catch (e: Exception) {
            if (!connection.isAlive) onDead()
            ToolResult(false, "MCP tool '${info.name}' failed: ${e.message}")
        }
    }
}

/** Guarantees the schema providers require: an object type at the root. */
internal fun normalizeMcpSchema(raw: JsonObject): JsonObject = when {
    raw.isEmpty() -> buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }
    raw.containsKey("type") -> raw
    else -> buildJsonObject {
        put("type", "object")
        raw.forEach { (k, v) -> put(k, v) }
    }
}
