package com.androidharness.app.llm

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AnthropicUsageTest {
    private val provider = AnthropicProvider(okhttp3.OkHttpClient(), ProviderFactory.json)
    private fun stream(vararg events: String) = provider.parseStream(events.map { Json.parseToJsonElement(it) }.asFlow())

    @Test fun `cumulative updates produce one final corrected usage before done`() = runBlocking {
        val events = stream(
            """{"type":"message_start","message":{"usage":{"input_tokens":20,"cache_read_input_tokens":80,"output_tokens":1}}}""",
            """{"type":"message_delta","usage":{"output_tokens":3}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":10,"cache_read_input_tokens":90,"output_tokens":7}}""",
            """{"type":"message_stop"}""",
        ).toList()
        assertEquals(StreamEvent.Usage(100, 7, 90, cacheReported = true), events.filterIsInstance<StreamEvent.Usage>().single())
        assertTrue(events.last() is StreamEvent.Done)
    }

    @Test fun `cache writes normalize total input and omitted reads remain unknown`() = runBlocking {
        val events = stream(
            """{"type":"message_start","message":{"usage":{"input_tokens":20,"cache_creation_input_tokens":80}}}""",
            """{"type":"message_delta","usage":{"output_tokens":5}}""",
        ).toList()
        assertEquals(StreamEvent.Usage(100, 5, 0, 80, false), events.filterIsInstance<StreamEvent.Usage>().single())
    }

    @Test fun `recollecting a request does not share mutable counters`() = runBlocking {
        val source = stream(
            """{"type":"message_start","message":{"usage":{"input_tokens":100,"cache_read_input_tokens":0}}}""",
            """{"type":"message_delta","usage":{"output_tokens":5}}""",
            """{"type":"message_stop"}""",
        )
        val first = source.toList()
        assertEquals(first, source.toList())
        assertTrue(first.filterIsInstance<StreamEvent.Usage>().single().cacheReported)
    }
}
