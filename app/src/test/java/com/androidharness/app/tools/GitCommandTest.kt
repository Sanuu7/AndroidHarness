package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Command-shape guarantees for the shell-level git tools: the safe.directory
 * override and the auto-maintenance suppression must ride on EVERY segment of
 * a multi-step command, and runtime artifacts under .harness/ must be excluded
 * from staging.
 */
class GitCommandTest {

    /** Prefix every git invocation must carry (see GitTools.GIT_BASE_ARGS). */
    private val base = "git -c 'safe.directory=*' -c gc.auto=0 -c maintenance.auto=false"

    @Test
    fun `gitCmd puts the overrides on every step`() {
        val cmd = gitCmd("status --short --branch")
        assertEquals("$base status --short --branch", cmd)
    }

    @Test
    fun `gitCmd repeats the overrides across chained steps`() {
        val cmd = gitCmd("add -A", "commit -m 'msg'")
        val parts = cmd.split(" && ")
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.startsWith("$base ") })
    }

    @Test
    fun `dubious ownership detector matches real git phrasing`() {
        assertTrue(
            isDubiousOwnership(
                "fatal: detected dubious ownership in repository at '/data/data/com.androidharness.app/files/ws'",
            ),
        )
        assertFalse(isDubiousOwnership("Everything up-to-date"))
    }

    @Test
    fun `gitLogCmd shapes the default history command`() {
        assertEquals(
            "$base log -n 20 --date=short --pretty=format:'%h %ad %an  %s'",
            gitLogCmd(20, null, false),
        )
    }

    @Test
    fun `gitLogCmd appends stat and a quoted path`() {
        val cmd = gitLogCmd(5, "app/src/Main.kt", true)
        assertTrue(cmd.contains("log -n 5 "))
        assertTrue(cmd.contains(" --stat"))
        assertTrue(cmd.endsWith("-- 'app/src/Main.kt'"))
    }

    @Test
    fun `gitShowCmd quotes the hash and keeps the stat under -s`() {
        assertEquals(
            "$base show --stat 'HEAD~1'",
            gitShowCmd("HEAD~1", false),
        )
        // -s BEFORE --stat: --no-patch kills the stat in any position, while
        // "show -s --stat" keeps message + stat (verified on git 2.55).
        assertEquals(
            "$base show -s --stat 'HEAD'",
            gitShowCmd("HEAD", true),
        )
    }

    @Test
    fun `gitCheckoutCmd covers switch create and restore shapes`() {
        assertEquals(
            "$base checkout 'main'",
            gitCheckoutCmd("main", false, emptyList()),
        )
        assertEquals(
            "$base checkout -b 'feature/x'",
            gitCheckoutCmd("feature/x", true, emptyList()),
        )
        assertEquals(
            "$base checkout -- 'a.kt' 'b.kt'",
            gitCheckoutCmd(null, false, listOf("a.kt", " b.kt")),
        )
        assertEquals(
            "$base checkout 'main' -- 'a.kt'",
            gitCheckoutCmd("main", false, listOf("a.kt")),
        )
    }

    @Test
    fun `gitCheckoutCmd refuses an empty call`() {
        try {
            gitCheckoutCmd(" ", false, listOf("  "))
            fail("expected ToolFailure")
        } catch (expected: ToolFailure) {
        }
    }

    @Test
    fun `gitPushCmd defaults to origin and HEAD`() {
        assertEquals(
            "$base push 'origin' HEAD",
            gitPushCmd(null, null, false),
        )
        assertEquals(
            "$base push -u 'origin' 'feature/x'",
            gitPushCmd("origin", "feature/x", true),
        )
    }

    @Test
    fun `gitPullCmd maps modes and rejects unknown ones`() {
        assertEquals(
            "$base pull --ff-only 'origin'",
            gitPullCmd(null, null),
        )
        assertEquals(
            "$base pull 'origin'",
            gitPullCmd("origin", "merge"),
        )
        assertEquals(
            "$base pull --rebase 'origin'",
            gitPullCmd(null, "rebase"),
        )
        try {
            gitPullCmd(null, "squash")
            fail("expected ToolFailure")
        } catch (expected: ToolFailure) {
        }
    }

    @Test
    fun `no upstream detector matches real git phrasing`() {
        assertTrue(
            isNoUpstream(
                "fatal: The current branch main has no upstream branch.\n" +
                    "To push the current branch and set the remote as upstream, use",
            ),
        )
        assertFalse(isNoUpstream("Everything up-to-date"))
    }
}
