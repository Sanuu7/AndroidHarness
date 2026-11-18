package com.androidharness.app.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The context size a self-hosted llama.cpp server was started with (`-c`).
 * No catalog knows it and it changes whenever the server restarts, so it is
 * read from the server's `/props` before a run. Without it the app assumes
 * the configured context and sends prompts the server cannot hold.
 */
object ServerContext {

    private const val TTL_MS = 60_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private class Probe(val at: Long, val tokens: Long?)
    private val cache = ConcurrentHashMap<String, Probe>()

    /** Tokens per request slot, or null when the server is not llama.cpp or did not answer. */
    suspend fun tokens(config: ProviderConfig, apiKey: String): Long? {
        if (com.androidharness.app.local.LocalModelCatalog.isLocal(config.id) ||
            config.type != ProviderType.OPENAI_COMPAT ||
            !OpenAiCompatProvider.isLocalHost(config.baseUrl)) return null
        val url = propsUrl(config.baseUrl)
        cache[url]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return it.tokens }
        val tokens = withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(url)
                if (apiKey.isNotBlank() && apiKey != OpenAiCompatProvider.LOCAL_KEY) builder.header("Authorization", "Bearer $apiKey")
                ProviderHeaders.client(config, client).newCall(ProviderHeaders.apply(builder, config).build()).execute().use { resp ->
                    if (resp.isSuccessful) parse(resp.body?.string().orEmpty()) else null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        cache[url] = Probe(System.currentTimeMillis(), tokens)
        return tokens
    }

    /**
     * llama.cpp's `exceed_context_size_error` as a sentence a person can act
     * on. The raw JSON only says the numbers; the fix is on the server side.
     */
    fun overflowMessage(body: String): String? {
        val error = runCatching { (json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject }.getOrNull()
            ?: return null
        if ((error["type"] as? JsonPrimitive)?.content != "exceed_context_size_error") return null
        val needed = (error["n_prompt_tokens"] as? JsonPrimitive)?.longOrNull ?: return null
        val available = (error["n_ctx"] as? JsonPrimitive)?.longOrNull ?: return null
        val suggested = (needed + 8_192 + 8_191) / 8_192 * 8_192
        return "The model server can hold ${"%,d".format(java.util.Locale.ROOT, available)} tokens, but this request needs ${"%,d".format(java.util.Locale.ROOT, needed)}. " +
            "Restart the server with a larger context (for llama.cpp, -c $suggested or more), then send the message again."
    }

    /** `/props` sits at the server root, beside the OpenAI-style `/v1`. */
    internal fun propsUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/').removeSuffix("/v1").trimEnd('/') + "/props"

    /**
     * The slot context from `default_generation_settings.n_ctx`. With
     * `--parallel` the server splits `-c` between slots, so the slot value is
     * what one request can use. Older builds only report a top-level `n_ctx`.
     */
    internal fun parse(body: String): Long? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val slot = (root["default_generation_settings"] as? JsonObject)?.get("n_ctx")
        return sequenceOf(slot, root["n_ctx"])
            .mapNotNull { (it as? JsonPrimitive)?.longOrNull }
            .firstOrNull { it > 0 }
    }
}
