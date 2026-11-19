package com.androidharness.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate

/**
 * A search API that takes one key per request. Each call walks the saved keys
 * in order and moves on when one is refused, out of credit or rate limited,
 * the same way the Brave and Tavily backends do.
 */
internal abstract class KeyedSearchBackend(private val apiKeys: List<String>) : SearchBackend {
    internal abstract fun request(key: String, query: String, count: Int, options: SearchOptions): Request
    protected abstract fun parse(body: String): List<WebSearchResult>

    /** Some APIs answer HTTP 200 and put the real status in the body. */
    internal open fun bodyStatus(body: String): Int? = null

    /** Full page text that came with the results, keyed by URL. */
    protected open fun pages(body: String): Map<String, String> = emptyMap()

    /** Maps a provider's own way of saying "this key is out of credit" onto the status key failover understands. */
    internal open fun keyStatus(status: Int): Int = status

    override suspend fun fetch(
        client: OkHttpClient,
        query: String,
        count: Int,
        options: SearchOptions,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val body = withKeyFailover(apiKeys) { key ->
            val request = request(key, query, count, options)
            val text = try {
                ResearchHttp.read(client, request) { ResearchHttp.bodyText(it) }
            } catch (e: ResearchHttpFailure) {
                val mapped = keyStatus(e.status)
                throw if (mapped == e.status) e else ResearchHttpFailure(mapped, e.retryAfterMs, e.host)
            }
            bodyStatus(text)?.let { throw ResearchHttpFailure(it, 0, request.url.host) }
            text
        }
        pages(body).forEach { (url, text) ->
            if (ReadableWebPage.problem(text) == null) WebResultCache.putPage(url, text.take(400_000), label)
        }
        SearchOutcome(parse(body).filter { it.url.startsWith("http") }.take(count), via = label)
    }
}

private val searchJson = Json { ignoreUnknownKeys = true; isLenient = true }
private val JSON_TYPE = "application/json".toMediaType()

private fun JsonObject.toBody(): RequestBody = toString().toRequestBody(JSON_TYPE)

private fun parseObject(body: String): JsonObject? =
    runCatching { searchJson.parseToJsonElement(body) as? JsonObject }.getOrNull()

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
private fun JsonObject.text(vararg names: String): String? =
    names.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { v -> v.isNotEmpty() } }

/** Builds a result from the common title, url and snippet field names a provider uses. */
private fun JsonObject.result(title: String, url: String, vararg snippet: String, date: String? = null): WebSearchResult? {
    val link = text(url) ?: return null
    val heading = text(title) ?: return null
    return WebSearchResult(heading, link, text(*snippet)?.let(::shortSnippet).orEmpty(), date?.let { text(it) }?.take(10))
}

/** Some APIs return page extracts where a snippet goes; keep the result list readable. */
private fun shortSnippet(text: String): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    if (flat.length <= 420) return flat
    val cut = flat.lastIndexOf(' ', 400).takeIf { it > 200 } ?: 400
    return flat.take(cut) + "…"
}

private fun lowerOption(value: String?) = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

private fun googleRange(freshness: String?): String? = when (freshness) {
    "day" -> "qdr:d"; "week" -> "qdr:w"; "month" -> "qdr:m"; "year" -> "qdr:y"; else -> null
}

/** Serper (google.serper.dev): Google results, key in X-API-KEY. */
internal class SerperApiBackend(keys: List<String>) : KeyedSearchBackend(keys) {
    override val label = "Serper"

    override fun request(key: String, query: String, count: Int, options: SearchOptions): Request =
        Request.Builder()
            .url("https://google.serper.dev/search")
            .header("X-API-KEY", key)
            .post(buildJsonObject {
                put("q", query)
                put("num", count.coerceIn(1, 100))
                lowerOption(options.country)?.let { put("gl", it) }
                lowerOption(options.language)?.let { put("hl", it) }
                googleRange(options.freshness)?.let { put("tbs", it) }
            }.toBody())
            .build()

    override fun parse(body: String) = SerperSearchParser.parse(body)

    // Serper answers an empty balance with HTTP 400 "Not enough credits". The request body is
    // always well formed here, so a 400 means this key is spent and the next one should run.
    override fun keyStatus(status: Int) = if (status == 400) 402 else status
}

internal object SerperSearchParser {
    fun parse(body: String): List<WebSearchResult> =
        parseObject(body)?.get("organic").arr()?.mapNotNull { it.obj()?.result("title", "link", "snippet", date = "date") }.orEmpty()
}

/** SerpApi (serpapi.com): Google results, key as the api_key query parameter. */
internal class SerpApiBackend(keys: List<String>) : KeyedSearchBackend(keys) {
    override val label = "SerpApi"

    override fun request(key: String, query: String, count: Int, options: SearchOptions): Request {
        val url = "https://serpapi.com/search.json".toHttpUrl().newBuilder()
            .addQueryParameter("engine", "google")
            .addQueryParameter("q", query)
            .addQueryParameter("api_key", key)
        lowerOption(options.country)?.let { url.addQueryParameter("gl", it) }
        lowerOption(options.language)?.let { url.addQueryParameter("hl", it) }
        googleRange(options.freshness)?.let { url.addQueryParameter("tbs", it) }
        return Request.Builder().url(url.build()).header("Accept", "application/json").build()
    }

    override fun parse(body: String) = SerpApiSearchParser.parse(body)
}

internal object SerpApiSearchParser {
    fun parse(body: String): List<WebSearchResult> =
        parseObject(body)?.get("organic_results").arr()?.mapNotNull { it.obj()?.result("title", "link", "snippet", date = "date") }.orEmpty()
}

/** Linkup (api.linkup.so): search built for agents, bearer key. */
internal class LinkupApiBackend(keys: List<String>, private val today: () -> LocalDate = LocalDate::now) : KeyedSearchBackend(keys) {
    override val label = "Linkup"

    override fun request(key: String, query: String, count: Int, options: SearchOptions): Request =
        Request.Builder()
            .url("https://api.linkup.so/v1/search")
            .header("Authorization", "Bearer $key")
            .post(buildJsonObject {
                put("q", query)
                // "deep" runs several search rounds and costs ten times more; "standard" is the step up.
                put("depth", if (options.deep) "standard" else "fast")
                put("outputType", "searchResults")
                put("maxResults", count.coerceIn(1, 50))
                sinceDate(options.freshness, today())?.let { put("fromDate", it.toString()) }
            }.toBody())
            .build()

    override fun parse(body: String) = LinkupSearchParser.parse(body)
}

internal object LinkupSearchParser {
    fun parse(body: String): List<WebSearchResult> =
        parseObject(body)?.get("results").arr()?.mapNotNull { element ->
            val o = element.obj() ?: return@mapNotNull null
            if (o.text("type")?.equals("image", ignoreCase = true) == true) return@mapNotNull null
            o.result("name", "url", "content", date = "publishedAt")
        }.orEmpty()
}

/** LangSearch (api.langsearch.com): free web search, bearer key. */
internal class LangSearchApiBackend(keys: List<String>) : KeyedSearchBackend(keys) {
    override val label = "LangSearch"

    override fun request(key: String, query: String, count: Int, options: SearchOptions): Request =
        Request.Builder()
            .url("https://api.langsearch.com/v1/web-search")
            .header("Authorization", "Bearer $key")
            .post(buildJsonObject {
                put("query", query)
                put("count", count.coerceIn(1, 50))
                put("freshness", when (options.freshness) {
                    "day" -> "oneDay"; "week" -> "oneWeek"; "month" -> "oneMonth"; "year" -> "oneYear"; else -> "noLimit"
                })
            }.toBody())
            .build()

    override fun bodyStatus(body: String) = LangSearchParser.status(body)
    override fun parse(body: String) = LangSearchParser.parse(body)
}

internal object LangSearchParser {
    /** LangSearch can report a refused key inside a 200 response. */
    fun status(body: String): Int? {
        val code = (parseObject(body)?.get("code") as? JsonPrimitive)?.intOrNull ?: return null
        return code.takeIf { it in 400..599 }
    }

    fun parse(body: String): List<WebSearchResult> =
        parseObject(body)?.get("data").obj()?.get("webPages").obj()?.get("value").arr()
            ?.mapNotNull { it.obj()?.result("name", "url", "snippet", "summary", date = "datePublished") }.orEmpty()
}

/**
 * Jina Search (s.jina.ai), bearer key. When the caller will read pages, each
 * result comes back with its page text, so those reads need no extra requests.
 */
internal class JinaApiBackend(keys: List<String>) : KeyedSearchBackend(keys) {
    override val label = "Jina Search"

    override fun request(key: String, query: String, count: Int, options: SearchOptions): Request =
        Request.Builder()
            .url("https://s.jina.ai/")
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .apply { if (!options.readPages && !options.deep) header("X-Respond-With", "no-content") }
            .post(buildJsonObject {
                put("q", query)
                put("num", count.coerceIn(1, 20))
                lowerOption(options.country)?.let { put("gl", it) }
                lowerOption(options.language)?.let { put("hl", it) }
            }.toBody())
            .build()

    override fun bodyStatus(body: String) = JinaSearchParser.status(body)
    override fun parse(body: String) = JinaSearchParser.parse(body)
    override fun pages(body: String) = JinaSearchParser.pages(body)
}

internal object JinaSearchParser {
    private fun entries(body: String): List<JsonObject> =
        parseObject(body)?.get("data").arr()?.mapNotNull { it.obj() }.orEmpty()

    fun status(body: String): Int? {
        val code = (parseObject(body)?.get("code") as? JsonPrimitive)?.intOrNull ?: return null
        return code.takeIf { it in 400..599 }
    }

    fun parse(body: String): List<WebSearchResult> = entries(body).mapNotNull { it.result("title", "url", "description", "content", date = "date") }

    fun pages(body: String): Map<String, String> = entries(body).mapNotNull { o ->
        val url = o.text("url") ?: return@mapNotNull null
        val content = o.text("content")?.takeIf { it.length > 400 } ?: return@mapNotNull null
        url to content
    }.toMap()
}

/** Firecrawl (api.firecrawl.dev v2), bearer key. Page scraping is left off to save credits. */
internal class FirecrawlApiBackend(keys: List<String>) : KeyedSearchBackend(keys) {
    override val label = "Firecrawl"

    override fun request(key: String, query: String, count: Int, options: SearchOptions): Request =
        Request.Builder()
            .url("https://api.firecrawl.dev/v2/search")
            .header("Authorization", "Bearer $key")
            .post(buildJsonObject {
                put("query", query.take(500))
                put("limit", count.coerceIn(1, 100))
                put("sources", buildJsonArray { add(JsonPrimitive("web")) })
                googleRange(options.freshness)?.let { put("tbs", it) }
                options.country?.trim()?.uppercase()?.takeIf { it.length == 2 }?.let { put("country", it) }
            }.toBody())
            .build()

    override fun parse(body: String) = FirecrawlSearchParser.parse(body)
}

internal object FirecrawlSearchParser {
    fun parse(body: String): List<WebSearchResult> {
        val data = parseObject(body)?.get("data")
        // v2 nests web results under data.web; v1 returned a bare array.
        val list = data.obj()?.get("web").arr() ?: data.arr() ?: return emptyList()
        return list.mapNotNull { it.obj()?.result("title", "url", "description", "snippet") }
    }
}

/**
 * Marginalia (api2.marginalia-search.com): an independent index that favours
 * small, non-commercial sites. Works with the shared "public" key, which is
 * often rate limited; a free personal key avoids that.
 */
internal class MarginaliaApiBackend(keys: List<String>) : KeyedSearchBackend(keys.ifEmpty { listOf(SHARED_KEY) }) {
    override val label = "Marginalia"

    override fun request(key: String, query: String, count: Int, options: SearchOptions): Request {
        val url = "https://api2.marginalia-search.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("count", count.coerceIn(1, 100).toString())
            .build()
        return Request.Builder().url(url).header("API-Key", key).header("Accept", "application/json").build()
    }

    override fun parse(body: String) = MarginaliaSearchParser.parse(body)

    companion object { const val SHARED_KEY = "public" }
}

internal object MarginaliaSearchParser {
    fun parse(body: String): List<WebSearchResult> =
        parseObject(body)?.get("results").arr()?.mapNotNull { it.obj()?.result("title", "url", "description") }.orEmpty()
}

/** The first day a freshness window covers, for APIs that take a start date. */
internal fun sinceDate(freshness: String?, today: LocalDate): LocalDate? = when (freshness) {
    "day" -> today.minusDays(1)
    "week" -> today.minusWeeks(1)
    "month" -> today.minusMonths(1)
    "year" -> today.minusYears(1)
    else -> null
}
