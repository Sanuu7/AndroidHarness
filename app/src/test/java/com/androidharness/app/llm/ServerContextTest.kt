package com.androidharness.app.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerContextTest {

    @Test fun `props sits at the server root`() {
        assertEquals("http://127.0.0.1:8080/props", ServerContext.propsUrl("http://127.0.0.1:8080/v1"))
        assertEquals("http://127.0.0.1:8080/props", ServerContext.propsUrl("http://127.0.0.1:8080/v1/"))
        assertEquals("http://10.0.2.2:8080/props", ServerContext.propsUrl("http://10.0.2.2:8080"))
    }

    @Test fun `slot context wins over the server total`() {
        assertEquals(8_192L, ServerContext.parse("""{"n_ctx":16384,"default_generation_settings":{"n_ctx":8192}}"""))
        assertEquals(16_384L, ServerContext.parse("""{"n_ctx":16384}"""))
        assertNull(ServerContext.parse("""{"default_generation_settings":{"n_ctx":0}}"""))
        assertNull(ServerContext.parse("not json"))
        assertNull(ServerContext.parse("""{"error":"Not Found"}"""))
    }

    @Test fun `cloud and on-device providers are never probed`() = runBlocking {
        val cloud = ProviderConfig("or", "OpenRouter", ProviderType.OPENAI_COMPAT, "https://openrouter.ai/api/v1", "m")
        assertNull(ServerContext.tokens(cloud, "key"))
        val anthropicLocal = cloud.copy(type = ProviderType.ANTHROPIC, baseUrl = "http://127.0.0.1:8080")
        assertNull(ServerContext.tokens(anthropicLocal, ""))
    }

    @Test fun `a missed probe keeps the last reported window and publishes it`() = runBlocking {
        val server = propsServer("""{"n_ctx":262144,"default_generation_settings":{"n_ctx":131072}}""")
        val config = ProviderConfig("llama", "llama.cpp", ProviderType.OPENAI_COMPAT,
            "http://127.0.0.1:${server.localPort}/v1", "m")
        try {
            ServerContext.expireProbes()
            assertEquals(131_072L, ServerContext.tokens(config, ""))
            assertEquals(131_072L, ServerContext.known.value[ServerContext.keyFor(config)])
        } finally {
            server.close()
        }
        ServerContext.expireProbes()
        assertEquals(131_072L, ServerContext.tokens(config, ""))
    }

    /**
     * A stand-in for a server's /props, answering every request with [body].
     * Plain sockets, because the unit-test classpath has no com.sun.net.httpserver.
     */
    private fun propsServer(body: String): java.net.ServerSocket {
        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
        return server
    }

    @Test fun `only self-hosted compatible servers have a key`() {
        val local = ProviderConfig("llama", "llama.cpp", ProviderType.OPENAI_COMPAT, "http://192.168.1.5:8080/v1", "m")
        assertEquals("http://192.168.1.5:8080/props", ServerContext.keyFor(local))
        assertNull(ServerContext.keyFor(local.copy(baseUrl = "https://openrouter.ai/api/v1")))
        assertNull(ServerContext.keyFor(local.copy(type = ProviderType.ANTHROPIC)))
    }

    @Test fun `llama cpp context overflow reads as a fix`() {
        val body = """{"error":{"code":400,"message":"request (21254 tokens) exceeds the available context size (16384 tokens), try increasing it","type":"exceed_context_size_error","n_prompt_tokens":21254,"n_ctx":16384}}"""
        assertEquals(
            "The model server can hold 16,384 tokens, but this request needs 21,254. " +
                "Restart the server with a larger context (for llama.cpp, -c 32768 or more), then send the message again.",
            ServerContext.overflowMessage(body),
        )
        assertEquals("HTTP 400: " + ServerContext.overflowMessage(body), ApiException(400, body).message)
        assertNull(ServerContext.overflowMessage("""{"error":{"type":"invalid_request_error","message":"bad"}}"""))
        assertEquals("HTTP 400: plain", ApiException(400, "plain").message)
    }
}
