package com.androidharness.app.tools.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

// ---------------------------------------------------------------------------
// Paste parsing: Claude JSON, single-server JSON, and `claude mcp add` lines
// ---------------------------------------------------------------------------

class McpPasteTest {

    @Test
    fun `wrapper json parses remote and stdio entries`() {
        val text = """
            {"mcpServers": {
              "supabase": {"type": "http", "url": "https://mcp.supabase.com/mcp",
                           "headers": {"Authorization": "Bearer xyz"}},
              "old": {"type": "sse", "url": "https://example.com/sse"},
              "fs": {"command": "npx", "args": ["-y", "server-fs"]}
            }}
        """.trimIndent()
        val configs = McpConfigParser.parsePaste(text)
        assertEquals(3, configs.size)
        val supa = configs.first { it.name == "supabase" }
        assertEquals("http", supa.type)
        assertEquals("https://mcp.supabase.com/mcp", supa.url)
        assertEquals(mapOf("Authorization" to "Bearer xyz"), supa.headers)
        assertTrue(supa.isRemote)
        assertEquals("sse", configs.first { it.name == "old" }.type)
        val fs = configs.first { it.name == "fs" }
        assertEquals("stdio", fs.type)
        assertEquals(listOf("-y", "server-fs"), fs.args)
    }

    @Test
    fun `a bare single-server object parses with a default name`() {
        val configs = McpConfigParser.parsePaste(
            """{"type": "http", "url": "https://mcp.example.com/mcp"}""",
        )
        assertEquals(1, configs.size)
        assertEquals("server", configs[0].name)
        assertEquals("http", configs[0].type)
    }

    @Test
    fun `the documented claude mcp add http example parses`() {
        val cmd = "claude mcp add --scope project --transport http supabase " +
            "\"https://mcp.supabase.com/mcp?project_ref=abc&features=docs\""
        val configs = McpConfigParser.parsePaste(cmd)
        assertEquals(1, configs.size)
        assertEquals("supabase", configs[0].name)
        assertEquals("http", configs[0].type)
        assertEquals("https://mcp.supabase.com/mcp?project_ref=abc&features=docs", configs[0].url)
        assertTrue(configs[0].headers.isEmpty())
    }

    @Test
    fun `claude mcp add parses stdio commands with flags and dash-dash`() {
        val cmd = "claude mcp add --env ROOT=/a filesystem -- npx -y server-fs '/tmp/x'"
        val configs = McpConfigParser.parsePaste(cmd)
        assertEquals(1, configs.size)
        val c = configs[0]
        assertEquals("filesystem", c.name)
        assertEquals("stdio", c.type)
        assertEquals("npx", c.command)
        assertEquals(listOf("-y", "server-fs", "/tmp/x"), c.args)
        assertEquals(mapOf("ROOT" to "/a"), c.env)
        assertTrue(c.headers.isEmpty())
    }

    @Test
    fun `claude mcp add http with a header parses both`() {
        val cmd = "claude mcp add -t http linear \"https://mcp.linear.app/sse\" " +
            "--header 'Authorization: Bearer tok'"
        val configs = McpConfigParser.parsePaste(cmd)
        assertEquals(1, configs.size)
        val c = configs[0]
        assertEquals("linear", c.name)
        assertEquals("http", c.type)
        assertEquals("https://mcp.linear.app/sse", c.url)
        assertEquals(mapOf("Authorization" to "Bearer tok"), c.headers)
    }

    @Test
    fun `cli tokenizer honors single and double quotes`() {
        assertEquals(
            listOf("a b", "c\"d", "e'f", "g"),
            McpConfigParser.tokenizeCli("'a b' \"c\\\"d\" 'e\\'f' g"),
        )
    }

    @Test
    fun `garbage paste yields nothing`() {
        assertEquals(emptyList<McpServerConfig>(), McpConfigParser.parsePaste(""))
        assertEquals(emptyList<McpServerConfig>(), McpConfigParser.parsePaste("hello world"))
        assertEquals(emptyList<McpServerConfig>(), McpConfigParser.parsePaste("claude mcp list"))
    }
}

// ---------------------------------------------------------------------------
// A dependency-free threaded HTTP server for driving the remote transports
// ---------------------------------------------------------------------------

private class RecordedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

private class StreamOut(private val out: OutputStream) {
    fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))
    fun flush() = out.flush()
}

/** One HTTP request/response exchange over its socket. */
private class Connection(
    val request: RecordedRequest,
    private val out: OutputStream,
) {
    /** One-shot response (Connection: close). */
    fun respond(
        status: Int,
        body: ByteArray = ByteArray(0),
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
    ) {
        val head = buildString {
            append("HTTP/1.1 $status X\r\nConnection: close\r\n")
            contentType?.let { append("Content-Type: $it\r\n") }
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("Content-Length: ${body.size}\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    /**
     * Streaming response (SSE): sends headers without Content-Length; the
     * socket stays open until the handler returns.
     */
    fun openStream(contentType: String, headers: Map<String, String> = emptyMap()): StreamOut {
        val head = buildString {
            append("HTTP/1.1 200 X\r\nConnection: close\r\n")
            append("Content-Type: $contentType\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        out.flush()
        return StreamOut(out)
    }
}

private fun interface ConnectionHandler {
    fun handle(conn: Connection)
}

private class TinyHttpServer {

    @Volatile
    var stopped = false
        private set

    private lateinit var serverSocket: ServerSocket
    private val handlers = mutableMapOf<String, ConnectionHandler>()
    val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    val port: Int get() = serverSocket.localPort

    fun route(path: String, handler: ConnectionHandler) {
        handlers[path] = handler
    }

    fun start() {
        serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!stopped) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                Thread { handle(socket) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        stopped = true
        runCatching { serverSocket.close() }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                val head = readHead(s.getInputStream()) ?: return
                val lines = head.split("\r\n", "\n")
                val requestLine = lines[0].split(" ")
                val method = requestLine[0]
                val path = requestLine.getOrElse(1) { "/" }.substringBefore('?')
                val headers = lines.drop(1).takeWhile { it.isNotBlank() }.associate {
                    val i = it.indexOf(':')
                    it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
                }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = s.getInputStream().read(body, read, len - read)
                    if (n < 0) break
                    read += n
                }
                val conn = Connection(
                    RecordedRequest(method, path, headers, String(body, Charsets.UTF_8)),
                    s.getOutputStream(),
                )
                requests.add(conn.request)
                handlers[path]?.handle(conn)
            }
        } catch (_: Exception) {
            // Client disconnect or server stop, expected.
        }
    }

    private fun readHead(input: InputStream): String? {
        val buf = StringBuilder()
        while (buf.length < 64 * 1024) {
            val c = input.read()
            if (c < 0) return if (buf.isEmpty()) null else buf.toString()
            buf.append(c.toChar())
            if (buf.endsWith("\r\n\r\n") || buf.endsWith("\n\n")) break
        }
        return buf.toString()
    }
}

/** Canned JSON-RPC answers shared by the fake remote servers. */
private fun cannedResponse(id: Int?, method: String): String? {
    val result = when (method) {
        "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"t","version":"1"}}"""
        "tools/list" -> """{"tools":[{"name":"echo","description":"E","inputSchema":{"type":"object"}}]}"""
        "tools/call" -> """{"content":[{"type":"text","text":"pong"}]}"""
        else -> return null
    }
    val idPart = if (id != null) "\"id\":$id," else ""
    return """{"jsonrpc":"2.0",$idPart"result":$result}"""
}

private fun parseRpc(line: String): Pair<Int?, String> = Pair(
    Regex("\"id\":(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull(),
    Regex("\"method\":\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: "",
)

// ---------------------------------------------------------------------------
// Streamable HTTP transport
// ---------------------------------------------------------------------------

class McpStreamableHttpTest {

    @Test
    fun `handshake discovery and call work over streamable http with sessions`() = runBlocking {
        val server = TinyHttpServer()
        val sessionIdsSeen = Collections.synchronizedList(mutableListOf<String?>())
        server.route("/mcp") { conn ->
            val (id, method) = parseRpc(conn.request.body)
            sessionIdsSeen.add(conn.request.headers["mcp-session-id"])
            val payload = cannedResponse(id, method)
            when {
                method.isEmpty() -> conn.respond(202)
                payload == null -> conn.respond(202) // notifications expect no body back
                method == "tools/call" -> {
                    // The server answers this one with an SSE stream.
                    val st = conn.openStream(
                        "text/event-stream",
                        mapOf("Mcp-Session-Id" to "sess-1"),
                    )
                    st.write("event: message\ndata: $payload\n\n")
                    st.flush()
                }
                else -> conn.respond(
                    200, payload!!.toByteArray(), "application/json",
                    if (method == "initialize") mapOf("Mcp-Session-Id" to "sess-1") else emptyMap(),
                )
            }
        }
        server.start()
        try {
            val conn = McpConnection(
                serverName = "remote",
                config = McpServerConfig(name = "remote", type = "http", url = "http://127.0.0.1:${server.port}/mcp"),
            )
            try {
                conn.connect(java.io.File("/tmp"))
                assertEquals(listOf("echo"), conn.tools.map { it.name })
                val (text, isError) = conn.callTool("echo", buildJsonObject { })
                assertEquals("pong", text)
                assertTrue(!isError)
                // Session management: initialize had none, everything after carried sess-1.
                assertEquals(null, sessionIdsSeen[0])
                assertTrue(sessionIdsSeen.drop(1).all { it == "sess-1" })
            } finally {
                conn.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a 401 challenge surfaces as McpAuthRequiredException with the metadata url`() = runBlocking {
        val server = TinyHttpServer()
        server.route("/mcp") { conn ->
            conn.respond(
                401, "{\"message\":\"Unauthorized\"}".toByteArray(), "application/json",
                mapOf(
                    "WWW-Authenticate" to "Bearer error=\"invalid_request\", " +
                        "resource_metadata=\"https://meta.example/.well-known/oauth-protected-resource\"",
                ),
            )
        }
        server.start()
        try {
            val conn = McpConnection(
                serverName = "locked",
                config = McpServerConfig(
                    name = "locked", type = "http",
                    url = "http://127.0.0.1:${server.port}/mcp",
                ),
            )
            try {
                conn.connect(java.io.File("/tmp"))
                error("expected connect to fail")
            } catch (expected: McpAuthRequiredException) {
                assertEquals(
                    "https://meta.example/.well-known/oauth-protected-resource",
                    expected.resourceMetadataUrl,
                )
            } finally {
                conn.close()
            }
        } finally {
            server.stop()
        }
    }
}

// ---------------------------------------------------------------------------
// Legacy HTTP+SSE transport
// ---------------------------------------------------------------------------

class McpSseLegacyTest {

    @Test
    fun `legacy sse transport handshakes through the endpoint event`() = runBlocking {
        val server = TinyHttpServer()
        val sseStream = AtomicReference<StreamOut?>(null)
        server.route("/sse") { conn ->
            val st = conn.openStream("text/event-stream")
            sseStream.set(st)
            st.write("event: endpoint\ndata: /message\n\n")
            st.flush()
            // Hold the stream open; tool responses are pushed by /message.
            while (!server.stopped) Thread.sleep(20)
        }
        server.route("/message") { conn ->
            val (id, method) = parseRpc(conn.request.body)
            val payload = cannedResponse(id, method)
            if (payload != null) {
                sseStream.get()?.let {
                    synchronized(it) {
                        it.write("data: $payload\n\n")
                        it.flush()
                    }
                }
            }
            conn.respond(202)
        }
        server.start()
        try {
            val conn = McpConnection(
                serverName = "legacy",
                config = McpServerConfig(
                    name = "legacy", type = "sse",
                    url = "http://127.0.0.1:${server.port}/sse",
                ),
            )
            try {
                conn.connect(java.io.File("/tmp"))
                assertEquals(listOf("echo"), conn.tools.map { it.name })
                val (text, isError) = conn.callTool("echo", buildJsonObject { })
                assertEquals("pong", text)
                assertTrue(!isError)
            } finally {
                conn.close()
            }
        } finally {
            server.stop()
        }
    }
}

// ---------------------------------------------------------------------------
// OAuth: PKCE vectors, discovery chain, DCR, exchange
// ---------------------------------------------------------------------------

class McpOAuthTest {

    @Test
    fun `s256 code challenge matches the RFC 7636 appendix B vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            McpOAuth.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `authorization url carries every required parameter`() {
        val ctx = McpOAuthContext(
            resource = "https://mcp.example.com/mcp",
            authorizationEndpoint = "https://as.example.com/authorize",
            tokenEndpoint = "https://as.example.com/token",
            clientId = "cid",
            scopes = "read write",
        )
        val url = McpOAuth.authorizationUrl(ctx, "state123", "challenge456")
        assertTrue(url.startsWith("https://as.example.com/authorize?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=cid"))
        assertTrue(url.contains("state=state123"))
        assertTrue(url.contains("code_challenge=challenge456"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("resource=https%3A%2F%2Fmcp.example.com%2Fmcp"))
        assertTrue(url.contains("scope=read+write"))
        assertTrue(url.contains("redirect_uri=androidharness%3A%2F%2Fmcp%2Foauth"))
    }

    @Test
    fun `discovery follows PRM then AS metadata and registers the client`() = runBlocking {
        val server = TinyHttpServer()
        fun json(body: String): ConnectionHandler = ConnectionHandler { conn ->
            conn.respond(200, body.toByteArray(), "application/json")
        }
        server.route("/.well-known/oauth-protected-resource") { conn ->
            conn.respond(
                200,
                ("{\"resource\":\"http://127.0.0.1:${server.port}/mcp\"," +
                    "\"authorization_servers\":[\"http://127.0.0.1:${server.port}\"]," +
                    "\"scopes_supported\":[\"read\",\"write\"]}").toByteArray(),
                "application/json",
            )
        }
        server.route("/.well-known/oauth-authorization-server") { conn ->
            conn.respond(
                200,
                ("{\"issuer\":\"http://127.0.0.1:${server.port}\"," +
                    "\"authorization_endpoint\":\"http://127.0.0.1:${server.port}/authorize\"," +
                    "\"token_endpoint\":\"http://127.0.0.1:${server.port}/token\"," +
                    "\"registration_endpoint\":\"http://127.0.0.1:${server.port}/register\"}").toByteArray(),
                "application/json",
            )
        }
        server.route("/register", json("{\"client_id\":\"cid-1\",\"client_secret\":\"sec-1\"}"))
        server.route("/token") { conn ->
            val form = conn.request.body
            val grant = Regex("grant_type=([^&]+)").find(form)?.groupValues?.get(1)
            val body = when (grant) {
                "authorization_code" ->
                    "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600}"
                else -> "{\"access_token\":\"at-2\",\"expires_in\":60}"
            }
            conn.respond(200, body.toByteArray(), "application/json")
        }
        server.start()
        try {
            val client = OkHttpClient()
            val base = "http://127.0.0.1:${server.port}"
            val ctx = McpOAuth.discover(client, null, "$base/mcp")
            assertTrue(ctx != null)
            assertEquals("$base/mcp", ctx!!.resource)
            assertEquals("$base/authorize", ctx.authorizationEndpoint)
            assertEquals("$base/token", ctx.tokenEndpoint)
            assertEquals("read write", ctx.scopes)
            val registered = McpOAuth.registerClient(client, ctx)
            assertEquals("cid-1", registered.clientId)
            assertEquals("sec-1", registered.clientSecret)
            val url = McpOAuth.authorizationUrl(registered, "s1", "c1")
            assertTrue(url.contains("client_id=cid-1"))
            val tokens = McpOAuth.exchangeCode(client, registered, "abc", "verifier")
            assertEquals("at-1", tokens.accessToken)
            assertEquals("rt-1", tokens.refreshToken)
            assertTrue(tokens.expiresAtMs != null)
            val refreshed = McpOAuth.refreshTokens(client, registered, tokens.refreshToken!!)
            assertEquals("at-2", refreshed.accessToken)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `discovery returns null when the server offers no oauth`() = runBlocking {
        val server = TinyHttpServer()
        server.start()
        try {
            val client = OkHttpClient()
            assertNull(McpOAuth.discover(client, null, "http://127.0.0.1:${server.port}/mcp"))
        } finally {
            server.stop()
        }
    }
}
