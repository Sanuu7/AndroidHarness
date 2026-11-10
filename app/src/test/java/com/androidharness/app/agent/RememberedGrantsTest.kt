package com.androidharness.app.agent

import com.androidharness.app.tools.ShellPolicy
import org.junit.Assert.*
import org.junit.Test

class RememberedGrantsTest {
    @Test fun revocationUpdatesEngineSet() {
        val store = RememberedGrants()
        val keys = store.start("chat", "/workspace")
        store.remember("chat", "shell#git status")
        assertTrue(ShellPolicy.isGranted("shell", "git status", keys))
        store.revoke(store.grants.value.single())
        assertFalse(ShellPolicy.isGranted("shell", "git status", keys))
        assertTrue(store.grants.value.isEmpty())
    }

    @Test fun revocationIsScopedToRunAndWorkspace() {
        val store = RememberedGrants()
        val a = store.start("a", "/a")
        val b = store.start("b", "/b")
        store.remember("a", "write_file")
        store.remember("b", "write_file")
        store.revoke(RememberedGrants.Grant("a", "/b", "write_file"))
        assertTrue(a.contains("write_file"))
        store.revoke(RememberedGrants.Grant("a", "/a", "write_file"))
        assertFalse(a.contains("write_file"))
        assertTrue(b.contains("write_file"))
    }

    @Test fun finishClearsGrantsAndLateApprovalDoesNotResurrectThem() {
        val store = RememberedGrants()
        val keys = store.start("a", "/a")
        store.remember("a", "write_file")
        store.finish("a")
        store.remember("a", "write_file")
        assertTrue(keys.isEmpty())
        assertTrue(store.grants.value.isEmpty())
    }

    @Test fun nextRunStartsWithoutPermissions() {
        val store = RememberedGrants()
        store.start("a", "/a")
        store.remember("a", "write_file")
        store.finish("a")
        assertTrue(store.start("a", "/a").isEmpty())
    }

    private class MemoryStore(var data: Map<String, Set<String>> = emptyMap()) : GrantPersistence {
        override fun load() = data
        override fun save(grants: Map<String, Set<String>>) { data = grants }
    }

    @Test fun alwaysIsSavedAndStartsTheNextRunApproved() {
        val disk = MemoryStore()
        val first = RememberedGrants(disk)
        first.start("a", "/a")
        assertTrue(first.remember("a", "shell#git status"))
        first.finish("a")
        // A fresh process reads the same saved approvals.
        val second = RememberedGrants(disk)
        val keys = second.start("b", "/a")
        assertTrue(ShellPolicy.isGranted("shell", "git status", keys))
        assertTrue(second.grants.value.single().saved)
        assertTrue(second.start("c", "/elsewhere").isEmpty())
    }

    @Test fun confirmEverythingStartsWithoutSavedApprovals() {
        val store = RememberedGrants(MemoryStore(mapOf("/a" to setOf("write_file"))))
        assertTrue(store.start("a", "/a", useSaved = false).isEmpty())
        assertEquals(listOf("write_file"), store.grants.value.map { it.key })
    }

    @Test fun revokingASavedGrantRemovesItFromDiskAndRunningChats() {
        val disk = MemoryStore()
        val store = RememberedGrants(disk)
        val keys = store.start("a", "/a")
        store.remember("a", "write_file")
        store.revoke(store.grants.value.single())
        assertFalse(keys.contains("write_file"))
        assertTrue(disk.data.isEmpty())
        assertTrue(store.grants.value.isEmpty())
    }

    @Test fun revokeAllClearsOnlyThatWorkspace() {
        val disk = MemoryStore()
        val store = RememberedGrants(disk)
        val a = store.start("a", "/a")
        val b = store.start("b", "/b")
        store.remember("a", "write_file")
        store.remember("a", "shell#npm test")
        store.remember("b", "write_file")
        store.revokeAll("/a")
        assertTrue(a.isEmpty())
        assertTrue(b.contains("write_file"))
        assertEquals(setOf("/b"), disk.data.keys)
    }

    @Test fun unsafeApprovalsLastOnlyForTheRun() {
        val disk = MemoryStore()
        val store = RememberedGrants(disk)
        val keys = store.start("a", "/a")
        assertFalse(store.remember("a", "shell#bash -c"))
        assertFalse(store.remember("a", "pkg_install"))
        assertTrue(keys.contains("shell#bash -c"))
        assertTrue(disk.data.isEmpty())
        store.finish("a")
        assertTrue(RememberedGrants(disk).start("b", "/a").isEmpty())
    }

    @Test fun savedKeysThatBecameUnsafeAreDroppedOnLoad() {
        val store = RememberedGrants(MemoryStore(mapOf("/a" to setOf("shell#sudo rm", "write_file"))))
        assertEquals(listOf("write_file"), store.grants.value.map { it.key })
    }

    @Test fun policyKeepsBoundedCommandsAndRefusesOpenEndedOnes() {
        assertTrue(GrantPolicy.canSave("write_file"))
        assertTrue(GrantPolicy.canSave("shell#git status"))
        assertTrue(GrantPolicy.canSave("shell_background#npm run"))
        assertTrue(GrantPolicy.canSave("workspace:p1:shell#./gradlew test"))
        assertTrue(GrantPolicy.canSave("mcp__docs__search"))
        assertFalse(GrantPolicy.canSave("shell#python3 -c"))
        assertFalse(GrantPolicy.canSave("shell#/bin/sh -c"))
        assertFalse(GrantPolicy.canSave("shell#rm -rf"))
        assertFalse(GrantPolicy.canSave("shell#curl https://example.com"))
        assertFalse(GrantPolicy.canSave("shell#"))
        assertFalse(GrantPolicy.canSave("device_owner_shell#pm list"))
        assertFalse(GrantPolicy.canSave("git_push"))
        assertFalse(GrantPolicy.canSave("github_create_pull_request"))
        assertFalse(GrantPolicy.canSave("android_sdk_install"))
        assertFalse(GrantPolicy.canSave("config:abc:shell#git status"))
        assertFalse(GrantPolicy.canSave("app_settings:call-1"))
    }
}
