package com.androidharness.app.agent

import com.androidharness.app.core.*
import com.androidharness.app.llm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class HistoryCompactorTest {
    private val config = ProviderConfig("test", "Fixture", ProviderType.OPENAI_COMPAT, "http://127.0.0.1/v1", "model")
    private val history = listOf(ChatMessage(role = Role.USER, text = "goal " + "x".repeat(70_000)),
        ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(ToolCallData("a", "shell", "{\"command\":\"pwd\"}"))),
        ChatMessage(role = Role.TOOL, toolCallId = "a", toolName = "shell", text = "/workspace/keep-me"))
    private fun provider(reply: (List<ChatMessage>) -> Flow<StreamEvent>) = object : LlmProvider {
        override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
            messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions): Flow<StreamEvent> {
            assertTrue(tools.isEmpty()); assertEquals(ThinkingLevel.OFF, options.thinking)
            assertEquals(Role.USER, messages.single().role); assertTrue(messages.single().toolCalls.isEmpty())
            assertTrue(messages.single().imageData.isEmpty()); return reply(messages)
        }
    }
    private suspend fun compact(p: LlmProvider, history: List<ChatMessage> = this.history, timeout: Long = 1000) =
        HistoryCompactor.summarize(p, config, "key", history, 32_000, "sid", stallTimeoutMs = timeout)

    @Test fun `large transcript splits then merges preserving tool outcomes`() = runBlocking {
        val inputs = mutableListOf<String>()
        val result = compact(provider { messages ->
            val text = messages.single().text; inputs += text
            flowOf(StreamEvent.TextDelta(if (text.contains("keep-me")) "Keep /workspace/keep-me" else "Keep goal"), StreamEvent.Done("stop"))
        })
        assertTrue(inputs.size >= 4); assertTrue(inputs.all { it.length <= HistoryCompactor.MAX_INPUT_CHARS })
        assertTrue(inputs.any { it.contains("Tool call shell") && it.contains("/workspace/keep-me") })
        assertTrue(result.contains("/workspace/keep-me"))
    }

    @Test fun `summary budget leaves room for models that reason anyway`() = runBlocking {
        val budgets = mutableListOf<Int>()
        val p = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions): Flow<StreamEvent> {
                budgets += options.maxOutputTokens
                return flowOf(StreamEvent.TextDelta("Keep goal"), StreamEvent.Done("stop"))
            }
        }
        HistoryCompactor.summarize(p, config, "key", history.take(1).map { it.copy(text = "goal") }, 262_144, "sid")
        assertEquals(listOf(HistoryCompactor.MAX_SUMMARY_TOKENS), budgets)
        budgets.clear()
        HistoryCompactor.summarize(p, config, "key", history.take(1).map { it.copy(text = "goal") }, 16_384, "sid")
        assertEquals(listOf(2_048), budgets)
    }

    @Test fun `chunking never drops oversized text or arguments`() {
        val transcript = HistoryCompactor.transcriptChunks(history, 99).toList()
        assertTrue(transcript.all { it.length <= 99 })
        assertEquals(70_000, transcript.joinToString("").count { it == 'x' })
        assertTrue(transcript.joinToString("").contains("{\"command\":\"pwd\"}"))
    }

    @Test fun `chunk boundaries preserve emoji surrogate pairs`() {
        val message = ChatMessage(role = Role.USER, text = "😀".repeat(100))
        val chunks = HistoryCompactor.transcriptChunks(listOf(message), 99).toList()
        assertTrue(chunks.all { it.length <= 99 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
        assertTrue(chunks.joinToString("").contains(message.text))
    }

    @Test fun `partial stream failure never returns a summary`() = runBlocking {
        val e = runCatching { compact(provider { flowOf(StreamEvent.TextDelta("partial"), StreamEvent.Failure("invalid request", 422)) }) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("422")); assertTrue(e?.message.orEmpty().contains("invalid request"))
    }

    @Test fun `nested batch failure discards text`() = runBlocking {
        val e = runCatching { compact(provider { flowOf(StreamEvent.Batch(listOf(StreamEvent.TextDelta("partial"), StreamEvent.Failure("denied", 402)))) }) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("402"))
    }

    @Test fun `empty truncated and abruptly closed summaries fail`() = runBlocking {
        for (events in listOf(listOf(StreamEvent.Done("stop")),
            listOf(StreamEvent.TextDelta("partial"), StreamEvent.Done("MAX_TOKENS")),
            listOf(StreamEvent.TextDelta("partial")))) {
            assertTrue(runCatching { compact(provider { events.asFlow() }) }.isFailure)
        }
    }

    @Test fun `stalled stream with partial output exits and cancellation propagates`() = runBlocking {
        val p = provider { flow { emit(StreamEvent.TextDelta("partial")); awaitCancellation() } }
        assertTrue(runCatching { compact(p, timeout = 20) }.exceptionOrNull()?.message.orEmpty().contains("stalled", true))
        val started = CompletableDeferred<Unit>()
        val job = async { compact(provider { flow { started.complete(Unit); awaitCancellation() } }) }
        started.await(); job.cancelAndJoin(); assertTrue(job.isCancelled)
    }

    @Test fun `model changes and usage apply to subsequent chunks`() = runBlocking {
        var calls = 0
        val switched = config.copy(id = "other", model = "other-model")
        val configs = mutableListOf<ProviderConfig>()
        val p = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions): Flow<StreamEvent> {
                configs += config
                return flow { if (calls++ == 0) emit(StreamEvent.ProviderChanged(switched, "Switched"))
                    emit(StreamEvent.TextDelta("summary")); emit(StreamEvent.Usage(12, 3)); emit(StreamEvent.Done("stop")) }
            }
        }
        val usageModels = mutableListOf<String>()
        HistoryCompactor.summarize(p, config, "key", history, 32000, "sid", event = { e, cfg -> if (e is StreamEvent.Usage) usageModels += cfg.model })
        assertTrue(configs.drop(1).all { it == switched }); assertTrue(usageModels.all { it == switched.model })
    }
}
