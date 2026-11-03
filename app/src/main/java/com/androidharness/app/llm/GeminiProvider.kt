package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.jsonArrayOrAbsent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
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

/** Gemini streamGenerateContent with function calling. */
class GeminiProvider(
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
        val thinking = options.resolveThinking(config)
        val body = buildRequestBody(config, systemPrompt, messages, tools, options, thinking)

        val url = config.baseUrl.trimEnd('/') +
            "/models/${config.model}:streamGenerateContent?alt=sse"
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { ProviderHeaders.apply(this, config) }
            .build()


        // usageMetadata arrives on every chunk with CUMULATIVE counts; keep the
        // latest and emit exactly one Usage event when the stream ends so
        // session totals aren't inflated by per-chunk additions and aren't
        // truncated by a usage-only chunk after the final candidate.
        var inputTokens = 0
        var thoughtTokens: Int? = null
        var outputTokens = 0
        var cachedTokens = 0
        var cacheReported = false
        var finishReason: String? = null

        return flow {
            var callCounter = 0
            val nativeParts = mutableListOf<JsonObject>()
            emit(StreamEvent.ThinkingConfigured(thinking.detail))
            ProviderFactory.sseJson(request, ProviderHeaders.client(config, client)).collect { el ->
                val chunk = el as? JsonObject ?: return@collect

                chunk["error"]?.jsonObjectOrAbsent()?.get("message")?.jsonPrimitive?.contentOrNull
                    ?.let {
                        emit(StreamEvent.Failure(it, code = providerErrorStatus(chunk["error"]?.jsonObjectOrAbsent()),
                            errorCode = providerErrorCode(chunk["error"]?.jsonObjectOrAbsent())))
                        return@collect
                    }

                chunk["usageMetadata"]?.jsonObjectOrAbsent()?.let { usage ->
                    inputTokens = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: inputTokens
                    outputTokens = usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: outputTokens
                    thoughtTokens = usage["thoughtsTokenCount"]?.jsonPrimitive?.intOrNull ?: thoughtTokens
                    cacheReported = cacheReported || usage["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull != null
                    cachedTokens = usage["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull ?: cachedTokens
                }

                val candidate = chunk["candidates"]?.jsonArrayOrAbsent()?.firstOrNull()?.jsonObjectOrAbsent()
                // Sits on the candidate, a sibling of content, read it before
                // the parts extraction below, which may come up empty.
                candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull
                    ?.let { finishReason = it }
                val parts = candidate?.get("content")?.jsonObjectOrAbsent()
                    ?.get("parts")?.jsonArrayOrAbsent()
                    ?: return@collect

                val events = mutableListOf<StreamEvent>()
                for (partEl in parts) {
                    val part = partEl.jsonObjectOrAbsent() ?: continue
                    nativeParts += part
                    val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
                    part["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                        events += if (isThought) StreamEvent.ThinkingDelta(text)
                        else StreamEvent.TextDelta(text)
                    }
                    part["functionCall"]?.jsonObjectOrAbsent()?.let { fc ->
                        val name = fc["name"]?.jsonPrimitive?.contentOrNull ?: return@let
                        val args = fc["args"]?.toString() ?: "{}"
                        callCounter++
                        events += StreamEvent.ToolCallReady(
                            ToolCallData(
                                id = fc["id"]?.jsonPrimitive?.contentOrNull ?: "gemini_${name}_$callCounter",
                                name = name,
                                argumentsJson = args,
                            )
                        )
                    }
                }
                when {
                    events.isEmpty() -> {}
                    events.size == 1 -> emit(events.first())
                    else -> emit(StreamEvent.Batch(events))
                }
            }

            if (inputTokens > 0) {
                emit(StreamEvent.Usage(inputTokens, outputTokens + (thoughtTokens ?: 0), cachedTokens,
                    cacheReported = cacheReported, reasoningTokens = thoughtTokens))
            }
            if (nativeParts.isNotEmpty()) emit(StreamEvent.ResponseState(
                com.androidharness.app.core.ProviderMessageState.of(config, "gemini", nativeParts)))
            emit(StreamEvent.Done(finishReason))
        }
    }

    internal fun buildRequestBody(config: ProviderConfig, systemPrompt: String, messages: List<ChatMessage>,
        tools: List<ToolSchema>, options: RequestOptions,
        thinking: com.androidharness.app.agent.ThinkingSpecs.ResolvedThinking = options.resolveThinking(config)): JsonObject {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", systemPrompt) })
                }
            }
            putJsonArray("contents") {
                serializeMessages(messages, config).forEach { add(it) }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    add(buildJsonObject {
                        putJsonArray("functionDeclarations") {
                            tools.sortedBy { it.name }.forEach { schema ->
                                add(buildJsonObject {
                                    put("name", schema.name)
                                    put("description", schema.description)
                                    // Tool schemas use JSON Schema (including dictionary headers).
                                    // Gemini's typed `parameters` rejects additionalProperties.
                                    put("parametersJsonSchema", schema.parametersJson)
                                })
                            }
                        }
                    })
                }
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", thinking.maxOutputTokens)
                if (thinking.spec.style == com.androidharness.app.agent.ThinkingSpecs.Style.EFFORT ||
                    thinking.spec.style == com.androidharness.app.agent.ThinkingSpecs.Style.BUDGET) {
                    putJsonObject("thinkingConfig") {
                        thinking.effort?.let { put("thinkingLevel", it) }
                        thinking.budget?.let { put("thinkingBudget", it) }
                        put("includeThoughts", thinking.level != com.androidharness.app.agent.ThinkingLevel.OFF)
                    }
                }
            }
        }

        return options.applyReasoningMapping(config, body)
    }

    internal fun serializeMessages(messages: List<ChatMessage>, config: ProviderConfig): List<JsonObject> {
        val out = ArrayList<JsonObject>(messages.size)
        val nativeCallIds = messages.flatMap { m -> m.generation.state?.takeIf { it.matches(config, "gemini", m) }?.items.orEmpty() }
            .mapNotNull { it["functionCall"]?.jsonObjectOrAbsent()?.get("id")?.jsonPrimitive?.contentOrNull }.toSet()
        var i = 0
        while (i < messages.size) {
            when (messages[i].role) {
                Role.USER -> {
                    val m = messages[i]
                    out += buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            m.imageData.forEach { image ->
                                add(buildJsonObject {
                                    putJsonObject("inlineData") {
                                        put("mimeType", image.mime)
                                        put("data", image.base64)
                                    }
                                })
                            }
                            add(buildJsonObject { put("text", m.text) })
                        }
                    }
                    i++
                }

                Role.ASSISTANT -> {
                    val m = messages[i]
                    out += buildJsonObject {
                        put("role", "model")
                        val native = m.generation.state?.takeIf { it.matches(config, "gemini", m) }
                        if (native != null) put("parts", kotlinx.serialization.json.JsonArray(native.items))
                        else putJsonArray("parts") {
                            if (m.text.isNotBlank()) {
                                add(buildJsonObject { put("text", m.text) })
                            }
                            m.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    putJsonObject("functionCall") {
                                        put("name", call.name)
                                        put("args", runCatching {
                                            json.parseToJsonElement(call.argumentsJson)
                                        }.getOrElse { buildJsonObject {} })
                                    }
                                })
                            }
                        }
                    }
                    i++
                }

                Role.TOOL -> {
                    // function responses are grouped into one user-role content
                    val parts = buildJsonArray {
                        while (i < messages.size && messages[i].role == Role.TOOL) {
                            val t = messages[i]
                            add(buildJsonObject {
                                putJsonObject("functionResponse") {
                                    t.toolCallId?.takeIf { it in nativeCallIds }?.let { put("id", it) }
                                    put("name", t.toolName ?: "unknown")
                                    putJsonObject("response") {
                                        put("output", t.text)
                                    }
                                }
                            })
                            t.imageData.forEach { image ->
                                add(buildJsonObject {
                                    putJsonObject("inlineData") {
                                        put("mimeType", image.mime)
                                        put("data", image.base64)
                                    }
                                })
                            }
                            i++
                        }
                    }
                    out += buildJsonObject {
                        put("role", "user")
                        put("parts", parts)
                    }
                }

                Role.SYSTEM -> {
                    val m = messages[i]
                    out += buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", m.text) })
                        }
                    }
                    i++
                }
            }
        }
        return out
    }
}
