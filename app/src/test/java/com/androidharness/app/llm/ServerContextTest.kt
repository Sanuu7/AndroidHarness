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
