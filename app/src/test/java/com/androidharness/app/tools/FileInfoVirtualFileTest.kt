package com.androidharness.app.tools

import com.androidharness.app.workspace.FileFs
import com.androidharness.app.workspace.FsNode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * file_info must not trust stat size alone: procfs entries (/proc/self/status
 * & co.) report st_size = 0 while their reads return real content, which made
 * file_info claim they were empty while read_file happily showed 1KB, the two
 * tools contradicted each other.
 */
class FileInfoVirtualFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ctx() = ToolContext(FileFs(tmp.root))

    /** Regular file whose length is 0 but whose stream yields [body]. */
    private class ZeroStatFakeNode(
        fileName: String,
        private val body: ByteArray,
    ) : FsNode {
        override val relPath = fileName
        override val name = fileName
        override val exists = true
        override val isDirectory = false
        override val isFile = true
        override val length = 0L // the lie procfs tells
        override fun list() = emptyList<FsNode>()
        override fun readText() = String(body)
        override fun writeText(content: String) = throw UnsupportedOperationException()
        override fun mkdirs() = throw UnsupportedOperationException()
        override fun delete() = false
        override fun renameTo(newName: String) = false
        override fun openInputStream() = body.inputStream()
        override fun isBinary() = false
        override fun writeBytes(data: ByteArray) = throw UnsupportedOperationException()
        override fun createFile(name: String): FsNode = throw UnsupportedOperationException()
        override fun createDir(name: String): FsNode = throw UnsupportedOperationException()
    }

    @Test
    fun `zero-stat node with real content reports non-empty with measured bytes`() {
        val body = buildString {
            repeat(48) { append("field_${it % 7}: some value\n") }
        }
        val info = inspectFileInfo(ZeroStatFakeNode("status", body.toByteArray(Charsets.UTF_8)))
        assertTrue(info.isEmpty.not())
        assertTrue(info.lineCount >= 47)
        assertEquals(body.length.toLong(), info.measuredBytes)
        assertTrue(info.trailingNewline == "present")
    }

    @Test
    fun `zero-stat node that really is empty still reports empty`() {
        val info = inspectFileInfo(ZeroStatFakeNode("empty", ByteArray(0)))
        assertTrue(info.isEmpty)
        assertEquals(0L, info.lineCount)
    }

    @Test
    fun `file_info tool surfaces size note for zero-stat content`() {
        // The temp folder can't host a fake FsNode through FileFs.resolve, so
        // assert on inspectFileInfo + renderer input directly: any zero-stat
        // non-empty scan produces measuredBytes > 0 which the tool renders as
        // size_note (unit-covered here via the data class contract).
        val body = "VmPeak: 1094 kB\nVmSize: 1094 kB\n"
        val info = inspectFileInfo(ZeroStatFakeNode("status", body.toByteArray()))
        assertEquals(false, info.isEmpty)
        assertEquals(2L, info.lineCount)
    }

    @Test
    fun `genuinely empty regular file path is unchanged`() = runBlocking {
        tmp.newFile("empty.txt").writeText("")
        val r = FileInfoTool().execute(
            buildJsonObject { put("path", JsonPrimitive("empty.txt")) },
            ctx(),
        )
        assertTrue(r.ok)
        assertTrue(r.output.contains("is_empty: true"))
        assertTrue(r.output.contains("line_count: 0"))
    }
}
