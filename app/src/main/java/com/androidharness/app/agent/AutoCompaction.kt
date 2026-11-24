package com.androidharness.app.agent

import java.math.BigDecimal

/** Zero inherits the original 80% policy; positive values are input-context tokens. */
internal object AutoCompaction {
    private val amount = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(k|m|thousand|million)?(?:\s*tokens?)?""", RegexOption.IGNORE_CASE)

    /** Exact decimal conversion avoids rounding, overflow and locale-dependent suffixes. */
    fun parse(value: String): Int? {
        if (value.trim().equals("auto", ignoreCase = true)) return 0
        val match = amount.matchEntire(value.trim()) ?: return null
        val multiplier = when (match.groupValues[2].lowercase()) {
            "k", "thousand" -> 1_000
            "m", "million" -> 1_000_000
            else -> 1
        }
        return runCatching {
            BigDecimal(match.groupValues[1]).multiply(BigDecimal(multiplier)).intValueExact()
                .takeIf { it >= 1_024 }
        }.getOrNull()
    }

    /**
     * Why compaction is starting below the configured value, or null when it is
     * not. The window can be smaller than the setting suggests: a self-hosted
     * server splits its context between parallel slots, and a model list or an
     * earlier rejection can name a smaller window.
     */
    fun loweredNotice(configured: Int, threshold: Int, contextLimit: Int): String? {
        if (configured <= 0 || threshold >= configured) return null
        val f = java.text.NumberFormat.getIntegerInstance(java.util.Locale.ROOT)
        return "Auto compact is set to ${f.format(configured)} tokens, but this model can take " +
            "${f.format(contextLimit)} tokens per request here, so Code compacts at ${f.format(threshold)} " +
            "to leave room for the reply. For a self-hosted server, start it with a larger context " +
            "or fewer parallel slots to compact later."
    }

    fun threshold(configured: Int, contextLimit: Int, outputTokens: Int): Int =
        if (configured <= 0) (contextLimit * 0.8).toInt().coerceAtLeast(1)
        else minOf(configured, ChatContextPolicy.compactAt(contextLimit, outputTokens)).coerceAtLeast(1)
}
