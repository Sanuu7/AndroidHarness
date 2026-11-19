package com.androidharness.app.tools

import com.androidharness.app.data.WorkspaceFileHistory
import com.androidharness.app.workspace.FileFs
import com.androidharness.app.workspace.UnboundedFileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Fixes for the issues an on-device tool audit reported. */
class ToolReportFixesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, if (v is Number) JsonPrimitive(v) else if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString())) }
    }

    @Test fun `delete_file in full access refuses the folder that holds the workspace`() = runBlocking {
        val parent = temp.newFolder("storage")
        val root = File(parent, "workspace").apply { mkdirs() }
        File(parent, "photo.jpg").writeText("keep me")
        val ctx = ToolContext(UnboundedFileFs(root))
        listOf("..", "../..").forEach { path ->
            val failure = runCatching { DeleteFileTool().execute(args("path" to path, "recursive" to true), ctx) }.exceptionOrNull()
            assertTrue(path, failure is ToolFailure)
            assertTrue(failure!!.message!!, failure.message!!.contains("Refusing to delete"))
        }
        assertTrue(File(parent, "photo.jpg").exists())
        // An ordinary sibling folder outside the workspace can still be deleted in full access.
        File(parent, "old").apply { mkdirs(); File(this, "x").writeText("x") }
        assertTrue(DeleteFileTool().execute(args("path" to "../old", "recursive" to true), ctx).ok)
    }

    @Test fun `storage and system roots are protected`() {
        assertTrue(protectedDeleteReason(File("/storage/emulated/0"), null)!!.contains("storage root"))
        assertTrue(protectedDeleteReason(File("/sdcard"), null) != null)
        assertTrue(protectedDeleteReason(File("/system"), null) != null)
        assertTrue(protectedDeleteReason(File("/storage/1234-ABCD"), null) != null)
        assertNull(protectedDeleteReason(File("/storage/emulated/0/Download/old"), File("/storage/emulated/0/Projects/app")))
    }

    @Test fun `create_dir rejects an empty path`() = runBlocking {
        val failure = runCatching { CreateDirTool().execute(args("path" to ""), ToolContext(FileFs(temp.newFolder()))) }.exceptionOrNull()
        assertTrue(failure!!.message!!, failure.message!!.contains("must not be empty"))
    }

    @Test fun `write_file counts characters and bytes the way people do`() = runBlocking {
        val result = WriteFileTool().execute(args("path" to "u.txt", "content" to "héllo 😀"), ToolContext(FileFs(temp.newFolder())))
        // 7 characters plus the added newline; é is 2 bytes and the emoji 4.
        assertTrue(result.output, result.output.contains("8 characters, 12 bytes"))
    }

    @Test fun `list_dir pages large directories`() = runBlocking {
        val root = temp.newFolder()
        repeat(250) { File(root, "f%03d".format(it)).writeText("") }
        val ctx = ToolContext(FileFs(root))
        val first = ListDirTool().execute(args("path" to "."), ctx).output
        assertEquals(201, first.lines().size)
        assertTrue(first, first.endsWith("[entries 1-200 of 250; next offset=201]"))
        val second = ListDirTool().execute(args("path" to ".", "offset" to 201), ctx).output
        assertTrue(second, second.startsWith("f200"))
        assertTrue(second, second.endsWith("[entries 201-250 of 250]"))
        assertFalse(ListDirTool().execute(args("path" to ".", "limit" to 300), ctx).output.contains("[entries"))
    }

    @Test fun `grep refuses an empty pattern`() = runBlocking {
        val failure = runCatching { GrepTool().execute(args("pattern" to ""), ToolContext(FileFs(temp.newFolder()))) }.exceptionOrNull()
        assertTrue(failure is ToolFailure)
    }

    @Test fun `a failed git command shows its exit code`() {
        val failed = buildGitResult(com.androidharness.app.data.env.ShellRunResult(
            128, false, "", "fatal: 'origin' does not appear to be a git repository", com.androidharness.app.data.env.ExecutionTier.APP_LINUX, null))
        assertFalse(failed.ok)
        assertTrue(failed.output, failed.output.endsWith("exit code: 128"))
        val ok = buildGitResult(com.androidharness.app.data.env.ShellRunResult(
            0, false, "done", "", com.androidharness.app.data.env.ExecutionTier.APP_LINUX, null))
        assertFalse(ok.output, ok.output.contains("exit code"))
    }

    @Test fun `file_restore explains unknown versions and hash mismatches`() = runBlocking {
        val root = temp.newFolder("ws")
        val fs = FileFs(root)
        val history = WorkspaceFileHistory(temp.newFolder("history"))
        fs.resolve("a.txt").writeText("old")
        val version = history.record("chat", fs, "a.txt", "written")!!
        fs.resolve("a.txt").writeText("new")

        val unknown = runCatching { history.restore("chat", fs, "a.txt", "00000000-0000") }.exceptionOrNull()!!.message!!
        assertTrue(unknown, unknown.contains("Unknown version"))
        assertFalse(unknown, unknown.contains("/"))

        val omitted = runCatching { history.restore("chat", fs, "a.txt", version.id) }.exceptionOrNull()!!.message!!
        assertTrue(omitted, omitted.contains("already exists"))

        val wrong = runCatching { history.restore("chat", fs, "a.txt", version.id, "deadbeef") }.exceptionOrNull()!!.message!!
        assertTrue(wrong, wrong.contains("does not match"))
        assertTrue(wrong, wrong.contains("deadbeef"))
        val current = WorkspaceFileHistory.hash("new".toByteArray())
        assertFalse("the full current hash is never handed over", wrong.contains(current))
    }

    @Test fun `http status lines get a reason phrase`() {
        assertEquals("Not Found", httpReason(404))
        assertEquals("I'm a teapot", httpReason(418))
        assertNull(httpReason(299))
        assertTrue(HTTP_TOOL_USER_AGENT.startsWith("AndroidHarness/"))
    }
}
