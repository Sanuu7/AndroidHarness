package com.androidharness.app.tools

import com.androidharness.app.config.ConfigLanguageServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The language servers plugins ship (Claude Code's lspServers), asked over the Language Server Protocol.
 * A server starts on first use in the Linux environment and stays running for the project until it has been idle a while.
 */
class LanguageServerTool(
    private val servers: List<ConfigLanguageServer>,
    private val shell: (String) -> ProcessBuilder,
) : Tool {
    override val name = NAME
    override val description = "Ask the project's language servers about code: diagnostics (errors and warnings in a file), " +
        "definition, references and hover at a position, or symbols (the outline of a file). Line and column are 1-based. " +
        "Servers: " + servers.joinToString { "${it.name} (${it.extensions.keys.joinToString(" ")})" }
    override val isReadOnly = true
    override val parametersSchema = Schema.obj(mapOf(
        "operation" to Schema.string("diagnostics, definition, references, hover or symbols."),
        "path" to Schema.string("File path relative to the project."),
        "line" to Schema.integer("1-based line, for definition, references and hover."),
        "column" to Schema.integer("1-based column, for definition, references and hover."),
    ), required = listOf("operation", "path"))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val operation = args["operation"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: throw ToolFailure("operation is required.")
        val path = args["path"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: throw ToolFailure("path is required.")
        val root = ctx.workspace.shellRoot?.canonicalFile ?: throw ToolFailure("Language servers need a project folder on this device.")
        val file = (if (path.startsWith("/")) File(path) else File(root, path)).canonicalFile
        if (!ctx.sandboxOff && !file.path.startsWith(root.path + File.separator) && file != root) throw ToolFailure("The file is outside the project.")
        if (!file.isFile) throw ToolFailure("File not found: $path")
        val extension = "." + file.extension.lowercase()
        val server = servers.firstOrNull { it.extensions.keys.any { key -> key.lowercase() == extension || key.lowercase() == file.name.lowercase() } }
            ?: throw ToolFailure("No language server handles $extension files. Servers: ${servers.joinToString { it.name }}")
        val language = server.extensions.entries.firstOrNull { it.key.lowercase() == extension }?.value ?: file.extension
        val line = (args["line"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1) - 1
        val column = (args["column"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1) - 1
        return withContext(Dispatchers.IO) {
            val session = LanguageServerSessions.get(server, root, shell)
            val uri = file.toURI().toString().replace("file:/", "file:///").replace("file:////", "file:///")
            val version = session.open(uri, language, file.readText())
            val position = buildJsonObject { put("line", line); put("character", column) }
            val document = buildJsonObject { put("uri", uri) }
            val output = when (operation) {
                "diagnostics" -> session.diagnostics(uri, version).let { formatDiagnostics(it, root) }
                "definition" -> formatLocations(session.request("textDocument/definition", buildJsonObject { put("textDocument", document); put("position", position) }), root)
                "references" -> formatLocations(session.request("textDocument/references", buildJsonObject {
                    put("textDocument", document); put("position", position); put("context", buildJsonObject { put("includeDeclaration", true) })
                }), root)
                "hover" -> formatHover(session.request("textDocument/hover", buildJsonObject { put("textDocument", document); put("position", position) }))
                "symbols" -> formatSymbols(session.request("textDocument/documentSymbol", buildJsonObject { put("textDocument", document) }), 0)
                else -> throw ToolFailure("Unknown operation '$operation'. Use diagnostics, definition, references, hover or symbols.")
            }
            ToolResult(true, output.ifBlank { "Nothing found." })
        }
    }

    private fun relative(uri: String, root: File): String {
        val path = runCatching { File(java.net.URI(uri)).path }.getOrDefault(uri)
        return path.removePrefix(root.path + "/")
    }
    private fun JsonElement?.position(): String {
        val start = (this as? JsonObject)?.get("start") as? JsonObject ?: return ""
        return "${(start["line"]?.jsonPrimitive?.intOrNull ?: 0) + 1}:${(start["character"]?.jsonPrimitive?.intOrNull ?: 0) + 1}"
    }
    private fun formatDiagnostics(items: JsonArray, root: File): String = items.joinToString("\n") { item ->
        val obj = item.jsonObject
        val severity = when (obj["severity"]?.jsonPrimitive?.intOrNull) { 1 -> "error"; 2 -> "warning"; 3 -> "info"; 4 -> "hint"; else -> "issue" }
        val source = obj["source"]?.jsonPrimitive?.contentOrNull?.let { " [$it]" }.orEmpty()
        "${obj["range"].position()} $severity$source: ${obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
    }.ifBlank { "No problems reported." }
    private fun formatLocations(result: JsonElement?, root: File): String {
        val list = when (result) { is JsonArray -> result.toList(); is JsonObject -> listOf(result); else -> emptyList() }
        return list.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val uri = (obj["uri"] ?: obj["targetUri"])?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            "${relative(uri, root)}:${(obj["range"] ?: obj["targetSelectionRange"]).position()}"
        }.distinct().joinToString("\n")
    }
    private fun formatHover(result: JsonElement?): String {
        val contents = (result as? JsonObject)?.get("contents") ?: return ""
        fun text(element: JsonElement): String = when (element) {
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            is JsonObject -> element["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
            is JsonArray -> element.joinToString("\n\n") { text(it) }
        }
        return text(contents).trim()
    }
    private fun formatSymbols(result: JsonElement?, depth: Int): String = (result as? JsonArray).orEmpty().joinToString("\n") { element ->
        val obj = element.jsonObject
        val kind = SYMBOL_KINDS.getOrNull((obj["kind"]?.jsonPrimitive?.intOrNull ?: 0) - 1) ?: "symbol"
        val range = obj["selectionRange"] ?: obj["range"] ?: (obj["location"] as? JsonObject)?.get("range")
        val line = "  ".repeat(depth) + "$kind ${obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()} ${range.position()}"
        val children = formatSymbols(obj["children"], depth + 1)
        if (children.isBlank()) line else "$line\n$children"
    }

    companion object {
        const val NAME = "lsp"
        private val SYMBOL_KINDS = listOf("file", "module", "namespace", "package", "class", "method", "property", "field", "constructor", "enum",
            "interface", "function", "variable", "constant", "string", "number", "boolean", "array", "object", "key", "null", "enum-member",
            "struct", "event", "operator", "type-parameter")
    }
}

/** Running language servers, one per server and project folder. */
object LanguageServerSessions {
    private val sessions = ConcurrentHashMap<String, LanguageServerSession>()
    private const val IDLE_MS = 10 * 60 * 1000L

    fun get(server: ConfigLanguageServer, root: File, shell: (String) -> ProcessBuilder): LanguageServerSession {
        sessions.entries.removeIf { (_, session) -> (!session.alive || System.currentTimeMillis() - session.lastUsed > IDLE_MS).also { if (it) session.close() } }
        val key = "${server.name}|${server.command}|${server.args}|${root.path}"
        return sessions.compute(key) { _, current -> current?.takeIf { it.alive } ?: LanguageServerSession(server, root, shell) }!!
    }
}

class LanguageServerSession(private val server: ConfigLanguageServer, private val root: File, shell: (String) -> ProcessBuilder) {
    private val process: Process
    private val input: InputStream
    private val output: OutputStream
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()
    private val diagnostics = ConcurrentHashMap<String, Pair<Int?, JsonArray>>()
    private val versions = ConcurrentHashMap<String, Int>()
    private val texts = ConcurrentHashMap<String, String>()
    private val initialized = CompletableDeferred<Unit>()
    @Volatile var lastUsed = System.currentTimeMillis()
    val alive get() = process.isAlive

    init {
        fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"
        val command = (listOf(server.command) + server.args).joinToString(" ") { quote(it) }
        val builder = shell("exec $command").directory(root)
        builder.environment().putAll(server.env)
        process = builder.start()
        // Servers log to stderr; it is read and dropped so a full pipe never stalls them.
        Thread({ runCatching { process.errorStream.use { err -> val buffer = ByteArray(8192); while (err.read(buffer) >= 0) Unit } } }, "lsp-${server.name}-err")
            .apply { isDaemon = true; start() }
        input = process.inputStream
        output = process.outputStream
        Thread({ readLoop() }, "lsp-${server.name}").apply { isDaemon = true; start() }
    }

    private fun send(message: JsonObject) {
        val body = message.toString().toByteArray()
        synchronized(output) {
            output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray())
            output.write(body)
            output.flush()
        }
    }
    private fun readLoop() {
        try {
            while (true) {
                var length = -1
                while (true) {
                    val header = readLine() ?: return
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
                }
                if (length < 0) continue
                val body = ByteArray(length)
                var read = 0
                while (read < length) { val n = input.read(body, read, length - read); if (n < 0) return; read += n }
                handle(runCatching { Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject }.getOrNull() ?: continue)
            }
        } catch (_: Exception) {
        } finally {
            pending.values.forEach { it.completeExceptionally(IllegalStateException("Language server ${server.name} stopped.")) }
        }
    }
    private fun readLine(): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
        }
    }
    private fun handle(message: JsonObject) {
        val id = message["id"]
        val method = message["method"]?.jsonPrimitive?.contentOrNull
        when {
            method == null && id != null -> {
                val deferred = pending.remove(id.jsonPrimitive.intOrNull ?: return) ?: return
                val error = message["error"] as? JsonObject
                if (error != null) deferred.completeExceptionally(IllegalStateException(error["message"]?.jsonPrimitive?.contentOrNull ?: "Language server error."))
                else deferred.complete(message["result"])
            }
            method == "textDocument/publishDiagnostics" -> {
                val params = message["params"] as? JsonObject ?: return
                val uri = params["uri"]?.jsonPrimitive?.contentOrNull ?: return
                diagnostics[normalize(uri)] = params["version"]?.jsonPrimitive?.intOrNull to ((params["diagnostics"] as? JsonArray) ?: JsonArray(emptyList()))
            }
            id != null -> {
                // Requests from the server: answer the ones a client must, with empty results.
                val result: JsonElement = when (method) {
                    "workspace/configuration" -> JsonArray(((message["params"] as? JsonObject)?.get("items") as? JsonArray).orEmpty().map { JsonNull })
                    "workspace/workspaceFolders" -> JsonArray(listOf(folder()))
                    else -> JsonNull
                }
                send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })
            }
        }
    }
    private fun normalize(uri: String) = runCatching { File(java.net.URI(uri)).canonicalPath }.getOrDefault(uri)
    private fun folder() = buildJsonObject { put("uri", root.toURI().toString().replace("file:/", "file:///").replace("file:////", "file:///")); put("name", root.name) }

    suspend fun request(method: String, params: JsonObject, timeoutMs: Long = 60_000): JsonElement? {
        if (method != "initialize") ensureInitialized()
        lastUsed = System.currentTimeMillis()
        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement?>()
        pending[id] = deferred
        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
        return try { withTimeout(timeoutMs) { deferred.await() } }
        catch (e: kotlinx.coroutines.TimeoutCancellationException) { pending.remove(id); throw ToolFailure("Language server ${server.name} did not answer $method in ${timeoutMs / 1000} seconds.") }
    }
    private fun notify(method: String, params: JsonObject) =
        send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) })

    private suspend fun ensureInitialized() {
        if (initialized.isCompleted) return
        synchronized(this) { if (starting) null else { starting = true; Unit } } ?: return initialized.await()
        try {
            request("initialize", buildJsonObject {
                put("processId", JsonNull)
                put("rootUri", folder()["uri"]!!)
                put("rootPath", root.path)
                put("workspaceFolders", JsonArray(listOf(folder())))
                server.initializationOptions?.let { put("initializationOptions", it) }
                put("capabilities", buildJsonObject {
                    put("textDocument", buildJsonObject {
                        put("publishDiagnostics", buildJsonObject { put("versionSupport", true) })
                        put("hover", buildJsonObject { put("contentFormat", buildJsonArray { add("markdown"); add("plaintext") }) })
                        put("documentSymbol", buildJsonObject { put("hierarchicalDocumentSymbolSupport", true) })
                        put("definition", buildJsonObject { put("linkSupport", true) })
                    })
                    put("workspace", buildJsonObject { put("workspaceFolders", true); put("configuration", true) })
                })
            }, timeoutMs = 120_000)
            notify("initialized", JsonObject(emptyMap()))
            initialized.complete(Unit)
        } catch (e: Exception) {
            initialized.completeExceptionally(e); close(); throw e
        }
    }
    @Volatile private var starting = false

    /** Opens or refreshes [uri] with the file's current text and returns the document version. */
    suspend fun open(uri: String, language: String, text: String): Int {
        ensureInitialized()
        val key = normalize(uri)
        val previous = versions[key]
        if (previous != null && texts[key] == text) return previous
        val version = (previous ?: 0) + 1
        versions[key] = version; texts[key] = text
        if (previous == null) notify("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri); put("languageId", language); put("version", version); put("text", text) })
        }) else notify("textDocument/didChange", buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri); put("version", version) })
            put("contentChanges", buildJsonArray { add(buildJsonObject { put("text", text) }) })
        })
        return version
    }

    /** Waits for the server to publish diagnostics for this version of the file, then a moment more for late ones. */
    suspend fun diagnostics(uri: String, version: Int): JsonArray {
        val key = normalize(uri)
        lastUsed = System.currentTimeMillis()
        withTimeoutOrNull(30_000) {
            while (diagnostics[key]?.let { it.first == null || it.first!! >= version } != true) delay(150)
        }
        delay(500)
        return diagnostics[key]?.second ?: JsonArray(emptyList())
    }

    fun close() {
        runCatching { if (initialized.isCompleted && process.isAlive) {
            send(buildJsonObject { put("jsonrpc", "2.0"); put("id", ids.getAndIncrement()); put("method", "shutdown") })
            notify("exit", JsonObject(emptyMap()))
        } }
        runCatching { process.destroy() }
    }
}
