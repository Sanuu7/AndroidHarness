package com.androidharness.app.llm

import com.androidharness.app.agent.StreamRetrier
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class HarnessFreeLimitTest {
    @After fun reset() { HarnessProvider.restoreModels(HarnessProvider.fallbackModels) }
    private fun client(body: String, code: Int, retryAfter: String? = null, requests: () -> Unit = {}) =
        OkHttpClient.Builder().addInterceptor { chain ->
            requests()
            assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .apply { retryAfter?.let { header("Retry-After", it) } }
                .body(body.toResponseBody()).build()
        }.build()
    private fun stream(body: String, code: Int = 429, retryAfter: String? = null, model: String = HarnessProvider.DEFAULT_MODEL,
        requests: () -> Unit = {}) = HarnessProvider.create(client(body, code, retryAfter, requests)).streamChat(
        HarnessProvider.config.copy(model = model), HarnessProvider.KEYLESS, "",
        listOf(ChatMessage(Role.USER, "Hello")), emptyList(), RequestOptions())

    @Test fun `current anonymous promotion refusal becomes friendly terminal quota notice`() = runBlocking {
        val body = """{"error":{"code":"PROMOTION_MODEL_LIMIT_REACHED","message":"Sign up for free to continue and explore 500 other models. Takes 2 minutes, no credit card required. Or come back later."},"error_type":"rate_limit_exceeded"}"""
        val failure = stream(body).toList().filterIsInstance<StreamEvent.Failure>().single()
        assertEquals(HarnessFreeLimit.QUOTA_CODE, failure.errorCode)
        assertEquals(false, failure.retryable)
        assertEquals(HarnessFreeLimit.Kind.QUOTA, HarnessFreeLimit.notice(failure.message)?.kind)
        assertFalse(failure.message.contains("Sign up"))
        assertNull(HarnessFreeLimit.notice(failure.message)?.retryAfterSeconds)
        assertEquals(FallbackFailureKind.QUOTA, fallbackFailureKind(failure))
    }

    @Test fun `documented and account free limit messages are recognized`() = runBlocking {
        for (body in listOf(
            """{"error":{"message":"Rate limit exceeded for free models. Please try again later.","code":429}}""",
            """{"error":"Rate limit exceeded","message":"Free model usage limit reached. Please try again later, or switch to a paid model or your own API key."}"""
        )) assertEquals(HarnessFreeLimit.QUOTA_CODE, stream(body).toList().filterIsInstance<StreamEvent.Failure>().single().errorCode)
    }

    @Test fun `SSE quota refusal preserves an explicit body delay`() = runBlocking {
        val body = "data: {\"error\":{\"code\":\"PROMOTION_MODEL_LIMIT_REACHED\",\"message\":\"Come back later\",\"metadata\":{\"retry_after_seconds\":120}}}\n\n"
        val failure = stream(body, 200).toList().filterIsInstance<StreamEvent.Failure>().single()
        assertEquals(HarnessFreeLimit.QUOTA_CODE, failure.errorCode)
        assertEquals(120L, HarnessFreeLimit.notice(failure.message)?.retryAfterSeconds)
    }

    @Test fun `known hourly quota is not retried or auto continued`() = runBlocking {
        var requests = 0
        var recoverable = true
        val result = StreamRetrier.run(
            streamFor = { stream("""{"error":{"code":"PROMOTION_MODEL_LIMIT_REACHED","message":"Come back later"}}""", requests = { requests++ }) },
            onAttemptStart = {}, hasOutput = { false }, handleEvent = {}, retryReason = { it },
            emitEvent = { fail("A known exhausted quota must not start a retry countdown") }, sleep = {},
            onTerminalFailure = { recoverable = it })
        assertEquals(1, requests)
        assertFalse(recoverable)
        assertNotNull(HarnessFreeLimit.notice(result))
    }

    @Test fun `temporary upstream limits do not claim the hourly quota is exhausted`() = runBlocking {
        val failure = stream("""{"error":{"message":"Upstream model rate limit exceeded","metadata":{"limit_source":"upstream_provider_shared_pool","retry_after_seconds":1}}}""")
            .toList().filterIsInstance<StreamEvent.Failure>().single()
        assertEquals(HarnessFreeLimit.RATE_CODE, failure.errorCode)
        assertEquals(HarnessFreeLimit.Kind.TEMPORARY, HarnessFreeLimit.notice(failure.message)?.kind)
        assertNotEquals(false, failure.retryable)
        assertEquals(1L, HarnessFreeLimit.notice(failure.message)?.retryAfterSeconds)
    }

    @Test fun `HTTP retry after is shown when supplied`() = runBlocking {
        val failure = stream("""{"error":{"code":"PROMOTION_MODEL_LIMIT_REACHED"}}""", retryAfter = "3600")
            .toList().filterIsInstance<StreamEvent.Failure>().single()
        assertEquals(3600L, HarnessFreeLimit.notice(failure.message)?.retryAfterSeconds)
    }

    @Test fun `auth server and other Harness upstream errors retain their normal behavior`() = runBlocking {
        for (code in listOf(400, 401, 403, 500, 503)) {
            val failure = stream("""{"error":{"message":"Original error"}}""", code)
                .toList().filterIsInstance<StreamEvent.Failure>().single()
            assertNull(HarnessFreeLimit.notice(failure.message))
            assertEquals(code, failure.code)
            assertTrue(failure.message.contains("Original error"))
        }
        try {
            stream("""{"error":{"message":"Rate limit exceeded for free models"}}""", model = "openai-fast").toList()
            fail("Pollinations should keep its original error")
        } catch (e: ApiException) { assertNull(HarnessFreeLimit.notice(e.message)) }
    }

    @Test fun `recovery suppression is scoped to friendly notices and saved Harness Kilo failures`() {
        val raw = """HTTP 429: {"error":{"code":"PROMOTION_MODEL_LIMIT_REACHED","message":"Come back later"}}"""
        val other = HarnessProvider.config.copy(id = "other", name = "Other provider")
        assertTrue(HarnessFreeLimit.suppressRecovery(raw, HarnessProvider.config))
        assertFalse(HarnessFreeLimit.suppressRecovery(raw, other))
        assertFalse(HarnessFreeLimit.suppressRecovery("HTTP 429: Too many requests", other))
        assertFalse(HarnessFreeLimit.suppressRecovery("HTTP 503: Unavailable", HarnessProvider.config))
        assertFalse(HarnessFreeLimit.suppressRecovery("HTTP 401: Authentication required", HarnessProvider.config))
        assertFalse(HarnessFreeLimit.suppressRecovery("HTTP 429: Too many requests", HarnessProvider.config.copy(model = "openai-fast")))
        for (kind in HarnessFreeLimit.Kind.entries) assertTrue(HarnessFreeLimit.suppressRecovery(HarnessFreeLimit.message(HarnessFreeLimit.Notice(kind))))
    }

    @Test fun `retry after parsing rejects invalid values and accepts numeric and date hints`() {
        assertEquals(90L, retryAfterHeaderSeconds("90"))
        assertEquals(1L, retryAfterHeaderSeconds("0.2"))
        assertEquals(60L, retryAfterHeaderSeconds("Thu, 01 Jan 1970 00:01:00 GMT", 0))
        assertEquals(0L, retryAfterHeaderSeconds("Thu, 01 Jan 1970 00:01:00 GMT", 120_000))
        for (bad in listOf("-1", "NaN", "Infinity", "9999999999999999999", "tomorrow")) assertNull(retryAfterHeaderSeconds(bad))
        assertEquals(15L, providerRetryAfterSeconds(Json.parseToJsonElement("""{"error":{"metadata":{"retryAfterSeconds":"15"}}}""")))
        assertNull(providerRetryAfterSeconds(Json.parseToJsonElement("""{"error":{"metadata":{"retry_after_seconds":-1}}}""")))
        assertNull(HarnessFreeLimit.notice("A different provider has insufficient quota"))
    }
}
