package com.androidharness.app.data

import android.net.Uri
import com.androidharness.app.AppContainer
import com.androidharness.app.llm.HarnessProvider
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.local.LocalModelCatalog
import com.androidharness.app.tools.mcp.McpServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class SettingsBackupFile(
    val version: Int = VERSION,
    val settings: AppSettings,
    val providers: List<BackupProvider> = emptyList(),
    val servers: List<McpServerConfig> = emptyList(),
    val searchKeys: Map<String, String> = emptyMap(),
    /** Every saved search key with its rotation switch; [searchKeys] keeps the first one for older readers. */
    val searchKeyPools: Map<String, com.androidharness.app.llm.ProviderKeyPool> = emptyMap(),
    val groqKey: String? = null,
    val memories: List<BackupMemory> = emptyList(),
    val providerProxy: com.androidharness.app.llm.ProviderProxy = com.androidharness.app.llm.ProviderProxy(),
) {
    companion object {
        /** v4 includes encrypted key pools and provider proxy controls. */
        const val VERSION = 4
    }
}

@Serializable
class BackupProvider(val config: ProviderConfig, val customModels: List<String> = emptyList(), val apiKey: String? = null,
    val headers: List<com.androidharness.app.llm.ProviderHeader> = emptyList(),
    val keyPool: com.androidharness.app.llm.ProviderKeyPool? = null,
    val proxy: com.androidharness.app.llm.ProviderProxy = com.androidharness.app.llm.ProviderProxy())

/** Local models and device sign-ins are not portable API connections. */
object SettingsBackupPolicy {
    private fun isLocal(config: ProviderConfig): Boolean =
        LocalModelCatalog.isLocal(config.id) || config.baseUrl.startsWith("local://")

    fun canExport(config: ProviderConfig): Boolean =
        !isLocal(config) && !com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(config.id)

    private fun filterSelections(settings: AppSettings, keep: (String) -> Boolean): AppSettings {
        val active = settings.activeProviderId?.let(keep) == true
        val planning = settings.planningProviderId?.let(keep) == true
        val execution = settings.executionProviderId?.let(keep) == true
        val subagent = settings.subagentProviderId?.let(keep) == true
        return settings.copy(
            chatAgents = settings.chatAgents.map { it.copy(model = it.model?.takeIf { choice -> keep(choice.providerId) }) },
            activeProviderId = settings.activeProviderId.takeIf { active },
            activeModel = settings.activeModel.takeIf { active },
            planningProviderId = settings.planningProviderId.takeIf { planning },
            planningModel = settings.planningModel.takeIf { planning },
            executionProviderId = settings.executionProviderId.takeIf { execution },
            executionModel = settings.executionModel.takeIf { execution },
            subagentProviderId = settings.subagentProviderId.takeIf { subagent },
            subagentModel = settings.subagentModel.takeIf { subagent },
            providerOrder = settings.providerOrder.filter(keep),
            disabledProviderIds = settings.disabledProviderIds.filter(keep).toSet(),
            fallbackPolicy = settings.fallbackPolicy.copy(targets = settings.fallbackPolicy.targets.filter { keep(it.providerId) }),
        )
    }

    fun exportSettings(settings: AppSettings, providerIds: Set<String>): AppSettings =
        filterSelections(settings) { id ->
            id in providerIds && !LocalModelCatalog.isLocal(id) &&
                !com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(id)
        }.copy(
            pinnedSessions = emptySet(), archivedSessions = emptySet(), lastActiveSessionId = null,
            lastConversationKind = ConversationKind.CODE,
            permissionMode = com.androidharness.app.agent.PermissionMode.CONFIRM_RISKY,
            biometricLockEnabled = false, biometricLockTimeoutMinutes = 0, allowScreenshots = false,
            localModelExperimental = false, liveMode = LiveModeSettings(),
        )

    /** Older v1 files can contain local references; ignore them before preview and restore. */
    fun withoutLocalModels(file: SettingsBackupFile): SettingsBackupFile {
        val removedIds = file.providers.filter { isLocal(it.config) }.map { it.config.id }.toSet()
        return file.copy(
            settings = filterSelections(file.settings) { !LocalModelCatalog.isLocal(it) && it !in removedIds }
                .copy(localModelExperimental = false),
            providers = file.providers.filterNot { isLocal(it.config) },
        )
    }
}

/** Never show raw exceptions: provider URLs and file-provider errors may contain secrets. */
fun settingsBackupExportError(error: Exception): String = when (error) {
    is SettingsBackupValidationException -> "Export failed. ${error.problem.hint}"
    is SettingsBackupCredentialsException -> "Export failed. Saved credentials could not be read. Check your API connections and search or voice keys."
    is SecurityException -> "Export failed. Choose a destination you can write to."
    is java.io.IOException -> "Export failed. Check the destination and available space, then retry. The file may be incomplete."
    is java.security.GeneralSecurityException -> "Export failed. Password encryption is unavailable on this device."
    is IllegalArgumentException -> "Export failed. Check provider connections, model settings and password length."
    else -> "Export failed. Check your settings and try again."
}

enum class SettingsBackupProblem(val hint: String) {
    VERSION("This settings backup version is unsupported."),
    CONNECTION_COUNT("The backup supports up to 100 API providers and 100 MCP servers."),
    SKILLS("Some disabled skill names are too long or there are too many. Review Skills settings."),
    MODEL_SELECTION("A selected model ID is too long. Review your model selections."),
    VOICE("A saved voice setting is unsupported. Choose the voice engine and model again in Voice settings."),
    PROVIDERS("A saved provider is invalid or duplicated. Review Manage providers in Models settings."),
    SEARCH("A saved search setting is unsupported. Review Web search settings."),
    MODEL_LIMITS("Saved model limits are outside the backup range. Review Run limits in Agent settings."),
    PROVIDER_REFERENCE("A selected provider is missing. Choose your models again in Models and Subagent settings."),
    PROVIDER_URL("A saved provider URL is invalid. Review Manage providers in Models settings."),
    CUSTOM_MODELS("A custom model ID is empty or too long. Review Manage providers in Models settings."),
    PROVIDER_CONTROLS("An API key pool or proxy setting is invalid. Review connection controls."),
    PROVIDER_HEADERS("A custom provider header is invalid. Review Manage providers in Models settings."),
    MCP("An MCP server name or transport is invalid, or names are duplicated. Review MCP servers settings."),
    CHAT_AGENTS("A saved Chat agent is invalid. Review Chat agents in Settings."),
    MEMORY("A saved memory is invalid or duplicated. Review Memory settings."),
    APPEARANCE("A saved palette or Harness Canvas setting is invalid. Review Appearance settings."),
}

class SettingsBackupValidationException(val problem: SettingsBackupProblem) : IllegalArgumentException(problem.hint)
private class SettingsBackupCredentialsException(cause: Exception) : Exception(cause)

object SettingsBackupValidation {
    private fun check(valid: Boolean, problem: SettingsBackupProblem) {
        if (!valid) throw SettingsBackupValidationException(problem)
    }

    fun validate(file: SettingsBackupFile) {
        check(file.version in 1..SettingsBackupFile.VERSION, SettingsBackupProblem.VERSION)
        check(file.providers.size <= 100 && file.servers.size <= 100, SettingsBackupProblem.CONNECTION_COUNT)
        check(file.settings.disabledSkills.size <= 1000 && file.settings.disabledSkills.all { it.length <= 500 }, SettingsBackupProblem.SKILLS)
        check(listOfNotNull(file.settings.activeModel, file.settings.planningModel, file.settings.executionModel).all { it.length <= 500 }, SettingsBackupProblem.MODEL_SELECTION)
        check(file.settings.fallbackPolicy.valid(), SettingsBackupProblem.MODEL_SELECTION)
        check(file.settings.groqWhisperModel in setOf("whisper-large-v3", "whisper-large-v3-turbo"), SettingsBackupProblem.VOICE)
        check(file.providers.map { it.config.id }.distinct().size == file.providers.size, SettingsBackupProblem.PROVIDERS)
        check(file.settings.providerOrder.size <= 100 && file.settings.disabledProviderIds.size <= 100 &&
            (file.settings.providerOrder + file.settings.disabledProviderIds).all { it.length <= 500 }, SettingsBackupProblem.PROVIDERS)
        check(file.searchKeys.keys.all { it in setOf("brave", "tavily") }, SettingsBackupProblem.SEARCH)
        check(file.searchKeyPools.all { (name, pool) -> name in setOf("brave", "tavily") && pool.error() == null }, SettingsBackupProblem.SEARCH)
        val s = file.settings
        check(s.customPalettes.size <= 24 && s.customPalettes.all { it.valid() } && s.customPalettes.map { it.id }.distinct().size == s.customPalettes.size &&
            (s.activeCustomPaletteId == null || s.customPalettes.any { it.id == s.activeCustomPaletteId }), SettingsBackupProblem.APPEARANCE)
        check(s.harnessCanvas.dim.isFinite() && s.harnessCanvas.dim in 0f..1f &&
            (s.harnessCanvas.imageId == null || canvasImageIdPattern.matches(s.harnessCanvas.imageId)) &&
            (s.harnessCanvas.seed == null || s.harnessCanvas.seed in 0xFF000000..0xFFFFFFFF), SettingsBackupProblem.APPEARANCE)
        check(runCatching { ChatAgentProfile.encodeList(s.chatAgents) }.isSuccess, SettingsBackupProblem.CHAT_AGENTS)
        check(s.maxContextTokens in 8192..2_000_000 && s.maxOutputTokens in 1..1_000_000 && s.maxIterations in 0..10_000, SettingsBackupProblem.MODEL_LIMITS)
        check(s.webSearchProvider in setOf("keyless", "brave", "tavily"), SettingsBackupProblem.SEARCH)
        check(s.voiceEngine in setOf("inbuilt", "groq"), SettingsBackupProblem.VOICE)
        val ids = file.providers.map { it.config.id }.toSet() + HarnessProvider.ID
        check(listOfNotNull(s.activeProviderId, s.planningProviderId, s.executionProviderId, s.subagentProviderId).all { it in ids }, SettingsBackupProblem.PROVIDER_REFERENCE)
        check(file.providerProxy.error() == null, SettingsBackupProblem.PROVIDER_CONTROLS)
        file.providers.forEach {
            check(it.keyPool?.error() == null && it.proxy.error() == null, SettingsBackupProblem.PROVIDER_CONTROLS)
            check(com.androidharness.app.llm.ProviderHeaders.error(it.headers) == null, SettingsBackupProblem.PROVIDER_HEADERS)
            check(!com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(it.config.id), SettingsBackupProblem.PROVIDERS)
            check(it.config.id.isNotBlank() && it.config.name.length in 1..200 && it.customModels.size <= 1000, SettingsBackupProblem.PROVIDERS)
            check(!LocalModelCatalog.isLocal(it.config.id), SettingsBackupProblem.PROVIDERS)
            // Use the same URL parser as API requests. java.net.URI rejects valid
            // HTTP endpoints with Unicode or underscore hostnames, for example.
            val url = it.config.baseUrl.toHttpUrlOrNull()
            val uri = runCatching { java.net.URI(it.config.baseUrl) }.getOrNull()
            check(url != null && uri != null && uri.scheme.lowercase() in setOf("http", "https") &&
                !uri.rawAuthority.isNullOrBlank() && uri.rawUserInfo == null &&
                url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty(), SettingsBackupProblem.PROVIDER_URL)
            check(it.customModels.all { model -> model.length in 1..500 }, SettingsBackupProblem.CUSTOM_MODELS)
        }
        check(file.servers.map { it.name }.distinct().size == file.servers.size, SettingsBackupProblem.MCP)
        file.servers.forEach { check(it.name.length in 1..200 && it.type in setOf("stdio", "http", "sse"), SettingsBackupProblem.MCP) }
        check(file.memories.size <= 1000, SettingsBackupProblem.MEMORY)
        check(file.memories.map { it.id }.distinct().size == file.memories.size, SettingsBackupProblem.MEMORY)
        val scopes = MemoryScope.entries.mapTo(mutableSetOf()) { it.name }
        val categories = MemoryCategory.entries.mapTo(mutableSetOf()) { it.name }
        file.memories.forEach { memory ->
            check(memory.id.isNotBlank() && memory.id.length <= 200, SettingsBackupProblem.MEMORY)
            check(memory.text.isNotBlank() && memory.text.length <= if (memory.isSummary) MemoryConsolidation.MAX_CHARS else MemoryText.MAX_ENTRY_CHARS, SettingsBackupProblem.MEMORY)
            check(memory.category in categories && memory.scope in scopes && memory.section in MemorySection.entries.map { it.name }, SettingsBackupProblem.MEMORY)
            check((memory.consolidatedInto?.length ?: 0) <= 200 && memory.consolidatedInto != memory.id, SettingsBackupProblem.MEMORY)
            check(memory.scope != MemoryScope.PROJECT.name || !memory.projectId.isNullOrBlank(), SettingsBackupProblem.MEMORY)
            check((memory.projectId?.length ?: 0) <= 500 && (memory.sourceSessionId?.length ?: 0) <= 200, SettingsBackupProblem.MEMORY)
            check(memory.agentId == null || memory.agentId.length in 1..100 && memory.section == "CHAT", SettingsBackupProblem.MEMORY)
            check(memory.useCount >= 0, SettingsBackupProblem.MEMORY)
        }
    }
}

/** Backups written before codeStart existed carry only the legacy Code resume toggle. */
internal fun legacyCodeStart(file: SettingsBackupFile, text: String): SettingsBackupFile {
    val hasCodeStart = runCatching {
        Json { encodeDefaults = true }.parseToJsonElement(text).jsonObject["settings"]?.jsonObject?.containsKey("codeStart") == true
    }.getOrDefault(true)
    if (hasCodeStart) return file
    val derived = if (file.settings.resumeLastChat) CodeStartMode.LAST_CODE else CodeStartMode.NEW_CODE
    return file.copy(settings = file.settings.copy(codeStart = derived))
}

class SettingsBackup(private val c: AppContainer) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun <T> credential(read: () -> T?): T? = try { read() }
        catch (e: Exception) { throw SettingsBackupCredentialsException(e) }

    suspend fun exportTo(uri: Uri, password: CharArray, includeKeys: Boolean) = withContext(Dispatchers.IO) {
        try {
            val current = c.settings.settings.first()
            val configs = c.providers.providers.first().distinctBy { it.id }.filter(SettingsBackupPolicy::canExport)
            val s = SettingsBackupPolicy.exportSettings(current, configs.map { it.id }.toSet() + HarnessProvider.ID)
            val providers = configs.map { config ->
                BackupProvider(config, c.providers.customModels(config.id).map { it.id },
                    if (includeKeys && config.id != HarnessProvider.ID) credential { c.keys.getKey(config.id) } else null,
                    if (includeKeys) config.headers else emptyList(),
                    if (com.androidharness.app.llm.ProviderHeaders.editable(config)) c.keys.providerKeyPool(config.id).let { pool ->
                        if (includeKeys) pool else pool.copy(keys = emptyList())
                    } else null,
                    c.keys.providerProxy(config.id).let { if (includeKeys) it else it.withoutCredentials() })
            }
            val file = SettingsBackupFile(settings = s, providers = providers,
                servers = if (includeKeys) c.mcp.servers.value.map { it.copy(enabled = false) } else emptyList(),
                searchKeys = if (includeKeys) listOf("brave", "tavily").mapNotNull { name -> credential { c.keys.searchApiKey(name) }?.let { name to it } }.toMap() else emptyMap(),
                searchKeyPools = if (includeKeys) listOf("brave", "tavily").mapNotNull { name ->
                    credential { c.keys.searchKeyPool(name) }?.takeIf { it.keys.isNotEmpty() }?.let { name to it }
                }.toMap() else emptyMap(),
                groqKey = if (includeKeys) credential { c.keys.groqApiKey() } else null,
                memories = c.memory.memories.first().map { it.toBackupMemory() },
                providerProxy = c.keys.providerProxy(null).let { if (includeKeys) it else it.withoutCredentials() })
            SettingsBackupValidation.validate(file)
            val plain = json.encodeToString(SettingsBackupFile.serializer(), file).toByteArray(Charsets.UTF_8)
            val encrypted = try { BackupEncryption.encrypt(plain, password) } finally { plain.fill(0) }
            c.appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(encrypted) }
                ?: throw java.io.IOException("Cannot open backup destination.")
        } finally { password.fill('\u0000') }
    }

    suspend fun read(uri: Uri, password: CharArray): SettingsBackupFile = withContext(Dispatchers.IO) {
        try {
            val bytes = c.appContext.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= BackupEncryption.MAX_BYTES) { "Backup is too large." }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } ?: error("Cannot open backup.")
            val plain = BackupEncryption.decrypt(bytes, password)
            val text = plain.toString(Charsets.UTF_8)
            try {
                val decoded = json.decodeFromString(SettingsBackupFile.serializer(), text)
                legacyCodeStart(decoded, text)
                    .let(SettingsBackupPolicy::withoutLocalModels)
                    .also(SettingsBackupValidation::validate)
            } finally { plain.fill(0) }
        } finally { password.fill('\u0000') }
    }

    suspend fun restore(backup: SettingsBackupFile) = withContext(Dispatchers.IO) {
        val file = SettingsBackupPolicy.withoutLocalModels(backup)
        SettingsBackupValidation.validate(file)
        require(c.runManager.runningSessionIds.value.isEmpty()) { "Stop running tasks before restoring settings." }
        val ids = mutableMapOf(HarnessProvider.ID to HarnessProvider.ID)
        file.providers.filterNot { it.config.id == HarnessProvider.ID }.forEach { entry ->
            val p = entry.config
            val restored = c.providers.add(p.name + " (restored)", p.type, p.baseUrl, p.model, entry.apiKey.orEmpty(), entry.headers)
            ids[p.id] = restored.id
            c.providers.saveControls(restored, entry.keyPool, entry.proxy)
            entry.customModels.forEach { c.providers.addCustomModel(restored.id, it) }
        }
        file.providers.firstOrNull { it.config.id == HarnessProvider.ID }?.customModels?.forEach {
            c.providers.addCustomModel(HarnessProvider.ID, it)
        }
        if (file.version >= 4) c.providers.saveGlobalProxy(file.providerProxy)
        file.providers.firstOrNull { it.config.id == HarnessProvider.ID }?.let { entry ->
            c.keys.putProviderControls(HarnessProvider.ID, null, entry.proxy)
        }
        if (file.servers.isNotEmpty()) c.mcp.importDisabledServers(file.servers)
        file.searchKeyPools.forEach { (name, pool) -> if (c.keys.searchApiKey(name) == null) c.keys.putSearchKeyPool(name, pool) }
        file.searchKeys.forEach { (name, key) -> if (c.keys.searchApiKey(name) == null) c.keys.putSearchApiKey(name, key) }
        file.groqKey?.let { if (c.keys.groqApiKey() == null) c.keys.putGroqApiKey(it) }
        val s = file.settings
        c.settings.restorePortable(s.copy(chatAgents = s.chatAgents.map { agent -> agent.copy(model = agent.model?.let { choice -> ids[choice.providerId]?.let { choice.copy(providerId = it) } }) },
            activeProviderId = ids[s.activeProviderId],
            planningProviderId = ids[s.planningProviderId], executionProviderId = ids[s.executionProviderId],
            subagentProviderId = ids[s.subagentProviderId],
            providerOrder = s.providerOrder.mapNotNull(ids::get),
            disabledProviderIds = s.disabledProviderIds.mapNotNull(ids::get).toSet(),
            fallbackPolicy = s.fallbackPolicy.copy(targets = s.fallbackPolicy.targets.mapNotNull { target ->
                ids[target.providerId]?.let { target.copy(providerId = it) }
            })))
        // Memories merge instead of replacing. A project that is not on this
        // device is kept as a global memory rather than dropped.
        val availableProjects = c.workspace.projects.first().map { it.id }.toSet()
        c.memory.importMissing(file.memories) { id -> id?.takeIf { it in availableProjects } }
    }
}
