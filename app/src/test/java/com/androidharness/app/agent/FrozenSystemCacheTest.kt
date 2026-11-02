package com.androidharness.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for [FrozenSystemCache] and [SystemKeyInputs].
 *
 * These tests run on the JVM without any Android dependency.
 */
class FrozenSystemCacheTest {

    private fun baseInputs(
        mode: String = "ACT",
        fullAccess: Boolean = false,
        cavemanInstalled: Boolean = false,
        cavemanIntensity: String = "OFF",
        cavemanWenyan: Boolean = false,
        providerId: String = "deepseek",
        modelId: String = "deepseek-chat",
        pinnedInstructions: String = "",
        enabledSkillNames: List<String> = listOf("android-build", "git"),
        repoMapEnabled: Boolean = true,
        memoryAllowed: Boolean = true,
        workspaceDisplayPath: String = "/workspace",
    ) = SystemKeyInputs(
        mode = mode,
        fullAccess = fullAccess,
        cavemanInstalled = cavemanInstalled,
        cavemanIntensity = cavemanIntensity,
        cavemanWenyan = cavemanWenyan,
        providerId = providerId,
        modelId = modelId,
        pinnedInstructions = pinnedInstructions,
        enabledSkillNames = enabledSkillNames,
        repoMapEnabled = repoMapEnabled,
        memoryAllowed = memoryAllowed,
        workspaceDisplayPath = workspaceDisplayPath,
    )

    // ------------------------------------------------------------------
    // getOrBuild: builder runs exactly once per (sessionId, key) pair
    // ------------------------------------------------------------------

    @Test fun `getOrBuild invokes builder once, returns cached text on second call`() = runBlocking {
        val cache = FrozenSystemCache()
        var buildCount = 0
        val inputs = baseInputs()

        val first = cache.getOrBuild("s1", inputs) { buildCount++; "system text" }
        val second = cache.getOrBuild("s1", inputs) { buildCount++; "system text" }

        assertEquals("text must be identical", first, second)
        assertEquals("builder must run exactly once", 1, buildCount)
    }

    @Test fun `getOrBuild rebuilds when inputs change`() = runBlocking {
        val cache = FrozenSystemCache()
        var buildCount = 0

        cache.getOrBuild("s1", baseInputs(mode = "ACT")) { buildCount++; "act-text" }
        val plan = cache.getOrBuild("s1", baseInputs(mode = "PLAN")) { buildCount++; "plan-text" }

        assertEquals("plan text", "plan-text", plan)
        assertEquals("two builds: ACT then PLAN", 2, buildCount)
    }

    @Test fun `invalidate forces rebuild on next call`() = runBlocking {
        val cache = FrozenSystemCache()
        var buildCount = 0
        val inputs = baseInputs()

        cache.getOrBuild("s1", inputs) { buildCount++; "v1" }
        cache.invalidate("s1")
        cache.getOrBuild("s1", inputs) { buildCount++; "v2" }

        assertEquals("builder must run twice after invalidate", 2, buildCount)
    }

    @Test fun `invalidate of other session does not evict current session`() = runBlocking {
        val cache = FrozenSystemCache()
        var buildCount = 0
        val inputs = baseInputs()

        cache.getOrBuild("s1", inputs) { buildCount++; "text" }
        cache.invalidate("s2")   // different session
        cache.getOrBuild("s1", inputs) { buildCount++; "text" }

        assertEquals("s1 must not be evicted by s2 invalidation", 1, buildCount)
    }

    // ------------------------------------------------------------------
    // Plan -> Act -> Plan: key changes with mode
    // ------------------------------------------------------------------

    @Test fun `PLAN and ACT modes produce different keys`() {
        val planKey = FrozenSystemCache.buildKey(baseInputs(mode = "PLAN"))
        val actKey = FrozenSystemCache.buildKey(baseInputs(mode = "ACT"))
        assertNotEquals("Plan vs Act must differ", planKey, actKey)
    }

    @Test fun `switching Plan->Act->Plan rebuilds and caches each mode separately`() = runBlocking {
        val cache = FrozenSystemCache()
        val actInputs = baseInputs(mode = "ACT")
        val planInputs = baseInputs(mode = "PLAN")

        val act1 = cache.getOrBuild("s1", actInputs) { "act-system" }
        val plan = cache.getOrBuild("s1", planInputs) { "plan-system" }
        val act2 = cache.getOrBuild("s1", actInputs) { "act-system-rebuilt" }

        assertEquals("ACT text same both times (cached)", act1, act2)
        assertEquals("act-system", act1)
        assertEquals("plan-system", plan)
        // The cache stores the last-accessed entry per session; act2 comes from cache.
        // Both were cached after their first build.
    }

    // ------------------------------------------------------------------
    // Each SystemKeyInputs field changes the key independently
    // ------------------------------------------------------------------

    private fun assertFieldChangesKey(changed: SystemKeyInputs) {
        val base = baseInputs()
        assertNotEquals(
            "key must differ when field changes",
            FrozenSystemCache.buildKey(base),
            FrozenSystemCache.buildKey(changed),
        )
    }

    @Test fun `mode change changes key`() = assertFieldChangesKey(baseInputs(mode = "PLAN"))
    @Test fun `fullAccess change changes key`() = assertFieldChangesKey(baseInputs(fullAccess = true))
    @Test fun `cavemanInstalled change changes key`() = assertFieldChangesKey(baseInputs(cavemanInstalled = true))
    @Test fun `cavemanIntensity change changes key`() = assertFieldChangesKey(baseInputs(cavemanIntensity = "FULL"))
    @Test fun `cavemanWenyan change changes key`() = assertFieldChangesKey(baseInputs(cavemanWenyan = true))
    @Test fun `providerId change changes key`() = assertFieldChangesKey(baseInputs(providerId = "anthropic"))
    @Test fun `modelId change changes key`() = assertFieldChangesKey(baseInputs(modelId = "deepseek-reasoner"))
    @Test fun `pinnedInstructions change changes key`() = assertFieldChangesKey(baseInputs(pinnedInstructions = "always respond in Spanish"))
    @Test fun `skill list change changes key`() = assertFieldChangesKey(baseInputs(enabledSkillNames = listOf("git")))
    @Test fun `repoMapEnabled change changes key`() = assertFieldChangesKey(baseInputs(repoMapEnabled = false))
    @Test fun `memoryAllowed change changes key`() = assertFieldChangesKey(baseInputs(memoryAllowed = false))
    @Test fun `workspaceDisplayPath change changes key`() = assertFieldChangesKey(baseInputs(workspaceDisplayPath = "/other/path"))

    // ------------------------------------------------------------------
    // Volatile inputs are NOT part of the key (memoryQuery, todoList, etc.)
    // ------------------------------------------------------------------

    @Test fun `volatile inputs do not affect the key`() {
        // Memory query, todo list, repo-map content, startNotes — none of these
        // are parameters of SystemKeyInputs; they live in the volatile tail.
        val key1 = FrozenSystemCache.buildKey(baseInputs())
        val key2 = FrozenSystemCache.buildKey(baseInputs())  // same stable inputs
        assertEquals("two runs with same stable inputs must share the key", key1, key2)
    }

    // ------------------------------------------------------------------
    // LRU eviction: cache does not exceed MAX_SESSIONS
    // ------------------------------------------------------------------

    @Test fun `LRU: cache evicts oldest entry when MAX_SESSIONS exceeded`() = runBlocking {
        val cache = FrozenSystemCache()
        val inputs = baseInputs()

        // Fill to capacity
        for (i in 0 until FrozenSystemCache.MAX_SESSIONS) {
            cache.getOrBuild("session-$i", inputs) { "text-$i" }
        }
        // Add one more — should evict session-0 (oldest, least recently accessed)
        cache.getOrBuild("session-overflow", inputs) { "overflow" }

        // session-overflow is present
        var overflowHit = 0
        cache.getOrBuild("session-overflow", inputs) { overflowHit++ ; "rebuilt" }
        assertEquals("overflow entry must be cached (not rebuilt)", 0, overflowHit)

        // session-0 was evicted; its next access rebuilds
        var evictedCount = 0
        cache.getOrBuild("session-0", inputs) { evictedCount++ ; "rebuilt-0" }
        assertEquals("evicted session must be rebuilt", 1, evictedCount)
    }
}
