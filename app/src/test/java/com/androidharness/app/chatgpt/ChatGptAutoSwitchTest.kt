package com.androidharness.app.chatgpt

import com.androidharness.app.core.*
import com.androidharness.app.llm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatGptAutoSwitchTest {
    private fun config(id: String, model: String = "sol") = ProviderConfig("chatgpt:$id", "ChatGPT · $id",
        ProviderType.OPENAI_RESPONSES, ChatGptProtocol.RESOURCE, model)
    private val a = config("a")
    private val b = config("b", "luna")
    private val limit = StreamEvent.Failure("Usage limit", 429, ChatGptAutoSwitch.USAGE_LIMIT, false)
    private val messages = listOf(ChatMessage(Role.USER, "Finish the task"),
        ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("c1", "write_file", "{}"))),
        ChatMessage(Role.TOOL, "Already saved", toolCallId = "c1"))

    private fun provider(block: suspend (ProviderConfig) -> List<StreamEvent>) = object : LlmProvider {
        override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>,
            tools: List<ToolSchema>, options: RequestOptions) = flow {
            assertEquals(this@ChatGptAutoSwitchTest.messages, messages)
            block(config).forEach { emit(it) }
        }
    }
    private suspend fun events(provider: LlmProvider) = provider.streamChat(a, "managed", "sys", messages, emptyList(), RequestOptions()).toList()

    @Test fun `confirmed quota switches model once and preserves completed tool history`() = runBlocking {
        val attempts = mutableListOf<ProviderConfig>()
        val limited = mutableListOf<String>()
        val successes = mutableListOf<String>()
        val router = ChatGptAutoSwitch(provider { config ->
            attempts += config
            if (config == a) listOf(limit) else listOf(StreamEvent.TextDelta("Continued"), StreamEvent.Done())
        }, { true }, { current, tried, _, history, _ ->
            assertEquals(a, current); assertEquals(setOf(a.id), tried); assertEquals(messages, history); b
        }, { limited += it }, { successes += it })
        val result = events(router)
        assertEquals(listOf(a, b), attempts)
        assertEquals(b, result.filterIsInstance<StreamEvent.ProviderChanged>().single().config)
        assertEquals("Continued", result.filterIsInstance<StreamEvent.TextDelta>().single().text)
        assertEquals(listOf(a.id), limited); assertEquals(listOf(b.id), successes)
        assertFalse(result.any { it is StreamEvent.Failure })
    }

    @Test fun `disabled switching generic 429 auth and server errors never move accounts`() = runBlocking {
        for (failure in listOf(limit, StreamEvent.Failure("Rate limit", 429, "rate_limit_exceeded"),
            StreamEvent.Failure("Auth", 401), StreamEvent.Failure("Policy", 403), StreamEvent.Failure("Offline", 503))) {
            var nextCalls = 0
            val router = ChatGptAutoSwitch(provider { listOf(failure) }, { failure != limit },
                { _, _, _, _, _ -> nextCalls++; b })
            assertEquals(failure, events(router).filterIsInstance<StreamEvent.Failure>().single())
            assertEquals(0, nextCalls)
        }
    }

    @Test fun `visible thinking text tools and nested batches prevent replay`() = runBlocking {
        val outputs = listOf(StreamEvent.TextDelta("Partial"), StreamEvent.ThinkingDelta("Reasoning"),
            StreamEvent.ToolCallReady(ToolCallData("c2", "read_file", "{}")),
            StreamEvent.Batch(listOf(StreamEvent.TextDelta("Nested"))), StreamEvent.Usage(10, 1))
        for (output in outputs) {
            var nextCalls = 0
            val router = ChatGptAutoSwitch(provider { listOf(output, limit) }, { true },
                { _, _, _, _, _ -> nextCalls++; b })
            val result = events(router)
            assertEquals(output, result.first()); assertTrue(result.last() is StreamEvent.Failure)
            assertEquals(0, nextCalls)
        }
    }

    @Test fun `all accounts limited stop after one attempt each`() = runBlocking {
        val c = config("c")
        val requests = mutableListOf<String>()
        val router = ChatGptAutoSwitch(provider { requests += it.id; listOf(limit) }, { true },
            { _, tried, _, _, _ -> listOf(b, c).firstOrNull { it.id !in tried } })
        val result = events(router)
        assertEquals(listOf(a.id, b.id, c.id), requests)
        assertFalse(result.filterIsInstance<StreamEvent.Failure>().single().retryable!!)
        assertEquals(2, result.filterIsInstance<StreamEvent.ProviderChanged>().size)
    }

    @Test fun `candidate cannot repeat an account or change billing provider`() = runBlocking {
        for (candidate in listOf(a, b.copy(id = "api-key"))) {
            val router = ChatGptAutoSwitch(provider { listOf(limit) }, { true }, { _, _, _, _, _ -> candidate })
            assertTrue(events(router).single() is StreamEvent.Failure)
        }
    }

    @Test fun `turning off during catalog refresh prevents switch`() = runBlocking {
        var enabled = true
        val router = ChatGptAutoSwitch(provider { listOf(limit) }, { enabled }, { _, _, _, _, _ -> enabled = false; b })
        assertTrue(events(router).single() is StreamEvent.Failure)
    }

    @Test fun `cancelled catalog lookup propagates cancellation`() = runBlocking {
        val router = ChatGptAutoSwitch(provider { listOf(limit) }, { true }, { _, _, _, _, _ -> throw CancellationException("Stopped") })
        try { events(router); fail("Cancellation swallowed") } catch (_: CancellationException) {}
    }
}
