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
    LEARN(
        R.string.learn_title, R.string.learn_description, R.string.learn_group,
        "learn tutorial guide help study getting started how to android harness features instructions",
    ),
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
        "chat option start screen default ui selection code chat last selected launch startup chat start new last resume search past history conversations",
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
        "coding code agent subagent behavior chat commands skills codegraph caveman connected tools mcp telegram workspaces terminal device troubleshooting search past history conversations",
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
    LIVE_MODE(
        R.string.live_title,
        R.string.settings_page_live_desc,
        R.string.settings_group_your_assistant,
        "live mode hands free voice conversation talk planning experimental realtime spoken",
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
    /** One line under the title; null for a setting whose label says enough, where the page path shows instead. */
    @StringRes val description: Int?,
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
    /**
     * Titles of the collapsed sections that hold the control, outermost first.
     * They open on arrival, and they are shown as the result's path.
     */
    val within: List<Int> = emptyList(),
)

internal val settingsHomePages = listOf(
    SettingsPage.LEARN,
    SettingsPage.CONNECTED_ACCOUNTS, SettingsPage.NINE_ROUTER,
    SettingsPage.LANGUAGE, SettingsPage.APPEARANCE, SettingsPage.CHAT_OPTIONS,
    SettingsPage.MEMORY, SettingsPage.CODING, SettingsPage.RUMBLE, SettingsPage.PRIVACY,
    SettingsPage.MODELS, SettingsPage.LOCAL_MODELS, SettingsPage.VOICE, SettingsPage.LIVE_MODE,
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

/** Every searchable entry: each page, then each setting inside one. */
internal val allSettingsEntries: List<SettingsSearchEntry> by lazy { searchableSettingsEntries + settingsControlEntries }

/**
 * Ranked search over [entries]. Titles and descriptions are resolved through
 * [text] so a search works in whichever language the app is running in, and
 * again through [englishText] so the English names keep working everywhere,
 * like [SettingsSearchEntry.keywords], which stay in English.
 *
 * A setting ranks above the page that holds it, and a page that only matched
 * through its keywords steps aside when one of its own settings matched at
 * least as well, so searching for a control lands on the control.
 */
internal class SettingsSearchIndex(
    entries: List<SettingsSearchEntry>,
    text: (Int) -> String,
    englishText: ((Int) -> String)? = null,
) {
    private class Indexed(val entry: SettingsSearchEntry, val fields: List<SettingsSearch.Field>, val order: String)

    private val indexed = entries.map { entry ->
        val fields = buildList {
            fun label(@StringRes id: Int?, weight: Double) {
                if (id == null) return
                val local = text(id)
                add(SettingsSearch.Field(local, weight))
                englishText?.invoke(id)?.takeIf { it != local }?.let { add(SettingsSearch.Field(it, weight * 0.95)) }
            }
            label(entry.title, 1.0)
            add(SettingsSearch.Field(entry.keywords, 0.7))
            if (!entry.primary) label(entry.page.title, 0.5)
            entry.within.forEach { label(it, 0.5) }
            label(entry.description, 0.45)
            label(entry.page.group, 0.3)
        }
        Indexed(entry, fields, text(entry.title))
    }

    fun search(query: String): List<SettingsSearchEntry> {
        val terms = SettingsSearch.terms(query)
        if (terms.isEmpty()) return homeSettingsEntries
        return rank(terms, fuzzy = false).ifEmpty { rank(terms, fuzzy = true) }
    }

    private fun rank(terms: List<String>, fuzzy: Boolean): List<SettingsSearchEntry> {
        val scored = indexed.mapNotNull { item ->
            var total = 0.0
            for (term in terms) {
                val best = item.fields.maxOf { it.weight * SettingsSearch.quality(term, it, fuzzy) }
                if (best <= 0.0) return@mapNotNull null
                total += best
            }
            item to total
        }
        val bestSetting = scored.filter { !it.first.entry.primary }
            .groupBy { it.first.entry.page }
            .mapValues { (_, matches) -> matches.maxOf { it.second } }
        return scored
            .filterNot { (item, score) -> item.entry.primary && (bestSetting[item.entry.page] ?: -1.0) >= score }
            .sortedWith(
                compareByDescending<Pair<Indexed, Double>> { it.second }
                    .thenBy { it.first.entry.primary }
                    .thenBy { it.first.order.length }
                    .thenBy { it.first.order },
            )
            .map { it.first.entry }
    }
}

/** The index for the language the app is running in, plus English unless that already is English. */
internal fun settingsSearchIndex(context: Context): SettingsSearchIndex {
    val english = if (context.resources.configuration.locales[0].language == Locale.ENGLISH.language) null
    else context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
    return SettingsSearchIndex(allSettingsEntries, { context.getString(it) }, english?.let { e -> { e.getString(it) } })
}

internal fun matchingSettingsEntries(query: String, context: Context): List<SettingsSearchEntry> =
    settingsSearchIndex(context).search(query)

internal fun matchingSettingsEntries(
    query: String,
    text: (Int) -> String,
    englishText: ((Int) -> String)? = null,
): List<SettingsSearchEntry> = SettingsSearchIndex(allSettingsEntries, text, englishText).search(query)

internal fun matchingSettingsPages(query: String, context: Context): List<SettingsPage> =
    matchingSettingsEntries(query, context).map { it.page }.distinct()

internal fun matchingSettingsPages(query: String, text: (Int) -> String): List<SettingsPage> =
    matchingSettingsEntries(query, text).map { it.page }.distinct()

internal fun settingsDeepLink(target: String): SettingsPage? = when (target) {
    "learn" -> SettingsPage.LEARN
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
    "live", "live-mode" -> SettingsPage.LIVE_MODE
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

/** Collapsed sections to open so the control behind [anchor] is visible. */
internal fun settingsRevealFor(@StringRes anchor: Int?): Set<Int> =
    if (anchor == null) emptySet()
    else allSettingsEntries.firstOrNull { it.anchor == anchor && it.within.isNotEmpty() }?.within?.toSet().orEmpty()
