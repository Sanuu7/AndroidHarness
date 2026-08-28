package com.androidharness.app.tools.mcp

import com.androidharness.app.tools.ToolFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class ConnectionState { CONNECTING, READY, DEAD }

/**
 * JSON-RPC 2.0 client for one MCP server over any supported transport:
 * stdio (child process), Streamable HTTP, or the legacy HTTP+SSE transport.
 * Implements the minimal surface the harness needs:
 * initialize → tools/list → tools/call. Server-initiated requests and
 * notifications are ignored.
 *
 * [processFactory] is injectable so tests can drive the stdio protocol
 * against an in-process double instead of a real subprocess.
 */
class McpConnection(
    val serverName: String,
    private val config: McpServerConfig,
    private val processFactory: ((File) -> Process)? = null,
    /** Supplies the OAuth bearer token (or null) for remote transports. */
    private val authHeader: suspend () -> String? = { null },
    private val handshakeTimeoutMs: Long = 15_000,
    private val httpClient: OkHttpClient = mcpHttpClient(),
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val writeMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ConnectionState.CONNECTING)
    val stateFlow: MutableStateFlow<ConnectionState> = _state
    val state: ConnectionState get() = _state.value

    private var transport: McpTransport? = null
    private var stdio: StdioTransport? = null
    var tools: List<McpToolInfo> = emptyList()
        private set

    val isAlive: Boolean
        get() = when {
            _state.value != ConnectionState.READY -> false
            config.isRemote -> true
            else -> stdio?.processAlive() == true
        }

    /** Establishes the transport, completes the initialize handshake and discovers tools. */
    suspend fun connect(cwd: File) {
        val t: McpTransport = when {
            config.type == "http" -> StreamableHttpTransport(
                config.url ?: throw ToolFailure("MCP server '$serverName' has no URL configured"),
                config.headers, authHeader, httpClient,
            )
            config.type == "sse" -> SseLegacyTransport(
                config.url ?: throw ToolFailure("MCP server '$serverName' has no URL configured"),
                config.headers, authHeader, httpClient,
            )
            else -> StdioTransport(
                serverName,
                processFactory
                    ?: throw ToolFailure("MCP server '$serverName' has no command configured"),
            )
        }
        transport = t
        if (t is StdioTransport) stdio = t
        scope.launch {
            try {
                for (line in t.incoming) handleLine(line)
            } catch (_: Exception) {
                // Cancelled.
            }
        }
        try {
            t.start(cwd)
            rpc(
                "initialize",
                buildJsonObject {
                    put(
                        "protocolVersion",
                        if (config.isRemote) McpProtocol.REMOTE_VERSION else "2024-11-05",
                    )
                    putJsonObject("capabilities") {}
                    putJsonObject("clientInfo") {
                        put("name", "AndroidHarness")
                        put("version", "0.4-alpha")
                    }
                },
                handshakeTimeoutMs,
            )
            notify("notifications/initialized")
            val listed = rpc("tools/list", buildJsonObject {}, handshakeTimeoutMs)
            tools = parseTools(listed["result"])
            _state.value = ConnectionState.READY
        } catch (e: McpAuthRequiredException) {
            close()
            throw e
        } catch (e: TimeoutCancellationException) {
            close()
            throw ToolFailure(
                "MCP server '$serverName' did not finish the initialize handshake within " +
                    "${handshakeTimeoutMs}ms. Check its stderr log and that the command is a " +
                    "working MCP server.",
            )
        } catch (e: ToolFailure) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw ToolFailure("MCP server '$serverName' failed during startup: ${e.message}")
        }
    }

    /**
     * Calls a tool. Returns the concatenated text content and the isError
     * flag; transport/protocol failures throw ToolFailure.
     */
    suspend fun callTool(
        toolName: String,
        args: JsonObject,
        timeoutMs: Long = 60_000,
    ): Pair<String, Boolean> {
        val resp = try {
            rpc(
                "tools/call",
                buildJsonObject {
                    put("name", toolName)
                    put("arguments", args)
                },
                timeoutMs,
            )
        } catch (e: TimeoutCancellationException) {
            throw ToolFailure(
                "MCP tool '$toolName' on '$serverName' timed out after ${timeoutMs}ms",
            )
        }
        val result = resp["result"]?.jsonObject
            ?: throw ToolFailure("MCP server '$serverName' returned no result for '$toolName'")
        val isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        return contentToText(result["content"]) to isError
    }

    fun close() {
        _state.value = ConnectionState.DEAD
        transport?.close()
        transport = null
        stdio = null
        scope.cancel()
    }

    // --- protocol internals ---------------------------------------------------

    private suspend fun rpc(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
        val t = transport ?: throw ToolFailure("MCP server '$serverName' is not connected")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            val line = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString()
            writeMutex.withLock { t.send(line) }
            val resp = withTimeout(timeoutMs) { deferred.await() }
            resp["error"]?.jsonObject?.let { err ->
                throw ToolFailure(
                    "MCP $method failed: " +
                        (err["message"]?.jsonPrimitive?.contentOrNull ?: err.toString()),
                )
            }
            return resp
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun notify(method: String) {
        val t = transport ?: return
        val line = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", buildJsonObject {})
        }.toString()
        writeMutex.withLock { t.send(line) }
    }

    /** Runs on the connection scope until the transport's incoming channel closes. */
    private fun handleLine(line: String) {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        // Server-initiated requests and notifications carry "method"; we only
        // correlate responses (id + result/error).
        if (obj.containsKey("method")) return
        val id = obj["id"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: return
        pending.remove(id)?.complete(obj)
    }

    private fun parseTools(result: JsonElement?): List<McpToolInfo> {
        val arr = (result as? JsonObject)?.get("tools") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            McpToolInfo(
                name = name,
                description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
                inputSchema = o["inputSchema"] as? JsonObject ?: buildJsonObject {},
            )
        }
    }

    private fun contentToText(content: JsonElement?): String = when {
        content == null -> "(empty response)"
        content is JsonArray -> content.mapNotNull { el ->
            (el as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                ?: if (el is JsonObject) el.toString() else null
        }.joinToString("\n").ifEmpty { content.toString() }
        content is JsonObject -> content["text"]?.jsonPrimitive?.contentOrNull ?: content.toString()
        else -> content.toString()
    }
}
