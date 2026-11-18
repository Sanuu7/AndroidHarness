package com.androidharness.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Checks one API key with the smallest real request the provider accepts:
 * a single "Say ok" message capped at a few output tokens, not streamed.
 * Listing models is not enough, since several gateways serve /models
 * without checking the key at all. Without a model it falls back to that
 * listing, which still catches a rejected key on most providers. Either way
 * the provider's own error text is shown, not just the status code.
 */
object ProviderKeyCheck {
    sealed interface Result {
        data class Ok(val latencyMs: Long, val model: String?) : Result
        data class Failed(val message: String, val status: Int? = null) : Result
    }

    /** Reasoning models and the Responses API reject anything much lower. */
    internal const val MAX_OUTPUT_TOKENS = 16
    private const val PROMPT = "Say ok"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /** [proxy] lets an unsaved proxy draft be tested; null uses the saved route. */
    suspend fun check(
        config: ProviderConfig,
        apiKey: String,
        proxy: ProviderProxy? = null,
        httpClient: OkHttpClient = client,
    ): Result = withContext(Dispatchers.IO) {
        try {
            val draft = proxy?.let { if (it.mode == ProviderProxyMode.INHERIT) ProviderNetwork.settings(null) else it }
            val routed = if (draft != null) ProviderNetwork.configure(httpClient, draft) else httpClient
            val http = if (draft != null) {
                if (ProviderHeaders.editable(config) && config.headers.any { it.enabled })
                    routed.newBuilder().followRedirects(false).followSslRedirects(false).build() else routed
            } else ProviderHeaders.client(config, routed)
            val request = if (config.model.isBlank()) ModelCatalog.buildRequest(config, apiKey) else buildRequest(config, apiKey)
            val started = System.currentTimeMillis()
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.Ok(System.currentTimeMillis() - started, config.model.ifBlank { null })
                else Result.Failed(redact(errorMessage(resp.code, resp.message, body), apiKey), resp.code)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Failed(redact(e.message ?: "Connection failed", apiKey))
        }
    }

    internal fun buildRequest(config: ProviderConfig, apiKey: String): Request {
        val base = config.baseUrl.trimEnd('/')
        val authed = apiKey.isNotBlank() && apiKey != OpenAiCompatProvider.LOCAL_KEY
        val builder = Request.Builder()
        val (url, body) = when (config.type) {
            ProviderType.OPENAI_COMPAT -> {
                if (authed) builder.header("Authorization", "Bearer $apiKey")
                "$base/chat/completions" to buildJsonObject {
                    put("model", config.model)
                    put(if (OpenAiCompatProvider.usesMaxCompletionTokens(config.baseUrl, config.model))
                        "max_completion_tokens" else "max_tokens", MAX_OUTPUT_TOKENS)
                    putJsonArray("messages") { add(userMessage()) }
                }
            }
            ProviderType.OPENAI_RESPONSES -> {
                if (authed) builder.header("Authorization", "Bearer $apiKey")
                "$base/responses" to buildJsonObject {
                    put("model", config.model)
                    put("input", PROMPT)
                    put("max_output_tokens", MAX_OUTPUT_TOKENS)
                    put("store", false)
                }
            }
            ProviderType.ANTHROPIC -> {
                if (authed) builder.header("x-api-key", apiKey)
                builder.header("anthropic-version", "2023-06-01")
                "$base/v1/messages" to buildJsonObject {
                    put("model", config.model)
                    put("max_tokens", MAX_OUTPUT_TOKENS)
                    putJsonArray("messages") { add(userMessage()) }
                }
            }
            ProviderType.GEMINI -> {
                if (authed) builder.header("x-goog-api-key", apiKey)
                "$base/models/${config.model}:generateContent" to buildJsonObject {
                    putJsonArray("contents") {
                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") { add(buildJsonObject { put("text", PROMPT) }) }
                        })
                    }
                    putJsonObject("generationConfig") { put("maxOutputTokens", MAX_OUTPUT_TOKENS) }
                }
            }
        }
        builder.url(url).post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (HarnessProvider.isOpenCode(config)) HarnessProvider.withSession(builder, null)
        return ProviderHeaders.apply(builder, config).build()
    }

    private fun userMessage(): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", PROMPT)
    }

    /** Pulls the provider's own reason out of the usual error shapes. */
    internal fun errorMessage(code: Int, status: String, body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = root?.get("error")
        val reason = when {
            error is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
            error != null -> runCatching { error.jsonPrimitive.contentOrNull }.getOrNull()
            else -> root?.get("message")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        }?.trim()?.takeIf { it.isNotEmpty() }
            ?: body.trim().takeIf { it.isNotEmpty() && !it.startsWith("<") }
        val head = "HTTP $code" + status.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return if (reason == null) head else "$head: ${reason.take(240)}"
    }

    /** Some providers echo part of a rejected key; never put it on screen. */
    internal fun redact(message: String, apiKey: String): String =
        if (apiKey.length < 8) message else message.replace(apiKey, "[key]")
}
