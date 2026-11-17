package com.androidharness.app.data

import com.androidharness.app.workspace.FileFs
import com.androidharness.app.workspace.UnboundedFileFs
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceFileHistoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `saved source survives store recreation deletion and restores exact bytes`() = runBlocking {
        val root = temp.newFolder("workspace")
        val directory = temp.newFolder("history")
        val fs = FileFs(root)
        val expected = "<html>saved</html>\r\n".toByteArray()
        fs.resolve("site/index.html").writeBytes(expected)
        val version = WorkspaceFileHistory(directory).record("chat", fs, "site/index.html", "written")!!
        root.resolve("site/index.html").delete()
        val restoredStore = WorkspaceFileHistory(directory)
        assertEquals(listOf("site/index.html"), restoredStore.missingFiles("chat", fs))
        restoredStore.restore("chat", fs, "site/index.html", version.id)
        assertArrayEquals(expected, root.resolve("site/index.html").readBytes())
        assertTrue(restoredStore.missingFiles("chat", fs).isEmpty())
    }

    @Test fun `restore refuses changed targets other chats roots and paths`() = runBlocking {
        val root = temp.newFolder("workspace")
        val fs = FileFs(root)
        val history = WorkspaceFileHistory(temp.newFolder("history"))
        fs.resolve("code.js").writeText("old")
        val version = history.record("chat", fs, "code.js", "written")!!
        fs.resolve("code.js").writeText("new")
        assertTrue(runCatching { history.restore("chat", fs, "code.js", version.id) }.isFailure)
        assertTrue(runCatching { history.restore("other", fs, "code.js", version.id) }.isFailure)
        assertTrue(runCatching { history.restore("chat", FileFs(temp.newFolder("other-root")), "code.js", version.id) }.isFailure)
        assertTrue(runCatching { history.restore("chat", fs, "other.js", version.id) }.isFailure)
        assertTrue(runCatching { history.restore("chat", fs, "../outside", version.id) }.isFailure)
        assertEquals("new", root.resolve("code.js").readText())
        history.restore("chat", fs, "code.js", version.id, WorkspaceFileHistory.hash("new".toByteArray()))
        assertEquals("old", root.resolve("code.js").readText())
        assertTrue(history.versions("chat", fs, "code.js").any { it.sha256 == WorkspaceFileHistory.hash("new".toByteArray()) })
    }

    @Test fun `recorded deletions are not reported as unexpected loss and copies remain recoverable`() = runBlocking {
        val fs = FileFs(temp.newFolder("workspace"))
        val history = WorkspaceFileHistory(temp.newFolder("history"))
        fs.resolve("site.js").writeText("saved")
        val first = history.record("chat", fs, "site.js", "write")!!
        fs.resolve("site.js").delete()
        history.record("chat", fs, "site.js", "after delete", recordMissing = true)
        assertTrue(history.missingFiles("chat", fs).isEmpty())
        assertTrue(history.versions("chat", fs, "site.js").first().deleted)
        history.restore("chat", fs, "site.js", first.id)
        assertEquals("saved", fs.resolve("site.js").readText())
    }

    @Test fun `corrupted saved bytes never overwrite current work`() = runBlocking {
        val fs = FileFs(temp.newFolder("workspace"))
        val directory = temp.newFolder("history")
        val history = WorkspaceFileHistory(directory)
        fs.resolve("base.css").writeText("saved")
        val first = history.record("chat", fs, "base.css", "write")!!
        File(directory, "${first.id}.gz").writeText("broken")
        assertTrue(runCatching { history.restore("chat", fs, "base.css", first.id, first.sha256) }.isFailure)
        assertEquals("saved", fs.resolve("base.css").readText())
    }

    @Test fun `observing a missing file does not disguise unexpected loss as an intentional deletion`() = runBlocking {
        val fs = FileFs(temp.newFolder("workspace"))
        val history = WorkspaceFileHistory(temp.newFolder("history"))
        fs.resolve("site.js").writeText("saved")
        history.record("chat", fs, "site.js", "write")
        fs.resolve("site.js").delete()
        history.record("chat", fs, "site.js", "before edit")
        history.record("chat", fs, "site.js", "after failed edit")
        assertEquals(listOf("site.js"), history.missingFiles("chat", fs))
    }

    @Test fun `full access writes outside the workspace do not break the missing file scan`() = runBlocking {
        val root = temp.newFolder("workspace")
        val outside = File(temp.newFolder("downloads"), "example.md").apply { writeText("report") }
        val history = WorkspaceFileHistory(temp.newFolder("history"))
        history.record("chat", UnboundedFileFs(root), outside.absolutePath, "write")!!
        val fs = FileFs(root)
        fs.resolve("kept.js").writeText("saved")
        history.record("chat", fs, "kept.js", "write")
        fs.resolve("kept.js").delete()
        assertEquals(listOf("kept.js"), history.missingFiles("chat", fs))
    }

    @Test fun `oversized compressed versions are bounded before replacing current work`() = runBlocking {
        val fs = FileFs(temp.newFolder("workspace"))
        val directory = temp.newFolder("history")
        val history = WorkspaceFileHistory(directory)
        fs.resolve("site.js").writeText("saved")
        val version = history.record("chat", fs, "site.js", "write")!!
        java.util.zip.GZIPOutputStream(File(directory, "${version.id}.gz").outputStream()).use {
            it.write(ByteArray(128 * 1024))
        }
        val error = runCatching { history.restore("chat", fs, "site.js", version.id, version.sha256) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("size limit"))
        assertEquals("saved", fs.resolve("site.js").readText())
    }
}
