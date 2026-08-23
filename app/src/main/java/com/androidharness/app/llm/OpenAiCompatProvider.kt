package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.TreeMap

/**
 * OpenAI /chat/completions streaming. Works with OpenAI, OpenRouter, Groq,
 * Together, DeepSeek, Ollama, LM Studio and any compatible endpoint.
 *
 * Chunk parsing never short-circuits on the first recognized field: gateways
 * bundle `usage` into the finish chunk, ship `content` alongside `tool_calls`,
 * or stream reasoning and calls in one delta. Every field of a chunk is read
 * before any event is emitted.
 */
class OpenAiCompatProvider(
    private val client: OkHttpClient,
    private val json: Json,
) : LlmProvider {

    override fun streamChat(
        config: ProviderConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchema>,
        options: RequestOptions,
    ): Flow<StreamEvent> {
        val host = config.baseUrl.lowercase()
        val body = buildJsonObject {
            put("model", config.model)
            put("stream", true)
            // o-series / gpt-5 models reject legacy max_tokens outright.
            if (usesMaxCompletionTokens(host, config.model)) {
                put("max_completion_tokens", options.maxOutputTokens)
            } else {
                put("max_tokens", options.maxOutputTokens)
            }
            options.thinking.reasoningEffort?.let { effort ->
                // Not every compat server tolerates unknown request fields.
                if (supportsReasoningEffort(host)) put("reasoning_effort", effort)
            }
            // Pins all requests of one session to the same cache shard where
            // the endpoint supports it; gated by host so strict OpenAI-compat
            // servers that reject unknown fields keep working.
            if (options.cacheKey != null && supportsCacheKey(config.baseUrl)) {
                put("prompt_cache_key", options.cacheKey)
            }
            // Older llama.cpp/Ollama builds answer 400 to unknown fields; only
            // request usage accounting from endpoint families documenting it.
            if (supportsUsageAccounting(host)) {
                putJsonObject("stream_options") { put("include_usage", true) }
            }
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                messages.forEach { add(serializeMessage(it)) }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { schema ->
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", schema.name)
                                put("description", schema.description)
                                put("parameters", schema.parametersJson)
                            }
                        })
                    }
                }
            }
        }

        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        // Accumulates streamed tool-call fragments: index -> (id, name, args)
        val acc = TreeMap<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>()

        return flow {
            // Some gateways attach a usage block to many (or every) SSE chunk
            // instead of only the final one. Counting each would multiply the
            // session totals, so keep the LAST usage seen — final counts are
            // the authoritative ones — and emit exactly one Usage per request.
            var pendingUsage: StreamEvent.Usage? = null
            ProviderFactory.sseJson(request).collect { el ->
                parseChunk(el, acc).forEach { event ->
                    if (event is StreamEvent.Usage) pendingUsage = event else emit(event)
                }
            }
            pendingUsage?.let { emit(it) }
            // Some gateways close the stream after [DONE] without ever sending
            // a finish_reason chunk — flush whatever fragments accumulated so
            // requested tool calls aren't silently dropped.
            val leftover = drainAccumulated(acc)
            when {
                leftover.size == 1 -> emit(StreamEvent.ToolCallReady(leftover.first()))
                leftover.size > 1 -> emit(StreamEvent.ToolCallBatch(leftover))
            }
        }
    }

    /**
     * Parses one SSE payload against the shared tool-call accumulator.
     * Returns every event the chunk carries (often several, sometimes none).
     */
    internal fun parseChunk(
        el: JsonElement,
        acc: TreeMap<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>,
    ): List<StreamEvent> {
        val chunk = el as? JsonObject ?: return emptyList()

        // Gateways disagree on error shape: {"error": "msg"} vs {"error": {"message": msg}}.
        chunk["error"]?.let { err ->
            val message = when (err) {
                is JsonPrimitive -> err.contentOrNull
                is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
                else -> null
            } ?: "Upstream server error"
            return listOf(StreamEvent.Failure(message))
        }

        val events = mutableListOf<StreamEvent>()

        // Usage may ride on the final chunk together with finish_reason and
        // tool calls — collect it without skipping the rest of the chunk.
        chunk["usage"]?.jsonObject?.let { usage ->
            val input = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull
            val output = usage["completion_tokens"]?.jsonPrimitive?.intOrNull
            // OpenAI/OpenRouter report inside prompt_tokens_details; DeepSeek's
            // direct API uses a flat prompt_cache_hit_tokens instead.
            val cached = usage["prompt_tokens_details"]?.jsonObject
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: usage["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
                ?: 0
            if (input != null || output != null) {
                events += StreamEvent.Usage(input ?: 0, output ?: 0, cached)
            }
        }

        val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return events
        val delta = choice["delta"]?.jsonObject

        delta?.get("content")?.let { content ->
            when {
                // Explicit JSON null rides along on tool-call/reasoning chunks;
                // it must not become text nor hide the rest of the delta.
                content is JsonNull -> {}
                content is JsonPrimitive && content.isString && content.content.isNotEmpty() ->
                    events += StreamEvent.TextDelta(content.content)

                // A few gateways deliver content as an array of typed parts.
                content is JsonArray -> {
                    val text = content.joinToString("") { part ->
                        (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                    if (text.isNotEmpty()) events += StreamEvent.TextDelta(text)
                }
            }
        }

        // Reasoning content: DeepSeek/OpenRouter use reasoning_content, others "reasoning".
        ((delta?.get("reasoning_content") ?: delta?.get("reasoning")) as? JsonPrimitive)?.let { reasoning ->
            if (reasoning.isString && reasoning.content.isNotEmpty()) {
                events += StreamEvent.ThinkingDelta(reasoning.content)
            }
        }

        delta?.get("tool_calls")?.jsonArray?.forEach { tcEl ->
            val tc = tcEl.jsonObject
            val index = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
            val entry = acc.getOrPut(index) {
                Triple(StringBuilder(), StringBuilder(), StringBuilder())
            }
            tc["id"]?.jsonPrimitive?.contentOrNull?.let { entry.first.append(it) }
            tc["function"]?.jsonObject?.let { fn ->
                fn["name"]?.jsonPrimitive?.contentOrNull?.let { entry.second.append(it) }
                fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { entry.third.append(it) }
            }
        }

        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finish ->
            val ready = drainAccumulated(acc)
            when {
                ready.size == 1 -> events += StreamEvent.ToolCallReady(ready.first())
                ready.size > 1 -> events += StreamEvent.ToolCallBatch(ready)
            }
            events += StreamEvent.Done(finish)
        }

        return events
    }

    /** Materializes accumulated fragments into tool calls and clears [acc]. */
    internal fun drainAccumulated(
        acc: TreeMap<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>,
    ): List<ToolCallData> = acc.values.mapNotNull { (id, name, args) ->
        if (name.isBlank()) null
        else ToolCallData(
            id = id.toString().ifBlank { "call_${System.nanoTime()}" },
            name = name.toString(),
            argumentsJson = args.toString().ifBlank { "{}" },
        )
    }.also { acc.clear() }

    /** Hosts known to understand `reasoning_effort` (OpenRouter normalizes it for other models). */
    private fun supportsReasoningEffort(host: String): Boolean =
        "api.openai.com" in host || "openrouter.ai" in host || "api.groq.com" in host

    /**
     * Whether to ask this endpoint for token accounting
     * (`stream_options.include_usage`). Sent to every REMOTE host: many
     * gateways report usage only when asked, and without the flag their cache
     * hit rates and token totals never update. Bare-local servers are excluded
     * because older llama.cpp/Ollama/LM Studio builds reject unknown fields.
     */
    internal fun supportsUsageAccounting(baseUrl: String): Boolean {
        // strip scheme, path, then port so bare hostnames match cleanly
        val host = baseUrl.lowercase()
            .substringAfter("://", "").substringBefore('/')
            .substringBefore(':')
        val local = host == "localhost" || host == "0.0.0.0" || host.startsWith("127.") ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
        return !local
    }

    /**
     * Hosts that document `prompt_cache_key` (or a compatible alias). Sent only
     * to these so stricter OpenAI-compat servers (Ollama, LM Studio, …) never
     * see an unknown field.
     */
    private fun supportsCacheKey(baseUrl: String): Boolean {
        val host = baseUrl.lowercase()
        return "api.openai.com" in host || "openrouter.ai" in host ||
            "api.deepseek.com" in host || "api.moonshot" in host
    }

    private fun serializeMessage(m: ChatMessage): JsonObject = when (m.role) {
        Role.USER -> buildJsonObject {
            put("role", "user")
            if (m.imageData.isEmpty()) {
                put("content", m.text)
            } else {
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", m.text)
                    })
                    m.imageData.forEach { image ->
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:${image.mime};base64,${image.base64}")
                            }
                        })
                    }
                }
            }
        }

        Role.ASSISTANT -> buildJsonObject {
            put("role", "assistant")
            put("content", m.text)
            if (m.toolCalls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    m.toolCalls.forEach { call ->
                        add(buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.argumentsJson)
                            }
                        })
                    }
                }
            }
        }

        Role.TOOL -> buildJsonObject {
            put("role", "tool")
            put("tool_call_id", m.toolCallId ?: "")
            put("content", m.text)
        }

        Role.SYSTEM -> buildJsonObject {
            put("role", "system")
            put("content", m.text)
        }
    }

    companion object {
        /** Model families that reject `max_tokens` in favor of `max_completion_tokens`. */
        private val NEW_TOKEN_PARAM_MODELS = Regex("""^(o\d|gpt-5)""")

        fun usesMaxCompletionTokens(baseUrl: String, model: String): Boolean =
            "api.openai.com" in baseUrl.lowercase() ||
                NEW_TOKEN_PARAM_MODELS.containsMatchIn(model.lowercase().trim())
    }
}
