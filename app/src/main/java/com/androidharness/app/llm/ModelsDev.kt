package com.androidharness.app.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The models.dev community catalog (https://models.dev — the same feed
 * OpenCode consumes, refreshed daily by its maintainers). Unlike every
 * provider API — which reports only *whether* reasoning exists — this
 * catalog enumerates each model's actual thinking vocabulary: effort values
 * ("low"/"high"/"xhigh"/"max"…), budget-token ranges, or a plain on/off
 * toggle. We fetch it weekly, cache it on disk, and let it override the
 * shipped [com.androidharness.app.agent.ThinkingSpecs] family table; when the
 * cache is absent or the model is unknown, the shipped table wins, so the app
 * degrades to today's behavior offline.
 */
object ModelsDev {

    private const val API_URL = "https://models.dev/api.json"
    private const val CACHE_FILE = "models-dev.json"
    /** Refetch at most weekly; the catalog moves fast but models ship faster. */
    private const val STALE_MS = 7L * 24 * 60 * 60 * 1000

    /** Per-model thinking capability as the catalog reports it. */
    data class Entry(
        /** Tri-state like ModelEntry.reasoning; false definitively means "cannot think". */
        val reasoning: Boolean?,
        /** Exact effort vocabulary, e.g. ["low","high","max"]; null when not enumerated. */
        val effortValues: List<String>?,
        /** Takes an explicit thinking budget (Anthropic budget_tokens, Gemini thinkingBudget). */
        val budgetTokens: Boolean,
        /** Upper bound for the budget, when the catalog knows one. */
        val budgetMax: Int?,
        /** Thinking can be toggled on/off but has no strength dial. */
        val toggle: Boolean,
        /** Context window tokens ("limit.context"), for display in pickers. */
        val contextTokens: Long? = null,
    )

    /** One provider listed on models.dev: display name, endpoint, protocol hint. */
    data class ProviderInfo(
        val id: String,
        val name: String,
        val api: String?,
        val npm: String?,
        val modelCount: Int,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var entries: Map<String, Map<String, Entry>> = emptyMap()
    @Volatile private var providerInfos: List<ProviderInfo> = emptyList()
    @Volatile private var loaded = false

    /** models.dev provider key for an endpoint, or null when unmapped. */
    fun providerKeyFor(baseUrl: String?): String? {
        val host = baseUrl?.lowercase() ?: return null
        val known = when {
            "openrouter.ai" in host -> "openrouter"
            "api.openai.com" in host -> "openai"
            "api.anthropic.com" in host -> "anthropic"
            "generativelanguage.googleapis.com" in host -> "google"
            "api.deepseek.com" in host -> "deepseek"
            "api.groq.com" in host -> "groq"
            "api.mistral.ai" in host -> "mistral"
            "api.moonshot" in host -> "moonshotai"
            "bigmodel.cn" in host -> "zhipuai"
            "dashscope" in host -> "alibaba"
            "api.together.xyz" in host -> "togetherai"
            "api.x.ai" in host -> "xai"
            else -> null
        }
        if (known != null) return known
        // Fallback: match by the catalog's own provider API host, so gateways
        // we never hardcoded (there are ~180) still resolve their section.
        val bare = host.substringAfter("://").substringBefore('/').substringBefore(':')
        if (bare.isBlank()) return null
        return providerInfos.firstOrNull { info ->
            info.api?.lowercase()
                ?.substringAfter("://")?.substringBefore('/')?.substringBefore(':') == bare
        }?.id
    }

    /**
     * The app's protocol for a catalog provider's SDK hint. Only protocols the
     * app actually speaks are returned; the rest (bedrock/azure/vertex-style
     * auth) stay off the add-provider list.
     */
    fun protocolFor(npm: String?): ProviderType? = when (npm) {
        "@ai-sdk/anthropic" -> ProviderType.ANTHROPIC
        "@ai-sdk/google" -> ProviderType.GEMINI
        "@ai-sdk/openai", "@ai-sdk/openai-compatible", "@ai-sdk/deepseek",
        "@ai-sdk/groq", "@ai-sdk/mistral", "@ai-sdk/togetherai", "@ai-sdk/xai",
        "@ai-sdk/cerebras", "@ai-sdk/deepinfra", "@ai-sdk/perplexity",
        "@openrouter/ai-sdk-provider" -> ProviderType.OPENAI_COMPAT
        else -> null
    }

    /** All providers the catalog lists, alphabetical by display name. */
    fun providers(): List<ProviderInfo> = providerInfos

    /** Model entries for a catalog provider section (for offline browsing). */
    fun modelsFor(providerKey: String?): Map<String, Entry> =
        entries[providerKey].orEmpty()

    /** Idempotent load of the cached catalog into memory (safe on any thread). */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return
        val parsed = runCatching { parse(file.readText()) }.getOrNull() ?: return
        entries = parsed.entries
        providerInfos = parsed.providers
    }

    /**
     * Downloads the catalog when the cache is stale (or [force]), stores it on
     * disk, and hot-swaps the in-memory maps. Returns an error message on
     * failure — the old cache (or the shipped table) keeps serving.
     */
    suspend fun refresh(context: Context, force: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, CACHE_FILE)
            if (!force && file.exists() &&
                System.currentTimeMillis() - file.lastModified() < STALE_MS
            ) return@withContext null
            try {
                client.newCall(Request.Builder().url(API_URL).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext "HTTP ${resp.code}"
                    val body = resp.body?.string() ?: return@withContext "Empty response"
                    val parsed = parse(body)
                    file.writeText(body)
                    entries = parsed.entries
                    providerInfos = parsed.providers
                    null
                }
            } catch (e: Exception) {
                e.message ?: "models.dev refresh failed"
            }
        }

    /** Catalog entry for [modelId] under [providerKey], matching exact ids first. */
    fun entry(providerKey: String?, modelId: String?): Entry? {
        if (providerKey == null || modelId.isNullOrBlank()) return null
        val models = entries[providerKey] ?: return null
        models[modelId]?.let { return it }
        // Catalog ids and user-configured ids differ in vendor prefixing both
        // ways ("anthropic/claude-sonnet-4-5" vs "claude-sonnet-4-5") — fall
        // back to suffix matching, first hit wins.
        val suffix = modelId.substringAfterLast('/')
        models[suffix]?.let { return it }
        return models.entries.firstOrNull { (key, _) ->
            key.substringAfterLast('/') == suffix || key.endsWith("/$modelId")
        }?.value
    }

    /** Parse output: per-provider model maps plus the searchable provider directory. */
    internal data class Parsed(
        val entries: Map<String, Map<String, Entry>>,
        val providers: List<ProviderInfo>,
    )

    /** Pure parser (unit-testable without disk or network). */
    internal fun parse(body: String): Parsed {
        val root = json.parseToJsonElement(body).jsonObject
        val entriesMap = LinkedHashMap<String, Map<String, Entry>>()
        val providersList = ArrayList<ProviderInfo>()
        for ((providerId, providerEl) in root) {
            val providerObj = providerEl.jsonObject
            val modelsEl = providerObj["models"] ?: continue
            val models = LinkedHashMap<String, Entry>()
            for ((modelId, modelEl) in modelsEl.jsonObject) {
                val obj = modelEl.jsonObject
                val reasoning = obj["reasoning"]?.jsonPrimitive?.booleanOrNull
                var effort: List<String>? = null
                var budget = false
                var budgetMax: Int? = null
                var toggle = false
                obj["reasoning_options"]?.jsonArray?.forEach { optEl ->
                    val opt = optEl.jsonObject
                    when (opt["type"]?.jsonPrimitive?.contentOrNull) {
                        "effort" -> effort = opt["values"]?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        "budget_tokens" -> {
                            budget = true
                            budgetMax = opt["max"]?.jsonPrimitive?.intOrNull
                        }
                        "toggle" -> toggle = true
                    }
                }
                val ctx = obj["limit"]?.jsonObject?.get("context")?.jsonPrimitive?.longOrNull
                models[modelId] = Entry(reasoning, effort, budget, budgetMax, toggle, ctx)
            }
            entriesMap[providerId] = models
            providersList += ProviderInfo(
                id = providerId,
                name = providerObj["name"]?.jsonPrimitive?.contentOrNull ?: providerId,
                api = providerObj["api"]?.jsonPrimitive?.contentOrNull,
                npm = providerObj["npm"]?.jsonPrimitive?.contentOrNull,
                modelCount = models.size,
            )
        }
        return Parsed(entriesMap, providersList.sortedBy { it.name.lowercase() })
    }

    /** Test hook: swap the in-memory catalog without touching disk. */
    internal fun replaceForTesting(
        map: Map<String, Map<String, Entry>>,
        providers: List<ProviderInfo> = emptyList(),
    ) {
        entries = map
        providerInfos = providers
        loaded = true
    }
}
