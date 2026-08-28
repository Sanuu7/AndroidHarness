package com.androidharness.app.llm

import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiResponsesProviderTest {

    private val json = ProviderFactory.json
    private val provider = OpenAiResponsesProvider(okhttp3.OkHttpClient(), json)
    private val config = ProviderConfig("p1", "OpenAI", ProviderType.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-5.6")

    private fun el(payload: String) = json.parseToJsonElement(payload)

    @Test
    fun `request body is stateless with instructions and flattened tool schema`() {
        val body = provider.buildRequestBody(
            config = config,
            systemPrompt = "You are helpful.",
            messages = listOf(ChatMessage(role = Role.USER, text = "hi")),
            tools = listOf(
                ToolSchema(
                    name = "read_file",
                    description = "Read a file",
                    parametersJson = json.parseToJsonElement("""{"type":"object"}""").jsonObject,
                ),
            ),
            options = RequestOptions(thinking = ThinkingLevel.HIGH),
        )

        assertEquals("gpt-5.6", body["model"]!!.jsonPrimitive.content)
        assertEquals(false, body["store"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("You are helpful.", body["instructions"]!!.jsonPrimitive.content)
        // Static family table: gpt-5 → effort "high".
        assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("auto", body["reasoning"]!!.jsonObject["summary"]!!.jsonPrimitive.content)

        val tool = body["tools"]!!.jsonArray.first().jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        assertEquals("read_file", tool["name"]!!.jsonPrimitive.content)
        assertFalse(tool["strict"]!!.jsonPrimitive.content.toBoolean())
        // Responses wants the FLATTENED shape, no nested "function" object.
        assertNull(tool["function"])

        val input = body["input"]!!.jsonArray
        assertEquals("user", input.first().jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "input_text",
            input.first().jsonObject["content"]!!.jsonArray.first().jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `tool calls and results serialize as replayable items`() {
        val body = provider.buildRequestBody(
            config = config,
            systemPrompt = "sys",
            messages = listOf(
                ChatMessage(
                    role = Role.ASSISTANT,
                    text = "let me check",
                    toolCalls = listOf(ToolCallData("call_1", "read_file", """{"path":"a.kt"}""")),
                ),
                ChatMessage(role = Role.TOOL, text = "file body", toolCallId = "call_1"),
            ),
            tools = emptyList(),
            options = RequestOptions(),
        )
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        // assistant message + function_call item + function_call_output item
        assertEquals(3, input.size)
        with(input[1]) {
            assertEquals("function_call", this["type"]!!.jsonPrimitive.content)
            assertEquals("call_1", this["call_id"]!!.jsonPrimitive.content)
            assertEquals("""{"path":"a.kt"}""", this["arguments"]!!.jsonPrimitive.content)
        }
        with(input[2]) {
            assertEquals("function_call_output", this["type"]!!.jsonPrimitive.content)
            assertEquals("call_1", this["call_id"]!!.jsonPrimitive.content)
            assertEquals("file body", this["output"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `streams text reasoning tool calls usage and done in order`() {
        val acc = LinkedHashMap<String, Triple<String, String, StringBuilder>>()
        fun types(payload: String) = provider.parseEvent(el(payload), acc)

        val text = types("""{"type":"response.output_text.delta","delta":"Hel"}""")
        assertEquals(listOf("Hel"), text.filterIsInstance<StreamEvent.TextDelta>().map { it.text })

        val think = types("""{"type":"response.reasoning_summary_text.delta","delta":"hmm"}""")
        assertEquals(listOf("hmm"), think.filterIsInstance<StreamEvent.ThinkingDelta>().map { it.text })

        types("""{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_1","call_id":"call_9","name":"read_file","arguments":""}}""")
        types("""{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"path\":"}""")
        // Server may never stream deltas; output_item.done carries the full body then.
        types("""{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"\"a.kt\"}"}""")
        types("""{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_9","name":"read_file","arguments":"{}"}}""")

        val end = types("""{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":100,"output_tokens":42,"input_tokens_details":{"cached_tokens":60}}}}""")

        val usage = end.filterIsInstance<StreamEvent.Usage>().single()
        assertEquals(100, usage.inputTokens)
        assertEquals(42, usage.outputTokens)
        assertEquals(60, usage.cachedInputTokens)

        val callEvent = end.filterIsInstance<StreamEvent.ToolCallReady>().single()
        assertEquals("call_9", callEvent.call.id)
        assertEquals("read_file", callEvent.call.name)
        assertEquals("""{"path":"a.kt"}""", callEvent.call.argumentsJson)

        // Calls land BEFORE Done, so the engine never sees a bare termination.
        val doneIdx = end.indexOfFirst { it is StreamEvent.Done }
        val callIdx = end.indexOfFirst { it is StreamEvent.ToolCallReady }
        assertTrue(callIdx in 0 until doneIdx)
        assertEquals("stop", (end.last { it is StreamEvent.Done } as StreamEvent.Done).finishReason)
    }

    @Test
    fun `incomplete reports its reason and errors surface as failure`() {
        val acc = LinkedHashMap<String, Triple<String, String, StringBuilder>>()
        val incomplete = provider.parseEvent(
            el("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}"""),
            acc,
        )
        assertEquals("max_output_tokens", incomplete.filterIsInstance<StreamEvent.Done>().single().finishReason)

        val failed = provider.parseEvent(
            el("""{"type":"response.failed","response":{"error":{"message":"rate limited"}}}"""),
            acc,
        )
        assertEquals("rate limited", failed.filterIsInstance<StreamEvent.Failure>().single().message)
    }

    @Test
    fun `off thinking omits the reasoning object entirely`() {
        val body = provider.buildRequestBody(
            config, "sys", listOf(ChatMessage(role = Role.USER, text = "hi")), emptyList(),
            RequestOptions(thinking = ThinkingLevel.OFF),
        )
        assertNull(body["reasoning"])
    }
}
