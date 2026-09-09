package com.androidharness.app.core

import org.junit.Assert.*
import org.junit.Test

class SelectiveUndoTest {
    @Test fun `undo one section keeps other edits`() {
        val old = "one\ntwo\nthree\nfour\n"
        val now = "ONE\ntwo\nTHREE\nfour\n"
        val chunks = Diff.undoSections(old, now)
        assertEquals(2, chunks.size)
        assertEquals("one\ntwo\nTHREE\nfour\n", Diff.undoSection(now, now, chunks.first()))
        assertEquals("ONE\ntwo\nthree\nfour\n", Diff.undoSection(now, now, chunks.last()))
    }
    @Test fun `preserves CRLF and final newline differences`() {
        val old = "a\r\nb\r\n"
        val now = "a\r\nB"
        val chunk = Diff.undoSections(old, now).single()
        assertEquals(old, Diff.undoSection(now, now, chunk))
    }
    @Test fun `restores deleted text at exact offset`() {
        val old = "a\nb\nc\n"
        val now = "a\nc\n"
        assertEquals(old, Diff.undoSection(now, now, Diff.undoSections(old, now).single()))
    }
    @Test fun `empty file addition and removal`() {
        assertEquals("", Diff.undoSection("new", "new", Diff.undoSections("", "new").single()))
        assertEquals("old", Diff.undoSection("", "", Diff.undoSections("old", "").single()))
        assertTrue(Diff.undoSections("", "").isEmpty())
    }
    @Test fun `refuses external changes since preview`() {
        val section = Diff.undoSections("old", "new").single()
        assertThrows(IllegalArgumentException::class.java) { Diff.undoSection("external", "new", section) }
    }
    @Test fun `never generates writable chunks from truncated files`() {
        assertThrows(IllegalArgumentException::class.java) { Diff.undoSections("a\n".repeat(3001), "b") }
    }
    @Test fun `duplicate lines restore only selected occurrence`() {
        val old = "x\nx\nx\n"
        val now = "x\ny\nx\n"
        var restored = now
        for (section in Diff.undoSections(old, now).asReversed()) restored = Diff.undoSection(restored, restored, section)
        assertEquals(old, restored)
    }
    @Test fun `undoing all chunks in reverse yields exact original`() {
        val inputs = listOf("", "a", "a\n", "a\r\nb\n", "\n\n", "x\nx\ny", "a\nb\nc\nd\n")
        for (old in inputs) for (new in inputs) {
            var current = new
            for (section in Diff.undoSections(old, new).asReversed()) current = Diff.undoSection(current, current, section)
            assertEquals("$old -> $new", old, current)
        }
    }
}
