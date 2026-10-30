package com.androidharness.app.core.response

import kotlinx.serialization.json.*

/** Chat-only recovery for providers that omit the native-component fence. Code keeps strict parsing. */
internal object ChatRichResponse {
    private val header = Regex("""^\{\s*"type"\s*:\s*"(callout|card|metrics|chart|choices|steps|details|link|preview|table|tabs|gallery|profile|calculator|evidence|diff|writing|column|columns|text|divider)"""")
    private val componentTypes = setOf("callout", "card", "metrics", "chart", "choices", "steps", "details", "link", "preview", "table", "tabs", "gallery", "profile", "calculator", "evidence", "diff", "writing", "column", "columns", "text", "divider")
    private fun partialHeader(candidate: String): Boolean {
        val compact = candidate.filterNot(Char::isWhitespace)
        val prefix = "{\"type\":\""
        return prefix.startsWith(compact) || (compact.startsWith(prefix) && componentTypes.any { it.startsWith(compact.removePrefix(prefix)) })
    }

    fun normalize(text: String, streaming: Boolean = false): String {
        val directives = normalizeDirectives(text, streaming)
        if (directives != text) return normalize(directives, streaming)
        if ('{' !in text) return text
        val output = StringBuilder()
        var position = 0
        var fence: String? = null
        while (position < text.length) {
            val lineEnd = text.indexOf('\n', position).let { if (it < 0) text.length else it }
            val line = text.substring(position, lineEnd)
            val trimmed = line.trimStart()
            val marker = trimmed.takeWhile { it == '`' || it == '~' }
            if (marker.length >= 3) {
                if (fence == null) fence = marker
                else if (trimmed.trimEnd().all { it == fence.first() } && marker.length >= fence.length) fence = null
            }
            val start = position + line.length - trimmed.length
            val candidateHead = text.substring(start, minOf(text.length, start + 160))
            // A model may put `type` after other fields. Only recover a complete,
            // valid component in that case, so ordinary JSON stays literal.
            val reorderedEnd = if (fence == null && trimmed.startsWith('{') && !header.containsMatchIn(candidateHead))
                objectEnd(text, start) else null
            val reorderedBody = reorderedEnd?.let { text.substring(start, it) }
            if (reorderedBody != null && RichResponse.decode(reorderedBody) != null) {
                output.append("```harness-ui\n").append(reorderedBody).append("\n```\n")
                position = if (reorderedEnd < text.length && text[reorderedEnd] == '\n') reorderedEnd + 1 else reorderedEnd
                continue
            }
            if (fence == null && trimmed.startsWith('{') &&
                (header.containsMatchIn(candidateHead) || (streaming && partialHeader(candidateHead)))) {
                val end = objectEnd(text, start)
                if (end == null) {
                    // Withhold unfinished component data during streaming; never flash its JSON.
                    output.append("```harness-ui\n")
                    if (streaming) output.append(text.substring(start).take(RichResponse.MAX_JSON_CHARS))
                    else output.append("{}\n```\n")
                    break
                }
                val body = text.substring(start, end)
                if (RichResponse.decode(body) != null) output.append("```harness-ui\n").append(body).append("\n```\n")
                else output.append("```harness-ui\n{}\n```\n")
                position = if (end < text.length && text[end] == '\n') end + 1 else end
                continue
            }
            output.append(line)
            if (lineEnd < text.length) output.append('\n')
            position = lineEnd + 1
        }
        return output.toString()
    }

    /** Recover the line-oriented form some models emit instead of the requested JSON fence. */
    private fun normalizeDirectives(text: String, streaming: Boolean): String {
        if (!text.contains("::")) return text
        val lines = text.split('\n')
        val out = StringBuilder()
        var fence: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val marker = trimmed.takeWhile { it == '`' || it == '~' }
            if (marker.length >= 3) {
                if (fence == null) fence = marker
                else if (trimmed.all { it == fence.first() } && marker.length >= fence.length) fence = null
            }
            val head = if (fence == null) directiveHead.matchEntire(trimmed) else null
            if (head != null) {
                val (colons, type, attributes) = head.destructured
                val end = (i + 1 until lines.size).firstOrNull { lines[it].trim() == colons }
                if (end == null) {
                    if (streaming) out.append("```harness-ui\n") else out.append(lines.drop(i).joinToString("\n"))
                    break
                }
                val body = lines.subList(i + 1, end).joinToString("\n")
                val json = directiveJson(type, body, attributes)
                if (json != null && RichResponse.decode(json) != null) out.append("```harness-ui\n").append(json).append("\n```")
                else out.append("```harness-ui\n{}\n```")
                i = end + 1
                if (i < lines.size) out.append('\n')
                continue
            }
            out.append(line)
            if (++i < lines.size) out.append('\n')
        }
        return out.toString()
    }

    // `::callout`, `::callout{tone="info" title="Key takeaway"}`, or the same with `:::`.
    private val directiveHead = Regex("""^(:{2,3})(card|callout|profile|preview)(?:\{(.*)\})?$""")
    private val directiveAttribute = Regex("""([a-z_]+)\s*=\s*"((?:[^"\\]|\\.)*)"""")

    private fun directiveJson(type: String, body: String, attributes: String = ""): String? {
        if (body.length > RichResponse.MAX_JSON_CHARS) return null
        val allowed = when (type) {
            "profile" -> setOf("name", "subtitle", "description", "image", "image_source", "facts", "sources")
            "callout" -> setOf("title", "text", "tone")
            "preview" -> setOf("title", "html")
            else -> setOf("title", "text")
        }
        val fields = linkedMapOf<String, StringBuilder>()
        for (attribute in directiveAttribute.findAll(attributes)) {
            val key = attribute.groupValues[1]
            if (key !in allowed || key in fields) return null
            fields[key] = StringBuilder(attribute.groupValues[2].replace("\\\"", "\""))
        }
        val field = Regex("^\\s*([a-z_]+):\\s*(.*)$")
        val firstLine = body.lines().firstOrNull { it.isNotBlank() }
        // With attributes in the header, a card or callout body may be plain text rather than `key: value` lines.
        val plainText = type in setOf("card", "callout") && "text" !in fields && firstLine != null &&
            field.matchEntire(firstLine)?.groupValues?.get(1) !in allowed
        if (plainText) fields["text"] = StringBuilder(body)
        var current: StringBuilder? = null
        if (!plainText) for (line in body.lines()) {
            val match = field.matchEntire(line)
            if (match != null && match.groupValues[1] in allowed) {
                val key = match.groupValues[1]
                if (key in fields) return null
                current = StringBuilder(match.groupValues[2]); fields[key] = current
            } else if (current != null) current.append('\n').append(line) else if (line.isNotBlank()) return null
        }
        val obj = buildJsonObject {
            put("type", type)
            for ((key, value) in fields) {
                val raw = value.toString().trim()
                if (key == "facts" || key == "sources") {
                    val array = directiveArray(raw, if (key == "facts") setOf("label", "value", "source") else setOf("title", "url")) ?: return null
                    put(key, array)
                } else put(key, raw)
            }
        }
        return obj.toString()
    }

    private fun directiveArray(raw: String, keys: Set<String>): JsonArray? {
        (runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray)?.let { return it }
        if (!raw.startsWith("[") || !raw.endsWith("]")) return null
        val inner = raw.substring(1, raw.length - 1).trim()
        if (inner.isEmpty()) return JsonArray(emptyList())
        val entries = inner.split(Regex("}\\s*,\\s*\\{"))
        if (entries.size > 12) return null
        val result = mutableListOf<JsonElement>()
        for ((index, entry) in entries.withIndex()) {
            val item = entry.removePrefix(if (index == 0) "{" else "").removeSuffix(if (index == entries.lastIndex) "}" else "")
            val matches = Regex("(?:^|,)\\s*(${keys.joinToString("|")}):").findAll(item).toList()
            if (matches.isEmpty()) return null
            val map = linkedMapOf<String, String>()
            for ((n, match) in matches.withIndex()) {
                val key = match.groupValues[1]
                if (key in map) return null
                map[key] = item.substring(match.range.last + 1, matches.getOrNull(n + 1)?.range?.first ?: item.length).trim()
            }
            result += buildJsonObject { map.forEach { (key, value) -> put(key, value) } }
        }
        return JsonArray(result)
    }

    private fun objectEnd(text: String, start: Int): Int? {
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until minOf(text.length, start + RichResponse.MAX_JSON_CHARS + 1)) {
            val c = text[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> if (--depth == 0) return index + 1
            }
        }
        return null
    }
}
