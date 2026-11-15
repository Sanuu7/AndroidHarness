package com.androidharness.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppWorkspaceResetTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `bytes add every file below the workspace root`() {
        val root = folder.newFolder("workspace")
        folder.newFile("workspace/notes.md").writeText("12345")
        val nested = folder.newFolder("workspace/app/src")
        folder.newFile("workspace/app/src/Main.kt").writeText("1234567890")
        assertTrue(nested.exists())
        assertEquals(15L, AppWorkspaceReset.bytes(root))
    }

    @Test fun `a missing workspace counts as zero bytes`() {
        assertEquals(0L, AppWorkspaceReset.bytes(folder.newFolder("gone").also { it.delete() }))
    }

    @Test fun `clear empties the folder but keeps it usable`() {
        val root = folder.newFolder("workspace")
        folder.newFile("workspace/notes.md").writeText("x")
        folder.newFolder("workspace/src")
        folder.newFile("workspace/src/Main.kt").writeText("y")

        AppWorkspaceReset.clear(root)

        assertTrue(root.isDirectory)
        assertEquals(0, root.listFiles()?.size)
        assertEquals(0L, AppWorkspaceReset.bytes(root))
    }
}
