package com.androidharness.app.agent

/**
 * Reads a provider's "the request is too long for this model" error. Gateways
 * often serve a model with a smaller window than the model itself has, and
 * the error is the only place that real window shows up.
 */
internal object ContextOverflow {
    private val markers = listOf(
        "context_length_exceeded", "maximum context length", "context length exceeded",
        "context window", "prompt is too long", "input is too long",
        "exceeds the maximum number of tokens", "exceeds the available context size",
        "max_input_tokens", "reduce the length of the messages", "input token count",
    )

    private val rateLimit = listOf("rate limit", "rate_limit", "per minute", "per day", "quota")

    /** Limits, most specific first; the first capture group is the token count. */
    private val limits = listOf(
        Regex(""""max_input_tokens"\s*:\s*(\d+)"""),
        Regex("""maximum context length is\s+(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""tokens\s*>\s*(\d+)\s*maximum""", RegexOption.IGNORE_CASE),
        Regex("""maximum number of tokens allowed\s*\((\d+)\)""", RegexOption.IGNORE_CASE),
        Regex("""available context size\s*\((\d+)\s*tokens?\)""", RegexOption.IGNORE_CASE),
        Regex("""context (?:window|length) (?:is|of)\s+(\d+)""", RegexOption.IGNORE_CASE),
    )

    /** What the server counted for the rejected request, most specific first. */
    private val counted = listOf(
        Regex(""""estimated_tokens"\s*:\s*(\d+)"""),
        Regex("""messages (?:are|resulted in) (?:about\s+)?(\d+)\s*tokens""", RegexOption.IGNORE_CASE),
        Regex("""requested (?:about\s+)?(\d+)\s*tokens""", RegexOption.IGNORE_CASE),
        Regex("""prompt is too long:\s*(\d+)\s*tokens""", RegexOption.IGNORE_CASE),
        Regex("""input token count\s*\((\d+)\)""", RegexOption.IGNORE_CASE),
    )

    /** Below this a parsed number is more likely a reply cap or noise than a window. */
    private const val MIN_LIMIT = 4_096

    fun isOverflow(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val lower = message.lowercase(java.util.Locale.ROOT)
        // "Too many tokens per minute" is a rate limit; compacting would not help.
        if (rateLimit.any { it in lower }) return false
        return markers.any { it in lower }
    }

    /** The input window the error names, or null when it names none. */
    fun reportedLimit(message: String?): Int? {
        if (!isOverflow(message)) return null
        return limits.firstNotNullOfOrNull { pattern ->
            pattern.find(message!!)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= MIN_LIMIT }
        }
    }

    /** The tokens the server counted for the rejected request, or null when it does not say. */
    fun reportedTokens(message: String?): Int? {
        if (!isOverflow(message)) return null
        return counted.firstNotNullOfOrNull { pattern ->
            pattern.find(message!!)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        }
    }

    /**
     * One run's state after "too long" rejections. [after] turns a failure into
     * the next state, or null when the failure is something else or the run
     * has already retried [MAX_RECOVERIES] times.
     */
    data class Recovery(
        val attempts: Int = 0,
        /** The window the latest rejection named, when it named one smaller than the budget. */
        val learned: Int? = null,
        /** How far the server's count ran ahead of the estimate; the compaction trigger moves down to match. */
        val estimateScale: Double = 1.0,
        val forceCompaction: Boolean = false,
        /** "providerId|model" the learned window belongs to. */
        val key: String = "",
    ) {
        fun after(failure: String?, contextLimit: Int, estimated: Int, key: String): Recovery? {
            if (failure == null || attempts >= MAX_RECOVERIES || !isOverflow(failure)) return null
            val counted = reportedTokens(failure)
            val scale = if (counted != null && estimated > 0 && counted > estimated)
                maxOf(estimateScale, counted.toDouble() / estimated).coerceAtMost(MAX_SCALE) else estimateScale
            return Recovery(attempts + 1, reportedLimit(failure)?.takeIf { it < contextLimit }, scale, forceCompaction = true, key = key)
        }

        fun limitFor(contextLimit: Int): Int = learned?.let { minOf(it, contextLimit) } ?: contextLimit

        fun scaled(threshold: Int): Int = (threshold / estimateScale).toInt().coerceAtLeast(1)

        fun settled(): Recovery = if (forceCompaction) copy(forceCompaction = false) else this

        fun notice(): String = learned?.let {
            "The provider accepts at most ${com.androidharness.app.ui.common.formatTokenCount(it)} tokens of context " +
                "for this model. Compacting earlier messages and sending again."
        } ?: "The provider said the conversation is too long for this model. Compacting earlier messages and sending again."
    }

    const val MAX_RECOVERIES = 2

    /** A larger gap means the count belongs to something else, not to this estimate. */
    private const val MAX_SCALE = 4.0
}

/** The window a run budgets against, lowered when the server rejects a request as too long. */
internal class ContextBudget(var limit: Int) {
    /** A server that rejects a request as too long gets a compacted retry, at most twice a run. */
    var recovery = ContextOverflow.Recovery()
        private set

    /** True when [failure] was a "too long" rejection this run can still retry after compacting. */
    fun recover(failure: String?, estimated: Int, key: String): Boolean {
        val next = recovery.after(failure, limit, estimated, key) ?: return false
        recovery = next
        limit = next.limitFor(limit)
        return true
    }

    fun settle() { recovery = recovery.settled() }
}
