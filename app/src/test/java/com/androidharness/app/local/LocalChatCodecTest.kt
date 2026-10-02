package com.androidharness.app.local

import com.androidharness.app.core.*
import com.androidharness.app.llm.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalChatCodecTest {
    private val tool = ToolSchema("read_file", "Read a file", Json.parseToJsonElement("""{"type":"object","required":["path"],"properties":{"path":{"type":"string"}}}""").jsonObject)

    @Test fun `plain chat omits instructions tools and previous agent context`() {
        val history = listOf(
            ChatMessage(Role.SYSTEM, "Old project instructions"),
            ChatMessage(Role.USER, "Inspect"),
            ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("call-1", "read_file", "{}"))),
            ChatMessage(Role.TOOL, "Large file contents", toolCallId = "call-1", toolName = "read_file"),
            ChatMessage(Role.ASSISTANT, "Here is the result", thinking = "Private reasoning"),
            ChatMessage(Role.USER, "Hi", imageData = listOf(ImageData("image/png", "image"))))
        val request = Json.parseToJsonElement(LocalChatCodec.request("Harness instructions", history, listOf(tool),
            includeAgentContext = false)).jsonObject
        assertTrue(request.getValue("tools").jsonArray.isEmpty())
        val messages = request.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.getValue("role").jsonPrimitive.content })
        assertEquals("Here is the result", messages[1].getValue("content").jsonPrimitive.content)
        assertEquals("media_marker", messages.last().getValue("content").jsonArray[1].jsonObject.getValue("type").jsonPrimitive.content)
        assertFalse(request.toString().contains("Harness instructions"))
        assertFalse(request.toString().contains("Large file contents"))
        assertFalse(messages.any { "tool_calls" in it || "reasoning_content" in it || "tool_call_id" in it })
    }

    @Test fun `existing settings default to plain local chat and opt in survives serialization`() {
        val oldSettings = Json.decodeFromString<com.androidharness.app.data.AppSettings>("{}")
        assertFalse(oldSettings.localModelAgentContext)
        val optedIn = oldSettings.copy(localModelAgentContext = true)
        assertTrue(Json.decodeFromString<com.androidharness.app.data.AppSettings>(
            Json.encodeToString(com.androidharness.app.data.AppSettings.serializer(), optedIn)).localModelAgentContext)
    }

    @Test fun `local requests preserve tool calls results and image order`() {
        val history = listOf(
            ChatMessage(Role.USER, "Inspect", imageData = listOf(ImageData("image/png", "image"))),
            ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("call-1", "read_file", "{\"path\":\"a.kt\"}"))),
            ChatMessage(Role.TOOL, "file contents", toolCallId = "call-1", toolName = "read_file"))
        val request = Json.parseToJsonElement(LocalChatCodec.request("system", history, listOf(tool))).jsonObject
        assertEquals("read_file", request.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
        val messages = request.getValue("messages").jsonArray
        assertEquals("media_marker", messages[1].jsonObject.getValue("content").jsonArray[1].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("call-1", messages[2].jsonObject.getValue("tool_calls").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("call-1", messages[3].jsonObject.getValue("tool_call_id").jsonPrimitive.content)
        assertEquals("tool", messages[3].jsonObject.getValue("role").jsonPrimitive.content)
    }

    private fun result(ended: Boolean, name: String = "read_file", arguments: String = "{\"path\":\"a.kt\"}") =
        Json.parseToJsonElement("""{"input":20,"output":8,"ended":$ended,"calls":[{"id":"","name":"$name","arguments":$arguments}]}""").jsonObject

    @Test fun `completed tool call reaches the agent and truncated calls never execute`() {
        val events = LocalChatCodec.finish(result(true), listOf(tool))
        val call = (events.first() as StreamEvent.ToolCallBatch).calls.single()
        assertEquals("read_file", call.name)
        assertTrue(call.id.isNotBlank())
        assertEquals("tool_calls", (events.last() as StreamEvent.Done).finishReason)
        val truncated = LocalChatCodec.finish(result(false), listOf(tool))
        assertFalse(truncated.any { it is StreamEvent.ToolCallBatch })
        assertEquals("length", (truncated.last() as StreamEvent.Done).finishReason)
    }

    @Test fun `unknown tools and missing arguments cannot reach execution`() {
        assertThrows(IllegalArgumentException::class.java) { LocalChatCodec.finish(result(true, "not_registered"), listOf(tool)) }
        assertThrows(IllegalArgumentException::class.java) { LocalChatCodec.finish(result(true, arguments = "{}"), listOf(tool)) }
    }
}
