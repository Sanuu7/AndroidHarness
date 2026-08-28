package com.androidharness.app.tools.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One configured MCP (Model Context Protocol) server. */
@Serializable
data class McpServerConfig(
    /** Unique, user-chosen identifier; tool names derive from it. */
    val name: String,
    /** Executable launched by the app-tier shell (resolved via PATH). */
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
)

/** A tool advertised by a connected MCP server. */
data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/** Connection state reported in Settings. */
data class McpServerStatus(
    val state: String, // connecting | connected | failed
    val toolCount: Int = 0,
    val error: String? = null,
)

object McpNames {

    /** Workspace-level MCP config file, standard "mcpServers" format. */
    const val WORKSPACE_CONFIG = ".harness/mcp.json"

    /**
     * Tool names exposed to the model: mcp__<server>__<tool> with every
     * component lowercased and reduced to [a-z0-9_], matching the convention
     * other harnesses use.
     */
    fun toolName(server: String, tool: String): String =
        "mcp__${sanitizeComponent(server).take(32)}__${sanitizeComponent(tool).take(64)}"

    fun sanitizeComponent(raw: String): String =
        raw.trim().lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .takeIf { it.isNotEmpty() } ?: "x"
}

/**
 * Parses the ecosystem-standard MCP config shape:
 * `{"mcpServers": {"name": {"command": "...", "args": [...], "env": {...}, "disabled": false}}}`
 * so users can reuse Claude Desktop / Claude Code server definitions as-is.
 */
object McpConfigParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<McpServerConfig> = runCatching {
        val servers = json.parseToJsonElement(text).jsonObject["mcpServers"]?.jsonObject
            ?: return emptyList()
        servers.mapNotNull { (name, el) ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val command = o["command"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            McpServerConfig(
                name = name,
                command = command,
                args = o["args"]?.jsonArray
                    ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                    ?: emptyList(),
                env = o["env"]?.jsonObject
                    ?.mapNotNull { (k, v) ->
                        runCatching { v.jsonPrimitive.contentOrNull }.getOrNull()?.let { k to it }
                    }
                    ?.toMap()
                    ?: emptyMap(),
                enabled = o["disabled"]?.jsonPrimitive?.booleanOrNull?.not() ?: true,
            )
        }
    }.getOrDefault(emptyList())
}
