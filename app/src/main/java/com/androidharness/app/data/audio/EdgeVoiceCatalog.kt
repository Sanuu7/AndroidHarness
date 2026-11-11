package com.androidharness.app.data.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One Edge voice, as the catalogue endpoint describes it. */
@Serializable
data class EdgeVoice(
    /** `pt-BR-FranciscaNeural`, the id SSML needs. */
    val shortName: String,
    /** `pt-BR`. */
    val locale: String,
    /** `Portugiesisch (Brasilien)` in the user's language when possible. */
    val localeName: String = "",
    /** "Female" or "Male". */
    val gender: String = "",
    /** Display name without the language suffix: "Francisca". */
    val displayName: String = shortName,
) {
    val isFemale: Boolean get() = gender.equals("Female", ignoreCase = true)
    val isMale: Boolean get() = gender.equals("Male", ignoreCase = true)

    /** Multilingual voices can speak every language; worth surfacing first. */
    val isMultilingual: Boolean get() = shortName.contains("Multilingual", ignoreCase = true)
}

/** Voices grouped under one locale, ready for a sectioned picker. */
data class EdgeVoiceGroup(
    val locale: String,
    /** Localized language name, e.g. "Português (Brasil)". */
    val title: String,
    val voices: List<EdgeVoice>,
)

/**
 * The Microsoft Edge voice catalogue: 300+ voices across ~140 locales, served
 * as one JSON array from a host that needs no key.
 *
 * The list changes rarely, so it is cached on disk for a day. When a fetch
 * fails and no cache exists, [fallback] keeps the picker usable instead of
 * showing an empty list.
 */
class EdgeVoiceCatalog(
    context: Context?,
    private val cacheDir: File = File(context?.cacheDir ?: File(System.getProperty("java.io.tmpdir")), "edge-voices"),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(EdgeVoice.serializer())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** In-memory copy, so opening the picker twice does not refetch. */
    @Volatile
    private var memory: List<EdgeVoice>? = null

    /**
     * All voices, newest available source first: memory, then a fresh-enough
     * disk cache, then the network, then the shipped fallback.
     */
    suspend fun voices(forceRefresh: Boolean = false): List<EdgeVoice> {
        memory?.takeIf { !forceRefresh }?.let { return it }

        if (!forceRefresh) {
            readCache()?.let { cached ->
                memory = cached
                return cached
            }
        }

        val fetched = fetch()
        if (fetched != null) {
            memory = fetched
            writeCache(fetched)
            return fetched
        }

        // Network failed: prefer a stale cache over nothing at all.
        readCache(ignoreAge = true)?.let {
            memory = it
            return it
        }

        val shipped = fallback()
        memory = shipped
        return shipped
    }

    /** Fresh fetch from Microsoft, or null when it fails. */
    private suspend fun fetch(): List<EdgeVoice>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(EdgeTtsClient.VOICE_LIST_URL).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                parse(body)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Parses the array of `{ShortName, Locale, LocaleName, Gender, ...}` objects. */
    internal fun parse(body: String): List<EdgeVoice>? {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val array = element as? kotlinx.serialization.json.JsonArray ?: return null
        val voices = array.mapNotNull { item ->
            val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun field(name: String): String =
                obj[name]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: ""
            val short = field("ShortName")
            if (short.isBlank()) return@mapNotNull null
            EdgeVoice(
                shortName = short,
                locale = field("Locale"),
                localeName = field("LocaleName"),
                gender = field("Gender"),
                displayName = displayNameFor(short),
            )
        }
        return voices.takeIf { it.isNotEmpty() }
    }

    private fun cacheFile() = File(cacheDir, "voices.json")

    private fun cacheIsFresh(): Boolean {
        val file = cacheFile()
        if (!file.exists()) return false
        val age = System.currentTimeMillis() - file.lastModified()
        return age in 0..CACHE_TTL_MS
    }

    private suspend fun readCache(ignoreAge: Boolean = false): List<EdgeVoice>? =
        withContext(Dispatchers.IO) {
            if (!ignoreAge && !cacheIsFresh()) return@withContext null
            val file = cacheFile()
            if (!file.exists()) return@withContext null
            runCatching { json.decodeFromString(serializer, file.readText()) }
                .getOrNull()?.takeIf { it.isNotEmpty() }
        }

    private suspend fun writeCache(voices: List<EdgeVoice>) = withContext(Dispatchers.IO) {
        runCatching {
            cacheDir.mkdirs()
            cacheFile().writeText(json.encodeToString(serializer, voices))
        }
    }

    /**
     * Shipped list, used only when there is no cache and no network. It keeps
     * the common languages working so the picker is never empty.
     */
    fun fallback(): List<EdgeVoice> = VoicePresets.edgeVoices.map { short ->
        EdgeVoice(
            shortName = short,
            locale = localeOf(short),
            localeName = "",
            gender = "",
            displayName = displayNameFor(short),
        )
    }

    /**
     * Groups voices by locale for the picker: the app language first, then
     * every other locale alphabetically. Within a group women come first so
     * the common choice is at the top, then alphabetical by name.
     */
    fun group(voices: List<EdgeVoice>, appLocale: Locale = Locale.getDefault()): List<EdgeVoiceGroup> {
        val primaryTag = appLocale.toLanguageTag()
        val language = appLocale.language
        val byLocale = voices.groupBy { it.locale }
        val ordered = byLocale.entries.sortedWith(
            compareBy<Map.Entry<String, List<EdgeVoice>>> { entry ->
                when {
                    // Exact match (pt-BR for pt-BR) first.
                    entry.key.equals(primaryTag, ignoreCase = true) -> 0
                    // Same language, other region (pt-PT) next.
                    entry.key.substringBefore('-').equals(language, ignoreCase = true) -> 1
                    else -> 2
                }
            }.thenBy { entry -> titleFor(entry.key, entry.value.firstOrNull()?.localeName.orEmpty()).lowercase(Locale.ROOT) },
        )
        return ordered.map { (locale, list) ->
            EdgeVoiceGroup(
                locale = locale,
                title = titleFor(locale, list.firstOrNull()?.localeName.orEmpty()),
                voices = list.sortedWith(
                    compareByDescending<EdgeVoice> { it.isFemale }
                        .thenBy { it.displayName.lowercase(Locale.ROOT) },
                ),
            )
        }
    }

    /**
     * Language name in the app's language. The endpoint returns English names
     * ("Portuguese (Brazil)"); a Locale lookup gives a localized one.
     */
    fun titleFor(locale: String, endpointName: String, display: Locale = Locale.getDefault()): String {
        val fromLocale = runCatching {
            val parsed = Locale.forLanguageTag(locale)
            val name = parsed.getDisplayName(display)
            if (name.isBlank() || name.equals(locale, ignoreCase = true)) "" else name
        }.getOrDefault("")
        return fromLocale.ifBlank { endpointName.ifBlank { locale } }
    }

    companion object {
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

        /** `pt-BR-FranciscaNeural` → `pt-BR`. */
        internal fun localeOf(shortName: String): String {
            val parts = shortName.split('-')
            return if (parts.size >= 2) parts[0] + "-" + parts[1] else shortName
        }

        /** `pt-BR-FranciscaNeural` → `Francisca`; keeps `Multilingual` visible. */
        internal fun displayNameFor(shortName: String): String {
            val parts = shortName.split('-')
            if (parts.size < 3) return shortName
            return parts.drop(2).joinToString("-").removeSuffix("Neural")
        }
    }
}
