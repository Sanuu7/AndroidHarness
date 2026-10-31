package com.androidharness.app.ui.settings

import androidx.annotation.StringRes
import com.androidharness.app.R
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.caveman.CavemanIntensity
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.ChatStartMode
import com.androidharness.app.data.CodeStartMode
import com.androidharness.app.data.CustomThemePalette
import com.androidharness.app.data.ImageFrequency
import com.androidharness.app.data.ProgressBarStyle
import com.androidharness.app.data.RUMBLE_MAX_SPACING_MS
import com.androidharness.app.data.RUMBLE_MIN_SPACING_MS
import com.androidharness.app.data.RumbleEngine
import com.androidharness.app.data.SearchDepth
import com.androidharness.app.data.SettingsRepository
import com.androidharness.app.data.StartScreen
import com.androidharness.app.data.SteeringMode
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.data.ThemePalette
import com.androidharness.app.data.WorkspaceLayoutMode
import com.androidharness.app.data.parseThemeHex
import com.androidharness.app.data.themeHex
import java.util.Locale

/** How a setting's value is shaped. Values travel as canonical strings. */
internal sealed interface SettingKind {
    data object Toggle : SettingKind
    data class Range(val min: Double, val max: Double, val unit: RangeUnit) : SettingKind
    data class Choice(
        val options: (AppSettings) -> List<String>,
        val labels: Map<String, Int> = emptyMap(),
    ) : SettingKind
    data class Text(val maxLength: Int) : SettingKind
}

internal enum class RangeUnit(val suffix: String) { PERCENT("%"), MS(" ms"), MINUTES(" min"), SPEED("x"), COUNT(""), TOKENS(" tokens") }

/**
 * One app setting the agent can read and change from a conversation.
 *
 * [key] is the stable name the model uses. [about] is English for the model;
 * [title] is what the change card shows, next to the page title for context.
 * [sensitive] changes always wait for the user's approval, in every mode.
 */
internal class AgentSetting(
    val key: String,
    @StringRes val title: Int,
    val page: SettingsPage,
    val kind: SettingKind,
    val about: String,
    @StringRes val anchor: Int? = null,
    val keywords: String = "",
    val sensitive: Boolean = false,
    /** Another setting (key to value) that must also be set when this one is turned on, or it has no effect. */
    val requires: Pair<String, String>? = null,
    val read: (AppSettings) -> String,
    val write: suspend (SettingsRepository, String) -> Unit,
) {
    /** The canonical form of [input], or a message explaining what is allowed. */
    fun normalize(input: String, settings: AppSettings): Result<String> {
        val raw = input.trim()
        return when (kind) {
            SettingKind.Toggle -> when (raw.lowercase(Locale.ROOT)) {
                "true", "on", "yes", "1", "enable", "enabled" -> Result.success("true")
                "false", "off", "no", "0", "disable", "disabled" -> Result.success("false")
                else -> fail("$key is on/off: use true or false.")
            }
            is SettingKind.Range -> {
                val number = raw.removeSuffix(kind.unit.suffix.trim()).removeSuffix("%").trim().toDoubleOrNull()
                if (number == null || number < kind.min || number > kind.max) fail("$key must be a number from ${format(kind.min)} to ${format(kind.max)}${kind.unit.suffix}.")
                else Result.success(format(number))
            }
            is SettingKind.Choice -> {
                val options = kind.options(settings)
                fun norm(value: String) = value.lowercase(Locale.ROOT).replace(' ', '_').replace('-', '_')
                options.firstOrNull { norm(it) == norm(raw) }?.let { Result.success(it) }
                    ?: fail("$key must be one of: ${options.joinToString()}.")
            }
            is SettingKind.Text -> if (input.length > kind.maxLength) fail("$key is limited to ${kind.maxLength} characters.") else Result.success(input.trim())
        }
    }

    /** One line for the model: key, current value, allowed values, where it lives. */
    fun describe(settings: AppSettings): String {
        val allowed = when (kind) {
            SettingKind.Toggle -> "true|false"
            is SettingKind.Range -> "${format(kind.min)}..${format(kind.max)}${kind.unit.suffix}"
            is SettingKind.Choice -> kind.options(settings).joinToString("|")
            is SettingKind.Text -> "text up to ${kind.maxLength} chars"
        }
        val current = read(settings).let { if (kind is SettingKind.Text) "\"${it.take(80)}${if (it.length > 80) "…" else ""}\"" else it }
        return "$key = $current ($allowed) · ${page.name}${if (sensitive) " · needs approval" else ""} · $about"
    }

    private fun fail(message: String) = Result.failure<String>(IllegalArgumentException(message))
}

internal fun formatSettingNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')

private fun format(value: Double) = formatSettingNumber(value)
private fun bool(value: String) = value == "true"
private fun enumChoice(values: Array<out Enum<*>>, labels: Map<String, Int> = emptyMap()) =
    SettingKind.Choice({ values.map { it.name } }, labels)

private fun paletteChoice(settings: AppSettings): String = when {
    settings.activeCustomPaletteId != null && settings.customPalettes.any { it.id == settings.activeCustomPaletteId } -> "custom:${settings.activeCustomPaletteId}"
    settings.dynamicColor -> "WALLPAPER"
    else -> settings.colorPalette.name
}

internal object AgentSettingsCatalog {
    const val PALETTE = "color_palette"

    val all: List<AgentSetting> = listOf(
        // Appearance
        AgentSetting("theme_mode", R.string.settings_search_theme_title, SettingsPage.APPEARANCE,
            enumChoice(ThemeMode.entries.toTypedArray(), mapOf("SYSTEM" to R.string.settings_theme_system, "LIGHT" to R.string.settings_theme_light,
                "DARK" to R.string.settings_theme_dark, "AMOLED" to R.string.settings_theme_amoled)),
            "Light, dark, pure black AMOLED, or follow the system.", R.string.settings_anchor_theme, "dark mode light night black",
            read = { it.themeMode.name }, write = { r, v -> r.setThemeMode(ThemeMode.valueOf(v)) }),
        AgentSetting(PALETTE, R.string.theme_color_palette, SettingsPage.APPEARANCE,
            SettingKind.Choice({ s -> listOf("WALLPAPER") + ThemePalette.entries.map { it.name } + s.customPalettes.map { "custom:${it.id}" } },
                mapOf("WALLPAPER" to R.string.theme_wallpaper_colors) + ThemePalette.entries.associate { it.name to it.labelRes() }),
            "App accent colors: WALLPAPER (dynamic), a preset, or a saved custom palette. Create new ones with create_palette.",
            R.string.settings_anchor_dynamic_color, "color colour accent dynamic material you wallpaper custom palette",
            read = ::paletteChoice, write = { r, v -> when {
                v == "WALLPAPER" -> r.setDynamicColor(true)
                v.startsWith("custom:") -> r.selectCustomPalette(v.removePrefix("custom:"))
                else -> r.setColorPalette(ThemePalette.valueOf(v))
            } }),
        AgentSetting("frosted_glass", R.string.glass_enable, SettingsPage.APPEARANCE, SettingKind.Toggle,
            "Frosted glass bars and sheets.", R.string.glass_title, "blur transparent glass",
            read = { it.harnessGlassEnabled.toString() }, write = { r, v -> r.setHarnessGlassEnabled(bool(v)) }),
        AgentSetting("liquid_glass", R.string.glass_liquid, SettingsPage.APPEARANCE, SettingKind.Toggle,
            "Liquid glass refraction on top of frosted glass. Reads as off while frosted glass is off; turning it on also turns frosted glass on.",
            R.string.glass_liquid, "refraction glass", requires = "frosted_glass" to "true",
            read = { (it.harnessLiquidGlass && it.harnessGlassEnabled).toString() }, write = { r, v -> r.setHarnessLiquidGlass(bool(v)) }),
        AgentSetting("glass_transparency", R.string.glass_transparency, SettingsPage.APPEARANCE, SettingKind.Range(0.0, 100.0, RangeUnit.PERCENT),
            "How see-through liquid glass is. Only takes effect while liquid glass is on.", R.string.glass_title, "glass opacity transparent",
            read = { format(Math.round(it.harnessGlassTransparency * 100.0).toDouble()) }, write = { r, v -> r.setHarnessGlassTransparency(v.toFloat() / 100f) }),
        AgentSetting("canvas", R.string.theme_canvas_enabled, SettingsPage.APPEARANCE, SettingKind.Toggle,
            "Show the Harness Canvas background image (the image itself is picked on the page).", R.string.theme_canvas_title, "background wallpaper image canvas",
            read = { it.harnessCanvas.enabled.toString() }, write = { r, v -> r.updateHarnessCanvas { c -> c.copy(enabled = bool(v)) } }),
        AgentSetting("canvas_dim", R.string.theme_canvas_dim, SettingsPage.APPEARANCE, SettingKind.Range(0.0, 100.0, RangeUnit.PERCENT),
            "How much the canvas image is dimmed.", R.string.theme_canvas_title, "background dim darken",
            read = { format(Math.round(it.harnessCanvas.dim * 100.0).toDouble()) }, write = { r, v -> r.updateHarnessCanvas { c -> c.copy(dim = v.toFloat() / 100f) } }),
        AgentSetting("progress_bar_style", R.string.settings_search_progress_bar_title, SettingsPage.APPEARANCE, enumChoice(ProgressBarStyle.entries.toTypedArray()),
            "Flat or squiggly progress bars.", R.string.settings_anchor_progress_bar_style, "progress wavy squiggly",
            read = { it.progressBarStyle.name }, write = { r, v -> r.setProgressBarStyle(ProgressBarStyle.valueOf(v)) }),
        AgentSetting("workspace_layout", R.string.settings_search_layout_title, SettingsPage.APPEARANCE, enumChoice(WorkspaceLayoutMode.entries.toTypedArray()),
            "Desktop workspace on wide screens, or phone screens.", R.string.settings_anchor_layout, "desktop tablet layout",
            read = { it.workspaceLayout.name }, write = { r, v -> r.setWorkspaceLayout(WorkspaceLayoutMode.valueOf(v)) }),
        // AH Rumble
        AgentSetting("rumble", R.string.rumble_title, SettingsPage.RUMBLE, SettingKind.Toggle,
            "Haptic pulses as the agent moves through a turn.", R.string.rumble_title, "haptics vibration vibrate",
            read = { it.rumbleEnabled.toString() }, write = { r, v -> r.setRumbleEnabled(bool(v)) }),
        AgentSetting("rumble_strength", R.string.rumble_strength, SettingsPage.RUMBLE, SettingKind.Range(0.0, 100.0, RangeUnit.PERCENT),
            "Vibration strength.", keywords = "haptics vibration intensity strong weak",
            read = { it.rumbleStrength.toString() }, write = { r, v -> r.setRumbleStrength(v.toDouble().toInt()) }),
        AgentSetting("rumble_smoothness", R.string.rumble_feel, SettingsPage.RUMBLE, SettingKind.Range(0.0, 100.0, RangeUnit.PERCENT),
            "0 is a crisp tick, 100 a long soft swell.", keywords = "haptics feel crisp smooth soft",
            read = { it.rumbleSmoothness.toString() }, write = { r, v -> r.setRumbleSmoothness(v.toDouble().toInt()) }),
        AgentSetting("rumble_engine", R.string.rumble_engine_title, SettingsPage.RUMBLE,
            enumChoice(RumbleEngine.entries.toTypedArray(), mapOf("AUTO" to R.string.rumble_engine_auto, "SYSTEM" to R.string.rumble_engine_system, "CUSTOM" to R.string.rumble_engine_custom)),
            "Which haptic engine plays the pulses.", R.string.rumble_engine_title, "haptics engine",
            read = { it.rumbleEngine.name }, write = { r, v -> r.setRumbleEngine(RumbleEngine.valueOf(v)) }),
        AgentSetting("rumble_in_chat", R.string.rumble_in_chat, SettingsPage.RUMBLE, SettingKind.Toggle,
            "Rumble in Chat conversations.", keywords = "haptics chat",
            read = { it.rumbleInChat.toString() }, write = { r, v -> r.setRumbleInChat(bool(v)) }),
        AgentSetting("rumble_in_code", R.string.rumble_in_code, SettingsPage.RUMBLE, SettingKind.Toggle,
            "Rumble in Code conversations.", R.string.rumble_in_code, "haptics code",
            read = { it.rumbleInCode.toString() }, write = { r, v -> r.setRumbleInCode(bool(v)) }),
        AgentSetting("rumble_flow", R.string.rumble_flow_title, SettingsPage.RUMBLE, SettingKind.Toggle,
            "A smooth rumble while a Chat reply streams in.", R.string.rumble_flow_title, "haptics streaming flow",
            read = { it.rumbleFlow.toString() }, write = { r, v -> r.setRumbleFlow(bool(v)) }),
        AgentSetting("rumble_spacing", R.string.rumble_spacing, SettingsPage.RUMBLE,
            SettingKind.Range(RUMBLE_MIN_SPACING_MS.toDouble(), RUMBLE_MAX_SPACING_MS.toDouble(), RangeUnit.MS),
            "Pulses closer together than this are dropped.", keywords = "haptics spacing gap",
            read = { it.rumbleSpacingMs.toString() }, write = { r, v -> r.setRumbleSpacing(v.toDouble().toInt()) }),
        AgentSetting("rumble_battery_saver", R.string.rumble_battery_saver, SettingsPage.RUMBLE, SettingKind.Toggle,
            "Pause rumble while battery saver is on.", keywords = "haptics battery",
            read = { it.rumbleSkipOnBatterySaver.toString() }, write = { r, v -> r.setRumbleSkipOnBatterySaver(bool(v)) }),
        // Chat options
        AgentSetting("start_screen", R.string.settings_start_screen, SettingsPage.CHAT_OPTIONS, enumChoice(StartScreen.entries.toTypedArray(),
            mapOf("LAST_SELECTED" to R.string.settings_start_last_selected)),
            "What the app opens to: CODE, CHAT, or the last one used.", R.string.settings_start_screen, "launch open startup",
            read = { it.startScreen.name }, write = { r, v -> r.setStartScreen(StartScreen.valueOf(v)) }),
        AgentSetting("chat_start", R.string.settings_chat_start, SettingsPage.CHAT_OPTIONS, enumChoice(ChatStartMode.entries.toTypedArray(),
            mapOf("NEW_CHAT" to R.string.settings_chat_start_new, "LAST_CHAT" to R.string.settings_chat_start_last)),
            "Chat opens a new chat or continues the last one.", R.string.settings_chat_start, "resume continue new chat",
            read = { it.chatStart.name }, write = { r, v -> r.setChatStart(ChatStartMode.valueOf(v)) }),
        AgentSetting("code_start", R.string.settings_start_code, SettingsPage.CODING, enumChoice(CodeStartMode.entries.toTypedArray(),
            mapOf("NEW_CODE" to R.string.settings_start_code_new, "LAST_CODE" to R.string.settings_start_code_last)),
            "Code opens a new conversation or continues the last one.", R.string.settings_start_code, "resume continue code",
            read = { it.codeStart.name }, write = { r, v -> r.setCodeStart(CodeStartMode.valueOf(v)) }),
        AgentSetting("chat_instructions", R.string.chat_global_instructions, SettingsPage.CHAT_OPTIONS, SettingKind.Text(4_000),
            "Custom instructions added to every Chat conversation.", R.string.chat_global_instructions, "custom instructions persona system prompt",
            sensitive = true, read = { it.chatInstructions }, write = { r, v -> r.setChatInstructions(v) }),
        AgentSetting("search_depth", R.string.ui_search_depth, SettingsPage.CHAT_OPTIONS, enumChoice(SearchDepth.entries.toTypedArray(),
            mapOf("OFF" to R.string.ui_search_depth_off, "INSTANT" to R.string.ui_search_depth_instant, "DEEP" to R.string.ui_search_depth_deep,
                "AUTO" to R.string.ui_search_depth_auto, "DEEP_RESEARCH" to R.string.ui_search_depth_research, "MAX_RESEARCH" to R.string.ui_search_depth_max)),
            "Default web research effort for Chat.", keywords = "web research sources search",
            read = { it.searchDepth.name }, write = { r, v -> r.setSearchDepth(SearchDepth.valueOf(v)) }),
        AgentSetting("image_frequency", R.string.settings_image_frequency, SettingsPage.CHAT_OPTIONS, enumChoice(ImageFrequency.entries.toTypedArray(),
            mapOf("NONE" to R.string.chat_image_frequency_none, "LESS" to R.string.chat_image_frequency_less, "DEFAULT" to R.string.chat_image_frequency_default,
                "MORE" to R.string.chat_image_frequency_more, "ALWAYS" to R.string.chat_image_frequency_always)),
            "How often Chat answers include pictures.", keywords = "images pictures photos",
            read = { it.chatImageFrequency.name }, write = { r, v -> r.setChatImageFrequency(ImageFrequency.valueOf(v)) }),
        AgentSetting("keep_thinking_collapsed", R.string.chat_keep_thinking_collapsed, SettingsPage.CHAT_OPTIONS, SettingKind.Toggle,
            "Keep the model's thinking folded away while it streams.", keywords = "reasoning thoughts expand",
            read = { it.chatKeepThinkingCollapsed.toString() }, write = { r, v -> r.setChatKeepThinkingCollapsed(bool(v)) }),
        // Memory
        AgentSetting("memory", R.string.settings_memory, SettingsPage.MEMORY, SettingKind.Toggle,
            "Master switch for saved memories.", keywords = "remember recall memories",
            read = { it.memoryEnabled.toString() }, write = { r, v -> r.setMemoryEnabled(bool(v)) }),
        AgentSetting("chat_memory", R.string.memory_chat_enabled, SettingsPage.MEMORY, SettingKind.Toggle,
            "Memory in Chat.", keywords = "remember chat",
            read = { it.chatMemoryEnabled.toString() }, write = { r, v -> r.setSectionMemoryEnabled(com.androidharness.app.data.MemorySection.CHAT, bool(v)) }),
        AgentSetting("code_memory", R.string.memory_code_enabled, SettingsPage.MEMORY, SettingKind.Toggle,
            "Memory in Code.", keywords = "remember code",
            read = { it.codeMemoryEnabled.toString() }, write = { r, v -> r.setSectionMemoryEnabled(com.androidharness.app.data.MemorySection.CODE, bool(v)) }),
        // Agent
        AgentSetting("permission_mode", R.string.settings_search_permission_mode_title, SettingsPage.AGENT, enumChoice(PermissionMode.entries.toTypedArray()),
            "Default approvals for Code: CONFIRM_ALL, CONFIRM_RISKY, FULL_AUTO, FULL_ACCESS.", R.string.settings_anchor_default_permission,
            "approvals permissions full access auto", sensitive = true,
            read = { it.permissionMode.name }, write = { r, v -> r.setPermissionMode(PermissionMode.valueOf(v)) }),
        AgentSetting("max_iterations", R.string.settings_search_tool_iterations_title, SettingsPage.AGENT, SettingKind.Range(0.0, 1_000.0, RangeUnit.COUNT),
            "Tool steps per run; 0 is unlimited.", R.string.settings_anchor_tool_iterations, "steps limit loops",
            read = { it.maxIterations.toString() }, write = { r, v -> r.setMaxIterations(v.toDouble().toInt()) }),
        AgentSetting("max_context", R.string.settings_search_max_context_title, SettingsPage.AGENT, SettingKind.Range(8_000.0, 2_000_000.0, RangeUnit.TOKENS),
            "Context cap in tokens before older messages are compacted.", R.string.settings_anchor_max_context, "context window tokens compact",
            read = { it.maxContextTokens.toString() }, write = { r, v -> r.setMaxContextTokens(v.toDouble().toInt()) }),
        AgentSetting("steering", R.string.settings_anchor_steering, SettingsPage.AGENT,
            enumChoice(SteeringMode.entries.toTypedArray(), mapOf("AH_AUTO" to R.string.steering_mode_auto, "AFTER_TASK" to R.string.steering_mode_after_task)),
            "AH_AUTO reads messages sent mid-task at the next step; AFTER_TASK waits for the task to end.", R.string.settings_anchor_steering, "steer queue messages",
            read = { it.steeringMode.name }, write = { r, v -> r.setSteeringMode(SteeringMode.valueOf(v)) }),
        AgentSetting("tips", R.string.tips_setting_title, SettingsPage.AGENT, SettingKind.Toggle,
            "Occasional tips while the agent works.", R.string.tips_setting_title, "hints tips help",
            read = { it.runTipsEnabled.toString() }, write = { r, v -> r.setRunTipsEnabled(bool(v)) }),
        AgentSetting("show_live_code", R.string.settings_search_show_live_code_title, SettingsPage.CHAT, SettingKind.Toggle,
            "Stream file edits as live code in Code.", R.string.settings_anchor_show_live_code, "live code preview edits",
            read = { it.showLiveCode.toString() }, write = { r, v -> r.setShowLiveCode(bool(v)) }),
        AgentSetting("repo_map", R.string.settings_search_repo_map_title, SettingsPage.CHAT, SettingKind.Toggle,
            "Give the Code agent a compact symbol map of the project.", R.string.settings_anchor_repo_map, "symbols index map",
            read = { it.repoMapEnabled.toString() }, write = { r, v -> r.setRepoMapEnabled(bool(v)) }),
        AgentSetting("dual_planning", R.string.settings_search_dual_planning_title, SettingsPage.MODELS, SettingKind.Toggle,
            "Separate planning and execution models (models are picked on the page).", R.string.settings_anchor_dual_planning, "plan model execute two models",
            read = { it.planningModelsEnabled.toString() }, write = { r, v -> r.setPlanningModelsEnabled(bool(v)) }),
        // Sub-agents
        AgentSetting("subagent_action_tools", R.string.settings_search_subagent_tools_title, SettingsPage.SUBAGENT, SettingKind.Toggle,
            "Let subagents edit files and run commands in Act mode.", R.string.settings_anchor_subagent_tools, "subagent edit commands", sensitive = true,
            read = { it.subagentFullAccess.toString() }, write = { r, v -> r.setSubagentFullAccess(bool(v)) }),
        AgentSetting("background_subagents", R.string.subagents_allow_background, SettingsPage.SUBAGENT, SettingKind.Toggle,
            "Allow read-only subagents to keep working in the background.", keywords = "subagent background delegation",
            read = { it.backgroundSubagents.toString() }, write = { r, v -> r.setBackgroundSubagents(bool(v)) }),
        AgentSetting("subagent_notifications", R.string.subagents_notify_completion, SettingsPage.SUBAGENT, SettingKind.Toggle,
            "Notify when a background subagent finishes.", keywords = "subagent notification done",
            read = { it.subagentCompletionNotifications.toString() }, write = { r, v -> r.setSubagentCompletionNotifications(bool(v)) }),
        // Caveman
        AgentSetting("caveman", R.string.settings_page_caveman_title, SettingsPage.CAVEMAN, SettingKind.Toggle,
            "Install or remove the Caveman token-saving reply style.", keywords = "caveman install brevity tokens short",
            read = { it.cavemanInstalled.toString() }, write = { r, v -> r.setCavemanInstalled(bool(v)) }),
        AgentSetting("caveman_level", R.string.as_caveman_level, SettingsPage.CAVEMAN, enumChoice(CavemanIntensity.entries.toTypedArray()),
            "How terse Caveman replies are; needs caveman installed.", keywords = "caveman intensity lite full ultra",
            read = { it.cavemanIntensity.name }, write = { r, v -> r.setCavemanIntensity(CavemanIntensity.valueOf(v)) }),
        AgentSetting("caveman_classical_chinese", R.string.settings_caveman_classical_chinese, SettingsPage.CAVEMAN, SettingKind.Toggle,
            "Caveman in classical Chinese (wenyan).", keywords = "caveman wenyan chinese",
            read = { it.cavemanWenyan.toString() }, write = { r, v -> r.setCavemanWenyan(bool(v)) }),
        // Voice
        AgentSetting("speech_to_text", R.string.settings_search_stt_title, SettingsPage.VOICE,
            SettingKind.Choice({ listOf(AppSettings.VOICE_ENGINE_INBUILT, AppSettings.VOICE_ENGINE_GROQ) }),
            "Voice input engine: inbuilt Android or groq Whisper (needs a Groq key on the page).", R.string.settings_anchor_stt, "voice dictation whisper microphone",
            read = { it.voiceEngine }, write = { r, v -> r.setVoiceEngine(v) }),
        AgentSetting("text_to_speech", R.string.settings_search_tts_title, SettingsPage.VOICE,
            SettingKind.Choice({ listOf(AppSettings.TTS_ENGINE_SYSTEM, AppSettings.TTS_ENGINE_GROQ) }),
            "Read-aloud engine: system Android voice or groq.", R.string.settings_anchor_tts, "read aloud voice speech",
            read = { it.ttsEngine }, write = { r, v -> r.setTtsEngine(v) }),
        AgentSetting("speech_speed", R.string.settings_tts_speed, SettingsPage.VOICE, SettingKind.Range(0.5, 2.0, RangeUnit.SPEED),
            "Read-aloud speed multiplier.", R.string.settings_anchor_tts, "read aloud rate fast slow",
            read = { format(it.ttsSpeed.toDouble()) }, write = { r, v -> r.setTtsSpeed(v.toFloat()) }),
        AgentSetting("always_read_aloud", R.string.settings_tts_always_speak, SettingsPage.VOICE, SettingKind.Toggle,
            "Read every finished reply aloud automatically.", R.string.settings_anchor_tts, "read aloud automatic speak",
            read = { it.ttsAlwaysSpeak.toString() }, write = { r, v -> r.setTtsAlwaysSpeak(bool(v)) }),
        // Web search
        AgentSetting("web_search_provider", R.string.settings_search_web_title, SettingsPage.SEARCH,
            SettingKind.Choice({ listOf("keyless", "brave", "tavily") }),
            "Web search backend; brave and tavily need an API key on the page.", keywords = "search engine brave tavily",
            read = { it.webSearchProvider }, write = { r, v -> r.setWebSearchProvider(v) }),
        // Background
        AgentSetting("run_in_background", R.string.background_run_title, SettingsPage.BACKGROUND, SettingKind.Toggle,
            "Keep runs going when the app is in the background.", R.string.background_run_title, "background minimize screen off",
            read = { it.runInBackground.toString() }, write = { r, v -> r.setRunInBackground(bool(v)) }),
        AgentSetting("notifications", R.string.background_notifications_title, SettingsPage.BACKGROUND, SettingKind.Toggle,
            "Notify when a background run finishes or needs approval.", R.string.background_notifications_title, "notifications alerts",
            read = { it.notificationsEnabled.toString() }, write = { r, v -> r.setNotificationsEnabled(bool(v)) }),
        AgentSetting("keep_runs_alive", R.string.settings_env_keep_alive, SettingsPage.BACKGROUND, SettingKind.Toggle,
            "Hold a wake lock so long runs survive the screen turning off.", keywords = "wakelock alive screen off",
            read = { it.keepAlive.toString() }, write = { r, v -> r.setKeepAlive(bool(v)) }),
        AgentSetting("live_updates", R.string.settings_agent_live_updates, SettingsPage.BACKGROUND, SettingKind.Toggle,
            "Show runs as an Android 16 Live Update.", R.string.settings_agent_live_updates, "live update status bar chip",
            read = { it.liveUpdatesEnabled.toString() }, write = { r, v -> r.setLiveUpdatesEnabled(bool(v)) }),
        // Privacy
        AgentSetting("app_lock", R.string.settings_search_app_lock_title, SettingsPage.PRIVACY, SettingKind.Toggle,
            "Require fingerprint, face or PIN to open the app.", keywords = "biometric fingerprint lock security", sensitive = true,
            read = { it.biometricLockEnabled.toString() }, write = { r, v -> r.setBiometricLockEnabled(bool(v)) }),
        AgentSetting("auto_lock_minutes", R.string.settings_search_autolock_title, SettingsPage.PRIVACY, SettingKind.Range(0.0, 60.0, RangeUnit.MINUTES),
            "Minutes in the background before the app locks again; 0 locks at once.", keywords = "lock timeout grace", sensitive = true,
            read = { it.biometricLockTimeoutMinutes.toString() }, write = { r, v -> r.setBiometricLockTimeout(v.toDouble().toInt()) }),
        AgentSetting("allow_screenshots", R.string.settings_search_screenshots_title, SettingsPage.PRIVACY, SettingKind.Toggle,
            "Allow screenshots and the recents preview.", keywords = "screenshot recents privacy secure", sensitive = true,
            read = { it.allowScreenshots.toString() }, write = { r, v -> r.setAllowScreenshots(bool(v)) }),
    )

    private val byKey = all.associateBy { it.key }

    operator fun get(key: String): AgentSetting? = byKey[key]

    /** Settings matching any word of [query], best first; everything when blank. */
    fun search(query: String): List<AgentSetting> {
        val words = query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
        if (words.isEmpty()) return all
        return all.map { setting ->
            val hay = "${setting.key.replace('_', ' ')} ${setting.about} ${setting.keywords} ${setting.page.name} ${setting.page.keywords}".lowercase(Locale.ROOT)
            setting to words.count { it in hay } + words.count { it in setting.key }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    /** Pages the agent can point the user at for things it cannot change itself. */
    fun pages(query: String): List<SettingsPage> {
        val words = query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
        return SettingsPage.entries.filter { page ->
            val hay = "${page.name.replace('_', ' ')} ${page.keywords}".lowercase(Locale.ROOT)
            words.isEmpty() || words.any { it in hay }
        }
    }

    /** Validates a palette the agent wants to create. */
    fun palette(id: String, name: String, primary: String, secondary: String?, tertiary: String?): Result<CustomThemePalette> {
        val main = parseThemeHex(primary) ?: return Result.failure(IllegalArgumentException("primary must be a hex color like #1E88E5."))
        val second = secondary?.takeIf { it.isNotBlank() }?.let { parseThemeHex(it) ?: return Result.failure(IllegalArgumentException("secondary must be a hex color like #26A69A.")) }
        val third = tertiary?.takeIf { it.isNotBlank() }?.let { parseThemeHex(it) ?: return Result.failure(IllegalArgumentException("tertiary must be a hex color like #FFB300.")) }
        val clean = name.trim().take(40).ifBlank { themeHex(main) }
        return Result.success(CustomThemePalette(id, clean, main, second, third))
    }
}
