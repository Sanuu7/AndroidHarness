package com.androidharness.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class PickedFolderAccessTest {
    @Test
    fun `writable device folder retains direct file and shell access`() {
        val root = createTempDirectory("harness-picked").toFile()
        try {
            val direct = WorkspaceManager.directFolderOrNull(root.absolutePath)
            assertEquals(root, direct)
            val fs = FileFs(requireNotNull(direct))
            fs.resolve("src/main.txt").writeText("project file")
            assertEquals("project file", fs.resolve("src/main.txt").readText())
            assertEquals(root, fs.shellRoot)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `read only picked folder must retain picker access instead of a writable path`() {
        val root = createTempDirectory("harness-picked-readonly").toFile()
        try {
            assertTrue(root.setWritable(false, false))
            assertNull(WorkspaceManager.directFolderOrNull(root.absolutePath))
        } finally {
            root.setWritable(true, true)
            root.deleteRecursively()
        }
    }

    @Test
    fun `unreadable directory must retain picker access`() {
        val root = createTempDirectory("harness-picked-denied").toFile()
        try {
            assertTrue(root.setReadable(false, false))
            assertNull(WorkspaceManager.directFolderOrNull(root.absolutePath))
        } finally {
            root.setReadable(true, true)
            root.deleteRecursively()
        }
    }

    @Test
    fun `cloud folder missing path and files are never upgraded to directories`() {
        val root = createTempDirectory("harness-picked-missing").toFile()
        try {
            assertNull(WorkspaceManager.directFolderOrNull(null))
            assertNull(WorkspaceManager.directFolderOrNull(File(root, "missing").absolutePath))
            val file = File(root, "file.txt").apply { writeText("file") }
            assertNull(WorkspaceManager.directFolderOrNull(file.absolutePath))
        } finally {
            root.deleteRecursively()
        }
    }
}
