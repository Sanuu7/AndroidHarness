package com.androidharness.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads the `options` and `recommended` arguments of ask_user.
 *
 * Models deliver them in every shape imaginable: ["a","b"], [{"label":"a"}],
 * {"1":"a","2":"b"}, "a | b", "a, b, c", or JSON double-encoded as a string.
 * Nothing here throws; odd input is salvaged or dropped.
 *
 * One rule matters most: an item of a real list is already one option and is
 * never split again. Only a bare string that stands for the whole list is
 * split, because that is the one place the model had no way to separate items.
 * Otherwise an option such as "Pizza, with cheese, and basil" turns into three.
 */
internal object AskUserOptions {
    private const val MAX_SPLIT_OPTIONS = 12
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(element: JsonElement?): List<String> {
        if (element == null || element is JsonNull) return emptyList()
        return runCatching { read(element, depth = 0, wholeList = true) }.getOrDefault(emptyList())
    }

    /** [wholeList] is true while [element] stands for the entire list rather than one item of it. */
    private fun read(element: JsonElement, depth: Int, wholeList: Boolean): List<String> {
        if (depth > 3) return emptyList()
        return when (element) {
            is JsonNull -> emptyList()
            is JsonArray -> element.flatMap { read(it, depth + 1, wholeList = false) }
            is JsonPrimitive -> {
                val content = element.contentOrNull?.trim().orEmpty()
                when {
                    content.isEmpty() -> emptyList()
                    // JSON or string-encoded JSON: reparse, fall back to raw text.
                    content.startsWith("[") || content.startsWith("{") -> {
                        val nested = runCatching { json.parseToJsonElement(content) }.getOrNull()
                        if (nested != null) read(nested, depth + 1, wholeList) else listOf(content)
                    }
                    wholeList -> splitText(content)
                    else -> listOf(content)
                }
            }

            is JsonObject -> {
                // One option described as an object: take its display text.
                val label = listOf(
                    "option", "label", "text", "value", "title", "name",
                    "answer", "choice", "description",
                ).firstNotNullOfOrNull { key ->
                    (element[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                }
                if (label != null) return listOf(label.trim())
                // Wrapper objects: {"options": [...]} / {"choices": [...]}
                val wrapper = element["options"] ?: element["choices"] ?: element["items"]
                if (wrapper != null) return read(wrapper, depth + 1, wholeList)
                // Map form: {"1": "a", "2": "b"}, the values are the options.
                val values = element.values.mapNotNull { v ->
                    (v as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                }
                if (values.size >= 2) values else emptyList()
            }
        }
    }

    /**
     * Splits a bare string that holds the whole list. A pipe or a line break is a
     * deliberate separator, so commas inside the pieces stay. Commas separate
     * options only when nothing else does, and only for three or more pieces, so
     * "Yes, definitely" stays one option.
     */
    private fun splitText(text: String): List<String> {
        val parts = if (text.contains('|') || text.contains('\n')) {
            text.split('|', '\n')
        } else {
            text.split(", ").takeIf { it.size >= 3 } ?: listOf(text)
        }
        return parts.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_SPLIT_OPTIONS)
    }
}
