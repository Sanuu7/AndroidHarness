package com.androidharness.app.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `parseUnified parses unified output with accurate line numbers`() = runBlocking {
        val old = "line 1\nline 2\nline 3\nline 4"
        val new = "line 1\nline 2 modified\nline 4\nline 5"
        val diffText = Diff.unified(old, new, "test.kt")
        
        val parsed = Diff.parseUnified(diffText)
        assertEquals("test.kt", parsed.newPath)
        assertEquals(1, parsed.hunks.size)
        
        val hunk = parsed.hunks.first()
        val lines = hunk.lines
        
        // Check line 1 (context)
        assertEquals(DiffLineType.CONTEXT, lines[0].type)
        assertEquals(1, lines[0].oldNum)
        assertEquals(1, lines[0].newNum)
        assertEquals("line 1", lines[0].text)

        // Check line 2 (remove old, add new)
        val removeLine = lines.firstOrNull { it.type == DiffLineType.REMOVE }
        val addLine = lines.firstOrNull { it.type == DiffLineType.ADD }
        assertNotNull(removeLine)
        assertNotNull(addLine)
        assertEquals(2, removeLine?.oldNum)
        assertEquals(null, removeLine?.newNum)
        assertEquals("line 2", removeLine?.text)

        assertEquals(null, addLine?.oldNum)
        assertEquals(2, addLine?.newNum)
        assertEquals("line 2 modified", addLine?.text)
    }

    @Test
    fun `parseUnified parses git style hunks and internal markers`() {
        val gitDiff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -10,3 +10,4 @@
             context line
            -removed line
            +added line 1
            +added line 2
             context 2
              @@ … @@
             after skip
        """.trimIndent()

        val parsed = Diff.parseUnified(gitDiff)
        assertEquals("foo.txt", parsed.oldPath)
        assertEquals("foo.txt", parsed.newPath)
        assertEquals(1, parsed.hunks.size)
        val lines = parsed.hunks[0].lines

        assertEquals(DiffLineType.CONTEXT, lines[0].type)
        assertEquals(10, lines[0].oldNum)
        assertEquals(10, lines[0].newNum)

        assertEquals(DiffLineType.REMOVE, lines[1].type)
        assertEquals(11, lines[1].oldNum)
        assertEquals(null, lines[1].newNum)

        assertEquals(DiffLineType.ADD, lines[2].type)
        assertEquals(null, lines[2].oldNum)
        assertEquals(11, lines[2].newNum)

        assertEquals(DiffLineType.ADD, lines[3].type)
        assertEquals(null, lines[3].oldNum)
        assertEquals(12, lines[3].newNum)

        assertEquals(DiffLineType.HEADER, lines[5].type)
        assertEquals("@@ … @@", lines[5].text)
    }
}
