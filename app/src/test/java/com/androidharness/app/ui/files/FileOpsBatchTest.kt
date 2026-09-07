package com.androidharness.app.ui.files

import com.androidharness.app.workspace.FileFs
import com.androidharness.app.workspace.FsNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory

class FileOpsBatchTest {
    @Test
    fun `batch copy keeps existing destinations and copies every selected file`() = runBlocking {
        val root = createTempDirectory().toFile()
        try {
            File(root, "a.txt").writeText("new")
            File(root, "b.txt").writeText("second")
            File(root, "dest").mkdir()
            File(root, "dest/a.txt").writeText("original")
            val fs = FileFs(root)
            val result = FileOps.batch(listOf(fs.resolve("a.txt"), fs.resolve("b.txt"))) {
                FileOps.copy(it, fs.resolve("dest"), it.name, preserveAll = true)
            }
            assertEquals(2, result.completed.size)
            assertTrue(result.failures.isEmpty())
            assertEquals("original", File(root, "dest/a.txt").readText())
            assertEquals("new", File(root, "dest/a (2).txt").readText())
            assertEquals("second", File(root, "dest/b.txt").readText())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `move preserves nested ignored folders before removing source`() = runBlocking {
        val root = createTempDirectory().toFile()
        try {
            File(root, "src/project/node_modules/pkg/code.js").apply { parentFile.mkdirs(); writeText("keep") }
            File(root, "dest").mkdir()
            val fs = FileFs(root)
            FileOps.move(fs.resolve("src"), fs.resolve("dest"), "src")
            assertEquals("keep", File(root, "dest/src/project/node_modules/pkg/code.js").readText())
            assertFalse(File(root, "src").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `batch failures are reported while independent items finish`() = runBlocking {
        val root = createTempDirectory().toFile()
        try {
            File(root, "keep.txt").writeText("keep")
            File(root, "delete.txt").writeText("delete")
            val fs = FileFs(root)
            val failing = object : FsNode by fs.resolve("keep.txt") {
                override fun delete(): Boolean = false
            }
            val result = FileOps.batch(listOf(failing, fs.resolve("delete.txt"))) {
                check(it.delete()) { "Delete failed" }
            }
            assertEquals(setOf("delete.txt"), result.completed)
            assertEquals(setOf("keep.txt"), result.failures.keys)
            assertTrue(File(root, "keep.txt").exists())
            assertFalse(File(root, "delete.txt").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `cannot copy a folder into itself or its descendants`() = runBlocking {
        val root = createTempDirectory().toFile()
        try {
            File(root, "src/child").mkdirs()
            val fs = FileFs(root)
            for (target in listOf("src", "src/child")) {
                val result = FileOps.batch(listOf(fs.resolve("src"))) {
                    FileOps.copy(it, fs.resolve(target), it.name, preserveAll = true)
                }
                assertEquals(1, result.failures.size)
                assertTrue(result.completed.isEmpty())
            }
            assertFalse(File(root, "src/child/src").exists())
            val rootCopy = FileOps.batch(listOf(fs.resolve("."))) {
                FileOps.copy(it, fs.resolve("src"), "root", preserveAll = true)
            }
            assertEquals(1, rootCopy.failures.size)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `shared archive keeps folder structure and binary file bytes`() {
        val root = createTempDirectory().toFile()
        try {
            File(root, "folder/empty").mkdirs()
            val bytes = byteArrayOf(0, 1, 2, -1)
            File(root, "folder/data.bin").writeBytes(bytes)
            File(root, "note.txt").writeText("hello")
            val fs = FileFs(root)
            val output = ByteArrayOutputStream()
            FileOps.writeShareZip(listOf(fs.resolve("folder"), fs.resolve("note.txt")), output)
            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                }
            }
            assertTrue("folder/empty/" in entries)
            assertArrayEquals(bytes, entries["folder/data.bin"])
            assertEquals("hello", entries["note.txt"]!!.toString(Charsets.UTF_8))
        } finally { root.deleteRecursively() }
    }
}
