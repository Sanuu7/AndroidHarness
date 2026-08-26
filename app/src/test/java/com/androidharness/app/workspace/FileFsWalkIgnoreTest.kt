package com.androidharness.app.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class FileFsWalkIgnoreTest {

    @Test
    fun `walk skips node_modules and build but not source`() {
        val root = createTempDirectory("harness-walk").toFile()
        try {
            File(root, "app/src/Main.kt").apply { parentFile.mkdirs(); writeText("ok") }
            File(root, "node_modules/pkg/index.js").apply { parentFile.mkdirs(); writeText("junk") }
            File(root, "build/outputs/apk.apk").apply { parentFile.mkdirs(); writeText("apk") }
            File(root, "README.md").writeText("hi")

            val found = FileFs(root).walk(".").filter { it.isFile }.map { it.relPath }.toList()
            assertTrue(found.any { it.endsWith("Main.kt") })
            assertTrue(found.any { it.endsWith("README.md") })
            assertFalse(found.any { it.contains("node_modules") })
            assertFalse(found.any { it.contains("build/") || it.endsWith("apk.apk") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `walk starting inside node_modules still returns those files`() {
        val root = createTempDirectory("harness-walk-nm").toFile()
        try {
            File(root, "node_modules/pkg/index.js").apply { parentFile.mkdirs(); writeText("junk") }
            val found = FileFs(root).walk("node_modules").filter { it.isFile }.map { it.relPath }.toList()
            assertTrue(found.any { it.endsWith("index.js") })
        } finally {
            root.deleteRecursively()
        }
    }
}
