package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
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
import java.util.TreeMap

/** Anthropic /v1/messages streaming with tool_use / tool_result blocks. */
class AnthropicProvider(
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
        val body = buildJsonObject {
            put("model", config.model)
            // models.dev knows the model's true budget ceiling; clamp to it
            // when present so X-High/Max never exceeds what the model accepts.
            val catalogMax = ModelsDev.entry("anthropic", config.model)?.budgetMax
            val budget = options.thinking.budgetTokens(options.maxOutputTokens)
                .let { if (catalogMax != null) minOf(it, catalogMax) else it }
            // Anthropic requires max_tokens > thinking budget
            put("max_tokens", maxOf(options.maxOutputTokens, budget + 8_192))
            put("stream", true)
            if (budget > 0) {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", budget)
                }
            }
            // System as a content array with a cache breakpoint, the system
            // prompt and tool schemas stay identical across turns, so they
            // should be served from the prompt cache on every follow-up.
            putJsonArray("system") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", systemPrompt)
                    putJsonObject("cache_control") { put("type", "ephemeral") }
                })
            }
            putJsonArray("messages") {
                applyCacheBreakpoints(serializeMessages(messages)).forEach { add(it) }
            }
            if (tools.isNotEmpty()) {
                val sortedTools = tools.sortedBy { it.name }
                putJsonArray("tools") {
                    sortedTools.forEachIndexed { index, schema ->
                        add(buildJsonObject {
                            put("name", schema.name)
                            put("description", schema.description)
                            put("input_schema", schema.parametersJson)
                            if (index == sortedTools.lastIndex) {
                                putJsonObject("cache_control") { put("type", "ephemeral") }
                            }
                        })
                    }
                }
            }
            // Stable per-session routing improves prompt-cache locality.
            options.cacheKey?.let { key ->
                putJsonObject("metadata") { put("user_id", key.take(64)) }
            }
        }

        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        // content block index -> (id, name, accumulated input json)
        val toolBlocks = TreeMap<Int, Triple<String, String, StringBuilder>>()
        var inputTokens = 0
        var cachedTokens = 0
        var cacheWriteTokens = 0
        var stopReason: String? = null

        return ProviderFactory.sseJson(request).mapNotNull { el ->
            val event = el as? JsonObject ?: return@mapNotNull null
            when (event["type"]?.jsonPrimitive?.contentOrNull) {
                "message_start" -> {
                    val usage = event["message"]?.jsonObjectOrAbsent()?.get("usage")?.jsonObjectOrAbsent()
                    // Anthropic's input_tokens EXCLUDES cache reads and writes;
                    // normalize to the total prompt size so hit-rate math is
                    // provider-agnostic (cached + write + uncached = total).
                    val uncached = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
                    cachedTokens = usage?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull ?: 0
                    cacheWriteTokens = usage?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull
                        ?: usage?.get("cache_creation")?.jsonObjectOrAbsent()?.let { cc ->
                            (cc["ephemeral_5m_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0) +
                                (cc["ephemeral_1h_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0)
                        } ?: 0
                    inputTokens = uncached + cachedTokens + cacheWriteTokens
                    null
                }

                "content_block_start" -> {
                    val index = event["index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val block = event["content_block"]?.jsonObjectOrAbsent() ?: return@mapNotNull null
                    if (block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                        val id = block["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val name = block["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        toolBlocks[index] = Triple(id, name, StringBuilder())
                    }
                    null
                }

                "content_block_delta" -> {
                    val index = event["index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val delta = event["delta"]?.jsonObjectOrAbsent() ?: return@mapNotNull null
                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull
                            ?.let { StreamEvent.TextDelta(it) }

                        "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull
                            ?.let { StreamEvent.ThinkingDelta(it) }

                        "input_json_delta" -> {
                            delta["partial_json"]?.jsonPrimitive?.contentOrNull
                                ?.let { toolBlocks[index]?.third?.append(it) }
                            null
                        }

                        else -> null
                    }
                }

                "content_block_stop" -> {
                    val index = event["index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val block = toolBlocks.remove(index) ?: return@mapNotNull null
                    val (id, name, args) = block
                    if (name.isBlank()) null
                    else StreamEvent.ToolCallReady(
                        ToolCallData(
                            id = id.ifBlank { "toolu_${System.nanoTime()}" },
                            name = name,
                            argumentsJson = args.toString().ifBlank { "{}" },
                        )
                    )
                }

                "message_delta" -> {
                    event["delta"]?.jsonObjectOrAbsent()?.get("stop_reason")
                        ?.jsonPrimitive?.contentOrNull?.let { stopReason = it }
                    val output = event["usage"]?.jsonObjectOrAbsent()
                        ?.get("output_tokens")?.jsonPrimitive?.intOrNull
                    output?.let {
                        StreamEvent.Usage(inputTokens, it, cachedTokens, cacheWriteTokens)
                    }
                }

                "message_stop" -> StreamEvent.Done(stopReason)

                "error" -> StreamEvent.Failure(
                    event["error"]?.jsonObjectOrAbsent()?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: "Anthropic stream error"
                )

                else -> null
            }
        }
    }

    // ------------------------------------------------------------------
    // Prompt caching
    // ------------------------------------------------------------------

    /**
     * Places the conversation cache breakpoints (Anthropic allows 4 in total;
     * system prompt and tool schemas already carry two).
     *
     * 1. A ROLLING breakpoint on the final message. Every request writes the
     *    whole conversation-so-far into the cache; the next request appends
     *    new messages and reads that entry back for the entire prefix. This is
     *    what keeps hit rates in the high 90s on long sessions.
     * 2. A STABLE breakpoint on the first user message. If the rolling entry
     *    expires (~5 min idle), the prefix up to the first user message stays
     *    cached, so a stale session degrades to a partial hit instead of a
     *    full miss.
     */
    internal fun applyCacheBreakpoints(messages: List<JsonObject>): List<JsonObject> {
        if (messages.isEmpty()) return messages
        val last = messages.lastIndex
        val firstUser = messages.indexOfFirst {
            it["role"]?.jsonPrimitive?.contentOrNull == "user"
        }.takeIf { it in 0 until last } ?: -1
        return messages.mapIndexed { index, msg ->
            when (index) {
                last, firstUser -> withCacheBreakpoint(msg)
                else -> msg
            }
        }
    }

    /** Returns [msg] with `cache_control: ephemeral` on its last cacheable block. */
    internal fun withCacheBreakpoint(msg: JsonObject): JsonObject {
        val content = msg["content"] ?: return msg
        val marked: JsonElement = when (content) {
            is JsonPrimitive -> buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", content.content)
                    putJsonObject("cache_control") { put("type", "ephemeral") }
                })
            }

            is JsonArray -> {
                // Anthropic rejects cache_control on thinking blocks; anchor on
                // the last ordinary block (text / tool_use / tool_result / image).
                val anchor = content.indexOfLast { el ->
                    el is JsonObject &&
                        el["type"]?.jsonPrimitive?.contentOrNull !in setOf("thinking", "redacted_thinking")
                }
                if (anchor < 0) return msg
                JsonArray(content.mapIndexed { i, el ->
                    if (i == anchor && el is JsonObject && !el.containsKey("cache_control")) {
                        JsonObject(
                            el.toMutableMap() +
                                ("cache_control" to buildJsonObject { put("type", "ephemeral") })
                        )
                    } else el
                })
            }

            else -> return msg
        }
        return JsonObject(msg.toMutableMap() + ("content" to marked))
    }

    private fun serializeMessages(messages: List<ChatMessage>): List<JsonObject> {
        val out = ArrayList<JsonObject>(messages.size)
        var i = 0
        while (i < messages.size) {
            when (messages[i].role) {
                Role.USER -> {
                    val m = messages[i]
                    out += if (m.imageData.isEmpty()) {
                        buildJsonObject {
                            put("role", "user")
                            put("content", m.text)
                        }
                    } else {
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                m.imageData.forEach { image ->
                                    add(buildJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", image.mime)
                                            put("data", image.base64)
                                        }
                                    })
                                }
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", m.text)
                                })
                            }
                        }
                    }
                    i++
                }

                Role.ASSISTANT -> {
                    val m = messages[i]
                    out += buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") {
                            if (m.text.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", m.text)
                                })
                            }
                            m.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", call.id)
                                    put("name", call.name)
                                    put("input", runCatching {
                                        json.parseToJsonElement(call.argumentsJson)
                                    }.getOrElse { buildJsonObject {} })
                                })
                            }
                        }
                    }
                    i++
                }

                Role.TOOL -> {
                    // Anthropic requires tool results grouped into a single user message
                    val parts = buildJsonArray {
                        while (i < messages.size && messages[i].role == Role.TOOL) {
                            val t = messages[i]
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", t.toolCallId ?: "")
                                put("content", t.text)
                                if (t.isError) put("is_error", true)
                            })
                            i++
                        }
                    }
                    out += buildJsonObject {
                        put("role", "user")
                        put("content", parts)
                    }
                }

                Role.SYSTEM -> {
                    val m = messages[i]
                    out += buildJsonObject {
                        put("role", "user")
                        put("content", m.text)
                    }
                    i++
                }
            }
        }
        return out
    }
}
