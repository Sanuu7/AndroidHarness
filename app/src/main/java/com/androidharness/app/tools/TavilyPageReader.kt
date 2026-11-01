package com.androidharness.app.tools

import java.io.IOException
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal class TavilyPageUnavailable(message: String) : IOException(message)
internal data class ExtractedPage(val text: String, val images: List<PageImage>)

internal object TavilyPageReader {
    private val json = Json { ignoreUnknownKeys = true }
    private val failures = object : LinkedHashMap<String, Pair<Long, String>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, String>>) = size > 64
    }

    /** Never send local device/private network addresses to an external extraction service. */
    fun publicUrl(raw: String): Boolean {
        val url = raw.toHttpUrlOrNull() ?: return false
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        if (url.queryParameterNames.any { it.lowercase() in setOf("token", "access_token", "api_key", "apikey", "password", "signature", "sig", "auth") || it.startsWith("X-Amz-", true) }) return false
        val host = url.host.lowercase()
        if (host in setOf("localhost", "appassets.androidplatform.net") || listOf(".localhost", ".local", ".internal", ".test", ".lan").any(host::endsWith)) return false
        if (':' in host) return false
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size == 4) return octets[0] !in setOf(0, 10, 127, 169, 192) &&
            !(octets[0] == 172 && octets[1] in 16..31) && octets[0] < 224
        return host.contains('.')
    }

    suspend fun extract(client: OkHttpClient, config: SearchApiConfig, url: String,
        advanced: Boolean, images: Boolean): ExtractedPage {
        val cacheKey = config.credentialIdentity() + ":" + canonicalResultUrl(url)
        synchronized(failures) { failures[cacheKey] }?.takeIf { System.currentTimeMillis() - it.first < 60000 }?.let {
            throw TavilyPageUnavailable(it.second + " (recent extraction failure cached; no new API request)")
        }
        val payload = buildJsonObject {
            put("urls", url)
            put("extract_depth", if (advanced) "advanced" else "basic")
            put("format", "text")
            put("include_images", images)
            put("timeout", 20)
        }
        val body = withKeyFailover(config.keys) { apiKey ->
            val request = Request.Builder().url("https://api.tavily.com/extract")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            ResearchHttp.read(client, request) { ResearchHttp.bodyText(it) }
        }
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw TavilyPageUnavailable("Tavily Extract returned an invalid response.")
        val rows = (obj["results"] as? JsonArray).orEmpty()
        val row = rows.firstOrNull { entry ->
            canonicalResultUrl(entry.jsonObject["url"]?.jsonPrimitive?.contentOrNull.orEmpty()) == canonicalResultUrl(url)
        }?.jsonObject ?: rows.singleOrNull()?.jsonObject
        val text = row?.get("raw_content")?.jsonPrimitive?.contentOrNull?.take(400000).orEmpty()
        val issue = ReadableWebPage.problem(text)
        if (issue != null) {
            val message = "Tavily Extract could not read this page. $issue"
            synchronized(failures) { failures[cacheKey] = System.currentTimeMillis() to message }
            throw TavilyPageUnavailable(message)
        }
        val pageHost = url.toHttpUrlOrNull()?.host.orEmpty()
        val foundImages = (row?.get("images") as? JsonArray).orEmpty().mapNotNull { entry ->
            val raw = (entry as? JsonPrimitive)?.contentOrNull ?: (entry as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
            val imageUrl = raw?.toHttpUrlOrNull() ?: return@mapNotNull null
            PageImage(pageHost, imageUrl.toString(), url, pageHost)
        }.take(8)
        return ExtractedPage(text, foundImages)
    }
}
