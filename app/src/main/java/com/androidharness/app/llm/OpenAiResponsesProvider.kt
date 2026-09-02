package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI's Responses API (`POST /v1/responses`), the newer gpt-5/o-series
 * API where reasoning is a first-class object (`reasoning.effort` + streamed
 * summary), tools are flattened function items, and the whole request can be
 * serverless (`store: false`). Some newer OpenAI models are only available
 * here, not on /chat/completions. For all OpenAI-compatible gateways nothing
 * changes, they keep using [OpenAiCompatProvider].
 */
class OpenAiResponsesProvider(
    @Suppress("unused") private val client: OkHttpClient,
    @Suppress("unused") private val json: Json,
) : LlmProvider {

    override fun streamChat(
        config: ProviderConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchema>,
        options: RequestOptions,
    ): Flow<StreamEvent> {
        val body = buildRequestBody(config, systemPrompt, messages, tools, options)
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/responses")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        // Function-call fragments accumulate by item id: (call id, name, args)
        val acc = LinkedHashMap<String, Triple<String, String, StringBuilder>>()

        return flow {
            // Usage lands once on response.completed, hold it and emit after
            // the stream so each request tallies exactly once.
            var pendingUsage: StreamEvent.Usage? = null
            ProviderFactory.sseJson(request).collect { el ->
                parseEvent(el, acc).forEach { event ->
                    if (event is StreamEvent.Usage) pendingUsage = event else emit(event)
                }
            }
            pendingUsage?.let { emit(it) }
            // Defensive flush: a stream that ends without output_item.done.
            drainAccumulated(acc)?.let { emit(it) }
        }
    }

    // ------------------------------------------------------------------
    // Request building (pure, unit-tested)

    internal fun buildRequestBody(
        config: ProviderConfig,
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchema>,
        options: RequestOptions,
    ): JsonObject = buildJsonObject {
        put("model", config.model)
        put("stream", true)
        // Stateless: we resend full history every request, so nothing is kept server-side.
        put("store", false)
        put("max_output_tokens", options.maxOutputTokens)
        put("instructions", systemPrompt)
        options.cacheKey?.let { key ->
            val cleanKey = key.take(64)
            put("prompt_cache_key", cleanKey)
            put("user", "pc_$cleanKey")
        }
        if (options.thinking != com.androidharness.app.agent.ThinkingLevel.OFF) {
            com.androidharness.app.agent.ThinkingSpecs
                .effortWire(config.model, options.thinking, "openai")
                ?.let { effort ->
                    putJsonObject("reasoning") {
                        put("effort", effort)
                        put("summary", "auto")
                    }
                }
        }
        putJsonArray("input") {
            messages.forEach { message -> serializeMessage(message).forEach { add(it) } }
        }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.sortedBy { it.name }.forEach { schema ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("name", schema.name)
                        put("description", schema.description)
                        put("parameters", schema.parametersJson)
                        put("strict", false)
                    })
                }
            }
        }
    }

    /**
     * One chat message → zero or more Responses input items. Tool calls and
     * their results are standalone items (`function_call` / `function_call_output`),
     * which is also the replay shape the API expects back.
     */
    private fun serializeMessage(m: ChatMessage): List<JsonObject> = when (m.role) {
        Role.USER -> listOf(buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                if (m.text.isNotBlank() || m.imageData.isEmpty()) {
                    add(buildJsonObject {
                        put("type", "input_text")
                        put("text", m.text)
                    })
                }
                m.imageData.forEach { image ->
                    add(buildJsonObject {
                        put("type", "input_image")
                        put("image_url", "data:${image.mime};base64,${image.base64}")
                    })
                }
            }
        })

        Role.ASSISTANT -> buildList {
            if (m.text.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "assistant")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "output_text")
                            put("text", m.text)
                        })
                    }
                })
            }
            m.toolCalls.forEach { call ->
                add(buildJsonObject {
                    put("type", "function_call")
                    put("call_id", call.id)
                    put("name", call.name)
                    put("arguments", call.argumentsJson)
                })
            }
        }

        Role.TOOL -> buildList {
            add(buildJsonObject {
                put("type", "function_call_output")
                put("call_id", m.toolCallId ?: "")
                put("output", m.text)
            })
            m.imageData.forEach { image ->
                add(buildJsonObject {
                    put("type", "input_image")
                    put("image_url", "data:${image.mime};base64,${image.base64}")
                })
            }
        }

        Role.SYSTEM -> listOf(buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", m.text)
                })
            }
        })
    }

    // ------------------------------------------------------------------
    // Stream parsing (pure given the accumulator, unit-tested)

    /**
     * Parses one SSE payload (Responses event types ride in the `type` field)
     * against the function-call accumulator; returns every event it carries.
     */
    internal fun parseEvent(
        el: kotlinx.serialization.json.JsonElement,
        acc: LinkedHashMap<String, Triple<String, String, StringBuilder>>,
    ): List<StreamEvent> {
        val event = el as? JsonObject ?: return emptyList()

        event["error"]?.jsonObjectOrAbsent()?.let { err ->
            val message = err["message"]?.jsonPrimitive?.contentOrNull ?: "Upstream server error"
            return listOf(StreamEvent.Failure(message))
        }

        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val events = mutableListOf<StreamEvent>()
        when {
            type == "response.failed" -> {
                val message = event["response"]?.jsonObjectOrAbsent()
                    ?.get("error")?.jsonObjectOrAbsent()
                    ?.get("message")?.jsonPrimitive?.contentOrNull ?: "Request failed"
                events += StreamEvent.Failure(message)
            }

            type == "response.output_text.delta" ->
                event["delta"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { events += StreamEvent.TextDelta(it) }

            // Reasoning summaries stream as their own item type.
            type == "response.reasoning_summary_text.delta" ->
                event["delta"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { events += StreamEvent.ThinkingDelta(it) }

            type == "response.output_item.added" -> {
                val item = event["item"]?.jsonObjectOrAbsent()
                if (item?.get("type")?.jsonPrimitive?.contentOrNull == "function_call") {
                    val itemId = item["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull ?: itemId
                    val name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    acc[itemId] = Triple(callId, name, StringBuilder())
                }
            }

            type == "response.function_call_arguments.delta" -> {
                val itemId = event["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                event["delta"]?.jsonPrimitive?.contentOrNull
                    ?.let { acc[itemId]?.third?.append(it) }
            }

            type == "response.output_item.done" -> {
                val item = event["item"]?.jsonObjectOrAbsent()
                if (item?.get("type")?.jsonPrimitive?.contentOrNull == "function_call") {
                    val itemId = item["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val entry = acc[itemId]
                    if (entry != null && entry.third.isEmpty()) {
                        // Some servers never stream argument deltas; the
                        // finished item carries the full body.
                        item["arguments"]?.jsonPrimitive?.contentOrNull
                            ?.let { entry.third.append(it) }
                    }
                }
            }

            type == "response.completed" || type == "response.incomplete" -> {
                val response = event["response"]?.jsonObjectOrAbsent()
                response?.get("usage")?.jsonObjectOrAbsent()?.let { usage ->
                    val input = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                    val output = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                    val inputDetails = usage["input_tokens_details"]?.jsonObjectOrAbsent()
                    val cached = inputDetails?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                        ?: inputDetails?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull
                        ?: usage["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
                        ?: 0
                    val cacheWrite = inputDetails?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull
                        ?: usage["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull
                        ?: 0
                    events += StreamEvent.Usage(input, output, cached, cacheWrite)
                }
                drainAccumulated(acc)?.let { events += it }
                val reason = if (type == "response.incomplete") {
                    response?.get("incomplete_details")?.jsonObjectOrAbsent()
                        ?.get("reason")?.jsonPrimitive?.contentOrNull ?: "incomplete"
                } else {
                    "stop"
                }
                events += StreamEvent.Done(reason)
            }
        }
        return events
    }

    /** Materializes accumulated function calls into tool-call events and clears [acc]. */
    private fun drainAccumulated(
        acc: LinkedHashMap<String, Triple<String, String, StringBuilder>>,
    ): StreamEvent? {
        val calls = acc.values.mapNotNull { (callId, name, args) ->
            if (name.isBlank()) null
            else ToolCallData(
                id = callId.ifBlank { "call_${System.nanoTime()}" },
                name = name,
                argumentsJson = args.toString().ifBlank { "{}" },
            )
        }
        acc.clear()
        return when {
            calls.size == 1 -> StreamEvent.ToolCallReady(calls.first())
            calls.size > 1 -> StreamEvent.ToolCallBatch(calls)
            else -> null
        }
    }
}
