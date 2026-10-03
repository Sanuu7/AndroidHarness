package com.androidharness.app.data.env

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAccessTest {
    private val read = Manifest.permission.READ_EXTERNAL_STORAGE
    private val write = Manifest.permission.WRITE_EXTERNAL_STORAGE

    private fun legacyAccess(sdk: Int, permissions: Set<String>, legacy: Boolean = true) =
        StorageAccess.isGranted(
            sdkInt = sdk,
            allFilesAccess = { error("All files access does not exist before Android 11") },
            legacyStorage = {
                check(sdk == 29) { "Legacy-storage API does not exist before Android 10" }
                legacy
            },
            permissionGranted = permissions::contains,
        )

    @Test
    fun `Android 8 through 10 need a real read and write grant`() {
        for (sdk in 26..29) {
            assertFalse("SDK $sdk without permission", legacyAccess(sdk, emptySet()))
            assertFalse("SDK $sdk with only read permission", legacyAccess(sdk, setOf(read)))
            assertFalse("SDK $sdk with only write permission", legacyAccess(sdk, setOf(write)))
            assertTrue("SDK $sdk after granting storage", legacyAccess(sdk, setOf(read, write)))
        }
    }

    @Test
    fun `Android 9 does not call unavailable platform APIs`() {
        assertTrue(legacyAccess(28, setOf(read, write)))
        assertFalse(legacyAccess(28, emptySet()))
    }

    @Test
    fun `Android 10 scoped storage is not mistaken for access to project files`() {
        assertFalse(legacyAccess(29, setOf(read, write), legacy = false))
        assertTrue(legacyAccess(29, setOf(read, write), legacy = true))
    }

    @Test
    fun `revoking old storage permission is detected on the next check`() {
        val permissions = mutableSetOf(read, write)
        assertTrue(legacyAccess(28, permissions))
        permissions.remove(write)
        assertFalse(legacyAccess(28, permissions))
        permissions.add(write)
        assertTrue(legacyAccess(28, permissions))
    }

    @Test
    fun `Android 11 and later use only the special all files grant`() {
        for (sdk in listOf(30, 31, 32, 33, 34, 35, 36, 37)) {
            for (allowed in listOf(false, true)) {
                val actual = StorageAccess.isGranted(
                    sdkInt = sdk,
                    allFilesAccess = { allowed },
                    legacyStorage = { error("Modern Android must not depend on legacy storage") },
                    permissionGranted = { error("Old storage permissions do not unlock modern Android") },
                )
                if (allowed) assertTrue("SDK $sdk", actual) else assertFalse("SDK $sdk", actual)
            }
        }
    }

    @Test
    fun `runtime storage requests contain both permissions and stop at Android 10`() {
        for (sdk in 26..29) {
            assertArrayEquals(arrayOf(read, write), StorageAccess.runtimePermissions(sdk))
        }
        for (sdk in listOf(30, 33, 36, 37)) {
            assertTrue(StorageAccess.runtimePermissions(sdk).isEmpty())
        }
    }
}
