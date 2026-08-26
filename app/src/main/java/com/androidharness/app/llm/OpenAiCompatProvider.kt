package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.jsonArrayOrAbsent
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
            // Reasoning/thinking. OpenRouter takes the unified `reasoning`
            // object and normalizes it across every provider it fronts — this
            // is the ONLY way to reach Claude/Gemini thinking through the
            // OpenAI-compatible API, whose native budget parameters live on
            // their own protocols. The models.dev catalog supplies the exact
            // per-model effort vocabulary (or a bare enabled toggle); the
            // shipped family table is the offline fallback. Other remote
            // OpenAI-compat hosts get `reasoning_effort` for models with an
            // enumerated/known vocabulary; inherent reasoners (DeepSeek) get
            // nothing and bare-local servers never see an unknown field.
            if (options.thinking != com.androidharness.app.agent.ThinkingLevel.OFF) {
                if ("openrouter.ai" in host) {
                    com.androidharness.app.agent.ThinkingSpecs
                        .openRouterReasoning(config.model, options.thinking)
                        ?.let { reasoning ->
                            putJsonObject("reasoning") {
                                reasoning.effort?.let { put("effort", it) }
                                reasoning.enabled?.let { put("enabled", it) }
                            }
                        }
                } else if (!isLocalHost(host)) {
                    com.androidharness.app.agent.ThinkingSpecs
                        .effortWire(config.model, options.thinking, ModelsDev.providerKeyFor(host))
                        ?.let { put("reasoning_effort", it) }
                }
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

        // Accumulates streamed tool-call fragments, keyed by CALL ID (with the
        // stream index only as a fallback). Some gateways stream parallel tool
        // calls with every fragment carrying index 0 — keying by index merged
        // them into one garbled call, silently dropping the second subagent.
        val acc = LinkedHashMap<String, Triple<StringBuilder, StringBuilder, StringBuilder>>()
        val indexToId = HashMap<Int, String>()

        return flow {
            // Some gateways attach a usage block to many (or every) SSE chunk
            // instead of only the final one. Counting each would multiply the
            // session totals, so keep the LAST usage seen — final counts are
            // the authoritative ones — and emit exactly one Usage per request.
            var pendingUsage: StreamEvent.Usage? = null
            ProviderFactory.sseJson(request).collect { el ->
                parseChunk(el, acc, indexToId).forEach { event ->
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
        acc: LinkedHashMap<String, Triple<StringBuilder, StringBuilder, StringBuilder>>,
        indexToId: MutableMap<Int, String> = HashMap(),
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
        chunk["usage"]?.jsonObjectOrAbsent()?.let { usage ->
            val input = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull
            val output = usage["completion_tokens"]?.jsonPrimitive?.intOrNull
            val promptDetails = usage["prompt_tokens_details"]?.jsonObjectOrAbsent()
                ?: usage["input_tokens_details"]?.jsonObjectOrAbsent()
            val cached = promptDetails?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: promptDetails?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull
                ?: promptDetails?.get("cache_read_tokens")?.jsonPrimitive?.intOrNull
                ?: promptDetails?.get("cached_prompt_tokens")?.jsonPrimitive?.intOrNull
                ?: promptDetails?.get("cached_tokens_count")?.jsonPrimitive?.intOrNull
                ?: promptDetails?.get("prompt_cache_hit_tokens")?.jsonPrimitive?.intOrNull
                ?: usage["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
                ?: usage["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull
                ?: usage["cache_read_tokens"]?.jsonPrimitive?.intOrNull
                ?: usage["cached_tokens"]?.jsonPrimitive?.intOrNull
                ?: usage["cached_prompt_tokens"]?.jsonPrimitive?.intOrNull
                ?: usage["prompt_cached_tokens"]?.jsonPrimitive?.intOrNull
                ?: usage["cache_hit_tokens"]?.jsonPrimitive?.intOrNull
                ?: usage["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull
                ?: 0
            val cacheWrite = promptDetails?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull
                ?: promptDetails?.get("cache_write_tokens")?.jsonPrimitive?.intOrNull
                ?: promptDetails?.get("cache_creation_tokens")?.jsonPrimitive?.intOrNull
                ?: usage["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull
                ?: usage["cache_write_tokens"]?.jsonPrimitive?.intOrNull
                ?: usage["prompt_cache_write_tokens"]?.jsonPrimitive?.intOrNull
                ?: usage["cache_creation_tokens"]?.jsonPrimitive?.intOrNull
                ?: 0
            if (input != null || output != null) {
                events += StreamEvent.Usage(input ?: 0, output ?: 0, cached, cacheWrite)
            }
        }

        val choice = chunk["choices"]?.jsonArrayOrAbsent()?.firstOrNull()?.jsonObjectOrAbsent() ?: return events
        val delta = choice["delta"]?.jsonObjectOrAbsent()

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

        delta?.get("tool_calls")?.jsonArrayOrAbsent()?.forEach { tcEl ->
            val tc = tcEl.jsonObjectOrAbsent() ?: return@forEach
            val index = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
            val newId = tc["id"]?.jsonPrimitive?.contentOrNull
            // Key by call id; the stream index only links fragments that carry
            // no id. A gateway that reuses index 0 for a SECOND call (new id)
            // therefore opens a new entry instead of corrupting the first.
            val key = if (!newId.isNullOrBlank()) {
                indexToId[index] = newId
                newId
            } else {
                indexToId[index] ?: "idx_$index"
            }
            val entry = acc.getOrPut(key) {
                Triple(StringBuilder(newId ?: ""), StringBuilder(), StringBuilder())
            }
            if (!newId.isNullOrBlank() && entry.first.isEmpty()) {
                entry.first.append(newId)
            }
            tc["function"]?.jsonObjectOrAbsent()?.let { fn ->
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
        acc: LinkedHashMap<String, Triple<StringBuilder, StringBuilder, StringBuilder>>,
    ): List<ToolCallData> = acc.values.mapNotNull { (id, name, args) ->
        if (name.isBlank()) null
        else ToolCallData(
            id = id.toString().ifBlank { "call_${System.nanoTime()}" },
            name = name.toString(),
            argumentsJson = args.toString().ifBlank { "{}" },
        )
    }.also {
        // android.util.Log is unmocked on the JVM test harness — never let
        // diagnostics throw inside the stream path.
        runCatching {
            if (it.size > 1) {
                android.util.Log.d("HarnessSpawn", "drained ${it.size} parallel tool calls: ${it.map { c -> c.id }}")
            }
        }
        acc.clear()
    }

    /** True for bare-local servers (llama.cpp/Ollama/LM Studio) that reject unknown fields. */
    internal fun isLocalHost(baseUrl: String): Boolean {
        // strip scheme, path, then port so bare hostnames match cleanly
        val host = baseUrl.lowercase()
            .substringAfter("://", "").substringBefore('/')
            .substringBefore(':')
        return host == "localhost" || host == "0.0.0.0" || host.startsWith("127.") ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
    }

    /**
     * Whether to ask this endpoint for token accounting
     * (`stream_options.include_usage`). Sent to every REMOTE host: many
     * gateways report usage only when asked, and without the flag their cache
     * hit rates and token totals never update. Bare-local servers are excluded
     * because older llama.cpp/Ollama/LM Studio builds reject unknown fields.
     */
    internal fun supportsUsageAccounting(baseUrl: String): Boolean = !isLocalHost(baseUrl)

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
