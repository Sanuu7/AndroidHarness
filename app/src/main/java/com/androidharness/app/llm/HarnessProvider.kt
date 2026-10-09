package com.androidharness.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

object HarnessProvider {
    const val ID = "harness"
    const val BASE_URL = "https://opencode.ai/zen/v1"
    const val KILO_BASE_URL = "https://api.kilo.ai/api/gateway"
    const val KILO_NOTE = "Kilo · ~200 req/hour per IP"
    const val CATALOG_SOURCE = "kilo-free-v1"
    const val DEFAULT_MODEL = "kilo-auto/free"
    const val KEYLESS = "harness-keyless"
    const val SESSION_HEADER = "x-opencode-session"
    const val USER_AGENT = "AndroidHarness"
    val config = ProviderConfig(ID, "Harness", ProviderType.OPENAI_COMPAT, BASE_URL, DEFAULT_MODEL)

    /** Routing for the last successful free catalog, restored from disk on startup. */
    data class Pool(val baseUrl: String, val note: String)

    private val pollinations = ModelEntry("openai-fast", note = "Pollinations · ~4 req/min per IP")
    val fallbackModels = listOf(ModelEntry(DEFAULT_MODEL, note = KILO_NOTE), pollinations)
    @Volatile private var availableModels = fallbackModels
    @Volatile var customModelIds: Set<String> = emptySet()

    val pooledModels: List<ModelEntry> get() = availableModels
    val pool: Map<String, Pool> get() = availableModels.associate { entry ->
        entry.id to if (entry.id == pollinations.id) Pool("https://text.pollinations.ai/openai", pollinations.note!!)
        else Pool(KILO_BASE_URL, KILO_NOTE)
    }

    fun restoreModels(entries: List<ModelEntry>) { availableModels = entries.toList() }

    internal fun cachedModels(raw: String?, source: String?): List<ModelEntry> =
        if (source == CATALOG_SOURCE && raw != null) {
            runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<List<ModelEntry>>(raw) }
                .getOrNull()?.takeIf { it.isNotEmpty() } ?: fallbackModels
        } else fallbackModels

    fun isPooled(model: String): Boolean = pool.containsKey(model)

    fun sanitize(model: String?, custom: Set<String> = emptySet()): String =
        model?.takeIf { it in pool || it in custom }
            ?: pooledModels.firstOrNull { it.id == DEFAULT_MODEL }?.id
            ?: pooledModels.firstOrNull { it.id != pollinations.id }?.id
            ?: pollinations.id

    /** Kilo declares free access explicitly; a :free suffix alone is insufficient. */
    internal fun parseKiloCatalog(body: String): List<ModelEntry> {
        val root = Json.parseToJsonElement(body).jsonObject
        val data = requireNotNull(root["data"]?.jsonArrayOrAbsent()) { "Invalid Kilo model catalog" }
        val allowed = data.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            if (entry["isFree"]?.jsonPrimitive?.booleanOrNull != true) return@mapNotNull null
            val supportsTools = entry["supported_parameters"]?.jsonArrayOrAbsent()
                ?.any { it.jsonPrimitive.contentOrNull == "tools" } == true
            if (!supportsTools) return@mapNotNull null
            entry["id"]?.jsonPrimitive?.contentOrNull
        }.toSet()
        val entries = ModelCatalog.parseCatalog(ProviderType.OPENAI_COMPAT, body)
            .filter { it.id in allowed }.map { it.copy(note = KILO_NOTE) }
        require(entries.isNotEmpty()) { "Kilo has no free models with tool support available" }
        return models(entries)
    }

    fun models(entries: List<ModelEntry>): List<ModelEntry> =
        (entries.filterNot { it.id == pollinations.id }.map { it.copy(note = KILO_NOTE) } + pollinations)
            .distinctBy { it.id }

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

    /** Zen's sentinel key means anonymous; anything else rides out as a Bearer. */
    private fun realKey(apiKey: String?): String? =
        apiKey?.takeIf { it.isNotBlank() && it != KEYLESS }

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("HTTP-Referer", "https://github.com/Sanuu7/AndroidHarness")
                .header("X-Title", "Harness")
                .build())
        }
        .build()

    /**
     * Probe for the wire a model actually speaks. 2xx wins; a failed
     * chat/completions falls through to the Anthropic and Responses
     * endpoints. Null when nothing answered, leaving the default wire.
     * When a Zen key is available it probes keyed: anonymous probes now 403
     * on every wire, which used to pin the wrong protocol and produce 500s.
     */
    suspend fun probeWire(model: String, apiKey: String? = null): ProviderType? = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        val key = realKey(apiKey)
        val chat = post("$BASE_URL/chat/completions", jsonBody(model), sessionId, key)
        if (chat) return@withContext ProviderType.OPENAI_COMPAT
        if (post(BASE_URL.removeSuffix("/v1") + "/v1/messages", anthropicBody(model), sessionId, key))
            return@withContext ProviderType.ANTHROPIC
        if (post("$BASE_URL/responses", responsesBody(model), sessionId, key))
            ProviderType.OPENAI_RESPONSES
        else null
    }

    private fun post(url: String, body: String, sessionId: String, key: String?): Boolean = runCatching {
        val builder = Request.Builder().url(url)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
        withSession(builder, sessionId)
        if (key != null) builder.header("Authorization", "Bearer $key")
        probeClient.newCall(builder.build()).execute().use { resp ->
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

    fun isOpenCode(config: ProviderConfig): Boolean =
        config.id == ID || isOpenCode(config.baseUrl) || "opencode" in config.name.lowercase()

    fun isOpenCode(baseUrl: String): Boolean =
        "opencode" in baseUrl.lowercase()

    /**
     * The identity header set every Zen client is expected to send: a stable
     * session id, a fresh per-request id, and a self-declared client name.
     * Requests missing them come back as FreeTierError / 500 from the relay.
     */
    fun withSession(builder: Request.Builder, sessionId: String?): Request.Builder =
        builder
            .header(SESSION_HEADER, sessionId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
            .header("x-opencode-request", UUID.randomUUID().toString())
            .header("x-opencode-client", "harness")
            .header("x-opencode-project", "default")
            .header("User-Agent", USER_AGENT)

    fun anonymous(request: Request): Request {
        val builder = request.newBuilder()
            .removeHeader("Authorization")
            .removeHeader("x-api-key")
            .header("HTTP-Referer", "https://github.com/Sanuu7/AndroidHarness")
            .header("X-Title", "Harness")
        withSession(builder, request.header(SESSION_HEADER))
        return builder.build()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor { chain -> chain.proceed(anonymous(chain.request())) }
        .build()

    /** Keyed variant: same timeouts, but the Authorization header survives. */
    private val keyedClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Anonymous community upstreams: our sentinel bearer must never ride out. */
    private val poolClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().removeHeader("Authorization").build())
        }
        .build()

    fun create(pooledClient: OkHttpClient = poolClient): LlmProvider = object : LlmProvider {
        override fun streamChat(
            config: ProviderConfig,
            apiKey: String,
            systemPrompt: String,
            messages: List<com.androidharness.app.core.ChatMessage>,
            tools: List<ToolSchema>,
            options: RequestOptions,
        ): Flow<StreamEvent> {
            // Pooled community upstreams route straight to their own endpoint
            // on the chat/completions wire, no zen probing or session header.
            pool[config.model]?.let { upstream ->
                val routed = config.copy(type = ProviderType.OPENAI_COMPAT, baseUrl = upstream.baseUrl)
                return OpenAiCompatProvider(pooledClient, ProviderFactory.json)
                    .streamChat(routed, OpenAiCompatProvider.LOCAL_KEY, systemPrompt, messages, tools, options)
            }
            if (config.model !in customModelIds) return flowOf(StreamEvent.Failure(
                "${config.model} is no longer available in Harness. Refresh the model list and choose an available model.",
            ))
            // Zen's free tier rejects anonymous calls, so a saved key rides
            // out on the keyed client; the sentinel stays on the anonymous one.
            val key = realKey(apiKey)
            val wire = wire(config.model)
            val baseUrl = if (wire == ProviderType.ANTHROPIC) BASE_URL.removeSuffix("/v1") else BASE_URL
            val routed = config.copy(type = wire, baseUrl = baseUrl)
            val routedOptions = if (options.cacheKey.isNullOrBlank()) {
                options.copy(cacheKey = UUID.randomUUID().toString())
            } else options
            val http = if (key != null) keyedClient else client
            val provider = when (wire) {
                ProviderType.ANTHROPIC -> AnthropicProvider(http, ProviderFactory.json)
                ProviderType.OPENAI_RESPONSES -> OpenAiResponsesProvider(http, ProviderFactory.json)
                else -> OpenAiCompatProvider(http, ProviderFactory.json)
            }
            return provider.streamChat(routed, key ?: KEYLESS, systemPrompt, messages, tools, routedOptions)
        }
    }
}
