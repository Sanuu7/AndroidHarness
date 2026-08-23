package com.androidharness.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Fetches the model list from a provider — also doubles as a connection test. */
object ModelCatalog {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        data class Models(val models: List<String>, val latencyMs: Long) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun listModels(config: ProviderConfig, apiKey: String): Result =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            try {
                val (url, requestBuilder) = when (config.type) {
                    ProviderType.OPENAI_COMPAT -> config.baseUrl.trimEnd('/') + "/models" to
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
                    val models = when (config.type) {
                        ProviderType.OPENAI_COMPAT -> json.parseToJsonElement(body).jsonObject["data"]
                            ?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                            ?: emptyList()

                        ProviderType.ANTHROPIC -> json.parseToJsonElement(body).jsonObject["data"]
                            ?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                            ?: emptyList()

                        ProviderType.GEMINI -> json.parseToJsonElement(body).jsonObject["models"]
                            ?.jsonArray?.mapNotNull {
                                it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                                    ?.removePrefix("models/")
                            } ?: emptyList()
                    }
                    Result.Models(models.sorted(), System.currentTimeMillis() - started)
                }
            } catch (e: Exception) {
                Result.Failed(e.message ?: "Connection failed")
            }
        }
}
