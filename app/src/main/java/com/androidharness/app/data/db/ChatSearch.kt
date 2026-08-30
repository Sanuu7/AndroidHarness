package com.androidharness.app.data.db

import java.util.Locale

/**
 * Pure helpers behind chat search: FTS/LIKE query building and snippet
 * picking. Kept free of Android types so they unit-test on the JVM.
 */
object ChatSearch {

    /** Whitespace-separated tokens of the raw query, deduplicated, non-empty. */
    fun tokens(raw: String): List<String> =
        raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct()

    /**
     * FTS4 MATCH expression for word mode: every token must appear somewhere
     * in the text, prefix-matched, so "grad" also finds "gradle". Tokens are
     * double-quoted so punctuation and FTS syntax stay literal instead of
     * crashing the MATCH parser.
     */
    fun ftsMatchQuery(raw: String): String? {
        val toks = tokens(raw)
        if (toks.isEmpty()) return null
        return toks.joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"*" }
    }

    /** LIKE pattern for fuzzy mode: the whole query as an escaped substring. */
    fun likePattern(raw: String): String {
        val q = raw.trim()
        if (q.isEmpty()) return "%"
        val escaped = buildString {
            for (ch in q) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '%' -> append("\\%")
                    '_' -> append("\\_")
                    else -> append(ch)
                }
            }
        }
        return "%$escaped%"
    }

    /** What the UI should highlight: the whole query when fuzzy, tokens otherwise. */
    fun highlightNeedles(raw: String, fuzzy: Boolean): List<String> {
        val q = raw.trim()
        if (q.isEmpty()) return emptyList()
        return if (fuzzy) listOf(q) else tokens(raw)
    }

    /** A one-line excerpt plus the character ranges (into [text]) to highlight. */
    data class Snippet(val text: String, val ranges: List<IntRange>)

    /**
     * Window of about [maxLen] chars over [full], positioned around the first
     * needle occurrence, with every needle occurrence inside the window
     * marked (ranges are merged where needles overlap). Newlines collapse to
     * spaces and cut edges get an ellipsis so results render on one line.
     */
    fun snippet(full: String, needles: List<String>, maxLen: Int = 180): Snippet {
        val flat = full.replace('\n', ' ')
        if (needles.isEmpty() || flat.isEmpty()) return Snippet(flat.take(maxLen), emptyList())
        val lower = flat.lowercase(Locale.ROOT)
        var firstAt = -1
        for (n in needles) {
            val idx = lower.indexOf(n.lowercase(Locale.ROOT))
            if (idx >= 0 && (firstAt == -1 || idx < firstAt)) firstAt = idx
        }
        if (firstAt == -1) return Snippet(flat.take(maxLen) + "…", emptyList())

        val start = (firstAt - maxLen / 3).coerceAtLeast(0)
        val end = minOf(flat.length, start + maxLen)
        val window = flat.substring(start, end)
        val windowLower = window.lowercase(Locale.ROOT)
        val ranges = ArrayList<IntRange>()
        for (n in needles) {
            val needle = n.lowercase(Locale.ROOT)
            var idx = windowLower.indexOf(needle)
            while (idx >= 0) {
                ranges += idx until minOf(idx + needle.length, window.length)
                idx = windowLower.indexOf(needle, idx + 1)
            }
        }
        val merged = mergeRanges(ranges)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < flat.length) "…" else ""
        val shift = prefix.length
        return Snippet(
            prefix + window + suffix,
            merged.map { (it.first + shift)..(it.last + shift) },
        )
    }

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        var cur = sorted[0]
        for (r in sorted.drop(1)) {
            cur = if (r.first <= cur.last + 1) cur.first..maxOf(cur.last, r.last) else {
                out += cur
                r
            }
        }
        out += cur
        return out
    }
}
