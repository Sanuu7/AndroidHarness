package com.androidharness.app.data

import android.net.Uri
import com.androidharness.app.AppContainer
import com.androidharness.app.llm.HarnessProvider
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.tools.mcp.McpServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
class SettingsBackupFile(
    val version: Int = 1,
    val settings: AppSettings,
    val providers: List<BackupProvider> = emptyList(),
    val servers: List<McpServerConfig> = emptyList(),
    val searchKeys: Map<String, String> = emptyMap(),
    val groqKey: String? = null,
)

@Serializable
class BackupProvider(val config: ProviderConfig, val customModels: List<String> = emptyList(), val apiKey: String? = null)

object SettingsBackupValidation {
    fun validate(file: SettingsBackupFile) {
        require(file.version == 1) { "Unsupported settings backup version." }
        require(file.providers.size <= 100 && file.servers.size <= 100) { "Too many connections." }
        require(file.settings.disabledSkills.size <= 1000 && file.settings.disabledSkills.all { it.length <= 500 }) { "Invalid skill preferences." }
        require(listOfNotNull(file.settings.activeModel, file.settings.planningModel, file.settings.executionModel).all { it.length <= 500 }) { "Invalid selected model." }
        require(file.settings.groqWhisperModel in setOf("whisper-large-v3", "whisper-large-v3-turbo")) { "Invalid voice model." }
        require(file.providers.map { it.config.id }.distinct().size == file.providers.size) { "Duplicate providers." }
        require(file.searchKeys.keys.all { it in setOf("brave", "tavily") }) { "Invalid credential slot." }
        val s = file.settings
        require(s.maxContextTokens in 8192..2_000_000 && s.maxOutputTokens in 1..1_000_000 && s.maxIterations in 0..10_000) { "Invalid model limits." }
        require(s.webSearchProvider in setOf("keyless", "brave", "tavily")) { "Invalid search provider." }
        require(s.voiceEngine in setOf("inbuilt", "groq")) { "Invalid voice engine." }
        val ids = file.providers.map { it.config.id }.toSet() + HarnessProvider.ID
        require(listOfNotNull(s.activeProviderId, s.planningProviderId, s.executionProviderId, s.subagentProviderId).all { it in ids }) { "Missing provider reference." }
        file.providers.forEach {
            require(!com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(it.config.id)) { "ChatGPT connections must be signed in on this device." }
            require(it.config.id.isNotBlank() && it.config.name.length in 1..200 && it.customModels.size <= 1000) { "Invalid provider." }
            val uri = java.net.URI(it.config.baseUrl)
            if (com.androidharness.app.local.LocalModelCatalog.isLocal(it.config.id)) {
                val id = it.config.id.removePrefix(com.androidharness.app.local.LocalModelCatalog.PROVIDER_PREFIX)
                require(com.androidharness.app.local.LocalModelCatalog.find(id) != null && it.config.model == it.config.id && it.config.baseUrl == "local://$id") { "Invalid local model reference." }
            } else {
                require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Invalid provider URL." }
            }
            require(it.customModels.all { model -> model.length in 1..500 }) { "Invalid custom model." }
        }
        require(file.servers.map { it.name }.distinct().size == file.servers.size) { "Duplicate MCP servers." }
        file.servers.forEach { require(it.name.length in 1..200 && it.type in setOf("stdio", "http", "sse")) { "Invalid MCP server." } }
    }
}

class SettingsBackup(private val c: AppContainer) {
    private val json = Json { encodeDefaults = true }

    suspend fun exportTo(uri: Uri, password: CharArray, includeKeys: Boolean) = withContext(Dispatchers.IO) {
        try {
            val current = c.settings.settings.first()
            val s = current.copy(
                activeProviderId = current.activeProviderId.takeUnless(com.androidharness.app.chatgpt.ChatGptProtocol::isProvider),
                activeModel = current.activeModel.takeUnless { com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(current.activeProviderId) },
                planningProviderId = current.planningProviderId.takeUnless(com.androidharness.app.chatgpt.ChatGptProtocol::isProvider),
                planningModel = current.planningModel.takeUnless { com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(current.planningProviderId) },
                executionProviderId = current.executionProviderId.takeUnless(com.androidharness.app.chatgpt.ChatGptProtocol::isProvider),
                executionModel = current.executionModel.takeUnless { com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(current.executionProviderId) },
                subagentProviderId = current.subagentProviderId.takeUnless(com.androidharness.app.chatgpt.ChatGptProtocol::isProvider),
                subagentModel = current.subagentModel.takeUnless { com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(current.subagentProviderId) },
                pinnedSessions = emptySet(), archivedSessions = emptySet(), lastActiveSessionId = null,
                permissionMode = com.androidharness.app.agent.PermissionMode.CONFIRM_RISKY,
                biometricLockEnabled = false, biometricLockTimeoutMinutes = 0, allowScreenshots = false,
            )
            val localReferences = listOfNotNull(s.activeProviderId, s.planningProviderId, s.executionProviderId, s.subagentProviderId)
                .filter(com.androidharness.app.local.LocalModelCatalog::isLocal)
                .map { it.removePrefix(com.androidharness.app.local.LocalModelCatalog.PROVIDER_PREFIX) }.toSet()
            val providers = (c.providers.providers.first() + c.localModels.configs(localReferences)).distinctBy { it.id }.filterNot { com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(it.id) }.map { config ->
                BackupProvider(config, c.providers.customModels(config.id).map { it.id },
                    if (includeKeys && config.id != HarnessProvider.ID) c.keys.getKey(config.id) else null)
            }
            val file = SettingsBackupFile(settings = s, providers = providers,
                servers = if (includeKeys) c.mcp.servers.value.map { it.copy(enabled = false) } else emptyList(),
                searchKeys = if (includeKeys) listOf("brave", "tavily").mapNotNull { name -> c.keys.searchApiKey(name)?.let { name to it } }.toMap() else emptyMap(),
                groqKey = if (includeKeys) c.keys.groqApiKey() else null)
            SettingsBackupValidation.validate(file)
            val plain = json.encodeToString(SettingsBackupFile.serializer(), file).toByteArray(Charsets.UTF_8)
            val encrypted = try { BackupEncryption.encrypt(plain, password) } finally { plain.fill(0) }
            c.appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(encrypted) }
                ?: error("Cannot open backup destination.")
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
            try {
                json.decodeFromString(SettingsBackupFile.serializer(), plain.toString(Charsets.UTF_8))
                    .also(SettingsBackupValidation::validate)
            } finally { plain.fill(0) }
        } finally { password.fill('\u0000') }
    }

    suspend fun restore(file: SettingsBackupFile) = withContext(Dispatchers.IO) {
        SettingsBackupValidation.validate(file)
        require(c.runManager.runningSessionIds.value.isEmpty()) { "Stop running tasks before restoring settings." }
        val ids = mutableMapOf(HarnessProvider.ID to HarnessProvider.ID)
        file.providers.filter { com.androidharness.app.local.LocalModelCatalog.isLocal(it.config.id) }.forEach { entry ->
            ids[entry.config.id] = entry.config.id
        }
        file.providers.filterNot { it.config.id == HarnessProvider.ID || com.androidharness.app.local.LocalModelCatalog.isLocal(it.config.id) }.forEach { entry ->
            val p = entry.config
            val restored = c.providers.add(p.name + " (restored)", p.type, p.baseUrl, p.model, entry.apiKey.orEmpty())
            ids[p.id] = restored.id
            entry.customModels.forEach { c.providers.addCustomModel(restored.id, it) }
        }
        file.providers.firstOrNull { it.config.id == HarnessProvider.ID }?.customModels?.forEach {
            c.providers.addCustomModel(HarnessProvider.ID, it)
        }
        if (file.servers.isNotEmpty()) c.mcp.importDisabledServers(file.servers)
        file.searchKeys.forEach { (name, key) -> if (c.keys.searchApiKey(name) == null) c.keys.putSearchApiKey(name, key) }
        file.groqKey?.let { if (c.keys.groqApiKey() == null) c.keys.putGroqApiKey(it) }
        val s = file.settings
        c.settings.restorePortable(s.copy(activeProviderId = ids[s.activeProviderId],
            planningProviderId = ids[s.planningProviderId], executionProviderId = ids[s.executionProviderId],
            subagentProviderId = ids[s.subagentProviderId]))
    }
}
