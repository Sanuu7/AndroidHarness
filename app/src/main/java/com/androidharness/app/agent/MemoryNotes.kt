package com.androidharness.app.agent

/**
 * Workspace memory (.harness/memory.md) with a hard cap so it cannot grow
 * into a second system prompt.
 */
object MemoryNotes {

    const val MAX_CHARS = 8_000

    fun load(raw: String?, maxChars: Int = MAX_CHARS): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.length <= maxChars) return text
        val marker = "\n\n[truncated ${text.length - maxChars} chars of memory]\n\n"
        val keep = ((maxChars - marker.length) / 2).coerceAtLeast(64)
        return text.take(keep) + marker + text.takeLast(keep)
    }

    fun write(
        existing: String,
        content: String,
        mode: String,
        maxChars: Int = MAX_CHARS,
    ): String {
        val next = when (mode) {
            "replace" -> content.trimEnd() + "\n"
            else -> {
                val base = existing.trimEnd()
                if (base.isEmpty()) content.trimEnd() + "\n"
                else base + "\n\n" + content.trimEnd() + "\n"
            }
        }
        if (next.length <= maxChars) return next
        // Newest notes live at the end, keep the tail.
        return next.takeLast(maxChars)
    }
}
