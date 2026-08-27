package com.androidharness.app.tools

import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PatchToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ctx() = ToolContext(FileFs(tmp.root))
    private fun file(path: String) = tmp.root.resolve(path)
    private fun write(path: String, content: String) =
        file(path).apply { parentFile?.mkdirs() }.writeText(content)

    private fun apply(patch: String): ToolResult =
        runBlocking {
            ApplyPatchTool().execute(
                buildJsonObject { put("patch", JsonPrimitive(patch)) },
                ctx(),
            )
        }

    /** Applies a patch expecting a ToolFailure; returns the failure message. */
    private fun failingApply(patch: String): String =
        try {
            apply(patch).let { r ->
                if (!r.ok) r.output else error("expected ToolFailure, got success: ${r.output}")
            }
        } catch (e: ToolFailure) {
            e.message ?: ""
        }

    // --- files WITHOUT a trailing newline (the stress-test repro) -----------

    @Test
    fun `patch modifies last line of file without trailing newline`() {
        write("f.txt", "one\ntwo") // no trailing \n
        val r = apply(
            """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,2 +1,2 @@
             one
            -two
            +TWO
            """.trimIndent(),
        )
        assertTrue(r.output, r.ok)
        assertEquals("one\nTWO", file("f.txt").readText())
    }

    @Test
    fun `git-style patch with no-newline markers on file without trailing newline`() {
        write("f.txt", "one\ntwo") // no trailing \n
        val r = apply(
            """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,2 +1,2 @@
             one
            -two
            ${"\\ No newline at end of file"}
            +TWO
            ${"\\ No newline at end of file"}
            """.trimIndent(),
        )
        assertTrue(r.output, r.ok)
        assertEquals("one\nTWO", file("f.txt").readText())
    }

    @Test
    fun `git-style patch appends line to file without trailing newline`() {
        write("f.txt", "one\ntwo") // no trailing \n
        val r = apply(
            """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,2 +1,3 @@
             one
            -two
            ${"\\ No newline at end of file"}
            +two
            +three
            """.trimIndent(),
        )
        assertTrue(r.output, r.ok)
        assertEquals("one\ntwo\nthree", file("f.txt").readText())
    }

    @Test
    fun `context hunk anchored at last line of file without trailing newline`() {
        write("f.txt", "one\ntwo") // no trailing \n
        val r = apply(
            """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,2 +1,2 @@
             one
             two
            """.trimIndent(),
        )
        assertTrue(r.output, r.ok)
        assertEquals("one\ntwo", file("f.txt").readText())
    }

    @Test
    fun `hand-written patch that models the final newline as an extra context line`() {
        // Models frequently emit the file's trailing newline as one more
        // context line. Against a file WITHOUT that trailing newline the
        // extra empty context line must not fail the match.
        write("f.txt", "one\ntwo") // no trailing \n
        val r = apply(
            "--- a/f.txt\n+++ b/f.txt\n@@ -1,3 +1,3 @@\n one\n-two\n+TWO\n \n",
        )
        assertTrue(r.output, r.ok)
        assertEquals("one\nTWO", file("f.txt").readText())
    }

    // --- files WITH a trailing newline --------------------------------------

    @Test
    fun `patch modifies last line of file with trailing newline`() {
        write("f.txt", "one\ntwo\n")
        val r = apply(
            """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,2 +1,2 @@
             one
            -two
            +TWO
            """.trimIndent(),
        )
        assertTrue(r.output, r.ok)
        // exactly one trailing newline - no doubled newline
        assertEquals("one\nTWO\n", file("f.txt").readText())
    }

    @Test
    fun `trailing newline state is preserved when patching mid-file`() {
        write("f.txt", "one\ntwo\nthree") // no trailing \n
        val r = apply(
            """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,3 +1,3 @@
            -one
            +ONE
             two
             three
            """.trimIndent(),
        )
        assertTrue(r.output, r.ok)
        assertEquals("ONE\ntwo\nthree", file("f.txt").readText())
    }

    // --- create / delete -----------------------------------------------------

    @Test
    fun `create new file via dev-null header`() {
        val r = apply(
            """
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1,2 @@
            +hello
            +world
            """.trimIndent(),
        )
        assertTrue(r.output, r.ok)
        assertEquals("hello\nworld\n", file("new.txt").readText())
    }

    @Test
    fun `create new file without trailing newline via no-newline marker`() {
        val r = apply(
            """
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1,1 @@
            +hello
            ${"\\ No newline at end of file"}
            """.trimIndent(),
        )
        assertTrue(r.output, r.ok)
        assertEquals("hello", file("new.txt").readText())
    }

    @Test
    fun `delete file via dev-null header`() {
        write("gone.txt", "bye\n")
        val r = apply(
            """
            --- a/gone.txt
            +++ /dev/null
            @@ -1,1 +0,0 @@
            -bye
            """.trimIndent(),
        )
        assertTrue(r.output, r.ok)
        assertFalse(file("gone.txt").exists())
    }

    // --- multi-hunk + fuzziness ----------------------------------------------

    @Test
    fun `multi-hunk patch applies with position shift`() {
        write("f.txt", "a\nb\nc\nd\ne\nf\ng\nh\n")
        val r = apply(
            """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,2 +1,2 @@
            -a
            +A
             b
            @@ -7,2 +7,2 @@
             g
            -h
            +H
            """.trimIndent(),
        )
        assertTrue(r.output, r.ok)
        assertEquals("A\nb\nc\nd\ne\nf\ng\nH\n", file("f.txt").readText())
    }

    @Test
    fun `hunk mismatch error mentions re-reading the file`() {
        write("f.txt", "one\ntwo\n")
        val msg = failingApply(
            """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,2 +1,2 @@
             nope
            -two
            +TWO
            """.trimIndent(),
        )
        assertTrue(msg, msg.contains("does not match the file contents"))
    }

    // --- BUG: atomicity across hunks (validate-then-commit) ------------------

    @Test
    fun `failing second hunk leaves file untouched and names the bad hunk`() {
        // The original repro: hunk 1 is valid, hunk 2 has garbage context that
        // matches nothing. The old code applied hunk 1 and reported success.
        write(
            "m.txt",
            "duplicate line\nunique line here\nSECOND HUNK SHOULD FAIL\n",
        )
        val msg = failingApply(
            """
            --- a/m.txt
            +++ b/m.txt
            @@ -1,3 +1,2 @@
            -duplicate line
             unique line here
            @@ -3,1 +2,1 @@
             garbage context that matches nothing
            -SECOND HUNK SHOULD FAIL
            +REPLACED
            """.trimIndent(),
        )
        assertTrue(msg, msg.contains("NOT applied"))
        assertTrue(msg, msg.contains("no hunk was written"))
        assertTrue(msg, msg.contains("Hunk 2"))
        // the valid first hunk must NOT have been applied
        assertEquals(
            "duplicate line\nunique line here\nSECOND HUNK SHOULD FAIL\n",
            file("m.txt").readText(),
        )
    }

    @Test
    fun `multi-hunk patch is atomic with no partial application`() {
        write("f.txt", "a\nb\nc\nd\n")
        val msg = failingApply(
            """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,1 +1,1 @@
            -a
            +A
            @@ -3,1 +3,1 @@
             nope
            -c
            +C
            """.trimIndent(),
        )
        assertTrue(msg, msg.contains("Hunk 2"))
        assertEquals("a\nb\nc\nd\n", file("f.txt").readText())
    }

    @Test
    fun `failing hunk in second file blocks the first file write too`() {
        write("good.txt", "one\n")
        write("bad.txt", "x\n")
        val msg = failingApply(
            """
            --- a/good.txt
            +++ b/good.txt
            @@ -1,1 +1,1 @@
            -one
            +ONE
            --- a/bad.txt
            +++ b/bad.txt
            @@ -1,1 +1,1 @@
             context that does not exist
            -x
            +y
            """.trimIndent(),
        )
        assertTrue(msg, msg.contains("NOT applied"))
        assertTrue(msg, msg.contains("bad.txt"))
        assertEquals("one\n", file("good.txt").readText())
    }

    @Test
    fun `dry run validates without writing`() {
        write("f.txt", "one\ntwo\n")
        val r = runBlocking {
            ApplyPatchTool().execute(
                buildJsonObject {
                    put("patch", JsonPrimitive(
                        "--- a/f.txt\n+++ b/f.txt\n@@ -1,2 +1,2 @@\n one\n-two\n+TWO",
                    ))
                    put("dry_run", JsonPrimitive(true))
                },
                ctx(),
            )
        }
        assertTrue(r.output, r.ok)
        assertTrue(r.output, r.output.contains("dry run"))
        assertEquals("one\ntwo\n", file("f.txt").readText())
    }

    @Test
    fun `dry run on bad hunk fails and writes nothing`() {
        write("f.txt", "one\ntwo\n")
        val msg = failingApply(
            """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,2 +1,2 @@
             missing context
            -two
            +TWO
            """.trimIndent(),
        )
        assertTrue(msg, msg.contains("NOT applied"))
        assertEquals("one\ntwo\n", file("f.txt").readText())
    }

    @Test
    fun `successful multi-hunk patch reports hunk count and applies all`() {
        write("f.txt", "a\nb\nc\nd\ne\nf\ng\nh\n")
        val r = apply(
            """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,2 +1,2 @@
            -a
            +A
             b
            @@ -7,2 +7,2 @@
             g
            -h
            +H
            """.trimIndent(),
        )
        assertTrue(r.output, r.ok)
        assertTrue(r.output, r.output.contains("2 hunk(s) applied"))
        assertEquals("A\nb\nc\nd\ne\nf\ng\nH\n", file("f.txt").readText())
    }

    @Test
    fun `hunk mismatch on newline-less file explains the trailing newline state`() {
        write("f.txt", "one\ntwo") // no trailing \n
        val msg = failingApply(
            """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,2 +1,2 @@
             nope
            -two
            +TWO
            """.trimIndent(),
        )
        assertTrue(msg, msg.contains("does not match the file contents"))
        assertTrue(msg, msg.contains("does not end with a newline"))
    }
}
