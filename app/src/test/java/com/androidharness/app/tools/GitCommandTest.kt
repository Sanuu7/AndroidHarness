package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
