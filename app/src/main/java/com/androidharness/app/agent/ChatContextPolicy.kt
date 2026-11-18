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

    /** Reserve room for the reply and estimation error before the context is full. */
    fun compactAt(limit: Int, output: Int): Int {
        val reserve = maxOf(limit / 20, output.coerceAtLeast(0)).coerceAtMost(limit / 2)
        return limit - reserve
    }
}
