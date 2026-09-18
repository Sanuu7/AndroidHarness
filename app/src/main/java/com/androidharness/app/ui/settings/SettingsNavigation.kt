package com.androidharness.app.ui.settings

internal enum class SettingsPage(
    val title: String,
    val description: String,
    val group: String,
    val keywords: String,
) {
    MODELS("Models & providers", "Connections, current model and separate planning models", "Your assistant", "api key token provider openai anthropic gemini thinking planning execution"),
    AGENT("Agent behavior", "Permissions, context limits and project instructions", "Your assistant", "approval full access iterations tools agents.md memory"),
    CHAT("Chat & commands", "Startup behavior, code indexing and slash commands", "Your assistant", "resume last chat launch repo map shortcuts"),
    CODE_INTELLIGENCE("CodeGraph", "Install CodeGraph and manage project indexes", "Integrations", "codegraph graph symbols callers callees impact affected index semantic code intelligence"),
    CAVEMAN("Caveman", "Shorter replies, optional coding skills and token reports", "Integrations", "caveman install intensity off lite full ultra wenyan compression tokens"),
    VOICE("Voice input", "Speech recognition and voice provider preferences", "Your assistant", "microphone groq whisper transcription speech"),
    SKILLS("Skills", "Manage the playbooks your assistant can use", "Your assistant", "catalog instructions add skills"),
    APPEARANCE("Appearance", "Theme, colors and the look of your app", "Personalize", "dark light amoled system wallpaper dynamic color"),
    PRIVACY("Privacy & security", "App lock, biometrics and screenshot access", "Personalize", "fingerprint pin authentication screenshots"),
    GITHUB("GitHub", "Connect your account and manage repository access", "Connections", "oauth login git pat token scopes"),
    SEARCH("Web search", "Search provider and API credentials", "Connections", "brave tavily keyless internet"),
    MCP("Connected tools", "Add and configure MCP servers", "Connections", "mcp integrations servers transport"),
    WORKSPACE("Workspaces", "Choose, add and manage your project folders", "Workspace & device", "storage files saf folder project remembered permissions grants revoke always"),
    ENVIRONMENT("Terminal & device", "Linux tools, storage permissions and background access", "Workspace & device", "shizuku shell packages environment battery optimization keep alive"),
    BACKUP("Backups", "Encrypted settings and chat backups", "App & data", "restore history archive transfer password providers keys encryption"),
    USAGE("Usage", "Token usage, costs and activity over time", "App & data", "stats statistics cache tokens"),
    UPDATES("Updates", "Check GitHub Releases for a newer build", "App & data", "version release download"),
    SETUP("Setup guide", "Revisit the app setup steps", "App & data", "onboarding notifications permissions"),
    ABOUT("About", "Version, license, credits and links", "App & data", "about license mit copyright credits author sanuu github repository open source thanks"),
}

internal data class SettingsSearchEntry(
    val title: String,
    val description: String,
    val page: SettingsPage,
    val keywords: String = "",
    val primary: Boolean = false,
    /**
     * Visible label of the single control this entry points at, when it describes
     * a setting rather than a whole page. Opening the entry scrolls that control
     * into view and tints it (see SettingsAnchor); the label must match what the
     * control renders, since that is also what the user searched for.
     */
    val anchor: String? = null,
)

private val primarySettingsEntries = SettingsPage.entries.map { page ->
    SettingsSearchEntry(page.title, page.description, page, page.keywords, primary = true)
}

private val subSettingsEntries = listOf(
    SettingsSearchEntry("Manage providers", "Models & providers · Add connections, API keys and choose models", SettingsPage.MODELS, "provider api key openai anthropic gemini login model", anchor = "Manage providers"),
    SettingsSearchEntry("Dual planning models", "Models & providers · Configure separate plan and execute models", SettingsPage.MODELS, "planning plan model execute execution model", anchor = "Dual planning models"),
    SettingsSearchEntry("Plan model", "Models & providers · Choose the model used for planning", SettingsPage.MODELS, "planning dual", anchor = "Dual planning models"),
    SettingsSearchEntry("Execute model", "Models & providers · Choose the model used for execution", SettingsPage.MODELS, "execution dual", anchor = "Dual planning models"),
    SettingsSearchEntry("Default permission mode", "Agent behavior · Choose approval or full-access behavior", SettingsPage.AGENT, "permissions approval full access", anchor = "Default permission mode"),
    SettingsSearchEntry("Max context window", "Agent behavior · Set the maximum model context size", SettingsPage.AGENT, "context tokens limit", anchor = "Max context window"),
    SettingsSearchEntry("Tool-call iteration limit", "Agent behavior · Limit agent tool iterations", SettingsPage.AGENT, "iterations tools max limit", anchor = "Tool-call iteration limit"),
    SettingsSearchEntry("Project instructions (AGENTS.md)", "Agent behavior · Edit workspace instructions injected into every run", SettingsPage.AGENT, "agents instructions memory workspace prompt", anchor = "Project instructions (AGENTS.md)"),
    SettingsSearchEntry("Resume last chat on launch", "Chat & commands · Open your most recent chat when the app starts", SettingsPage.CHAT, "startup launch chat resume", anchor = "Resume last chat on launch"),
    SettingsSearchEntry("Workspace code index (Repo map)", "Chat & commands · Index project symbols for agent context", SettingsPage.CHAT, "repo map code index symbols", anchor = "Workspace code index (Repo map)"),
    SettingsSearchEntry("CodeGraph", "Code intelligence · Install CodeGraph and enable semantic indexes per workspace", SettingsPage.CODE_INTELLIGENCE, "codegraph explore node impact affected sync semantic graph"),
    SettingsSearchEntry("CodeGraph workspace index", "Code intelligence · Enable, sync or rebuild the active project index", SettingsPage.CODE_INTELLIGENCE, "project workspace initialize reindex graph"),
    SettingsSearchEntry("Slash commands", "Chat & commands · Manage custom slash commands", SettingsPage.CHAT, "shortcut snippets custom command", anchor = "Slash commands"),
    SettingsSearchEntry("Speech-to-text engine", "Voice input · Choose inbuilt recognition or Groq Whisper", SettingsPage.VOICE, "voice microphone groq whisper speech transcription", anchor = "Speech-to-text engine"),
    SettingsSearchEntry("Whisper model", "Voice input · Choose the Groq Whisper transcription model", SettingsPage.VOICE, "groq voice transcription", anchor = "Whisper Model"),
    SettingsSearchEntry("Theme", "Appearance · Choose System, Light, Dark or AMOLED", SettingsPage.APPEARANCE, "dark light amoled color", anchor = "Theme"),
    SettingsSearchEntry("Dynamic color", "Appearance · Match app colors to your wallpaper", SettingsPage.APPEARANCE, "material you wallpaper colors", anchor = "Dynamic color"),
    SettingsSearchEntry("Biometric app lock", "Privacy & security · Require fingerprint, face or device PIN", SettingsPage.PRIVACY, "fingerprint face pin authentication lock"),
    SettingsSearchEntry("Auto-lock timeout", "Privacy & security · Choose when the app locks again", SettingsPage.PRIVACY, "biometric timeout security"),
    SettingsSearchEntry("Allow screenshots", "Privacy & security · Control screenshot access", SettingsPage.PRIVACY, "screen capture screenshot privacy"),
    SettingsSearchEntry("GitHub sign in", "GitHub · Connect account access for repositories", SettingsPage.GITHUB, "oauth token pat repositories login"),
    SettingsSearchEntry("Web search backend", "Web search · Choose Keyless, Brave or Tavily", SettingsPage.SEARCH, "internet brave tavily api key keyless"),
    SettingsSearchEntry("MCP servers", "Connected tools · Add and configure MCP integrations", SettingsPage.MCP, "tools integrations server transport"),
    SettingsSearchEntry("Add workspace", "Workspaces · Add another project folder", SettingsPage.WORKSPACE, "folder storage project saf"),
    SettingsSearchEntry("Shared storage", "Terminal & device · Grant read/write access to device folders", SettingsPage.ENVIRONMENT, "files storage permission all files", anchor = "Shared storage"),
    SettingsSearchEntry("Shizuku (ADB privileges)", "Terminal & device · Configure elevated shell access", SettingsPage.ENVIRONMENT, "adb shell system paths permission", anchor = "System paths & any folder"),
    SettingsSearchEntry("Linux environment", "Terminal & device · Configure the Linux toolchain", SettingsPage.ENVIRONMENT, "terminal packages shell environment", anchor = "App workspace shell"),
    SettingsSearchEntry("Battery optimization", "Terminal & device · Keep background agent work alive", SettingsPage.ENVIRONMENT, "background keep alive battery"),
    SettingsSearchEntry("Export chats", "Chat backups · Save conversations to a backup file", SettingsPage.BACKUP, "backup export history archive"),
    SettingsSearchEntry("Import chats", "Chat backups · Restore conversations from a backup file", SettingsPage.BACKUP, "backup import restore history"),
    SettingsSearchEntry("Check for updates", "Updates · Check GitHub Releases for a newer build", SettingsPage.UPDATES, "version update release download"),
    SettingsSearchEntry("License", "About · The MIT license and what it means for forks", SettingsPage.ABOUT, "mit copyright permissive open source"),
    SettingsSearchEntry("Credits", "About · Open source projects behind AndroidHarness", SettingsPage.ABOUT, "thanks acknowledgements inspirations projects"),
    SettingsSearchEntry("Author", "About · Who builds AndroidHarness and where to find it", SettingsPage.ABOUT, "sanuu github repository maintainer source code"),
)

internal fun matchingSettingsEntries(query: String): List<SettingsSearchEntry> {
    val terms = query.trim().lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }
    if (terms.isEmpty()) return primarySettingsEntries
    return (primarySettingsEntries + subSettingsEntries).filter { entry ->
        val text = "${entry.title} ${entry.description} ${entry.page.title} ${entry.page.group} ${entry.keywords}".lowercase()
        terms.all { it in text }
    }.sortedWith(compareBy<SettingsSearchEntry> { it.primary }.thenBy { it.title })
}

internal fun matchingSettingsPages(query: String): List<SettingsPage> =
    matchingSettingsEntries(query).map { it.page }.distinct()

internal fun settingsDeepLink(target: String): SettingsPage? = when (target) {
    "planning" -> SettingsPage.MODELS
    "voice" -> SettingsPage.VOICE
    else -> null
}

/** The control a deep link should scroll to and tint, if it names a specific one. */
internal fun settingsDeepLinkAnchor(target: String): String? = when (target) {
    "planning" -> "Dual planning models"
    "voice" -> "Speech-to-text engine"
    else -> null
}

/** The control a search result should scroll to, or null when it opens a whole page. */
internal fun settingsAnchorFor(title: String): String? =
    subSettingsEntries.firstOrNull { it.title == title }?.anchor
