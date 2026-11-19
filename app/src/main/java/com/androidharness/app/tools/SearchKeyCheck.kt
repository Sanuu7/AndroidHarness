package com.androidharness.app.tools

import com.androidharness.app.llm.ProviderKeyCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Sends one real one-result search with a key before it is saved, so a bad
 * paste never becomes the stored credential. The request is the same one
 * web_search sends, which means a key that passes here also works there.
 */
object SearchKeyCheck {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun check(provider: String, key: String, http: OkHttpClient = client): ProviderKeyCheck.Result = withContext(Dispatchers.IO) {
        val name = SearchProviders.name(provider) ?: provider
        val request = request(provider, key.trim())
            ?: return@withContext ProviderKeyCheck.Result.Failed("$name does not use API keys.")
        val started = System.nanoTime()
        runCatching {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty().take(64_000)
                val status = if (response.isSuccessful) backend(provider, key)?.bodyStatus(body) else response.code
                val ms = (System.nanoTime() - started) / 1_000_000
                if (status == null) ProviderKeyCheck.Result.Ok(ms, null)
                else ProviderKeyCheck.Result.Failed(explain(name, status, body, response.header("Retry-After")), status)
            }
        }.getOrElse { ProviderKeyCheck.Result.Failed("Could not reach $name. Check your connection and try again.") }
    }

    private fun backend(provider: String, key: String): KeyedSearchBackend? =
        searchBackendFor(SearchApiConfig(provider, key)) as? KeyedSearchBackend

    internal fun request(provider: String, key: String): Request? = when (provider) {
        "brave" -> Request.Builder()
            .url("https://api.search.brave.com/res/v1/web/search?q=android&count=1")
            .header("X-Subscription-Token", key)
            .header("Accept", "application/json")
            .build()
        "tavily" -> Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Authorization", "Bearer $key")
            .post("""{"query":"android","max_results":1}""".toRequestBody("application/json".toMediaType()))
            .build()
        else -> backend(provider, key)?.request(key, "android", 1, SearchOptions())
    }

    internal fun explain(name: String, status: Int, body: String, retryAfter: String?): String {
        val detail = Regex("\"(?:message|detail|error|msg)\"\\s*:\\s*\"([^\"]{1,200})\"").find(body)?.groupValues?.get(1)?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
        val credits = detail?.contains("credit", ignoreCase = true) == true || detail?.contains("quota", ignoreCase = true) == true ||
            detail?.contains("run out", ignoreCase = true) == true || detail?.contains("balance", ignoreCase = true) == true
        return when {
            status in setOf(402, 432, 433) || credits ->
                "$name has no credits left on this key, or its plan limit was reached. Check the $name dashboard."
            status == 401 || status == 403 ->
                "$name rejected this key" + (detail?.let { " ($it)" } ?: "") +
                    ". Check that the whole key was copied, that it is a $name key, and that it is still active."
            status == 429 ->
                "$name is rate limiting this key right now. Wait" +
                    (retryAfter?.toLongOrNull()?.let { " $it seconds" } ?: " a moment") + " and test again."
            else -> "$name returned HTTP $status" + (detail?.let { ": $it" } ?: "") + ". Try again shortly."
        }
    }
}
