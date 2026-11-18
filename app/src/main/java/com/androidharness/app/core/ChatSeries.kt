package com.androidharness.app.core

/**
 * Numbered names for a project's new chats. A pattern such as "ALPHA" names chats
 * "ALPHA 1", "ALPHA 2", and so on; a "#" in the pattern marks where the number goes,
 * so "Sprint #: fixes" gives "Sprint 1: fixes".
 *
 * The next number is one past the highest already used, so deleting a chat never
 * produces a duplicate name and the series keeps counting up.
 */
object ChatSeries {
    const val MAX_PATTERN_CHARS = 60
    private const val MARK = '#'

    /** The stored form of what the user typed, or null for no series. */
    fun normalize(pattern: String?): String? = pattern?.trim()?.take(MAX_PATTERN_CHARS)?.takeIf { it.isNotEmpty() }

    fun title(pattern: String, number: Int): String =
        if (MARK in pattern) pattern.replaceFirst(MARK.toString(), number.toString()) else "$pattern $number"

    /** The number in [title] if it belongs to this series. */
    fun numberOf(pattern: String, title: String): Int? {
        val (prefix, suffix) = if (MARK in pattern) pattern.substringBefore(MARK) to pattern.substringAfter(MARK) else "$pattern " to ""
        val regex = Regex("^" + Regex.escape(prefix) + "(\\d{1,9})" + Regex.escape(suffix) + "$", RegexOption.IGNORE_CASE)
        return regex.matchEntire(title.trim())?.groupValues?.get(1)?.toIntOrNull()
    }

    fun next(pattern: String, existingTitles: Iterable<String>): String =
        title(pattern, (existingTitles.mapNotNull { numberOf(pattern, it) }.maxOrNull() ?: 0) + 1)
}
