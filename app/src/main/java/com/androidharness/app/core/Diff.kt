package com.androidharness.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DiffLineType {
    CONTEXT,
    ADD,
    REMOVE,
    HEADER,
}

data class DiffLine(
    val type: DiffLineType,
    val oldNum: Int?,
    val newNum: Int?,
    val text: String,
)

data class DiffHunk(
    val header: String,
    val oldStart: Int,
    val newStart: Int,
    val lines: List<DiffLine>,
)

data class ParsedDiff(
    val oldPath: String? = null,
    val newPath: String? = null,
    val hunks: List<DiffHunk> = emptyList(),
    val isTruncated: Boolean = false,
) {
    val totalAdded: Int get() = hunks.sumOf { h -> h.lines.count { it.type == DiffLineType.ADD } }
    val totalRemoved: Int get() = hunks.sumOf { h -> h.lines.count { it.type == DiffLineType.REMOVE } }
}

/** Myers O(ND) line diff producing unified-diff-style preview text. */
object Diff {

    private const val MAX_LINES = 3000
    private const val CONTEXT = 2

    /**
     * Parse raw unified diff string into structured [ParsedDiff] with computed old/new line numbers.
     */
    fun parseUnified(diffText: String): ParsedDiff {
        val lines = splitLines(diffText)
        var oldPath: String? = null
        var newPath: String? = null
        val hunks = mutableListOf<DiffHunk>()
        var currentLines = mutableListOf<DiffLine>()
        var currentHeader = ""
        var oldStart = 1
        var newStart = 1
        var oldCursor = 1
        var newCursor = 1
        var inHunk = false
        var isTruncated = false

        fun flushHunk() {
            if (inHunk && (currentHeader.isNotEmpty() || currentLines.isNotEmpty())) {
                hunks += DiffHunk(currentHeader, oldStart, newStart, currentLines.toList())
                currentLines = mutableListOf()
            }
        }

        for (line in lines) {
            when {
                line.startsWith("--- ") -> {
                    flushHunk()
                    inHunk = false
                    oldPath = line.removePrefix("--- ").trim()
                        .removePrefix("a/").removePrefix("b/").substringBefore('\t')
                }
                line.startsWith("+++ ") -> {
                    newPath = line.removePrefix("+++ ").trim()
                        .removePrefix("a/").removePrefix("b/").substringBefore('\t')
                }
                line.startsWith("@@") -> {
                    flushHunk()
                    inHunk = true
                    currentHeader = line
                    val match = Regex("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@").find(line)
                    if (match != null) {
                        oldStart = match.groupValues[1].toIntOrNull() ?: 1
                        newStart = match.groupValues[2].toIntOrNull() ?: 1
                    } else {
                        val fallbackOld = Regex("-(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        val fallbackNew = Regex("\\+(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        oldStart = fallbackOld
                        newStart = fallbackNew
                    }
                    oldCursor = oldStart
                    newCursor = newStart
                }
                line.startsWith("  @@") -> {
                    // Internal separator eliding unchanged lines
                    if (!inHunk) inHunk = true
                    currentLines += DiffLine(
                        type = DiffLineType.HEADER,
                        oldNum = null,
                        newNum = null,
                        text = line.trim(),
                    )
                }
                line.contains("[diff truncated") -> {
                    isTruncated = true
                }
                line.startsWith("+") -> {
                    if (!inHunk) inHunk = true
                    currentLines += DiffLine(
                        type = DiffLineType.ADD,
                        oldNum = null,
                        newNum = newCursor++,
                        text = line.substring(1),
                    )
                }
                line.startsWith("-") -> {
                    if (!inHunk) inHunk = true
                    currentLines += DiffLine(
                        type = DiffLineType.REMOVE,
                        oldNum = oldCursor++,
                        newNum = null,
                        text = line.substring(1),
                    )
                }
                line.startsWith(" ") -> {
                    if (!inHunk) inHunk = true
                    currentLines += DiffLine(
                        type = DiffLineType.CONTEXT,
                        oldNum = oldCursor++,
                        newNum = newCursor++,
                        text = line.substring(1),
                    )
                }
                line.startsWith("\\") -> {
                    // "\ No newline at end of file"
                }
                else -> {
                    // Plain text context without prefix
                    if (inHunk && line.isNotEmpty()) {
                        currentLines += DiffLine(
                            type = DiffLineType.CONTEXT,
                            oldNum = oldCursor++,
                            newNum = newCursor++,
                            text = line,
                        )
                    }
                }
            }
        }
        flushHunk()

        return ParsedDiff(
            oldPath = oldPath,
            newPath = newPath,
            hunks = hunks,
            isTruncated = isTruncated,
        )
    }

    /**
     * Unified diff between [oldText] and [newText]. Lines are prefixed with
     * ' ', '-' or '+'; long unchanged runs collapse into "@@ … @@" separators.
     */
    suspend fun unified(oldText: String, newText: String, path: String): String =
        withContext(Dispatchers.Default) {
            val oldLines = splitLines(oldText).take(MAX_LINES)
            val newLines = splitLines(newText).take(MAX_LINES)
            val ops = myers(oldLines, newLines)

            val sb = StringBuilder()
            sb.append("--- ").append(if (oldText.isEmpty()) "/dev/null" else "a/$path").append('\n')
            sb.append("+++ ").append(if (newText.isEmpty()) "/dev/null" else "b/$path").append('\n')

            var equalRun = 0
            var inChange = false
            var skipped = false
            for ((idx, op) in ops.withIndex()) {
                val (mark, line) = op
                if (mark == ' ') {
                    if (inChange) {
                        sb.append(mark).append(line).append('\n')
                        equalRun++
                        if (equalRun > CONTEXT) {
                            // lookahead: if more changes are coming, elide the gap
                            val moreChanges = ops.drop(idx + 1).any { it.first != ' ' }
                            if (moreChanges) {
                                sb.append("  @@ … @@\n")
                                skipped = true
                            }
                            inChange = false
                            equalRun = 0
                        }
                    } else if (!skipped) {
                        equalRun++
                        if (equalRun > CONTEXT) {
                            sb.append("  @@ … @@\n")
                            skipped = true
                        } else {
                            sb.append(mark).append(line).append('\n')
                        }
                    }
                    if (skipped) equalRun = 0
                } else {
                    inChange = true
                    skipped = false
                    equalRun = 0
                    sb.append(mark).append(line).append('\n')
                }
            }
            if (oldLines.size >= MAX_LINES || newLines.size >= MAX_LINES) {
                sb.append("  [diff truncated for preview]\n")
            }
            sb.toString()
        }

    /** Counts of added/removed lines between [oldText] and [newText] (for "+N −M" chips). */
    fun lineCounts(oldText: String, newText: String): Pair<Int, Int> {
        // POSIX line splitting: empty text is zero lines, and a trailing
        // newline never counts as an extra (phantom) line.
        val oldLines = splitLines(oldText).take(MAX_LINES)
        val newLines = splitLines(newText).take(MAX_LINES)
        var added = 0
        var removed = 0
        for ((mark, _) in myers(oldLines, newLines)) {
            when (mark) {
                '+' -> added++
                '-' -> removed++
            }
        }
        return added to removed
    }

    /** Returns (op, line) where op is ' ', '-' or '+'. */
    private fun myers(a: List<String>, b: List<String>): List<Pair<Char, String>> {
        val n = a.size
        val m = b.size
        if (n == 0 && m == 0) return emptyList()
        val max = n + m
        val trace = mutableListOf<IntArray>()
        val v = IntArray(2 * max + 1)
        var reached = false
        for (d in 0..max) {
            trace.add(v.copyOf())
            for (k in -d..d step 2) {
                val x0 = if (k == -d || (k != d && v[k - 1 + max] < v[k + 1 + max])) {
                    v[k + 1 + max]
                } else {
                    v[k - 1 + max] + 1
                }
                var x = x0
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++; y++
                }
                v[k + max] = x
                if (x >= n && y >= m) {
                    reached = true
                    break
                }
            }
            if (reached) break
        }

        val ops = ArrayDeque<Pair<Char, String>>()
        var x = n
        var y = m
        for (d in trace.indices.reversed()) {
            val vPrev = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vPrev[k - 1 + max] < vPrev[k + 1 + max])) {
                k + 1
            } else {
                k - 1
            }
            val prevX = vPrev[prevK + max]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                ops.addFirst(' ' to a[x - 1])
                x--; y--
            }
            if (d > 0) {
                if (x == prevX) {
                    ops.addFirst('+' to b[y - 1]); y--
                } else {
                    ops.addFirst('-' to a[x - 1]); x--
                }
            }
        }
        while (x > 0 && y > 0) {
            ops.addFirst(' ' to a[x - 1])
            x--; y--
        }
        return ops.toList()
    }
}
