package com.androidharness.app.agent

import com.androidharness.app.llm.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RetryPolicyTest {

    @Test
    fun `http retryable codes`() {
        assertTrue(RetryPolicy.isRetryableCode(408))
        assertTrue(RetryPolicy.isRetryableCode(429))
        assertTrue(RetryPolicy.isRetryableCode(500))
        assertTrue(RetryPolicy.isRetryableCode(502))
        assertTrue(RetryPolicy.isRetryableCode(503))
        assertTrue(RetryPolicy.isRetryableCode(599))
        assertFalse(RetryPolicy.isRetryableCode(400))
        assertFalse(RetryPolicy.isRetryableCode(401))
        assertFalse(RetryPolicy.isRetryableCode(403))
        assertFalse(RetryPolicy.isRetryableCode(404))
        assertFalse(RetryPolicy.isRetryableCode(422))
    }

    @Test
    fun `api exception classifies by code`() {
        assertTrue(RetryPolicy.isRetryable(ApiException(429, "rate limit"), null))
        assertFalse(RetryPolicy.isRetryable(ApiException(400, "bad request"), null))
    }

    @Test
    fun `message-only http-4xx text does not match transient regex`() {
        // No typed cause available (flattened message): must not retry.
        assertFalse(RetryPolicy.isRetryable(null, "HTTP 400: invalid request"))
        assertFalse(RetryPolicy.isRetryable(null, "HTTP 401: unauthorized"))
    }

    @Test
    fun `plain io exception is retryable`() {
        assertTrue(RetryPolicy.isRetryable(IOException("Connection reset by peer"), null))
        assertTrue(RetryPolicy.isRetryable(null, "Connection reset by peer"))
    }

    @Test
    fun `wrapped io exception is retryable`() {
        val wrapped = RuntimeException("stream failed", IOException("socket closed"))
        assertTrue(RetryPolicy.isRetryable(wrapped, "stream failed"))
    }

    @Test
    fun `wrapped api exception classifies by code not wrapper`() {
        val wrapped = RuntimeException("request failed", ApiException(500, "boom"))
        assertTrue(RetryPolicy.isRetryable(wrapped, null))
        val wrapped400 = RuntimeException("request failed", ApiException(400, "nope"))
        assertFalse(RetryPolicy.isRetryable(wrapped400, null))
    }

    @Test
    fun `transient in-stream messages are retryable`() {
        assertTrue(RetryPolicy.isRetryable(null, "The server is overloaded"))
        assertTrue(RetryPolicy.isRetryable(null, "Rate limit exceeded"))
        assertTrue(RetryPolicy.isRetryable(null, "Request timed out"))
    }

    @Test
    fun `permanent in-stream messages are not retryable`() {
        assertFalse(RetryPolicy.isRetryable(null, "Invalid API key provided"))
        assertFalse(RetryPolicy.isRetryable(null, "model not found"))
        assertFalse(RetryPolicy.isRetryable(null, null))
    }

    @Test
    fun `backoff grows exponentially within bounds`() {
        val d1 = RetryPolicy.delayMs(1)
        val d2 = RetryPolicy.delayMs(2)
        val d3 = RetryPolicy.delayMs(3)
        // 1s, 2s, 4s bases each ±30% jitter.
        assertTrue(d1 in 700..1300)
        assertTrue(d2 in 1400..2600)
        assertTrue(d3 in 2800..5200)
        assertEquals(3, RetryPolicy.MAX_RETRIES)
    }
}
