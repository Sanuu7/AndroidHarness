package com.androidharness.app.repomap

import com.androidharness.app.workspace.FileFs
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RepoMapCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `same filename and length in different projects have independent symbols`() = runBlocking {
        val alpha = tmp.newFolder("alpha")
        val bravo = tmp.newFolder("bravo")
        File(alpha, "Demo.kt").writeText("class Alpha {\n}\n")
        File(bravo, "Demo.kt").writeText("class Bravo {\n}\n")
        val cache = RepoMapCache()
        assertTrue(cache.getMap(FileFs(alpha)).contains("Alpha"))
        val b = cache.getMap(FileFs(bravo))
        assertTrue(b.contains("Bravo")); assertFalse(b.contains("Alpha"))
        File(alpha, "Demo.kt").writeText("class Delta {\n}\n")
        cache.invalidate("Demo.kt")
        assertTrue(cache.getMap(FileFs(alpha)).contains("Delta"))
        assertTrue(cache.getMap(FileFs(bravo)).contains("Bravo"))
    }
}
