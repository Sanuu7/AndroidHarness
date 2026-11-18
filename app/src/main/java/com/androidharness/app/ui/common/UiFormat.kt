package com.androidharness.app.ui.common

/** "just now", "5m ago", "2h ago", "Yesterday", "Mar 4", … */
fun formatRelativeTime(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - epochMillis
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "just now"
        diff < hour -> "${diff / minute}m ago"
        diff < day -> "${diff / hour}h ago"
        diff < 2 * day -> "Yesterday"
        diff < 7 * day -> "${diff / day}d ago"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            .format(java.util.Date(epochMillis))
    }
}

/** Compact elapsed time: "43s", "2m", "2m 13s", "1h", "1h 4m", "2d 3h". A zero second part is left off. */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val seconds = ms / 1000
    fun pair(big: Long, bigUnit: String, small: Long, smallUnit: String) =
        if (small == 0L) "$big$bigUnit" else "$big$bigUnit $small$smallUnit"
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> pair(seconds / 60, "m", seconds % 60, "s")
        seconds < 86_400 -> pair(seconds / 3_600, "h", seconds % 3_600 / 60, "m")
        else -> pair(seconds / 86_400, "d", seconds % 86_400 / 3_600, "h")
    }
}

/**
 * Universal token count formatting across the app:
 * - 450 -> "450"
 * - 1,200 -> "1.2k"
 * - 128,000 -> "128k"
 * - 1,000,000 -> "1M" (never "1000k"!)
 * - 1,500,000 -> "1.5M"
 * - 128,000_000 -> "128M"
 * - 1,000,000_000 -> "1B"
 * - 2,500,000_000 -> "2.5B"
 */
fun formatTokenCount(tokens: Long): String = when {
    tokens < 0 -> "0"
    tokens >= 1_000_000_000 -> "%.1fB".format(tokens / 1_000_000_000.0).replace(".0B", "B")
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0).replace(".0M", "M")
    tokens >= 1_000 -> "%.1fK".format(tokens / 1_000.0).replace(".0K", "K")
    else -> tokens.toString()
}

fun formatTokenCount(tokens: Int): String = formatTokenCount(tokens.toLong())

fun formatTokens(tokens: Long): String = formatTokenCount(tokens)

fun formatTokens(tokens: Int): String = formatTokenCount(tokens.toLong())

