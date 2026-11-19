package com.androidharness.app.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Web search across every provider the user has turned on and made ready (a
 * saved key, a shared key, or the keyless public engines). The agent can name
 * one with the provider parameter; "auto" tries them in [SearchSetup] order and
 * falls through to the next one when a provider fails or finds nothing.
 */
class WebSearchTool(
    private val client: OkHttpClient,
    /** User's search API config, read fresh per call (may be null = keyless). */
    private val searchApi: () -> SearchApiConfig? = { null },
    /** Turn-scoped source allowance; null means the model decides. */
    private val sourceBudget: SearchSourceBudget? = null,
    /** Chat's selected research depth; null for Code and Auto. */
    private val depth: com.androidharness.app.data.SearchDepth? = null,
    private val refreshApi: suspend () -> SearchApiConfig? = { searchApi() },
    /** Reads the top results in parallel so one call returns passages, not just snippets. */
    private val pageReader: SearchPageReader? = null,
    /** Always-images chats: list pictures from the pages read, so the answer has one without another round. */
    private val pageImages: Boolean = false,
    /** Every provider that is on and ready, read fresh per call. */
    private val setup: () -> SearchSetup = { SearchSetup.single(searchApi()) },
    private val refreshSetup: suspend () -> SearchSetup = { SearchSetup.single(refreshApi()) },
    /** Result URLs already returned this turn, for the repeat note when no budget tracks them. */
    private val seenThisTurn: MutableSet<String>? = null,
) : Tool {
    override val name = "web_search"
    override val description: String get() = buildString {
        append(
            "Search the web and return top results (title, url, date when reported, snippet). " +
                "Set freshness to day, week, month, or year for news and latest-state questions; " +
                "check the reported date before treating a result as current.",
        )
        append(providerGuide(setup()))
        if (pageReader != null) {
            append(" It also reads the top result pages at the same time and returns their passages that best match the query " +
                "(set read to choose how many pages, 0 for snippets only). Those pages count as read; answer from the passages " +
                "when they cover the question, and use web_fetch only for a page that was not read or when you need more of one.")
        }
        sourceBudget?.let {
            append(" This turn allows at most ${it.maxSources} distinct sources; the tool stops returning new sources when the budget is used.")
        }
    }
    override val parametersSchema = Schema.obj(
        mapOf(
            "query" to Schema.string("The search query."),
            "count" to Schema.integer("Maximum number of results (default 8; research depth levels request a full batch of 20)."),
            "provider" to Schema.string("Which search provider to use: auto (default) or one of the ids listed in the description. " +
                "auto tries them in order and moves on when one fails or finds nothing."),
            "engine" to Schema.string("duckduckgo | bing | brave | google | auto (default). Only for the keyless provider."),
            "freshness" to Schema.string("Optional recency filter: day, week, month, year. Omit or use any for no filter."),
            "language" to Schema.string("Optional result language code, for example en or de."),
            "country" to Schema.string("Optional country code, for example US or DE."),
            "read" to Schema.integer("How many top result pages to read in parallel and return passages from (0-6). " +
                "Defaults to ${READ_DEFAULT} in Chat; 0 returns snippets only."),
        ),
        required = listOf("query"),
    )
    override val isReadOnly = true

    private val keyless = KeylessSearchBackend()

    internal suspend fun configurationNote(): String {
        val current = refreshSetup()
        val ready = current.apis.filter { usable(it) }.mapNotNull { searchBackendFor(it)?.label }
        val missing = current.apis.filterNot { usable(it) }.map { SearchProviders.name(it.provider) ?: it.provider }
        return when {
            ready.isNotEmpty() -> "Search providers ready to use (a saved API key is available for each): ${ready.joinToString(", ")}" +
                (if (current.keyless) ", plus the keyless public engines" else "") + ". " +
                "Use web_search normally: the harness supplies the keys automatically. Pick a provider when one fits the query better, " +
                "otherwise leave provider on auto. Do not ask the user to paste keys. " +
                (if (current.config("tavily")?.keys?.isNotEmpty() == true) "web_fetch also uses Tavily Extract and cached full-page content automatically. " else "") +
                "Do not navigate to search-engine websites to bypass these tools. Search snippets are not full page reads. Report API failures and unavailable pages honestly."
            missing.isNotEmpty() -> "${missing.joinToString(", ")} is turned on for search, but has no saved API key. web_search reports this explicitly; the user can fix it in Web search settings."
            current.keyless -> "No search API key is saved. web_search uses public keyless engines. Use web_fetch for pages and browser_read for JavaScript pages."
            else -> "Every search provider is turned off in Web search settings, so web_search cannot run. Use web_fetch with known URLs."
        }
    }

    private fun usable(config: SearchApiConfig): Boolean =
        config.keys.isNotEmpty() || SearchProviders.info(config.provider)?.sharedKey != null

    private fun providerGuide(current: SearchSetup): String = buildString {
        val ids = current.providerIds
        if (ids.isEmpty()) {
            append(" Every search provider is turned off in Settings, so this tool cannot run.")
            return@buildString
        }
        if (ids.size == 1 && current.apis.size == 1) {
            val name = SearchProviders.name(ids[0]) ?: ids[0]
            append(" Every search uses $name with the saved key. API authentication, quota and rate-limit failures " +
                "are reported explicitly; do not switch to scraping or repeat unchanged requests.")
            return@buildString
        }
        append(" Providers the user turned on (id: what it is good for):")
        current.apis.forEach { config ->
            val info = SearchProviders.info(config.provider) ?: return@forEach
            append(" ${info.id}: ${info.strength};")
        }
        if (current.keyless) append(" ${SearchProviders.KEYLESS}: ${SearchProviders.KEYLESS_STRENGTH}; choose its engine with 'engine'.")
        append(" Leave provider on auto for ordinary searches; name one when the query suits it, or to get a second, independent set of results.")
    }

    /** A run-scoped copy that spends from this turn's source and read budgets. */
    fun forDepth(depth: com.androidharness.app.data.SearchDepth, images: Boolean = false): WebSearchTool =
        WebSearchTool(
            client, searchApi,
            depth.maxSources?.let { SearchSourceBudget(it, researchReadLimit(depth), researchReadMinimum(depth)) },
            depth,
            refreshApi,
            pageReader,
            images,
            setup,
            refreshSetup,
            seenThisTurn = if (depth.maxSources == null) java.util.Collections.synchronizedSet(HashSet()) else null,
        )

    internal fun budget(): SearchSourceBudget? = sourceBudget

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val started = System.nanoTime()
        val shown = ArrayList<WebSearchResult>()
        val result = search(args, shown).let { found ->
            val note = repeatNote(shown)
            if (note.isEmpty()) found else found.copy(output = found.output + note)
        }
        val searchMs = (System.nanoTime() - started) / 1_000_000
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val wanted = (args["read"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: if (depth != null) READ_DEFAULT else 0).coerceIn(0, READ_MAX)
        val reader = pageReader
        if (!result.ok || shown.isEmpty() || reader == null || wanted == 0 || depth == com.androidharness.app.data.SearchDepth.OFF) {
            researchTiming("web_search: ${searchMs}ms, ${shown.size} results, no page reads")
            return result
        }
        val outcome = reader.read(query, shown, wanted, sourceBudget)
        researchTiming("web_search: search ${searchMs}ms, total ${(System.nanoTime() - started) / 1_000_000}ms")
        if (outcome.attempted == 0) return result
        val passages = buildString {
            append("\n\n")
            if (outcome.pages.isEmpty()) {
                append("[Read ${outcome.loaded} of ${outcome.attempted} top pages, but none had passages matching the query. " +
                    "Use web_fetch on the strongest result if the snippets are not enough.]")
            } else {
                append(SearchPassages.render(outcome.pages))
                if (outcome.unread.isNotEmpty() && outcome.loaded < wanted) {
                    append("\n[Not read in time or blocked: ${outcome.unread.joinToString(", ")}. Use web_fetch if one of them matters.]")
                }
            }
            if (pageImages) {
                val images = outcome.pages.flatMap { WebResultCache.getPageImages(it.result.url).orEmpty() }
                if (images.isNotEmpty()) append("\n").append(PageImageText.render(images, 4))
            }
            sourceBudget?.let { budget ->
                append("\n[pages read this turn: ${budget.pagesRead}")
                budget.maxReads?.let { append('/').append(it) }
                append(']')
            }
        }
        return result.copy(output = result.output + passages, research = sourceBudget?.receipt(backend = result.research?.backend) ?: result.research)
    }

    private suspend fun search(args: JsonObject, shown: MutableList<WebSearchResult>): ToolResult {
        val rawQuery = args["query"]?.jsonPrimitive?.content
            ?: throw ToolFailure("Missing required argument: query")
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            return ToolResult(true, "No search results for empty query.")
        }
        val rawCount = args["count"]?.jsonPrimitive?.content?.toIntOrNull()
        if (rawCount != null && rawCount <= 0) {
            throw ToolFailure("count must be greater than 0.")
        }
        val budget = sourceBudget
        if (budget != null && budget.remaining == 0) {
            return ToolResult(true, exhaustedText())
        }
        val count = searchBatchCount(depth, rawCount, budget?.remaining ?: Int.MAX_VALUE)
        val requested = args["engine"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: "auto"
        val validEngines = setOf("auto", "duckduckgo", "bing", "brave", "google")
        if (requested !in validEngines) {
            throw ToolFailure("Unknown engine '$requested'. Supported engines: auto, duckduckgo, bing, brave, google.")
        }
        val freshness = args["freshness"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?.takeIf { it.isNotBlank() && it != "any" }
        if (freshness != null && freshness !in setOf("day", "week", "month", "year")) {
            throw ToolFailure("Unknown freshness '$freshness'. Use day, week, month, year, or omit it.")
        }
        val language = args["language"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val country = args["country"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val deep = depth in setOf(
            com.androidharness.app.data.SearchDepth.DEEP,
            com.androidharness.app.data.SearchDepth.DEEP_RESEARCH,
            com.androidharness.app.data.SearchDepth.MAX_RESEARCH,
        )
        val readCount = (args["read"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: if (depth != null && pageReader != null) READ_DEFAULT else 0).coerceIn(0, READ_MAX)
            .let { if (pageReader == null || depth == com.androidharness.app.data.SearchDepth.OFF) 0 else it }
        val options = SearchOptions(requested, freshness, language, country, deep, readPages = readCount > 0)
        val searchClient = client.newBuilder()
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

        val current = refreshSetup()
        val provider = args["provider"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "auto"
        val available = current.providerIds
        if (provider != "auto" && provider !in available) {
            val known = provider == SearchProviders.KEYLESS || SearchProviders.info(provider) != null
            throw ToolFailure(
                (if (known) "The $provider search provider is turned off or has no saved key." else "Unknown search provider '$provider'.") +
                    (if (available.isEmpty()) " Every provider is turned off in Web search settings."
                    else " Available: auto, ${available.joinToString(", ")}."),
            )
        }
        // A specific keyless engine only makes sense on the keyless provider.
        val wantsKeyless = provider == SearchProviders.KEYLESS || (provider == "auto" && requested != "auto" && current.keyless)
        val apis = when {
            wantsKeyless -> emptyList()
            provider == "auto" -> current.apis
            else -> listOfNotNull(current.config(provider)) + current.apis.filter { it.provider != provider }
        }
        val useKeyless = current.keyless && (provider == "auto" || wantsKeyless || apis.isNotEmpty())
        val notes = StringBuilder()
        val unusable = apis.filterNot { usable(it) }
        if (unusable.isNotEmpty() && apis.size == unusable.size && !useKeyless) {
            return ToolResult(false, "${unusable.first().provider} search API is selected but it has no enabled saved key. " +
                "Add or turn on a key in Web search settings.")
        }
        if (apis.isEmpty() && !useKeyless) {
            return ToolResult(false, "Every web search provider is turned off in Web search settings. Turn one on, or use web_fetch with a known URL.")
        }
        val chain = apis.filter { usable(it) }.mapNotNull { config -> searchBackendFor(config)?.let { config to it } }
        val cacheKey = listOf(
            provider, chain.joinToString(",") { it.second.label }, useKeyless, query, count, options.engine,
            options.freshness, options.language, options.country, options.deep, options.readPages,
            chain.joinToString(",") { it.first.credentialIdentity() },
        ).joinToString("|")
        WebResultCache.getSearch(cacheKey)?.let { cached ->
            val suffix = (cached.via?.let { "\n[via $it]" } ?: "") + "\n[cached search; no new API request]"
            return render(cached.results, count, "", suffix, shown)
        }
        val failures = ArrayList<String>()
        val empty = ArrayList<String>()
        for ((_, backend) in chain) {
            try {
                val outcome = backend.fetch(searchClient, query, count, options)
                if (outcome.results.isEmpty()) {
                    empty += backend.label
                    continue
                }
                WebResultCache.putSearch(cacheKey, outcome)
                val suffix = buildString {
                    if (requested != "auto") {
                        // The engine selector is keyless-only; say so instead of
                        // silently returning different results than asked for.
                        append("\n[note: the engine parameter is ignored; ${backend.label} is active]")
                    }
                    append("\n[via ").append(outcome.via ?: backend.label).append(']')
                }
                return render(outcome.results, count, fallThroughNote(failures, empty, backend.label), suffix, shown)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "${backend.label} failed: ${SecretRedactor.redact(e.message.orEmpty())}"
            }
        }
        if (!useKeyless) {
            if (failures.isEmpty()) {
                val names = empty.joinToString(" and ")
                return render(emptyList(), count, "$names returned no results. Try a different query.\n", "\n[via ${empty.lastOrNull().orEmpty()}]")
            }
            val list = (failures + empty.map { "$it returned no results" }).joinToString("; ")
            return ToolResult(false, "$list. " +
                (if (chain.size == 1) "The selected API was not replaced with anonymous scraping. "
                else "Keyless public engines are turned off, so they were not tried. ") +
                "Check the API key, available credits, or the reported cooldown.")
        }
        notes.append(fallThroughNote(failures, empty, "the keyless engines"))

        val outcome = try {
            keyless.fetch(searchClient, query, count, options)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ToolFailure) {
            return ToolResult(
                false,
                notes.toString() + e.message +
                    " You can also use web_fetch with a known URL.",
            )
        } catch (e: Exception) {
            return ToolResult(false, notes.toString() + "Search failed: ${e.message}")
        }
        WebResultCache.putSearch(cacheKey, outcome)
        val suffix = outcome.via?.let { "\n[via $it]" } ?: ""
        return render(outcome.results, count, notes.toString(), suffix, shown)
    }

    /**
     * Says how many of these results earlier searches this turn already
     * returned. Information only: the agent still decides whether to go on.
     */
    internal fun repeatNote(shown: List<WebSearchResult>): String {
        val seen = seenThisTurn ?: return ""
        if (shown.isEmpty()) return ""
        val keys = shown.map { canonicalResultUrl(it.url) }.filter { it.isNotEmpty() }.distinct()
        if (keys.isEmpty()) return ""
        val repeated = synchronized(seen) { keys.count { !seen.add(it) } }
        return when {
            repeated == keys.size -> "\n[All ${keys.size} results were already returned by earlier searches this turn; this query found nothing new.]"
            repeated * 2 >= keys.size -> "\n[$repeated of ${keys.size} results were already returned by earlier searches this turn.]"
            else -> ""
        }
    }

    /** Tells the agent which providers were skipped on the way to the one that answered. */
    private fun fallThroughNote(failures: List<String>, empty: List<String>, used: String): String {
        if (failures.isEmpty() && empty.isEmpty()) return ""
        val parts = failures + empty.map { "$it returned no results" }
        return "[${parts.joinToString("; ")}; used $used instead]\n"
    }

    private fun render(
        results: List<WebSearchResult>,
        count: Int,
        prefix: String,
        suffix: String,
        shown: MutableList<WebSearchResult> = ArrayList(),
    ): ToolResult {
        val varied = diversifyResults(results)
        val budget = sourceBudget
        if (budget == null) {
            shown += varied.take(count)
            return ToolResult(true, prefix + WebSearchText.render(varied, count) + suffix,
                research = com.androidharness.app.core.ResearchReceipt(backend = suffix.substringAfter("[via ", "").substringBefore(']')))
        }
        val claimed = budget.claim(varied.take(count))
        shown += claimed
        if (claimed.isEmpty()) {
            val message = if (budget.remaining == 0) exhaustedText()
            else "[No new sources found; ${budget.remaining} of ${budget.maxSources} sources remain this turn. Try a different query angle.]"
            return ToolResult(true, prefix + message + suffix, research = budget.receipt(backend = suffix.substringAfter("[via ", "").substringBefore(']')))
        }
        val push = when (depth) {
            com.androidharness.app.data.SearchDepth.DEEP ->
                if (pageReader != null) ". Pages read below count; use web_fetch for other strong results before answering."
                else ". Read the strongest pages with web_fetch before answering."
            com.androidharness.app.data.SearchDepth.DEEP_RESEARCH,
            com.androidharness.app.data.SearchDepth.MAX_RESEARCH ->
                ". Keep searching different angles and read the strongest pages with web_fetch until the budget is used."
            else -> ""
        }
        val note = buildString {
            append("\n[sources this turn: ${budget.used}/${budget.maxSources}, ${budget.remaining} remaining")
            if (budget.minReads > 0) {
                append("; pages read: ${budget.pagesRead}/${budget.minReads} required")
            }
            append(push)
            append(']')
        }
        return ToolResult(true, prefix + WebSearchText.render(claimed, claimed.size) + suffix + note,
            research = budget.receipt(backend = suffix.substringAfter("[via ", "").substringBefore(']')))
    }

    private companion object {
        const val READ_DEFAULT = 4
        const val READ_MAX = 6
    }

    private fun exhaustedText(): String {
        val budget = sourceBudget ?: return ""
        return "[Search source limit reached: ${budget.used}/${budget.maxSources} sources this turn. " +
            "Do not search again; read the strongest pages already found with web_fetch, then answer from what you have.]"
    }
}

/**
 * Decides when http_request attaches the stored GitHub token. API hosts
 * (api.github.com, uploads.github.com) get it AUTOMATICALLY, anonymous API
 * calls from the main agent were a standing footgun (60 req/h rate limit,
 * private repos 404). Explicit true widens to other github.com /
 * githubusercontent.com hosts; explicit false never attaches. The token is
 * NEVER attached for any other host, whatever the arguments say.
 */
internal val HTTP_TOOL_USER_AGENT: String =
    "AndroidHarness/${com.androidharness.app.BuildConfig.VERSION_NAME} (+https://github.com/Sanuu7/AndroidHarness)"

internal fun httpReason(code: Int): String? = when (code) {
    200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
    301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"; 307 -> "Temporary Redirect"; 308 -> "Permanent Redirect"
    400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
    408 -> "Request Timeout"; 409 -> "Conflict"; 410 -> "Gone"; 413 -> "Content Too Large"; 415 -> "Unsupported Media Type"
    418 -> "I'm a teapot"; 422 -> "Unprocessable Content"; 429 -> "Too Many Requests"
    500 -> "Internal Server Error"; 501 -> "Not Implemented"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
    else -> null
}

internal object GithubAuthPolicy {
    fun shouldAttach(host: String?, explicit: Boolean?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return when (explicit) {
            false -> false
            true -> h == "github.com" || h.endsWith(".github.com") ||
                h == "githubusercontent.com" || h.endsWith(".githubusercontent.com")
            null -> h == "api.github.com" || h == "uploads.github.com"
        }
    }
}

internal object HttpBinaryDetector {
    fun isBinaryMediaType(mediaType: MediaType?): Boolean {
        if (mediaType == null) return false
        val type = mediaType.type.lowercase()
        val subtype = mediaType.subtype.lowercase()
        if (type == "text") return false
        if (subtype == "svg+xml") return false
        if (type in setOf("image", "audio", "video", "font")) return true
        if (subtype == "json" || subtype.endsWith("+json")) return false
        if (subtype == "xml" || subtype.endsWith("+xml")) return false
        if (subtype == "yaml" || subtype == "x-yaml" || subtype.endsWith("+yaml")) return false
        if (subtype == "javascript" || subtype == "x-javascript" || subtype == "ecmascript") return false
        if (subtype == "graphql" || subtype == "x-www-form-urlencoded") return false
        if (subtype == "sql" || subtype == "csv") return false
        return true
    }
}

/** Generic HTTP client for testing APIs. */
class HttpRequestTool(
    private val client: OkHttpClient,
    /** Stored GitHub token from the app's encrypted settings (lazy, may be null). */
    private val githubToken: () -> String? = { null },
) : Tool {
    override val name = "http_request"
    override val description =
        "Send an HTTP request (GET/POST/PUT/PATCH/DELETE) with optional headers and body. " +
        "Public addresses only; localhost/private destinations are blocked and redirects are not followed. " +
        "Returns status, headers and the truncated response body. Requests to api.github.com " +
        "and uploads.github.com are authenticated with the configured GitHub token automatically " +
        "(set github_auth=false to go anonymous); github_auth=true extends auth to other " +
        "github.com/githubusercontent.com hosts."
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
            "github_auth" to Schema.boolean(
                "GitHub token handling. Omitted: attach automatically for api.github.com and " +
                    "uploads.github.com. true: also attach for other github.com / " +
                    "githubusercontent.com hosts. false: never attach (anonymous request).",
            ),
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
            val githubAuth = args["github_auth"]
                ?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
            val host = runCatching { url.toHttpUrlOrNull()?.host }.getOrNull()
            val attachAuth = GithubAuthPolicy.shouldAttach(host, githubAuth)
            val token = if (attachAuth) githubToken() else null

            // OkHttp's bare "okhttp/x" agent is refused by sites that ask clients to identify
            // themselves (Wikipedia, for one). A headers argument can still replace it.
            val builder = Request.Builder().url(url).header("User-Agent", HTTP_TOOL_USER_AGENT)
            // Attached first so an explicit user Authorization header wins.
            if (attachAuth && token != null) {
                builder.header("Authorization", "Bearer $token")
            }
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

            try {
                val request = builder.build()
                val addresses = PublicHttpAddresses.validate(client.dns.lookup(request.url.host))
                val reqClient = client.newBuilder()
                    .proxy(java.net.Proxy.NO_PROXY)
                    .dns(object : okhttp3.Dns {
                        override fun lookup(hostname: String): List<java.net.InetAddress> =
                            if (hostname == request.url.host) addresses
                            else PublicHttpAddresses.validate(client.dns.lookup(hostname))
                    })
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectionPool(okhttp3.ConnectionPool())
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                reqClient.newCall(request).execute().use { resp ->
                    val sb = StringBuilder()
                    sb.append("HTTP ").append(resp.code)
                    // HTTP/2 has no reason phrase; fall back to the standard one instead of a trailing space.
                    (resp.message.trim().ifEmpty { httpReason(resp.code) })?.let { sb.append(' ').append(it) }
                    sb.append('\n')
                    val headers = (0 until minOf(resp.headers.size, 12))
                        .map { resp.headers.name(it) to resp.headers.value(it) }
                    headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append('\n') }
                    sb.append('\n')
                    val body = resp.body
                    val mediaType = body?.contentType()
                    if (HttpBinaryDetector.isBinaryMediaType(mediaType)) {
                        val mime = mediaType?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
                        val len = if ((body?.contentLength() ?: -1L) >= 0L) {
                            body!!.contentLength()
                        } else {
                            body?.bytes()?.size?.toLong() ?: 0L
                        }
                        sb.append("[binary content: $mime, $len bytes]")
                    } else {
                        val bytes = body?.bytes() ?: ByteArray(0)
                        if (mediaType == null && bytes.isNotEmpty() && com.androidharness.app.workspace.isBinaryStream(java.io.ByteArrayInputStream(bytes))) {
                            sb.append("[binary content: application/octet-stream, ${bytes.size} bytes]")
                        } else {
                            val respBody = bytes.toString(mediaType?.charset() ?: Charsets.UTF_8)
                            sb.append(respBody.take(20_000))
                            if (respBody.length > 20_000) sb.append("\n[truncated]")
                        }
                    }
                    if (attachAuth && token == null) {
                        sb.append("\n[note: no GitHub token configured; request sent anonymously; " +
                            "set one in Settings → GitHub for private repos and the 5000 req/h limit]")
                    }
                    if (attachAuth && token != null && (resp.code == 401 || resp.code == 403)) {
                        sb.append("\n[note: GitHub refused an AUTHENTICATED request (" + resp.code + "): " +
                            "check the token's validity/scopes with doctor --github]")
                    }
                    ToolResult(true, sb.toString())
                }
            } catch (e: Exception) {
                ToolResult(false, "Request failed: ${e.message}")
            }
        }
}
