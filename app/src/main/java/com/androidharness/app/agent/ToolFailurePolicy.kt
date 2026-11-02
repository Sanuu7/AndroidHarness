package com.androidharness.app.agent

import com.androidharness.app.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Files, dependencies and backends may change between identical calls. */
internal fun immutableToolFailureHint(result: ToolResult): String? {
    if (result.ok) return null
    val text = result.output.trimStart()
    return if (listOf("Missing required argument:", "Invalid tool arguments:", "Unknown tool:").any {
        text.startsWith(it, ignoreCase = true)
    }) "Fix the tool name or arguments before retrying." else null
}

/**
 * A model that keeps sending `{}` to a tool with required parameters copies that
 * from its own history. Prefixed to the tool's own failure so the model learns which
 * fields to send; some tools accept `{}` despite their schema, so this never blocks.
 */
internal fun emptyArgumentsHint(toolName: String, argumentsJson: String, schema: JsonObject): String? {
    val args = argumentsJson.trim()
    val empty = args.isEmpty() || runCatching { (Json.parseToJsonElement(args) as? JsonObject)?.isEmpty() }.getOrNull() == true
    if (!empty) return null
    val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
    if (required.isNullOrEmpty()) return null
    return "$toolName received empty arguments ({}). " +
        "Put the values inside the call's arguments object, for example {\"${required.first()}\": ...}. " +
        "Required: ${required.joinToString(", ")}."
}
