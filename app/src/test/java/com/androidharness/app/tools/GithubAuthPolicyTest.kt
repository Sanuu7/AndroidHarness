package com.androidharness.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * http_request's GitHub auth policy: API hosts get the token automatically,
 * explicit true is confined to GitHub hosts (it must never leak the token to
 * a third-party host), and explicit false always wins.
 */
class GithubAuthPolicyTest {

    @Test
    fun `api and uploads hosts attach automatically`() {
        assertTrue(GithubAuthPolicy.shouldAttach("api.github.com", null))
        assertTrue(GithubAuthPolicy.shouldAttach("uploads.github.com", null))
    }

    @Test
    fun `anonymous hosts never attach without explicit opt-in`() {
        assertFalse(GithubAuthPolicy.shouldAttach("example.com", null))
        assertFalse(GithubAuthPolicy.shouldAttach("github.com", null))
        assertFalse(GithubAuthPolicy.shouldAttach("raw.githubusercontent.com", null))
        assertFalse(GithubAuthPolicy.shouldAttach("api.github.com.evil.com", null))
    }

    @Test
    fun `explicit true covers github hosts only`() {
        assertTrue(GithubAuthPolicy.shouldAttach("github.com", true))
        assertTrue(GithubAuthPolicy.shouldAttach("raw.githubusercontent.com", true))
        assertTrue(GithubAuthPolicy.shouldAttach("objects.githubusercontent.com", true))
        assertTrue(GithubAuthPolicy.shouldAttach("API.GitHub.COM", true))
        // The exfiltration guard: a lookalike or unrelated host never gets the token.
        assertFalse(GithubAuthPolicy.shouldAttach("evil.com", true))
        assertFalse(GithubAuthPolicy.shouldAttach("api.github.com.evil.com", true))
        assertFalse(GithubAuthPolicy.shouldAttach("github.com.evil.com", true))
    }

    @Test
    fun `explicit false always wins`() {
        assertFalse(GithubAuthPolicy.shouldAttach("api.github.com", false))
        assertFalse(GithubAuthPolicy.shouldAttach("github.com", false))
    }

    @Test
    fun `unparseable urls never attach`() {
        assertFalse(GithubAuthPolicy.shouldAttach(null, true))
        assertFalse(GithubAuthPolicy.shouldAttach("", null))
        assertFalse(GithubAuthPolicy.shouldAttach("   ", true))
    }
}
