package com.androidharness.app.agent

/** Chat's ceiling is independent of the Code context setting. */
internal object ChatContextPolicy {
    const val LIMIT = 250_000

    fun limit(configured: Int, modelLimit: Long?, local: Boolean): Int {
        val available = if (local) configured.coerceAtLeast(1) else LIMIT
        return minOf(LIMIT.toLong(), available.toLong(), modelLimit?.takeIf { it > 0 } ?: LIMIT.toLong()).toInt()
    }

    /** Code keeps its configured context unless the server reports a smaller one. */
    fun cap(configured: Int, serverLimit: Long?): Int =
        serverLimit?.takeIf { it > 0 }?.let { minOf(configured.toLong(), it).toInt() } ?: configured

    /**
     * Smallest catalog value trusted as a context window. Some model lists
     * fill the field from max_tokens, which is often the reply limit, and a
     * 4K or 8K reply cap read as the whole window would compact every turn.
     */
    const val MIN_CATALOG_CONTEXT = 16_384L

    /**
     * The window a Code run budgets against: the configured setting, lowered
     * by what the server, the model list or an earlier rejection says the
     * model really accepts.
     */
    fun codeLimit(configured: Int, serverLimit: Long?, catalogLimit: Long?, learnedLimit: Int?): Int =
        cap(configured, listOfNotNull(
            serverLimit?.takeIf { it > 0 },
            catalogLimit?.takeIf { it >= MIN_CATALOG_CONTEXT },
            learnedLimit?.takeIf { it > 0 }?.toLong(),
        ).minOrNull())

    /** The window a run budgets against, from the request's setting, the model list and any learned window. */
    fun runLimit(chat: Boolean, configured: Int, catalogLimit: Long?, learnedLimit: Int?, local: Boolean): Int =
        if (chat) minOf(configured, limit(configured, modelLimit(catalogLimit, learnedLimit), local))
        else codeLimit(configured, null, catalogLimit, learnedLimit)

    /** The model limit Chat should use: the catalog's, or a smaller one learned from a rejection. */
    fun modelLimit(catalogLimit: Long?, learnedLimit: Int?): Long? =
        listOfNotNull(catalogLimit?.takeIf { it > 0 }, learnedLimit?.takeIf { it > 0 }?.toLong()).minOrNull()

    /** Reserve room for the reply and estimation error before the context is full. */
    fun compactAt(limit: Int, output: Int): Int {
        val reserve = maxOf(limit / 20, output.coerceAtLeast(0)).coerceAtMost(limit / 2)
        return limit - reserve
    }
}
