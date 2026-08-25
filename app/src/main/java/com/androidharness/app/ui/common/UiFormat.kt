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
