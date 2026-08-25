package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
enum class ProviderType(val label: String, val defaultBaseUrl: String) {
    OPENAI_COMPAT("OpenAI-compatible", "https://api.openai.com/v1"),
    OPENAI_RESPONSES("OpenAI Responses", "https://api.openai.com/v1"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta"),
}

/** Wire-endpoint naming for the protocol picker (what the API actually speaks). */
val ProviderType.endpointPath: String
    get() = when (this) {
        ProviderType.OPENAI_COMPAT -> "/chat/completions"
        ProviderType.OPENAI_RESPONSES -> "/v1/responses"
        ProviderType.ANTHROPIC -> "/v1/messages"
        ProviderType.GEMINI -> ":streamGenerateContent"
    }

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val model: String,
)

data class ToolSchema(
    val name: String,
    val description: String,
    val parametersJson: JsonObject,
)

/**
 * Per-request generation options derived from user settings.
 *
 * [cacheKey] is a stable per-session identifier (the session id). Providers use
 * it where the API supports explicit cache routing — OpenAI's `prompt_cache_key`
 * and Anthropic's `metadata.user_id` — so consecutive requests of one session
 * land on the same cache shard.
 */
data class RequestOptions(
    val maxOutputTokens: Int = 32_768,
    val thinking: com.androidharness.app.agent.ThinkingLevel =
        com.androidharness.app.agent.ThinkingLevel.OFF,
    val cacheKey: String? = null,
)

sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ThinkingDelta(val text: String) : StreamEvent
    data class ToolCallReady(val call: ToolCallData) : StreamEvent
    data class ToolCallBatch(val calls: List<ToolCallData>) : StreamEvent
    data class Batch(val events: List<StreamEvent>) : StreamEvent

    /**
     * One per request, emitted once the final counts are known.
     *
     * [inputTokens] is the TOTAL prompt size — uncached input + tokens served
     * from the cache + tokens written to the cache — so the cache hit rate is
     * simply `cachedInputTokens / inputTokens` for every provider.
     * [cachedInputTokens] counts cache reads only; [cacheWriteTokens]
     * (Anthropic) are billed at the 1.25x write premium and are reported
     * separately for honest cost math.
     */
    data class Usage(
        val inputTokens: Int,
        val outputTokens: Int,
        val cachedInputTokens: Int = 0,
        val cacheWriteTokens: Int = 0,
    ) : StreamEvent
    data class Failure(val message: String) : StreamEvent

    /**
     * Emitted once per request when the stream terminates. [finishReason] is
     * the provider's raw termination code — "stop", "length", "tool_calls",
     * Anthropic's "max_tokens"/"end_turn"/"tool_use", Gemini's "STOP"/
     * "MAX_TOKENS" — or null when the server never reported one.
     */
    data class Done(val finishReason: String? = null) : StreamEvent
}

class ApiException(val code: Int, message: String) : IOException("HTTP $code: $message")

/**
 * [JsonObject] accessor that treats an explicit JSON `null` exactly like an
 * absent field. Several gateways emit `"usage": null`, `"delta": null`, etc.;
 * the strict `.jsonObject` throws on those and kills the stream.
 */
fun JsonElement?.jsonObjectOrAbsent(): JsonObject? = this as? JsonObject

/** [JsonArray] accessor treating an explicit JSON `null` like an absent field. */
fun JsonElement?.jsonArrayOrAbsent(): JsonArray? = this as? JsonArray

interface LlmProvider {
    fun streamChat(
        config: ProviderConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchema>,
        options: RequestOptions,
    ): Flow<StreamEvent>
}

object ProviderFactory {
    val json: Json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE streams are long-lived
        .build()

    fun create(type: ProviderType): LlmProvider = when (type) {
        ProviderType.OPENAI_COMPAT -> OpenAiCompatProvider(client, json)
        ProviderType.OPENAI_RESPONSES -> OpenAiResponsesProvider(client, json)
        ProviderType.ANTHROPIC -> AnthropicProvider(client, json)
        ProviderType.GEMINI -> GeminiProvider(client, json)
    }

    /**
     * POSTs [request] and parses the response body as server-sent events,
     * emitting the JSON payload of each `data:` line. Cancellation of the
     * collector cancels the underlying HTTP call.
     */
    fun sseJson(request: Request): Flow<JsonElement> = callbackFlow {
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val errBody = try {
                            it.body?.string()?.take(2000)
                        } catch (_: Exception) {
                            null
                        }
                        close(ApiException(it.code, errBody ?: it.message))
                        return
                    }
                    try {
                        val source = it.body!!.source()
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload == "[DONE]") break
                            if (payload.isEmpty()) continue
                            try {
                                trySend(json.parseToJsonElement(payload))
                            } catch (_: Exception) {
                                // skip unparseable keep-alive lines
                            }
                        }
                        close()
                    } catch (e: Exception) {
                        close(e)
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)
}
