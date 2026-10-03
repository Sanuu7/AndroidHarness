package com.androidharness.app.workspace

import com.androidharness.app.data.db.ProjectEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafPathResolverTest {
    private val primary = "/storage/emulated/0"
    private val downloads = "com.android.providers.downloads.documents"
    private val external = "com.android.externalstorage.documents"

    @Test
    fun `Android 9 Downloads raw folder resolves to its actual path`() {
        assertEquals("$primary/Download/My project", resolve(downloads, "raw:$primary/Download/My project"))
    }

    @Test
    fun `raw picker folder reuses an existing device workspace`() {
        val path = "$primary/Download/HarnessSafProbe"
        val project = ProjectEntity("existing", "HarnessSafProbe", WorkspaceManager.KIND_SHELL, path, 1)
        val existing = WorkspaceManager.findDuplicate(
            listOf(project), WorkspaceManager.KIND_SAF, "raw:$path",
            resolveSaf = { resolve(downloads, it) },
        )
        assertEquals(project, existing)
    }

    @Test
    fun `Downloads root and primary folder mappings stay compatible`() {
        assertEquals("$primary/Download", resolve(downloads, "downloads"))
        assertEquals("$primary/Download/project", resolve(downloads, "primary:Download/project"))
        assertEquals("$primary/Documents/project", resolve(external, "primary:Documents/project"))
        assertEquals("$primary/Documents", resolve(external, "home:Documents"))
    }

    @Test
    fun `SD card folder mapping stays compatible`() {
        assertEquals("/storage/0F1C-2A3D/project", resolve(external, "0F1C-2A3D:project"))
    }

    @Test
    fun `relative raw paths and cloud providers keep picker access only`() {
        assertNull(resolve(downloads, "raw:Download/project"))
        assertNull(resolve(downloads, "raw:"))
        assertNull(resolve("example.cloud.documents", "raw:$primary/Download/project"))
        assertNull(resolve(null, "primary:Download/project"))
    }

    private fun resolve(authority: String?, documentId: String): String? =
        SafPathResolver.resolveDocumentId(authority, documentId, primary)
}
