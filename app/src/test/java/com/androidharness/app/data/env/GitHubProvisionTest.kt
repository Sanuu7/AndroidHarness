package com.androidharness.app.data.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub auth materialization (stress-test C1/C2/M6): the global git config
 * must always carry safe.directory + identity + a main default branch, embed
 * the token only via the insteadOf URL rewrite (credential helpers cannot
 * exec in the app tier, so no empty `credential.helper` reset is written that
 * would block them in the shell tier either).
 */
class GitHubProvisionTest {

    @Test
    fun `config without token keeps safe directory identity and default branch only`() {
        val body = GitHubProvision.gitConfigBody(null)
        assertTrue(body.contains("[safe]"))
        assertTrue(body.contains("directory = *"))
        assertTrue(body.contains("name = Android Harness"))
        assertTrue(body.contains("[init]"))
        assertTrue(body.contains("defaultBranch = main"))
        assertFalse(body.contains("insteadOf"))
        assertFalse(body.contains("github.com"))
    }

    @Test
    fun `config with token rewrites github urls and does not reset helpers`() {
        val body = GitHubProvision.gitConfigBody("ghp_TESTTOKEN123")
        assertTrue(body.contains("[url \"https://x-access-token:ghp_TESTTOKEN123@github.com/\"]"))
        assertTrue(body.contains("insteadOf = https://github.com/"))
        // The old empty `credential.helper =` reset is gone: with the git ELF's
        // SHELL_PATH patched to /system/bin/sh, gh's helpers must be able to run.
        assertFalse(body.contains("[credential]"))
        assertFalse(body.contains("helper"))
        // Every remaining section is still written with the token present.
        assertTrue(body.contains("[init]"))
        assertTrue(body.contains("defaultBranch = main"))
    }

    @Test
    fun `blank tokens are treated as no token`() {
        assertEquals(GitHubProvision.gitConfigBody(null), GitHubProvision.gitConfigBody("   "))
        assertFalse(GitHubProvision.hasToken(""))
    }

    @Test
    fun `gh hosts yaml authenticates the cli and clears without a token`() {
        val yaml = GitHubProvision.ghHostsYaml("ghp_TESTTOKEN123")
        assertTrue(yaml!!.startsWith("github.com:"))
        assertTrue(yaml.contains("git_protocol: https"))
        assertTrue(yaml.contains("oauth_token: ghp_TESTTOKEN123"))
        assertEquals(null, GitHubProvision.ghHostsYaml(null))
        assertEquals(null, GitHubProvision.ghHostsYaml("  "))
    }
}
