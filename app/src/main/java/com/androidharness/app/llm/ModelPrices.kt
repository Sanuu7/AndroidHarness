package com.androidharness.app.llm

/** Rough list prices ($ per 1M tokens) for common model families. */
object ModelPrices {
    private data class Price(
        val input: Double,
        val output: Double,
        /** Cache-read multiplier vs input price (Anthropic 0.1x, others ~0.25-0.5x). */
        val cacheRead: Double = 0.1,
        /** Cache-write multiplier vs input price; only Anthropic charges a premium (1.25x). */
        val cacheWrite: Double = 1.0,
    )

    private val table = listOf(
        // prefix to the *input* (input $/M, output $/M, cache-read $/M, cache-write $/M)
        "claude-opus-4" to Price(15.0, 75.0),
        "claude-opus" to Price(15.0, 75.0),
        "claude-sonnet-4.5" to Price(3.0, 15.0),
        "claude-sonnet-4" to Price(3.0, 15.0),
        "claude-sonnet" to Price(3.0, 15.0),
        "claude-3-7-sonnet" to Price(3.0, 15.0),
        "claude-3-5-sonnet" to Price(3.0, 15.0),
        "claude-haiku" to Price(0.80, 4.0),
        "gpt-4o-mini" to Price(0.15, 0.60, cacheRead = 0.5, cacheWrite = 1.0),
        "gpt-4o" to Price(2.50, 10.0, cacheRead = 0.5, cacheWrite = 1.0),
        "gpt-4.1-mini" to Price(0.40, 1.60, cacheRead = 0.5, cacheWrite = 1.0),
        "gpt-4.1" to Price(2.0, 8.0, cacheRead = 0.5, cacheWrite = 1.0),
        "gpt-5-mini" to Price(0.25, 2.0, cacheRead = 0.25, cacheWrite = 1.0),
        "gpt-5" to Price(1.25, 10.0, cacheRead = 0.25, cacheWrite = 1.0),
        "gemini-2.5-flash" to Price(0.30, 2.50, cacheRead = 0.25, cacheWrite = 1.0),
        "gemini-2.5-pro" to Price(1.25, 10.0, cacheRead = 0.25, cacheWrite = 1.0),
        "gemini-2.0-flash" to Price(0.10, 0.40, cacheRead = 0.25, cacheWrite = 1.0),
        "gemini-1.5-pro" to Price(1.25, 5.0, cacheRead = 0.25, cacheWrite = 1.0),
        // DeepSeek context caching: hit tokens ~0.07/M, miss at the input rate.
        "deepseek-chat" to Price(0.28, 0.42, cacheRead = 0.25, cacheWrite = 1.0),
        "deepseek-v3" to Price(0.28, 0.42, cacheRead = 0.25, cacheWrite = 1.0),
        "deepseek-r1" to Price(0.55, 2.19, cacheRead = 0.25, cacheWrite = 1.0),
        "llama-3.3-70b" to Price(0.59, 0.79),
        "llama-3.1-8b" to Price(0.05, 0.08),
        "qwen3" to Price(0.30, 1.20),
    )

    /** Estimated cost in USD, or null for unknown models. */
    fun estimate(
        model: String,
        totalInputTokens: Long,
        outputTokens: Long,
        cachedTokens: Long = 0,
        cacheWriteTokens: Long = 0,
    ): Double? {
        val normalized = model.lowercase()
        val price = table.firstOrNull { (prefix, _) -> normalized.contains(prefix) }?.second
            ?: return null
        // totalInput = uncached + cache reads + cache writes; each tier is
        // billed at its own rate. Unknown-model callers still pass raw totals.
        val uncached = (totalInputTokens - cachedTokens - cacheWriteTokens).coerceAtLeast(0)
        return (uncached / 1_000_000.0 * price.input) +
            (cachedTokens / 1_000_000.0 * price.input * price.cacheRead) +
            (cacheWriteTokens / 1_000_000.0 * price.input * price.cacheWrite) +
            (outputTokens / 1_000_000.0 * price.output)
    }
}
