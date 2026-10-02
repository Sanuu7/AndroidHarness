package com.androidharness.app.chatgpt

import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.llm.ModelEntry

/** Account metadata wins. Fallbacks are exact model families documented by OpenAI. */
object ChatGptThinking {
    private val wireNames = mapOf(
        ThinkingLevel.OFF to "none", ThinkingLevel.MINIMAL to "minimal", ThinkingLevel.LOW to "low",
        ThinkingLevel.MEDIUM to "medium", ThinkingLevel.HIGH to "high", ThinkingLevel.XHIGH to "xhigh",
        ThinkingLevel.MAX to "max", ThinkingLevel.ULTRA to "ultra",
    )
    private val standard = listOf("low", "medium", "high", "xhigh", "max")
    // https://developers.openai.com/api/docs/models/<model>
    private val documented = mapOf(
        "gpt-6.1-sol" to standard,
        "gpt-6-astra" to standard,
        "gpt-6-sol" to (listOf("none") + standard),
        "gpt-6-luna" to (listOf("none") + standard),
        "gpt-5.6-sol" to (listOf("none") + standard),
        "gpt-5.6-terra" to (listOf("none") + standard),
        "gpt-5.6-luna" to (listOf("none") + standard),
        "gpt-5.5" to listOf("none", "low", "medium", "high", "xhigh"),
        "gpt-5.4" to listOf("none", "low", "medium", "high", "xhigh"),
        "gpt-5.3-codex" to listOf("low", "medium", "high", "xhigh"),
        "gpt-5.2-codex" to listOf("low", "medium", "high", "xhigh"),
        "gpt-5.2" to listOf("none", "low", "medium", "high", "xhigh"),
        "gpt-5.1" to listOf("none", "low", "medium", "high"),
        "gpt-5" to listOf("minimal", "low", "medium", "high"),
    )

    fun levels(model: ModelEntry): List<ThinkingLevel> {
        if (model.reasoning == false) return emptyList()
        val efforts = model.reasoningEfforts ?: documented.entries.firstOrNull { (id, _) ->
            model.id == id || model.id.matches(Regex("${Regex.escape(id)}-\\d{4}-\\d{2}-\\d{2}"))
        }?.value ?: emptyList()
        return efforts.mapNotNull { value -> wireNames.entries.firstOrNull { it.value == value }?.key }.distinct()
    }

    fun selected(model: ModelEntry, requested: ThinkingLevel): ThinkingLevel? {
        val levels = levels(model)
        if (requested in levels) return requested
        if (requested == ThinkingLevel.OFF) return levels.firstOrNull { wireNames[it] == model.defaultReasoningEffort }
            ?: ThinkingLevel.MEDIUM.takeIf { it in levels } ?: levels.firstOrNull()
        return levels.filter { it.rank <= requested.rank }.maxByOrNull { it.rank } ?: levels.firstOrNull()
    }

    fun effort(model: ModelEntry, requested: ThinkingLevel): String? = selected(model, requested)?.let(wireNames::get)
}
