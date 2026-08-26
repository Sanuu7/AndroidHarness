package com.androidharness.app.workspace

import com.androidharness.app.data.db.ProjectEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Adding the same folder twice (via device browser, system picker, or a mix)
 * must not create duplicate workspace entries.
 */
class WorkspaceDedupeTest {

    private fun proj(id: String, kind: String, uri: String?) =
        ProjectEntity(id = id, name = id, kind = kind, uri = uri, lastUsedAt = 0)

    private val safResolver: (String) -> String? = { uri ->
        when {
            uri.contains("primary%3ADownload") -> "/storage/emulated/0/Download"
            uri.contains("primary%3AProjects") -> "/storage/emulated/0/Projects"
            else -> null
        }
    }

    @Test
    fun `shell keys ignore trailing slash`() {
        assertEquals(
            WorkspaceManager.dedupeKey(WorkspaceManager.KIND_SHELL, "/a/b"),
            WorkspaceManager.dedupeKey(WorkspaceManager.KIND_SHELL, "/a/b/"),
        )
    }

    @Test
    fun `picked folder maps to the same key as its real path`() {
        val viaPicker = WorkspaceManager.dedupeKey(
            WorkspaceManager.KIND_SAF,
            "content://com.android.externalstorage.documents/tree/primary%3ADownload",
            safResolver,
        )
        val viaBrowser = WorkspaceManager.dedupeKey(
            WorkspaceManager.KIND_SHELL,
            "/storage/emulated/0/Download",
        )
        assertEquals(viaBrowser, viaPicker)
    }

    @Test
    fun `different folders have different keys`() {
        assertNotEquals(
            WorkspaceManager.dedupeKey(WorkspaceManager.KIND_SHELL, "/a/b"),
            WorkspaceManager.dedupeKey(WorkspaceManager.KIND_SHELL, "/a/c"),
        )
    }

    @Test
    fun `unresolvable saf folders dedupe among themselves only`() {
        val uri = "content://com.android.providers.downloads.documents/tree/downloads"
        assertEquals(
            WorkspaceManager.dedupeKey(WorkspaceManager.KIND_SAF, uri, safResolver),
            WorkspaceManager.dedupeKey(WorkspaceManager.KIND_SAF, uri, safResolver),
        )
        assertNotEquals(
            WorkspaceManager.dedupeKey(WorkspaceManager.KIND_SAF, uri, safResolver),
            WorkspaceManager.dedupeKey(WorkspaceManager.KIND_SHELL, "/storage/emulated/0/Download"),
        )
    }

    @Test
    fun `app workspace never collides`() {
        assertNotEquals(
            WorkspaceManager.dedupeKey(WorkspaceManager.KIND_APP, null),
            WorkspaceManager.dedupeKey(WorkspaceManager.KIND_SHELL, "/a"),
        )
    }

    @Test
    fun `findDuplicate returns the existing project for the same folder`() {
        val projects = listOf(
            proj("p-app", WorkspaceManager.KIND_APP, null),
            proj("p-dl", WorkspaceManager.KIND_SAF, "content://x/tree/primary%3ADownload"),
        )
        val dup = WorkspaceManager.findDuplicate(
            projects,
            WorkspaceManager.KIND_SHELL,
            "/storage/emulated/0/Download",
            safResolver,
        )
        assertEquals("p-dl", dup?.id)
    }

    @Test
    fun `findDuplicate returns null when nothing matches`() {
        val projects = listOf(proj("p-dl", WorkspaceManager.KIND_SHELL, "/storage/emulated/0/Download"))
        assertNull(
            WorkspaceManager.findDuplicate(
                projects,
                WorkspaceManager.KIND_SHELL,
                "/storage/emulated/0/Other",
                safResolver,
            ),
        )
        assertNull(
            WorkspaceManager.findDuplicate(emptyList(), WorkspaceManager.KIND_SHELL, "/x", safResolver),
        )
    }

    @Test
    fun `findDuplicate skips the app workspace`() {
        val projects = listOf(proj("p-app", WorkspaceManager.KIND_APP, null))
        assertNull(
            WorkspaceManager.findDuplicate(projects, WorkspaceManager.KIND_SHELL, "/x", safResolver),
        )
    }
}
