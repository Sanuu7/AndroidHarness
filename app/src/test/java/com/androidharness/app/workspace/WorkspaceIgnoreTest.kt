package com.androidharness.app.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceIgnoreTest {

    @Test
    fun `default junk directories are ignored`() {
        for (name in listOf(
            ".git", "node_modules", "build", ".gradle", ".idea",
            "__pycache__", ".next", "dist", "out", ".cache",
            "venv", ".venv", "target",
        )) {
            assertTrue("$name should be ignored", WorkspaceIgnore.isIgnoredDir(name))
        }
    }

    @Test
    fun `source directories are not ignored`() {
        for (name in listOf("app", "src", "java", "kotlin", "res", "lib")) {
            assertFalse("$name should be searchable", WorkspaceIgnore.isIgnoredDir(name))
        }
    }

    @Test
    fun `any ignored path segment skips the file`() {
        assertTrue(WorkspaceIgnore.shouldSkip("app/build/outputs/apk/debug.apk"))
        assertTrue(WorkspaceIgnore.shouldSkip("node_modules/okhttp/index.js"))
        assertTrue(WorkspaceIgnore.shouldSkip(".git/HEAD"))
        assertFalse(WorkspaceIgnore.shouldSkip("app/src/main/java/Foo.kt"))
        assertFalse(WorkspaceIgnore.shouldSkip("build.gradle.kts"))
    }

    @Test
    fun `starting at an ignored dir is still allowed to be entered`() {
        // Walks that begin AT node_modules should still run — the user asked.
        assertFalse(WorkspaceIgnore.shouldSkipEnter(startRelPath = "node_modules", dirName = "node_modules"))
        assertTrue(WorkspaceIgnore.shouldSkipEnter(startRelPath = ".", dirName = "node_modules"))
    }
}
