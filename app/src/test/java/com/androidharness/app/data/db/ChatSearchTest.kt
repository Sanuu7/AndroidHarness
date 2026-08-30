package com.androidharness.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchTest {

    @Test
    fun `tokens split on whitespace and dedupe`() {
        assertEquals(listOf("foo", "bar"), ChatSearch.tokens("  foo  bar   foo "))
        assertEquals(emptyList<String>(), ChatSearch.tokens("   "))
    }

    @Test
    fun `fts match wraps tokens as required prefixes`() {
        assertEquals("\"gradle\"* \"build\"*", ChatSearch.ftsMatchQuery("gradle build"))
    }

    @Test
    fun `fts match keeps punctuation literal and doubles quotes`() {
        assertEquals("\"f(x)\"*", ChatSearch.ftsMatchQuery("f(x)"))
        assertEquals("\"\"\"hi\"\"\"*", ChatSearch.ftsMatchQuery("\"hi\""))
    }

    @Test
    fun `fts match of blank query is null`() {
        assertNull(ChatSearch.ftsMatchQuery("   "))
    }

    @Test
    fun `like pattern escapes wildcards and backslash`() {
        assertEquals("%50\\%\\_off\\\\%", ChatSearch.likePattern("50%_off\\"))
        assertEquals("%gradle%", ChatSearch.likePattern(" gradle "))
        assertEquals("%", ChatSearch.likePattern("   "))
    }

    @Test
    fun `highlight needles follow the mode`() {
        assertEquals(listOf("gradle build"), ChatSearch.highlightNeedles("gradle build", fuzzy = true))
        assertEquals(listOf("gradle", "build"), ChatSearch.highlightNeedles("gradle build", fuzzy = false))
        assertEquals(emptyList<String>(), ChatSearch.highlightNeedles("  ", fuzzy = true))
    }

    @Test
    fun `small text gets no ellipsis and marks the needle`() {
        val s = ChatSearch.snippet("hello world", listOf("world"))
        assertEquals("hello world", s.text)
        assertEquals(listOf(6..10), s.ranges)
    }

    @Test
    fun `long text window centers on first hit with ellipses`() {
        val text = "a".repeat(100) + "NEEDLE" + "b".repeat(100)
        val s = ChatSearch.snippet(text, listOf("needle"), maxLen = 50)
        assertEquals(52, s.text.length)
        assertTrue(s.text.startsWith("…"))
        assertTrue(s.text.endsWith("…"))
        // Needle lands at flat index 100, window starts at 100 - 50/3 = 84.
        assertEquals(listOf(100 - 84 + 1 + 0..100 - 84 + 1 + 5), s.ranges)
        assertEquals("NEEDLE", s.text.substring(s.ranges[0].first, s.ranges[0].last + 1))
    }

    @Test
    fun `newlines collapse so hits stay on one line`() {
        val s = ChatSearch.snippet("line1\nneedle here", listOf("needle"))
        assertEquals("line1 needle here", s.text)
        assertEquals(listOf(6..11), s.ranges)
    }

    @Test
    fun `overlapping needle occurrences merge into one range`() {
        val s = ChatSearch.snippet("aaa bbb aaabbb", listOf("aaa", "bbb"))
        assertEquals("aaa bbb aaabbb", s.text)
        assertEquals(listOf(0..2, 4..6, 8..13), s.ranges)
    }

    @Test
    fun `miss yields a plain window`() {
        val s = ChatSearch.snippet("nothing to see here".repeat(20), listOf("zzz"), maxLen = 40)
        assertEquals(41, s.text.length)
        assertTrue(s.ranges.isEmpty())
    }
}
