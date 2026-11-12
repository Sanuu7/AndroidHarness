package com.androidharness.app.llm.debug

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON helpers for reading a captured body: indent it, colour it, export it.
 *
 * The tokenizer is deliberately forgiving. A body in the log is often exactly
 * the broken one, so it never throws and simply stops marking where the text
 * stops looking like JSON.
 */
object DebugJson {

    enum class Kind { KEY, STRING, NUMBER, LITERAL, PUNCTUATION }

    /** [start] inclusive, [end] exclusive. */
    data class Token(val start: Int, val end: Int, val kind: Kind)

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val strict = Json { }

    /** The body indented for reading, or null when it is not JSON. */
    fun pretty(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || (trimmed[0] != '{' && trimmed[0] != '[')) return null
        return runCatching {
            pretty.encodeToString(JsonElement.serializer(), strict.parseToJsonElement(trimmed))
        }.getOrNull()
    }

    fun isJson(text: String): Boolean = runCatching { strict.parseToJsonElement(text) }.isSuccess

    fun tokens(text: String): List<Token> {
        val out = ArrayList<Token>()
        var index = 0
        val length = text.length
        while (index < length) {
            val char = text[index]
            when {
                char == '"' -> {
                    var end = index + 1
                    while (end < length && text[end] != '"') {
                        if (text[end] == '\\') end++
                        end++
                    }
                    end = (end + 1).coerceAtMost(length)
                    // A string followed by a colon is a key.
                    var after = end
                    while (after < length && text[after].isWhitespace()) after++
                    val key = after < length && text[after] == ':'
                    out += Token(index, end, if (key) Kind.KEY else Kind.STRING)
                    index = end
                }
                char == '-' || char.isDigit() -> {
                    var end = index + 1
                    while (end < length && (text[end].isDigit() || text[end] in ".eE+-")) end++
                    out += Token(index, end, Kind.NUMBER)
                    index = end
                }
                char.isLetter() -> {
                    var end = index + 1
                    while (end < length && text[end].isLetter()) end++
                    val word = text.substring(index, end)
                    if (word == "true" || word == "false" || word == "null") out += Token(index, end, Kind.LITERAL)
                    index = end
                }
                char in "{}[]:," -> {
                    out += Token(index, index + 1, Kind.PUNCTUATION)
                    index++
                }
                else -> index++
            }
        }
        return out
    }

    /**
     * The whole selection as one JSON document, for attaching to a bug report.
     * Bodies that are JSON are embedded as JSON, not as an escaped string.
     */
    fun export(traces: List<DebugTrace>): String = pretty.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("format", "androidharness-provider-log")
            put("version", 1)
            put("entries", buildJsonArray { traces.forEach { add(entry(it)) } })
        },
    )

    private fun entry(trace: DebugTrace): JsonElement = buildJsonObject {
        put("id", trace.id)
        put("startedAt", java.time.Instant.ofEpochMilli(trace.startedAt).toString())
        put("durationMs", trace.durationMs)
        put("provider", trace.providerName.ifBlank { trace.providerId })
        put("protocol", trace.protocol)
        put("model", trace.model)
        put("attempt", trace.attempt)
        put("keySlot", trace.keySlot + 1)
        trace.group?.let { put("turn", it) }
        trace.sessionId?.let { put("chat", it) }
        putJsonObject("request") {
            put("method", trace.method)
            put("url", trace.url)
            put("headers", headers(trace.requestHeaders))
            trace.requestBody?.let { put("body", body(it)) }
            if (trace.requestBodyElided) put("bodyElidedCharacters", trace.requestBodyOmitted)
        }
        putJsonObject("response") {
            trace.statusCode?.let { put("status", it) }
            trace.error?.let { put("error", it) }
            trace.providerTraceId?.let { put("providerRequestId", it) }
            put("headers", headers(trace.responseHeaders))
            (trace.errorBody ?: trace.responsePreview)?.let { put("body", body(it)) }
            if (trace.stream) {
                put("streamEvents", trace.streamEvents)
                trace.streamUsage?.let { put("usage", it) }
                trace.streamBody?.let { stream ->
                    put("stream", buildJsonArray { stream.lineSequence().filter { it.isNotBlank() }.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                }
            }
            if (trace.truncated) put("truncated", true)
        }
    }

    private fun headers(headers: List<Pair<String, String>>): JsonElement = buildJsonArray {
        headers.forEach { (name, value) -> add(buildJsonObject { put("name", name); put("value", value) }) }
    }

    private fun body(text: String): JsonElement =
        runCatching { strict.parseToJsonElement(text) }.getOrNull()
            ?.takeIf { it !is kotlinx.serialization.json.JsonPrimitive }
            ?: kotlinx.serialization.json.JsonPrimitive(text)
}
