package com.androidharness.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object HarnessProvider {
    const val ID = "harness"
    const val BASE_URL = "https://opencode.ai/zen/v1"
    const val DEFAULT_MODEL = "ling-3.0-flash-fin-free"
    const val KEYLESS = "harness-keyless"
    val config = ProviderConfig(ID, "Harness", ProviderType.OPENAI_COMPAT, BASE_URL, DEFAULT_MODEL)
    val fallbackModels = listOf(
        DEFAULT_MODEL, "big-pickle", "deepseek-v4-flash-free",
        "mimo-v2.5-free", "nemotron-3-ultra-free", "nemotron-3.5-lightning-free",
        "muse-spark-1.3-contributor-free", "muse-spark-1.2-contributor-free",
    ).map { ModelEntry(it) }

    /** Zen relay free slots retire without notice; drop stored picks on bump. */
    val retired = setOf("laguna-s-2.1-free", "hy3-free")

    fun isFree(model: String): Boolean = model == "big-pickle" ||
        (model.endsWith("-free") && model != "ox-alpha-free")

    fun sanitize(model: String?, custom: Set<String> = emptySet()): String =
        model?.takeIf { (isFree(it) || it in custom) && it !in retired } ?: DEFAULT_MODEL

    fun models(entries: List<ModelEntry>): List<ModelEntry> {
        val free = entries.filter { isFree(it.id) }
        return if (free.isEmpty()) fallbackModels else
            (free + ModelEntry("big-pickle")).distinctBy { it.id }.sortedBy { it.id }
    }

    /**
     * The Zen relay serves one catalog behind three wires. Where Hermes pins
     * a static per-model table, we probe instead: the first request for a
     * model tries chat/completions, then Anthropic /messages, then OpenAI
     * /responses. The winner persists in DataStore (mirrored into [pins])
     * so later requests route directly.
     */
    @Volatile var pins: Map<String, String> = emptyMap()

    fun wire(model: String): ProviderType = when (pins[model]) {
        ProviderType.ANTHROPIC.name -> ProviderType.ANTHROPIC
        ProviderType.OPENAI_RESPONSES.name -> ProviderType.OPENAI_RESPONSES
        else -> ProviderType.OPENAI_COMPAT
    }

    private fun looksAnthropicError(body: String?): Boolean =
        body?.contains("\"type\":\"error\"") == true && body.contains("\"error\"")

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(anonymous(chain.request())) }
        .build()

    /**
     * Anonymous probe for the wire a free model actually speaks. 2xx wins;
     * a failed chat/completions falls through to the Anthropic and Responses
     * endpoints. Null when nothing answered, leaving the default wire.
     */
    suspend fun probeWire(model: String): ProviderType? = withContext(Dispatchers.IO) {
        val chat = post("$BASE_URL/chat/completions", jsonBody(model))
        if (chat) return@withContext ProviderType.OPENAI_COMPAT
        if (post(BASE_URL.removeSuffix("/v1") + "/v1/messages", anthropicBody(model)))
            return@withContext ProviderType.ANTHROPIC
        if (post("$BASE_URL/responses", responsesBody(model)))
            ProviderType.OPENAI_RESPONSES
        else null
    }

    private fun post(url: String, body: String): Boolean = runCatching {
        probeClient.newCall(
            Request.Builder().url(url)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            val text = resp.body?.string()
            resp.isSuccessful && text != null && !looksAnthropicError(text)
        }
    }.getOrDefault(false)

    private fun jsonBody(model: String) =
        """{"model":"$model","messages":[{"role":"user","content":"reply with the single word ok"}],"max_tokens":16}"""

    private fun anthropicBody(model: String) =
        """{"model":"$model","max_tokens":16,"messages":[{"role":"user","content":"reply with the single word ok"}]}"""

    private fun responsesBody(model: String) =
        """{"model":"$model","input":"reply with the single word ok","max_output_tokens":16}"""

    fun anonymous(request: Request): Request = request.newBuilder()
        .removeHeader("Authorization")
        .removeHeader("x-api-key")
        .header("HTTP-Referer", "https://github.com/Sanuu7/AndroidHarness")
        .header("X-Title", "Harness")
        .header("User-Agent", "AndroidHarness")
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor { chain -> chain.proceed(anonymous(chain.request())) }
        .build()

    fun create(): LlmProvider = object : LlmProvider {
        override fun streamChat(
            config: ProviderConfig,
            apiKey: String,
            systemPrompt: String,
            messages: List<com.androidharness.app.core.ChatMessage>,
            tools: List<ToolSchema>,
            options: RequestOptions,
        ): Flow<StreamEvent> {
            val wire = wire(config.model)
            val baseUrl = if (wire == ProviderType.ANTHROPIC) BASE_URL.removeSuffix("/v1") else BASE_URL
            val routed = config.copy(type = wire, baseUrl = baseUrl)
            val provider = when (wire) {
                ProviderType.ANTHROPIC -> AnthropicProvider(client, ProviderFactory.json)
                ProviderType.OPENAI_RESPONSES -> OpenAiResponsesProvider(client, ProviderFactory.json)
                else -> OpenAiCompatProvider(client, ProviderFactory.json)
            }
            return provider.streamChat(routed, KEYLESS, systemPrompt, messages, tools, options)
        }
    }
}
