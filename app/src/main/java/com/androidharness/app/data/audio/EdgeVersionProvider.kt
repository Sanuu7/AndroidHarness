package com.androidharness.app.data.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Keeps the Edge build number in [EdgeTtsClient.chromiumVersion] current.
 *
 * Microsoft rejects read-aloud clients that advertise an old Edge build, and
 * Edge ships a new stable roughly every four weeks. Hardcoding the version
 * means the free provider silently starts returning 403 a few weeks after each
 * app release, so the version is fetched from Microsoft's own update feed and
 * cached for a day.
 *
 * The feed is public and needs no key:
 * `https://edgeupdates.microsoft.com/api/products?view=enterprise`
 * returns one entry per channel (Dev, Beta, Stable, ...) with releases per
 * platform; the Windows Stable entry is the build Edge itself runs.
 */
class EdgeVersionProvider(
    context: Context?,
    private val cacheDir: File = File(context?.cacheDir ?: File(System.getProperty("java.io.tmpdir")), "edge-version"),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Refreshes the advertised build when the cached value is stale. Safe to
     * call before every synthesis: it returns immediately once the value is fresh.
     */
    suspend fun ensureFresh(): String = withContext(Dispatchers.IO) {
        cached()?.let { return@withContext apply(it) }
        val fetched = fetch()
        if (fetched != null) {
            writeCache(fetched)
            apply(fetched)
        } else {
            // Keep whatever is already advertised; the fallback constant is
            // already the compile-time default.
            EdgeTtsClient.chromiumVersion
        }
    }

    /** Forces a fresh lookup; used after a 403 to rule out a stale build. */
    suspend fun refresh(): String = withContext(Dispatchers.IO) {
        val fetched = fetch()
        if (fetched != null) {
            writeCache(fetched)
            apply(fetched)
        } else {
            EdgeTtsClient.chromiumVersion
        }
    }

    private fun apply(version: String): String {
        EdgeTtsClient.chromiumVersion = version
        return version
    }

    /** Latest Windows Stable build, or null when the feed cannot be read. */
    private suspend fun fetch(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(PRODUCTS_URL).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                parseStableVersion(response.body?.string().orEmpty())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Walks `[{Product, Releases:[{Platform, ProductVersion, PublishedTime}]}]`
     * and picks the newest Windows release of the Stable channel.
     */
    internal fun parseStableVersion(body: String): String? {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val products = element as? kotlinx.serialization.json.JsonArray ?: return null
        for (product in products) {
            val obj = product as? kotlinx.serialization.json.JsonObject ?: continue
            val name = obj["Product"]?.jsonPrimitive?.content ?: continue
            if (!name.equals("Stable", ignoreCase = true)) continue
            val releases = obj["Releases"] as? kotlinx.serialization.json.JsonArray ?: continue
            val windows = releases.mapNotNull { release ->
                val r = release as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val platform = r["Platform"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (!platform.equals("Windows", ignoreCase = true)) return@mapNotNull null
                val version = r["ProductVersion"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val published = r["PublishedTime"]?.jsonPrimitive?.content.orEmpty()
                version to published
            }
            if (windows.isEmpty()) continue
            // PublishedTime is ISO-8601, so lexical order is chronological.
            return windows.maxByOrNull { it.second }?.first?.takeIf { it.isValidVersion() }
        }
        return null
    }

    /** A build number like "156.0.4314.80"; anything else is discarded. */
    private fun String.isValidVersion(): Boolean = VERSION_PATTERN.matches(this)

    private fun cacheFile() = File(cacheDir, "edge-build.txt")

    private fun cached(): String? {
        val file = cacheFile()
        if (!file.exists()) return null
        val age = System.currentTimeMillis() - file.lastModified()
        if (age !in 0..CACHE_TTL_MS) return null
        val text = runCatching { file.readText().trim() }.getOrNull() ?: return null
        return text.takeIf { it.isValidVersion() }
    }

    private suspend fun writeCache(version: String) = withContext(Dispatchers.IO) {
        runCatching {
            cacheDir.mkdirs()
            cacheFile().writeText(version)
        }
        Unit
    }

    companion object {
        const val PRODUCTS_URL = "https://edgeupdates.microsoft.com/api/products?view=enterprise"

        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
        private val VERSION_PATTERN = Regex("""\d+\.\d+\.\d+\.\d+""")
    }
}
