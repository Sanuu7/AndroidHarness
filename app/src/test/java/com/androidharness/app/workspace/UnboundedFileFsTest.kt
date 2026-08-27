package com.androidharness.app.workspace

import com.androidharness.app.tools.ToolFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Full access mode's open resolver: containment lifted, shell root intact,
 * ignore rules still applying on walks.
 */
class UnboundedFileFsTest {

    private fun tempRoot(): File = createTempDirectory("harness-unbounded").toFile()

    @Test
    fun `relative path still resolves inside workspace root`() {
        val root = tempRoot()
        try {
            File(root, "sub/a.txt").apply { parentFile.mkdirs(); writeText("inside") }
            val fs = UnboundedFileFs(root)
            assertEquals("inside", fs.resolve("sub/a.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `dotdot escape resolves to real location instead of throwing`() {
        val root = tempRoot()
        val outsideDir = createTempDirectory("harness-outside").toFile()
        try {
            val outside = File(outsideDir, "secret.txt")
            outside.writeText("outside content")

            // Plain FileFs refuses this; UnboundedFileFs must resolve it.
            assertThrows(ToolFailure::class.java) { FileFs(root).resolve("../secret.txt") }
            val fs = UnboundedFileFs(root)
            assertTrue(fs.resolve("../${outsideDir.name}/secret.txt").exists)
            assertEquals("outside content", fs.resolve("../${outsideDir.name}/secret.txt").readText())
        } finally {
            root.deleteRecursively()
            outsideDir.deleteRecursively()
        }
    }

    @Test
    fun `absolute path resolves through`() {
        val root = tempRoot()
        val outsideDir = createTempDirectory("harness-outside2").toFile()
        try {
            val outside = File(outsideDir, "data.bin")
            outside.writeText("abc")
            val fs = UnboundedFileFs(root)
            assertEquals("abc", fs.resolve(outside.absolutePath).readText())
        } finally {
            root.deleteRecursively()
            outsideDir.deleteRecursively()
        }
    }

    @Test
    fun `shell root and display stay the workspace`() {
        val root = tempRoot()
        try {
            val fs = UnboundedFileFs(root)
            assertEquals(root.canonicalPath, fs.shellRoot.canonicalPath)
            assertEquals(FileFs(root).displayPath, fs.displayPath)
            assertFalse(fs.isSaf)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `walk applies ignore rules and can traverse upward`() {
        val root = tempRoot()
        val outsideDir = createTempDirectory("harness-walkout").toFile()
        try {
            File(root, "in.txt").writeText("a")
            File(outsideDir, "node_modules/pkg.js").apply { parentFile.mkdirs(); writeText("junk") }
            File(outsideDir, "keep.txt").writeText("b")

            val fs = UnboundedFileFs(root)
            val names = fs.walk("../${outsideDir.name}").map { it.name }.toList()
            assertTrue(names.contains("keep.txt"))
            assertFalse(names.any { it == "pkg.js" || it == "node_modules" })
        } finally {
            root.deleteRecursively()
            outsideDir.deleteRecursively()
        }
    }

    @Test
    fun `walk throws for missing path`() {
        val root = tempRoot()
        try {
            assertThrows(ToolFailure::class.java) { UnboundedFileFs(root).walk("./nope") }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `relpath of external node renders without crashing`() {
        val root = tempRoot()
        val outsideDir = createTempDirectory("harness-rel").toFile()
        try {
            val f = File(outsideDir, "x.txt"); f.writeText("n")
            val node = UnboundedFileFs(root).resolve(f.absolutePath)
            // Outside the root it renders as an absolute-ish path, never a
            // relativize crash; on Unix-style roots it would start with "/".
            assertTrue(node.relPath.contains("x.txt"))
            assertTrue(!node.relPath.startsWith(".."))
        } finally {
            root.deleteRecursively()
            outsideDir.deleteRecursively()
        }
    }
}
