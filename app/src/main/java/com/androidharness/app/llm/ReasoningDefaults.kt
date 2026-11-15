package com.androidharness.app.llm

import com.androidharness.app.agent.CustomReasoning
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.agent.ThinkingSpecs
import kotlinx.serialization.json.*

/** Inherited model rules override protocol defaults. Custom level names never consult a preset allowlist. */
object ReasoningDefaults {
    fun isLocal(config: ProviderConfig): Boolean = config.baseUrl.startsWith("local://") ||
        com.androidharness.app.local.LocalModelCatalog.isLocal(config.id) ||
        OpenAiCompatProvider.isLocalHost(config.baseUrl)

    fun effective(custom: com.androidharness.app.agent.CustomReasoningConfig, config: ProviderConfig,
        spec: ThinkingSpecs.Spec): String? = custom.mapping.trim().takeIf { it.isNotEmpty() } ?: inherited(config, spec)

    fun inherited(rawConfig: ProviderConfig, spec: ThinkingSpecs.Spec): String? {
        val config = ThinkingSpecs.routedConfig(rawConfig)
        if (isLocal(config)) return null
        val router = ThinkingSpecs.usesRouterReasoning(config)
        val disabled = "reasoningLevel == \"disabled\""
        val managed = "reasoningLevel in [\"on\", \"enabled\"]"
        val budget = budgetExpression(spec)
        if (router) return when (spec.style) {
            ThinkingSpecs.Style.BUDGET -> "$disabled ? dyn({\"reasoning\": {\"enabled\": false}}) : {\"reasoning\": {\"max_tokens\": $budget}}"
            ThinkingSpecs.Style.TOGGLE -> "{\"reasoning\": {\"enabled\": !($disabled)}}"
            else -> "$managed ? {\"reasoning\": {\"enabled\": true}} : {\"reasoning\": {\"effort\": $disabled ? \"none\" : reasoningLevel}}"
        }
        return when (config.type) {
            ProviderType.OPENAI_COMPAT -> {
                val toggle = spec.toggleField
                if (toggle != null) {
                    val key = JsonPrimitive(toggle).toString()
                    val extras = when {
                        ModelsDev.providerKeyFor(config.baseUrl)?.substringBefore("-coding-plan") in setOf("zhipuai", "zai") -> ", \"clear_thinking\": false"
                        ModelsDev.providerKeyFor(config.baseUrl) in setOf("moonshotai", "moonshotai-cn") && config.model.startsWith("kimi-k2.6") -> ", \"keep\": \"all\""
                        else -> ""
                    }
                    val on = "{$key: {\"type\": \"enabled\"$extras}}"
                    val off = "{$key: {\"type\": \"disabled\"}}"
                    if (spec.style == ThinkingSpecs.Style.TOGGLE) "$disabled ? $off : $on"
                    else "$disabled ? $off : $managed ? $on : {$key: {\"type\": \"enabled\"$extras}, \"reasoning_effort\": reasoningLevel}"
                } else if (spec.style == ThinkingSpecs.Style.TOGGLE) null
                else "$managed ? {} : {\"reasoning_effort\": $disabled ? \"none\" : reasoningLevel}"
            }
            ProviderType.OPENAI_RESPONSES -> "$managed ? {} : $disabled ? {\"reasoning\": {\"effort\": \"none\"}} : {\"reasoning\": {\"effort\": reasoningLevel, \"summary\": \"auto\"}, \"include\": [\"reasoning.encrypted_content\"]}"
            ProviderType.ANTHROPIC -> when (spec.style) {
                ThinkingSpecs.Style.ADAPTIVE, ThinkingSpecs.Style.EFFORT -> {
                    val deepseek = ModelsDev.providerKeyFor(config.baseUrl) == "deepseek"
                    val type = if (deepseek) "enabled" else "adaptive"
                    val display = if (config.model.startsWith("claude-")) ", \"display\": \"summarized\"" else ""
                    val on = "{\"thinking\": {\"type\": \"$type\"$display}}"
                    "$disabled ? {\"thinking\": {\"type\": \"disabled\"}} : $managed ? $on : {\"thinking\": {\"type\": \"$type\"$display}, \"output_config\": {\"effort\": reasoningLevel}}"
                }
                ThinkingSpecs.Style.BUDGET -> "$disabled ? {\"thinking\": {\"type\": \"disabled\"}} : {\"thinking\": {\"type\": \"enabled\", \"budget_tokens\": $budget}}"
                else -> null
            }
            ProviderType.GEMINI -> if (spec.style == ThinkingSpecs.Style.BUDGET)
                "{\"generationConfig\": {\"thinkingConfig\": {\"thinkingBudget\": $budget, \"includeThoughts\": !($disabled)}}}"
            else "$managed ? {} : {\"generationConfig\": {\"thinkingConfig\": {\"thinkingLevel\": reasoningLevel, \"includeThoughts\": true}}}"
        }
    }

    fun budgetFor(spec: ThinkingSpecs.Spec, name: String): Int? {
        val level = CustomReasoning.native(name) ?: return null
        if (level == ThinkingLevel.OFF) return 0
        val tokens = when (level) {
            ThinkingLevel.MAX, ThinkingLevel.ULTRA -> spec.budgetMax
            ThinkingLevel.MINIMAL -> spec.budgetMin
            else -> level.budgetTokens(0)
        }
        return tokens.coerceIn(spec.budgetMin, spec.budgetMax)
    }

    private fun budgetExpression(spec: ThinkingSpecs.Spec): String = buildJsonObject {
        (ThinkingLevel.entries.map { CustomReasoning.wire(it.label) } + listOf("on", "enabled")).forEach { name ->
            budgetFor(spec, name)?.let { put(name, it) }
        }
    }.toString() + "[reasoningLevel]"
}
