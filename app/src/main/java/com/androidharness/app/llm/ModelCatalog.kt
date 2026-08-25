package com.androidharness.app.llm

import com.androidharness.app.llm.jsonArrayOrAbsent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * One entry of a provider's model catalog. [reasoning] is a tri-state:
 * true/false when the endpoint reports capability (e.g. OpenRouter's
 * `supported_parameters`), null when unknown — the UI then falls back to
 * [reasoningCapable] id heuristics.
 */
@Serializable
data class ModelEntry(
    val id: String,
    val reasoning: Boolean? = null,
    val contextTokens: Long? = null,
)

/** Family-based thinking-capability hint for endpoints that don't report it. */
fun reasoningCapable(modelId: String): Boolean {
    val m = modelId.lowercase()
    return Regex(
        "(^|/)(o[1345]([-.]|$)|gpt-5)" +                 // OpenAI reasoning families
            "|deepseek-(r1|reasoner)|deepseek-v[4-9]" +   // DeepSeek reasoners / v4+
            "|claude" +                                   // Claude 3.7+ all support thinking
            "|gemini-[23]\\.[0-9]" +                      // Gemini 2.x/3.x flash/pro
            "|qwen3|glm-[45]|minimax-m2|nemotron|hy3|kimi-latest|k2",
    ).containsMatchIn(m)
}

/** Fetches the model catalog from a provider — also doubles as a connection test. */
object ModelCatalog {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        data class Models(val models: List<ModelEntry>, val latencyMs: Long) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun listModels(config: ProviderConfig, apiKey: String): Result =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            try {
                val (url, requestBuilder) = when (config.type) {
                    // Responses is OpenAI-only; its model listing is identical.
                    ProviderType.OPENAI_COMPAT, ProviderType.OPENAI_RESPONSES ->
                        config.baseUrl.trimEnd('/') + "/models" to
                            Request.Builder().header("Authorization", "Bearer $apiKey")

                    ProviderType.ANTHROPIC -> config.baseUrl.trimEnd('/') + "/v1/models" to
                        Request.Builder()
                            .header("x-api-key", apiKey)
                            .header("anthropic-version", "2023-06-01")

                    ProviderType.GEMINI ->
                        config.baseUrl.trimEnd('/') + "/models" to
                            Request.Builder().header("x-goog-api-key", apiKey)
                }
                client.newCall(requestBuilder.url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@use Result.Failed("HTTP ${resp.code}: ${resp.message}")
                    }
                    val body = resp.body?.string() ?: return@use Result.Failed("Empty response")
                    Result.Models(parseCatalog(config.type, body), System.currentTimeMillis() - started)
                }
            } catch (e: Exception) {
                Result.Failed(e.message ?: "Connection failed")
            }
        }

    /** Pure parser, unit-testable without HTTP. */
    internal fun parseCatalog(type: ProviderType, body: String): List<ModelEntry> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        return when (type) {
            ProviderType.OPENAI_COMPAT, ProviderType.OPENAI_RESPONSES, ProviderType.ANTHROPIC ->
                root["data"]?.jsonArrayOrAbsent()?.mapNotNull { el ->
                    val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    // OpenRouter-style capability reporting; absent elsewhere.
                    val reasoning = obj["supported_parameters"]?.jsonArrayOrAbsent()?.let { params ->
                        params.any { p ->
                            p.jsonPrimitive.contentOrNull?.contains("reasoning") == true
                        }
                    } ?: obj["reasoning"]?.jsonPrimitive?.booleanOrNull
                    val ctx = (obj["context_length"] ?: obj["max_tokens"] ?: obj["context_window"])
                        ?.jsonPrimitive?.longOrNull
                    ModelEntry(id, reasoning, ctx)
                } ?: emptyList()

            ProviderType.GEMINI ->
                root["models"]?.jsonArrayOrAbsent()?.mapNotNull { el ->
                    val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull
                        ?.removePrefix("models/") ?: return@mapNotNull null
                    ModelEntry(name, reasoning = reasoningCapable(name))
                } ?: emptyList()
        }.sortedBy { it.id }
    }
}
