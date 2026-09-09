package com.androidharness.app.tools

import com.androidharness.app.core.splitLines
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

class FileToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ctx() = ToolContext(FileFs(tmp.root))
    private fun file(path: String) = tmp.root.resolve(path)

    private suspend fun run(tool: Tool, vararg args: Pair<String, String>): ToolResult =
        tool.execute(
            buildJsonObject {
                args.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            },
            ctx(),
        )

    /** Runs a tool expecting a ToolFailure; returns the failure message. */
    private suspend fun runExpectingFailure(tool: Tool, vararg args: Pair<String, String>): String =
        try {
            run(tool, *args).let { r ->
                if (!r.ok) r.output else error("expected ToolFailure, got success: ${r.output}")
            }
        } catch (e: ToolFailure) {
            e.message ?: ""
        }

    @Test
    fun `malformed globs return a clean error`() = runBlocking {
        for (pattern in listOf("*.h[tm][", "[", "{a,b", "**/*[")) {
            val message = runExpectingFailure(SearchFilesTool(), "pattern" to pattern)
            assertEquals("Invalid glob pattern: \"$pattern\". Check brackets, braces, and escapes.", message)
        }
        val message = runExpectingFailure(GrepTool(), "pattern" to "text", "include" to "[")
        assertTrue(message, message.startsWith("Invalid glob pattern:"))
    }

    @Test
    fun `grep fails fast with a budget error on a pathological regex`() = runBlocking {
        // The on-device wedge was `^(a+)+$` against 36 bytes (security QA,
        // 2026-09-06): exponential on ART, but desktop JDKs short-circuit it,
        // so the test uses a shape that backtracks hard on ANY engine and a
        // tiny budget to prove the wrapper trips instead of hanging.
        file("evil.txt").writeText("a".repeat(500) + "\n")
        val started = System.currentTimeMillis()
        val message = runExpectingFailure(
            GrepTool(regexStepBudget = 100_000),
            "pattern" to ".*.*x",
        )
        assertTrue(message, message.contains("step budget"))
        // Budgeted, so this returns in well under a second; the assert only
        // catches a regression to the unbounded hang.
        assertTrue(System.currentTimeMillis() - started < 10_000)
    }

    @Test
    fun `grep rejects extreme regex quantifiers before matching`() = runBlocking {
        val msg1 = runExpectingFailure(GrepTool(), "pattern" to "(z{1000}){1900}")
        assertTrue("Expected rejection of extreme quantifier, got: $msg1", msg1.contains("quantifier") || msg1.contains("repetition"))

        val msg2 = runExpectingFailure(GrepTool(), "pattern" to "a{5000}")
        assertTrue("Expected rejection of large quantifier, got: $msg2", msg2.contains("quantifier") || msg2.contains("repetition"))
    }

    @Test
    fun `read_file rejects non-positive limit or offset`() = runBlocking {
        file("data.txt").writeText("line1\nline2\n")
        val msg1 = runExpectingFailure(ReadFileTool(), "path" to "data.txt", "limit" to "0")
        assertTrue("Expected limit > 0 error, got: $msg1", msg1.contains("limit must be greater than 0"))

        val msg2 = runExpectingFailure(ReadFileTool(), "path" to "data.txt", "offset" to "-1")
        assertTrue("Expected offset > 0 error, got: $msg2", msg2.contains("offset must be greater than 0"))
    }

    @Test
    fun `valid character classes and recursive globs still match`() = runBlocking {
        file("index.htm").writeText("text")
        file("nested").mkdirs()
        file("nested/index.htm").writeText("text")
        val result = run(SearchFilesTool(), "pattern" to "**/*.ht[ml]")
        assertEquals(setOf("index.htm", "nested/index.htm"), result.output.lines().toSet())
    }

    // --- splitLines (POSIX line semantics) -----------------------------------

    @Test
    fun `splitLines treats trailing newline as terminator`() {
        assertEquals(listOf("a", "b"), splitLines("a\nb\n"))
        assertEquals(listOf("a", "b"), splitLines("a\nb"))
        assertEquals(listOf("a", ""), splitLines("a\n\n")) // one empty line + terminator
        assertEquals(emptyList<String>(), splitLines(""))
        assertEquals(listOf(""), splitLines("\n")) // a single empty line
        assertEquals(listOf("a", "", "b"), splitLines("a\n\nb"))
    }

    // --- write_file trailing newline ------------------------------------------

    @Test
    fun `write_file appends trailing newline to non-empty content`() = runBlocking {
        val r = WriteFileTool().execute(
            buildJsonObject {
                put("path", JsonPrimitive("a.txt"))
                put("content", JsonPrimitive("hello"))
            },
            ctx(),
        )
        assertTrue(r.output, r.ok)
        assertEquals("hello\n", file("a.txt").readText())
        assertTrue(r.output.contains("trailing newline added"))
    }

    @Test
    fun `write_file keeps existing trailing newline and does not double it`() = runBlocking {
        WriteFileTool().execute(
            buildJsonObject {
                put("path", JsonPrimitive("a.txt"))
                put("content", JsonPrimitive("hello\n"))
            },
            ctx(),
        )
        assertEquals("hello\n", file("a.txt").readText())
    }

    @Test
    fun `write_file leaves empty content empty`() = runBlocking {
        WriteFileTool().execute(
            buildJsonObject {
                put("path", JsonPrimitive("a.txt"))
                put("content", JsonPrimitive(""))
            },
            ctx(),
        )
        assertEquals("", file("a.txt").readText())
    }

    // --- CRLF & BOM fidelity -----------------------------------------------------

    @Test
    fun `write_file preserves carriage returns byte for byte`() = runBlocking {
        val content = "line1\r\nline2\r\nline3\r\n"
        WriteFileTool().execute(
            buildJsonObject {
                put("path", JsonPrimitive("crlf.txt"))
                put("content", JsonPrimitive(content))
            },
            ctx(),
        )
        val bytes = file("crlf.txt").readBytes()
        assertEquals(content.length, bytes.size)
        assertEquals(content, String(bytes, Charsets.UTF_8))
        assertTrue(bytes.contains(0x0D.toByte()))
    }

    @Test
    fun `read_file strips a UTF-8 BOM from line 1`() = runBlocking {
        file("bom.txt").writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hello".toByteArray())
        val r = ReadFileTool().execute(
            buildJsonObject { put("path", JsonPrimitive("bom.txt")) },
            ctx(),
        )
        assertTrue(r.output, r.ok)
        assertEquals("1\thello", r.output)
    }

    @Test
    fun `read_file without BOM is unchanged`() = runBlocking {
        file("plain.txt").writeText("plain")
        val r = ReadFileTool().execute(
            buildJsonObject { put("path", JsonPrimitive("plain.txt")) },
            ctx(),
        )
        assertEquals("1\tplain", r.output)
    }

    // --- read_file empty file --------------------------------------------------

    @Test
    fun `read_file reports empty file instead of a phantom line 1`() = runBlocking {
        file("empty.txt").writeText("")
        val r = ReadFileTool().execute(
            buildJsonObject { put("path", JsonPrimitive("empty.txt")) },
            ctx(),
        )
        assertTrue(r.ok)
        assertEquals("(empty file)", r.output)
    }

    @Test
    fun `read_file does not report a phantom trailing empty line`() = runBlocking {
        file("two.txt").writeText("one\ntwo\n")
        val r = ReadFileTool().execute(
            buildJsonObject { put("path", JsonPrimitive("two.txt")) },
            ctx(),
        )
        assertTrue(r.ok)
        assertEquals("1\tone\n2\ttwo", r.output)
    }

    // --- create_dir guards ------------------------------------------------------

    @Test
    fun `create_dir fails when a file occupies the path`() = runBlocking {
        file("occupied.txt").writeText("data")
        val msg = runExpectingFailure(CreateDirTool(), "path" to "occupied.txt")
        assertTrue(msg, msg.contains("already exists and is a file"))
        assertEquals("data", file("occupied.txt").readText()) // untouched
    }

    @Test
    fun `create_dir on existing directory is an explicit no-op`() = runBlocking {
        file("dir").mkdirs()
        val r = run(CreateDirTool(), "path" to "dir")
        assertTrue(r.output, r.ok)
        assertTrue(r.output.contains("already exists"))
    }

    @Test
    fun `create_dir creates nested directories`() = runBlocking {
        val r = run(CreateDirTool(), "path" to "a/b/c")
        assertTrue(r.output, r.ok)
        assertTrue(file("a/b/c").isDirectory)
    }

    // --- delete_file guards ------------------------------------------------------

    @Test
    fun `delete_file refuses the workspace root`() = runBlocking {
        for (path in listOf(".", "", "./", "sub/..")) {
            val msg = runExpectingFailure(DeleteFileTool(), "path" to path)
            assertTrue("'$path': $msg", msg.contains("workspace root"))
        }
        assertTrue(tmp.root.exists()) // nothing was deleted
    }

    @Test
    fun `delete_file refuses non-empty directory without recursive`() = runBlocking {
        file("proj").apply { mkdirs() }
        file("proj/inner.txt").writeText("x")
        val msg = runExpectingFailure(DeleteFileTool(), "path" to "proj")
        assertTrue(msg, msg.contains("recursive=true"))
        assertTrue(file("proj/inner.txt").exists()) // untouched
    }

    @Test
    fun `delete_file deletes non-empty directory with recursive`() = runBlocking {
        file("proj").apply { mkdirs() }
        file("proj/inner.txt").writeText("x")
        val r = run(DeleteFileTool(), "path" to "proj", "recursive" to "true")
        assertTrue(r.output, r.ok)
        assertFalse(file("proj").exists())
    }

    @Test
    fun `delete_file deletes empty directory without recursive`() = runBlocking {
        file("emptydir").mkdirs()
        val r = run(DeleteFileTool(), "path" to "emptydir")
        assertTrue(r.output, r.ok)
        assertFalse(file("emptydir").exists())
    }

    @Test
    fun `delete_file deletes plain files as before`() = runBlocking {
        file("f.txt").writeText("x")
        val r = run(DeleteFileTool(), "path" to "f.txt")
        assertTrue(r.output, r.ok)
        assertFalse(file("f.txt").exists())
    }

    // --- sandbox escape still blocked ---------------------------------------------

    @Test
    fun `file tools still block path escapes`() = runBlocking {
        val msg = runExpectingFailure(WriteFileTool(), "path" to "../escape.txt", "content" to "nope")
        assertTrue(msg, msg.contains("outside the workspace"))
        assertFalse(tmp.root.parentFile?.resolve("escape.txt")?.exists() ?: false)
    }

    // --- file_info metadata & newline check ---------------------------------------

    @Test
    fun `file_info reports byte size, line count, and trailing newline`() = runBlocking {
        file("has_nl.txt").writeText("line1\nline2\n")
        val r1 = run(FileInfoTool(), "path" to "has_nl.txt")
        assertTrue(r1.ok)
        assertTrue(r1.output.contains("type: file"))
        assertTrue(r1.output.contains("line_count: 2"))
        assertTrue(r1.output.contains("trailing_newline: present"))

        file("no_nl.txt").writeText("line1\nline2")
        val r2 = run(FileInfoTool(), "path" to "no_nl.txt")
        assertTrue(r2.ok)
        assertTrue(r2.output.contains("type: file"))
        assertTrue(r2.output.contains("line_count: 2"))
        assertTrue(r2.output.contains("trailing_newline: none"))

        file("folder").mkdirs()
        val r3 = run(FileInfoTool(), "path" to "folder")
        assertTrue(r3.ok)
        assertTrue(r3.output.contains("type: directory"))
    }

    // --- binary & empty file checks ----------------------------------------------

    @Test
    fun `read_file refuses binary files`() = runBlocking {
        val binBytes = ByteArray(4096) { idx -> if (idx % 10 == 0) 0.toByte() else (idx % 256).toByte() }
        file("random.bin").writeBytes(binBytes)
        val msg = runExpectingFailure(ReadFileTool(), "path" to "random.bin")
        assertTrue(msg, msg.contains("binary"))
    }

    @Test
    fun `file_info reports explicit empty marker for 0-byte file`() = runBlocking {
        file("empty.txt").writeText("")
        val r = run(FileInfoTool(), "path" to "empty.txt")
        assertTrue(r.ok)
        assertTrue(r.output.contains("size_bytes: 0"))
        assertTrue(r.output.contains("is_empty: true"))
        assertTrue(r.output.contains("line_count: 0"))
    }

    @Test
    fun `file_info reports binary file without line count`() = runBlocking {
        val binBytes = ByteArray(1024) { (it % 256).toByte() }
        file("test.bin").writeBytes(binBytes)
        val r = run(FileInfoTool(), "path" to "test.bin")
        assertTrue(r.ok)
        assertTrue(r.output.contains("is_binary: true"))
    }

    @Test
    fun `file_info streams large single-line file promptly`() = runBlocking {
        val largeFile = file("large.txt")
        val size = 5_000_000
        val bytes = ByteArray(size) { 'x'.code.toByte() }
        largeFile.writeBytes(bytes)
        val start = System.currentTimeMillis()
        val r = run(FileInfoTool(), "path" to "large.txt")
        val elapsed = System.currentTimeMillis() - start
        assertTrue("Took ${elapsed}ms, should be under 1000ms", elapsed < 1000)
        assertTrue(r.ok)
        assertTrue(r.output.contains("is_empty: false"))
        assertTrue(r.output.contains("line_count: 1"))
        assertTrue(r.output.contains("trailing_newline: none"))
    }

    @Test
    fun `grep skips binary files`() = runBlocking {
        val binBytes = ByteArray(512) { 0.toByte() }
        file("sample.bin").writeBytes(binBytes)
        file("sample.txt").writeText("hello pattern here\n")
        val r = run(GrepTool(), "pattern" to "pattern")
        assertTrue(r.ok)
        assertTrue(r.output.contains("sample.txt"))
        assertFalse(r.output.contains("sample.bin"))
    }

    @Test
    fun `read_file clamps single huge line within MAX_READ_LINE_CHARS`() = runBlocking {
        val hugeLine = "x".repeat(300_000)
        file("huge_line.txt").writeText(hugeLine)
        val r = ReadFileTool().execute(
            buildJsonObject { put("path", JsonPrimitive("huge_line.txt")) },
            ctx(),
        )
        assertTrue(r.ok)
        assertTrue("Output should contain line truncation marker", r.output.contains("[line truncated at 10000 chars]"))
        assertTrue("Output length should be bounded around 10k chars, got ${r.output.length}", r.output.length < 15_000)
    }

    @Test
    fun `clampForStorage bounds oversized text safely below CursorWindow ceiling`() {
        val huge = "x".repeat(500_000)
        val clamped = com.androidharness.app.data.clampForStorage(huge)
        assertTrue(clamped.contains("[truncated for storage safety]"))
        assertTrue("Clamped length must be below 260_000, got ${clamped.length}", clamped.length < 260_000)

        val small = "regular message text"
        assertEquals(small, com.androidharness.app.data.clampForStorage(small))
    }

    @Test
    fun `move_file refuses moving directory into its parent or ancestor`() = runBlocking {
        file("stress2/sub2/deep").mkdirs()
        file("stress2/sub2/deep/test.txt").writeText("content")

        val msg = runExpectingFailure(MoveFileTool(), "source" to "stress2/sub2", "destination" to "stress2")
        assertTrue("Expected ancestor/parent move rejection, got: $msg", msg.contains("ancestor") || msg.contains("exists"))
    }

    @Test
    fun `move_file refuses moving into itself or subdirectory`() = runBlocking {
        file("stress2/sub2").mkdirs()
        val msg = runExpectingFailure(MoveFileTool(), "source" to "stress2", "destination" to "stress2/sub2")
        assertTrue("Expected self/sub move rejection, got: $msg", msg.contains("subdirectory") || msg.contains("itself"))
    }

    @Test
    fun `move_file renames within same directory`() = runBlocking {
        file("a/old.txt").apply { parentFile?.mkdirs() }.writeText("hello")
        val r = run(MoveFileTool(), "source" to "a/old.txt", "destination" to "a/new.txt")
        assertTrue(r.ok)
        assertFalse(file("a/old.txt").exists())
        assertEquals("hello", file("a/new.txt").readText())
    }

    @Test
    fun `grep rejects contradictory include pattern on explicit file`() = runBlocking {
        file("foo.kt").writeText("val x = 1\n")
        val msg = runExpectingFailure(GrepTool(), "path" to "foo.kt", "pattern" to "val", "include" to "*.txt")
        assertTrue("Expected contradiction error, got: $msg", msg.contains("excludes it"))
    }
}
