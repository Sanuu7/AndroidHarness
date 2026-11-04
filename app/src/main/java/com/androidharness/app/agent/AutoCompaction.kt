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

    fun threshold(configured: Int, contextLimit: Int, outputTokens: Int): Int =
        if (configured <= 0) (contextLimit * 0.8).toInt().coerceAtLeast(1)
        else minOf(configured, ChatContextPolicy.compactAt(contextLimit, outputTokens)).coerceAtLeast(1)
}
