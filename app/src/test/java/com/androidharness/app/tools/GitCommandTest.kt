package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Command-shape guarantees for the shell-level git tools: the dubious-
 * ownership override must ride on EVERY segment of a multi-step command,
 * and runtime artifacts under .harness/ must be excluded from staging.
 */
class GitCommandTest {

    @Test
    fun `gitCmd puts safe directory override on every step`() {
        val cmd = gitCmd("status --short --branch")
        assertEquals(
            "git -c 'safe.directory=*' status --short --branch",
            cmd,
        )
    }

    @Test
    fun `gitCmd repeats the override across chained steps`() {
        val cmd = gitCmd("add -A", "commit -m 'msg'")
        val parts = cmd.split(" && ")
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.startsWith("git -c 'safe.directory=*' ") })
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
            "git -c 'safe.directory=*' log -n 20 --date=short --pretty=format:'%h %ad %an  %s'",
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
    fun `gitShowCmd quotes the hash and honors no_patch`() {
        assertEquals(
            "git -c 'safe.directory=*' show --stat 'HEAD~1'",
            gitShowCmd("HEAD~1", false),
        )
        assertEquals(
            "git -c 'safe.directory=*' show --stat --no-patch 'HEAD'",
            gitShowCmd("HEAD", true),
        )
    }

    @Test
    fun `gitCheckoutCmd covers switch create and restore shapes`() {
        assertEquals(
            "git -c 'safe.directory=*' checkout 'main'",
            gitCheckoutCmd("main", false, emptyList()),
        )
        assertEquals(
            "git -c 'safe.directory=*' checkout -b 'feature/x'",
            gitCheckoutCmd("feature/x", true, emptyList()),
        )
        assertEquals(
            "git -c 'safe.directory=*' checkout -- 'a.kt' 'b.kt'",
            gitCheckoutCmd(null, false, listOf("a.kt", " b.kt")),
        )
        assertEquals(
            "git -c 'safe.directory=*' checkout 'main' -- 'a.kt'",
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
            "git -c 'safe.directory=*' push 'origin' HEAD",
            gitPushCmd(null, null, false),
        )
        assertEquals(
            "git -c 'safe.directory=*' push -u 'origin' 'feature/x'",
            gitPushCmd("origin", "feature/x", true),
        )
    }

    @Test
    fun `gitPullCmd maps modes and rejects unknown ones`() {
        assertEquals(
            "git -c 'safe.directory=*' pull --ff-only 'origin'",
            gitPullCmd(null, null),
        )
        assertEquals(
            "git -c 'safe.directory=*' pull 'origin'",
            gitPullCmd("origin", "merge"),
        )
        assertEquals(
            "git -c 'safe.directory=*' pull --rebase 'origin'",
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
