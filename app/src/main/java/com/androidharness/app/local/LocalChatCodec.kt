package com.androidharness.app.local

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.StreamEvent
import com.androidharness.app.llm.ToolSchema
import kotlinx.serialization.json.*
import java.util.UUID

/** Preserve tool IDs, tool results and media order for llama.cpp's native templates. */
internal object LocalChatCodec {
    fun plainChatMessages(messages: List<ChatMessage>): List<ChatMessage> = messages
        .filter { it.role == Role.USER || it.role == Role.ASSISTANT }
        .map { it.copy(toolCalls = emptyList(), toolCallId = null, toolName = null, thinking = "") }
        .filter { it.role == Role.USER || it.text.isNotBlank() || it.imageData.isNotEmpty() || it.images.isNotEmpty() }

    fun request(system: String, messages: List<ChatMessage>, tools: List<ToolSchema>, thinking: Boolean = false,
        includeAgentContext: Boolean = true): String = buildJsonObject {
        put("enable_thinking", thinking)
        putJsonArray("messages") {
            if (includeAgentContext && system.isNotBlank()) add(buildJsonObject { put("role", "system"); put("content", system) })
            (if (includeAgentContext) messages else plainChatMessages(messages)).forEach { message -> add(buildJsonObject {
                put("role", message.role.name.lowercase())
                if (message.imageData.isEmpty()) put("content", message.text)
                else putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", message.text) })
                    message.imageData.forEach {
                        add(buildJsonObject { put("type", "media_marker"); put("text", "<__media__>") })
                    }
                }
                if (message.thinking.isNotBlank()) put("reasoning_content", message.thinking)
                message.toolCallId?.let { put("tool_call_id", it) }
                message.toolName?.let { put("name", it) }
                if (message.toolCalls.isNotEmpty()) putJsonArray("tool_calls") {
                    message.toolCalls.forEach { call -> add(buildJsonObject {
                        put("id", call.id); put("type", "function")
                        putJsonObject("function") { put("name", call.name); put("arguments", call.argumentsJson) }
                    }) }
                }
            }) }
        }
        putJsonArray("tools") { (if (includeAgentContext) tools else emptyList()).forEach { tool -> add(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", tool.name); put("description", tool.description); put("parameters", tool.parametersJson)
            }
        }) } }
    }.toString()

    fun events(data: String): List<StreamEvent> = buildList {
        val event = Json.parseToJsonElement(data).jsonObject
        event["text"]?.jsonPrimitive?.content?.takeIf(String::isNotEmpty)?.let { add(StreamEvent.TextDelta(it)) }
        event["thinking"]?.jsonPrimitive?.content?.takeIf(String::isNotEmpty)?.let { add(StreamEvent.ThinkingDelta(it)) }
    }

    fun finish(result: JsonObject, tools: List<ToolSchema>): List<StreamEvent> = buildList {
        val ended = result.getValue("ended").jsonPrimitive.boolean
        val calls = if (ended) result["calls"]?.jsonArray.orEmpty().map { item ->
            val call = item.jsonObject
            val name = call.getValue("name").jsonPrimitive.content
            require(tools.any { it.name == name }) { "Local model requested an unknown tool: $name" }
            val arguments = call.getValue("arguments").jsonObject
            val schema = tools.first { it.name == name }.parametersJson
            schema["required"]?.jsonArray.orEmpty().forEach {
                require(arguments.containsKey(it.jsonPrimitive.content)) { "Local tool call is missing ${it.jsonPrimitive.content}." }
            }
            ToolCallData(call["id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: "local-${UUID.randomUUID()}", name, arguments.toString())
        } else emptyList()
        if (calls.isNotEmpty()) add(StreamEvent.ToolCallBatch(calls))
        add(StreamEvent.Usage(result.getValue("input").jsonPrimitive.int, result.getValue("output").jsonPrimitive.int))
        add(StreamEvent.Done(if (calls.isNotEmpty()) "tool_calls" else if (ended) "stop" else "length"))
    }
}
