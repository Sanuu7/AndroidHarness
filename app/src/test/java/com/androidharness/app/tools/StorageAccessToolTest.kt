package com.androidharness.app.tools

import com.androidharness.app.core.StorageAccessReceipt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StorageAccessToolTest {
    private val ctx = ToolContext(com.androidharness.app.workspace.FileFs(java.io.File(System.getProperty("java.io.tmpdir"))))

    @Test fun `shows a card with the reason and path when access is missing`() = runBlocking {
        val result = StorageAccessTool { false }.execute(buildJsonObject {
            put("reason", " Copy the photos into the project. ")
            put("path", "/sdcard/DCIM")
        }, ctx)
        assertTrue(result.ok)
        assertEquals(StorageAccessReceipt("Copy the photos into the project.", "/sdcard/DCIM"), result.storageAccess)
        assertTrue(result.output, "Do not retry" in result.output)
    }

    @Test fun `shows nothing when access is already granted`() = runBlocking {
        val result = StorageAccessTool { true }.execute(buildJsonObject { put("reason", "Read Downloads") }, ctx)
        assertNull(result.storageAccess)
        assertTrue(result.output, "already granted" in result.output)
    }

    @Test fun `a missing reason is refused and the path is optional`() = runBlocking {
        try { StorageAccessTool { false }.execute(buildJsonObject { put("path", "/sdcard") }, ctx); fail() }
        catch (e: ToolFailure) { assertTrue(e.message, "reason" in e.message.orEmpty()) }
        assertNull(StorageAccessTool { false }.execute(buildJsonObject { put("reason", "x") }, ctx).storageAccess?.path)
    }
}
