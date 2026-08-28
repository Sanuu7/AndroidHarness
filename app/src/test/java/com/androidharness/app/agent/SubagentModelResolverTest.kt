package com.androidharness.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentModelResolverTest {

    private val openRouterStyle = listOf(
        "anthropic/claude-sonnet-4",
        "anthropic/claude-opus-4",
        "google/gemini-2.5-pro",
        "openai/gpt-5",
    )

    // ---------- SubagentModels.match ----------

    @Test
    fun `exact catalog id resolves`() {
        val out = SubagentModels.match("openai/gpt-5", openRouterStyle)
        assertEquals(SubagentModelResolution.Resolved("openai/gpt-5"), out)
    }

    @Test
    fun `case-insensitive hit returns the catalog casing`() {
        val out = SubagentModels.match("OPENAI/GPT-5", openRouterStyle)
        assertEquals(SubagentModelResolution.Resolved("openai/gpt-5"), out)
    }

    @Test
    fun `surrounding whitespace is trimmed before matching`() {
        val out = SubagentModels.match("  gemini-2.5-flash  ", listOf("gemini-2.5-flash"))
        assertEquals(SubagentModelResolution.Resolved("gemini-2.5-flash"), out)
    }

    @Test
    fun `unqualified tail of a vendor-qualified catalog resolves uniquely`() {
        val out = SubagentModels.match("claude-sonnet-4", openRouterStyle)
        assertEquals(SubagentModelResolution.Resolved("anthropic/claude-sonnet-4"), out)
    }

    @Test
    fun `ambiguous tail is refused with the candidate list`() {
        val candidates = listOf("a/model", "b/model")
        val out = SubagentModels.match("model", candidates)
        assertEquals(SubagentModelResolution.Unknown(candidates), out)
    }

    @Test
    fun `qualified request never falls back to suffix matching`() {
        val out = SubagentModels.match("anthropic/claude-sonnet-5", openRouterStyle)
        assertTrue(out is SubagentModelResolution.Unknown)
    }

    @Test
    fun `unknown id returns the full candidate list`() {
        val out = SubagentModels.match("nope", openRouterStyle)
        assertEquals(SubagentModelResolution.Unknown(openRouterStyle), out)
    }

    @Test
    fun `empty catalog yields empty Unknown`() {
        val out = SubagentModels.match("anything", emptyList())
        assertEquals(SubagentModelResolution.Unknown(emptyList()), out)
    }

    // ---------- SubagentModelResolver ----------

    @Test
    fun `one catalog fetch serves parallel resolves`() = runBlocking {
        var fetches = 0
        val resolver = SubagentModelResolver {
            fetches++
            delay(50) // force real concurrency overlap
            listOf("m1", "m2")
        }
        val outs = withContext(Dispatchers.Default) {
            (1..8).map { async { resolver.resolve("m1") } }.awaitAll()
        }
        assertEquals(1, fetches)
        assertTrue(outs.all { it == SubagentModelResolution.Resolved("m1") })
    }

    @Test
    fun `failed fetch is not cached and the retry succeeds`() = runBlocking {
        var fetches = 0
        val resolver = SubagentModelResolver {
            fetches++
            if (fetches == 1) error("HTTP 503: provider hiccup")
            listOf("m1")
        }
        val first = resolver.resolve("m1")
        assertTrue(first is SubagentModelResolution.Failed)
        assertEquals("HTTP 503: provider hiccup", (first as SubagentModelResolution.Failed).message)
        val second = resolver.resolve("m1")
        assertEquals(SubagentModelResolution.Resolved("m1"), second)
        assertEquals(2, fetches)
    }

    @Test
    fun `fetch failure without a message falls back to a generic one`() = runBlocking {
        val resolver = SubagentModelResolver { throw IllegalStateException() }
        val out = resolver.resolve("m1")
        assertEquals(SubagentModelResolution.Failed("catalog unavailable"), out)
    }
}
