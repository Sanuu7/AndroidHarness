package com.androidharness.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class WebSearchResult(val title: String, val url: String, val snippet: String)

/**
 * The user's search API choice: provider id ("brave" | "tavily") plus the key
 * from encrypted storage. Null means the keyless HTML engines are used.
 */
data class SearchApiConfig(val provider: String, val apiKey: String)

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
        engine: String = "auto",
    ): List<WebSearchResult>
}

/** Picks the backend for a user configuration; null = keyless scraping. */
internal fun searchBackendFor(config: SearchApiConfig?): SearchBackend? =
    when (config?.provider?.trim()?.lowercase()) {
        "brave" -> config.apiKey.takeIf { it.isNotBlank() }?.let { BraveApiBackend(it) }
        "tavily" -> config.apiKey.takeIf { it.isNotBlank() }?.let { TavilyApiBackend(it) }
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

    override suspend fun fetch(
        client: OkHttpClient,
        query: String,
        count: Int,
        engine: String,
    ): List<WebSearchResult> = withContext(Dispatchers.IO) {
        val searchClient = client.newBuilder()
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
        val chain = when (engine?.trim()?.lowercase()) {
            "duckduckgo", "bing", "brave", "google" -> listOf(engine.trim().lowercase())
            else -> allEngines
        }
        var lastError: String? = null
        for (e in chain) {
            try {
                val results = fetchEngine(searchClient, e, query)
                if (results.isNotEmpty()) return@withContext results
                lastError = "$e returned no results"
            } catch (ex: Exception) {
                lastError = "$e failed: ${ex.message}"
            }
        }
        throw ToolFailure(
            "All keyless engines failed. Tried: ${chain.joinToString(", ")}. " +
                "Last error: ${lastError ?: "unknown"}.",
        )
    }

    private fun fetchEngine(client: OkHttpClient, engine: String, query: String): List<WebSearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = when (engine) {
            "duckduckgo" -> "https://html.duckduckgo.com/html/?q=$encoded"
            "bing" -> "https://www.bing.com/search?q=$encoded"
            "brave" -> "https://search.brave.com/search?q=$encoded"
            else -> "https://www.google.com/search?q=$encoded&num=10"
        }
        val html = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AndroidHarness/1.0")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw ToolFailure("HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
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
        // <div id="results"> <a href="...">title</a> <p class="snippet-description">...
        val linkRegex = Regex("(?s)<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.{5,200}?)</a>")
        val snippetRegex = Regex("(?s)class=\"[^\"]*snippet[^\"]*\"[^>]*>(.*?)</")
        val snippets = snippetRegex.findAll(html).map { cleanHtml(it.groupValues[1]) }.toList()
        return linkRegex.findAll(html).mapIndexed { idx, match ->
            WebSearchResult(cleanHtml(match.groupValues[2]), match.groupValues[1], snippets.getOrElse(idx) { "" })
        }.filterNot { it.url.contains("brave.com") || it.title.isBlank() }.take(10).toList()
    }

    private fun parseGoogle(html: String): List<WebSearchResult> {
        // <a href="/url?q=..."><h3>title</h3></a> — often JS-walled, best effort
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
internal class BraveApiBackend(private val apiKey: String) : SearchBackend {
    override val label = "Brave Search API"

    override suspend fun fetch(
        client: OkHttpClient,
        query: String,
        count: Int,
        engine: String,
    ): List<WebSearchResult> = withContext(Dispatchers.IO) {
        val url = "https://api.search.brave.com/res/v1/web/search" +
            "?q=${URLEncoder.encode(query, "UTF-8")}&count=$count"
        val body = client.newCall(
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw ToolFailure("HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
        BraveSearchParser.parse(body)
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
            WebSearchResult(title, url, o["description"]?.jsonPrimitive?.contentOrNull ?: "")
        }.filter { it.url.startsWith("http") }
    }.getOrDefault(emptyList())
}

/** Tavily Search API (api.tavily.com), key in the JSON body. */
internal class TavilyApiBackend(private val apiKey: String) : SearchBackend {
    override val label = "Tavily Search API"

    override suspend fun fetch(
        client: OkHttpClient,
        query: String,
        count: Int,
        engine: String,
    ): List<WebSearchResult> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("api_key", apiKey)
            put("query", query)
            put("max_results", count)
            put("search_depth", "basic")
        }
        val body = client.newCall(
            Request.Builder()
                .url("https://api.tavily.com/search")
                .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw ToolFailure("HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
        TavilySearchParser.parse(body)
    }
}

internal object TavilySearchParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<WebSearchResult> = runCatching {
        val results = json.parseToJsonElement(body).jsonObject
            .get("results")?.jsonArray
            ?: return emptyList()
        results.mapNotNull { el ->
            val o = el.jsonObject
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            WebSearchResult(title, url, o["content"]?.jsonPrimitive?.contentOrNull ?: "")
        }.filter { it.url.startsWith("http") }
    }.getOrDefault(emptyList())
}
