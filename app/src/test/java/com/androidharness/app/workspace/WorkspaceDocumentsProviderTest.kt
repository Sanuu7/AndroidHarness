package com.androidharness.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException

class WorkspaceDocumentsProviderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun namesCannotLeaveTheirFolder() {
        assertEquals("a_b", WorkspaceDocumentsProvider.safeName("a/b"))
        assertEquals("_etc_passwd", WorkspaceDocumentsProvider.safeName("/etc/passwd"))
        listOf("", "  ", ".", "..").forEach { name ->
            assertThrows(FileNotFoundException::class.java) { WorkspaceDocumentsProvider.safeName(name) }
        }
    }

    @Test fun takenNamesGetANumberBeforeTheExtension() {
        val dir = temp.root
        assertEquals("run.sh", WorkspaceDocumentsProvider.freeName(dir, "run.sh").name)
        dir.resolve("run.sh").createNewFile()
        assertEquals("run (1).sh", WorkspaceDocumentsProvider.freeName(dir, "run.sh").name)
        dir.resolve("run (1).sh").createNewFile()
        assertEquals("run (2).sh", WorkspaceDocumentsProvider.freeName(dir, "run.sh").name)
        dir.resolve("notes").mkdir()
        assertEquals("notes (1)", WorkspaceDocumentsProvider.freeName(dir, "notes").name)
        dir.resolve(".env").createNewFile()
        assertEquals(".env (1)", WorkspaceDocumentsProvider.freeName(dir, ".env").name)
    }
}
