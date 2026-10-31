package com.androidharness.app.config

import kotlinx.serialization.json.*

/** Merged values keep their source, so relative paths never change meaning between layers. */
data class ConfigValue(val json: JsonElement, val source: String, val offset: Int = 0,
    val fields: Map<String, ConfigValue> = emptyMap(), val items: List<ConfigValue> = emptyList()) {
    operator fun get(key: String): ConfigValue? = fields[key]?.takeUnless { it.json is JsonNull }
    fun string(): String? = (json as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun strings(): List<String> = items.mapNotNull { it.string() }
}

class HarnessConfigException(val document: String, val line: Int, val column: Int, detail: String) :
    IllegalArgumentException("$document:$line:$column: $detail")

/** Bounded lexical JSON/JSONC parser; quoted URLs, escapes and comment markers stay intact. */
object ConfigDocument {
    const val MAX_BYTES = 256 * 1024
    fun parse(text: String, source: String, jsonc: Boolean = source.endsWith(".jsonc")): ConfigValue {
        if (text.toByteArray().size > MAX_BYTES) throw HarnessConfigException(source, 1, 1, "Configuration exceeds 256 KiB.")
        return Reader(text.removePrefix("\uFEFF"), source, jsonc).read()
    }
    fun merge(earlier: ConfigValue?, later: ConfigValue): ConfigValue {
        if (earlier == null || later.json is JsonNull) return later
        if (earlier.json is JsonObject && later.json is JsonObject) {
            val fields = earlier.fields.toMutableMap()
            later.fields.forEach { (key, value) -> fields[key] = merge(fields[key], value) }
            return later.copy(json = JsonObject(fields.mapValues { it.value.json }), fields = fields)
        }
        if (earlier.json is JsonArray && later.json is JsonArray) {
            val items = earlier.items + later.items
            return later.copy(json = JsonArray(items.map { it.json }), items = items)
        }
        return later
    }
    private class Reader(val text: String, val source: String, val comments: Boolean) {
        var p = 0
        fun fail(message: String, at: Int = p): Nothing {
            val prefix = text.take(at)
            throw HarnessConfigException(source, prefix.count { it == '\n' } + 1,
                at - (prefix.lastIndexOf('\n') + 1) + 1, message)
        }
        fun skip() {
            while (p < text.length) {
                if (text[p] in " \t\r\n") { p++; continue }
                if (comments && text.startsWith("//", p)) {
                    while (p < text.length && text[p] != '\n') p++
                } else if (comments && text.startsWith("/*", p)) {
                    val end = text.indexOf("*/", p + 2)
                    if (end < 0) fail("Unclosed comment.")
                    p = end + 2
                } else return
            }
        }
        fun read(): ConfigValue {
            skip(); val result = value(0); skip()
            if (p != text.length) fail("Unexpected content after configuration.")
            if (result.json !is JsonObject) fail("Configuration must be an object.", 0)
            return result
        }
        fun quoted(): String {
            val start = p++
            var escape = false
            while (p < text.length) {
                val c = text[p++]
                if (c < ' ') fail("Unescaped control character in string.", p - 1)
                if (!escape && c == '"') return try {
                    Json.parseToJsonElement(text.substring(start, p)).jsonPrimitive.content
                } catch (_: Exception) { fail("Invalid string escape.", start) }
                escape = !escape && c == '\\'
            }
            fail("Unclosed string.", start)
        }
        fun value(depth: Int): ConfigValue {
            if (depth > 32) fail("Configuration nesting exceeds 32 levels.")
            skip(); val start = p
            if (p >= text.length) fail("Expected a value.")
            return when (text[p]) {
                '{' -> {
                    p++; skip(); val fields = linkedMapOf<String, ConfigValue>()
                    while (p < text.length && text[p] != '}') {
                        if (text[p] != '"') fail("Expected a quoted property name.")
                        val keyAt = p; val key = quoted()
                        if (key in fields) fail("Duplicate property '$key'.", keyAt)
                        skip()
                        if (p >= text.length || text[p++] != ':') fail("Expected ':' after property.")
                        fields[key] = value(depth + 1); skip()
                        if (p < text.length && text[p] == '}') break
                        if (p >= text.length || text[p++] != ',') fail("Expected ',' or '}'.")
                        skip()
                        if (!comments && p < text.length && text[p] == '}') fail("Trailing commas require .jsonc.")
                    }
                    if (p >= text.length) fail("Unclosed object.", start)
                    p++; ConfigValue(JsonObject(fields.mapValues { it.value.json }), source, start, fields = fields)
                }
                '[' -> {
                    p++; skip(); val items = mutableListOf<ConfigValue>()
                    while (p < text.length && text[p] != ']') {
                        items += value(depth + 1)
                        if (items.size > 1024) fail("An array may contain at most 1024 entries.")
                        skip()
                        if (p < text.length && text[p] == ']') break
                        if (p >= text.length || text[p++] != ',') fail("Expected ',' or ']'.")
                        skip()
                        if (!comments && p < text.length && text[p] == ']') fail("Trailing commas require .jsonc.")
                    }
                    if (p >= text.length) fail("Unclosed array.", start)
                    p++; ConfigValue(JsonArray(items.map { it.json }), source, start, items = items)
                }
                '"' -> ConfigValue(JsonPrimitive(quoted()), source, start)
                else -> {
                    while (p < text.length && text[p] !in ",]} \t\r\n/") p++
                    if (p == start) fail("Expected a JSON value.")
                    val token = text.substring(start, p)
                    if (token !in listOf("true", "false", "null") &&
                        !Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(token)) fail("Invalid JSON value.", start)
                    ConfigValue(Json.parseToJsonElement(token), source, start)
                }
            }
        }
    }
}

object ConfigGlob {
    fun matches(pattern: String, path: String): Boolean = Regex(buildString {
        append('^'); var i = 0; val clean = pattern.removePrefix("./")
        while (i < clean.length) {
            when {
                clean.startsWith("**/", i) -> { append("(?:.*/)?"); i += 3 }
                clean.startsWith("**", i) -> { append(".*"); i += 2 }
                clean[i] == '*' -> { append("[^/]*"); i++ }
                clean[i] == '?' -> { append("[^/]"); i++ }
                else -> { append(Regex.escape(clean[i].toString())); i++ }
            }
        }
        append('$')
    }).matches(path.removePrefix("./"))
}
