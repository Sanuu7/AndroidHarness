package com.androidharness.app.browser

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import com.androidharness.app.workspace.WorkspaceFs

/**
 * Minimal loopback HTTP server that serves the active workspace so the agent
 * browser can load local sites like a real one: relative links, form GET
 * submits, css/js/image assets, and back/forward history all behave normally,
 * with no per-request interception hacks.
 *
 * Binds 127.0.0.1 only, daemon threads, GET/HEAD, Connection: close. Path
 * mapping rejects traversal and falls back to the current base document for
 * root requests (form submits land on "/").
 */
class WorkspaceHttpServer(
    private val workspace: () -> WorkspaceFs?,
    private val rootDoc: () -> String?,
) {
    @Volatile
    private var socket: ServerSocket? = null

    val port: Int get() = socket?.takeIf { !it.isClosed }?.localPort ?: 0
    val isRunning: Boolean get() = socket?.takeIf { !it.isClosed } != null

    /** Starts the server if needed, returning the bound port. */
    @Synchronized
    fun ensureStarted(): Int {
        socket?.takeIf { !it.isClosed }?.let { return it.localPort }
        var lastError: BindException? = null
        for (candidate in 8123..8179) {
            try {
                val ss = ServerSocket(candidate, 64, InetAddress.getLoopbackAddress())
                socket = ss
                Thread({ acceptLoop(ss) }, "harness-ws-http").apply {
                    isDaemon = true
                    start()
                }
                return candidate
            } catch (e: BindException) {
                lastError = e
            }
        }
        throw IllegalStateException("No free local port for the workspace HTTP server: ${lastError?.message}")
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        try {
            var acceptFailures = 0
            while (!ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (e: Exception) {
                    if (ss.isClosed) return
                    // Repeated accept failures (fd exhaustion, etc.) mean this
                    // listener is done: tear it down so the next ensureStarted
                    // rebinds instead of handing out a dead port.
                    if (++acceptFailures > 50) {
                        teardown(ss)
                        return
                    }
                    continue
                }
                acceptFailures = 0
                Thread({ runCatching { handle(client) } }, "harness-ws-req").apply {
                    isDaemon = true
                    start()
                }
            }
        } catch (t: Throwable) {
            // A broken listener must never linger looking "started".
            teardown(ss)
        }
    }

    private fun teardown(ss: ServerSocket) {
        runCatching { ss.close() }
        synchronized(this) { if (socket === ss) socket = null }
    }

    private fun handle(client: Socket) {
        client.use { s ->
            s.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]
            if (method != "GET" && method != "HEAD") {
                respond(s, 405, "text/plain", "Method not allowed")
                return
            }
            val rel = mapRequestPath(target, rootDoc())
                ?: run { respond(s, 400, "text/plain", "Bad request"); return }
            val ws = workspace() ?: run { respond(s, 503, "text/plain", "No active workspace"); return }
            val node = runCatching { ws.resolve(rel) }.getOrNull()
            val fileNode = when {
                node == null || !node.exists -> null
                node.isDirectory -> runCatching { ws.resolve("$rel/index.html") }.getOrNull()?.takeIf { it.exists }
                else -> node
            }
            if (fileNode == null) {
                respond(s, 404, "text/plain", "Not found: /$rel")
                return
            }
            val bytes = runCatching { fileNode.openInputStream()?.use { it.readBytes() } }.getOrNull()
            if (bytes == null) {
                respond(s, 500, "text/plain", "Read failed")
                return
            }
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: ${mimeFor(rel)}\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            s.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                if (method == "GET") write(bytes)
                flush()
            }
        }
    }

    private fun respond(s: Socket, code: Int, mime: String, body: String) {
        runCatching {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $code ${codeText(code)}\r\n" +
                "Content-Type: $mime\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            s.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(bytes)
                flush()
            }
        }
    }

    private fun codeText(code: Int) = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Error"
    }

    companion object {
        /**
         * Maps an HTTP request target to a workspace-relative path. Root and
         * query-only requests fall back to [rootDoc] then index.html; traversal
         * ("..") and undecodable paths are rejected with null. Pure for tests.
         */
        fun mapRequestPath(rawTarget: String, rootDoc: String?): String? {
            val path = rawTarget.substringBefore('?').substringBefore('#')
            val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrElse { return null }
            var rel = decoded.removePrefix("/")
            if (rel.isBlank()) {
                rel = rootDoc?.takeIf { it.isNotBlank() } ?: "index.html"
            }
            val segments = mutableListOf<String>()
            for (seg in rel.split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> return null
                    "\\" -> return null
                    else -> segments.add(seg)
                }
            }
            if (segments.isEmpty()) return null
            return segments.joinToString("/")
        }

        fun mimeFor(rel: String): String = when (rel.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "txt", "md" -> "text/plain"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }
    }
}
