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
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class ConnectionState { CONNECTING, READY, DEAD }

/**
 * JSON-RPC 2.0 client for one MCP server over the stdio transport: the
 * process's stdin/stdout carry one JSON message per line, stderr goes to a
 * log file. Implements the minimal surface the harness needs:
 * initialize → tools/list → tools/call. Server-initiated requests and
 * notifications are ignored.
 *
 * [processFactory] is injectable so tests can drive the protocol against an
 * in-process double instead of a real subprocess.
 */
class McpConnection(
    val serverName: String,
    private val config: McpServerConfig,
    private val processFactory: (File) -> Process,
    private val handshakeTimeoutMs: Long = 15_000,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val writeMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ConnectionState.CONNECTING)
    val stateFlow: MutableStateFlow<ConnectionState> = _state
    val state: ConnectionState get() = _state.value

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    var tools: List<McpToolInfo> = emptyList()
        private set

    val isAlive: Boolean
        get() = _state.value == ConnectionState.READY && process?.isAlive == true

    /** Spawns the server, completes the initialize handshake and discovers tools. */
    suspend fun connect(cwd: File) {
        val proc = try {
            processFactory(cwd)
        } catch (e: Exception) {
            throw ToolFailure(
                "Could not start MCP server '$serverName': ${e.message}. " +
                    "Check the command and that the Linux environment is installed.",
            )
        }
        process = proc
        writer = proc.outputStream.bufferedWriter()
        scope.launch { readLoop(proc) }
        try {
            rpc(
                "initialize",
                buildJsonObject {
                    put("protocolVersion", "2024-11-05")
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
        val proc = process
        process = null
        runCatching { proc?.destroy() }
        scope.cancel()
    }

    // --- protocol internals ---------------------------------------------------

    private suspend fun rpc(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
        val w = writer ?: throw ToolFailure("MCP server '$serverName' is not running")
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
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    w.write(line)
                    w.newLine()
                    w.flush()
                }
            }
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
        val w = writer ?: return
        val line = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", buildJsonObject {})
        }.toString()
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                w.write(line)
                w.newLine()
                w.flush()
            }
        }
    }

    /** Runs on the IO scope until the process's stdout closes. */
    private suspend fun readLoop(proc: Process) {
        try {
            proc.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) handleLine(line)
                }
            }
        } catch (_: Exception) {
            // Destroyed or IO error: fall through to the dead-path below.
        }
        _state.value = ConnectionState.DEAD
        val dead = ToolFailure("MCP server '$serverName' exited unexpectedly")
        pending.values.forEach { it.completeExceptionally(dead) }
        pending.clear()
    }

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
