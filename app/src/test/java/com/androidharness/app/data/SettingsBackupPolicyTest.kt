package com.androidharness.app.data

import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.llm.HarnessProvider
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.tools.mcp.McpServerConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SettingsBackupPolicyTest {
    @Test fun experimentalModelConsentCannotBeEnabledByImportingSettings() {
        assertFalse(Json.decodeFromString(AppSettings.serializer(), "{}").localModelExperimental)
        val optedIn = AppSettings(localModelExperimental = true)
        assertFalse(SettingsBackupPolicy.exportSettings(optedIn, emptySet()).localModelExperimental)
        assertFalse(SettingsBackupPolicy.withoutLocalModels(SettingsBackupFile(settings = optedIn)).settings.localModelExperimental)
    }
    @Test fun globalChatInstructionsArePortableAndOlderSettingsDefaultToEmpty() {
        val original = AppSettings(chatInstructions = "Answer clearly in German.")
        val exported = SettingsBackupPolicy.exportSettings(original, emptySet())
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), exported))
        assertEquals(original.chatInstructions, restored.chatInstructions)
        assertEquals("", json.decodeFromString(AppSettings.serializer(), "{}").chatInstructions)
    }
    private fun api(id: String = "api") = ProviderConfig(id, "API", ProviderType.OPENAI_COMPAT, "https://example.com/v1", "remote-model")
    private fun local(id: String) = ProviderConfig("local-model:$id", "Local", ProviderType.OPENAI_COMPAT, "local://$id", "local-model:$id")

    @Test fun apiCredentialsSurviveEncryptedBackupWithInstalledAndSelectedLocalModels() {
        val remote = BackupProvider(api(), listOf("custom-api-model"), "test-api-key")
        val configs = listOf(remote.config, HarnessProvider.config, local("qwen-05b"), local("custom-import"))
            .filter(SettingsBackupPolicy::canExport)
        val settings = SettingsBackupPolicy.exportSettings(
            AppSettings(activeProviderId = "local-model:custom-import", activeModel = "local-model:custom-import",
                planningProviderId = "api", planningModel = "custom-api-model",
                executionProviderId = "local-model:qwen-05b", executionModel = "local-model:qwen-05b",
                subagentProviderId = "local-model:custom-import", subagentModel = "local-model:custom-import"),
            configs.map { it.id }.toSet(),
        )
        val original = SettingsBackupFile(settings = settings,
            providers = configs.map { if (it.id == "api") remote else BackupProvider(it) },
            servers = listOf(McpServerConfig("test", enabled = false, env = mapOf("API_KEY" to "test-mcp-key"))),
            searchKeys = mapOf("brave" to "test-search-key"), groqKey = "test-voice-key")
        SettingsBackupValidation.validate(original)
        val json = Json { encodeDefaults = true }
        val plain = json.encodeToString(SettingsBackupFile.serializer(), original)
        assertFalse(plain.contains("local://"))
        assertFalse(plain.contains("local-model:"))
        assertFalse(plain.contains("custom-import"))
        val password = "test-password-123".toCharArray()
        val encrypted = BackupEncryption.encrypt(plain.toByteArray(), password)
        val restored = json.decodeFromString(SettingsBackupFile.serializer(), BackupEncryption.decrypt(encrypted, password).toString(Charsets.UTF_8))
        SettingsBackupValidation.validate(restored)
        assertNull(restored.settings.activeProviderId)
        assertNull(restored.settings.activeModel)
        assertNull(restored.settings.executionProviderId)
        assertNull(restored.settings.executionModel)
        assertNull(restored.settings.subagentProviderId)
        assertNull(restored.settings.subagentModel)
        assertEquals("api", restored.settings.planningProviderId)
        assertEquals("custom-api-model", restored.settings.planningModel)
        assertEquals("test-api-key", restored.providers.first { it.config.id == "api" }.apiKey)
        assertEquals(listOf("custom-api-model"), restored.providers.first { it.config.id == "api" }.customModels)
        assertEquals("test-mcp-key", restored.servers.single().env["API_KEY"])
        assertEquals(mapOf("brave" to "test-search-key"), restored.searchKeys)
        assertEquals("test-voice-key", restored.groqKey)
    }

    @Test fun everySelectionRoleClearsLocalOAuthAndDeletedProviders() {
        for (id in listOf("local-model:qwen-05b", "local-model:custom-import", "chatgpt:test-account", "deleted-api")) {
            val settings = SettingsBackupPolicy.exportSettings(AppSettings(
                activeProviderId = id, activeModel = "model", planningProviderId = id, planningModel = "model",
                executionProviderId = id, executionModel = "model", subagentProviderId = id, subagentModel = "model"),
                setOf("api", HarnessProvider.ID))
            assertNull(settings.activeProviderId)
            assertNull(settings.activeModel)
            assertNull(settings.planningProviderId)
            assertNull(settings.planningModel)
            assertNull(settings.executionProviderId)
            assertNull(settings.executionModel)
            assertNull(settings.subagentProviderId)
            assertNull(settings.subagentModel)
            SettingsBackupValidation.validate(SettingsBackupFile(settings = settings))
        }
    }

    @Test fun localEndpointsAndOAuthAccountsAreExcludedBeforeReadingCredentials() {
        assertTrue(SettingsBackupPolicy.canExport(api()))
        assertTrue(SettingsBackupPolicy.canExport(HarnessProvider.config))
        assertFalse(SettingsBackupPolicy.canExport(local("qwen-05b")))
        assertFalse(SettingsBackupPolicy.canExport(local("custom-import")))
        assertFalse(SettingsBackupPolicy.canExport(api().copy(baseUrl = "local://custom-import")))
        assertFalse(SettingsBackupPolicy.canExport(api("chatgpt:test-account")))
    }

    @Test fun olderBackupDropsAllLocalReferencesWithoutLookingUpInstalledModels() {
        val old = SettingsBackupFile(settings = AppSettings(
            activeProviderId = "local-model:custom-import", activeModel = "local-model:custom-import",
            planningProviderId = "api", planningModel = "remote-model",
            executionProviderId = "old-local", executionModel = "local-model:custom-import",
            subagentProviderId = "local-model:already-removed", subagentModel = "local-model:already-removed"),
            providers = listOf(BackupProvider(api(), apiKey = "test-key"), BackupProvider(local("custom-import")),
                BackupProvider(api("old-local").copy(baseUrl = "local://custom-import"))),
            servers = listOf(McpServerConfig("test")), searchKeys = mapOf("tavily" to "search"), groqKey = "voice")
        val json = Json { encodeDefaults = true }
        val decoded = json.decodeFromString(SettingsBackupFile.serializer(), json.encodeToString(SettingsBackupFile.serializer(), old))
        val portable = SettingsBackupPolicy.withoutLocalModels(decoded)
        SettingsBackupValidation.validate(portable)
        assertEquals(listOf("api"), portable.providers.map { it.config.id })
        assertEquals("test-key", portable.providers.single().apiKey)
        assertNull(portable.settings.activeProviderId)
        assertNull(portable.settings.activeModel)
        assertNull(portable.settings.executionProviderId)
        assertNull(portable.settings.executionModel)
        assertNull(portable.settings.subagentProviderId)
        assertNull(portable.settings.subagentModel)
        assertEquals("api", portable.settings.planningProviderId)
        assertEquals("remote-model", portable.settings.planningModel)
        assertEquals(old.servers, portable.servers)
        assertEquals(old.searchKeys, portable.searchKeys)
        assertEquals(old.groqKey, portable.groqKey)
    }

    @Test fun olderBackupStillRejectsInvalidRemoteConnectionsAndVersions() {
        for (old in listOf(
            SettingsBackupFile(version = SettingsBackupFile.VERSION + 1, settings = AppSettings(), providers = listOf(BackupProvider(local("custom-import")))),
            SettingsBackupFile(settings = AppSettings(), providers = listOf(BackupProvider(api().copy(baseUrl = "file:///secret")))),
            SettingsBackupFile(settings = AppSettings(planningProviderId = "missing-api")),
            SettingsBackupFile(settings = AppSettings(), providers = listOf(BackupProvider(api("chatgpt:test-account")))),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                SettingsBackupValidation.validate(SettingsBackupPolicy.withoutLocalModels(old))
            }
        }
    }

    @Test fun exportKeepsPreferencesWhileExcludingDeviceState() {
        val settings = SettingsBackupPolicy.exportSettings(AppSettings(
            activeProviderId = "api", activeModel = "remote-model", planningProviderId = HarnessProvider.ID, planningModel = "custom-api-model",
            executionProviderId = "api", executionModel = "remote-model", subagentProviderId = "api", subagentModel = "small",
            themeMode = ThemeMode.AMOLED, maxContextTokens = 262_144, permissionMode = PermissionMode.FULL_ACCESS,
            progressBarStyle = ProgressBarStyle.SQUIGGLY,
            biometricLockEnabled = true, biometricLockTimeoutMinutes = 5, allowScreenshots = true,
            pinnedSessions = setOf("chat"), archivedSessions = setOf("old-chat"), lastActiveSessionId = "chat"),
            setOf("api", HarnessProvider.ID))
        assertEquals("api", settings.activeProviderId)
        assertEquals("remote-model", settings.activeModel)
        assertEquals(HarnessProvider.ID, settings.planningProviderId)
        assertEquals("custom-api-model", settings.planningModel)
        assertEquals("api", settings.executionProviderId)
        assertEquals("remote-model", settings.executionModel)
        assertEquals("api", settings.subagentProviderId)
        assertEquals("small", settings.subagentModel)
        assertEquals(ThemeMode.AMOLED, settings.themeMode)
        val reopened = Json.decodeFromString(AppSettings.serializer(), Json.encodeToString(AppSettings.serializer(), settings))
        assertEquals(ProgressBarStyle.SQUIGGLY, reopened.progressBarStyle)
        assertEquals(ProgressBarStyle.SQUIGGLY, Json.decodeFromString(AppSettings.serializer(), "{}").progressBarStyle)
        assertEquals(262_144, settings.maxContextTokens)
        assertEquals(PermissionMode.CONFIRM_RISKY, settings.permissionMode)
        assertFalse(settings.biometricLockEnabled)
        assertEquals(0, settings.biometricLockTimeoutMinutes)
        assertFalse(settings.allowScreenshots)
        assertTrue(settings.pinnedSessions.isEmpty())
        assertTrue(settings.archivedSessions.isEmpty())
        assertNull(settings.lastActiveSessionId)
    }

    @Test fun exportErrorsGiveSafeRecoveryHintsWithoutExceptionDetails() {
        val secret = "secret-key-in-provider-url"
        val cases = listOf(
            SecurityException(secret) to "destination",
            java.io.FileNotFoundException(secret) to "destination",
            java.io.IOException(secret) to "incomplete",
            java.security.GeneralSecurityException(secret) to "encryption",
            IllegalArgumentException(secret) to "provider",
            IllegalStateException(secret) to "try again",
        )
        for ((error, hint) in cases) {
            val message = settingsBackupExportError(error)
            assertTrue(message.contains(hint, ignoreCase = true))
            assertFalse(message.contains(secret))
        }
    }
}
