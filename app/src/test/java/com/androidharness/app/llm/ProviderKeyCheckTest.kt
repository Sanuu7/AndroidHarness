package com.androidharness.app.llm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ProviderKeyCheckTest {
    private fun body(request: Request): JsonObject =
        Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject

    private fun reply(code: Int, text: String, seen: MutableList<Request>) = OkHttpClient.Builder().addInterceptor { chain ->
        seen += chain.request()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "Unauthorized")
            .body(text.toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun eachProtocolSendsOneTinyNonStreamingMessage() {
        val key = "sk-test-0123456789"
        fun config(type: ProviderType, base: String, model: String = "m1") = ProviderConfig("p", "P", type, base, model)

        val compat = ProviderKeyCheck.buildRequest(config(ProviderType.OPENAI_COMPAT, "https://openrouter.ai/api/v1/"), key)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", compat.url.toString())
        assertEquals("Bearer $key", compat.header("Authorization"))
        assertEquals(ProviderKeyCheck.MAX_OUTPUT_TOKENS, body(compat)["max_tokens"]!!.jsonPrimitive.int)
        assertNull(body(compat)["stream"])
        assertEquals(1, body(compat)["messages"]!!.jsonArray.size)

        val openAi = ProviderKeyCheck.buildRequest(config(ProviderType.OPENAI_COMPAT, "https://api.openai.com/v1", "gpt-5"), key)
        assertNotNull(body(openAi)["max_completion_tokens"])
        assertNull(body(openAi)["max_tokens"])

        val responses = ProviderKeyCheck.buildRequest(config(ProviderType.OPENAI_RESPONSES, "https://api.openai.com/v1"), key)
        assertEquals("https://api.openai.com/v1/responses", responses.url.toString())
        assertEquals(ProviderKeyCheck.MAX_OUTPUT_TOKENS, body(responses)["max_output_tokens"]!!.jsonPrimitive.int)

        val anthropic = ProviderKeyCheck.buildRequest(config(ProviderType.ANTHROPIC, "https://api.anthropic.com"), key)
        assertEquals("https://api.anthropic.com/v1/messages", anthropic.url.toString())
        assertEquals(key, anthropic.header("x-api-key"))
        assertEquals("2023-06-01", anthropic.header("anthropic-version"))

        val gemini = ProviderKeyCheck.buildRequest(config(ProviderType.GEMINI, "https://generativelanguage.googleapis.com/v1beta"), key)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/m1:generateContent", gemini.url.toString())
        assertEquals(key, gemini.header("x-goog-api-key"))
        assertEquals(ProviderKeyCheck.MAX_OUTPUT_TOKENS,
            body(gemini)["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.int)
    }

    @Test fun customHeadersRideAlong() {
        val config = ProviderConfig("p", "P", ProviderType.OPENAI_COMPAT, "https://example.com/v1", "m1",
            headers = listOf(ProviderHeader("X-Team", "core"), ProviderHeader("X-Off", "1", enabled = false)))
        val request = ProviderKeyCheck.buildRequest(config, "sk-test")
        assertEquals("core", request.header("X-Team"))
        assertNull(request.header("X-Off"))
    }

    @Test fun successAndRejectionAreReportedWithoutEchoingTheKey() = runBlocking {
        val key = "sk-rejected-0123456789"
        val config = ProviderConfig("p", "P", ProviderType.OPENAI_COMPAT, "https://example.com/v1", "m1")
        val seen = mutableListOf<Request>()

        val ok = ProviderKeyCheck.check(config, key, httpClient = reply(200, """{"choices":[]}""", seen))
        assertEquals("m1", (ok as ProviderKeyCheck.Result.Ok).model)

        val failed = ProviderKeyCheck.check(config, key,
            httpClient = reply(401, """{"error":{"message":"Incorrect API key provided: $key"}}""", seen))
        failed as ProviderKeyCheck.Result.Failed
        assertEquals(401, failed.status)
        assertEquals("HTTP 401 Unauthorized: Incorrect API key provided: [key]", failed.message)
        assertEquals(2, seen.size)
    }

    @Test fun withoutAModelTheKeyIsCheckedByListingModels() = runBlocking {
        val config = ProviderConfig("p", "P", ProviderType.OPENAI_COMPAT, "https://example.com/v1", "")
        val seen = mutableListOf<Request>()
        val result = ProviderKeyCheck.check(config, "sk-test", httpClient = reply(200, """{"data":[{"id":"a"}]}""", seen))
        assertNull((result as ProviderKeyCheck.Result.Ok).model)
        assertEquals("https://example.com/v1/models", seen.single().url.toString())
        val failed = ProviderKeyCheck.check(config, "sk-test",
            httpClient = reply(401, """{"error":{"message":"Incorrect API key provided"}}""", seen))
        assertEquals("HTTP 401 Unauthorized: Incorrect API key provided", (failed as ProviderKeyCheck.Result.Failed).message)
    }

    @Test fun errorShapesFromCommonProviders() {
        assertEquals("HTTP 400: bad", ProviderKeyCheck.errorMessage(400, "", """{"error":{"message":"bad"}}"""))
        assertEquals("HTTP 403 Forbidden: denied", ProviderKeyCheck.errorMessage(403, "Forbidden", """{"error":"denied"}"""))
        assertEquals("HTTP 404: missing", ProviderKeyCheck.errorMessage(404, "", """{"message":"missing"}"""))
        assertEquals("HTTP 502", ProviderKeyCheck.errorMessage(502, "", "<html>bad gateway</html>"))
        // NVIDIA puts its reason in RFC 7807 `detail`.
        assertEquals("HTTP 403: Authorization failed", ProviderKeyCheck.errorMessage(403, "",
            """{"status":403,"title":"Forbidden","detail":"Authorization failed"}"""))
    }

    @Test fun nvidiaMissingAndRetiredModelsSayWhatToDo() {
        val missing = ProviderKeyCheck.errorMessage(404, "Not Found", "404 page not found", "nobody/not-a-model")
        assertTrue(missing, missing.startsWith("HTTP 404 Not Found: The provider does not serve \"nobody/not-a-model\""))
        assertTrue(missing, missing.contains("Refresh the model list"))
        val retired = ProviderKeyCheck.errorMessage(410, "Gone",
            """{"type":"about:blank","title":"Gone","status":410,"detail":"The model 'meta/llama-3.1-8b-instruct' has reached its end of life on 2026-08-26T09:00:00Z and is no longer available."}""",
            "meta/llama-3.1-8b-instruct")
        assertTrue(retired, retired.startsWith("HTTP 410 Gone: The model 'meta/llama-3.1-8b-instruct' has reached its end of life"))
        assertTrue(retired, retired.endsWith("Refresh the model list and choose another model."))
        assertEquals("HTTP 404: The provider has nothing at this address. Check the base URL.",
            ProviderKeyCheck.errorMessage(404, "", "404 page not found"))
    }

    @Test fun chatErrorsExplainMissingModelsButKeepOtherBodies() {
        assertTrue(ApiException(404, "404 page not found").message!!.contains("Refresh the model list"))
        val explained = """{"error":{"message":"model gpt-x does not exist"}}"""
        assertEquals("HTTP 404: $explained", ApiException(404, explained).message)
        val overflow = """{"error":{"message":"maximum context length is 8192 tokens"}}"""
        assertEquals("HTTP 400: $overflow", ApiException(400, overflow).message)
    }
}
