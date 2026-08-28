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
            "connection reset|connection refused|ECONNRESET|socket closed",
        RegexOption.IGNORE_CASE,
    )

    /** HTTP codes worth another attempt: request timeout, rate limit, server errors. */
    fun isRetryableCode(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    /**
     * Classifies a failure from its (optional) thrown cause and (optional)
     * in-stream message. [ApiException] is checked before [IOException]
     * because it subclasses it, an HTTP 400 must not retry even though it
     * arrives as an IOException.
     */
    fun isRetryable(cause: Throwable?, message: String?): Boolean {
        var c = cause
        while (c != null) {
            if (c is ApiException) return isRetryableCode(c.code)
            if (c is IOException) return true
            c = c.cause
        }
        return message != null && TRANSIENT_MESSAGE.containsMatchIn(message)
    }

    /** Exponential backoff 1s / 2s / 4s with +/-30% jitter. [attempt] is 1-based. */
    fun delayMs(attempt: Int): Long {
        val exp = (attempt - 1).coerceIn(0, 4)
        val base = BASE_DELAY_MS shl exp
        val jitter = (base * 0.3 * (Math.random() * 2 - 1)).toLong()
        return base + jitter
    }
}
