package com.androidharness.app.ui.chat

/** Incremental block boundaries, so blanks inside code/math never split a live block. */
internal class MarkdownStreamTracker {
    data class Window(val stableEnd: Int, val openFenceStart: Int?, val fenceBodyStart: Int?, val revision: Int)
    private var revision = 0
    private var previous = ""
    private var scanned = 0
    private var stableEnd = 0
    private var fenceLength = 0
    private var fenceStart: Int? = null
    private var bodyStart: Int? = null
    private var math: String? = null

    fun update(text: String): Window {
        if (!text.startsWith(previous)) {
            revision++
            scanned = 0; stableEnd = 0; fenceLength = 0; fenceStart = null; bodyStart = null; math = null
        }
        while (true) {
            val end = text.indexOf('\n', scanned)
            if (end < 0) break
            val line = text.substring(scanned, end).trim()
            val ticks = line.takeWhile { it == '`' }.length
            when {
                fenceLength > 0 -> if (ticks >= fenceLength && line.drop(ticks).isBlank()) {
                    fenceLength = 0; fenceStart = null; bodyStart = null
                }
                math != null -> if (line.contains(if (math == "$$") "$$" else "\\]")) math = null
                ticks >= 3 -> { fenceLength = ticks; fenceStart = scanned; bodyStart = end + 1 }
                line.startsWith("$$") && !line.drop(2).contains("$$") -> math = "$$"
                line.startsWith("\\[") && !line.drop(2).contains("\\]") -> math = "\\["
                line.isBlank() -> stableEnd = end + 1
            }
            scanned = end + 1
        }
        previous = text
        // A closing delimiter is already complete before its trailing newline arrives.
        // Do not include that delimiter in the live code Copy action.
        var pos = scanned
        while (pos < text.length && text[pos].isWhitespace()) pos++
        val firstTick = pos
        while (pos < text.length && text[pos] == '`') pos++
        val closedTail = fenceLength > 0 && pos - firstTick >= fenceLength && text.substring(pos).isBlank()
        return Window(stableEnd, if (closedTail) null else fenceStart, if (closedTail) null else bodyStart, revision)
    }
}

/**
 * Hides markdown that is only half typed in the live tail, so text does not
 * render one way and then reflow into another a moment later. A bare block
 * marker (`#`, `-`, `1.`, `>`) waits for its text, a table waits for its
 * delimiter row and shows complete rows only, and an open `**`, `~~` or
 * backtick is closed early so the words are styled from the first letter.
 */
internal fun stabilizeLiveTail(tail: String): String {
    if (tail.isEmpty()) return tail
    val lineStart = tail.lastIndexOf('\n') + 1
    val head = tail.substring(0, lineStart)
    val line = tail.substring(lineStart)
    val trimmed = line.trim()
    // An opening code fence is block syntax, not an inline code span.
    if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) return tail
    if (trimmed.isEmpty() || trimmed.startsWith('|')) return withoutHeaderOnlyTable(head)
    if (BARE_MARKER.matches(trimmed)) return head
    return head + closeInlineMarkers(line)
}

private val BARE_MARKER = Regex("""(#{1,6}|[-*+_]{1,2}|\d{1,3}[.)]|>|`{1,2}|~{1,2}|:{1,2}|\$)""")

/** A table header with no delimiter row yet would show as a plain line first. */
private fun withoutHeaderOnlyTable(head: String): String {
    val body = head.removeSuffix("\n")
    val lastStart = body.lastIndexOf('\n') + 1
    val last = body.substring(lastStart).trim()
    if (!last.startsWith('|')) return head
    val previous = body.substring(0, maxOf(0, lastStart - 1)).substringAfterLast('\n').trim()
    return if (lastStart > 0 && previous.startsWith('|')) head else body.substring(0, lastStart)
}

private fun closeInlineMarkers(line: String): String {
    if (line.isBlank()) return line
    val ticks = line.count { it == '`' }
    if (ticks % 2 == 1) {
        val open = line.lastIndexOf('`')
        return if (line.substring(open + 1).isBlank()) line.substring(0, open) else closeAt(line, "`")
    }
    // Code spans hide their contents from the other markers.
    val outside = Regex("`[^`]*`").replace(line) { " ".repeat(it.value.length) }
    var out = line
    for (marker in listOf("**", "~~")) {
        if (countMarker(outside, marker) % 2 == 0) continue
        val open = outside.lastIndexOf(marker)
        val after = out.substring(open + marker.length)
        out = when {
            after.isBlank() -> out.substring(0, open)
            // Half of the closing marker already arrived.
            after.trimEnd().endsWith(marker[0]) -> closeAt(out.trimEnd().dropLast(1), marker)
            else -> closeAt(out, marker)
        }
    }
    return out
}

private fun countMarker(text: String, marker: String): Int {
    var count = 0
    var at = text.indexOf(marker)
    while (at >= 0) { count++; at = text.indexOf(marker, at + marker.length) }
    return count
}

/** Closes before trailing spaces so the styled run ends at the last word. */
private fun closeAt(text: String, marker: String): String {
    val end = text.trimEnd().length
    return text.substring(0, end) + marker + text.substring(end)
}
