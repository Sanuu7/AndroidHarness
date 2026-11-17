package com.androidharness.app.ui.chat

/**
 * A provider failure can be a multi-kilobyte JSON body, and a compact card cannot grow with it.
 * This keeps a one-line, human-readable lead for the card and leaves the exact text behind Details.
 */
internal object PausedReason {
    /** Lines the paused card may show before the full text moves behind Details. */
    const val SUMMARY_LINES = 3
    /** Characters the one-line lead may keep. */
    const val SUMMARY_CHARS = 180

    private val jsonMessage = Regex("\"(?:message|detail|error)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val httpLead = Regex("^HTTP\\s+([1-9]\\d{2})\\s*:\\s*")
    private val whitespace = Regex("\\s+")

    /** Null when there is nothing to show; never throws on malformed input. */
    fun summary(reason: String?): String? {
        val text = reason?.trim().orEmpty()
        if (text.isEmpty()) return null
        val status = httpLead.find(text)?.groupValues?.get(1)
        val body = text.replaceFirst(httpLead, "")
        val lead = jsonMessage.find(body)?.groupValues?.get(1)?.let(::unescape)
            ?: body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: body
        val collapsed = lead.replace(whitespace, " ").trim()
        val short = if (collapsed.length > SUMMARY_CHARS) collapsed.take(SUMMARY_CHARS).trimEnd() + "…" else collapsed
        return if (status != null) "HTTP $status · $short" else short
    }

    /** True when the card shows less than the full reason, so Details is offered. */
    fun hasMore(reason: String?): Boolean {
        val text = reason?.trim().orEmpty()
        if (text.isEmpty()) return false
        if (jsonMessage.containsMatchIn(text)) return true
        if (text.lines().count { it.isNotBlank() } > 1) return true
        return text.length > SUMMARY_CHARS
    }

    private fun unescape(value: String): String = value
        .replace("\\n", " ").replace("\\r", " ").replace("\\t", " ")
        .replace("\\\"", "\"").replace("\\\\", "\\")
}
