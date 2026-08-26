package com.androidharness.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryNotesTest {

    @Test
    fun `blank memory is omitted`() {
        assertNull(MemoryNotes.load(null))
        assertNull(MemoryNotes.load("   \n"))
    }

    @Test
    fun `short memory is kept whole`() {
        assertEquals("user prefers kotlin", MemoryNotes.load("user prefers kotlin"))
    }

    @Test
    fun `oversized memory keeps head and tail`() {
        val raw = "HEAD-" + "m".repeat(20_000) + "-TAIL"
        val loaded = MemoryNotes.load(raw, maxChars = 800)
        requireNotNull(loaded)
        assertTrue(loaded.startsWith("HEAD-"))
        assertTrue(loaded.endsWith("-TAIL"))
        assertTrue(loaded.contains("truncated"))
        assertTrue(loaded.length < raw.length)
        assertTrue(loaded.length <= 800 + 80) // prefix/suffix markers
    }

    @Test
    fun `append then cap keeps the newest notes`() {
        val next = MemoryNotes.write(
            existing = "old\n",
            content = "newest-preference",
            mode = "append",
            maxChars = 24,
        )
        assertTrue(next.contains("newest-preference"))
        assertTrue(next.length <= 24)
    }

    @Test
    fun `replace overwrites`() {
        val next = MemoryNotes.write("old", "new memory", "replace", maxChars = 8_000)
        assertEquals("new memory\n", next)
    }
}
