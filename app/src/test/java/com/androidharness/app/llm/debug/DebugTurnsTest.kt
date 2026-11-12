package com.androidharness.app.llm.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugTurnsTest {

    private fun trace(
        id: Long,
        group: String?,
        provider: String = "a",
        model: String = "m",
        keySlot: Int = 0,
        status: Int? = 200,
        error: String? = null,
    ) = DebugTrace(
        id = id, startedAt = 1_000 + id, providerId = provider, providerName = provider, model = model,
        protocol = "OPENAI", method = "POST", url = "https://example.test/v1/chat", requestHeaders = emptyList(),
        statusCode = status, error = error, keySlot = keySlot, group = group,
        requestBody = """{"model":"m","note":"it's fine"}""",
    )

    @Test
    fun attemptsOfOneTurnAreOrderedAndExplained() {
        val turns = DebugTurns.group(listOf(
            trace(4, "t", provider = "b"),
            trace(3, "t", keySlot = 1, status = 503),
            trace(2, "t", status = null, error = "timeout"),
            trace(1, "t", status = 429),
        ))
        assertEquals(1, turns.size)
        val kinds = turns.single().attempts.map { it.kind }
        assertEquals(listOf(DebugTurns.Kind.FIRST, DebugTurns.Kind.RETRY, DebugTurns.Kind.KEY_ROTATION, DebugTurns.Kind.FALLBACK), kinds)
        assertNull(turns.single().attempts.first().previous)
        assertEquals(429, turns.single().attempts[1].previous?.statusCode)
        assertTrue(turns.single().recovered)
    }

    @Test
    fun ungroupedTracesStandAloneNewestFirst() {
        val turns = DebugTurns.group(listOf(trace(1, null), trace(2, null, status = 500)))
        assertEquals(listOf(2L, 1L), turns.map { it.last.id })
        assertFalse(turns.first().retried)
        assertFalse(turns.first().recovered)
    }

    @Test
    fun jsonIsFormattedTokenizedAndExported() {
        val pretty = DebugJson.pretty("""{"a":[1,true,null],"b":"x: y"}""")
        assertNotNull(pretty)
        val tokens = DebugJson.tokens(pretty!!)
        assertEquals(listOf("\"a\"", "\"b\""), tokens.filter { it.kind == DebugJson.Kind.KEY }.map { pretty.substring(it.start, it.end) })
        assertEquals(listOf("\"x: y\""), tokens.filter { it.kind == DebugJson.Kind.STRING }.map { pretty.substring(it.start, it.end) })
        assertEquals(2, tokens.count { it.kind == DebugJson.Kind.LITERAL })
        assertNull(DebugJson.pretty("data: not json"))
        // A broken body must not throw: that is the body someone is trying to read.
        DebugJson.tokens("""{"open": "never closed""")

        val export = DebugJson.export(listOf(trace(1, "t", status = 429)))
        assertTrue(DebugJson.isJson(export))
        assertTrue(export.contains("\"status\": 429"))
        assertTrue("the request body is embedded as JSON, not as an escaped string", export.contains("\"note\": \"it's fine\""))
    }

    @Test
    fun curlHidesCredentialsAndEscapesQuotes() {
        val curl = trace(1, null).copy(requestHeaders = listOf(
            "Authorization" to "Bearer sk-a…(40 chars)", "Content-Type" to "application/json", "Content-Length" to "31",
        )).asCurl()
        assertTrue(curl.startsWith("curl -X POST 'https://example.test/v1/chat'"))
        assertTrue(curl.contains("-H 'Authorization: <your key>'"))
        assertFalse(curl.contains("Content-Length"))
        assertTrue(curl.contains("""it'\''s fine"""))
    }
}
