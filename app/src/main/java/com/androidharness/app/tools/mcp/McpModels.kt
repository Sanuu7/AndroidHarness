package com.androidharness.app.tools.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One configured MCP (Model Context Protocol) server, stdio or remote:
 * - stdio: a local executable launched as an app-tier child process.
 * - http: the Streamable HTTP transport (spec 2025-03-26+), one POST endpoint.
 * - sse: the deprecated 2024-11-05 HTTP+SSE transport, still served by many
 *   remote servers.
 */
@Serializable
data class McpServerConfig(
    /** Unique, user-chosen identifier; tool names derive from it. */
    val name: String,
    /** Executable launched by the app-tier shell (resolved via PATH). */
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    /** stdio | http | sse. */
    val type: String = "stdio",
    /** Remote endpoint for http/sse. */
    val url: String? = null,
    /** Static request headers for http/sse (e.g. manually supplied API keys). */
    val headers: Map<String, String> = emptyMap(),
) {
    val isRemote: Boolean get() = type == "http" || type == "sse"
}

/** A tool advertised by a connected MCP server. */
data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/** Connection state reported in Settings. */
data class McpServerStatus(
    val state: String, // connecting | connected | failed | auth
    val toolCount: Int = 0,
    val error: String? = null,
    /** True when the server answered with a 401/OAuth challenge. */
    val needsAuth: Boolean = false,
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
 * `{"mcpServers": {"name": {"command": "..."}}} for stdio servers and
 * `{"name": {"type": "http", "url": "...", "headers": {...}}}` for remote
 * ones, the format Claude Desktop / Claude Code / Cursor use, so their
 * server definitions work as-is. Also accepts a bare single-server object
 * or an array of them.
 */
object McpConfigParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Workspace-file entry point: strict JSON, no command-line fallback. */
    fun parse(text: String): List<McpServerConfig> =
        runCatching { parseJson(text) }.getOrNull().orEmpty()

    /**
     * Entry point for user paste in the add dialog: accepts the wrapper JSON,
     * a single-server JSON object/array, or a `claude mcp add …` command line.
     */
    fun parsePaste(text: String): List<McpServerConfig> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        parseJson(trimmed)?.let { return it }
        parseClaudeCommand(trimmed)?.let { return it }
        return emptyList()
    }

    private fun parseJson(text: String): List<McpServerConfig>? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return null
        root["mcpServers"]?.jsonObject?.let { servers ->
            return servers.mapNotNull { (name, el) -> entry(name, el) }
        }
        // A single server object (with or without a "name" field) or an array.
        entry(root["name"]?.jsonPrimitive?.contentOrNull ?: "server", root)?.let { return listOf(it) }
        val arr = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return null
        return arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            entry(o["name"]?.jsonPrimitive?.contentOrNull ?: "server", o)
        }
    }

    private fun entry(name: String, el: kotlinx.serialization.json.JsonElement): McpServerConfig? {
        val o = runCatching { el.jsonObject }.getOrNull() ?: return null
        val enabled = o["disabled"]?.jsonPrimitive?.booleanOrNull?.not() ?: true
        val command = o["command"]?.jsonPrimitive?.contentOrNull
        val url = o["url"]?.jsonPrimitive?.contentOrNull
        val type = o["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?.takeIf { it == "http" || it == "sse" || it == "stdio" }
        return when {
            command != null -> McpServerConfig(
                name = name,
                command = command,
                args = o["args"]?.jsonArray
                    ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                    ?: emptyList(),
                env = stringMap(o["env"]),
                enabled = enabled,
                type = type ?: "stdio",
            )
            url != null -> McpServerConfig(
                name = name,
                enabled = enabled,
                // Unqualified remote entries are Streamable HTTP; the very
                // common legacy SSE servers are declared with "type": "sse".
                type = type ?: "http",
                url = url,
                headers = stringMap(o["headers"]),
            )
            else -> null
        }
    }

    private fun stringMap(el: kotlinx.serialization.json.JsonElement?): Map<String, String> =
        runCatching { el?.jsonObject }
            .getOrNull()
            ?.mapNotNull { (k, v) -> runCatching { k to v.jsonPrimitive.contentOrNull!! }.getOrNull() }
            ?.toMap()
            ?: emptyMap()

    /**
     * Parses `claude mcp add [--scope project] [--transport http|sse|stdio]
     * [--env K=V] [--header "K: V"] [--] <name> <urlOrCommand> [args…]`
     * so a command copied from Claude Code's docs works as paste input.
     */
    fun parseClaudeCommand(text: String): List<McpServerConfig>? {
        val tokens = tokenizeCli(text.trim())
        if (tokens.size < 3 || tokens[0] != "claude" || tokens[1] != "mcp" || tokens[2] != "add") {
            return null
        }
        var transport: String? = null
        val env = mutableMapOf<String, String>()
        val headers = mutableMapOf<String, String>()
        val positional = mutableListOf<String>()
        // Flags whose next token is their value; any other -flag is ignored.
        val valueFlags = setOf("--scope", "-s", "--transport", "-t", "--env", "-e", "--header")
        var i = 3
        var afterName = false
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "--" -> { positional += tokens.drop(i + 1); i = tokens.size }
                t in valueFlags -> {
                    val value = tokens.getOrNull(i + 1) ?: ""
                    when (t) {
                        "--transport", "-t" -> transport = value.lowercase()
                        "--env", "-e" -> value.split('=', limit = 2).takeIf { it.size == 2 }
                            ?.let { (k, v) -> env[k.trim()] = v }
                        "--header" -> value.split(':', limit = 2).takeIf { it.size == 2 }
                            ?.let { (k, v) -> headers[k.trim()] = v.trim() }
                    }
                    i += 2
                }
                t.startsWith("-") && t.length > 1 -> i += 1
                else -> {
                    positional += t
                    afterName = true
                    i += 1
                }
            }
        }
        if (!afterName || positional.size < 2) return null
        val name = positional[0]
        val target = positional[1]
        val effectiveTransport = (transport ?: if (target.startsWith("http")) "http" else "stdio")
        return listOf(
            when (effectiveTransport) {
                "http", "sse" -> McpServerConfig(
                    name = name, type = effectiveTransport, url = target, headers = headers,
                )
                else -> McpServerConfig(
                    name = name, command = target, args = positional.drop(2), env = env,
                )
            },
        )
    }

    /** Splits a command line into tokens, honoring ' and " quoting with backslash escapes. */
    internal fun tokenizeCli(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var inToken = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null && c == '\\' && i + 1 < text.length && text[i + 1] == quote -> {
                    sb.append(quote)
                    inToken = true
                    i += 2
                }
                quote != null -> {
                    if (c == quote) quote = null else sb.append(c)
                    i += 1
                }
                c == '\\' && i + 1 < text.length && (text[i + 1] == '\'' || text[i + 1] == '"') -> {
                    quote = text[i + 1]
                    inToken = true
                    i += 2
                }
                c == '\'' || c == '"' -> { quote = c; inToken = true; i += 1 }
                c.isWhitespace() -> {
                    if (inToken) { out += sb.toString(); sb.clear(); inToken = false }
                    i += 1
                }
                else -> { sb.append(c); inToken = true; i += 1 }
            }
        }
        if (inToken) out += sb.toString()
        return out
    }
}
