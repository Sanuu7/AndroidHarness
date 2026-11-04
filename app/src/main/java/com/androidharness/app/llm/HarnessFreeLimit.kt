package com.androidharness.app.llm

/** Only Harness's anonymous Kilo transport creates these user-facing notices. */
internal object HarnessFreeLimit {
    const val QUOTA_CODE = "harness_free_quota_exhausted"
    const val RATE_CODE = "harness_free_rate_limited"
    private const val QUOTA_TITLE = "Harness free quota limit has been exhausted."
    private const val RATE_TITLE = "Harness free models are temporarily rate limited."
    private val quotaText = Regex("free[- ]model (?:usage )?limit (?:reached|exceeded)|" +
        "rate limit exceeded for free models|free[- ]model.{0,40}quota.{0,20}(?:exhausted|exceeded)", RegexOption.IGNORE_CASE)
    private val waitText = Regex("(?:^|\\n)Try again in ([0-9]+) seconds\\.")
    enum class Kind { QUOTA, TEMPORARY }
    data class Notice(val kind: Kind, val retryAfterSeconds: Long? = null)

    fun normalize(failure: StreamEvent.Failure): StreamEvent.Failure {
        if (failure.code != null && failure.code != 429) return failure
        val quota = failure.errorCode.equals("PROMOTION_MODEL_LIMIT_REACHED", ignoreCase = true) ||
            failure.errorCode == QUOTA_CODE || quotaText.containsMatchIn(failure.message)
        if (!quota && failure.code != 429) return failure
        val notice = Notice(if (quota) Kind.QUOTA else Kind.TEMPORARY, failure.retryAfterSeconds)
        return failure.copy(message = message(notice), code = 429,
            errorCode = if (quota) QUOTA_CODE else RATE_CODE,
            retryable = if (quota) false else failure.retryable)
    }

    fun message(notice: Notice): String = buildString {
        append(if (notice.kind == Kind.QUOTA) QUOTA_TITLE else RATE_TITLE)
        notice.retryAfterSeconds?.let { append("\nTry again in ").append(it).append(" seconds.") }
        append(if (notice.kind == Kind.QUOTA)
            "\nThe free allowance renews over an hourly window. Please wait for it to reset, or switch providers to continue."
            else "\nPlease try again shortly, or switch providers to continue.")
    }

    /** Also recognize a saved raw Kilo 429 from before friendly notices were introduced. */
    fun suppressRecovery(reason: String?, provider: ProviderConfig? = null): Boolean {
        if (notice(reason) != null) return true
        if (reason == null || provider?.id != HarnessProvider.ID) return false
        val promotion = reason.contains("PROMOTION_MODEL_LIMIT_REACHED", ignoreCase = true)
        if (!promotion && HarnessProvider.pool[provider.model]?.baseUrl != HarnessProvider.KILO_BASE_URL) return false
        val status = Regex("""(?:"(?:status|code)"\s*:\s*"?|\bHTTP\s+|^\s*)([45]\d{2})\b""")
            .find(reason)?.groupValues?.get(1)?.toIntOrNull()
        val failure = normalize(StreamEvent.Failure(reason, status,
            errorCode = if (promotion) "PROMOTION_MODEL_LIMIT_REACHED" else null))
        return failure.errorCode == QUOTA_CODE || failure.errorCode == RATE_CODE
    }

    /** Stable friendly prefixes survive the shared retry, recovery and notification paths. */
    fun notice(message: String?): Notice? {
        if (message == null) return null
        val kind = when {
            message.contains(QUOTA_TITLE) -> Kind.QUOTA
            message.contains(RATE_TITLE) -> Kind.TEMPORARY
            else -> return null
        }
        return Notice(kind, waitText.find(message)?.groupValues?.get(1)?.toLongOrNull())
    }
}
