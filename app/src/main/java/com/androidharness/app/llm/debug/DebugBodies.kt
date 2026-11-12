package com.androidharness.app.llm.debug

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shrinks a captured body without turning it into garbage.
 *
 * A hard `substring` cut used to leave the request body half a JSON document:
 * nothing could be replayed and the messages array was usually the part that
 * fell off the end. Here a JSON body is parsed and only its long string leaves
 * are shortened, so the result still parses, still shows the messages, tools and
 * parameters, and only the bulky prose carries an omission marker.
 *
 * [Body.elided] tells the reader they are looking at a representation, not the
 * exact bytes that went on the wire.
 */
object DebugBodies {

    data class Body(
        val text: String,
        val elided: Boolean = false,
        /** Characters dropped from string leaves, for the "elided" note. */
        val omitted: Int = 0,
        /** False when the body was not JSON and had to be cut head and tail. */
        val validJson: Boolean = true,
    )

    private val json = Json { }

    /** Longest single string leaf before it is shortened on the first pass. */
    private val PASSES = listOf(1_200 to 12, 400 to 6, 120 to 2)

    fun shrink(raw: String, limit: Int): Body {
        if (raw.length <= limit) return Body(raw)
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: return cutText(raw, limit)
        for ((stringLimit, arrayKeep) in PASSES) {
            var omitted = 0
            val node = walk(root, stringLimit, arrayKeep) { omitted += it }
            val text = runCatching { json.encodeToString(JsonElement.serializer(), node) }.getOrNull() ?: continue
            if (text.length <= limit) return Body(text, elided = omitted > 0, omitted = omitted)
        }
        // Pathological body (thousands of short strings): keep it legal by keeping
        // only the last few messages, which is what a reader needs anyway.
        var omitted = 0
        val node = walk(root, 40, 1) { omitted += it }
        val text = runCatching { json.encodeToString(JsonElement.serializer(), node) }.getOrNull()
            ?: return cutText(raw, limit)
        return Body(text, elided = true, omitted = omitted)
    }

    private fun walk(
        element: JsonElement,
        stringLimit: Int,
        arrayKeep: Int,
        onOmit: (Int) -> Unit,
    ): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> walk(value, stringLimit, arrayKeep, onOmit) })
        is JsonArray -> {
            val kept = if (element.size <= arrayKeep) element else {
                // Keep the head (setup) and the newest tail (the actual question).
                val head = arrayKeep / 2
                val tail = element.size - (arrayKeep - head)
                val dropped = tail - head
                onOmit(dropped)
                element.filterIndexed { index, _ -> index < head || index >= tail }
            }
            JsonArray(kept.map { walk(it, stringLimit, arrayKeep, onOmit) })
        }
        is JsonPrimitive -> {
            val value = element.content
            if (element.isString && value.length > stringLimit) {
                val cut = value.length - stringLimit
                onOmit(cut)
                JsonPrimitive(value.take(stringLimit) + "…[$cut characters omitted]")
            } else {
                element
            }
        }
        else -> element
    }

    /** Non-JSON bodies (multipart, form data): cut head and tail so the marker is visible. */
    private fun cutText(raw: String, limit: Int): Body {
        val head = maxOf(limit / 2, 10)
        val tail = maxOf(limit - head - MARKER_RESERVE, 10)
        val omitted = raw.length - head - tail
        if (omitted <= 0) return Body(raw)
        return Body(
            raw.take(head) + "\n…[$omitted characters omitted]…\n" + raw.takeLast(tail),
            elided = true,
            omitted = omitted,
            validJson = false,
        )
    }

    private const val MARKER_RESERVE = 40
}
