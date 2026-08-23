package com.androidharness.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Web search with an engine parameter and automatic fallback. When [engine]
 * is "auto" (or an engine yields nothing), the next engine in the chain is
 * tried until results are found. All engines are keyless HTML endpoints.
 */
class WebSearchTool(
    private val client: OkHttpClient,
) : Tool {
    override val name = "web_search"
    override val description =
        "Search the web and return top results (title, url, snippet). No API key required. " +
        "Choose an engine with the 'engine' parameter (duckduckgo, bing, brave, google) or " +
        "use 'auto' to let the app fall back to whichever engine responds."
    override val parametersSchema = Schema.obj(
        mapOf(
            "query" to Schema.string("The search query."),
            "count" to Schema.integer("Maximum number of results (default 8)."),
            "engine" to Schema.string("duckduckgo | bing | brave | google | auto (default)."),
        ),
        required = listOf("query"),
    )
    override val isReadOnly = true

    private data class SearchResult(val title: String, val url: String, val snippet: String)

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val query = args["query"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: query")
            val count = (args["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 8).coerceIn(1, 15)
            val requested = args["engine"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                ?: "auto"

            val chain = when (requested) {
                "duckduckgo" -> listOf("duckduckgo")
                "bing" -> listOf("bing")
                "brave" -> listOf("brave")
                "google" -> listOf("google")
                else -> listOf("duckduckgo", "bing", "brave", "google")
            }

            val searchClient = client.newBuilder()
                .readTimeout(25, TimeUnit.SECONDS)
                .build()

            var lastError: String? = null
            var usedEngine: String? = null
            for (engine in chain) {
                try {
                    val results = fetch(searchClient, engine, query)
                    if (results.isNotEmpty()) {
                        usedEngine = engine
                        val text = results.take(count).mapIndexed { idx, r ->
                            "${idx + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}"
                        }.joinToString("\n")
                        return@withContext ToolResult(
                            true,
                            if (requested == "auto") text else text,
                        )
                    }
                    lastError = "$engine returned no results"
                } catch (e: Exception) {
                    lastError = "$engine failed: ${e.message}"
                }
            }
            ToolResult(
                false,
                "Web search found nothing. Tried: ${chain.joinToString(", ")}. " +
                    "Last error: ${lastError ?: "unknown"}. " +
                    "You can also use web_fetch with a known URL.",
            )
        }

    private fun fetch(client: OkHttpClient, engine: String, query: String): List<SearchResult> {
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
        return when (engine) {
            "duckduckgo" -> parseDuckDuckGo(html)
            "bing" -> parseBing(html)
            "brave" -> parseBrave(html)
            else -> parseGoogle(html)
        }
    }

    private fun parseDuckDuckGo(html: String): List<SearchResult> {
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
            SearchResult(cleanHtml(match.groupValues[2]), href, snippets.getOrElse(idx) { "" })
        }.filter { it.url.startsWith("http") }
    }

    private fun parseBing(html: String): List<SearchResult> {
        // <li class="b_algo"><h2><a href="...">title</a></h2><p>snippet</p>
        val itemRegex = Regex("(?s)<li class=\"b_algo\".*?</li>")
        return itemRegex.findAll(html).mapNotNull { item ->
            val block = item.value
            val linkMatch = Regex("<h2><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").find(block)
                ?: return@mapNotNull null
            val snippet = Regex("(?s)<p[^>]*>(.*?)</p>").find(block)?.groupValues?.get(1)
            SearchResult(
                cleanHtml(linkMatch.groupValues[2]),
                linkMatch.groupValues[1],
                cleanHtml(snippet ?: ""),
            )
        }.toList()
    }

    private fun parseBrave(html: String): List<SearchResult> {
        // <div id="results"> <a href="...">title</a> <p class="snippet-description">...
        val linkRegex = Regex("(?s)<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.{5,200}?)</a>")
        val snippetRegex = Regex("(?s)class=\"[^\"]*snippet[^\"]*\"[^>]*>(.*?)</")
        val snippets = snippetRegex.findAll(html).map { cleanHtml(it.groupValues[1]) }.toList()
        return linkRegex.findAll(html).mapIndexed { idx, match ->
            SearchResult(cleanHtml(match.groupValues[2]), match.groupValues[1], snippets.getOrElse(idx) { "" })
        }.filterNot { it.url.contains("brave.com") || it.title.isBlank() }.take(10).toList()
    }

    private fun parseGoogle(html: String): List<SearchResult> {
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
            SearchResult(cleanHtml(match.groupValues[2]), q, "")
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

/** Generic HTTP client for testing APIs. */
class HttpRequestTool(
    private val client: OkHttpClient,
) : Tool {
    override val name = "http_request"
    override val description =
        "Send an HTTP request (GET/POST/PUT/PATCH/DELETE) with optional headers and body. " +
        "Returns status, headers and the truncated response body."
    override val parametersSchema = Schema.obj(
        mapOf(
            "method" to Schema.string("HTTP method. Defaults to GET."),
            "url" to Schema.string("The request URL."),
            "headers" to kotlinx.serialization.json.buildJsonObject {
                put("type", "object")
                put("description", "Request headers as key/value pairs.")
                putJsonObject("additionalProperties") { put("type", "string") }
            },
            "body" to Schema.string("Request body (for POST/PUT/PATCH)."),
            "content_type" to Schema.string("Body content type. Defaults to application/json."),
        ),
        required = listOf("url"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val url = args["url"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: url")
            val method = (args["method"]?.jsonPrimitive?.content ?: "GET").uppercase()
            val body = args["body"]?.jsonPrimitive?.content
            val contentType = args["content_type"]?.jsonPrimitive?.content ?: "application/json"

            val builder = Request.Builder().url(url)
            args["headers"]?.jsonObject?.forEach { (key, value) ->
                builder.header(key, value.jsonPrimitive.content)
            }
            val requestBody = body?.toRequestBody(contentType.toMediaTypeOrNull())
            when (method) {
                "GET" -> builder.get()
                "DELETE" -> if (body == null) builder.delete() else builder.delete(requestBody)
                "HEAD" -> builder.head()
                else -> builder.method(method, requestBody ?: "".toRequestBody(contentType.toMediaTypeOrNull()))
            }

            val reqClient = client.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            try {
                reqClient.newCall(builder.build()).execute().use { resp ->
                    val sb = StringBuilder()
                    sb.append("HTTP ").append(resp.code).append(' ').append(resp.message ?: "").append('\n')
                    val headers = (0 until minOf(resp.headers.size, 12))
                        .map { resp.headers.name(it) to resp.headers.value(it) }
                    headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append('\n') }
                    sb.append('\n')
                    val respBody = resp.body?.string() ?: ""
                    sb.append(respBody.take(20_000))
                    if (respBody.length > 20_000) sb.append("\n[truncated]")
                    ToolResult(true, sb.toString())
                }
            } catch (e: Exception) {
                ToolResult(false, "Request failed: ${e.message}")
            }
        }
}
