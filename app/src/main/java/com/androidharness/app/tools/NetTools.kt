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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Web search. When the user configured a search API (Brave / Tavily key in
 * Settings) that backend is used first for higher-quality results, with the
 * keyless HTML engine chain as automatic fallback. Without a key the keyless
 * chain runs directly: an engine parameter picks one, "auto" tries them all.
 */
class WebSearchTool(
    private val client: OkHttpClient,
    /** User's search API config, read fresh per call (may be null = keyless). */
    private val searchApi: () -> SearchApiConfig? = { null },
) : Tool {
    override val name = "web_search"
    override val description =
        "Search the web and return top results (title, url, snippet). Uses the configured " +
        "search API automatically when one is set in Settings; otherwise keyless engines " +
        "run with automatic fallback. Choose an engine with the 'engine' parameter " +
        "(duckduckgo, bing, brave, google — keyless mode only) or use 'auto'."
    override val parametersSchema = Schema.obj(
        mapOf(
            "query" to Schema.string("The search query."),
            "count" to Schema.integer("Maximum number of results (default 8)."),
            "engine" to Schema.string("duckduckgo | bing | brave | google | auto (default). Keyless mode only."),
        ),
        required = listOf("query"),
    )
    override val isReadOnly = true

    private val keyless = KeylessSearchBackend()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args["query"]?.jsonPrimitive?.content
            ?: throw ToolFailure("Missing required argument: query")
        val count = (args["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 8).coerceIn(1, 15)
        val requested = args["engine"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: "auto"
        val searchClient = client.newBuilder()
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

        val notes = StringBuilder()
        val apiBackend = searchBackendFor(searchApi())
        if (apiBackend != null) {
            try {
                val outcome = apiBackend.fetch(searchClient, query, count)
                if (outcome.results.isNotEmpty()) {
                    val text = StringBuilder(formatResults(outcome.results, count))
                    if (requested != "auto") {
                        // The engine selector is keyless-only; say so instead of
                        // silently returning different results than asked for.
                        text.append("\n[note: the engine parameter is ignored — ${apiBackend.label} is active]")
                    }
                    text.append("\n[via ").append(outcome.via ?: apiBackend.label).append(']')
                    return ToolResult(true, text.toString())
                }
                notes.append("[note: ${apiBackend.label} returned no results; falling back to keyless engines]\n")
            } catch (e: Exception) {
                notes.append("[note: ${apiBackend.label} failed: ${e.message}; falling back to keyless engines]\n")
            }
        }

        val outcome = try {
            keyless.fetch(searchClient, query, count, requested)
        } catch (e: ToolFailure) {
            return ToolResult(
                false,
                notes.toString() + e.message +
                    " You can also use web_fetch with a known URL.",
            )
        } catch (e: Exception) {
            return ToolResult(false, notes.toString() + "Search failed: ${e.message}")
        }
        val text = StringBuilder(notes).append(formatResults(outcome.results, count))
        outcome.via?.let { text.append("\n[via ").append(it).append(']') }
        return ToolResult(true, text.toString())
    }

    private fun formatResults(results: List<WebSearchResult>, count: Int): String =
        results.take(count).mapIndexed { idx, r ->
            "${idx + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}"
        }.joinToString("\n")
}

/**
 * Decides when http_request attaches the stored GitHub token. API hosts
 * (api.github.com, uploads.github.com) get it AUTOMATICALLY — anonymous API
 * calls from the main agent were a standing footgun (60 req/h rate limit,
 * private repos 404). Explicit true widens to other github.com /
 * githubusercontent.com hosts; explicit false never attaches. The token is
 * NEVER attached for any other host, whatever the arguments say.
 */
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

/** Generic HTTP client for testing APIs. */
class HttpRequestTool(
    private val client: OkHttpClient,
    /** Stored GitHub token from the app's encrypted settings (lazy, may be null). */
    private val githubToken: () -> String? = { null },
) : Tool {
    override val name = "http_request"
    override val description =
        "Send an HTTP request (GET/POST/PUT/PATCH/DELETE) with optional headers and body. " +
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

            val builder = Request.Builder().url(url)
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
                    if (attachAuth && token == null) {
                        sb.append("\n[note: no GitHub token configured — request sent anonymously; " +
                            "set one in Settings → GitHub for private repos and the 5000 req/h limit]")
                    }
                    if (attachAuth && token != null && (resp.code == 401 || resp.code == 403)) {
                        sb.append("\n[note: GitHub refused an AUTHENTICATED request (" + resp.code + ") — " +
                            "check the token's validity/scopes with doctor --github]")
                    }
                    ToolResult(true, sb.toString())
                }
            } catch (e: Exception) {
                ToolResult(false, "Request failed: ${e.message}")
            }
        }
}
