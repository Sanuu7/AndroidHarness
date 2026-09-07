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
    VOICE("Voice input", "Speech recognition and voice provider preferences", "Your assistant", "microphone groq whisper transcription speech"),
    SKILLS("Skills", "Manage the playbooks your assistant can use", "Your assistant", "catalog instructions add skills"),
    APPEARANCE("Appearance", "Theme, colors and the look of your app", "Personalize", "dark light amoled system wallpaper dynamic color"),
    PRIVACY("Privacy & security", "App lock, biometrics and screenshot access", "Personalize", "fingerprint pin authentication screenshots"),
    GITHUB("GitHub", "Connect your account and manage repository access", "Connections", "oauth login git pat token scopes"),
    SEARCH("Web search", "Search provider and API credentials", "Connections", "brave tavily keyless internet"),
    MCP("Connected tools", "Add and configure MCP servers", "Connections", "mcp integrations servers transport"),
    WORKSPACE("Workspaces", "Choose, add and manage your project folders", "Workspace & device", "storage files saf folder project"),
    ENVIRONMENT("Terminal & device", "Linux tools, storage permissions and background access", "Workspace & device", "shizuku shell packages environment battery optimization keep alive"),
    BACKUP("Chat backups", "Export and import your conversations", "App & data", "restore history archive transfer"),
    USAGE("Usage", "Token usage, costs and activity over time", "App & data", "stats statistics cache tokens"),
    UPDATES("About & updates", "App version and available updates", "App & data", "version release download"),
    SETUP("Setup guide", "Revisit the app setup steps", "App & data", "onboarding notifications permissions"),
}

internal fun matchingSettingsPages(query: String): List<SettingsPage> {
    val terms = query.trim().lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }
    return SettingsPage.entries.filter { page ->
        val text = "${page.title} ${page.description} ${page.group} ${page.keywords}".lowercase()
        terms.all { it in text }
    }
}

internal fun settingsDeepLink(target: String): SettingsPage? = when (target) {
    "planning" -> SettingsPage.MODELS
    "voice" -> SettingsPage.VOICE
    else -> null
}
