package com.androidharness.app.llm

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Chunk-level tests for [OpenAiCompatProvider.parseChunk]: real-world SSE
 * payload shapes that broke providers other than the vanilla OpenAI one.
 */
class OpenAiCompatParsingTest {

    private lateinit var provider: OpenAiCompatProvider
    private lateinit var acc: LinkedHashMap<String, Triple<StringBuilder, StringBuilder, StringBuilder>>
    private val indexToId = HashMap<Int, String>()

    @Before
    fun setUp() {
        provider = OpenAiCompatProvider(OkHttpClient(), Json { ignoreUnknownKeys = true })
        acc = LinkedHashMap()
        indexToId.clear()
    }

    private fun parse(payload: String): List<StreamEvent> =
        provider.parseChunk(Json.parseToJsonElement(payload), acc, indexToId)

    @Test
    fun `explicit null content does not become text and tool calls still accumulate`() {
        // vLLM and friends send content:null on every tool-call fragment.
        val first = parse(
            """{"choices":[{"index":0,"delta":{"role":"assistant","content":null,
                "tool_calls":[{"index":0,"id":"call_1","type":"function",
                               "function":{"name":"read_file","arguments":""}}]}}]}""",
        )
        assertTrue("null must not emit text nor flush", first.isEmpty())

        parse("""{"choices":[{"index":0,"delta":{"tool_calls":[
                   {"index":0,"function":{"arguments":"{\"path\":\"a.txt\"}"}}]}}]}""")

        val finish = parse("""{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""")
        assertEquals(2, finish.size)
        val call = finish[0] as StreamEvent.ToolCallReady
        assertEquals("read_file", call.call.name)
        assertEquals("call_1", call.call.id)
        assertEquals("""{"path":"a.txt"}""", call.call.argumentsJson)
        assertEquals("tool_calls", (finish[1] as StreamEvent.Done).finishReason)
    }

    @Test
    fun `usage sharing the final chunk does not swallow tool calls`() {
        parse("""{"choices":[{"index":0,"delta":{"tool_calls":[
                   {"index":0,"id":"c1","function":{"name":"shell","arguments":"{\"cmd\""}}]}}]}""")
        parse("""{"choices":[{"index":0,"delta":{"tool_calls":[
                   {"index":0,"function":{"arguments":":\"ls\"}"}}]}}]}""")

        // Together/Fireworks/vLLM put usage on the same chunk as finish_reason.
        val final = parse(
            """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":100,"completion_tokens":20,
                         "prompt_tokens_details":{"cached_tokens":64}}}""",
        )

        assertEquals(3, final.size)
        assertEquals(StreamEvent.Usage(100, 20, 64), final[0])
        val ready = final[1] as StreamEvent.ToolCallReady
        assertEquals("shell", ready.call.name)
        assertEquals("""{"cmd":"ls"}""", ready.call.argumentsJson)
        assertEquals("tool_calls", (final[2] as StreamEvent.Done).finishReason)
        assertTrue("accumulator must be drained", acc.isEmpty())
    }

    @Test
    fun `pending fragments are drained when the stream ends without finish_reason`() {
        parse("""{"choices":[{"index":0,"delta":{"tool_calls":[
                   {"index":0,"id":"c9","function":{"name":"list_dir","arguments":"{}"}}]}}]}""")
        assertTrue("fragment must stay buffered without a finish chunk", acc.isNotEmpty())

        val leftover = provider.drainAccumulated(acc)
        assertEquals(1, leftover.size)
        assertEquals("list_dir", leftover[0].name)
        assertEquals("{}", leftover[0].argumentsJson)
        assertTrue(acc.isEmpty())
    }

    @Test
    fun `content reasoning and tool calls in one chunk all survive`() {
        // Aggregators serving R1-style models bundle all three fields.
        val out = parse(
            """{"choices":[{"index":0,"delta":{
                 "content":"Hi",
                 "reasoning_content":"thinking hard",
                 "tool_calls":[{"index":0,"id":"cb","function":{"name":"grep","arguments":"{}"}}]}}]}""",
        )
        assertEquals(2, out.size)
        assertEquals(StreamEvent.TextDelta("Hi"), out[0])
        assertEquals(StreamEvent.ThinkingDelta("thinking hard"), out[1])

        val finish = parse("""{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""")
        assertEquals("grep", (finish[0] as StreamEvent.ToolCallReady).call.name)
    }

    @Test
    fun `parallel tool calls arrive as one batch`() {
        parse("""{"choices":[{"index":0,"delta":{"tool_calls":[
                   {"index":0,"id":"a","function":{"name":"read_file","arguments":"{\"path\":\"x\"}"}}]}}]}""")
        parse("""{"choices":[{"index":0,"delta":{"tool_calls":[
                   {"index":1,"id":"b","function":{"name":"list_dir","arguments":"{}"}}]}}]}""")
        val finish = parse("""{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""")

        val batch = finish.filterIsInstance<StreamEvent.ToolCallBatch>().single()
        assertEquals(listOf("read_file", "list_dir"), batch.calls.map { it.name })
    }

    @Test
    fun `array-shaped content parts are joined into text`() {
        val out = parse(
            """{"choices":[{"index":0,"delta":{"content":[
                 {"type":"text","text":"Hel"},
                 {"type":"text","text":"lo"}]}}]}""",
        )
        assertEquals(listOf(StreamEvent.TextDelta("Hello")), out)
    }

    @Test
    fun `reasoning-only chunks surface as thinking deltas under both field names`() {
        val a = parse("""{"choices":[{"index":0,"delta":{"reasoning_content":"step 1"}}]}""")
        val b = parse("""{"choices":[{"index":0,"delta":{"reasoning":"step 2"}}]}""")
        val end = parse("""{"choices":[{"index":0,"delta":{"reasoning_content":null},"finish_reason":"stop"}]}""")

        assertEquals(listOf(StreamEvent.ThinkingDelta("step 1")), a)
        assertEquals(listOf(StreamEvent.ThinkingDelta("step 2")), b)
        assertEquals(listOf(StreamEvent.Done("stop")), end)
    }

    @Test
    fun `string and object error payloads both produce failures`() {
        assertEquals(listOf(StreamEvent.Failure("rate limited")), parse("""{"error":"rate limited"}"""))
        assertEquals(listOf(StreamEvent.Failure("boom")), parse("""{"error":{"message":"boom"}}"""))
    }

    @Test
    fun `max completion tokens selected by host or model family`() {
        assertTrue(OpenAiCompatProvider.usesMaxCompletionTokens("https://api.openai.com/v1", "gpt-4o"))
        assertTrue(OpenAiCompatProvider.usesMaxCompletionTokens("https://proxy.example.com/v1", "o4-mini"))
        assertTrue(OpenAiCompatProvider.usesMaxCompletionTokens("https://proxy.example.com/v1", "GPT-5-Mini"))
        // Routers and local servers expect legacy max_tokens.
        assertFalse(OpenAiCompatProvider.usesMaxCompletionTokens("https://openrouter.ai/api/v1", "openai/gpt-5-mini"))
        assertFalse(OpenAiCompatProvider.usesMaxCompletionTokens("http://127.0.0.1:11434/v1", "llama3"))
    }

    @Test
    fun `usage accounting requested for remote hosts, skipped for bare-local ones`() {
        // Remote gateways must be asked — many report usage only when asked,
        // and without it cache hit rates freeze at zero.
        assertTrue(provider.supportsUsageAccounting("https://api.runinfra.ai/v1"))
        assertTrue(provider.supportsUsageAccounting("https://opencode.ai/zen/v1"))
        assertTrue(provider.supportsUsageAccounting("https://api.openai.com/v1"))
        assertTrue(provider.supportsUsageAccounting("https://gateway.example.com/v1"))
        // Older local builds (Ollama/LM Studio/llama.cpp) reject unknown fields.
        assertFalse(provider.supportsUsageAccounting("http://127.0.0.1:11434/v1"))
        assertFalse(provider.supportsUsageAccounting("http://localhost:1234/v1"))
        assertFalse(provider.supportsUsageAccounting("http://192.168.1.2:8080/api"))
        assertFalse(provider.supportsUsageAccounting("http://10.0.0.5:8000/v1"))
    }
}
