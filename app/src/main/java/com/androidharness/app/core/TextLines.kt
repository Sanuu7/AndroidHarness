package com.androidharness.app.core

/**
 * Splits text into lines using the POSIX convention: a trailing newline is a
 * line TERMINATOR, not a separator.
 *
 * Kotlin's [String.lines] treats "a\nb\n" as THREE lines ("a", "b", "") — the
 * trailing empty element leaks into line numbers, greps, diffs and patch
 * matching, where it shows up as a phantom extra line (it made apply_patch
 * fail on files without a trailing newline and append a stray newline to files
 * that had one). This helper matches git's model instead:
 *
 *   "a\nb\n" → ["a", "b"]
 *   "a\nb"   → ["a", "b"]
 *   "a\n\n"  → ["a", ""]  (one empty line + terminator)
 *   ""       → []         (no lines at all)
 */
fun splitLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    var s = text
    if (s.endsWith("\r\n")) {
        s = s.substring(0, s.length - 2)
    } else if (s.endsWith("\n") || s.endsWith("\r")) {
        s = s.substring(0, s.length - 1)
    }
    if (s.isEmpty()) return listOf("")
    return s.split("\r\n", "\n", "\r")
}
