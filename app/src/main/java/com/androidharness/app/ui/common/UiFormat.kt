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

/** Compact elapsed time: "43s", "2m 13s", "1h 4m". */
fun formatDuration(ms: Long): String = when {
    ms <= 0 -> ""
    ms < 60_000 -> "${ms / 1000}s"
    ms < 3_600_000 -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    else -> "${ms / 3_600_000}h ${(ms % 3_600_000) / 60_000}m"
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

