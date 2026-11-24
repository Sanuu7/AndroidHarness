package com.androidharness.app.agent

import com.androidharness.app.llm.ApiException
import java.io.IOException

/**
 * Decides which provider failures are worth retrying and how long to wait.
 * Transient conditions only, rate limits, server overload, network drops.
 * Permanent errors (bad request, auth, not found) surface to the user at once.
 */
internal object RetryPolicy {
    const val MAX_RETRIES = 3
    private const val BASE_DELAY_MS = 1_000L

    private val TRANSIENT_MESSAGE = Regex(
        "overloaded|rate.?limit|timeout|timed out|temporarily|try again|" +
            "connection reset|connection refused|ECONNRESET|socket closed|JSON error injected into SSE stream",
        RegexOption.IGNORE_CASE,
    )

    /** HTTP codes worth another attempt: request timeout, rate limit, server errors. */
    fun isRetryableCode(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    /**
     * Classifies a failure from its (optional) thrown cause and (optional)
     * in-stream message. [ApiException] is checked before [IOException]
     * because it subclasses it, an HTTP 400 must not retry even though it
     * arrives as an IOException. An explicit in-stream [code] also takes
     * precedence over message-based guesses.
     */
    fun isRetryable(cause: Throwable?, message: String?, code: Int? = null): Boolean {
        if (code != null) return isRetryableCode(code)
        var c = cause
        while (c != null) {
            if (c is ApiException) return isRetryableCode(c.code)
            if (c is IOException) return true
            c = c.cause
        }
        return message != null && TRANSIENT_MESSAGE.containsMatchIn(message)
    }

    /**
     * Harness's free models share Kilo's anonymous gateway, which throttles in
     * short windows. Three quick retries over about 7 seconds rarely outlast
     * the window, so a "temporarily limited" reply gets more, slower attempts
     * (about a minute in all) before the run stops and asks the user.
     */
    const val FREE_RATE_RETRIES = 6
    private val FREE_RATE_DELAYS_MS = longArrayOf(2_000, 4_000, 8_000, 12_000, 15_000, 20_000)
    private const val MAX_FREE_RATE_DELAY_MS = 30_000L

    /** Waits at least as long as the gateway asked, capped so one reply cannot park the run. */
    fun freeRateDelayMs(attempt: Int, retryAfterSeconds: Long?): Long {
        val planned = FREE_RATE_DELAYS_MS[(attempt - 1).coerceIn(0, FREE_RATE_DELAYS_MS.lastIndex)]
        val asked = retryAfterSeconds?.takeIf { it > 0 }?.let { minOf(it, MAX_FREE_RATE_DELAY_MS / 1_000) * 1_000 } ?: 0
        return maxOf(planned, asked)
    }

    /** Exponential backoff 1s / 2s / 4s with +/-30% jitter. [attempt] is 1-based. */
    fun delayMs(attempt: Int): Long {
        val exp = (attempt - 1).coerceIn(0, 4)
        val base = BASE_DELAY_MS shl exp
        val jitter = (base * 0.3 * (Math.random() * 2 - 1)).toLong()
        return base + jitter
    }
}
