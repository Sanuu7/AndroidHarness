package com.androidharness.app.tools

import com.androidharness.app.core.splitLines

/**
 * Whitespace-tolerant string replacement for edit tools.
 *
 * Models frequently reproduce code with drifted whitespace: trailing spaces
 * dropped, indentation off by a level, CRLF read as LF. An exact match then
 * fails and the turn is wasted on a re-read loop. This matcher retries at
 * increasingly tolerant levels — each only accepted when the match is
 * unambiguous — and replaces using the ORIGINAL text span, so surrounding
 * formatting is never rewritten.
 *
 * Deliberately whitespace-only: no typo/Levenshtein tolerance. Wrong-but-
 * similar code must fail, not silently corrupt a file.
 */
object FuzzyEdit {

    /** How loose the match that succeeded was. */
    enum class Level { EXACT, LINE_ENDINGS, INDENTATION }

    sealed interface Result {
        /** [newText] has [count] replacement(s) applied. */
        data class Ok(
            val newText: String,
            val count: Int,
            val level: Level,
        ) : Result

        data class NotFound(val detail: String) : Result
        data class Ambiguous(val count: Int) : Result
    }

    /** One concrete occurrence in [text]: [start] inclusive, [end] exclusive. */
    data class Match(val start: Int, val end: Int)

    /**
     * Replaces occurrences of [old] with [new]. Tries exact substring first,
     * then line-ending/trailing-space tolerant, then indentation tolerant.
     * Falls through to the next level when the current one finds NOTHING —
     * never when it is ambiguous (ambiguity at a stricter level is a real
     * error the caller must see).
     */
    fun replace(text: String, old: String, new: String, replaceAll: Boolean): Result {
        if (old.isEmpty()) return Result.NotFound("old_string is empty")

        // L0 — today's behavior: plain substring.
        val exact = findAllExact(text, old)
        if (exact.isNotEmpty()) return finish(text, exact, new, replaceAll, Level.EXACT)

        // L1 — same lines, ignoring trailing whitespace and line terminators.
        val l1 = findNormalized(text, old, Level.LINE_ENDINGS)
        if (l1.isEmpty()) {
            // L2 — additionally ignore leading whitespace per line.
            val l2 = findNormalized(text, old, Level.INDENTATION)
            if (l2.isEmpty()) {
                return Result.NotFound(
                    "old_string not found (exact or whitespace-tolerant). " +
                        "Re-read the file and retry with the current contents."
                )
            }
            return finish(text, l2, new, replaceAll, Level.INDENTATION)
        }
        return finish(text, l1, new, replaceAll, Level.LINE_ENDINGS)
    }

    private fun finish(
        text: String,
        matches: List<Match>,
        new: String,
        replaceAll: Boolean,
        level: Level,
    ): Result {
        if (matches.size > 1 && !replaceAll) return Result.Ambiguous(matches.size)
        val out = StringBuilder(text.length)
        var cursor = 0
        val use = if (replaceAll) matches else matches.take(1)
        for ((start, end) in use) {
            out.append(text, cursor, start)
            out.append(new)
            cursor = end
        }
        out.append(text, cursor, text.length)
        return Result.Ok(out.toString(), use.size, level)
    }

    private fun findAllExact(text: String, old: String): List<Match> {
        val out = ArrayList<Match>()
        var i = text.indexOf(old)
        while (i >= 0) {
            out += Match(i, i + old.length)
            i = text.indexOf(old, i + old.length.coerceAtLeast(1))
        }
        return out
    }

    /**
     * Line-window matcher for tolerant levels. Splits both texts into lines
     * (Kotlin's lines() handles \n, \r\n and \r), then slides a window of
     * old.lines().size across text.lines() comparing normalized lines.
     * Overlapping windows from a shared start are collapsed so a run of
     * identical blank lines yields one match, not several.
     */
    private fun findNormalized(text: String, old: String, level: Level): List<Match> {
        val textLines = splitLines(text)
        val oldLines = splitLines(old)
        if (oldLines.isEmpty() || oldLines.size > textLines.size) return emptyList()

        // Per-line offsets in the original text (line start index).
        val starts = lineStarts(text)
        val norm: (String) -> String = when (level) {
            Level.LINE_ENDINGS -> { s -> s.trimEnd() }
            Level.INDENTATION -> { s -> s.trim() }
            Level.EXACT -> { s -> s }
        }
        val normOld = oldLines.map(norm)

        val out = ArrayList<Match>()
        var i = 0
        outer@ while (i + oldLines.size <= textLines.size) {
            for (j in oldLines.indices) {
                if (norm(textLines[i + j]) != normOld[j]) {
                    i++
                    continue@outer
                }
            }
            val startIdx = starts[i]
            val endIdx = starts[i + oldLines.size - 1] + textLines[i + oldLines.size - 1].length
            out += Match(startIdx, endIdx)
            i += oldLines.size // skip past; no overlapping matches
        }
        return out
    }

    private fun lineStarts(text: String): IntArray {
        val starts = IntArray(text.length + 1)
        var count = 0
        starts[count++] = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                starts[count++] = i + 1
            } else if (c == '\r') {
                starts[count++] = i + if (i + 1 < text.length && text[i + 1] == '\n') 2 else 1
            }
            i++
        }
        return starts.copyOf(count)
    }

    /**
     * Line equality for patch-hunk context matching: [line] from the file vs
     * [expected] from the patch, at the given tolerance [level].
     */
    fun lineEquals(line: String, expected: String, level: Level): Boolean = when (level) {
        Level.EXACT -> line == expected
        Level.LINE_ENDINGS -> line.trimEnd() == expected.trimEnd()
        Level.INDENTATION -> line.trim() == expected.trim()
    }
}
