package com.androidharness.app.chatgpt

import com.androidharness.app.core.*
import com.androidharness.app.llm.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ChatGptProviderTest {
    private val config = ProviderConfig("chatgpt:oaiapp_fixture", "ChatGPT", ProviderType.OPENAI_RESPONSES, "https://untrusted.invalid/v1", "account-model")
    private val tools = listOf(ToolSchema("read_file", "Read a file", Json.parseToJsonElement("""{"type":"object","properties":{"path":{"type":"string"}}}""").jsonObject))
    private val call = """{"type":"function_call","id":"item1","call_id":"call1","name":"read_file","namespace":"harness","arguments":"{\"path\":\"a.kt\"}"}"""
    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }
    private fun completed(output: String = "[]") = """{"type":"response.completed","response":{"status":"completed","output":$output,"usage":{"input_tokens":20,"output_tokens":4}}}"""
    private fun provider(content: String, status: Int = 200, inspect: (Request) -> Unit = {}): ChatGptProvider {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(content.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        return ChatGptProvider({ _, _ -> "fixture-access" }, client)
    }

    @Test fun `subscription request omits rejected fields and namespaces all local tools`() {
        val provider = provider("")
        val body = provider.body(config, "Project instructions", listOf(ChatMessage(Role.USER, "Read a.kt"),
            ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(ToolCallData("call1", "read_file", "{}"))),
            ChatMessage(Role.TOOL, "file content", toolCallId = "call1")), tools, RequestOptions(cacheKey = "session"))
        assertFalse(body.containsKey("max_output_tokens")); assertFalse(body.containsKey("user"))
        assertFalse(body.getValue("store").jsonPrimitive.boolean)
        assertTrue(body.getValue("stream").jsonPrimitive.boolean)
        assertEquals("Project instructions", body.getValue("instructions").jsonPrimitive.content)
        val namespace = body.getValue("tools").jsonArray.single().jsonObject
        assertEquals("namespace", namespace.getValue("type").jsonPrimitive.content)
        assertEquals("harness", namespace.getValue("name").jsonPrimitive.content)
        assertEquals("read_file", namespace.getValue("tools").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("harness", body.getValue("input").jsonArray[1].jsonObject.getValue("namespace").jsonPrimitive.content)
        assertEquals("function_call_output", body.getValue("input").jsonArray[2].jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test fun `complete response emits a local tool call and only sends OAuth to official endpoint`() = runBlocking {
        val provider = provider(sse("""{"type":"response.output_text.delta","delta":"Checking"}""", completed("[$call]"))) { request ->
            assertEquals("https://api.openai.com/v1/responses", request.url.toString())
            assertEquals("Bearer fixture-access", request.header("Authorization"))
        }
        val events = provider.streamChat(config, "managed-oauth", "sys", listOf(ChatMessage(Role.USER, "hi")), tools, RequestOptions()).toList()
        assertEquals("Checking", events.filterIsInstance<StreamEvent.TextDelta>().single().text)
        assertEquals(ToolCallData("call1", "read_file", """{"path":"a.kt"}"""), events.filterIsInstance<StreamEvent.ToolCallReady>().single().call)
        assertEquals(20, events.filterIsInstance<StreamEvent.Usage>().single().inputTokens)
        assertEquals("stop", events.filterIsInstance<StreamEvent.Done>().single().finishReason)
        assertFalse(events.any { it is StreamEvent.Failure })
    }

    @Test fun `failed incomplete interrupted and foreign tool streams never release tools`() = runBlocking {
        val added = """{"type":"response.output_item.added","item":$call}"""
        val failed = """{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}"""
        val incomplete = """{"type":"response.incomplete","response":{"status":"incomplete"}}"""
        val foreign = completed("[${call.replace("harness", "other")}]")
        listOf(sse(added), sse(added, failed), sse(added, incomplete), sse(foreign)).forEach { content ->
            val events = provider(content).streamChat(config, "managed", "sys", emptyList(), tools, RequestOptions()).toList()
            assertTrue(events.any { it is StreamEvent.Failure })
            assertFalse(events.any { it is StreamEvent.ToolCallReady || it is StreamEvent.ToolCallBatch || it is StreamEvent.Done })
        }
    }

    @Test fun `401 refreshes once and limits point to subscription usage`() = runBlocking {
        val attempts = mutableListOf<Boolean>()
        var count = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val first = count++ == 0
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (first) 401 else 200).message("fixture")
                .body((if (first) "{}" else sse(completed())).toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val provider = ChatGptProvider({ _, refresh -> attempts += refresh; "fixture" }, client)
        val result = provider.streamChat(config, "managed", "sys", emptyList(), emptyList(), RequestOptions()).toList()
        assertEquals(listOf(false, true), attempts)
        assertTrue(result.any { it is StreamEvent.Done })
        val failure = provider("""{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}""", 429)
            .streamChat(config, "managed", "sys", emptyList(), emptyList(), RequestOptions()).toList().filterIsInstance<StreamEvent.Failure>().single()
        assertTrue(failure.message.contains(ChatGptProtocol.USAGE_URL))
    }

    @Test fun `encrypted reasoning survives saved tool calls and is replayed before their outputs`() = runBlocking {
        val reasoning = """{"type":"reasoning","id":"rs_1","encrypted_content":"opaque-fixture","summary":[]}"""
        val provider = provider(sse(completed("[$reasoning,$call]")))
        val events = provider.streamChat(config, "managed", "sys", emptyList(), tools, RequestOptions()).toList()
        val toolCall = events.filterIsInstance<StreamEvent.ToolCallReady>().single().call
        val stored = Json.encodeToString(toolCall)
        val restored = Json.decodeFromString<ToolCallData>(stored)
        val request = provider.body(config, "sys", listOf(ChatMessage(Role.ASSISTANT, toolCalls = listOf(restored)),
            ChatMessage(Role.TOOL, "result", toolCallId = restored.id)), tools, RequestOptions())
        assertEquals(listOf("reasoning", "function_call", "function_call_output"), request.getValue("input").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals("opaque-fixture", request.getValue("input").jsonArray.first().jsonObject.getValue("encrypted_content").jsonPrimitive.content)
        assertTrue(request.getValue("include").jsonArray.any { it.jsonPrimitive.content == "reasoning.encrypted_content" })
        val switched = provider.body(config.copy(id = "chatgpt:other-account"), "sys", listOf(ChatMessage(Role.ASSISTANT, toolCalls = listOf(restored))), tools, RequestOptions())
        assertFalse(switched.getValue("input").jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" })
    }
}
