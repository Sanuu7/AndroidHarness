package com.androidharness.app.agent

import com.androidharness.app.chatgpt.ChatGptProtocol
import com.androidharness.app.chatgpt.ChatGptThinking
import com.androidharness.app.llm.*

/** One capability policy for the picker and every transport. Unknown models never invent a dial. */
object ThinkingSpecs {
    enum class Style { NONE, AUTOMATIC, TOGGLE, EFFORT, BUDGET, ADAPTIVE }

    data class Spec(
        val style: Style,
        val levels: List<ThinkingLevel>,
        val budgetMin: Int = 1_024,
        val budgetMax: Int = 32_768,
        val outputMax: Int? = null,
        /** Native compat toggle, only for documented endpoint families. */
        val toggleField: String? = null,
        val defaultLevel: ThinkingLevel? = null,
        val outputLimitManaged: Boolean = false,
        val reasoningSupported: Boolean = false,
        /** Ordered provider-reported vocabulary, also seeds the custom editor. */
        val reportedLevels: List<String>? = null,
    )

    data class ResolvedThinking(
        val level: ThinkingLevel,
        val spec: Spec = Spec(Style.AUTOMATIC, emptyList()),
        val requested: ThinkingLevel = level,
        val effort: String? = null,
        val budget: Int? = null,
        val maxOutputTokens: Int = 32_768,
        val customConfig: CustomReasoningConfig? = null,
        val customLevel: String? = null,
        val requestMapping: String? = null,
    ) {
        val label: String get() = customLevel?.let(CustomReasoning::label) ?: when (spec.style) {
            Style.AUTOMATIC -> if (spec.reasoningSupported) "On" else "Thinking"
            Style.NONE -> "Off"
            Style.TOGGLE -> if (level == ThinkingLevel.OFF) "Off" else "On"
            else -> level.label
        }
        fun optionLabel(value: ThinkingLevel): String =
            if (spec.style == Style.TOGGLE && value != ThinkingLevel.OFF) "On" else value.label
        val active: Boolean get() = customLevel?.let { it != "disabled" } ?: (spec.style == Style.AUTOMATIC || level != ThinkingLevel.OFF)
        val detail: String get() = if (customLevel != null) {
            "Thinking: $label\n" + when {
                spec.style == Style.TOGGLE && requestMapping == null -> "Native on/off control"
                requestMapping == null -> "Reasoning mapping required"
                customConfig?.mapping.isNullOrBlank() -> "Inherited model/provider mapping"
                else -> "Custom CEL request mapping"
            }
        } else when (spec.style) {
            Style.NONE -> "This model has no reasoning control"
            Style.AUTOMATIC -> if (spec.reasoningSupported) "On · This model manages its own reasoning effort" else "Reasoning support is unverified. Add a custom mapping only for settings this model supports."
            Style.TOGGLE -> "$label · This model supports thinking on or off"
            else -> buildList {
                add("Thinking: $label")
                if (requested != level) add("Requested ${requested.label} · Using ${level.label}")
                budget?.takeIf { it > 0 }?.let { add("Up to $it thinking tokens") }
                add(if (spec.outputLimitManaged) "Output limit managed by ChatGPT" else "Up to $maxOutputTokens total output tokens")
            }.joinToString("\n")
        }
    }

    private val efforts = listOf(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)
    private val budgets = listOf(ThinkingLevel.OFF, ThinkingLevel.MINIMAL, ThinkingLevel.LOW,
        ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX)
    private val automatic = Spec(Style.AUTOMATIC, emptyList())
    private val none = Spec(Style.NONE, emptyList())
    private const val ANSWER_RESERVE = 4_096

    /** Keep the user's cross-model intent. Both UI and requests resolve it without rewriting settings. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun clampStoredLevel(settings: com.androidharness.app.data.SettingsRepository, modelId: String?, devKey: String?) = Unit

    fun wire(level: ThinkingLevel): String = if (level == ThinkingLevel.OFF) "none" else level.name.lowercase()
    private fun parse(value: String): ThinkingLevel? = ThinkingLevel.entries.firstOrNull { wire(it) == value.lowercase() }

    /** Saved Max/Ultra picks use the highest native setting; other unavailable picks resolve down. */
    fun resolveLevel(level: ThinkingLevel?, spec: Spec?): ThinkingLevel? {
        if (level == null || spec == null) return level
        if (spec.style == Style.NONE || spec.style == Style.AUTOMATIC) return ThinkingLevel.OFF
        val enabled = spec.levels.filter { it != ThinkingLevel.OFF }
        if (level in spec.levels) return level
        if (level == ThinkingLevel.MAX || level == ThinkingLevel.ULTRA) return enabled.maxByOrNull { it.rank }
        if (level == ThinkingLevel.OFF) return spec.defaultLevel?.takeIf { it in enabled } ?: enabled.firstOrNull()
        return enabled.filter { it.rank <= level.rank }.maxByOrNull { it.rank } ?: enabled.firstOrNull()
    }

    fun routedConfig(config: ProviderConfig): ProviderConfig {
        if (config.id != HarnessProvider.ID) return config
        HarnessProvider.pool[config.model]?.let { return config.copy(type = ProviderType.OPENAI_COMPAT, baseUrl = it.baseUrl) }
        val type = HarnessProvider.wire(config.model)
        return config.copy(type = type, baseUrl = if (type == ProviderType.ANTHROPIC)
            HarnessProvider.BASE_URL.removeSuffix("/v1") else HarnessProvider.BASE_URL)
    }

    fun usesRouterReasoning(config: ProviderConfig): Boolean {
        val host = runCatching { java.net.URI(config.baseUrl).host?.lowercase() }.getOrNull()
        return host == "openrouter.ai" || host == "api.kilo.ai" || host == "ai-gateway.kilo.ai"
    }

    fun forProvider(rawConfig: ProviderConfig, modelEntry: ModelEntry? = null): Spec {
        val native = nativeProviderSpec(rawConfig, modelEntry)
        val spec = reportedSpec(rawConfig, modelEntry, native)
        val known = modelEntry?.reasoning == true || ModelsDev.entry(ModelsDev.providerKeyFor(rawConfig.baseUrl), rawConfig.model)?.reasoning == true
        return spec.copy(reasoningSupported = spec.reasoningSupported || known || spec.style !in setOf(Style.NONE, Style.AUTOMATIC))
    }

    private fun reportedSpec(rawConfig: ProviderConfig, entry: ModelEntry?, native: Spec): Spec {
        val reported = entry?.reasoningEfforts ?: return native
        if (entry.reasoning == false || ReasoningDefaults.isLocal(rawConfig)) return native
        if (reported.isEmpty()) return automatic.copy(outputMax = native.outputMax, reportedLevels = emptyList())
        val config = routedConfig(rawConfig)
        val levels = reported.mapNotNull(CustomReasoning::native).distinct()
        val toggle = native.toggleField ?: "thinking".takeIf {
            config.type == ProviderType.OPENAI_COMPAT && ModelsDev.providerKeyFor(config.baseUrl) == "deepseek"
        }
        val off = ThinkingLevel.OFF in native.levels &&
            (toggle != null || native.style == Style.ADAPTIVE)
        val available = (if (off) listOf(ThinkingLevel.OFF) + levels else levels).distinct()
        val names = (if (off) listOf("Disabled") + reported else reported).map(CustomReasoning::label).distinct()
        return native.copy(style = if (config.type == ProviderType.ANTHROPIC) Style.ADAPTIVE else Style.EFFORT,
            levels = available, toggleField = toggle, reasoningSupported = true, reportedLevels = names,
            defaultLevel = entry.defaultReasoningEffort?.let(CustomReasoning::native)?.takeIf { it in available }
                ?: native.defaultLevel?.takeIf { it in available })
    }

    private fun nativeProviderSpec(rawConfig: ProviderConfig, modelEntry: ModelEntry? = null): Spec {
        val config = routedConfig(rawConfig)
        val key = ModelsDev.providerKeyFor(config.baseUrl)
        val entry = ModelsDev.entry(key, config.model)
        if (config.model.startsWith("kilo-auto/")) return automatic.copy(outputMax = entry?.outputTokens)
        if (modelEntry?.reasoning == false || entry?.reasoning == false) return none.copy(outputMax = entry?.outputTokens)
        if (ChatGptProtocol.isProvider(config.id)) {
            val model = modelEntry ?: ModelEntry(config.model)
            val levels = ChatGptThinking.levels(model)
            return if (levels.isEmpty()) automatic.copy(outputLimitManaged = true) else Spec(Style.EFFORT, levels,
                defaultLevel = ChatGptThinking.selected(model, ThinkingLevel.OFF), outputLimitManaged = true)
        }
        if (config.baseUrl.startsWith("local://") || com.androidharness.app.local.LocalModelCatalog.isLocal(config.id))
            return Spec(Style.TOGGLE, listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH))
        val model = config.model.substringAfterLast('/').lowercase()
        return when (config.type) {
            ProviderType.ANTHROPIC -> if (model.startsWith("claude-") && key != "deepseek") anthropic(model, entry) else {
                val capabilityModel = if (key == "deepseek" && model.startsWith("claude-")) {
                    if (model.startsWith("claude-opus")) "deepseek-v4-pro" else "deepseek-flash"
                } else config.model
                val spec = forModel(capabilityModel, key)
                if (spec.style == Style.EFFORT) spec.copy(style = Style.ADAPTIVE) else spec
            }
            ProviderType.GEMINI -> gemini(model, entry)
            ProviderType.OPENAI_RESPONSES -> forModel(config.model, key).let {
                if (it.style == Style.ADAPTIVE) it.copy(style = Style.EFFORT)
                else if (it.style == Style.BUDGET || it.style == Style.TOGGLE) automatic.copy(outputMax = it.outputMax) else it
            }
            ProviderType.OPENAI_COMPAT -> {
                if (OpenAiCompatProvider.isLocalHost(config.baseUrl)) return Spec(Style.TOGGLE, listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH))
                val spec = forModel(config.model, key)
                when {
                    usesRouterReasoning(config) -> spec
                    model.startsWith("deepseek-") && key != "deepseek" && entry?.effortValues.isNullOrEmpty() -> automatic
                    spec.style == Style.EFFORT && spec.toggleField == null && entry?.toggle == true &&
                        entry.effortValues?.contains("none") != true -> spec.copy(levels = spec.levels.filter { it != ThinkingLevel.OFF })
                    spec.style == Style.BUDGET || spec.style == Style.ADAPTIVE -> automatic.copy(outputMax = spec.outputMax)
                    spec.style == Style.TOGGLE && spec.toggleField == null -> automatic.copy(outputMax = spec.outputMax)
                    else -> spec
                }
            }
        }
    }

    fun resolve(config: ProviderConfig, requested: ThinkingLevel, maxOutputTokens: Int = 32_768,
        modelEntry: ModelEntry? = null): ResolvedThinking {
        val spec = forProvider(config, modelEntry)
        val level = resolveLevel(requested, spec) ?: ThinkingLevel.OFF
        var output = maxOutputTokens.coerceAtLeast(1).let { spec.outputMax?.let(it::coerceAtMost) ?: it }
        val budget = if (spec.style == Style.BUDGET) {
            if (level == ThinkingLevel.OFF) 0 else budgetFor(level, spec)
        } else null
        if (budget != null && budget > 0) output = maxOf(output, budget + ANSWER_RESERVE)
            .let { spec.outputMax?.let(it::coerceAtMost) ?: it }
        return ResolvedThinking(level, spec, requested,
            effort = if (spec.style == Style.EFFORT || spec.style == Style.ADAPTIVE) wire(level) else null,
            budget = budget, maxOutputTokens = output)
    }

    private fun budgetFor(level: ThinkingLevel, spec: Spec): Int =
        (when (level) {
            ThinkingLevel.MAX, ThinkingLevel.ULTRA -> spec.budgetMax
            ThinkingLevel.MINIMAL -> spec.budgetMin
            else -> level.budgetTokens(0)
        })
            .coerceIn(spec.budgetMin, spec.budgetMax)

    private fun budgetSpec(entry: ModelsDev.Entry?, minimum: Int, maximum: Int, output: Int, off: Boolean): Spec {
        val outputMax = entry?.outputTokens?.coerceAtLeast(1) ?: output
        val floor = maxOf(minimum, entry?.budgetMin ?: minimum)
        val ceiling = minOf(entry?.budgetMax ?: maximum, outputMax - ANSWER_RESERVE)
        if (ceiling < floor) return automatic.copy(outputMax = outputMax)
        val spec = Spec(Style.BUDGET, emptyList(), floor, ceiling, outputMax)
        val levels = budgets.filter { it != ThinkingLevel.OFF || off }.asReversed()
            .distinctBy { if (it == ThinkingLevel.OFF) 0 else budgetFor(it, spec) }.asReversed()
        return spec.copy(levels = levels)
    }

    private fun anthropic(model: String, entry: ModelsDev.Entry?): Spec {
        if (!model.startsWith("claude-")) return automatic.copy(outputMax = entry?.outputTokens)
        if (Regex("^claude-(2|instant|3[-.](?:[0-6]|haiku|opus|sonnet))").containsMatchIn(model))
            return none.copy(outputMax = entry?.outputTokens ?: 4_096)
        val adaptive = Regex("claude-(opus|sonnet)-4-[6-9]|claude-(opus|sonnet|haiku)-5|claude-(mythos|fable)").containsMatchIn(model)
        if (adaptive) {
            val alwaysOn = "mythos" in model || "fable" in model || "opus-5-5" in model || "sonnet-5-5" in model
            val extended = !model.contains("4-6") && !model.contains("mythos-preview")
            val fallback = efforts + (if (extended) listOf(ThinkingLevel.XHIGH) else emptyList()) + ThinkingLevel.MAX
            val levels = entry?.effortValues?.mapNotNull(::parse)?.filter { it != ThinkingLevel.OFF }
                ?.takeIf { it.isNotEmpty() } ?: fallback
            return Spec(Style.ADAPTIVE, ((if (alwaysOn) emptyList() else listOf(ThinkingLevel.OFF)) + levels)
                .distinct().sortedBy { it.rank }, outputMax = entry?.outputTokens ?: 128_000,
                defaultLevel = ThinkingLevel.MEDIUM.takeIf { "5-5" in model } ?: ThinkingLevel.HIGH)
        }
        val manual = model.startsWith("claude-3-7-sonnet") || model.startsWith("claude-3.7-sonnet") ||
            Regex("^claude-(opus|sonnet|haiku)-4(?:-[015](?:-|$)|-[0-9]{8}$|$)").containsMatchIn(model)
        if (!manual && entry?.budgetTokens != true) return automatic.copy(outputMax = entry?.outputTokens)
        val output = entry?.outputTokens ?: if (model.contains("-4-5")) 64_000 else 32_000
        return budgetSpec(entry, 1_024, output - ANSWER_RESERVE, output, off = true)
    }

    private fun gemini(model: String, entry: ModelsDev.Entry?): Spec {
        if (model.startsWith("gemini-2.5")) {
            val pro = "pro" in model
            return budgetSpec(entry, if (pro) 128 else 512, if (pro) 32_768 else 24_576, 65_536, off = !pro)
        }
        if (model.startsWith("gemini-3") || model.startsWith("gemini-4")) {
            val levels = when {
                model.startsWith("gemini-4") || model.startsWith("gemini-3.8") -> efforts
                "image" in model && "flash-lite" in model -> listOf(ThinkingLevel.MINIMAL, ThinkingLevel.HIGH)
                "flash" in model -> listOf(ThinkingLevel.MINIMAL) + efforts
                else -> listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH)
            }
            return Spec(Style.EFFORT, levels, outputMax = entry?.outputTokens ?: 65_536, defaultLevel = ThinkingLevel.HIGH)
        }
        return if (entry?.reasoning == false) none else automatic
    }

    /** Catalog vocabularies are endpoint-specific. Documented families also work without a catalog. */
    fun forModel(modelId: String?, devKey: String? = null): Spec {
        val entry = ModelsDev.entry(devKey, modelId)
        if (entry?.reasoning == false) return none.copy(outputMax = entry.outputTokens)
        val model = modelId.orEmpty().substringAfterLast('/').lowercase()
        if (devKey in setOf("moonshotai", "moonshotai-cn") && model.startsWith("kimi-k2.7")) return automatic.copy(outputMax = entry?.outputTokens, reasoningSupported = true)
        val zai = devKey?.substringBefore("-coding-plan") in setOf("zhipuai", "zai")
        val moonshot = devKey in setOf("moonshotai", "moonshotai-cn")
        val alwaysOn = (zai && model.startsWith("glm-5.3")) ||
            (moonshot && (model.startsWith("kimi-k3") || model.startsWith("kimi-k2.7")))
        val toggleField = if (!alwaysOn && ((devKey == "deepseek" && model.startsWith("deepseek-")) ||
            (moonshot && (model.startsWith("kimi-k2.5") || model.startsWith("kimi-k2.6"))) ||
            (zai && model.startsWith("glm-")))) "thinking" else null
        entry?.effortValues?.mapNotNull(::parse)?.distinct()?.sortedBy { it.rank }?.takeIf { it.isNotEmpty() }?.let { levels ->
            val withOff = when {
                alwaysOn -> levels.filter { it != ThinkingLevel.OFF }
                entry.toggle -> (listOf(ThinkingLevel.OFF) + levels).distinct()
                else -> levels
            }
            return Spec(Style.EFFORT, withOff, outputMax = entry.outputTokens, toggleField = toggleField,
                defaultLevel = if (moonshot && model.startsWith("kimi-k3")) ThinkingLevel.MAX
                    else ThinkingLevel.MEDIUM.takeIf { it in levels } ?: ThinkingLevel.HIGH.takeIf { it in levels })
        }
        if (moonshot && model.startsWith("kimi-k3")) return Spec(Style.EFFORT,
            listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.MAX), defaultLevel = ThinkingLevel.MAX, outputMax = entry?.outputTokens)
        if (alwaysOn) return automatic.copy(outputMax = entry?.outputTokens, reasoningSupported = true)
        if (toggleField != null && (moonshot || zai) && entry == null)
            return Spec(Style.TOGGLE, listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH), toggleField = toggleField)
        if (entry?.budgetTokens == true && entry.budgetMax != null && entry.outputTokens != null)
            return budgetSpec(entry, entry.budgetMin ?: 1_024, entry.budgetMax, entry.outputTokens, off = entry.toggle)
        if (entry?.toggle == true) return Spec(Style.TOGGLE, listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH),
            toggleField = toggleField, outputMax = entry.outputTokens)
        if (model.startsWith("deepseek-v4") || model == "deepseek-flash")
            return Spec(Style.EFFORT, listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.MAX),
                toggleField = toggleField, defaultLevel = ThinkingLevel.HIGH, outputMax = entry?.outputTokens)
        val openAi = ChatGptThinking.levels(ModelEntry(model))
        if (openAi.isNotEmpty()) return Spec(Style.EFFORT, openAi, outputMax = entry?.outputTokens, defaultLevel = ThinkingLevel.MEDIUM)
        if (Regex("^o[134]([-.]|$)").containsMatchIn(model)) return Spec(Style.EFFORT,
            if ("pro" in model) listOf(ThinkingLevel.HIGH) else efforts, outputMax = entry?.outputTokens, defaultLevel = ThinkingLevel.MEDIUM)
        if (model.startsWith("gpt-oss")) return Spec(Style.EFFORT, efforts, outputMax = entry?.outputTokens)
        if (model.startsWith("grok-3-mini")) return Spec(Style.EFFORT, listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH), outputMax = entry?.outputTokens)
        if ("muse-spark" in model) return Spec(Style.EFFORT, listOf(ThinkingLevel.MINIMAL) + efforts + ThinkingLevel.XHIGH)
        if (model.startsWith("claude-")) return anthropic(model, entry)
        if (model.startsWith("gemini-")) return gemini(model, entry)
        return automatic.copy(outputMax = entry?.outputTokens)
    }

    fun visibleLevels(modelId: String?, devKey: String? = null): List<ThinkingLevel> = forModel(modelId, devKey).levels
    fun effortWire(modelId: String?, rawRequested: ThinkingLevel, devKey: String? = null): String? {
        val spec = forModel(modelId, devKey)
        if (spec.style != Style.EFFORT && spec.style != Style.ADAPTIVE) return null
        val level = resolveLevel(rawRequested, spec) ?: return null
        return if (level == ThinkingLevel.OFF && spec.toggleField != null) null else wire(level)
    }

    data class RouterReasoning(val effort: String? = null, val enabled: Boolean? = null, val maxTokens: Int? = null)
    fun routerReasoning(config: ProviderConfig, rawRequested: ThinkingLevel,
        resolved: ResolvedThinking = resolve(config, rawRequested)): RouterReasoning? {
        return when (resolved.spec.style) {
            Style.NONE, Style.AUTOMATIC -> null
            Style.TOGGLE -> RouterReasoning(enabled = resolved.level != ThinkingLevel.OFF)
            Style.BUDGET -> if (resolved.level == ThinkingLevel.OFF) RouterReasoning(enabled = false)
                else RouterReasoning(maxTokens = resolved.budget)
            else -> RouterReasoning(effort = resolved.effort)
        }
    }
    fun openRouterReasoning(modelId: String?, rawRequested: ThinkingLevel): RouterReasoning? =
        routerReasoning(ProviderConfig("router", "OpenRouter", ProviderType.OPENAI_COMPAT,
            "https://openrouter.ai/api/v1", modelId.orEmpty()), rawRequested)
    fun openRouterEffort(modelId: String?, rawRequested: ThinkingLevel): String? = openRouterReasoning(modelId, rawRequested)?.effort
    fun resolvedForModel(modelId: String?, rawRequested: ThinkingLevel, devKey: String? = null): ResolvedThinking =
        ResolvedThinking(resolveLevel(rawRequested, forModel(modelId, devKey)) ?: ThinkingLevel.OFF)
}
