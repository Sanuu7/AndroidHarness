package com.androidharness.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DiffTest {

    @Test
    fun `lineCounts counts pure insertion`() {
        // Insert x between a and b.
        val (added, removed) = Diff.lineCounts("a\nb", "a\nx\nb")
        assertEquals(1, added)
        assertEquals(0, removed)
    }

    @Test
    fun `lineCounts counts replacement as remove plus add`() {
        val (added, removed) = Diff.lineCounts("a\nb\nc", "a\nb\nd")
        assertEquals(1, added)
        assertEquals(1, removed)
    }

    @Test
    fun `lineCounts handles empty sides`() {
        assertEquals((3) to 0, Diff.lineCounts("", "a\nb\nc"))
        assertEquals(0 to (3), Diff.lineCounts("a\nb\nc", ""))
        assertEquals(0 to 0, Diff.lineCounts("same", "same"))
    }
}
