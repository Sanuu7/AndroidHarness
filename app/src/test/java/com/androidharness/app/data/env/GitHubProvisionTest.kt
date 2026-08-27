package com.androidharness.app.data.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub auth materialization (stress-test C1/C2/M6): the global git config
 * must always carry safe.directory + identity, embed the token only via the
 * insteadOf URL rewrite (credential helpers cannot exec in this toolchain),
 * and the token fingerprint must change the staging hash inputs.
 */
class GitHubProvisionTest {

    @Test
    fun `config without token keeps safe directory and identity only`() {
        val body = GitHubProvision.gitConfigBody(null)
        assertTrue(body.contains("[safe]"))
        assertTrue(body.contains("directory = *"))
        assertTrue(body.contains("name = Android Harness"))
        assertFalse(body.contains("insteadOf"))
        assertFalse(body.contains("github.com"))
    }

    @Test
    fun `config with token rewrites github urls and disables helpers`() {
        val body = GitHubProvision.gitConfigBody("ghp_TESTTOKEN123")
        assertTrue(body.contains("[credential]"))
        assertTrue(body.contains("helper ="))
        assertTrue(body.contains("[url \"https://x-access-token:ghp_TESTTOKEN123@github.com/\"]"))
        assertTrue(body.contains("insteadOf = https://github.com/"))
    }

    @Test
    fun `blank tokens are treated as no token`() {
        assertEquals(GitHubProvision.gitConfigBody(null), GitHubProvision.gitConfigBody("   "))
        assertEquals("none", GitHubProvision.fingerprint(""))
        assertFalse(GitHubProvision.hasToken(""))
    }

    @Test
    fun `fingerprint distinguishes tokens so deploys pick up a rotation`() {
        assertNotEquals(GitHubProvision.fingerprint("ghp_A"), GitHubProvision.fingerprint("ghp_B"))
        assertNotEquals("none", GitHubProvision.fingerprint("ghp_A"))
        assertEquals(GitHubProvision.fingerprint(" ghp_A "), GitHubProvision.fingerprint("ghp_A"))
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
