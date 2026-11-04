package com.androidharness.app.llm

import kotlinx.serialization.json.*
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/** Accept explicit delays, never infer a reset time from a generic HTTP 429. */
internal fun providerRetryAfterSeconds(value: JsonElement?, depth: Int = 0): Long? {
    if (depth > 4) return null
    val fields = value as? JsonObject ?: return null
    for (key in listOf("retry_after_seconds", "retryAfterSeconds")) {
        retrySeconds((fields[key] as? JsonPrimitive)?.contentOrNull)?.let { return it }
    }
    for (key in listOf("error", "metadata", "details")) {
        providerRetryAfterSeconds(fields[key], depth + 1)?.let { return it }
    }
    return null
}

private fun retrySeconds(value: String?): Long? = value?.toDoubleOrNull()
    ?.takeIf { it.isFinite() && it >= 0 && it <= 31_536_000 }
    ?.let { ceil(it).toLong() }

internal fun retryAfterHeaderSeconds(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
    if (value == null) return null
    retrySeconds(value.trim())?.let { return it }
    return runCatching {
        val deadline = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        ceil((deadline - nowMillis).coerceAtLeast(0) / 1000.0).toLong().takeIf { it <= 31_536_000 }
    }.getOrNull()
}
