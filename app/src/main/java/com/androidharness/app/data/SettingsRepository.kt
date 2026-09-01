package com.androidharness.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

data class AppSettings(
    val permissionMode: PermissionMode = PermissionMode.CONFIRM_RISKY,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val activeProviderId: String? = null,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    val maxContextTokens: Int = DEFAULT_MAX_CONTEXT,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT,
    /** Tool-call iterations per run. 0 = unlimited. */
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    /** Hold a wakelock while a run is active so runs survive screen-off. */
    val keepAlive: Boolean = true,
    /** Drawer: pinned / archived session ids (DataStore, no DB migration). */
    val pinnedSessions: Set<String> = emptySet(),
    val archivedSessions: Set<String> = emptySet(),
    /** First-run setup was finished or skipped; stops the setup gate. */
    val onboardingDone: Boolean = false,
    /**
     * Model selected from the active provider's catalog. Null = use the
     * provider entry's saved default model.
     */
    val activeModel: String? = null,
    /** Skill names hidden from the catalog and slash picker. */
    val disabledSkills: Set<String> = emptySet(),
    /** web_search backend: "keyless" (default) | "brave" | "tavily". */
    val webSearchProvider: String = "keyless",
    /**
     * Screenshots are allowed outside credential screens. Off (default) blocks
     * them app wide; screens that show keys and tokens always block.
     */
    val allowScreenshots: Boolean = false,
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
    /** Voice speech-to-text engine: "inbuilt" (native Android recognizer) or "groq" (Groq Whisper API). */
    val voiceEngine: String = "inbuilt",
    /** Groq Whisper model: "whisper-large-v3" (default) or "whisper-large-v3-turbo". */
    val groqWhisperModel: String = "whisper-large-v3",
    /** The one-time voice configuration promo dialog was dismissed. */
    val voicePromoSeen: Boolean = false,
    /** Resume the last opened chat session when app launches. */
    val resumeLastChat: Boolean = true,
    /** ID of the last active chat session. */
    val lastActiveSessionId: String? = null,
    /** Generate and inject compact codebase symbol index (Repo map) into agent context. */
    val repoMapEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_MAX_CONTEXT = 1_000_000
        const val DEFAULT_MAX_OUTPUT = 32_768
        const val DEFAULT_MAX_ITERATIONS = 0 // unlimited
        const val VOICE_ENGINE_INBUILT = "inbuilt"
        const val VOICE_ENGINE_GROQ = "groq"
        const val GROQ_MODEL_WHISPER_V3 = "whisper-large-v3"
        const val GROQ_MODEL_WHISPER_TURBO = "whisper-large-v3-turbo"
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val PERMISSION_MODE = stringPreferencesKey("permission_mode")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ACTIVE_PROVIDER = stringPreferencesKey("active_provider_id")
        val THINKING_LEVEL = stringPreferencesKey("thinking_level")
        val MAX_CONTEXT = intPreferencesKey("max_context_tokens")
        val MAX_OUTPUT = intPreferencesKey("max_output_tokens")
        val MAX_ITERATIONS = intPreferencesKey("max_iterations")
        val KEEP_ALIVE = booleanPreferencesKey("keep_alive")
        val PINNED = stringSetPreferencesKey("pinned_sessions")
        val ARCHIVED = stringSetPreferencesKey("archived_sessions")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val ACTIVE_MODEL = stringPreferencesKey("active_model")
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
        val BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
        val VOICE_ENGINE = stringPreferencesKey("voice_engine")
        val GROQ_WHISPER_MODEL = stringPreferencesKey("groq_whisper_model")
        val VOICE_PROMO_SEEN = booleanPreferencesKey("voice_promo_seen")
        val RESUME_LAST_CHAT = booleanPreferencesKey("resume_last_chat")
        val LAST_ACTIVE_SESSION = stringPreferencesKey("last_active_session_id")
        val REPO_MAP_ENABLED = booleanPreferencesKey("repo_map_enabled")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        AppSettings(
            permissionMode = prefs[Keys.PERMISSION_MODE]
                ?.let { runCatching { PermissionMode.valueOf(it) }.getOrNull() }
                ?: PermissionMode.CONFIRM_RISKY,
            themeMode = prefs[Keys.THEME_MODE]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            activeProviderId = prefs[Keys.ACTIVE_PROVIDER],
            thinkingLevel = prefs[Keys.THINKING_LEVEL]
                ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
                ?: ThinkingLevel.OFF,
            maxContextTokens = prefs[Keys.MAX_CONTEXT] ?: AppSettings.DEFAULT_MAX_CONTEXT,
            maxOutputTokens = prefs[Keys.MAX_OUTPUT] ?: AppSettings.DEFAULT_MAX_OUTPUT,
            maxIterations = prefs[Keys.MAX_ITERATIONS] ?: AppSettings.DEFAULT_MAX_ITERATIONS,
            keepAlive = prefs[Keys.KEEP_ALIVE] ?: true,
            pinnedSessions = prefs[Keys.PINNED] ?: emptySet(),
            archivedSessions = prefs[Keys.ARCHIVED] ?: emptySet(),
            onboardingDone = prefs[Keys.ONBOARDING_DONE] ?: false,
            activeModel = prefs[Keys.ACTIVE_MODEL],
            disabledSkills = prefs[Keys.DISABLED_SKILLS] ?: emptySet(),
            webSearchProvider = prefs[Keys.WEB_SEARCH_PROVIDER]
                ?.takeIf { it in WEB_SEARCH_PROVIDERS }
                ?: "keyless",
            allowScreenshots = prefs[Keys.ALLOW_SCREENSHOTS] ?: false,
            planningModelsEnabled = prefs[Keys.PLANNING_MODELS_ENABLED] ?: false,
            planningProviderId = prefs[Keys.PLANNING_PROVIDER],
            planningModel = prefs[Keys.PLANNING_MODEL],
            executionProviderId = prefs[Keys.EXECUTION_PROVIDER],
            executionModel = prefs[Keys.EXECUTION_MODEL],
            planningModelsPromoSeen = prefs[Keys.PLANNING_PROMO_SEEN] ?: false,
            forkPromoSeen = prefs[Keys.FORK_PROMO_SEEN] ?: false,
            biometricLockEnabled = prefs[Keys.BIOMETRIC_LOCK_ENABLED] ?: false,
            voiceEngine = prefs[Keys.VOICE_ENGINE] ?: AppSettings.VOICE_ENGINE_INBUILT,
            groqWhisperModel = prefs[Keys.GROQ_WHISPER_MODEL] ?: AppSettings.GROQ_MODEL_WHISPER_V3,
            voicePromoSeen = prefs[Keys.VOICE_PROMO_SEEN] ?: false,
            resumeLastChat = prefs[Keys.RESUME_LAST_CHAT] ?: true,
            lastActiveSessionId = prefs[Keys.LAST_ACTIVE_SESSION],
            repoMapEnabled = prefs[Keys.REPO_MAP_ENABLED] ?: true,
        )
    }

    suspend fun setWebSearchProvider(provider: String) {
        if (provider !in WEB_SEARCH_PROVIDERS) return
        context.settingsStore.edit { it[Keys.WEB_SEARCH_PROVIDER] = provider }
    }

    suspend fun setPermissionMode(mode: PermissionMode) {
        context.settingsStore.edit { it[Keys.PERMISSION_MODE] = mode.name }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
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

    suspend fun setVoiceEngine(engine: String) {
        context.settingsStore.edit { it[Keys.VOICE_ENGINE] = engine }
    }

    suspend fun setGroqWhisperModel(model: String) {
        context.settingsStore.edit { it[Keys.GROQ_WHISPER_MODEL] = model }
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

    suspend fun setActiveProvider(id: String?) {
        context.settingsStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.ACTIVE_PROVIDER)
            else prefs[Keys.ACTIVE_PROVIDER] = id
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

    suspend fun setKeepAlive(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.KEEP_ALIVE] = enabled }
    }

    suspend fun setPinned(sessionId: String, pinned: Boolean) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.PINNED] ?: emptySet()
            prefs[Keys.PINNED] = if (pinned) current + sessionId else current - sessionId
        }
    }

    suspend fun setArchived(sessionId: String, archived: Boolean) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.ARCHIVED] ?: emptySet()
            prefs[Keys.ARCHIVED] = if (archived) current + sessionId else current - sessionId
        }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        context.settingsStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    suspend fun setRepoMapEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.REPO_MAP_ENABLED] = enabled }
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

    suspend fun setResumeLastChat(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.RESUME_LAST_CHAT] = enabled }
    }

    suspend fun setLastActiveSessionId(sessionId: String?) {
        context.settingsStore.edit { prefs ->
            if (sessionId.isNullOrBlank()) prefs.remove(Keys.LAST_ACTIVE_SESSION)
            else prefs[Keys.LAST_ACTIVE_SESSION] = sessionId
        }
    }
}
