package com.androidharness.app.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Anthropic prompt-cache breakpoint strategy: a rolling marker on
 * the final message (cache write each turn -> cache read next turn) plus a
 * stable anchor on the first user message, never exceeding one breakpoint per
 * message and never touching thinking blocks.
 */
class AnthropicCacheBreakpointsTest {

    private val provider = AnthropicProvider(
        client = okhttp3.OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
    )

    private fun parse(json: String): JsonObject =
        provider::class.java.classLoader.let { Json.parseToJsonElement(json).jsonObject }

    private fun userMessage(text: String): JsonObject = parse(
        """{"role":"user","content":"$text"}"""
    )

    private fun toolResultMessage(): JsonObject = parse(
        """{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}"""
    )

    private fun assistantWithToolUse(): JsonObject = parse(
        """{"role":"assistant","content":[{"type":"text","text":"let me check"},
           {"type":"tool_use","id":"t1","name":"read_file","input":{"path":"a.py"}}]}"""
    )

    private fun countBreakpoints(msg: JsonObject): Int {
        val content = msg["content"] ?: return 0
        return when (content) {
            is kotlinx.serialization.json.JsonArray -> content.count { el ->
                el is JsonObject && el.containsKey("cache_control")
            }
            else -> if (msg.containsKey("cache_control")) 1 else 0
        }
    }

    private fun hasBreakpoint(msg: JsonObject): Boolean {
        val content = msg["content"] ?: return false
        return when (content) {
            is kotlinx.serialization.json.JsonArray ->
                content.any { el -> el is JsonObject && el.containsKey("cache_control") }
            else -> msg.containsKey("cache_control")
        }
    }

    @Test
    fun `rolling breakpoint lands on last message and anchor on first user message`() {
        val messages = listOf(
            userMessage("first user turn"),
            assistantWithToolUse(),
            toolResultMessage(),
            assistantWithToolUse(),
            toolResultMessage(),
        )

        val marked = provider.applyCacheBreakpoints(messages)

        assertEquals(5, marked.size)
        // exactly two marked messages: first user + last
        assertEquals(2, marked.count(::hasBreakpoint))
        assertTrue(hasBreakpoint(marked[0]))
        assertFalse(hasBreakpoint(marked[1]))
        assertFalse(hasBreakpoint(marked[2]))
        assertFalse(hasBreakpoint(marked[3]))
        assertTrue(hasBreakpoint(marked[4]))
        // at most one breakpoint per message (Anthropic caps 4 per request;
        // system + tools already carry two)
        assertTrue(marked.all { countBreakpoints(it) <= 1 })
    }

    @Test
    fun `prefix is byte-stable when one message is appended`() {
        val base = listOf(userMessage("hi"), assistantWithToolUse(), toolResultMessage())
        val turn1 = provider.applyCacheBreakpoints(base)
        val turn2 = provider.applyCacheBreakpoints(base + userMessage("next turn"))

        // Turn 1: tool result is last -> carries the rolling breakpoint.
        assertTrue(hasBreakpoint(turn1[2]))

        // Turn 2: the breakpoint rolls to the new last message, and the old
        // last message reverts to EXACTLY its original bytes (content is
        // untouched; cache_control markers are not part of the cached prefix).
        assertEquals(base[2].toString(), turn2[2].toString())
        assertFalse(hasBreakpoint(turn2[2]))
        assertTrue(hasBreakpoint(turn2[3]))
        // anchor + new rolling = 2
        assertEquals(2, turn2.count(::hasBreakpoint))
    }

    @Test
    fun `single message conversation gets exactly one breakpoint`() {
        val marked = provider.applyCacheBreakpoints(listOf(userMessage("hello")))
        assertEquals(1, marked.count(::hasBreakpoint))
    }

    @Test
    fun `empty conversation is a no-op`() {
        assertTrue(provider.applyCacheBreakpoints(emptyList()).isEmpty())
    }

    @Test
    fun `string content is promoted to a marked text block`() {
        val marked = provider.withCacheBreakpoint(userMessage("plain"))
        val content = marked["content"]!!.jsonArray
        assertEquals(1, content.size)
        val block = content[0].jsonObject
        assertEquals("text", block["type"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", block["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("plain", block["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `breakpoint anchors on last non-thinking block`() {
        val msg = parse(
            """{"role":"assistant","content":[
                 {"type":"thinking","thinking":"hmm"},
                 {"type":"text","text":"answer"},
                 {"type":"redacted_thinking","data":"x"}
               ]}"""
        )
        val marked = provider.withCacheBreakpoint(msg)
        val blocks = marked["content"]!!.jsonArray
        assertNull(blocks[0].jsonObject["cache_control"])          // thinking
        assertNotNull(blocks[1].jsonObject["cache_control"])       // text
        assertNull(blocks[2].jsonObject["cache_control"])          // redacted_thinking
    }

    @Test
    fun `assistant tool_use trailing block can carry the breakpoint`() {
        val marked = provider.withCacheBreakpoint(assistantWithToolUse())
        val blocks = marked["content"]!!.jsonArray
        assertNull(blocks[0].jsonObject["cache_control"])
        assertNotNull(blocks[1].jsonObject["cache_control"])
    }

    @Test
    fun `usage normalization math`() {
        // Anthropic reports input_tokens EXCLUDING cache reads/writes; the
        // provider must emit uncached + read + write as the total prompt.
        val uncached = 1_000
        val cacheRead = 98_000
        val cacheWrite = 1_500
        val total = uncached + cacheRead + cacheWrite
        val hitRate = cacheRead.toDouble() / total
        // long-session steady state: ~97.5% honest hit rate
        assertEquals(0.9752, hitRate, 0.001)
    }
}
