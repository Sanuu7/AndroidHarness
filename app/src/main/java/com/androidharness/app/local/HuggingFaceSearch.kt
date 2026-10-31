package com.androidharness.app.local

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class HubSort(val parameter: String) { POPULAR("downloads"), TRENDING("trendingScore"), NEWEST("lastModified"), LIKED("likes") }

/** One repository from a Hugging Face search. Sizes are estimates until the files are listed. */
data class HubModel(
    val repository: String,
    val downloads: Long,
    val likes: Long,
    val parameters: Long?,
    /** Weight download size for safetensors, from the parameter count and dtype. Null for GGUF repositories. */
    val downloadBytes: Long?,
    val updated: String?,
    val format: LocalModelFormat,
) {
    val author get() = repository.substringBefore('/')
    val name get() = repository.substringAfter('/')
    val url get() = "https://huggingface.co/$repository"
    /** Rough size of the Q4 GGUF the local engine would load. */
    val estimatedQ4Bytes: Long? get() = parameters?.let { (it * Q4_BYTES_PER_PARAMETER).toLong() }

    /** True when the estimate fits this phone, or when the size is unknown. */
    fun likelyFits(device: LocalDeviceProfile): Boolean {
        val size = estimatedQ4Bytes ?: return device.supported
        return device.supported && size + RUNTIME_OVERHEAD <= device.totalRam * 55 / 100
    }

    companion object {
        const val Q4_BYTES_PER_PARAMETER = 0.6
        const val RUNTIME_OVERHEAD = 900L * 1024 * 1024
    }
}

/** Searches public text-generation repositories that carry GGUF or safetensors weights. */
class HuggingFaceSearch(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()) {

    internal fun url(query: String, format: LocalModelFormat, sort: HubSort, limit: Int) =
        "https://huggingface.co/api/models".toHttpUrl().newBuilder().apply {
            query.trim().takeIf { it.isNotEmpty() }?.let { addQueryParameter("search", it.take(120)) }
            when (format) {
                LocalModelFormat.GGUF -> { addQueryParameter("filter", "gguf"); addQueryParameter("filter", "text-generation") }
                LocalModelFormat.SAFETENSORS -> { addQueryParameter("filter", "safetensors"); addQueryParameter("pipeline_tag", "text-generation") }
            }
            addQueryParameter("sort", sort.parameter)
            addQueryParameter("direction", "-1")
            addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            listOf("downloads", "likes", "gated", "private", "lastModified", "safetensors").forEach { addQueryParameter("expand[]", it) }
        }.build()

    fun search(query: String, format: LocalModelFormat, sort: HubSort, limit: Int = 25): List<HubModel> {
        client.newCall(Request.Builder().url(url(query, format, sort, limit)).header("Accept", "application/json").build()).execute().use { response ->
            check(response.isSuccessful) { when (response.code) {
                429 -> "Hugging Face is busy. Try again in a moment."
                else -> "Hugging Face returned HTTP ${response.code}."
            } }
            val bytes = readPrefix(checkNotNull(response.body) { "Empty response." }.byteStream(), MAX_RESPONSE + 1)
            check(bytes.size <= MAX_RESPONSE) { "Hugging Face sent more data than expected." }
            return parse(bytes.toString(Charsets.UTF_8), format)
        }
    }

    internal fun parse(text: String, format: LocalModelFormat): List<HubModel> =
        Json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!id.matches(REPOSITORY)) return@mapNotNull null
            // Gated repositories ("auto" or "manual") and private ones need a login this app never asks for.
            val gated = item["gated"]?.jsonPrimitive?.let { it.booleanOrNull == true || it.booleanOrNull == null && it.contentOrNull != null }
            if (gated == true || item["private"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val weights = item["safetensors"] as? JsonObject
            val total = weights?.get("total")?.jsonPrimitive?.longOrNull
            val parameters = if (format == LocalModelFormat.SAFETENSORS) total else total ?: parseParameterCount(id.substringAfter('/'))
            val download = if (format == LocalModelFormat.SAFETENSORS) (weights?.get("parameters") as? JsonObject)?.let { map ->
                map.entries.sumOf { (dtype, count) -> (count.jsonPrimitive.longOrNull ?: 0L) * dtypeBytes(dtype) }
            }?.takeIf { it > 0 } else null
            HubModel(id, item["downloads"]?.jsonPrimitive?.longOrNull ?: 0, item["likes"]?.jsonPrimitive?.longOrNull ?: 0,
                parameters, download, item["lastModified"]?.jsonPrimitive?.contentOrNull, format)
        }

    private companion object {
        const val MAX_RESPONSE = 2 * 1024 * 1024
        val REPOSITORY = Regex("[A-Za-z0-9_.-]{1,96}/[A-Za-z0-9_.-]{1,96}")
    }
}

internal fun dtypeBytes(dtype: String): Long = when (dtype.uppercase(Locale.ROOT)) {
    "F64", "I64", "U64" -> 8
    "F32", "I32", "U32" -> 4
    "BOOL", "I8", "U8", "F8_E4M3", "F8_E5M2" -> 1
    else -> 2
}

/** Reads "7B", "1.5B" or "135M" from a repository name. Returns null when the name carries no size. */
internal fun parseParameterCount(name: String): Long? {
    val match = Regex("(?<![A-Za-z0-9.])(\\d+(?:\\.\\d+)?)([BbMm])(?![A-Za-z])").find(name) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    val unit = if (match.groupValues[2].equals("b", true)) 1_000_000_000.0 else 1_000_000.0
    return (value * unit).toLong().takeIf { it > 0 }
}

/** 1_234 -> "1.2K", 3_400_000 -> "3.4M". */
internal fun compactCount(value: Long): String = when {
    value >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", value / 1e9)
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1e6)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1e3)
    else -> value.toString()
}
