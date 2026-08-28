package com.androidharness.app.agent

import kotlinx.coroutines.flow.firstOrNull

/**
 * Per-model thinking capability, with a Hermes-style resolution policy:
 *
 * Every surface (UI chips, stored setting, wire calls) speaks the ONE global
 * ladder [ThinkingLevel]. What a given model accepts is DATA here, style +
 * supported levels, and exactly one function, [resolveLevel], applies it:
 * a requested rung passes through verbatim when supported, otherwise it takes
 * the NEAREST WEAKER supported rung (a clamp never silently escalates cost),
 * and only when nothing weaker exists does it take the weakest level. "none"
 * is never a degradation target: an enabled ask stays enabled.
 */
object ThinkingSpecs {

    /** How a model consumes thinking configuration. */
    enum class Style {
        /** Thinks on its own / takes no parameter, nothing sent on the wire. */
        NONE,

        /** OpenAI-style `reasoning_effort` enum ("low"/"medium"/"high"/"xhigh"). */
        EFFORT,

        /** Explicit token budget (Anthropic `budget_tokens`, Gemini `thinkingBudget`). */
        BUDGET,
    }

    data class Spec(
        val style: Style,
        /** Rungs this model accepts natively, ascending. Always contains OFF. */
        val levels: List<ThinkingLevel>,
    )

    private val ALL = ThinkingLevel.entries

    /** Ordered family rules; first match wins. */
    private val rules: List<Pair<Regex, Spec>> = listOf(
        Regex("(^|/)gpt-5") to
            Spec(Style.EFFORT, listOf(ThinkingLevel.OFF, ThinkingLevel.MINIMAL, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH)),

        Regex("(^|/)o[3-9]([-.]|$)") to
            Spec(Style.EFFORT, listOf(ThinkingLevel.OFF, ThinkingLevel.MINIMAL, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH)),

        Regex("(^|/)grok-[34]") to
            Spec(Style.EFFORT, listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.HIGH)),

        Regex("claude") to
            Spec(Style.BUDGET, ALL),

        Regex("gemini-[23]") to
            Spec(Style.BUDGET, ALL),

        // DeepSeek reasoners think inherently, there is no dial to send.
        Regex("deepseek") to
            Spec(Style.NONE, listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)),

        Regex("gpt-oss") to
            Spec(Style.EFFORT, listOf(ThinkingLevel.OFF, ThinkingLevel.MINIMAL, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)),

        Regex("qwen3|glm-[45]|kimi|minimax-m2|nemotron|hy3") to
            Spec(Style.NONE, listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)),
    )

    private val defaultSpec = Spec(Style.BUDGET, ALL)

    /**
     * What goes inside OpenRouter's unified `reasoning` object: an effort
     * string, a bare enabled toggle, or (null) nothing.
     */
    data class RouterReasoning(val effort: String? = null, val enabled: Boolean? = null)

    /**
     * One clamping policy for the whole app: keep a native rung verbatim,
     * otherwise take the nearest WEAKER native rung (never escalates cost),
     * falling back to the weakest native rung when nothing weaker exists.
     * Models with NO reasoning capability at all resolve every ask to OFF,
     * the only honest tier for them. Unknown/custom vocabularies pass through
     * rather than being guessed.
     */
    fun resolveLevel(level: ThinkingLevel?, spec: Spec?): ThinkingLevel? {
        if (level == null || spec == null) return level
        val enabled = spec.levels.filter { it != ThinkingLevel.OFF }
        if (level == ThinkingLevel.OFF || enabled.isEmpty()) {
            return if (enabled.isEmpty()) ThinkingLevel.OFF else level
        }
        if (level in enabled) return level
        // Nearest weaker first; "floor" fallback keeps an enabled ask enabled.
        return enabled.lastOrNull { it < level } ?: enabled.first()
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
                level == ThinkingLevel.OFF ||
                    values.any { v -> tierRank(v) == level.rank }
            }
            return Spec(Style.EFFORT, levels)
        }
        if (entry.budgetTokens) return Spec(Style.BUDGET, ALL)
        return null
    }

    /**
     * Tiers advertised across ALL surfaces: the FULL global ladder, for EVERY
     * model, exactly like Hermes, where /reasoning offers none..ultra
     * regardless of endpoint. Selecting a rung the model doesn't natively
     * speak resolves DOWN the chain (ultra -> max -> xhigh -> high -> …)
     * until it lands on the closest supported tier via [setClamped], so the
     * stored value and the wire call are always honest. The ladder itself
     * never shrinks per model.
     */
    fun visibleLevels(modelId: String?, devKey: String? = null): List<ThinkingLevel> =
        ThinkingLevel.entries.toList()

    /**
     * After a model switch the stored tier may not exist on the new model,
     * but Hermes-style the STORED value is never rewritten: [resolveLevel]
     * re-applies per request, so the same pick adapts to every model.
     * Kept as a no-op shim for existing call sites.
     */
    suspend fun clampStoredLevel(
        settings: com.androidharness.app.data.SettingsRepository,
        modelId: String?,
        devKey: String?,
    ) = Unit

    /**
     * Persists [requested] VERBATIM (the raw global pick, Hermes-style).
     * Resolution happens later, per request, against whichever model runs,
     * never here, or the user's intent would be lost on every switch.
     */
    suspend fun setClamped(
        settings: com.androidharness.app.data.SettingsRepository,
        @Suppress("UNUSED_PARAMETER") modelId: String?,
        @Suppress("UNUSED_PARAMETER") devKey: String?,
        requested: ThinkingLevel,
    ) {
        settings.setThinkingLevel(requested)
    }

    private fun tierRank(tier: String): Int = when (tier.lowercase()) {
        "none" -> ThinkingLevel.OFF.rank
        "minimal" -> ThinkingLevel.MINIMAL.rank
        "low" -> ThinkingLevel.LOW.rank
        "medium" -> ThinkingLevel.MEDIUM.rank
        "high" -> ThinkingLevel.HIGH.rank
        "xhigh" -> ThinkingLevel.XHIGH.rank
        "max" -> ThinkingLevel.MAX.rank
        else -> -1
    }

    /** Closest tier the model actually enumerates (never invents a value). */
    private fun nearestEffort(values: List<String>, level: ThinkingLevel): String? =
        values.minByOrNull { kotlin.math.abs(tierRank(it) - level.rank) }

    /**
     * Exact `reasoning_effort` string for [rawRequested] on [modelId] after
     * clamping to its real vocabulary, or null when this model/style takes no
     * effort parameter (or the level is OFF). The models.dev vocabulary
     * ([devKey]) wins over the shipped family table.
     */
    fun effortWire(modelId: String?, rawRequested: ThinkingLevel, devKey: String? = null): String? {
        if (rawRequested == ThinkingLevel.OFF) return null
        val dyn = com.androidharness.app.llm.ModelsDev.entry(devKey, modelId)
        if (dyn?.reasoning == false) return null
        dyn?.effortValues?.takeIf { it.isNotEmpty() }?.let { values ->
            return nearestEffort(values, rawRequested)
        }
        val spec = forModel(modelId)
        if (spec.style != Style.EFFORT) return null

        val resolved = resolveLevel(rawRequested, spec) ?: return null
        if (resolved == ThinkingLevel.OFF) return null
        // MAX/ULTRA are sentinels for whatever the family's highest effort is.
        if (resolved == ThinkingLevel.MAX || resolved == ThinkingLevel.ULTRA) {
            return when {
                ThinkingLevel.XHIGH in spec.levels -> "xhigh"
                ThinkingLevel.HIGH in spec.levels -> "high"
                else -> null
            }
        }
        return resolved.reasoningEffort
    }

    /**
     * What to put in OpenRouter's unified `reasoning` object for [modelId]
     * at [rawRequested]. Same resolve-then-translate policy: exact effort
     * strings from the model's own list (never invented), a bare `enabled`
     * toggle when that's the only dial the model has, null for non-reasoners.
     */
    fun openRouterReasoning(modelId: String?, rawRequested: ThinkingLevel): RouterReasoning? {
        if (rawRequested == ThinkingLevel.OFF) return null
        val devKey = "openrouter"
        val dyn = com.androidharness.app.llm.ModelsDev.entry(devKey, modelId)
        if (dyn?.reasoning == false) return null
        if (dyn != null) {
            val values = dyn.effortValues
            if (!values.isNullOrEmpty()) {
                return RouterReasoning(effort = nearestEffort(values, rawRequested))
            }
            if (dyn.toggle) return RouterReasoning(enabled = true)
            // Reported as reasoning-capable but no dial vocabulary,
            // let the shipped table try below.
        }
        return openRouterEffort(modelId, rawRequested)?.let { RouterReasoning(effort = it) }
    }

    /**
     * Effort value for OpenRouter's unified `reasoning` object, which the
     * gateway normalizes across every provider it fronts. EFFORT families get
     * their exact clamped wire value; BUDGET families reached through the
     * OpenAI-compatible API clamp to the standard low/medium/high vocabulary;
     * NONE-style inherent reasoners get null, there is no dial, and they
     * already think by default.
     */
    fun openRouterEffort(modelId: String?, rawRequested: ThinkingLevel): String? {
        if (rawRequested == ThinkingLevel.OFF) return null
        val spec = forModel(modelId)
        val resolved = resolveLevel(rawRequested, spec) ?: return null
        return when (spec.style) {
            Style.EFFORT -> effortWire(modelId, resolved)
            Style.BUDGET -> when (resolved) {
                ThinkingLevel.MINIMAL, ThinkingLevel.LOW -> "low"
                ThinkingLevel.MEDIUM -> "medium"
                ThinkingLevel.OFF, ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "high"
            }
            Style.NONE -> null
        }
    }

    /**
     * Resolves the globally-set level for [modelId] once, then hands each
     * transport its answer: the native rung to encode, plus what OpenRouter
     * should carry. Call sites never re-implement the clamp.
     */
    data class ResolvedThinking(val level: ThinkingLevel)

    fun resolvedForModel(modelId: String?, rawRequested: ThinkingLevel, devKey: String? = null): ResolvedThinking =
        ResolvedThinking(resolveLevel(rawRequested, forModel(modelId, devKey)) ?: rawRequested)

    fun forModel(modelId: String?, devKey: String? = null): Spec {
        if (!modelId.isNullOrBlank()) {
            dynamicSpec(modelId, devKey)?.let { return it }
            return rules.firstOrNull { (regex, _) -> regex.containsMatchIn(modelId.lowercase()) }?.second
                ?: defaultSpec
        }
        return defaultSpec
    }
}
