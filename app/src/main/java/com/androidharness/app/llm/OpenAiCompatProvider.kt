package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.jsonArrayOrAbsent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
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
        // GPT-6 tool use with reasoning requires Responses on the official OpenAI endpoint.
        val routed = requestConfig(config, tools.isNotEmpty())
        if (routed.type == ProviderType.OPENAI_RESPONSES) {
            return OpenAiResponsesProvider(client, json).streamChat(routed,
                apiKey, systemPrompt, messages, tools, options.forTransportRoute(config, routed))
        }
        val thinking = options.resolveThinking(config)
        val body = buildRequestBody(config, systemPrompt, messages, tools, options, thinking)

        val request = buildRequest(
            config,
            apiKey,
            body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()),
            options,
        )

        // Accumulates streamed tool-call fragments, keyed by CALL ID (with the
        // stream index only as a fallback). Some gateways stream parallel tool
        // calls with every fragment carrying index 0, keying by index merged
        // them into one garbled call, silently dropping the second subagent.
        return flow {
            emit(StreamEvent.ThinkingConfigured(thinking.detail))
            val acc = LinkedHashMap<String, Triple<StringBuilder, StringBuilder, StringBuilder>>()
            val indexToId = HashMap<Int, String>()
            // Some gateways attach a usage block to many (or every) SSE chunk
            // instead of only the final one. Counting each would multiply the
            // session totals, so keep the LAST usage seen, final counts are
            // the authoritative ones, and emit exactly one Usage per request.
            var pendingUsage: StreamEvent.Usage? = null
            val reasoningDetails = mutableListOf<JsonObject>()
            val reasoningText = StringBuilder()
            var completed = false
            var doneSeen = false
            var failed = false
            ProviderFactory.sseJson(request, ProviderHeaders.client(config, client), onDone = { completed = true }).takeWhile { el ->
                val delta = (el as? JsonObject)?.get("choices")?.jsonArrayOrAbsent()?.firstOrNull()
                    ?.jsonObjectOrAbsent()?.get("delta")?.jsonObjectOrAbsent()
                delta?.get("reasoning_details")?.jsonArrayOrAbsent()?.forEach { item ->
                    (item as? JsonObject)?.let { reasoningDetails += it }
                }
                ((delta?.get("reasoning_content") ?: delta?.get("reasoning")) as? JsonPrimitive)
                    ?.contentOrNull?.let(reasoningText::append)
                val events = parseChunk(el, acc, indexToId)
                events.forEach { event ->
                    if (event is StreamEvent.Done) doneSeen = true
                    if (event is StreamEvent.Failure) failed = true
                    if (event is StreamEvent.Usage) pendingUsage = event else emit(event)
                }
                // An in-band error terminates this attempt, just like a failed
                // HTTP response. Never consume trailing deltas after it.
                events.none { it is StreamEvent.Failure }
            }.collect { }
            pendingUsage?.let { emit(it) }
            val state = buildJsonObject {
                if (reasoningDetails.isNotEmpty()) put("reasoning_details", mergeReasoningDetails(reasoningDetails))
                if (reasoningText.isNotEmpty()) put("reasoning_content", reasoningText.toString())
            }
            if (state.isNotEmpty()) emit(StreamEvent.ResponseState(
                com.androidharness.app.core.ProviderMessageState.of(config, "chat_completions", listOf(state))))
            // Some gateways close the stream after [DONE] without ever sending
            // a finish_reason chunk, flush whatever fragments accumulated so
            // requested tool calls aren't silently dropped.
            val leftover = drainAccumulated(acc)
            when {
                leftover.size == 1 -> emit(StreamEvent.ToolCallReady(leftover.first()))
                leftover.size > 1 -> emit(StreamEvent.ToolCallBatch(leftover))
            }
            // [DONE] is an explicit completion even when a gateway omits finish_reason.
            if (completed && !doneSeen && !failed) emit(StreamEvent.Done())
        }
    }

    internal fun buildRequestBody(config: ProviderConfig, systemPrompt: String, messages: List<ChatMessage>,
        tools: List<ToolSchema>, options: RequestOptions,
        thinking: com.androidharness.app.agent.ThinkingSpecs.ResolvedThinking = options.resolveThinking(config)): JsonObject {
        val host = config.baseUrl.lowercase()
        val body = buildJsonObject {
            put("model", config.model)
            put("stream", true)
            // o-series / gpt-5 models reject legacy max_tokens outright.
            if (usesMaxCompletionTokens(host, config.model)) {
                put("max_completion_tokens", thinking.maxOutputTokens)
            } else {
                put("max_tokens", thinking.maxOutputTokens)
            }
            if (com.androidharness.app.agent.ThinkingSpecs.usesRouterReasoning(config)) {
                com.androidharness.app.agent.ThinkingSpecs.routerReasoning(config, options.thinking, thinking)?.let { reasoning ->
                    putJsonObject("reasoning") {
                        reasoning.effort?.let { put("effort", it) }
                        reasoning.enabled?.let { put("enabled", it) }
                        reasoning.maxTokens?.let { put("max_tokens", it) }
                    }
                }
            } else {
                thinking.spec.toggleField?.let { field ->
                    putJsonObject(field) {
                        put("type", if (thinking.level == com.androidharness.app.agent.ThinkingLevel.OFF) "disabled" else "enabled")
                        if (thinking.level != com.androidharness.app.agent.ThinkingLevel.OFF) {
                            if (ModelsDev.providerKeyFor(config.baseUrl)?.substringBefore("-coding-plan") in setOf("zhipuai", "zai")) put("clear_thinking", false)
                            if (ModelsDev.providerKeyFor(config.baseUrl) in setOf("moonshotai", "moonshotai-cn") && config.model.startsWith("kimi-k2.6")) put("keep", "all")
                        }
                    }
                }
                thinking.effort?.takeUnless { it == "none" && thinking.spec.toggleField != null }
                    ?.let { put("reasoning_effort", it) }
            }
            // Pins all requests of one session to the same cache shard where
            // the endpoint supports it; gated by host so strict OpenAI-compat
            // servers that reject unknown fields keep working.
            if (options.cacheKey != null && PromptCachePolicy.supportsKey(config.baseUrl)) {
                val cleanKey = options.cacheKey.take(64)
                put("prompt_cache_key", cleanKey)
                put("user", "pc_$cleanKey")
            }
            // Older llama.cpp/Ollama builds answer 400 to unknown fields; only
            // request usage accounting from endpoint families documenting it.
            if (supportsUsageAccounting(host)) {
                putJsonObject("stream_options") { put("include_usage", true) }
            }
            // Strict chat templates (Qwen3.5/3.6 on SGLang/vLLM) reject any
            // role=system message after index 0 with "System message must be
            // at the beginning." History can legitimately carry system notes
            // (compaction summaries, run notices), so fold them into the
            // leading prompt and keep exactly one system message, at index 0.
            val normalized = CompatHistory.normalize(messages).map {
                com.androidharness.app.tools.ImageInspectionDelivery.update(it, it.imageData)
            }
            val systemNotes = normalized
                .filter { it.role == Role.SYSTEM }
                .mapNotNull { it.text.takeIf { text -> text.isNotBlank() } }
            val fullSystemPrompt = if (systemNotes.isEmpty()) systemPrompt
                else buildString {
                    if (systemPrompt.isNotBlank()) append(systemPrompt.trimEnd())
                    append("\n\n[Notes recorded by the app earlier in this conversation]\n")
                    append(systemNotes.joinToString("\n\n"))
                }
            val history = normalized.filter { it.role != Role.SYSTEM }
            putJsonArray("messages") {
                if ("openrouter.ai" in host && (config.model.contains("claude", true) || config.model.contains("anthropic", true))) {
                    add(buildJsonObject {
                        put("role", "system")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", fullSystemPrompt)
                                putJsonObject("cache_control") { put("type", "ephemeral") }
                            })
                        }
                    })
                } else {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", fullSystemPrompt)
                    })
                }
                val closeUps = mutableListOf<com.androidharness.app.core.ImageData>()
                val imageLabels = mutableListOf<String>()
                history.forEachIndexed { index, message ->
                    // Chat Completions accepts images in user messages, while
                    // strict endpoints reject image parts in tool messages.
                    val toolImage = message.role == Role.TOOL && message.imageData.isNotEmpty()
                    if (toolImage) {
                        val metadata = com.androidharness.app.tools.ImageInspectionDelivery.envelope(message.text)?.body
                        message.imageData.forEachIndexed { imageIndex, data ->
                            imageLabels += "Image ${closeUps.size + 1}: tool_call_id=${message.toolCallId}, tool=${message.toolName}, attachment_index=${imageIndex + 1}" +
                                metadata?.let { " " + JsonObject(it.filterKeys { key -> key in setOf("source_image_id", "image_id", "caption", "attached_image", "source_region") }) }.orEmpty()
                            closeUps += data
                        }
                    }
                    add(serializeMessage(if (toolImage) message.copy(imageData = emptyList()) else message, config))
                    if (closeUps.isNotEmpty() && history.getOrNull(index + 1)?.role != Role.TOOL) {
                        add(serializeMessage(ChatMessage(role = Role.USER,
                            text = "Images actually attached by the preceding tools, in this exact attachment order. Match attachment_id and tool_call_id; omitted results have no image here.\n" + imageLabels.joinToString("\n"),
                            imageData = closeUps.toList()), config))
                        closeUps.clear()
                        imageLabels.clear()
                    }
                }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.sortedBy { it.name }.forEach { schema ->
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

        return options.applyReasoningMapping(config, body)
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
        val choice = chunk["choices"]?.jsonArrayOrAbsent()?.firstOrNull()?.jsonObjectOrAbsent()

        // Gateways disagree on error shape: {"error": "msg"} vs {"error": {"message": msg}}.
        val err = chunk["error"]?.takeUnless { it is JsonNull }
            ?: choice?.get("error")?.takeUnless { it is JsonNull }
        if (err != null || choice?.get("finish_reason")?.primitiveOrAbsent()?.contentOrNull == "error") {
            val message = when (err) {
                is JsonPrimitive -> err.contentOrNull
                is JsonObject -> (err["message"] as? JsonPrimitive)?.contentOrNull
                else -> null
            } ?: "Upstream server error"
            val code = ((err as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
            // Incomplete arguments must never become executable tool calls,
            // even if the gateway follows the error with [DONE] or a finish chunk.
            acc.clear()
            indexToId.clear()
            return listOf(StreamEvent.Failure(message, code, errorCode = providerErrorCode(err as? JsonObject),
                retryAfterSeconds = providerRetryAfterSeconds(chunk)))
        }

        val events = mutableListOf<StreamEvent>()

        // Usage may ride on the final chunk together with finish_reason and
        // tool calls, collect it without skipping the rest of the chunk.
        chunk["usage"]?.jsonObjectOrAbsent()?.let { usage ->
            val input = usage["prompt_tokens"]?.primitiveOrAbsent()?.intOrNull
            val output = usage["completion_tokens"]?.primitiveOrAbsent()?.intOrNull
            val promptDetails = usage["prompt_tokens_details"]?.jsonObjectOrAbsent()
                ?: usage["input_tokens_details"]?.jsonObjectOrAbsent()
            val cached = promptDetails?.get("cached_tokens")?.primitiveOrAbsent()?.intOrNull
                ?: promptDetails?.get("cache_read_input_tokens")?.primitiveOrAbsent()?.intOrNull
                ?: promptDetails?.get("cache_read_tokens")?.primitiveOrAbsent()?.intOrNull
                ?: promptDetails?.get("cached_prompt_tokens")?.primitiveOrAbsent()?.intOrNull
                ?: promptDetails?.get("cached_tokens_count")?.primitiveOrAbsent()?.intOrNull
                ?: promptDetails?.get("prompt_cache_hit_tokens")?.primitiveOrAbsent()?.intOrNull
                ?: usage["prompt_cache_hit_tokens"]?.primitiveOrAbsent()?.intOrNull
                ?: usage["cache_read_input_tokens"]?.primitiveOrAbsent()?.intOrNull
                ?: usage["cache_read_tokens"]?.primitiveOrAbsent()?.intOrNull
                ?: usage["cached_tokens"]?.primitiveOrAbsent()?.intOrNull
                ?: usage["cached_prompt_tokens"]?.primitiveOrAbsent()?.intOrNull
                ?: usage["prompt_cached_tokens"]?.primitiveOrAbsent()?.intOrNull
                ?: usage["cache_hit_tokens"]?.primitiveOrAbsent()?.intOrNull
                ?: usage["cachedContentTokenCount"]?.primitiveOrAbsent()?.intOrNull
            val cacheWrite = promptDetails?.get("cache_creation_input_tokens")?.primitiveOrAbsent()?.intOrNull
                ?: promptDetails?.get("cache_write_tokens")?.primitiveOrAbsent()?.intOrNull
                ?: promptDetails?.get("cache_creation_tokens")?.primitiveOrAbsent()?.intOrNull
                ?: usage["cache_creation_input_tokens"]?.primitiveOrAbsent()?.intOrNull
                ?: usage["cache_write_tokens"]?.primitiveOrAbsent()?.intOrNull
                ?: usage["prompt_cache_write_tokens"]?.primitiveOrAbsent()?.intOrNull
                ?: usage["cache_creation_tokens"]?.primitiveOrAbsent()?.intOrNull
                ?: 0
            if (input != null || output != null) {
                println("HarnessUsage: Parsed usage: input=$input, output=$output, cached=$cached, cacheWrite=$cacheWrite, raw=$usage")
                events += StreamEvent.Usage(input ?: 0, output ?: 0, cached ?: 0, cacheWrite, cacheReported = cached != null,
                    reasoningTokens = (usage["completion_tokens_details"] ?: usage["output_tokens_details"])
                        ?.jsonObjectOrAbsent()?.get("reasoning_tokens")?.primitiveOrAbsent()?.intOrNull)
            }
        }

        if (choice == null) return events
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
                        (part as? JsonObject)?.get("text")?.primitiveOrAbsent()?.contentOrNull.orEmpty()
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

        if (delta?.get("reasoning_content") == null && delta?.get("reasoning") == null) {
            delta?.get("reasoning_details")?.jsonArrayOrAbsent()?.forEach { item ->
                val detail = item.jsonObjectOrAbsent() ?: return@forEach
                (detail["text"] ?: detail["summary"])?.primitiveOrAbsent()?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }?.let { events += StreamEvent.ThinkingDelta(it) }
            }
        }
        delta?.get("tool_calls")?.jsonArrayOrAbsent()?.forEach { tcEl ->
            val tc = tcEl.jsonObjectOrAbsent() ?: return@forEach
            val index = tc["index"]?.primitiveOrAbsent()?.intOrNull ?: 0
            val newId = tc["id"]?.primitiveOrAbsent()?.contentOrNull
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
                Triple(StringBuilder(newId ?: "call_${System.nanoTime()}"), StringBuilder(), StringBuilder())
            }
            if (!newId.isNullOrBlank() && entry.first.isEmpty()) {
                entry.first.append(newId)
            }
            tc["function"]?.jsonObjectOrAbsent()?.let { fn ->
                fn["name"]?.primitiveOrAbsent()?.contentOrNull?.let { entry.second.append(it) }
                when (val arguments = fn["arguments"]) {
                    is JsonPrimitive -> arguments.contentOrNull?.let {
                        entry.third.append(it)
                        events += StreamEvent.ToolCallDelta(entry.first.toString(), entry.second.toString(), it)
                    }
                    // Object arguments are a complete snapshot, not a string fragment.
                    // Preserve nested values and replace any earlier snapshot.
                    is JsonObject -> {
                        entry.third.setLength(0)
                        entry.third.append(arguments.toString())
                        events += StreamEvent.ToolCallDelta(entry.first.toString(), entry.second.toString(), arguments.toString(), replace = true)
                    }
                    null -> {}
                    else -> {
                        acc.clear()
                        indexToId.clear()
                        return listOf(StreamEvent.Failure("Upstream returned unsupported tool arguments; expected a JSON string or object"))
                    }
                }
            }
        }

        choice["finish_reason"]?.primitiveOrAbsent()?.contentOrNull?.let { finish ->
            val ready = drainAccumulated(acc)
            when {
                ready.size == 1 -> events += StreamEvent.ToolCallReady(ready.first())
                ready.size > 1 -> events += StreamEvent.ToolCallBatch(ready)
            }
            events += StreamEvent.Done(finish)
        }

        return events
    }

    /** Optional scalar fields from gateways may have an unexpected JSON shape. */
    private fun JsonElement.primitiveOrAbsent(): JsonPrimitive? = this as? JsonPrimitive

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
        // android.util.Log is unmocked on the JVM test harness, never let
        // diagnostics throw inside the stream path.
        runCatching {
            if (it.size > 1) {
                android.util.Log.d("HarnessSpawn", "drained ${it.size} parallel tool calls: ${it.map { c -> c.id }}")
            }
        }
        acc.clear()
    }

    internal fun buildRequest(
        config: ProviderConfig,
        apiKey: String,
        body: RequestBody,
        options: RequestOptions,
    ): Request {
        val host = config.baseUrl.lowercase()
        val requestBuilder = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
        // A local server with no key must get no header at all: "Bearer local"
        // is not a credential, and some builds reject an empty one.
        if (apiKey.isNotBlank() && apiKey != LOCAL_KEY) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        if (HarnessProvider.isOpenCode(config) &&
            (config.id != HarnessProvider.ID || HarnessProvider.isOpenCode(config.baseUrl))) {
            HarnessProvider.withSession(requestBuilder, options.cacheKey)
        }
        if ("openrouter.ai" in host) {
            requestBuilder.header("HTTP-Referer", "https://github.com/Sanuu7/AndroidHarness")
            requestBuilder.header("X-Title", "Android Harness")
        }
        return ProviderHeaders.apply(requestBuilder, config, options.debugAttempt, options.debugKeySlot, options.debugGroup, options.cacheKey).post(body).build()
    }

    /**
     * Whether to ask this endpoint for token accounting
     * (`stream_options.include_usage`). Sent to every REMOTE host: many
     * gateways report usage only when asked, and without the flag their cache
     * hit rates and token totals never update. Bare-local servers are excluded
     * because older llama.cpp/Ollama/LM Studio builds reject unknown fields.
     */
    internal fun supportsUsageAccounting(baseUrl: String): Boolean = !Companion.isLocalHost(baseUrl)

    /** SSE details are fragments of indexed blocks; concatenation preserves encrypted signatures. */
    internal fun mergeReasoningDetails(parts: List<JsonObject>): JsonArray {
        val blocks = linkedMapOf<String, MutableMap<String, JsonElement>>()
        parts.forEachIndexed { position, part ->
            val key = part["type"].toString() + ":" + (part["index"]?.toString() ?: part["id"]?.toString() ?: "unindexed_$position")
            val block = blocks.getOrPut(key) { linkedMapOf() }
            part.forEach { (name, value) ->
                val old = block[name] as? JsonPrimitive
                if (name in setOf("text", "summary", "data") && old?.isString == true && (value as? JsonPrimitive)?.isString == true)
                    block[name] = JsonPrimitive(old.content + value.content)
                else block[name] = value
            }
        }
        return JsonArray(blocks.values.map(::JsonObject))
    }

    private fun serializeMessage(m: ChatMessage, config: ProviderConfig): JsonObject = when (m.role) {
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
            val state = m.generation.state?.takeIf { it.matches(config, "chat_completions", m) }
            state?.items?.firstOrNull()?.let { fields -> fields.forEach { (key, value) -> put(key, value) } }
            if (ModelsDev.providerKeyFor(config.baseUrl) == "deepseek" && state == null) {
                // Legacy messages have no provider-bound continuation; keep the required field present.
                put("reasoning_content", "")
            }
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
            if (m.imageData.isEmpty()) {
                put("content", m.text)
            } else {
                putJsonArray("content") {
                    if (m.text.isNotBlank()) {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", m.text)
                        })
                    }
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

        Role.SYSTEM -> buildJsonObject {
            put("role", "system")
            put("content", m.text)
        }
    }

    companion object {
        fun requestConfig(config: ProviderConfig, hasTools: Boolean): ProviderConfig {
            val nativeOpenAi = runCatching { java.net.URI(config.baseUrl).host == "api.openai.com" }.getOrDefault(false)
            return if (config.type == ProviderType.OPENAI_COMPAT && nativeOpenAi && hasTools &&
                config.model.substringAfterLast('/').startsWith("gpt-6")) config.copy(type = ProviderType.OPENAI_RESPONSES) else config
        }

        /** Placeholder so key checks pass for a local server that has no key. */
        const val LOCAL_KEY = "local"

        /** True for bare-local servers (llama.cpp/Ollama/LM Studio) that reject unknown fields. */
        fun isLocalHost(baseUrl: String): Boolean {
            // strip scheme, path, then port so bare hostnames match cleanly
            val host = baseUrl.lowercase()
                .substringAfter("://", "").substringBefore('/')
                .substringBefore(':')
            return host == "localhost" || host == "0.0.0.0" || host.startsWith("127.") ||
                host.startsWith("192.168.") || host.startsWith("10.") ||
                Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
        }

        /** Model families that reject `max_tokens` in favor of `max_completion_tokens`. */
        private val NEW_TOKEN_PARAM_MODELS = Regex("""^(o\d|gpt-5)""")

        fun usesMaxCompletionTokens(baseUrl: String, model: String): Boolean =
            "api.openai.com" in baseUrl.lowercase() ||
                NEW_TOKEN_PARAM_MODELS.containsMatchIn(model.lowercase().trim())
    }
}
