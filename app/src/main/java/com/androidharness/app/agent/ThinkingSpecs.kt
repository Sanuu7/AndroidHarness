package com.androidharness.app.agent

import kotlinx.coroutines.flow.firstOrNull

/**
 * Per-model thinking capability. Primary source is the models.dev community
 * catalog ([com.androidharness.app.llm.ModelsDev]), which uniquely enumerates
 * each model's effort vocabulary; when it's unloaded or silent about a model,
 * the curated family table below decides — the same fallback approach desktop
 * harnesses take. The spec drives both the UI (which chips render) and the
 * request layer (exact wire value).
 */
object ThinkingSpecs {

    /** How a model consumes thinking configuration. */
    enum class Style {
        /** Thinks on its own / takes no parameter — nothing sent on the wire. */
        NONE,

        /** OpenAI-style `reasoning_effort` enum ("low"/"medium"/"high"/"xhigh"). */
        EFFORT,

        /** Explicit token budget (Anthropic `budget_tokens`, Gemini `thinkingBudget`). */
        BUDGET,
    }

    data class Spec(
        val style: Style,
        /** Levels the UI may offer, in ascending order. Always contains OFF. */
        val levels: List<ThinkingLevel>,
    )

    private val ALL = ThinkingLevel.entries

    /** Ordered family rules; first match wins. */
    private val rules: List<Pair<Regex, Spec>> = listOf(
        Regex("(^|/)gpt-5") to
            Spec(Style.EFFORT, listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH)),

        Regex("(^|/)o[3-9]([-.]|$)") to
            Spec(Style.EFFORT, listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH)),

        Regex("(^|/)grok-[34]") to
            Spec(Style.EFFORT, listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.HIGH)),

        Regex("claude") to
            Spec(Style.BUDGET, ALL),

        Regex("gemini-[23]") to
            Spec(Style.BUDGET, ALL),

        // DeepSeek reasoners think inherently — there is no dial to send.
        Regex("deepseek") to
            Spec(Style.NONE, listOf(ThinkingLevel.OFF, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)),

        Regex("gpt-oss") to
            Spec(Style.EFFORT, listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)),

        Regex("qwen3|glm-[45]|kimi|minimax-m2|nemotron|hy3") to
            Spec(Style.NONE, listOf(ThinkingLevel.OFF, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)),
    )

    private val defaultSpec = Spec(Style.BUDGET, ALL)

    /**
     * What goes inside OpenRouter's unified `reasoning` object: an effort
     * string, a bare enabled toggle, or (null) nothing.
     */
    data class RouterReasoning(val effort: String? = null, val enabled: Boolean? = null)

    fun forModel(modelId: String?, devKey: String? = null): Spec {
        if (!modelId.isNullOrBlank()) {
            dynamicSpec(modelId, devKey)?.let { return it }
            return rules.firstOrNull { (regex, _) -> regex.containsMatchIn(modelId.lowercase()) }?.second
                ?: defaultSpec
        }
        return defaultSpec
    }

    /**
     * models.dev override: freshly-maintained per-model vocabularies beat the
     * shipped family table. Returns null when the catalog has no dial info
     * for this model (toggle-only or unlisted) so the shipped table decides.
     */
    private fun dynamicSpec(modelId: String?, devKey: String?): Spec? {
        val entry = com.androidharness.app.llm.ModelsDev.entry(devKey, modelId) ?: return null
        if (entry.reasoning == false) return Spec(Style.NONE, listOf(ThinkingLevel.OFF))
        val values = entry.effortValues
        if (!values.isNullOrEmpty()) {
            val levels = ThinkingLevel.entries.filter { level ->
                level == ThinkingLevel.OFF || candidates(level).any { it in values }
            }
            return Spec(Style.EFFORT, levels)
        }
        if (entry.budgetTokens) return Spec(Style.BUDGET, ALL)
        return null
    }

    /**
     * Tiers the UI should render for this model: only its native ones. MAX is
     * appended for EFFORT families missing it — as the "highest tier"
     * sentinel it always has something safe to fold onto. NONE-style inherent
     * reasoners keep exactly their shipped list (their tiers are cosmetic —
     * no dial is ever sent).
     */
    fun visibleLevels(modelId: String?, devKey: String? = null): List<ThinkingLevel> {
        val spec = forModel(modelId, devKey)
        if (spec.style == Style.EFFORT &&
            ThinkingLevel.MAX !in spec.levels &&
            spec.levels.any { it != ThinkingLevel.OFF }
        ) {
            return spec.levels + ThinkingLevel.MAX
        }
        return spec.levels
    }

    /**
     * After a model switch the stored thinking tier may not exist on the new
     * model — adapt it to the closest native tier (smallest ≥ current, else
     * the model's top). Keeps UI chips and wire values honest without user
     * intervention. No-op when the level is already supported.
     */
    suspend fun clampStoredLevel(
        settings: com.androidharness.app.data.SettingsRepository,
        modelId: String?,
        devKey: String?,
    ) {
        val visible = visibleLevels(modelId, devKey)
        val current = settings.settings.firstOrNull()?.thinkingLevel
        if (visible.isEmpty() || current == null || current in visible) return
        val usable = visible.filter { it != ThinkingLevel.OFF }
        val clamped = when {
            usable.isEmpty() -> ThinkingLevel.OFF
            current == ThinkingLevel.MAX -> usable.last()
            else -> usable.firstOrNull { it >= current } ?: usable.last()
        }
        settings.setThinkingLevel(clamped)
    }

    /** Wiring preference order per level, used to compute which tiers a model natively speaks. */
    private fun candidates(level: ThinkingLevel): List<String> = when (level) {        ThinkingLevel.OFF -> emptyList()
        ThinkingLevel.LOW -> listOf("low", "minimal")
        ThinkingLevel.MEDIUM -> listOf("medium", "low", "minimal")
        ThinkingLevel.HIGH -> listOf("high", "medium")
        ThinkingLevel.XHIGH -> listOf("xhigh", "high")
        ThinkingLevel.MAX -> listOf("max", "xhigh", "high")
    }

    private fun tierRank(tier: String): Int = when (tier) {
        "none" -> 0
        "minimal" -> 1
        "low" -> 2
        "medium" -> 3
        "high" -> 4
        "xhigh" -> 5
        "max" -> 6
        else -> -1
    }

    private fun tierRank(level: ThinkingLevel): Int = when (level) {
        ThinkingLevel.OFF -> 0
        ThinkingLevel.LOW -> 2
        ThinkingLevel.MEDIUM -> 3
        ThinkingLevel.HIGH -> 4
        ThinkingLevel.XHIGH -> 5
        ThinkingLevel.MAX -> 6
    }

    /** Closest tier the model actually enumerates (never invents a value). */
    private fun nearestEffort(values: List<String>, level: ThinkingLevel): String? =
        values.minByOrNull { kotlin.math.abs(tierRank(it) - tierRank(level)) }

    /**
     * Exact `reasoning_effort` string for [level] on [modelId], or null when
     * this model/style takes no effort parameter (or the level is OFF). The
     * models.dev vocabulary ([devKey]) wins over the shipped family table.
     */
    fun effortWire(modelId: String?, level: ThinkingLevel, devKey: String? = null): String? {
        if (level == ThinkingLevel.OFF) return null
        val dyn = com.androidharness.app.llm.ModelsDev.entry(devKey, modelId)
        if (dyn?.reasoning == false) return null
        dyn?.effortValues?.takeIf { it.isNotEmpty() }?.let { values ->
            return nearestEffort(values, level)
        }
        val spec = forModel(modelId)
        if (spec.style != Style.EFFORT) return null

        // Max is a sentinel for whatever the family's highest effort is.
        if (level == ThinkingLevel.MAX) {
            return when {
                ThinkingLevel.XHIGH in spec.levels -> "xhigh"
                ThinkingLevel.HIGH in spec.levels -> "high"
                else -> null
            }
        }
        if (level !in spec.levels) return null
        return when (level) {
            ThinkingLevel.LOW -> "low"
            ThinkingLevel.MEDIUM -> "medium"
            ThinkingLevel.HIGH -> "high"
            ThinkingLevel.XHIGH -> "xhigh"
            ThinkingLevel.OFF, ThinkingLevel.MAX -> null
        }
    }

    /**
     * What to put in OpenRouter's `reasoning` object for [modelId] at
     * [level]. The models.dev vocabulary wins: exact effort strings from the
     * model's own list (never invented), a bare `enabled` toggle when that's
     * the only dial the model has, null for non-reasoners. Unknown models
     * fall back to the shipped table via [openRouterEffort].
     */
    fun openRouterReasoning(modelId: String?, level: ThinkingLevel): RouterReasoning? {
        if (level == ThinkingLevel.OFF) return null
        val dyn = com.androidharness.app.llm.ModelsDev.entry("openrouter", modelId)
        if (dyn?.reasoning == false) return null
        if (dyn != null) {
            val values = dyn.effortValues
            if (!values.isNullOrEmpty()) {
                return RouterReasoning(effort = nearestEffort(values, level))
            }
            if (dyn.toggle) return RouterReasoning(enabled = true)
            // Reported as reasoning-capable but no dial vocabulary —
            // let the shipped table try below.
        }
        return openRouterEffort(modelId, level)?.let { RouterReasoning(effort = it) }
    }

    /**
     * Effort value for OpenRouter's unified `reasoning` object, which the
     * gateway normalizes across every provider it fronts. EFFORT families get
     * their exact wire value (including "xhigh"); BUDGET families — Claude and
     * Gemini reached through the OpenAI-compatible API, whose native budget
     * parameters only exist on their own protocols — clamp to the standard
     * low/medium/high vocabulary; NONE-style inherent reasoners (DeepSeek)
     * get null: there is no dial, and they already think by default.
     */
    fun openRouterEffort(modelId: String?, level: ThinkingLevel): String? {
        if (level == ThinkingLevel.OFF) return null
        val spec = forModel(modelId)
        return when (spec.style) {
            // Exact wire value when native, else clamp to the closest tier the
            // family actually speaks (e.g. Medium on Grok becomes High).
            Style.EFFORT -> effortWire(modelId, level)
                ?: clampToNative(spec, level)?.let { effortWire(modelId, it) }
            Style.BUDGET -> when (level) {
                ThinkingLevel.LOW -> "low"
                ThinkingLevel.MEDIUM -> "medium"
                ThinkingLevel.OFF, ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX -> "high"
            }
            Style.NONE -> null
        }
    }

    /** Closest native tier to [level]: smallest tier ≥ level, else the family's top. */
    private fun clampToNative(spec: Spec, level: ThinkingLevel): ThinkingLevel? {
        val usable = spec.levels.filter { it != ThinkingLevel.OFF }
        if (usable.isEmpty()) return null
        if (level == ThinkingLevel.MAX) return usable.last()
        return usable.firstOrNull { it >= level } ?: usable.last()
    }
}
