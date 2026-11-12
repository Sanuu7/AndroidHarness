package com.androidharness.app.llm.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugRedactTest {

    // Fixtures are built at runtime so no credential scanner mistakes them for
    // real keys and rewrites this file.
    private val fakeKey = buildString { repeat(20) { append('a' + it % 26) } }
    private val vendorKey = "sk-" + "b".repeat(20)
    private val googleKey = "AIza" + "c".repeat(30)
    private val scheme = "Bear" + "er "

    @Test fun aCustomAuthValueIsMaskedByHeaderNameNotByShape() {
        // Nothing here matches a vendor prefix; only the header name can catch it.
        val raw = scheme + fakeKey
        val masked = DebugRedact.header("Authorization", raw)
        assertFalse(masked.contains(fakeKey))
        assertTrue(masked.startsWith(scheme))
        assertEquals(masked, DebugRedact.headers(listOf("Authorization" to raw)).single().second)
    }

    @Test fun everyCredentialHeaderNameIsCovered() {
        listOf("Authorization", "x-api-key", "X-Goog-Api-Key", "proxy-authorization", "Cookie",
            "x-auth-token", "X-Custom-Secret").forEach { name ->
            assertTrue(name, DebugRedact.isSecretName(name))
            assertFalse(name, DebugRedact.header(name, fakeKey).contains(fakeKey))
        }
    }

    @Test fun ordinaryHeadersSurvive() {
        assertEquals("application/json", DebugRedact.header("Content-Type", "application/json"))
        assertEquals("gzip", DebugRedact.header("Accept-Encoding", "gzip"))
        assertFalse(DebugRedact.isSecretName("Content-Type"))
        assertFalse(DebugRedact.isSecretName("anthropic-version"))
    }

    @Test fun aBodyCarriesNoRequestSecret() {
        val secrets = DebugRedact.secretsOf(listOf("Authorization" to scheme + fakeKey))
        val body = DebugRedact.body("""{"echo":"$fakeKey","prompt":"count the tokens"}""", secrets)
        assertFalse(body.contains(fakeKey))
        assertTrue(body.contains("count the tokens"))
    }

    @Test fun vendorShapesInABodyAreStillRedacted() {
        val body = DebugRedact.body("""{"key":"$vendorKey"}""", emptyList())
        assertFalse(body.contains(vendorKey))
    }

    @Test fun credentialQueryParametersAreMaskedAndThePathSurvives() {
        val url = DebugRedact.url("https://generativelanguage.googleapis.com/v1beta/models?key=$googleKey&alt=sse")
        assertFalse(url.contains(googleKey))
        assertTrue(url.contains("/v1beta/models"))
        assertTrue(url.contains("alt=sse"))
    }

    @Test fun aUrlWithoutAQueryKeepsItsShape() {
        assertEquals("https://api.example.com/v1/chat/completions",
            DebugRedact.url("https://api.example.com/v1/chat/completions"))
    }

    @Test fun workspacePathsAreHiddenInBodies() {
        val body = DebugRedact.body(
            """{"cwd":"/storage/emulated/0/Agent workspace/Androidharness/app"}""", emptyList())
        assertFalse(body.contains("Agent workspace"))
        assertTrue(body.contains("<storage path>"))
    }

    @Test fun appDataPathsAreHidden() {
        val body = DebugRedact.body("""{"db":"/data/user/0/com.androidharness.app/files/x.db"}""", emptyList())
        assertFalse(body.contains("com.androidharness.app/files"))
        assertTrue(body.contains("<app data path>"))
    }
}
