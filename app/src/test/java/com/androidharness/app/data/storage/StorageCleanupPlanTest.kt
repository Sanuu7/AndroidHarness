package com.androidharness.app.data.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class StorageCleanupPlanTest {
    @get:Rule val temp = TemporaryFolder()

    private fun item(role: StorageRole, key: String = role.name, bytes: Long? = 100, name: String = "") =
        StorageItem(role, key, name, "/data/$key", bytes = bytes)

    @Test fun `every role is either offered or kept, never both`() {
        StorageRole.entries.forEach { role ->
            val offered = CleanupPlanner.kind(role) != null
            val kept = CleanupPlanner.keep(role) != null
            assertTrue("$role must be offered or kept", offered || kept)
            assertFalse("$role is both offered and kept", offered && kept)
        }
    }

    @Test fun `9router, the Linux core, projects and the app are never offered`() {
        val protectedRoles = listOf(StorageRole.ROUTER, StorageRole.LINUX_OTHER, StorageRole.LINUX_HOME, StorageRole.LINUX_SHARED_FILES,
            StorageRole.PACKAGE_RECORDS, StorageRole.LINUX_STAGING, StorageRole.WORKSPACE, StorageRole.APP_PACKAGE,
            StorageRole.UNDO_CHECKPOINTS, StorageRole.SKILL_BUNDLED, StorageRole.SKILL_PROJECT)
        val plan = CleanupPlanner.plan(StorageReport(protectedRoles.map { item(it) }), emptyMap())
        assertTrue(plan.entries.isEmpty())
        assertEquals(KeepReason.ROUTER, plan.kept.first { it.item?.role == StorageRole.ROUTER }.reason)
        // Even a caller that passes a kept id gets nothing to clean.
        assertTrue(plan.chosen(protectedRoles.map { item(it).id }.toSet()).isEmpty())
        assertEquals(0L, plan.bytes(setOf(item(StorageRole.ROUTER).id)))
    }

    @Test fun `secrets are always listed as kept`() {
        val plan = CleanupPlanner.plan(StorageReport(listOf(item(StorageRole.SETTINGS))), emptyMap())
        assertTrue(plan.kept.any { it.reason == KeepReason.SECRETS && it.item == null })
        assertEquals(CleanupKind.SETTINGS, plan.entries.single().kind)
    }

    @Test fun `user data kinds are offered and the destructive ones ask again`() {
        val report = StorageReport(listOf(item(StorageRole.WEB_DATA), item(StorageRole.CHAT_IMAGES), item(StorageRole.CACHE_DEB),
            item(StorageRole.SCRATCH), item(StorageRole.CACHE_NPM), item(StorageRole.MODEL, "m1", name = "Small"),
            item(StorageRole.CHAT_DATABASE), item(StorageRole.CANVAS), item(StorageRole.SETTINGS),
            item(StorageRole.PLUGIN, "p1", name = "Plugin"), item(StorageRole.SKILL_USER, "s1", name = "Skill")))
        val plan = CleanupPlanner.plan(report, emptyMap())
        assertEquals(11, plan.entries.size)
        val all = plan.entries.map { it.id }.toSet()
        assertEquals(setOf(CleanupKind.MODEL, CleanupKind.CHAT_HISTORY, CleanupKind.SETTINGS, CleanupKind.CHAT_IMAGES, CleanupKind.SKILL),
            plan.needsConfirmation(all).map { it.kind }.toSet())
        assertEquals(1100L, plan.bytes(all))
    }

    @Test fun `default selection only picks caches and downloads`() {
        val plan = CleanupPlanner.plan(StorageReport(listOf(item(StorageRole.CACHE_PIP), item(StorageRole.CACHE_DEB),
            item(StorageRole.CHAT_DATABASE), item(StorageRole.SETTINGS), item(StorageRole.WEB_DATA))), emptyMap())
        assertEquals(setOf(item(StorageRole.CACHE_PIP).id, item(StorageRole.CACHE_DEB).id), plan.defaultSelection())
    }

    @Test fun `empty and unmeasured items are not offered, but plugins and packages always are`() {
        val plan = CleanupPlanner.plan(StorageReport(listOf(item(StorageRole.CACHE_PIP, bytes = 0), item(StorageRole.WEB_DATA, bytes = null),
            item(StorageRole.PLUGIN, "p", bytes = 0), item(StorageRole.LINUX_PACKAGE, "jq", bytes = 0))), mapOf("jq" to null))
        assertEquals(setOf(StorageRole.PLUGIN, StorageRole.LINUX_PACKAGE), plan.entries.map { it.item.role }.toSet())
    }

    @Test fun `linux packages follow their protection and unknown packages stay`() {
        val report = StorageReport(listOf(item(StorageRole.LINUX_PACKAGE, "jq"), item(StorageRole.LINUX_PACKAGE, "bash"),
            item(StorageRole.LINUX_PACKAGE, "mystery"), item(StorageRole.LINUX_UNTRACKED_PACKAGE, "old", bytes = null)))
        val plan = CleanupPlanner.plan(report, mapOf("jq" to null, "bash" to PackageProtection(PackageBlock.REQUIRED),
            "old" to PackageProtection(PackageBlock.UNTRACKED)))
        assertEquals(listOf("jq"), plan.entries.map { it.item.key })
        val kept = plan.kept.filter { it.reason == KeepReason.PACKAGE }.associate { it.item!!.key to it.protection!!.block }
        assertEquals(mapOf("bash" to PackageBlock.REQUIRED, "mystery" to PackageBlock.UNTRACKED, "old" to PackageBlock.UNTRACKED), kept)
    }

    @Test fun `a rescan drops selections that are no longer offered`() {
        val plan = CleanupPlanner.plan(StorageReport(listOf(item(StorageRole.CACHE_PIP))), emptyMap())
        assertEquals(setOf(item(StorageRole.CACHE_PIP).id), plan.keepValid(setOf(item(StorageRole.CACHE_PIP).id, "PLUGIN:gone")))
    }

    @Test fun `guard refuses protected folders and their parents but allows listed exceptions`() {
        val files = temp.newFolder("files")
        val router = File(files, "9router").apply { mkdirs() }
        val prefix = File(files, "linux").apply { mkdirs() }
        val tmp = File(prefix, "tmp").apply { mkdirs() }
        val guard = StorageGuard(listOf(router, prefix), listOf(tmp))
        assertFalse(guard.allows(router))
        assertFalse(guard.allows(File(router, "node_modules")))
        assertFalse(guard.allows(files)) // contains 9router
        assertFalse(guard.allows(prefix))
        assertFalse(guard.allows(File(prefix, "bin/bash")))
        assertTrue(guard.allows(File(tmp, "build.log")))
        assertTrue(guard.allows(File(files, "images")))
    }

    @Test fun `delete removes contents, keeps the folder and never follows a link`() {
        val outside = temp.newFolder("outside").apply { File(this, "keep.txt").writeText("keep") }
        val cache = temp.newFolder("cache")
        File(cache, "a/b").mkdirs(); File(cache, "a/b/file.bin").writeText("x")
        Files.createSymbolicLink(File(cache, "link").toPath(), outside.toPath())
        val guard = StorageGuard(listOf(temp.newFolder("protected")))
        StorageDelete.contents(cache, guard)
        assertTrue(cache.isDirectory)
        assertEquals(0, cache.list()!!.size)
        assertEquals("keep", File(outside, "keep.txt").readText())
    }

    @Test fun `delete refuses a protected target`() {
        val router = temp.newFolder("9router").apply { File(this, "server.js").writeText("run") }
        val guard = StorageGuard(listOf(router))
        assertThrows(IllegalStateException::class.java) { StorageDelete.tree(File(router, "server.js"), guard) }
        assertTrue(File(router, "server.js").exists())
    }
}
