package com.androidharness.app.ui.chat

import android.os.Build
import androidx.annotation.StringRes
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.SteeringMode
import com.androidharness.app.ui.settings.SettingsPage

/** Where a tip appears. */
internal enum class TipScope { CHAT, CODE, BOTH }

/**
 * One short tip shown while the agent works. [page] and [anchor] open the
 * setting it talks about; [relevant] hides tips for things already set up.
 */
internal data class RunTip(
    val id: String,
    @StringRes val text: Int,
    val scope: TipScope = TipScope.BOTH,
    val page: SettingsPage? = null,
    @StringRes val anchor: Int? = null,
    val relevant: (AppSettings) -> Boolean = { true },
)

internal object RunTips {
    /** How long a run must be working before a tip appears. */
    const val DELAY_MS = 6_000L
    /** Minimum gap between tips, so they stay occasional. */
    const val COOLDOWN_MS = 3 * 60_000L
    /** How long a tip stays after the run ends, so it can be finished. */
    const val LINGER_MS = 8_000L

    // Interleaved by topic so consecutive tips feel different.
    val all: List<RunTip> = listOf(
        RunTip("btw", R.string.tip_btw),
        RunTip("ask_settings", R.string.tip_ask_settings, page = SettingsPage.AGENT, anchor = R.string.as_setting_title,
            relevant = { it.agentSettingsAccess != com.androidharness.app.data.AgentSettingsAccess.OFF }),
        RunTip("steer_auto", R.string.tip_steer_auto, page = SettingsPage.AGENT, anchor = R.string.settings_anchor_steering,
            relevant = { it.steeringMode == SteeringMode.AH_AUTO }),
        RunTip("steer_after", R.string.tip_steer_after, page = SettingsPage.AGENT, anchor = R.string.settings_anchor_steering,
            relevant = { it.steeringMode == SteeringMode.AFTER_TASK }),
        RunTip("permissions", R.string.tip_permissions, TipScope.CODE, SettingsPage.AGENT, R.string.settings_anchor_default_permission),
        RunTip("chat_agents", R.string.tip_chat_agents, TipScope.CHAT, SettingsPage.CHAT_OPTIONS, R.string.chat_agents_title),
        RunTip("rumble", R.string.tip_rumble, page = SettingsPage.RUMBLE, anchor = R.string.rumble_title,
            relevant = { !it.rumbleEnabled }),
        RunTip("background", R.string.tip_background, page = SettingsPage.BACKGROUND, anchor = R.string.background_run_title,
            relevant = { !it.runInBackground }),
        RunTip("plan", R.string.tip_plan, TipScope.CODE),
        RunTip("cost", R.string.tip_cost),
        RunTip("fallbacks", R.string.tip_fallbacks, page = SettingsPage.MODELS, anchor = R.string.settings_anchor_fallbacks),
        RunTip("chat_instructions", R.string.tip_chat_instructions, TipScope.CHAT, SettingsPage.CHAT_OPTIONS, R.string.chat_global_instructions,
            relevant = { it.chatInstructions.isBlank() }),
        RunTip("tts", R.string.tip_tts, page = SettingsPage.VOICE, anchor = R.string.settings_anchor_tts,
            relevant = { !it.ttsAlwaysSpeak }),
        RunTip("agents_md", R.string.tip_agents_md, TipScope.CODE, SettingsPage.AGENT, R.string.settings_anchor_project_instructions),
        RunTip("memory_off", R.string.tip_memory_off, page = SettingsPage.MEMORY, relevant = { !it.memoryEnabled }),
        RunTip("memory_on", R.string.tip_memory_on, page = SettingsPage.MEMORY, relevant = { it.memoryEnabled }),
        RunTip("thinking", R.string.tip_thinking),
        RunTip("glass", R.string.tip_glass, page = SettingsPage.APPEARANCE, anchor = R.string.glass_title,
            relevant = { !it.harnessGlassEnabled }),
        RunTip("compact", R.string.tip_compact, TipScope.CODE),
        RunTip("search_depth", R.string.tip_search_depth, TipScope.CHAT),
        RunTip("notifications", R.string.tip_notifications, page = SettingsPage.BACKGROUND, anchor = R.string.background_notifications_title,
            relevant = { !it.notificationsEnabled }),
        RunTip("dual_planning", R.string.tip_dual_planning, page = SettingsPage.MODELS, anchor = R.string.settings_anchor_dual_planning,
            relevant = { !it.planningModelsEnabled }),
        RunTip("codegraph", R.string.tip_codegraph, TipScope.CODE, SettingsPage.CODE_INTELLIGENCE),
        RunTip("whisper", R.string.tip_whisper, page = SettingsPage.VOICE, anchor = R.string.settings_anchor_whisper,
            relevant = { it.voiceEngine != AppSettings.VOICE_ENGINE_GROQ }),
        RunTip("app_lock", R.string.tip_app_lock, page = SettingsPage.PRIVACY, relevant = { !it.biometricLockEnabled }),
        RunTip("mcp", R.string.tip_mcp, TipScope.CODE, SettingsPage.MCP),
        RunTip("web_search", R.string.tip_web_search, TipScope.CHAT, SettingsPage.SEARCH),
        RunTip("theme", R.string.tip_theme, page = SettingsPage.APPEARANCE, anchor = R.string.settings_anchor_dynamic_color),
        RunTip("subagents", R.string.tip_subagents, TipScope.CODE, SettingsPage.SUBAGENT, R.string.settings_anchor_subagent_model),
        RunTip("local_models", R.string.tip_local_models, page = SettingsPage.LOCAL_MODELS),
        RunTip("github", R.string.tip_github, TipScope.CODE, SettingsPage.GITHUB),
        RunTip("live_updates", R.string.tip_live_updates, page = SettingsPage.BACKGROUND, anchor = R.string.settings_agent_live_updates,
            relevant = { !it.liveUpdatesEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA }),
        RunTip("iterations", R.string.tip_iterations, TipScope.CODE, SettingsPage.AGENT, R.string.settings_anchor_tool_iterations),
        RunTip("chatgpt", R.string.tip_chatgpt, page = SettingsPage.CONNECTED_ACCOUNTS, anchor = R.string.settings_anchor_continue_chatgpt),
        RunTip("live_code", R.string.tip_live_code, TipScope.CODE, SettingsPage.CHAT, R.string.settings_anchor_show_live_code,
            relevant = { !it.showLiveCode }),
        RunTip("repo_map", R.string.tip_repo_map, TipScope.CODE, SettingsPage.CHAT, R.string.settings_anchor_repo_map,
            relevant = { !it.repoMapEnabled }),
        RunTip("caveman", R.string.tip_caveman, TipScope.CODE, SettingsPage.CAVEMAN, relevant = { !it.cavemanInstalled }),
        RunTip("telegram", R.string.tip_telegram, page = SettingsPage.TELEGRAM),
        RunTip("config", R.string.tip_config, TipScope.CODE, SettingsPage.CONFIGURATION),
        RunTip("shizuku", R.string.tip_shizuku, TipScope.CODE, SettingsPage.ENVIRONMENT, R.string.settings_anchor_system_paths),
        RunTip("plugins", R.string.tip_plugins, TipScope.CODE, SettingsPage.PLUGINS),
        RunTip("language", R.string.tip_language, page = SettingsPage.LANGUAGE),
        RunTip("backup", R.string.tip_backup, page = SettingsPage.BACKUP),
        RunTip("progress_bar", R.string.tip_progress_bar, page = SettingsPage.APPEARANCE, anchor = R.string.settings_anchor_progress_bar_style),
        RunTip("turn_off", R.string.tip_turn_off, page = SettingsPage.AGENT, anchor = R.string.tips_setting_title),
    )

    /**
     * The next tip after [cursor] that fits this conversation and the current
     * settings, with the cursor to save so the rotation continues from there.
     */
    fun next(settings: AppSettings, isChat: Boolean, cursor: Int = settings.runTipCursor): Pair<RunTip, Int>? {
        if (!settings.runTipsEnabled) return null
        for (step in 0 until all.size) {
            val index = Math.floorMod(cursor + step, all.size)
            val tip = all[index]
            val inScope = tip.scope == TipScope.BOTH || (tip.scope == TipScope.CHAT) == isChat
            if (inScope && tip.relevant(settings)) return tip to (index + 1) % all.size
        }
        return null
    }
}
