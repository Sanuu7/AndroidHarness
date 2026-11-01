package com.androidharness.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    /** Publication age as the backend reported it, for recency judgements. */
    val date: String? = null,
)

/** Results plus the source that produced them, for the [via …] output tag. */
internal data class SearchOutcome(val results: List<WebSearchResult>, val via: String?)

/** Per-call search options. Freshness is day, week, month, year or null. */
internal data class SearchOptions(
    val engine: String = "auto",
    val freshness: String? = null,
    val language: String? = null,
    val country: String? = null,
    val deep: Boolean = false,
)

/**
 * The user's search API choice: provider id ("brave" | "tavily") plus the key
 * from encrypted storage. Null means the keyless HTML engines are used.
 * [fallbackKeys] are the other enabled keys of the pool, tried in order when
 * [apiKey] is rejected or out of quota.
 */
// Manual toString: the generated one embeds the raw key, and a stray
// Log.d(config) would leak a live Brave/Tavily key to logcat.
data class SearchApiConfig(val provider: String, val apiKey: String, val fallbackKeys: List<String> = emptyList()) {
    /** Every usable key, the preferred one first. */
    val keys: List<String> get() = (listOf(apiKey) + fallbackKeys).filter { it.isNotBlank() }.distinct()

    /** Stable across rotation order, so caches survive the start key moving on each call. */
    internal fun credentialIdentity(): String = ResearchHttp.credentialIdentity(keys.sorted().joinToString("\n"))

    override fun toString(): String =
        "SearchApiConfig(provider=$provider, apiKey=${if (apiKey.isEmpty()) "<unset>" else "<redacted>"}, keys=${keys.size})"
}

/** A key rejection or limit that another saved key of the same provider may not share. */
internal fun ResearchHttpFailure.keySpecific(): Boolean = status in setOf(401, 402, 403, 429, 432, 433)

/**
 * Runs [block] with each key in turn until one is not rejected for a key-specific
 * reason. A key still in cooldown fails at once without a request, so spent keys
 * are skipped quickly. The last failure is rethrown when every key was refused.
 */
internal suspend fun <T> withKeyFailover(keys: List<String>, block: suspend (String) -> T): T {
    require(keys.isNotEmpty()) { "No API key" }
    var last: ResearchHttpFailure? = null
    for (key in keys) {
        try { return block(key) }
        catch (e: ResearchHttpFailure) { if (!e.keySpecific()) throw e; last = e }
    }
    val failure = last!!
    if (keys.size == 1) throw failure
    throw ResearchHttpFailure(failure.status, failure.retryAfterMs, failure.host, keys.size)
}

/**
 * One web-search source. The keyless backend scrapes HTML search endpoints;
 * API backends (Brave, Tavily) use proper JSON APIs and are preferred when
 * the user configures a key. The engine selector only means something to the
 * keyless backend; API backends ignore it.
 */
internal interface SearchBackend {
    val label: String
    suspend fun fetch(
        client: OkHttpClient,
        query: String,
        count: Int,
        options: SearchOptions = SearchOptions(),
    ): SearchOutcome
}

/** Picks the backend for a user configuration; null = keyless scraping. */
internal fun searchBackendFor(config: SearchApiConfig?): SearchBackend? =
    when (config?.provider?.trim()?.lowercase()) {
        "brave" -> config.keys.takeIf { it.isNotEmpty() }?.let { BraveApiBackend(it) }
        "tavily" -> config.keys.takeIf { it.isNotEmpty() }?.let { TavilyApiBackend(it) }
        else -> null
    }

/**
 * Engine fallback chain over keyless HTML endpoints (moved verbatim from the
 * original web_search implementation). Throws ToolFailure when every engine
 * comes up empty so the caller can report the whole chain.
 */
internal class KeylessSearchBackend : SearchBackend {
    override val label = "keyless engines"

    private val allEngines = listOf("duckduckgo", "bing", "brave", "google")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun fetch(
        client: OkHttpClient,
        query: String,
        count: Int,
        options: SearchOptions,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val searchClient = client.newBuilder()
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
        val engine = options.engine
        val chain = when (engine.trim().lowercase()) {
            "duckduckgo", "bing", "brave", "google" -> listOf(engine.trim().lowercase())
            else -> allEngines
        }
        // Engines run concurrently. The old chain waited out every engine in
        // turn, so one blocked engine could hold a research query for its full
        // read timeout before the next one even started.
        val jobs = chain.map { name ->
            async(Dispatchers.IO) {
                name to try { Result.success(fetchEngine(searchClient, name, query, options)) }
                    catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
                    catch (e: Exception) { Result.failure(e) }
            }
        }
        val finished = withTimeoutOrNull(28_000) { jobs.awaitAll() }
            ?: jobs.filter { it.isCompleted }.mapNotNull { runCatching { it.getCompleted() }.getOrNull() }
        jobs.filterNot { it.isCompleted }.forEach { it.cancel() }
        val collected = ArrayList<WebSearchResult>()
        val seen = HashSet<String>()
        val used = ArrayList<String>()
        val errors = LinkedHashMap<String, String>()
        for ((name, result) in finished) {
            val results = result.getOrElse { errors[name] = it.message ?: "failed"; continue }
            if (results.isEmpty()) {
                errors[name] = "returned no results"
                continue
            }
            if (collected.size >= count) break
            used += name
            for (item in results) {
                if (seen.add(canonicalResultUrl(item.url))) collected += item
                if (collected.size >= count) break
            }
        }
        if (collected.isNotEmpty()) {
            return@withContext SearchOutcome(collected, via = used.joinToString("+"))
        }
        throw ToolFailure(
            "All keyless engines failed. Tried: ${chain.joinToString(", ")}. " +
                "Details: ${errors.entries.joinToString("; ") { "${it.key}: ${it.value}" }}. " +
                "You can also use browser_read on a search URL or web_fetch a known URL.",
        )
    }

    private suspend fun fetchEngine(
        client: OkHttpClient,
        engine: String,
        query: String,
        options: SearchOptions,
    ): List<WebSearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val lang = options.language?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val country = options.country?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        val url = when (engine) {
            "duckduckgo" -> buildString {
                append("https://html.duckduckgo.com/html/?q=").append(encoded)
                if (lang != null && country != null) append("&kl=").append(country.lowercase()).append('-').append(lang)
                ddgDate(options.freshness)?.let { append("&df=").append(it) }
            }
            "bing" -> buildString {
                append("https://www.bing.com/search?q=").append(encoded)
                lang?.let { append("&setlang=").append(it) }
                country?.let { append("&cc=").append(it) }
            }
            "brave" -> buildString {
                append("https://search.brave.com/search?q=").append(encoded)
                braveDate(options.freshness)?.let { append("&tf=").append(it) }
            }
            else -> buildString {
                append("https://www.google.com/search?q=").append(encoded).append("&num=10")
                lang?.let { append("&hl=").append(it) }
                country?.let { append("&gl=").append(it) }
                googleDate(options.freshness)?.let { append("&tbs=").append(it) }
            }
        }
        val html = ResearchHttp.read(client,
            Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", languageHeader(options.language))
                .build()
        ) { resp -> ResearchHttp.bodyText(resp) }
        return parse(engine, html)
    }

    fun parse(engine: String, html: String): List<WebSearchResult> = when (engine) {
        "duckduckgo" -> parseDuckDuckGo(html)
        "bing" -> parseBing(html)
        "brave" -> parseBrave(html)
        else -> parseGoogle(html)
    }

    private fun parseDuckDuckGo(html: String): List<WebSearchResult> {
        val linkRegex = Regex(
            "(?s)<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>"
        )
        val snippetRegex = Regex("(?s)result__snippet[^>]*>(.*?)</a>")
        val links = linkRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).map { cleanHtml(it.groupValues[1]) }.toList()
        return links.mapIndexed { idx, match ->
            var href = match.groupValues[1]
            val uddg = Regex("[?&]uddg=([^&]+)").find(href)?.groupValues?.get(1)
            if (uddg != null) {
                href = runCatching { java.net.URLDecoder.decode(uddg, "UTF-8") }.getOrDefault(href)
            }
            WebSearchResult(cleanHtml(match.groupValues[2]), href, snippets.getOrElse(idx) { "" })
        }.filter { it.url.startsWith("http") }
    }

    private fun parseBing(html: String): List<WebSearchResult> {
        // <li class="b_algo"><h2><a href="...">title</a></h2><p>snippet</p>
        val itemRegex = Regex("(?s)<li class=\"b_algo\".*?</li>")
        return itemRegex.findAll(html).mapNotNull { item ->
            val block = item.value
            val linkMatch = Regex("<h2><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").find(block)
                ?: return@mapNotNull null
            val snippet = Regex("(?s)<p[^>]*>(.*?)</p>").find(block)?.groupValues?.get(1)
            WebSearchResult(
                cleanHtml(linkMatch.groupValues[2]),
                linkMatch.groupValues[1],
                cleanHtml(snippet ?: ""),
            )
        }.toList()
    }

    private fun parseBrave(html: String): List<WebSearchResult> {
        val resultsBlock = Regex("(?s)<div[^>]*id=\"results\"[^>]*>(.*?)(?:<footer|<div[^>]*id=\"footer\"|$)")
            .find(html)?.groupValues?.get(1) ?: return emptyList()

        val snippetBlocks = Regex("(?s)<div[^>]*class=\"[^\"]*snippet[^\"]*\"[^>]*>.*?</div>\\s*</div>")
            .findAll(resultsBlock).map { it.value }.toList()

        if (snippetBlocks.isNotEmpty()) {
            return snippetBlocks.mapNotNull { block ->
                val linkMatch = Regex("(?s)<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.{2,200}?)</a>").find(block)
                    ?: return@mapNotNull null
                val url = linkMatch.groupValues[1]
                if (url.contains("brave.com") || url.contains("hackerone.com")) return@mapNotNull null
                val title = cleanHtml(linkMatch.groupValues[2])
                if (title.isBlank() || title.equals("Report a security issue", ignoreCase = true)) return@mapNotNull null
                val snippet = Regex("(?s)class=\"[^\"]*snippet-description[^\"]*\"[^>]*>(.*?)</p>").find(block)?.groupValues?.get(1)
                    ?: Regex("(?s)class=\"[^\"]*snippet[^\"]*\"[^>]*>(.*?)</").find(block)?.groupValues?.get(1)
                WebSearchResult(title, url, cleanHtml(snippet.orEmpty()))
            }.take(10)
        }

        val linkRegex = Regex("(?s)<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.{2,200}?)</a>")
        val snippetRegex = Regex("(?s)class=\"[^\"]*snippet[^\"]*\"[^>]*>(.*?)</")
        val snippets = snippetRegex.findAll(resultsBlock).map { cleanHtml(it.groupValues[1]) }.toList()
        return linkRegex.findAll(resultsBlock).mapIndexed { idx, match ->
            WebSearchResult(cleanHtml(match.groupValues[2]), match.groupValues[1], snippets.getOrElse(idx) { "" })
        }.filterNot {
            it.url.contains("brave.com") || it.url.contains("hackerone.com") ||
                it.title.isBlank() || it.title.equals("Report a security issue", ignoreCase = true)
        }.take(10).toList()
    }

    private fun parseGoogle(html: String): List<WebSearchResult> {
        // <a href="/url?q=..."><h3>title</h3></a>, often JS-walled, best effort
        val itemRegex = Regex("(?s)<a href=\"(/url\\?q=[^\"]+)\"[^>]*>.*?<h3[^>]*>(.*?)</h3>")
        return itemRegex.findAll(html).mapNotNull { match ->
            val q = Regex("(?s)&amp;|&").replace(
                java.net.URLDecoder.decode(
                    match.groupValues[1].removePrefix("/url?q=").substringBefore("&"),
                    "UTF-8",
                ),
                "",
            )
            WebSearchResult(cleanHtml(match.groupValues[2]), q, "")
        }.filter { it.url.startsWith("http") }.take(10).toList()
    }

    private fun cleanHtml(s: String): String = s
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** Brave Search API (api.search.brave.com), key passed as X-Subscription-Token. */
internal class BraveApiBackend(private val apiKeys: List<String>) : SearchBackend {
    constructor(apiKey: String) : this(listOf(apiKey))
    override val label = "Brave Search API"

    override suspend fun fetch(
        client: OkHttpClient,
        query: String,
        count: Int,
        options: SearchOptions,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val url = buildString {
            append("https://api.search.brave.com/res/v1/web/search")
            append("?q=").append(URLEncoder.encode(query, "UTF-8")).append("&count=").append(count)
            braveDate(options.freshness)?.let { append("&freshness=").append(it) }
            options.language?.trim()?.takeIf { it.isNotBlank() }?.let { append("&search_lang=").append(it) }
            options.country?.trim()?.takeIf { it.isNotBlank() }?.let { append("&country=").append(it.uppercase()) }
        }
        val body = withKeyFailover(apiKeys) { apiKey ->
            ResearchHttp.read(client,
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", apiKey)
                    .build()
            ) { resp -> ResearchHttp.bodyText(resp) }
        }
        SearchOutcome(BraveSearchParser.parse(body), via = label)
    }
}

internal object BraveSearchParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<WebSearchResult> = runCatching {
        val results = json.parseToJsonElement(body).jsonObject
            .get("web")?.jsonObject?.get("results")?.jsonArray
            ?: return emptyList()
        results.mapNotNull { el ->
            val o = el.jsonObject
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val date = o["age"]?.jsonPrimitive?.contentOrNull
                ?: o["page_age"]?.jsonPrimitive?.contentOrNull?.take(10)
            WebSearchResult(title, url, o["description"]?.jsonPrimitive?.contentOrNull ?: "", date)
        }.filter { it.url.startsWith("http") }
    }.getOrDefault(emptyList())
}

/** Tavily Search API (api.tavily.com), key in the JSON body. */
internal class TavilyApiBackend(private val apiKeys: List<String>) : SearchBackend {
    constructor(apiKey: String) : this(listOf(apiKey))
    override val label = "Tavily Search API"

    override suspend fun fetch(
        client: OkHttpClient,
        query: String,
        count: Int,
        options: SearchOptions,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("query", query)
            put("max_results", count)
            put("search_depth", if (options.deep) "advanced" else "basic")
            put("include_raw_content", options.deep)
            tavilyTimeRange(options.freshness)?.let { put("time_range", it) }
        }
        val body = withKeyFailover(apiKeys) { apiKey ->
            ResearchHttp.read(client,
                Request.Builder()
                    .url("https://api.tavily.com/search")
                    .header("Authorization", "Bearer $apiKey")
                    .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
            ) { resp -> ResearchHttp.bodyText(resp) }
        }
        TavilySearchParser.cachePages(body)
        SearchOutcome(TavilySearchParser.parse(body), via = label)
    }
}

internal object TavilySearchParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Retrieval is reused on web_fetch, and is counted only once delivered to the model. */
    fun cachePages(body: String) {
        runCatching {
            json.parseToJsonElement(body).jsonObject["results"]?.jsonArray?.take(20)?.forEach { entry ->
                val obj = entry.jsonObject
                val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val content = obj["raw_content"]?.jsonPrimitive?.contentOrNull?.take(400000) ?: return@forEach
                if (ReadableWebPage.problem(content) == null) WebResultCache.putPage(url, content, "Tavily Search API")
            }
        }
    }

    fun parse(body: String): List<WebSearchResult> = runCatching {
        val results = json.parseToJsonElement(body).jsonObject
            .get("results")?.jsonArray
            ?: return emptyList()
        results.mapNotNull { el ->
            val o = el.jsonObject
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val date = o["published_date"]?.jsonPrimitive?.contentOrNull?.take(10)
            WebSearchResult(title, url, o["content"]?.jsonPrimitive?.contentOrNull ?: "", date)
        }.filter { it.url.startsWith("http") }
    }.getOrDefault(emptyList())
}

/** Shared browser identity for keyless search and page reads. */
internal const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/125.0.0.0 Mobile Safari/537.36"

/** Freshness values understood by the tool, mapped per backend. */
private fun ddgDate(freshness: String?): String? = when (freshness) {
    "day" -> "d"; "week" -> "w"; "month" -> "m"; "year" -> "y"; else -> null
}

private fun braveDate(freshness: String?): String? = when (freshness) {
    "day" -> "pd"; "week" -> "pw"; "month" -> "pm"; "year" -> "py"; else -> null
}

private fun googleDate(freshness: String?): String? = when (freshness) {
    "day" -> "qdr:d"; "week" -> "qdr:w"; "month" -> "qdr:m"; "year" -> "qdr:y"; else -> null
}

private fun tavilyTimeRange(freshness: String?): String? =
    freshness?.takeIf { it in setOf("day", "week", "month", "year") }

/** Accept-Language for keyless scraping when the caller asks for a language. */
internal fun languageHeader(language: String?): String {
    val lang = language?.trim()?.takeIf { it.isNotBlank() } ?: "en"
    return "$lang,$lang;q=0.9,en;q=0.6"
}

/** URL identity for budget and dedupe: no fragment, no tracking params, no trailing slash. */
internal fun canonicalResultUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val parsed = runCatching { trimmed.toHttpUrlOrNull() }.getOrNull()
        ?: return trimmed.trimEnd('/').lowercase()
    val builder = parsed.newBuilder().fragment(null)
    TRACKING_PARAMS.forEach { builder.removeAllQueryParameters(it) }
    return builder.build().toString().trimEnd('/')
}

private val TRACKING_PARAMS = setOf(
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
    "fbclid", "gclid", "mc_cid", "mc_eid", "ref", "source",
)

/**
 * Small process-wide cache for search and page reads. Deep research repeats
 * queries and pages across overlapping angles; the TTLs are short enough that
 * news still refreshes within a session.
 */
internal object WebResultCache {
    private data class Timed<T>(val value: T, val at: Long)

    private const val SEARCH_TTL_MS = 10 * 60_000L
    private const val PAGE_TTL_MS = 15 * 60_000L
    private const val MAX_SEARCHES = 24
    private const val MAX_PAGES = 24
    private const val MAX_PAGE_CHARS = 400_000

    private val lock = Any()
    private val searches = object : LinkedHashMap<String, Timed<SearchOutcome>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Timed<SearchOutcome>>) =
            size > MAX_SEARCHES
    }
    private data class Page(val text: String, val via: String?)
    private val pages = object : LinkedHashMap<String, Timed<Page>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Timed<Page>>) =
            size > MAX_PAGES
    }
    private val pageImages = object : LinkedHashMap<String, Timed<List<PageImage>>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Timed<List<PageImage>>>) =
            size > MAX_PAGES
    }

    fun getSearch(key: String): SearchOutcome? = synchronized(lock) {
        val hit = searches[key] ?: return null
        if (System.currentTimeMillis() - hit.at > SEARCH_TTL_MS) {
            searches.remove(key)
            null
        } else hit.value
    }

    fun putSearch(key: String, outcome: SearchOutcome) = synchronized(lock) {
        if (outcome.results.isNotEmpty()) searches[key] = Timed(outcome, System.currentTimeMillis())
    }

    fun getPage(url: String): String? = synchronized(lock) {
        val key = canonicalResultUrl(url)
        val hit = pages[key] ?: return null
        if (System.currentTimeMillis() - hit.at > PAGE_TTL_MS) {
            pages.remove(key)
            null
        } else hit.value.text
    }

    fun putPage(url: String, text: String, via: String? = null) = synchronized(lock) {
        pages[canonicalResultUrl(url)] = Timed(Page(text.take(MAX_PAGE_CHARS), via), System.currentTimeMillis())
    }

    fun getPageVia(url: String): String? = synchronized(lock) { pages[canonicalResultUrl(url)]?.value?.via }

    fun getPageImages(url: String): List<PageImage>? = synchronized(lock) {
        val key = canonicalResultUrl(url)
        val hit = pageImages[key] ?: return null
        if (System.currentTimeMillis() - hit.at > PAGE_TTL_MS) {
            pageImages.remove(key)
            null
        } else hit.value
    }

    fun putPageImages(url: String, images: List<PageImage>) = synchronized(lock) {
        pageImages[canonicalResultUrl(url)] = Timed(images.take(8), System.currentTimeMillis())
    }

    fun clear() = synchronized(lock) {
        searches.clear()
        pages.clear()
        pageImages.clear()
    }
}
