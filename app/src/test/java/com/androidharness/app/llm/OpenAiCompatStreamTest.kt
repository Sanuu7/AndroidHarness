package com.androidharness.app.llm

import com.androidharness.app.agent.StreamRetrier
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatStreamTest {
    private val config = ProviderConfig(
        "kilo", "Kilo Auto Free", ProviderType.OPENAI_COMPAT,
        "https://api.kilo.ai/api/gateway", "kilo-auto/free",
    )
    private val partialCall = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"broken","function":{"name":"write_file","arguments":"{\"path\":\"providers.py\""}}]}}]}"""
    private val completeCall = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"complete","function":{"name":"write_file","arguments":"{\"path\":\"ok.txt\",\"content\":\"ok\"}"}}]},"finish_reason":"tool_calls"}]}"""

    private fun provider(vararg attempts: List<String>): OpenAiCompatProvider {
        var attempt = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = buildString {
                attempts[attempt++].forEach { append("data: $it\n\n") }
                append("data: [DONE]\n\n")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        return OpenAiCompatProvider(client, Json { ignoreUnknownKeys = true })
    }

    private fun stream(provider: OpenAiCompatProvider) = provider.streamChat(
        config, "", "Help with coding.", listOf(ChatMessage(role = Role.USER, text = "Write a file.")),
        emptyList(), RequestOptions(),
    )

    @Test
    fun `injected SSE error discards partial tool call and all later chunks`() = runBlocking {
        val events = stream(provider(listOf(
            partialCall,
            """{"error":{"code":502,"message":"JSON error injected into SSE stream"}}""",
            completeCall,
        ))).toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is StreamEvent.Failure)
        assertFalse(events.any { it is StreamEvent.ToolCallReady || it is StreamEvent.ToolCallBatch })
    }

    @Test
    fun `error finish reason without error body discards partial tool call`() = runBlocking {
        val events = stream(provider(listOf(
            partialCall,
            """{"choices":[{"delta":{},"finish_reason":"error"}]}""",
        ))).toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is StreamEvent.Failure)
    }

    @Test
    fun `transient injected error retries and only delivers the complete tool call`() = runBlocking {
        val provider = provider(
            listOf(partialCall, """{"error":{"code":502,"message":"JSON error injected into SSE stream"}}"""),
            listOf(completeCall),
        )
        val events = mutableListOf<StreamEvent>()
        var retries = 0
        val result = StreamRetrier.run(
            streamFor = { stream(provider) },
            onAttemptStart = { events.clear() },
            hasOutput = { events.any { it is StreamEvent.ToolCallReady || it is StreamEvent.ToolCallBatch } },
            handleEvent = { events += it },
            retryReason = { it },
            emitEvent = { retries++ },
            sleep = {},
        )

        assertEquals(null, result)
        assertEquals(1, retries)
        val call = events.filterIsInstance<StreamEvent.ToolCallReady>().single().call
        assertEquals("complete", call.id)
        assertEquals("""{"path":"ok.txt","content":"ok"}""", call.argumentsJson)
    }

    @Test
    fun `generic gateway 503 retries using error code`() = runBlocking {
        val provider = provider(
            listOf("""{"error":{"code":503,"message":"Service unavailable"}}"""),
            listOf("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""),
        )
        var retries = 0
        val result = StreamRetrier.run(
            streamFor = { stream(provider) }, onAttemptStart = {}, hasOutput = { false },
            handleEvent = {}, retryReason = { it }, emitEvent = { retries++ }, sleep = {},
        )
        assertEquals(null, result)
        assertEquals(1, retries)
    }

    @Test
    fun `permanent error code overrides transient wording`() = runBlocking {
        val provider = provider(listOf("""{"error":{"code":400,"message":"Invalid request, try again"}}"""))
        val result = StreamRetrier.run(
            streamFor = { stream(provider) }, onAttemptStart = {}, hasOutput = { false },
            handleEvent = {}, retryReason = { it },
            emitEvent = { error("400 must not retry") }, sleep = {},
        )
        assertEquals("Invalid request, try again", result)
    }

    @Test
    fun `legacy injected error without code still retries`() = runBlocking {
        val provider = provider(
            listOf(partialCall, """{"error":"JSON error injected into SSE stream"}"""),
            listOf(completeCall),
        )
        var retries = 0
        val result = StreamRetrier.run(
            streamFor = { stream(provider) }, onAttemptStart = {}, hasOutput = { false },
            handleEvent = {}, retryReason = { it }, emitEvent = { retries++ }, sleep = {},
        )
        assertEquals(null, result)
        assertEquals(1, retries)
    }

    @Test
    fun `failure after visible thinking preserves output without retrying or emitting tools`() = runBlocking {
        val provider = provider(listOf(
            """{"choices":[{"delta":{"reasoning_content":"Let me check."}}]}""",
            partialCall,
            """{"error":{"code":502,"message":"JSON error injected into SSE stream"}}""",
        ))
        val events = mutableListOf<StreamEvent>()
        val result = StreamRetrier.run(
            streamFor = { stream(provider) }, onAttemptStart = {}, hasOutput = { events.isNotEmpty() },
            handleEvent = { events += it }, retryReason = { it },
            emitEvent = { error("Visible output must not be duplicated") }, sleep = {},
        )
        assertEquals("JSON error injected into SSE stream", result)
        assertEquals(listOf(StreamEvent.ThinkingDelta("Let me check.")), events)
    }

    @Test
    fun `null error field permits a successful tool response`() = runBlocking {
        val events = stream(provider(listOf(
            """{"error":null,"choices":[{"delta":{"tool_calls":[{"index":0,"id":"ok","function":{"name":"list_dir","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
        ))).toList()
        assertEquals("ok", events.filterIsInstance<StreamEvent.ToolCallReady>().single().call.id)
        assertEquals(StreamEvent.Done("tool_calls"), events.last())
    }

    @Test
    fun `choice error discards fragments and preserves string status code`() = runBlocking {
        val events = stream(provider(listOf(
            partialCall,
            """{"choices":[{"delta":{},"finish_reason":"error","error":{"code":"429","message":"Too many requests"}}]}""",
        ))).toList()
        assertEquals(listOf(StreamEvent.Failure("Too many requests", 429)), events)
    }
}
