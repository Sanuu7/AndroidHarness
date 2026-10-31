package com.androidharness.app.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import com.androidharness.app.R
import java.util.Locale

/**
 * A settings page, described entirely by string resources.
 *
 * Titles, descriptions and groups are resource ids rather than literals because
 * the same title is shown in the sidebar, matched by the settings search and used
 * as the scroll target of a deep link. Keeping one id per label means the three
 * can never disagree once the app runs in a language other than English.
 *
 * [keywords] stays in English on purpose: it is search vocabulary, not UI copy,
 * so a user who knows the English term keeps finding the setting in any locale.
 */
internal enum class SettingsPage(
    @StringRes val title: Int,
    @StringRes val description: Int,
    @StringRes val group: Int,
    val keywords: String,
    /** False for pages that live inside a hub page (Coding) instead of the settings home. */
    val onHome: Boolean = true,
) {
    LANGUAGE(
        R.string.settings_page_language_title,
        R.string.settings_page_language_desc,
        R.string.settings_group_personalize,
        "language locale translation translate system default english chinese russian русский язык arabic العربية عربي اللغة persian farsi فارسی زبان portuguese português brasil brazil pt-br idioma língua german deutsch sprache übersetzung spanish español espanol idioma traducción japanese nihongo 日本語 にほんご 言語 翻訳 indonesian indonesia bahasa 中文 简体 语言 poeditor contribute",
    ),
    CHAT_OPTIONS(
        R.string.settings_page_chat_options_title,
        R.string.settings_page_chat_options_desc,
        R.string.settings_group_personalize,
        "chat option start screen default ui selection code chat last selected launch startup chat start new last resume",
    ),
    MEMORY(
        R.string.settings_page_memory_title,
        R.string.settings_page_memory_desc,
        R.string.settings_group_personalize,
        "memory remember saved facts preferences projects decisions forget delete clear recall",
    ),
    CODING(
        R.string.settings_page_coding_title,
        R.string.settings_page_coding_desc,
        R.string.settings_group_personalize,
        "coding code agent subagent behavior chat commands skills codegraph caveman connected tools mcp telegram workspaces terminal device troubleshooting",
    ),
    RUMBLE(
        R.string.settings_page_rumble_title,
        R.string.settings_page_rumble_desc,
        R.string.settings_group_personalize,
        "ah rumble haptics haptic vibration vibrate buzz feedback pulse motor tool calls thinking strength smooth crisp engine pattern",
    ),
    GITHUB(
        R.string.settings_page_github_title,
        R.string.settings_page_github_desc,
        R.string.settings_group_connections,
        "oauth login git pat token scopes import commit push preset automation",
    ),
    CONNECTED_ACCOUNTS(
        R.string.settings_page_accounts_title,
        R.string.settings_page_accounts_desc,
        R.string.settings_group_connections,
        "chatgpt codex subscription sign in login account plan oauth",
    ),
    NINE_ROUTER(
        R.string.nine_router_title,
        R.string.nine_router_description,
        R.string.settings_group_special_providers,
        "9router nine router local proxy linux install start stop dashboard",
    ),
    MODELS(
        R.string.settings_page_models_title,
        R.string.settings_page_models_desc,
        R.string.settings_group_your_assistant,
        "api key token provider openai anthropic gemini thinking planning execution fallback retries quota privacy routing",
    ),
    SUBAGENT(
        R.string.settings_page_subagent_title,
        R.string.settings_page_subagent_desc,
        R.string.settings_group_coding,
        "subagent sub-agent sub agent model provider action tools permissions edit commands",
        onHome = false,
    ),
    LOCAL_MODELS(
        R.string.settings_page_local_models_title,
        R.string.settings_page_local_models_desc,
        R.string.settings_group_your_assistant,
        "offline hugging face gguf safetensors llama ram memory storage cpu download uninstall context input output vision images threads",
    ),
    PLUGINS(
        R.string.pm_title, R.string.pm_description, R.string.settings_group_coding,
        "plugins marketplace discover installed npm github bundle install update uninstall hooks instructions skills mcp",
        onHome = false,
    ),
    CONFIGURATION(
        R.string.config_title,
        R.string.config_description,
        R.string.settings_group_coding,
        "harness.json harness.jsonc project global configuration instructions references plugins mcp schema variables secrets",
        onHome = false,
    ),
    AGENT(
        R.string.settings_page_agent_title,
        R.string.settings_page_agent_desc,
        R.string.settings_group_coding,
        "approval full access iterations tools agents.md memory",
        onHome = false,
    ),
    CHAT(
        R.string.settings_page_chat_title,
        R.string.settings_page_chat_desc,
        R.string.settings_group_coding,
        "resume last chat launch repo map shortcuts",
        onHome = false,
    ),
    CODE_INTELLIGENCE(
        R.string.settings_page_codegraph_title,
        R.string.settings_page_codegraph_desc,
        R.string.settings_group_coding,
        "codegraph graph symbols callers callees impact affected index semantic code intelligence",
        onHome = false,
    ),
    CAVEMAN(
        R.string.settings_page_caveman_title,
        R.string.settings_page_caveman_desc,
        R.string.settings_group_coding,
        "caveman install intensity off lite full ultra wenyan compression tokens",
        onHome = false,
    ),
    VOICE(
        R.string.settings_page_voice_title,
        R.string.settings_page_voice_desc,
        R.string.settings_group_your_assistant,
        "microphone groq whisper transcription speech tts text to speech read aloud voice output",
    ),
    SKILLS(
        R.string.settings_page_skills_title,
        R.string.settings_page_skills_desc,
        R.string.settings_group_coding,
        "catalog instructions add skills",
        onHome = false,
    ),
    APPEARANCE(
        R.string.settings_page_appearance_title,
        R.string.settings_page_appearance_desc,
        R.string.settings_group_personalize,
        "dark light amoled system wallpaper dynamic color landscape desktop tablet layout split panes progress flat squiggly wavy animation",
    ),
    PRIVACY(
        R.string.settings_page_privacy_title,
        R.string.settings_page_privacy_desc,
        R.string.settings_group_personalize,
        "fingerprint pin authentication screenshots",
    ),
    SEARCH(
        R.string.settings_page_search_title,
        R.string.settings_page_search_desc,
        R.string.settings_group_connections,
        "brave tavily keyless internet",
    ),
    MCP(
        R.string.settings_page_mcp_title,
        R.string.settings_page_mcp_desc,
        R.string.settings_group_connections,
        "mcp integrations servers transport",
    ),
    TELEGRAM(
        R.string.settings_page_telegram_title,
        R.string.settings_page_telegram_desc,
        R.string.settings_group_connections,
        "telegram bot remote control pairing notifications gateway",
    ),
    WORKSPACE(
        R.string.settings_page_workspace_title,
        R.string.settings_page_workspace_desc,
        R.string.settings_group_coding,
        "storage files saf folder project remembered permissions grants revoke always",
        onHome = false,
    ),
    BACKGROUND(
        R.string.background_settings_title,
        R.string.background_settings_description,
        R.string.settings_group_workspace_device,
        "background notifications alerts running minimize closed services",
    ),
    STORAGE(
        R.string.settings_page_storage_title,
        R.string.settings_page_storage_desc,
        R.string.settings_group_workspace_device,
        "storage cleanup disk space cache packages downloads build outputs",
    ),
    ENVIRONMENT(
        R.string.settings_page_environment_title,
        R.string.settings_page_environment_desc,
        R.string.settings_group_coding,
        "shizuku shevery dhizuku device owner shell packages environment battery optimization keep alive wireless adb wifi pairing debugging developer options notification",
        onHome = false,
    ),
    TROUBLESHOOTING(
        R.string.settings_page_troubleshooting_title,
        R.string.settings_page_troubleshooting_desc,
        R.string.settings_group_app_data,
        "diagnostics help doctor repair errors storage linux shizuku ssh mcp notifications provider report",
    ),
    SYNC(
        R.string.sync_title, R.string.sync_description, R.string.settings_group_connections,
        "sync ftp sftp upload download remote folder transfer",
    ),
    FTP_SERVER(
        R.string.ftp_server_title, R.string.ftp_server_description, R.string.settings_group_connections,
        "ftp server share wifi local network files passive",
    ),
    BACKUP(
        R.string.settings_page_backup_title,
        R.string.settings_page_backup_desc,
        R.string.settings_group_app_data,
        "restore history archive transfer password providers keys encryption",
    ),
    USAGE(
        R.string.settings_page_usage_title,
        R.string.settings_page_usage_desc,
        R.string.settings_group_app_data,
        "stats statistics cache tokens",
    ),
    UPDATES(
        R.string.settings_page_updates_title,
        R.string.settings_page_updates_desc,
        R.string.settings_group_app_data,
        "version release download install root shizuku github update",
    ),
    SETUP(
        R.string.settings_page_setup_title,
        R.string.settings_page_setup_desc,
        R.string.settings_group_app_data,
        "onboarding notifications permissions",
    ),
    ABOUT(
        R.string.settings_page_about_title,
        R.string.settings_page_about_desc,
        R.string.settings_group_app_data,
        "about license mit copyright credits author sanuu github repository open source thanks",
    ),
}

internal data class SettingsSearchEntry(
    @StringRes val title: Int,
    @StringRes val description: Int,
    val page: SettingsPage,
    val keywords: String = "",
    val primary: Boolean = false,
    /**
     * Visible label of the single control this entry points at, when it describes
     * a setting rather than a whole page. Opening the entry scrolls that control
     * into view and tints it (see SettingsAnchor); the id must be the one the
     * control itself renders, so the pair can never drift apart.
     */
    @StringRes val anchor: Int? = null,
    /** API level the control needs; below it the result stays findable but is labelled as unavailable. */
    val minSdk: Int = 0,
    /** The control only does something on a phone with a vibration motor. */
    val needsVibrator: Boolean = false,
)

internal val settingsHomePages = listOf(
    SettingsPage.CONNECTED_ACCOUNTS, SettingsPage.NINE_ROUTER,
    SettingsPage.LANGUAGE, SettingsPage.APPEARANCE, SettingsPage.CHAT_OPTIONS,
    SettingsPage.MEMORY, SettingsPage.CODING, SettingsPage.RUMBLE, SettingsPage.PRIVACY,
    SettingsPage.MODELS, SettingsPage.LOCAL_MODELS, SettingsPage.VOICE,
    SettingsPage.GITHUB, SettingsPage.SEARCH, SettingsPage.MCP,
    SettingsPage.TELEGRAM, SettingsPage.SYNC, SettingsPage.FTP_SERVER,
    SettingsPage.BACKGROUND, SettingsPage.STORAGE,
    SettingsPage.BACKUP, SettingsPage.USAGE, SettingsPage.TROUBLESHOOTING,
    SettingsPage.UPDATES, SettingsPage.SETUP, SettingsPage.ABOUT,
)

/** Keep the account shortcut at the top without pulling every connection above the other sections. */
internal fun settingsDisplayGroups(entries: List<SettingsSearchEntry>, query: String) = entries.groupBy {
    if (query.isBlank() && it.page in setOf(SettingsPage.CONNECTED_ACCOUNTS, SettingsPage.NINE_ROUTER)) R.string.settings_group_special_providers else it.page.group
}

internal val settingsCodingPages = listOf(
    SettingsPage.SUBAGENT, SettingsPage.AGENT, SettingsPage.CONFIGURATION, SettingsPage.PLUGINS, SettingsPage.CHAT, SettingsPage.SKILLS,
    SettingsPage.CODE_INTELLIGENCE, SettingsPage.CAVEMAN, SettingsPage.WORKSPACE, SettingsPage.ENVIRONMENT,
)

private val homeSettingsEntries = settingsHomePages.map { page ->
    SettingsSearchEntry(page.title, page.description, page, page.keywords, primary = true)
}

/** All pages stay searchable, including the ones reached through the Coding hub. */
private val searchableSettingsEntries = SettingsPage.entries.map { page ->
    SettingsSearchEntry(page.title, page.description, page, page.keywords, primary = true)
}

private val subSettingsEntries = listOfNotNull(
    SettingsSearchEntry(R.string.glass_title, R.string.glass_description, SettingsPage.APPEARANCE,
        "aharness harness glass frosted liquid blur refraction experimental appearance transparent translucent", anchor = R.string.glass_title,
        minSdk = android.os.Build.VERSION_CODES.S),
    SettingsSearchEntry(R.string.glass_liquid, R.string.glass_liquid_hint, SettingsPage.APPEARANCE,
        "liquid glass refraction lens blur aharness harness experimental android 13", anchor = R.string.glass_title,
        minSdk = android.os.Build.VERSION_CODES.TIRAMISU),
    SettingsSearchEntry(R.string.rumble_title, R.string.rumble_desc, SettingsPage.RUMBLE,
        "ah rumble haptics haptic vibration vibrate buzz feedback tool calls thinking motor on off",
        anchor = R.string.rumble_title, needsVibrator = true),
    SettingsSearchEntry(R.string.rumble_engine_title, R.string.rumble_engine_auto_desc, SettingsPage.RUMBLE,
        "rumble haptic engine system effects primitives android 11 waveform custom automatic", anchor = R.string.rumble_engine_title,
        needsVibrator = true),
    SettingsSearchEntry(R.string.rumble_events_title, R.string.rumble_events_desc, SettingsPage.RUMBLE,
        "rumble pattern tick click thud swell rise double triple tool thinking approval answer finished level per event",
        anchor = R.string.rumble_events_title, needsVibrator = true),
    SettingsSearchEntry(R.string.rumble_flow_title, R.string.rumble_flow_desc, SettingsPage.RUMBLE,
        "rumble flow smooth soothing continuous streaming writing reply typing visuals rich response widget breathe haptic vibration chat",
        anchor = R.string.rumble_flow_title, needsVibrator = true),
    SettingsSearchEntry(R.string.rumble_in_code, R.string.rumble_in_code_desc, SettingsPage.RUMBLE,
        "rumble code coding tools haptic vibration", anchor = R.string.rumble_where_title, needsVibrator = true),
    SettingsSearchEntry(R.string.chat_agents_title, R.string.chat_agents_description, SettingsPage.CHAT_OPTIONS,
        "personal agents persona assistant knowledge skills tools model tutor researcher", anchor = R.string.chat_agents_title),
    SettingsSearchEntry(R.string.background_run_title, R.string.background_run_description, SettingsPage.BACKGROUND, "background running leave minimize", anchor = R.string.background_run_title),
    SettingsSearchEntry(R.string.background_notifications_title, R.string.background_notifications_description, SettingsPage.BACKGROUND, "notifications alerts", anchor = R.string.background_notifications_title),
    SettingsSearchEntry(
        R.string.chat_global_instructions,
        R.string.chat_global_instructions_desc,
        SettingsPage.CHAT_OPTIONS,
        "global chat instructions custom personalization prompt default override replace",
        anchor = R.string.chat_global_instructions,
    ),
    SettingsSearchEntry(
        R.string.settings_start_screen,
        R.string.settings_page_chat_options_desc,
        SettingsPage.CHAT_OPTIONS,
        "default ui selection code chat last selected start screen launch startup",
        anchor = R.string.settings_start_screen,
    ),
    SettingsSearchEntry(
        R.string.settings_chat_start,
        R.string.settings_page_chat_options_desc,
        SettingsPage.CHAT_OPTIONS,
        "chat start new last resume continue fresh blank conversation launch startup",
        anchor = R.string.settings_chat_start,
    ),
    SettingsSearchEntry(
        R.string.settings_search_live_updates_title,
        R.string.settings_search_live_updates_desc,
        SettingsPage.BACKGROUND,
        "live activity updates now bar samsung one ui pixel hyperos coloros oxygenos originos magicos status bar lock screen notification foreground",
        anchor = R.string.settings_agent_live_updates,
        minSdk = android.os.Build.VERSION_CODES.BAKLAVA,
    ),
    SettingsSearchEntry(
        R.string.settings_language_contribute_title,
        R.string.settings_language_contribute_description,
        SettingsPage.LANGUAGE,
        "translation translate contribute community poeditor language",
        anchor = R.string.settings_language_contribute_title,
    ),
    SettingsSearchEntry(
        R.string.settings_search_progress_bar_title,
        R.string.settings_search_progress_bar_desc,
        SettingsPage.APPEARANCE,
        "progress loading indicator animation expressive flat squiggly wavy",
        anchor = R.string.settings_anchor_progress_bar_style,
    ),
    SettingsSearchEntry(
        R.string.settings_search_fallbacks_title,
        R.string.settings_search_fallbacks_desc,
        SettingsPage.MODELS,
        "fallback failover retry retries quota ZDR privacy chain",
        anchor = R.string.settings_anchor_fallbacks,
    ),
    SettingsSearchEntry(
        R.string.settings_search_continue_chatgpt_title,
        R.string.settings_search_continue_chatgpt_desc,
        SettingsPage.CONNECTED_ACCOUNTS,
        "sign in codex subscription",
        anchor = R.string.settings_anchor_continue_chatgpt,
    ),
    SettingsSearchEntry(
        R.string.settings_search_manage_providers_title,
        R.string.settings_search_manage_providers_desc,
        SettingsPage.MODELS,
        "provider api key openai anthropic gemini login model",
        anchor = R.string.settings_anchor_manage_providers,
    ),
    SettingsSearchEntry(
        R.string.settings_search_dual_planning_title,
        R.string.settings_search_dual_planning_desc,
        SettingsPage.MODELS,
        "planning plan model execute execution model",
        anchor = R.string.settings_anchor_dual_planning,
    ),
    SettingsSearchEntry(
        R.string.settings_search_plan_model_title,
        R.string.settings_search_plan_model_desc,
        SettingsPage.MODELS,
        "planning dual",
        anchor = R.string.settings_anchor_dual_planning,
    ),
    SettingsSearchEntry(
        R.string.settings_search_execute_model_title,
        R.string.settings_search_execute_model_desc,
        SettingsPage.MODELS,
        "execution dual",
        anchor = R.string.settings_anchor_dual_planning,
    ),
    SettingsSearchEntry(
        R.string.settings_search_permission_mode_title,
        R.string.settings_search_permission_mode_desc,
        SettingsPage.AGENT,
        "permissions approval full access",
        anchor = R.string.settings_anchor_default_permission,
    ),
    SettingsSearchEntry(
        R.string.settings_search_subagent_tools_title,
        R.string.settings_search_subagent_tools_desc,
        SettingsPage.SUBAGENT,
        "subagent sub-agent permissions tools commands read only plan",
        anchor = R.string.settings_anchor_subagent_tools,
    ),
    SettingsSearchEntry(
        R.string.settings_search_subagent_model_title,
        R.string.settings_search_subagent_model_desc,
        SettingsPage.SUBAGENT,
        "subagent sub-agent model provider execution different separate",
        anchor = R.string.settings_anchor_subagent_model,
    ),
    SettingsSearchEntry(
        R.string.settings_search_max_context_title,
        R.string.settings_search_max_context_desc,
        SettingsPage.AGENT,
        "context tokens limit",
        anchor = R.string.settings_anchor_max_context,
    ),
    SettingsSearchEntry(
        R.string.settings_search_tool_iterations_title,
        R.string.settings_search_tool_iterations_desc,
        SettingsPage.AGENT,
        "iterations tools max limit",
        anchor = R.string.settings_anchor_tool_iterations,
    ),
    SettingsSearchEntry(
        R.string.settings_anchor_steering,
        R.string.steering_search_desc,
        SettingsPage.AGENT,
        "steer steering queue message while running busy interrupt ah auto after task",
        anchor = R.string.settings_anchor_steering,
    ),
    SettingsSearchEntry(
        R.string.tips_setting_title,
        R.string.tips_search_desc,
        SettingsPage.AGENT,
        "tips hints suggestions help hide turn off disable onboarding learn features",
        anchor = R.string.tips_setting_title,
    ),
    SettingsSearchEntry(
        R.string.as_setting_title,
        R.string.as_search_desc,
        SettingsPage.AGENT,
        "agent change settings from chat ask assistant control toggle slider palette",
        anchor = R.string.as_setting_title,
    ),
    SettingsSearchEntry(
        R.string.settings_search_project_instructions_title,
        R.string.settings_search_project_instructions_desc,
        SettingsPage.AGENT,
        "agents instructions memory workspace prompt",
        anchor = R.string.settings_anchor_project_instructions,
    ),
    SettingsSearchEntry(
        R.string.settings_search_start_code_title,
        R.string.settings_search_start_code_desc,
        SettingsPage.CODING,
        "startup launch chat resume continue code coding new last conversation",
        anchor = R.string.settings_start_code,
    ),
    SettingsSearchEntry(
        R.string.settings_search_show_live_code_title,
        R.string.settings_search_show_live_code_desc,
        SettingsPage.CHAT,
        "writing streaming diff added removed tokens",
        anchor = R.string.settings_anchor_show_live_code,
    ),
    SettingsSearchEntry(
        R.string.settings_search_repo_map_title,
        R.string.settings_search_repo_map_desc,
        SettingsPage.CHAT,
        "repo map code index symbols",
        anchor = R.string.settings_anchor_repo_map,
    ),
    SettingsSearchEntry(
        R.string.settings_search_codegraph_title,
        R.string.settings_search_codegraph_desc,
        SettingsPage.CODE_INTELLIGENCE,
        "codegraph explore node impact affected sync semantic graph",
    ),
    SettingsSearchEntry(
        R.string.settings_search_codegraph_index_title,
        R.string.settings_search_codegraph_index_desc,
        SettingsPage.CODE_INTELLIGENCE,
        "project workspace initialize reindex graph",
    ),
    SettingsSearchEntry(
        R.string.settings_search_slash_commands_title,
        R.string.settings_search_slash_commands_desc,
        SettingsPage.CHAT,
        "shortcut snippets custom command",
        anchor = R.string.settings_anchor_slash_commands,
    ),
    SettingsSearchEntry(
        R.string.settings_search_stt_title,
        R.string.settings_search_stt_desc,
        SettingsPage.VOICE,
        "voice microphone groq whisper speech transcription",
        anchor = R.string.settings_anchor_stt,
    ),
    SettingsSearchEntry(
        R.string.settings_search_whisper_title,
        R.string.settings_search_whisper_desc,
        SettingsPage.VOICE,
        "groq voice transcription",
        anchor = R.string.settings_anchor_whisper,
    ),
    SettingsSearchEntry(
        R.string.settings_search_tts_title,
        R.string.settings_search_tts_desc,
        SettingsPage.VOICE,
        "tts text to speech read aloud speak replies voice output groq playai",
        anchor = R.string.settings_anchor_tts,
    ),
    SettingsSearchEntry(
        R.string.settings_search_theme_title,
        R.string.settings_search_theme_desc,
        SettingsPage.APPEARANCE,
        "dark light amoled color",
        anchor = R.string.settings_anchor_theme,
    ),
    SettingsSearchEntry(
        R.string.settings_search_layout_title,
        R.string.settings_search_layout_desc,
        SettingsPage.APPEARANCE,
        "landscape tablet split panes wide desktop",
        anchor = R.string.settings_anchor_layout,
    ),
    SettingsSearchEntry(
        R.string.theme_color_palette,
        R.string.theme_color_hint,
        SettingsPage.APPEARANCE,
        "theme material you colors palette blue ocean indigo lavender orchid rose coral amber lime forest teal slate",
        anchor = R.string.settings_anchor_dynamic_color,
    ),
    SettingsSearchEntry(
        R.string.theme_custom_title, R.string.theme_custom_hint, SettingsPage.APPEARANCE,
        "custom color picker palette hex hue saturation brightness create edit accent highlight",
        anchor = R.string.theme_custom_title,
    ),
    SettingsSearchEntry(
        R.string.theme_canvas_title, R.string.theme_canvas_description, SettingsPage.APPEARANCE,
        "harness canvas background wallpaper photo image dimming dim chat code adapt colors",
        anchor = R.string.theme_canvas_title,
    ),
    SettingsSearchEntry(
        R.string.settings_search_dynamic_color_title,
        R.string.settings_search_dynamic_color_desc,
        SettingsPage.APPEARANCE,
        "material you wallpaper colors",
        anchor = R.string.settings_anchor_dynamic_color,
        minSdk = android.os.Build.VERSION_CODES.S,
    ),
    SettingsSearchEntry(
        R.string.settings_search_app_lock_title,
        R.string.settings_search_app_lock_desc,
        SettingsPage.PRIVACY,
        "fingerprint face pin authentication lock",
    ),
    SettingsSearchEntry(
        R.string.settings_search_autolock_title,
        R.string.settings_search_autolock_desc,
        SettingsPage.PRIVACY,
        "biometric timeout security",
    ),
    SettingsSearchEntry(
        R.string.settings_search_screenshots_title,
        R.string.settings_search_screenshots_desc,
        SettingsPage.PRIVACY,
        "screen capture screenshot privacy",
    ),
    SettingsSearchEntry(
        R.string.settings_search_github_title,
        R.string.settings_search_github_desc,
        SettingsPage.GITHUB,
        "oauth token pat repositories login",
    ),
    SettingsSearchEntry(
        R.string.settings_search_web_title,
        R.string.settings_search_web_desc,
        SettingsPage.SEARCH,
        "internet brave tavily api key keyless",
    ),
    SettingsSearchEntry(
        R.string.settings_search_mcp_title,
        R.string.settings_search_mcp_desc,
        SettingsPage.MCP,
        "tools integrations server transport",
    ),
    SettingsSearchEntry(
        R.string.settings_search_add_workspace_title,
        R.string.settings_search_add_workspace_desc,
        SettingsPage.WORKSPACE,
        "folder storage project saf",
    ),
    SettingsSearchEntry(
        R.string.settings_search_shared_storage_title,
        R.string.settings_search_shared_storage_desc,
        SettingsPage.ENVIRONMENT,
        "files storage permission all files",
        anchor = R.string.settings_anchor_shared_storage,
    ),
    SettingsSearchEntry(
        R.string.settings_search_shizuku_title,
        R.string.settings_search_shizuku_desc,
        SettingsPage.ENVIRONMENT,
        "adb shell system paths permission",
        anchor = R.string.settings_anchor_system_paths,
    ),
    SettingsSearchEntry(
        R.string.settings_search_linux_title,
        R.string.settings_search_linux_desc,
        SettingsPage.ENVIRONMENT,
        "terminal packages shell environment",
        anchor = R.string.settings_anchor_app_shell,
    ),
    SettingsSearchEntry(
        R.string.settings_search_battery_title,
        R.string.settings_search_battery_desc,
        SettingsPage.ENVIRONMENT,
        "background keep alive battery",
    ),
    SettingsSearchEntry(
        R.string.settings_search_export_title,
        R.string.settings_search_export_desc,
        SettingsPage.BACKUP,
        "backup export history archive",
    ),
    SettingsSearchEntry(
        R.string.settings_search_import_title,
        R.string.settings_search_import_desc,
        SettingsPage.BACKUP,
        "backup import restore history",
    ),
    SettingsSearchEntry(
        R.string.settings_search_updates_title,
        R.string.settings_search_updates_desc,
        SettingsPage.UPDATES,
        "version update release download",
    ),
    SettingsSearchEntry(
        R.string.settings_search_license_title,
        R.string.settings_search_license_desc,
        SettingsPage.ABOUT,
        "mit copyright permissive open source",
    ),
    SettingsSearchEntry(
        R.string.settings_search_credits_title,
        R.string.settings_search_credits_desc,
        SettingsPage.ABOUT,
        "thanks acknowledgements inspirations projects",
    ),
    SettingsSearchEntry(
        R.string.settings_search_author_title,
        R.string.settings_search_author_desc,
        SettingsPage.ABOUT,
        "sanuu github repository maintainer source code",
    ),
)

/**
 * Entries matching every whitespace-separated term of [query]. Titles and
 * descriptions are resolved through [context] so a search works in whichever
 * language the app is currently running in, while [SettingsSearchEntry.keywords]
 * stay in English so the original vocabulary keeps working everywhere.
 */
internal fun matchingSettingsEntries(query: String, context: Context): List<SettingsSearchEntry> {
    val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocale(Locale.ENGLISH)
    })
    return matchingSettingsEntries(query, text = { context.getString(it) }, englishText = { english.getString(it) })
}

internal fun matchingSettingsEntries(
    query: String,
    text: (Int) -> String,
    englishText: ((Int) -> String)? = null,
): List<SettingsSearchEntry> {
    val terms = query.trim().lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }
    if (terms.isEmpty()) return homeSettingsEntries
    fun haystack(entry: SettingsSearchEntry): String = buildString {
        append(text(entry.title)).append(' ')
        append(text(entry.description)).append(' ')
        append(text(entry.page.title)).append(' ')
        append(text(entry.page.group)).append(' ')
        englishText?.let { english ->
            listOf(entry.title, entry.description, entry.page.title, entry.page.group).forEach {
                append(english(it)).append(' ')
            }
        }
        append(entry.keywords)
    }.lowercase()
    return (searchableSettingsEntries + subSettingsEntries)
        .filter { entry -> terms.all { it in haystack(entry) } }
        .sortedWith(
            compareBy<SettingsSearchEntry> { it.primary }
                .thenBy { text(it.title) },
        )
}

internal fun matchingSettingsPages(query: String, context: Context): List<SettingsPage> =
    matchingSettingsEntries(query, context).map { it.page }.distinct()

internal fun matchingSettingsPages(query: String, text: (Int) -> String): List<SettingsPage> =
    matchingSettingsEntries(query, text).map { it.page }.distinct()

internal fun settingsDeepLink(target: String): SettingsPage? = when (target) {
    "chat-agents" -> SettingsPage.CHAT_OPTIONS
    "sync" -> SettingsPage.SYNC
    "ftp-server" -> SettingsPage.FTP_SERVER
    "updates" -> SettingsPage.UPDATES
    "language" -> SettingsPage.LANGUAGE
    "troubleshooting" -> SettingsPage.TROUBLESHOOTING
    "github" -> SettingsPage.GITHUB
    "chatgpt" -> SettingsPage.CONNECTED_ACCOUNTS
    "planning" -> SettingsPage.MODELS
    "voice" -> SettingsPage.VOICE
    "telegram" -> SettingsPage.TELEGRAM
    "plugins", "plugin-manager" -> SettingsPage.PLUGINS
    "configuration", "config" -> SettingsPage.CONFIGURATION
    "agent" -> SettingsPage.AGENT
    "coding" -> SettingsPage.CODING
    "background", "notifications" -> SettingsPage.BACKGROUND
    "storage" -> SettingsPage.STORAGE
    else -> parseSettingsTarget(target)?.first
}

/**
 * Deep link to any page, optionally scrolled to one control. Only ever kept
 * in memory for the current process, so carrying the resource id is safe.
 */
internal fun settingsTarget(page: SettingsPage, @StringRes anchor: Int? = null): String = "page:${page.name}:${anchor ?: 0}"

private fun parseSettingsTarget(target: String): Pair<SettingsPage, Int?>? {
    val parts = target.split(':')
    if (parts.size != 3 || parts[0] != "page") return null
    val page = SettingsPage.entries.firstOrNull { it.name == parts[1] } ?: return null
    return page to parts[2].toIntOrNull()?.takeIf { it != 0 }
}

/** The control a deep link should scroll to and tint, if it names a specific one. */
@StringRes
internal fun settingsDeepLinkAnchor(target: String): Int? = when (target) {
    "planning" -> R.string.settings_anchor_dual_planning
    "voice" -> R.string.settings_anchor_stt
    else -> parseSettingsTarget(target)?.second
}

/** The control a search result should scroll to, or null when it opens a whole page. */
@StringRes
internal fun settingsAnchorFor(title: String, context: Context): Int? =
    subSettingsEntries.firstOrNull { entry ->
        entry.anchor != null && context.getString(entry.title) == title
    }?.anchor
