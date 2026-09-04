package com.androidharness.app.data.github

import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class GitHubOAuthProtocolTest {
    @Test fun `PKCE matches RFC 7636 test vector`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            GitHubOAuthProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
    @Test fun `callbacks require matching unexpired state`() {
        assertTrue(GitHubOAuthProtocol.validState("expected", "expected", 1000, 2000))
        assertFalse(GitHubOAuthProtocol.validState("expected", "wrong", 1000, 2000))
        assertFalse(GitHubOAuthProtocol.validState("expected", null, 1000, 2000))
        assertFalse(GitHubOAuthProtocol.validState("expected", "expected", 1000, 601000))
        assertFalse(GitHubOAuthProtocol.validState("expected", "expected", 2000, 1000))
    }
    @Test fun `authorization uses PKCE and only supported scopes`() {
        val verifier = GitHubOAuthProtocol.randomSecret()
        assertEquals(43, verifier.length)
        val url = GitHubOAuthProtocol.authorizeUrl("id", "com.androidharness.app.oauth://github/callback",
            "state", verifier, setOf("workflow", "admin:org")).toHttpUrl()
        assertEquals("github.com", url.host)
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("offline_access repo workflow", url.queryParameter("scope"))
        assertNull(url.queryParameter("client_secret"))
    }
}
