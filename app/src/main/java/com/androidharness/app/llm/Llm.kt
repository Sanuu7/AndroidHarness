package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

/**
 * Local servers (Ollama, LM Studio, llama.cpp) need no key, and an empty
 * `Authorization` header makes some of them reject the request. A key the user
 * typed still rides out, so a reverse proxy in front of one keeps working.
 */
fun ProviderConfig.effectiveApiKey(stored: String?): String =
    stored?.takeIf { it.isNotBlank() }
        ?: if (OpenAiCompatProvider.isLocalHost(baseUrl)) OpenAiCompatProvider.LOCAL_KEY else ""

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
    /** Picker visibility; saved chats and running work keep their selected connection. */
    @kotlinx.serialization.Transient
    val enabled: Boolean = true,
    /** Loaded from encrypted credentials; excluded from provider/task JSON and chat exports. */
    @kotlinx.serialization.Transient
    val headers: List<ProviderHeader> = emptyList(),
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
 * it where the API supports explicit cache routing, OpenAI's `prompt_cache_key`
 * and Anthropic's `metadata.user_id`, so consecutive requests of one session
 * land on the same cache shard.
 */
data class RequestOptions(
    val maxOutputTokens: Int = 32_768,
    val thinking: com.androidharness.app.agent.ThinkingLevel =
        com.androidharness.app.agent.ThinkingLevel.OFF,
    val cacheKey: String? = null,
    /** The same provider catalog snapshot used by the chat controls, also available to model overrides. */
    val modelCatalogs: Map<String, List<ModelEntry>> = emptyMap(),
    val customReasoning: Map<String, com.androidharness.app.agent.CustomReasoningConfig> = emptyMap(),
    val customThinkingLevels: Map<String, String> = emptyMap(),
) {
    fun resolveThinking(config: ProviderConfig, entry: ModelEntry? = null) =
        com.androidharness.app.agent.CustomReasoning.resolve(config, thinking, maxOutputTokens,
            entry ?: modelCatalogs[config.id]?.firstOrNull { it.id == config.model },
            customReasoning[com.androidharness.app.agent.CustomReasoning.key(config)],
            customThinkingLevels[com.androidharness.app.agent.CustomReasoning.key(config)])

    /** Carry the original connection's overrides across an internal transport-only redirect. */
    fun forTransportRoute(from: ProviderConfig, to: ProviderConfig): RequestOptions {
        require(from.id == to.id && from.model == to.model && from.baseUrl.trimEnd('/') == to.baseUrl.trimEnd('/'))
        val source = com.androidharness.app.agent.CustomReasoning.key(from)
        val target = com.androidharness.app.agent.CustomReasoning.key(to)
        if (source == target) return this
        val custom = customReasoning[source]
        val selection = customThinkingLevels[source]
        if (custom == null && selection == null) return this
        return copy(customReasoning = if (custom == null) customReasoning else customReasoning + (target to custom),
            customThinkingLevels = if (selection == null) customThinkingLevels else customThinkingLevels + (target to selection))
    }

    fun applyReasoningMapping(config: ProviderConfig, body: JsonObject): JsonObject {
        val resolved = resolveThinking(config)
        val custom = resolved.customConfig ?: return body
        ReasoningMapping.validate(custom, config, com.androidharness.app.agent.ThinkingSpecs.forProvider(config,
            modelCatalogs[config.id]?.firstOrNull { it.id == config.model }))
        val expression = requireNotNull(resolved.requestMapping) { "No inherited reasoning mapping is available. Add a CEL mapping." }
        return ReasoningMapping.merge(body, ReasoningMapping.evaluate(expression, requireNotNull(resolved.customLevel)))
    }
}

sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ThinkingDelta(val text: String) : StreamEvent
    data class ResponseState(val state: com.androidharness.app.core.ProviderMessageState) : StreamEvent
    /** Request configuration, not model output. A retry can replace it before any output arrives. */
    data class ThinkingConfigured(val label: String) : StreamEvent
    /** Display-only argument fragments. These never authorize or execute a tool. */
    data class ToolCallDelta(val id: String, val name: String, val arguments: String, val replace: Boolean = false) : StreamEvent
    data class ToolCallReady(val call: ToolCallData) : StreamEvent
    data class ToolCallBatch(val calls: List<ToolCallData>) : StreamEvent
    data class Batch(val events: List<StreamEvent>) : StreamEvent

    /**
     * One per request, emitted once the final counts are known.
     *
     * [inputTokens] is the TOTAL prompt size, uncached input + tokens served
     * from the cache + tokens written to the cache, so the cache hit rate is
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
        val cacheReported: Boolean = false,
        val reasoningTokens: Int? = null,
    ) : StreamEvent
    /** [code] is the provider's error status, even when the SSE HTTP response is 200. */
    data class Failure(val message: String, val code: Int? = null, val errorCode: String? = null,
        val retryable: Boolean? = null, val retryAfterSeconds: Long? = null) : StreamEvent
    /** A consented account/model change before any response output. */
    data class ProviderChanged(val config: ProviderConfig, val reason: String, val persistSelection: Boolean = true) : StreamEvent
    data class Retrying(val attempt: Int, val delayMs: Long, val reason: String) : StreamEvent

    /**
     * Emitted once per request when the stream terminates. [finishReason] is
     * the provider's raw termination code, "stop", "length", "tool_calls",
     * Anthropic's "max_tokens"/"end_turn"/"tool_use", Gemini's "STOP"/
     * "MAX_TOKENS", or null when the server never reported one.
     */
    data class Done(val finishReason: String? = null) : StreamEvent
}

class ApiException(val code: Int, message: String, retryAfterSecondsHeader: Long? = null) : IOException("HTTP $code: $message") {
    val retryAfterSeconds: Long? = retryAfterSecondsHeader ?: runCatching {
        providerRetryAfterSeconds(Json.parseToJsonElement(message))
    }.getOrNull()
    val errorCode: String? = runCatching {
        providerErrorCode((Json.parseToJsonElement(message) as? JsonObject)?.get("error") as? JsonObject)
    }.getOrNull()
}

/** Keep machine-readable quota/rate-limit categories separate from human error text. */
internal fun providerErrorCode(error: JsonObject?): String? = listOf("code", "type", "status")
    .mapNotNull { (error?.get(it) as? kotlinx.serialization.json.JsonPrimitive)?.content }
    .firstOrNull { it.isNotBlank() && it != "null" && it.toIntOrNull() == null }

internal fun providerErrorStatus(error: JsonObject?): Int? =
    listOf("code", "status").mapNotNull { (error?.get(it) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
        .firstOrNull { it in 400..599 }

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

    @Volatile var localProvider: LlmProvider? = null
    @Volatile var chatGptProvider: LlmProvider? = null

    fun create(config: ProviderConfig): LlmProvider = when {
        com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(config.id) ->
            checkNotNull(chatGptProvider) { "ChatGPT connection is not initialized." }
        com.androidharness.app.local.LocalModelCatalog.isLocal(config.id) || config.baseUrl.startsWith("local://") ->
            checkNotNull(localProvider) { "Local inference is not initialized." }
        config.id == HarnessProvider.ID -> HarnessProvider.create()
        else -> RotatingKeyProvider.wrap(config, create(config.type))
    }

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
    fun sseJson(request: Request, httpClient: OkHttpClient = client, onDone: () -> Unit = {}): Flow<JsonElement> = callbackFlow {
        val call = httpClient.newCall(request)
        val reader = launch(Dispatchers.IO) {
            try {
                val response = kotlinx.coroutines.suspendCancellableCoroutine<Response> { continuation ->
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                        }

                        override fun onResponse(call: Call, response: Response) {
                            if (continuation.isActive) continuation.resumeWith(Result.success(response))
                            else response.close()
                        }
                    })
                }
                response.use {
                    if (!it.isSuccessful) {
                        val errBody = try { it.body?.string()?.take(2000) } catch (_: Exception) { null }
                        throw ApiException(it.code, errBody ?: it.message, retryAfterHeaderSeconds(it.header("Retry-After")))
                    }
                    val source = it.body!!.source()
                    while (true) {
                        val line = readSseLine(source) ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") { onDone(); break }
                        if (payload.isEmpty()) continue
                        try {
                            send(json.parseToJsonElement(payload))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: kotlinx.serialization.SerializationException) {
                            // Skip malformed keep-alive lines, never drop valid stream chunks.
                        }
                    }
                }
                close()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose {
            call.cancel()
            reader.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Reads one SSE line from [source]. Lines terminate at '\n' with one
     * immediately-preceding '\r' stripped (the CRLF form). Interior CR
     * bytes anywhere else in the body are PRESERVED, unlike
     * BufferedReader.readLine() / okio's readUtf8Line(), which treat every
     * lone CR as a line break too. Some remote hosts emit unescaped CR
     * characters inside streamed JSON strings (tool-argument content among
     * them); with a standard reader those vanish silently and files
     * written through write_file come back with their carriage returns
     * stripped (CRLF corrupted into LF).
     */
    fun readSseLine(source: okio.BufferedSource): String? {
        if (source.exhausted()) return null
        val lf = source.indexOf('\n'.code.toByte())
        return if (lf >= 0) {
            val line = source.readUtf8(lf)
            source.skip(1) // the '\n'
            if (line.endsWith("\r")) line.dropLast(1) else line
        } else {
            val tail = source.readUtf8()
            if (tail.endsWith("\r")) tail.dropLast(1) else tail
        }
    }
}
