package com.androidharness.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.agent.ThinkingLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsStore by preferencesDataStore(name = "settings")

private val WEB_SEARCH_PROVIDERS = setOf("keyless", "brave", "tavily")

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

@kotlinx.serialization.Serializable
enum class ProgressBarStyle { FLAT, SQUIGGLY }

@kotlinx.serialization.Serializable
data class AppSettings(
    val permissionMode: PermissionMode = PermissionMode.CONFIRM_RISKY,
    val subagentFullAccess: Boolean = false,
    val backgroundSubagents: Boolean = false,
    val subagentCompletionNotifications: Boolean = true,
    val subagentProviderId: String? = null,
    val subagentModel: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val colorPalette: ThemePalette = ThemePalette.BLUE,
    val customPalettes: List<CustomThemePalette> = emptyList(),
    val activeCustomPaletteId: String? = null,
    val harnessCanvas: HarnessCanvasSettings = HarnessCanvasSettings(),
    val harnessGlassEnabled: Boolean = false,
    val harnessLiquidGlass: Boolean = false,
    val harnessGlassTransparency: Float = 0.5f,
    val progressBarStyle: ProgressBarStyle = ProgressBarStyle.FLAT,
    val workspaceLayout: WorkspaceLayoutMode = WorkspaceLayoutMode.AUTO,
    val editorWordWrap: Boolean = false,
    val editorMatchCase: Boolean = false,
    val editorRegexSearch: Boolean = false,
    val activeProviderId: String? = null,
    val providerOrder: List<String> = emptyList(),
    val disabledProviderIds: Set<String> = emptySet(),
    val fallbackPolicy: com.androidharness.app.llm.FallbackPolicy = com.androidharness.app.llm.FallbackPolicy(),
    val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    val reasoningConfigs: Map<String, com.androidharness.app.agent.CustomReasoningConfig> = emptyMap(),
    val customThinkingLevels: Map<String, String> = emptyMap(),
    val maxContextTokens: Int = DEFAULT_MAX_CONTEXT,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT,
    /** Tool-call iterations per run. 0 = unlimited. */
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    /** Hold a wakelock while a run is active so runs survive screen-off. */
    val runInBackground: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val keepAlive: Boolean = true,
    /** Promote the agent-run notification to an Android 16 Live Update on this device. */
    val liveUpdatesEnabled: Boolean = true,
    /** Drawer: pinned / archived session ids (DataStore, no DB migration). */
    val pinnedSessions: Set<String> = emptySet(),
    val archivedSessions: Set<String> = emptySet(),
    val sidebarProjects: Boolean = false,
    val pinnedProjects: Set<String> = emptySet(),
    val archivedProjects: Set<String> = emptySet(),
    /** First-run setup was finished or skipped; stops the setup gate. */
    val onboardingDone: Boolean = false,
    /**
     * Model selected from the active provider's catalog. Null = use the
     * provider entry's saved default model.
     */
    val activeModel: String? = null,
    /** On keeps the existing shared picker; off restores each chat's saved models. */
    val sameModelAcrossChats: Boolean = true,
    /** Skill names hidden from the catalog and slash picker. */
    val disabledSkills: Set<String> = emptySet(),
    /** web_search backend: "keyless" (default) | "brave" | "tavily". */
    val webSearchProvider: String = "keyless",
    /**
     * Screenshots are allowed by default outside credential screens. Off blocks
     * them app wide; screens that show keys and tokens always block.
     */
    val allowScreenshots: Boolean = true,
    /**
     * Separate models per agent mode: plan-mode runs use the planning
     * provider/model, everything else uses the execution one. Off (default)
     * runs everything on the single active model.
     */
    val planningModelsEnabled: Boolean = false,
    val planningProviderId: String? = null,
    val planningModel: String? = null,
    val executionProviderId: String? = null,
    val executionModel: String? = null,
    /** The one-time "you can now use two models" chat dialog was dismissed. */
    val planningModelsPromoSeen: Boolean = false,
    /** The one-time introductory fork dialog was dismissed. */
    val forkPromoSeen: Boolean = false,
    /** Biometric app lock: requires fingerprint/face/PIN authentication to open. */
    val biometricLockEnabled: Boolean = false,
    /** Grace period in minutes before re-locking when app is backgrounded (0 = immediately). */
    val biometricLockTimeoutMinutes: Int = 0,
    /** Voice speech-to-text engine: "inbuilt" (native Android recognizer) or "groq" (Groq Whisper API). */
    val voiceEngine: String = "inbuilt",
    /** Groq Whisper model: "whisper-large-v3" (default) or "whisper-large-v3-turbo". */
    val groqWhisperModel: String = "whisper-large-v3",
    /** Text-to-speech engine: "system" (Android TTS) or "groq" (Groq PlayAI). */
    val ttsEngine: String = TTS_ENGINE_SYSTEM,
    /** Groq TTS model id, fetched from the account's model list when possible. */
    val ttsGroqModel: String = DEFAULT_TTS_GROQ_MODEL,
    /** Groq TTS voice id, e.g. "Fritz-PlayAI" or "autumn". */
    val ttsGroqVoice: String = DEFAULT_TTS_GROQ_VOICE,
    /** Android TTS voice name; null picks the best voice for the app language. */
    val ttsSystemVoice: String? = null,
    /** Speech rate multiplier shared by both engines. */
    val ttsSpeed: Float = 1f,
    /** Read every finished assistant reply automatically. */
    val ttsAlwaysSpeak: Boolean = false,
    /** The one-time voice configuration promo dialog was dismissed. */
    val voicePromoSeen: Boolean = false,
    /** Legacy Code resume toggle kept for old backups; codeStart is authoritative. */
    val resumeLastChat: Boolean = true,
    val startScreen: StartScreen = StartScreen.LAST_SELECTED,
    /** Chat opens a fresh conversation unless the user asks to continue the latest one. */
    val chatStart: ChatStartMode = ChatStartMode.NEW_CHAT,
    val chatKeepThinkingCollapsed: Boolean = true,
    /** Default image appetite for chats that have not overridden it. */
    val chatImageFrequency: ImageFrequency = ImageFrequency.DEFAULT,
    val chatInstructions: String = "",
    val chatAgents: List<ChatAgentProfile> = emptyList(),
    /** Code opens a fresh conversation unless the user asks to continue the latest one. */
    val codeStart: CodeStartMode = CodeStartMode.LAST_CODE,
    /** Web research effort for Chat turns. */
    val searchDepth: SearchDepth = SearchDepth.AUTO,
    /** Master switch for section memories and legacy project notes. */
    val memoryEnabled: Boolean = true,
    val chatMemoryEnabled: Boolean = true,
    val codeMemoryEnabled: Boolean = true,
    val chatRecallCodeMemory: Boolean = false,
    val codeRecallChatMemory: Boolean = false,
    /** Device-local section selection, including an empty Chat or Code draft. */
    val lastConversationKind: ConversationKind = ConversationKind.CODE,
    val showLiveCode: Boolean = true,
    /** ID of the last active chat session. */
    val lastActiveSessionId: String? = null,
    /** Generate and inject compact codebase symbol index (Repo map) into agent context. */
    val repoMapEnabled: Boolean = true,
    /** Local models start in plain chat until the user enables agent context. */
    val localModelAgentContext: Boolean = false,
    /** Explicit, device-local consent for experimental local model features. */
    val localModelExperimental: Boolean = false,
    val cavemanInstalled: Boolean = false,
    val cavemanIntensity: com.androidharness.app.caveman.CavemanIntensity = com.androidharness.app.caveman.CavemanIntensity.OFF,
    val cavemanWenyan: Boolean = false,
) {
    companion object {
        const val DEFAULT_MAX_CONTEXT = 1_000_000
        const val DEFAULT_MAX_OUTPUT = 32_768
        const val DEFAULT_MAX_ITERATIONS = 0 // unlimited
        const val VOICE_ENGINE_INBUILT = "inbuilt"
        const val VOICE_ENGINE_GROQ = "groq"
        const val GROQ_MODEL_WHISPER_V3 = "whisper-large-v3"
        const val GROQ_MODEL_WHISPER_TURBO = "whisper-large-v3-turbo"
        const val TTS_ENGINE_SYSTEM = "system"
        const val TTS_ENGINE_GROQ = "groq"
        const val DEFAULT_TTS_GROQ_MODEL = "playai-tts"
        const val DEFAULT_TTS_GROQ_VOICE = "Fritz-PlayAI"
    }
}

class SettingsRepository(private val context: Context) {
    suspend fun setFallbackPolicy(policy: com.androidharness.app.llm.FallbackPolicy) {
        val encoded = com.androidharness.app.llm.FallbackPolicy.encode(policy)
        context.settingsStore.edit { it[Keys.FALLBACK_POLICY] = encoded }
    }

    private fun decodeReasoningConfigs(raw: String?): Map<String, com.androidharness.app.agent.CustomReasoningConfig> =
        raw?.let { kotlinx.serialization.json.Json.decodeFromString(it) } ?: emptyMap()

    private fun decodeCustomLevels(raw: String?): Map<String, String> =
        raw?.let { kotlinx.serialization.json.Json.decodeFromString(it) } ?: emptyMap()

    suspend fun setReasoningConfig(key: String, config: com.androidharness.app.agent.CustomReasoningConfig?) {
        context.settingsStore.edit { prefs ->
            val saved = decodeReasoningConfigs(prefs[Keys.REASONING_CONFIGS]).toMutableMap()
            if (config == null) saved.remove(key) else saved[key] = config
            prefs[Keys.REASONING_CONFIGS] = kotlinx.serialization.json.Json.encodeToString(saved)
        }
    }

    suspend fun setCustomThinkingLevel(key: String, level: String?) {
        context.settingsStore.edit { prefs ->
            val saved = decodeCustomLevels(prefs[Keys.CUSTOM_THINKING_LEVELS]).toMutableMap()
            if (level == null) saved.remove(key) else saved[key] = level
            prefs[Keys.CUSTOM_THINKING_LEVELS] = kotlinx.serialization.json.Json.encodeToString(saved)
        }
    }

    private object Keys {
        val PERMISSION_MODE = stringPreferencesKey("permission_mode")
        val SUBAGENT_FULL_ACCESS = booleanPreferencesKey("subagent_full_access")
        val BACKGROUND_SUBAGENTS = booleanPreferencesKey("background_subagents")
        val SUBAGENT_COMPLETION_NOTIFICATIONS = booleanPreferencesKey("subagent_completion_notifications")
        val SUBAGENT_PROVIDER = stringPreferencesKey("subagent_provider_id")
        val SUBAGENT_MODEL = stringPreferencesKey("subagent_model")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val COLOR_PALETTE = stringPreferencesKey("color_palette")
        val CUSTOM_PALETTES = stringPreferencesKey("custom_palettes")
        val ACTIVE_CUSTOM_PALETTE = stringPreferencesKey("active_custom_palette")
        val HARNESS_CANVAS = stringPreferencesKey("harness_canvas")
        val HARNESS_GLASS_ENABLED = booleanPreferencesKey("harness_glass_enabled")
        val HARNESS_LIQUID_GLASS = booleanPreferencesKey("harness_liquid_glass")
        val HARNESS_GLASS_TRANSPARENCY = floatPreferencesKey("harness_glass_transparency")
        val PROGRESS_BAR_STYLE = stringPreferencesKey("progress_bar_style")
        val WORKSPACE_LAYOUT = stringPreferencesKey("workspace_layout")
        val EDITOR_WORD_WRAP = booleanPreferencesKey("editor_word_wrap")
        val EDITOR_MATCH_CASE = booleanPreferencesKey("editor_match_case")
        val EDITOR_REGEX_SEARCH = booleanPreferencesKey("editor_regex_search")
        val ACTIVE_PROVIDER = stringPreferencesKey("active_provider_id")
        val PROVIDER_ORDER = stringPreferencesKey("provider_order")
        val DISABLED_PROVIDERS = stringSetPreferencesKey("disabled_providers")
        val FALLBACK_POLICY = stringPreferencesKey("fallback_policy")
        val THINKING_LEVEL = stringPreferencesKey("thinking_level")
        val REASONING_CONFIGS = stringPreferencesKey("reasoning_configs")
        val CUSTOM_THINKING_LEVELS = stringPreferencesKey("custom_thinking_levels")
        val MAX_CONTEXT = intPreferencesKey("max_context_tokens")
        val MAX_OUTPUT = intPreferencesKey("max_output_tokens")
        val MAX_ITERATIONS = intPreferencesKey("max_iterations")
        val RUN_IN_BACKGROUND = booleanPreferencesKey("run_in_background")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val KEEP_ALIVE = booleanPreferencesKey("keep_alive")
        val LIVE_UPDATES_ENABLED = booleanPreferencesKey("live_updates_enabled")
        val PINNED = stringSetPreferencesKey("pinned_sessions")
        val ARCHIVED = stringSetPreferencesKey("archived_sessions")
        val SIDEBAR_PROJECTS = booleanPreferencesKey("sidebar_projects")
        val PINNED_PROJECTS = stringSetPreferencesKey("pinned_projects")
        val ARCHIVED_PROJECTS = stringSetPreferencesKey("archived_projects")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val ACTIVE_MODEL = stringPreferencesKey("active_model")
        val SAME_MODEL_ACROSS_CHATS = booleanPreferencesKey("same_model_across_chats")
        val DISABLED_SKILLS = stringSetPreferencesKey("disabled_skills")
        val WEB_SEARCH_PROVIDER = stringPreferencesKey("web_search_provider")
        val ALLOW_SCREENSHOTS = booleanPreferencesKey("allow_screenshots")
        val PLANNING_MODELS_ENABLED = booleanPreferencesKey("planning_models_enabled")
        val PLANNING_PROVIDER = stringPreferencesKey("planning_provider_id")
        val PLANNING_MODEL = stringPreferencesKey("planning_model")
        val EXECUTION_PROVIDER = stringPreferencesKey("execution_provider_id")
        val EXECUTION_MODEL = stringPreferencesKey("execution_model")
        val PLANNING_PROMO_SEEN = booleanPreferencesKey("planning_promo_seen")
        val FORK_PROMO_SEEN = booleanPreferencesKey("fork_promo_seen")
        val DONATION_NOTICE_DISMISSED = booleanPreferencesKey("donation_notice_dismissed")
        val BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
        val BIOMETRIC_LOCK_TIMEOUT = intPreferencesKey("biometric_lock_timeout_minutes")
        val VOICE_ENGINE = stringPreferencesKey("voice_engine")
        val GROQ_WHISPER_MODEL = stringPreferencesKey("groq_whisper_model")
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val TTS_GROQ_MODEL = stringPreferencesKey("tts_groq_model")
        val TTS_GROQ_VOICE = stringPreferencesKey("tts_groq_voice")
        val TTS_SYSTEM_VOICE = stringPreferencesKey("tts_system_voice")
        val TTS_SPEED = floatPreferencesKey("tts_speed")
        val TTS_ALWAYS_SPEAK = booleanPreferencesKey("tts_always_speak")
        val VOICE_PROMO_SEEN = booleanPreferencesKey("voice_promo_seen")
        val RESUME_LAST_CHAT = booleanPreferencesKey("resume_last_chat")
        val START_SCREEN = stringPreferencesKey("start_screen")
        val CHAT_KEEP_THINKING_COLLAPSED = booleanPreferencesKey("chat_keep_thinking_collapsed")
        val CHAT_IMAGE_FREQUENCY = stringPreferencesKey("chat_image_frequency")
        val CHAT_INSTRUCTIONS = stringPreferencesKey("chat_instructions")
        val CHAT_AGENTS = stringPreferencesKey("chat_agents")
        val CHAT_START = stringPreferencesKey("chat_start")
        val CODE_START = stringPreferencesKey("code_start")
        val SEARCH_DEPTH = stringPreferencesKey("search_depth")
        val CHAT_MEMORY_ENABLED = booleanPreferencesKey("chat_memory_enabled")
        val CODE_MEMORY_ENABLED = booleanPreferencesKey("code_memory_enabled")
        val CHAT_RECALL_CODE_MEMORY = booleanPreferencesKey("chat_recall_code_memory")
        val CODE_RECALL_CHAT_MEMORY = booleanPreferencesKey("code_recall_chat_memory")
        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        val LAST_CONVERSATION_KIND = stringPreferencesKey("last_conversation_kind")
        val SHOW_LIVE_CODE = booleanPreferencesKey("show_live_code")
        val LAST_ACTIVE_SESSION = stringPreferencesKey("last_active_session_id")
        val REPO_MAP_ENABLED = booleanPreferencesKey("repo_map_enabled")
        val LOCAL_MODEL_AGENT_CONTEXT = booleanPreferencesKey("local_model_agent_context")
        val LOCAL_MODEL_EXPERIMENTAL = booleanPreferencesKey("local_model_experimental")
        val CAVEMAN_INSTALLED = booleanPreferencesKey("caveman_installed")
        val CAVEMAN_INTENSITY = stringPreferencesKey("caveman_intensity")
        val CAVEMAN_WENYAN = booleanPreferencesKey("caveman_wenyan")
    }

    /** Device-local dismissal shared by the empty Chat and Code screens. */
    val donationNoticeDismissed: Flow<Boolean> = context.settingsStore.data.map { prefs ->
        prefs[Keys.DONATION_NOTICE_DISMISSED] ?: false
    }

    suspend fun setDonationNoticeDismissed(dismissed: Boolean) {
        context.settingsStore.edit { it[Keys.DONATION_NOTICE_DISMISSED] = dismissed }
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        AppSettings(
            permissionMode = prefs[Keys.PERMISSION_MODE]
                ?.let { runCatching { PermissionMode.valueOf(it) }.getOrNull() }
                ?: PermissionMode.CONFIRM_RISKY,
            subagentFullAccess = prefs[Keys.SUBAGENT_FULL_ACCESS] ?: false,
            backgroundSubagents = prefs[Keys.BACKGROUND_SUBAGENTS] ?: false,
            subagentCompletionNotifications = prefs[Keys.SUBAGENT_COMPLETION_NOTIFICATIONS] ?: true,
            themeMode = prefs[Keys.THEME_MODE]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            colorPalette = ThemePalette.fromStored(prefs[Keys.COLOR_PALETTE]),
            customPalettes = runCatching { kotlinx.serialization.json.Json.decodeFromString<List<CustomThemePalette>>(prefs[Keys.CUSTOM_PALETTES] ?: "[]") }.getOrDefault(emptyList()),
            activeCustomPaletteId = prefs[Keys.ACTIVE_CUSTOM_PALETTE],
            harnessCanvas = runCatching { kotlinx.serialization.json.Json.decodeFromString<HarnessCanvasSettings>(prefs[Keys.HARNESS_CANVAS] ?: "{}") }.getOrDefault(HarnessCanvasSettings()),
            harnessGlassEnabled = prefs[Keys.HARNESS_GLASS_ENABLED] ?: false,
            harnessLiquidGlass = prefs[Keys.HARNESS_LIQUID_GLASS] ?: false,
            harnessGlassTransparency = (prefs[Keys.HARNESS_GLASS_TRANSPARENCY] ?: .5f).let { if (it.isFinite()) it.coerceIn(0f, 1f) else .5f },
            progressBarStyle = ProgressBarStyle.entries.firstOrNull { it.name == prefs[Keys.PROGRESS_BAR_STYLE] }
                ?: ProgressBarStyle.FLAT,
            workspaceLayout = WorkspaceLayoutMode.fromStored(prefs[Keys.WORKSPACE_LAYOUT])
                ?: WorkspaceLayoutMode.AUTO,
            editorWordWrap = prefs[Keys.EDITOR_WORD_WRAP] ?: false,
            editorMatchCase = prefs[Keys.EDITOR_MATCH_CASE] ?: false,
            editorRegexSearch = prefs[Keys.EDITOR_REGEX_SEARCH] ?: false,
            activeProviderId = prefs[Keys.ACTIVE_PROVIDER] ?: com.androidharness.app.llm.HarnessProvider.ID,
            providerOrder = ProviderListPreferences.decodeOrder(prefs[Keys.PROVIDER_ORDER]),
            disabledProviderIds = prefs[Keys.DISABLED_PROVIDERS] ?: emptySet(),
            fallbackPolicy = com.androidharness.app.llm.FallbackPolicy.decode(prefs[Keys.FALLBACK_POLICY]),
            thinkingLevel = prefs[Keys.THINKING_LEVEL]
                ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
                ?: ThinkingLevel.OFF,
            reasoningConfigs = decodeReasoningConfigs(prefs[Keys.REASONING_CONFIGS]),
            customThinkingLevels = decodeCustomLevels(prefs[Keys.CUSTOM_THINKING_LEVELS]),
            maxContextTokens = prefs[Keys.MAX_CONTEXT] ?: AppSettings.DEFAULT_MAX_CONTEXT,
            maxOutputTokens = prefs[Keys.MAX_OUTPUT] ?: AppSettings.DEFAULT_MAX_OUTPUT,
            maxIterations = prefs[Keys.MAX_ITERATIONS] ?: AppSettings.DEFAULT_MAX_ITERATIONS,
            runInBackground = prefs[Keys.RUN_IN_BACKGROUND] ?: true,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            keepAlive = prefs[Keys.KEEP_ALIVE] ?: true,
            liveUpdatesEnabled = prefs[Keys.LIVE_UPDATES_ENABLED] ?: true,
            pinnedSessions = prefs[Keys.PINNED] ?: emptySet(),
            archivedSessions = prefs[Keys.ARCHIVED] ?: emptySet(),
            sidebarProjects = prefs[Keys.SIDEBAR_PROJECTS] ?: false,
            pinnedProjects = prefs[Keys.PINNED_PROJECTS] ?: emptySet(),
            archivedProjects = prefs[Keys.ARCHIVED_PROJECTS] ?: emptySet(),
            onboardingDone = prefs[Keys.ONBOARDING_DONE] ?: false,
            activeModel = prefs[Keys.ACTIVE_MODEL],
            sameModelAcrossChats = prefs[Keys.SAME_MODEL_ACROSS_CHATS] ?: true,
            disabledSkills = prefs[Keys.DISABLED_SKILLS] ?: emptySet(),
            webSearchProvider = prefs[Keys.WEB_SEARCH_PROVIDER]
                ?.takeIf { it in WEB_SEARCH_PROVIDERS }
                ?: "keyless",
            allowScreenshots = prefs[Keys.ALLOW_SCREENSHOTS] ?: true,
            planningModelsEnabled = prefs[Keys.PLANNING_MODELS_ENABLED] ?: false,
            planningProviderId = prefs[Keys.PLANNING_PROVIDER],
            planningModel = prefs[Keys.PLANNING_MODEL],
            executionProviderId = prefs[Keys.EXECUTION_PROVIDER],
            executionModel = prefs[Keys.EXECUTION_MODEL],
            subagentProviderId = prefs[Keys.SUBAGENT_PROVIDER],
            subagentModel = prefs[Keys.SUBAGENT_MODEL],
            planningModelsPromoSeen = prefs[Keys.PLANNING_PROMO_SEEN] ?: false,
            forkPromoSeen = prefs[Keys.FORK_PROMO_SEEN] ?: false,
            biometricLockEnabled = prefs[Keys.BIOMETRIC_LOCK_ENABLED] ?: false,
            biometricLockTimeoutMinutes = prefs[Keys.BIOMETRIC_LOCK_TIMEOUT] ?: 0,
            voiceEngine = prefs[Keys.VOICE_ENGINE] ?: AppSettings.VOICE_ENGINE_INBUILT,
            groqWhisperModel = prefs[Keys.GROQ_WHISPER_MODEL] ?: AppSettings.GROQ_MODEL_WHISPER_V3,
            ttsEngine = prefs[Keys.TTS_ENGINE] ?: AppSettings.TTS_ENGINE_SYSTEM,
            ttsGroqModel = prefs[Keys.TTS_GROQ_MODEL] ?: AppSettings.DEFAULT_TTS_GROQ_MODEL,
            ttsGroqVoice = prefs[Keys.TTS_GROQ_VOICE] ?: AppSettings.DEFAULT_TTS_GROQ_VOICE,
            ttsSystemVoice = prefs[Keys.TTS_SYSTEM_VOICE]?.ifBlank { null },
            ttsSpeed = prefs[Keys.TTS_SPEED] ?: 1f,
            ttsAlwaysSpeak = prefs[Keys.TTS_ALWAYS_SPEAK] ?: false,
            voicePromoSeen = prefs[Keys.VOICE_PROMO_SEEN] ?: false,
            resumeLastChat = prefs[Keys.RESUME_LAST_CHAT] ?: true,
            startScreen = StartScreen.fromStored(prefs[Keys.START_SCREEN]),
            chatStart = ChatStartMode.fromStored(prefs[Keys.CHAT_START]),
            chatKeepThinkingCollapsed = prefs[Keys.CHAT_KEEP_THINKING_COLLAPSED] ?: true,
            chatInstructions = prefs[Keys.CHAT_INSTRUCTIONS].orEmpty(),
            chatAgents = ChatAgentProfile.decodeList(prefs[Keys.CHAT_AGENTS]),
            chatImageFrequency = prefs[Keys.CHAT_IMAGE_FREQUENCY]?.let {
                runCatching { ImageFrequency.valueOf(it) }.getOrNull()
            } ?: ImageFrequency.DEFAULT,
            codeStart = prefs[Keys.CODE_START]?.let { CodeStartMode.fromStored(it) }
                ?: if (prefs[Keys.RESUME_LAST_CHAT] ?: true) CodeStartMode.LAST_CODE else CodeStartMode.NEW_CODE,
            searchDepth = SearchDepth.fromStored(prefs[Keys.SEARCH_DEPTH]),
            chatMemoryEnabled = prefs[Keys.CHAT_MEMORY_ENABLED] ?: true,
            codeMemoryEnabled = prefs[Keys.CODE_MEMORY_ENABLED] ?: true,
            chatRecallCodeMemory = prefs[Keys.CHAT_RECALL_CODE_MEMORY] ?: false,
            codeRecallChatMemory = prefs[Keys.CODE_RECALL_CHAT_MEMORY] ?: false,
            memoryEnabled = prefs[Keys.MEMORY_ENABLED] ?: true,
            lastConversationKind = ConversationKind.entries.firstOrNull { it.name == prefs[Keys.LAST_CONVERSATION_KIND] } ?: ConversationKind.CODE,
            showLiveCode = prefs[Keys.SHOW_LIVE_CODE] ?: true,
            lastActiveSessionId = prefs[Keys.LAST_ACTIVE_SESSION],
            repoMapEnabled = prefs[Keys.REPO_MAP_ENABLED] ?: true,
            localModelAgentContext = prefs[Keys.LOCAL_MODEL_AGENT_CONTEXT] ?: false,
            localModelExperimental = prefs[Keys.LOCAL_MODEL_EXPERIMENTAL] ?: false,
            cavemanInstalled = prefs[Keys.CAVEMAN_INSTALLED] ?: false,
            cavemanIntensity = prefs[Keys.CAVEMAN_INTENSITY]?.let {
                runCatching { com.androidharness.app.caveman.CavemanIntensity.valueOf(it) }.getOrNull()
            } ?: com.androidharness.app.caveman.CavemanIntensity.OFF,
            cavemanWenyan = prefs[Keys.CAVEMAN_WENYAN] ?: false,
        )
    }

    suspend fun restorePortable(s: AppSettings) {
        context.settingsStore.edit { p ->
            p[Keys.THEME_MODE] = s.themeMode.name
            p[Keys.DYNAMIC_COLOR] = s.dynamicColor
            p[Keys.COLOR_PALETTE] = s.colorPalette.name
            p[Keys.CUSTOM_PALETTES] = kotlinx.serialization.json.Json.encodeToString(s.customPalettes)
            s.activeCustomPaletteId?.let { p[Keys.ACTIVE_CUSTOM_PALETTE] = it } ?: p.remove(Keys.ACTIVE_CUSTOM_PALETTE)
            p[Keys.HARNESS_CANVAS] = kotlinx.serialization.json.Json.encodeToString(s.harnessCanvas)
            p[Keys.HARNESS_GLASS_ENABLED] = s.harnessGlassEnabled
            p[Keys.HARNESS_LIQUID_GLASS] = s.harnessLiquidGlass
            p[Keys.HARNESS_GLASS_TRANSPARENCY] = s.harnessGlassTransparency.let { if (it.isFinite()) it.coerceIn(0f, 1f) else .5f }
            p[Keys.PROGRESS_BAR_STYLE] = s.progressBarStyle.name
            p[Keys.WORKSPACE_LAYOUT] = s.workspaceLayout.name
            p[Keys.EDITOR_WORD_WRAP] = s.editorWordWrap
            p[Keys.EDITOR_MATCH_CASE] = s.editorMatchCase
            p[Keys.EDITOR_REGEX_SEARCH] = s.editorRegexSearch
            p[Keys.PROVIDER_ORDER] = ProviderListPreferences.encodeOrder(s.providerOrder)
            p[Keys.DISABLED_PROVIDERS] = s.disabledProviderIds
            p[Keys.FALLBACK_POLICY] = com.androidharness.app.llm.FallbackPolicy.encode(s.fallbackPolicy)
            p[Keys.THINKING_LEVEL] = s.thinkingLevel.name
            p[Keys.REASONING_CONFIGS] = kotlinx.serialization.json.Json.encodeToString(s.reasoningConfigs)
            p[Keys.CUSTOM_THINKING_LEVELS] = kotlinx.serialization.json.Json.encodeToString(s.customThinkingLevels)
            p[Keys.MAX_CONTEXT] = s.maxContextTokens
            p[Keys.MAX_OUTPUT] = s.maxOutputTokens
            p[Keys.MAX_ITERATIONS] = s.maxIterations
            p[Keys.RUN_IN_BACKGROUND] = s.runInBackground
            p[Keys.NOTIFICATIONS_ENABLED] = s.notificationsEnabled
            p[Keys.KEEP_ALIVE] = s.keepAlive
            p[Keys.LIVE_UPDATES_ENABLED] = s.liveUpdatesEnabled
            p[Keys.SUBAGENT_FULL_ACCESS] = s.subagentFullAccess
            p[Keys.BACKGROUND_SUBAGENTS] = s.backgroundSubagents
            p[Keys.SUBAGENT_COMPLETION_NOTIFICATIONS] = s.subagentCompletionNotifications
            p[Keys.DISABLED_SKILLS] = s.disabledSkills
            p[Keys.WEB_SEARCH_PROVIDER] = s.webSearchProvider
            p[Keys.VOICE_ENGINE] = s.voiceEngine
            p[Keys.GROQ_WHISPER_MODEL] = s.groqWhisperModel
            p[Keys.TTS_ENGINE] = s.ttsEngine
            p[Keys.TTS_GROQ_MODEL] = s.ttsGroqModel
            p[Keys.TTS_GROQ_VOICE] = s.ttsGroqVoice
            p[Keys.TTS_SYSTEM_VOICE] = s.ttsSystemVoice.orEmpty()
            p[Keys.TTS_SPEED] = s.ttsSpeed
            p[Keys.TTS_ALWAYS_SPEAK] = s.ttsAlwaysSpeak
            p[Keys.RESUME_LAST_CHAT] = s.resumeLastChat
            p[Keys.START_SCREEN] = s.startScreen.name
            p[Keys.CHAT_START] = s.chatStart.name
            p[Keys.CHAT_KEEP_THINKING_COLLAPSED] = s.chatKeepThinkingCollapsed
            p[Keys.CHAT_IMAGE_FREQUENCY] = s.chatImageFrequency.name
            p[Keys.CHAT_INSTRUCTIONS] = s.chatInstructions.take(ConversationConfig.MAX_INSTRUCTIONS_CHARS)
            p[Keys.CHAT_AGENTS] = ChatAgentProfile.encodeList(s.chatAgents)
            p[Keys.CODE_START] = s.codeStart.name
            p[Keys.SEARCH_DEPTH] = s.searchDepth.name
            p[Keys.CHAT_MEMORY_ENABLED] = s.chatMemoryEnabled
            p[Keys.CODE_MEMORY_ENABLED] = s.codeMemoryEnabled
            p[Keys.CHAT_RECALL_CODE_MEMORY] = s.chatRecallCodeMemory
            p[Keys.CODE_RECALL_CHAT_MEMORY] = s.codeRecallChatMemory
            p[Keys.MEMORY_ENABLED] = s.memoryEnabled
            p[Keys.SHOW_LIVE_CODE] = s.showLiveCode
            p[Keys.REPO_MAP_ENABLED] = s.repoMapEnabled
            p[Keys.LOCAL_MODEL_AGENT_CONTEXT] = s.localModelAgentContext
            p[Keys.LOCAL_MODEL_EXPERIMENTAL] = s.localModelExperimental
            p[Keys.CAVEMAN_INSTALLED] = s.cavemanInstalled
            p[Keys.CAVEMAN_INTENSITY] = s.cavemanIntensity.name
            p[Keys.CAVEMAN_WENYAN] = s.cavemanWenyan
            p[Keys.PLANNING_MODELS_ENABLED] = s.planningModelsEnabled
            p[Keys.SAME_MODEL_ACROSS_CHATS] = s.sameModelAcrossChats
            listOf(Keys.ACTIVE_PROVIDER to s.activeProviderId, Keys.ACTIVE_MODEL to s.activeModel,
                Keys.PLANNING_PROVIDER to s.planningProviderId, Keys.PLANNING_MODEL to s.planningModel,
                Keys.EXECUTION_PROVIDER to s.executionProviderId, Keys.EXECUTION_MODEL to s.executionModel,
                Keys.SUBAGENT_PROVIDER to s.subagentProviderId, Keys.SUBAGENT_MODEL to s.subagentModel
            ).forEach { (key, value) -> if (value == null) p.remove(key) else p[key] = value }
        }
    }

    suspend fun setWebSearchProvider(provider: String) {
        if (provider !in WEB_SEARCH_PROVIDERS) return
        context.settingsStore.edit { it[Keys.WEB_SEARCH_PROVIDER] = provider }
    }

    suspend fun setProviderEnabled(id: String, enabled: Boolean) {
        context.settingsStore.edit { prefs ->
            val disabled = prefs[Keys.DISABLED_PROVIDERS].orEmpty()
            prefs[Keys.DISABLED_PROVIDERS] = if (enabled) disabled - id else disabled + id
        }
    }

    suspend fun moveProvider(id: String, direction: Int, availableIds: List<String>, visibleIds: List<String>) {
        context.settingsStore.edit { prefs ->
            val order = ProviderListPreferences.move(
                ProviderListPreferences.decodeOrder(prefs[Keys.PROVIDER_ORDER]), availableIds, visibleIds, id, direction,
            )
            prefs[Keys.PROVIDER_ORDER] = ProviderListPreferences.encodeOrder(order)
        }
    }

    suspend fun setPermissionMode(mode: PermissionMode) {
        context.settingsStore.edit { it[Keys.PERMISSION_MODE] = mode.name }
    }

    suspend fun setSubagentFullAccess(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.SUBAGENT_FULL_ACCESS] = enabled }
    }

    suspend fun setBackgroundSubagents(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.BACKGROUND_SUBAGENTS] = enabled }
    }

    suspend fun setSubagentCompletionNotifications(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.SUBAGENT_COMPLETION_NOTIFICATIONS] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsStore.edit {
            it[Keys.DYNAMIC_COLOR] = enabled
            if (enabled) {
                val canvas = canvasFrom(it[Keys.HARNESS_CANVAS])
                it[Keys.HARNESS_CANVAS] = kotlinx.serialization.json.Json.encodeToString(canvas.copy(adaptColors = false))
            }
        }
    }

    suspend fun setColorPalette(palette: ThemePalette) {
        context.settingsStore.edit {
            it[Keys.COLOR_PALETTE] = palette.name
            it[Keys.DYNAMIC_COLOR] = false
            it.remove(Keys.ACTIVE_CUSTOM_PALETTE)
            it[Keys.HARNESS_CANVAS] = kotlinx.serialization.json.Json.encodeToString(canvasFrom(it[Keys.HARNESS_CANVAS]).copy(adaptColors = false))
        }
    }

    private fun canvasFrom(raw: String?) = runCatching {
        kotlinx.serialization.json.Json.decodeFromString<HarnessCanvasSettings>(raw ?: "{}")
    }.getOrDefault(HarnessCanvasSettings())

    suspend fun saveCustomPalette(palette: CustomThemePalette) {
        require(palette.valid())
        context.settingsStore.edit { prefs ->
            val existing = runCatching { kotlinx.serialization.json.Json.decodeFromString<List<CustomThemePalette>>(prefs[Keys.CUSTOM_PALETTES] ?: "[]") }.getOrDefault(emptyList())
            require(existing.size < 24 || existing.any { it.id == palette.id })
            val saved = palette.copy(name = palette.name.trim())
            prefs[Keys.CUSTOM_PALETTES] = kotlinx.serialization.json.Json.encodeToString(existing.filterNot { it.id == saved.id } + saved)
            prefs[Keys.ACTIVE_CUSTOM_PALETTE] = saved.id
            prefs[Keys.DYNAMIC_COLOR] = false
            prefs[Keys.HARNESS_CANVAS] = kotlinx.serialization.json.Json.encodeToString(canvasFrom(prefs[Keys.HARNESS_CANVAS]).copy(adaptColors = false))
        }
    }

    suspend fun selectCustomPalette(id: String) {
        context.settingsStore.edit { prefs ->
            val saved = runCatching { kotlinx.serialization.json.Json.decodeFromString<List<CustomThemePalette>>(prefs[Keys.CUSTOM_PALETTES] ?: "[]") }.getOrDefault(emptyList())
            require(saved.any { it.id == id })
            prefs[Keys.ACTIVE_CUSTOM_PALETTE] = id
            prefs[Keys.DYNAMIC_COLOR] = false
            prefs[Keys.HARNESS_CANVAS] = kotlinx.serialization.json.Json.encodeToString(canvasFrom(prefs[Keys.HARNESS_CANVAS]).copy(adaptColors = false))
        }
    }

    suspend fun removeCustomPalette(id: String) {
        context.settingsStore.edit { prefs ->
            val saved = runCatching { kotlinx.serialization.json.Json.decodeFromString<List<CustomThemePalette>>(prefs[Keys.CUSTOM_PALETTES] ?: "[]") }.getOrDefault(emptyList())
            prefs[Keys.CUSTOM_PALETTES] = kotlinx.serialization.json.Json.encodeToString(saved.filterNot { it.id == id })
            if (prefs[Keys.ACTIVE_CUSTOM_PALETTE] == id) prefs.remove(Keys.ACTIVE_CUSTOM_PALETTE)
        }
    }

    suspend fun setHarnessGlassEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.HARNESS_GLASS_ENABLED] = enabled }
    }

    suspend fun setHarnessLiquidGlass(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.HARNESS_LIQUID_GLASS] = enabled }
    }

    suspend fun setHarnessGlassTransparency(value: Float) {
        if (!value.isFinite()) return
        context.settingsStore.edit { it[Keys.HARNESS_GLASS_TRANSPARENCY] = value.coerceIn(0f, 1f) }
    }

    suspend fun updateHarnessCanvas(transform: (HarnessCanvasSettings) -> HarnessCanvasSettings) {
        context.settingsStore.edit { prefs ->
            val canvas = transform(canvasFrom(prefs[Keys.HARNESS_CANVAS])).let {
                it.copy(dim = if (it.dim.isFinite()) it.dim.coerceIn(0f, 1f) else .65f)
            }
            prefs[Keys.HARNESS_CANVAS] = kotlinx.serialization.json.Json.encodeToString(canvas)
            if (canvas.adaptColors) {
                prefs[Keys.DYNAMIC_COLOR] = false
            }
        }
    }

    suspend fun setProgressBarStyle(style: ProgressBarStyle) {
        context.settingsStore.edit { it[Keys.PROGRESS_BAR_STYLE] = style.name }
    }

    suspend fun setWorkspaceLayout(mode: WorkspaceLayoutMode) {
        context.settingsStore.edit { it[Keys.WORKSPACE_LAYOUT] = mode.name }
    }

    suspend fun setEditorWordWrap(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.EDITOR_WORD_WRAP] = enabled }
    }

    suspend fun setEditorSearchOptions(matchCase: Boolean? = null, regex: Boolean? = null) {
        context.settingsStore.edit {
            if (matchCase != null) it[Keys.EDITOR_MATCH_CASE] = matchCase
            if (regex != null) it[Keys.EDITOR_REGEX_SEARCH] = regex
        }
    }

    suspend fun setAllowScreenshots(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.ALLOW_SCREENSHOTS] = enabled }
    }

    suspend fun setPlanningModelsEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.PLANNING_MODELS_ENABLED] = enabled }
    }

    suspend fun setPlanningModelsPromoSeen(seen: Boolean) {
        context.settingsStore.edit { it[Keys.PLANNING_PROMO_SEEN] = seen }
    }

    suspend fun setForkPromoSeen(seen: Boolean) {
        context.settingsStore.edit { it[Keys.FORK_PROMO_SEEN] = seen }
    }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.BIOMETRIC_LOCK_ENABLED] = enabled }
    }

    suspend fun setBiometricLockTimeout(minutes: Int) {
        context.settingsStore.edit { it[Keys.BIOMETRIC_LOCK_TIMEOUT] = minutes }
    }

    suspend fun setVoiceEngine(engine: String) {
        context.settingsStore.edit { it[Keys.VOICE_ENGINE] = engine }
    }

    suspend fun setGroqWhisperModel(model: String) {
        context.settingsStore.edit { it[Keys.GROQ_WHISPER_MODEL] = model }
    }

    suspend fun setTtsEngine(engine: String) {
        context.settingsStore.edit { it[Keys.TTS_ENGINE] = engine }
    }

    suspend fun setTtsGroqModel(model: String) {
        context.settingsStore.edit { it[Keys.TTS_GROQ_MODEL] = model }
    }

    suspend fun setTtsGroqVoice(voice: String) {
        context.settingsStore.edit { it[Keys.TTS_GROQ_VOICE] = voice }
    }

    suspend fun setTtsSystemVoice(voice: String?) {
        context.settingsStore.edit { it[Keys.TTS_SYSTEM_VOICE] = voice.orEmpty() }
    }

    suspend fun setTtsSpeed(speed: Float) {
        context.settingsStore.edit { it[Keys.TTS_SPEED] = speed.coerceIn(0.5f, 2f) }
    }

    suspend fun setTtsAlwaysSpeak(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.TTS_ALWAYS_SPEAK] = enabled }
    }

    suspend fun setVoicePromoSeen(seen: Boolean) {
        context.settingsStore.edit { it[Keys.VOICE_PROMO_SEEN] = seen }
    }

    suspend fun setPlanningModel(providerId: String?, model: String?) {
        context.settingsStore.edit { prefs ->
            if (providerId == null) prefs.remove(Keys.PLANNING_PROVIDER)
            else prefs[Keys.PLANNING_PROVIDER] = providerId
            if (model.isNullOrBlank()) prefs.remove(Keys.PLANNING_MODEL)
            else prefs[Keys.PLANNING_MODEL] = model
        }
    }

    suspend fun setExecutionModel(providerId: String?, model: String?) {
        context.settingsStore.edit { prefs ->
            if (providerId == null) prefs.remove(Keys.EXECUTION_PROVIDER)
            else prefs[Keys.EXECUTION_PROVIDER] = providerId
            if (model.isNullOrBlank()) prefs.remove(Keys.EXECUTION_MODEL)
            else prefs[Keys.EXECUTION_MODEL] = model
        }
    }

    suspend fun setSubagentModel(providerId: String?, model: String?) {
        context.settingsStore.edit { prefs ->
            if (providerId.isNullOrBlank()) {
                prefs.remove(Keys.SUBAGENT_PROVIDER)
                prefs.remove(Keys.SUBAGENT_MODEL)
            } else {
                prefs[Keys.SUBAGENT_PROVIDER] = providerId
                if (model.isNullOrBlank()) prefs.remove(Keys.SUBAGENT_MODEL)
                else prefs[Keys.SUBAGENT_MODEL] = model
            }
        }
    }

    suspend fun setActiveProvider(id: String?) {
        context.settingsStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.ACTIVE_PROVIDER)
            else prefs[Keys.ACTIVE_PROVIDER] = id
        }
    }

    suspend fun setActiveSelection(providerId: String, model: String?) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.ACTIVE_PROVIDER] = providerId
            if (model.isNullOrBlank()) prefs.remove(Keys.ACTIVE_MODEL) else prefs[Keys.ACTIVE_MODEL] = model
        }
    }

    suspend fun setModelSelectionScope(shared: Boolean, selection: ChatModelSelection? = null) {
        context.settingsStore.edit { prefs ->
            if (selection != null) {
                listOf(Keys.ACTIVE_PROVIDER to selection.active?.providerId, Keys.ACTIVE_MODEL to selection.active?.model,
                    Keys.PLANNING_PROVIDER to selection.planning?.providerId, Keys.PLANNING_MODEL to selection.planning?.model,
                    Keys.EXECUTION_PROVIDER to selection.execution?.providerId, Keys.EXECUTION_MODEL to selection.execution?.model
                ).forEach { (key, value) -> if (value == null) prefs.remove(key) else prefs[key] = value }
                prefs[Keys.THINKING_LEVEL] = selection.thinking.name
                prefs[Keys.CUSTOM_THINKING_LEVELS] = kotlinx.serialization.json.Json.encodeToString(selection.customThinkingLevels)
            }
            prefs[Keys.SAME_MODEL_ACROSS_CHATS] = shared
        }
    }

    /** A background switch must not overwrite a newer choice made in the model picker. */
    suspend fun switchActiveSelection(from: com.androidharness.app.llm.ProviderConfig, to: com.androidharness.app.llm.ProviderConfig) {
        context.settingsStore.edit { prefs ->
            if ((prefs[Keys.SAME_MODEL_ACROSS_CHATS] ?: true) && prefs[Keys.ACTIVE_PROVIDER] == from.id && (prefs[Keys.ACTIVE_MODEL] == null || prefs[Keys.ACTIVE_MODEL] == from.model)) {
                prefs[Keys.ACTIVE_PROVIDER] = to.id
                prefs[Keys.ACTIVE_MODEL] = to.model
            }
        }
    }

    suspend fun clearMissingLocalProviderSelections(availableIds: Set<String>) {
        context.settingsStore.edit { prefs ->
            listOf(Keys.ACTIVE_PROVIDER to Keys.ACTIVE_MODEL,
                Keys.PLANNING_PROVIDER to Keys.PLANNING_MODEL,
                Keys.EXECUTION_PROVIDER to Keys.EXECUTION_MODEL,
                Keys.SUBAGENT_PROVIDER to Keys.SUBAGENT_MODEL).forEach { (providerKey, modelKey) ->
                val id = prefs[providerKey]
                if (id != null && (com.androidharness.app.local.LocalModelCatalog.isLocal(id) || com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(id)) && id !in availableIds) {
                    if (providerKey == Keys.ACTIVE_PROVIDER) {
                        prefs[providerKey] = com.androidharness.app.llm.HarnessProvider.ID
                    } else prefs.remove(providerKey)
                    prefs.remove(modelKey)
                }
            }
        }
    }

    suspend fun setThinkingLevel(level: ThinkingLevel) {
        context.settingsStore.edit { it[Keys.THINKING_LEVEL] = level.name }
    }

    suspend fun setMaxContextTokens(tokens: Int) {
        context.settingsStore.edit { it[Keys.MAX_CONTEXT] = tokens }
    }

    suspend fun setMaxOutputTokens(tokens: Int) {
        context.settingsStore.edit { it[Keys.MAX_OUTPUT] = tokens }
    }

    suspend fun setMaxIterations(iterations: Int) {
        context.settingsStore.edit { it[Keys.MAX_ITERATIONS] = iterations }
    }

    suspend fun setRunInBackground(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.RUN_IN_BACKGROUND] = enabled }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setKeepAlive(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.KEEP_ALIVE] = enabled }
    }

    suspend fun setLiveUpdatesEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.LIVE_UPDATES_ENABLED] = enabled }
    }

    suspend fun setPinned(sessionId: String, pinned: Boolean) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.PINNED] ?: emptySet()
            prefs[Keys.PINNED] = if (pinned) current + sessionId else current - sessionId
        }
    }

    suspend fun setSidebarProjects(projects: Boolean) {
        context.settingsStore.edit { it[Keys.SIDEBAR_PROJECTS] = projects }
    }

    suspend fun setProjectPinned(projectId: String, pinned: Boolean) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.PINNED_PROJECTS] ?: emptySet()
            prefs[Keys.PINNED_PROJECTS] = if (pinned) current + projectId else current - projectId
        }
    }

    suspend fun setProjectArchived(projectId: String, archived: Boolean) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.ARCHIVED_PROJECTS] ?: emptySet()
            prefs[Keys.ARCHIVED_PROJECTS] = if (archived) current + projectId else current - projectId
        }
    }

    suspend fun removeProjectReferences(projectId: String, chatIds: Set<String>) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.PINNED_PROJECTS] = (prefs[Keys.PINNED_PROJECTS] ?: emptySet()) - projectId
            prefs[Keys.ARCHIVED_PROJECTS] = (prefs[Keys.ARCHIVED_PROJECTS] ?: emptySet()) - projectId
            prefs[Keys.PINNED] = (prefs[Keys.PINNED] ?: emptySet()) - chatIds
            prefs[Keys.ARCHIVED] = (prefs[Keys.ARCHIVED] ?: emptySet()) - chatIds
            if (prefs[Keys.LAST_ACTIVE_SESSION] in chatIds) prefs.remove(Keys.LAST_ACTIVE_SESSION)
        }
    }

    suspend fun setArchived(sessionId: String, archived: Boolean) = setArchived(setOf(sessionId), archived)

    suspend fun setArchived(sessionIds: Set<String>, archived: Boolean) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.ARCHIVED] ?: emptySet()
            prefs[Keys.ARCHIVED] = if (archived) current + sessionIds else current - sessionIds
        }
    }

    suspend fun restoreArchived(sessionIds: Set<String>, previouslyArchived: Set<String>) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.ARCHIVED] = ((prefs[Keys.ARCHIVED] ?: emptySet()) - sessionIds) + (previouslyArchived intersect sessionIds)
        }
    }

    suspend fun forgetSidebarSessions(sessionIds: Set<String>) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.PINNED] = (prefs[Keys.PINNED] ?: emptySet()) - sessionIds
            prefs[Keys.ARCHIVED] = (prefs[Keys.ARCHIVED] ?: emptySet()) - sessionIds
            if (prefs[Keys.LAST_ACTIVE_SESSION] in sessionIds) prefs.remove(Keys.LAST_ACTIVE_SESSION)
        }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        context.settingsStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    suspend fun setRepoMapEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.REPO_MAP_ENABLED] = enabled }
    }

    suspend fun setShowLiveCode(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.SHOW_LIVE_CODE] = enabled }
    }

    suspend fun setCavemanInstalled(installed: Boolean) {
        context.settingsStore.edit {
            it[Keys.CAVEMAN_INSTALLED] = installed
            it[Keys.CAVEMAN_INTENSITY] = com.androidharness.app.caveman.CavemanIntensity.OFF.name
        }
    }

    suspend fun setLocalModelAgentContext(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.LOCAL_MODEL_AGENT_CONTEXT] = enabled }
    }

    suspend fun setLocalModelExperimental(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.LOCAL_MODEL_EXPERIMENTAL] = enabled }
    }

    suspend fun setCavemanIntensity(intensity: com.androidharness.app.caveman.CavemanIntensity) {
        context.settingsStore.edit {
            if (it[Keys.CAVEMAN_INSTALLED] == true) it[Keys.CAVEMAN_INTENSITY] = intensity.name
        }
    }

    suspend fun setCavemanWenyan(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.CAVEMAN_WENYAN] = enabled }
    }

    suspend fun setSkillEnabled(name: String, enabled: Boolean) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.DISABLED_SKILLS] ?: emptySet()
            prefs[Keys.DISABLED_SKILLS] = if (enabled) current - name else current + name
        }
    }

    /** Model override for the ACTIVE provider; null falls back to its saved default. */
    suspend fun setActiveModel(model: String?) {
        context.settingsStore.edit { prefs ->
            if (model.isNullOrBlank()) prefs.remove(Keys.ACTIVE_MODEL)
            else prefs[Keys.ACTIVE_MODEL] = model
        }
    }

    suspend fun setStartScreen(value: StartScreen) {
        context.settingsStore.edit { it[Keys.START_SCREEN] = value.name }
    }

    suspend fun setChatKeepThinkingCollapsed(value: Boolean) {
        context.settingsStore.edit { it[Keys.CHAT_KEEP_THINKING_COLLAPSED] = value }
    }

    suspend fun setChatImageFrequency(value: ImageFrequency) {
        context.settingsStore.edit { it[Keys.CHAT_IMAGE_FREQUENCY] = value.name }
    }

    suspend fun setChatInstructions(value: String) {
        context.settingsStore.edit { it[Keys.CHAT_INSTRUCTIONS] = value.trim().take(ConversationConfig.MAX_INSTRUCTIONS_CHARS) }
    }

    suspend fun saveChatAgent(profile: ChatAgentProfile) {
        profile.validated()
        context.settingsStore.edit { prefs ->
            val current = ChatAgentProfile.decodeList(prefs[Keys.CHAT_AGENTS])
            prefs[Keys.CHAT_AGENTS] = ChatAgentProfile.encodeList(current.filterNot { it.id == profile.id } + profile.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deleteChatAgent(id: String) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.CHAT_AGENTS] = ChatAgentProfile.encodeList(ChatAgentProfile.decodeList(prefs[Keys.CHAT_AGENTS]).filterNot { it.id == id })
        }
    }

    suspend fun setChatStart(value: ChatStartMode) {
        context.settingsStore.edit { it[Keys.CHAT_START] = value.name }
    }

    suspend fun setCodeStart(value: CodeStartMode) {
        context.settingsStore.edit {
            it[Keys.CODE_START] = value.name
            it[Keys.RESUME_LAST_CHAT] = value == CodeStartMode.LAST_CODE
        }
    }

    suspend fun setSectionMemoryEnabled(section: MemorySection, enabled: Boolean) {
        context.settingsStore.edit { it[if (section == MemorySection.CHAT) Keys.CHAT_MEMORY_ENABLED else Keys.CODE_MEMORY_ENABLED] = enabled }
    }

    suspend fun setCrossSectionMemory(section: MemorySection, enabled: Boolean) {
        context.settingsStore.edit { it[if (section == MemorySection.CHAT) Keys.CHAT_RECALL_CODE_MEMORY else Keys.CODE_RECALL_CHAT_MEMORY] = enabled }
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.MEMORY_ENABLED] = enabled }
    }

    suspend fun setSearchDepth(depth: SearchDepth) {
        context.settingsStore.edit { it[Keys.SEARCH_DEPTH] = depth.name }
    }

    suspend fun setLastConversationKind(value: ConversationKind) {
        context.settingsStore.edit { it[Keys.LAST_CONVERSATION_KIND] = value.name }
    }

    suspend fun setResumeLastChat(enabled: Boolean) {
        context.settingsStore.edit {
            it[Keys.RESUME_LAST_CHAT] = enabled
            it[Keys.CODE_START] = (if (enabled) CodeStartMode.LAST_CODE else CodeStartMode.NEW_CODE).name
        }
    }

    suspend fun setLastActiveSessionId(sessionId: String?) {
        context.settingsStore.edit { prefs ->
            if (sessionId.isNullOrBlank()) prefs.remove(Keys.LAST_ACTIVE_SESSION)
            else prefs[Keys.LAST_ACTIVE_SESSION] = sessionId
        }
    }
}
