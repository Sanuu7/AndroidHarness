package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for the case-insensitive mount collision heuristics. */
class CaseCollisionTest {

    @Test
    fun `shared storage mounts are treated as case insensitive`() {
        assertTrue(CaseCollision.insensitiveMount("/storage/emulated/0/AndroidHarness", false))
        assertTrue(CaseCollision.insensitiveMount("/sdcard/ws", false))
    }

    @Test
    fun `SAF workspaces are always case insensitive`() {
        assertTrue(CaseCollision.insensitiveMount(null, true))
    }

    @Test
    fun `app-private ext4 storage stays case sensitive`() {
        assertFalse(CaseCollision.insensitiveMount("/data/user/0/com.androidharness.app/files/ws", false))
    }

    @Test
    fun `unknown roots default to no warning`() {
        // null path (no shell root) must not warn rather than warn everywhere.
        assertFalse(CaseCollision.insensitiveMount(null, false))
    }

    @Test
    fun `siblings differing only by case are reported`() {
        val hits = CaseCollision.siblingsMatchingOnlyByCase(
            "CaseTest",
            listOf("casetest", "other.txt", "CaseTest2", "CASETEST"),
        )
        assertEquals(listOf("casetest", "CASETEST"), hits)
    }

    @Test
    fun `identical name is not a collision`() {
        val hits = CaseCollision.siblingsMatchingOnlyByCase("README.md", listOf("README.md", "readme.md"))
        assertEquals(listOf("readme.md"), hits)
    }

    @Test
    fun `warning names the colliding sibling and the mount behavior`() {
        val w = CaseCollision.warning("CaseTest", listOf("casetest"))
        assertTrue(w!!.contains("casetest"))
        assertTrue(w.contains("CaseTest"))
        assertTrue(w.contains("[warning:"))
    }

    @Test
    fun `no collisions means no warning`() {
        assertNull(CaseCollision.warning("unique.txt", emptyList()))
    }
}
