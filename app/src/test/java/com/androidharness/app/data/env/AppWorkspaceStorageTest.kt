package com.androidharness.app.data.env

import com.androidharness.app.data.env.PathClassifier.Region
import org.junit.Assert.assertEquals
import org.junit.Test

class AppWorkspaceStorageTest {
    private val internal = "/data/user/0/com.androidharness.app"
    private val external = "/storage/emulated/0/Android/data/com.androidharness.app/files"

    @Test
    fun `default workspace uses the app shell without broad storage or Shizuku`() {
        assertEquals(Region.APP_DATA, PathClassifier.regionOf("$external/workspace", internal, external))
        assertEquals(Region.APP_DATA, PathClassifier.regionOf(external, internal, external))
        assertEquals(Region.APP_DATA, PathClassifier.regionOf("$external/workspace/src", internal, external))
    }

    @Test
    fun `own internal data remains private when external storage is unavailable`() {
        assertEquals(Region.APP_DATA, PathClassifier.regionOf("$internal/files", internal))
        assertEquals(Region.SHARED_STORAGE, PathClassifier.regionOf("/storage/emulated/0/Projects", internal))
    }

    @Test
    fun `private access does not extend to neighboring directories or other apps`() {
        for (path in listOf(
            "${external}-other/workspace",
            "/storage/emulated/0/Android/data/com.other.app/files/workspace",
            "/storage/emulated/0/Projects",
        )) {
            assertEquals(Region.SHARED_STORAGE, PathClassifier.regionOf(path, internal, external))
        }
        assertEquals(Region.SYSTEM, PathClassifier.regionOf("/data/local/tmp", internal, external))
    }

    @Test
    fun `own app workspace on an SD card also needs no broad storage grant`() {
        val sdRoot = "/storage/1234-ABCD/Android/data/com.androidharness.app/files"
        assertEquals(Region.APP_DATA, PathClassifier.regionOf("$sdRoot/workspace", internal, sdRoot))
    }
}
