package com.androidharness.app.data

import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.tools.mcp.McpServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsBackupValidationTest {
    @Test fun providerControlsRoundTripAndInvalidControlsBlockRestore() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val pool = com.androidharness.app.llm.ProviderKeyPool(listOf(
            com.androidharness.app.llm.ProviderApiKey("a", "Primary", "key-one"),
            com.androidharness.app.llm.ProviderApiKey("b", "Spare", "key-two", false)), rotate = false)
        val proxy = com.androidharness.app.llm.ProviderProxy(com.androidharness.app.llm.ProviderProxyMode.CUSTOM,
            host = "proxy.example", port = 3128, username = "user", password = "proxy-secret")
        val entry = BackupProvider(provider().config, keyPool = pool, proxy = proxy)
        val original = SettingsBackupFile(settings = AppSettings(), providers = listOf(entry), providerProxy = proxy)
        val restored = json.decodeFromString(SettingsBackupFile.serializer(), json.encodeToString(SettingsBackupFile.serializer(), original))
        SettingsBackupValidation.validate(restored)
        assertEquals(pool, restored.providers.single().keyPool)
        assertEquals(proxy, restored.providers.single().proxy)
        assertEquals(proxy, restored.providerProxy)
        for (invalid in listOf(
            original.copy(providerProxy = proxy.copy(port = 0)),
            original.copy(providers = listOf(BackupProvider(entry.config, proxy = proxy.copy(host = "https://proxy.example")))),
            original.copy(providers = listOf(BackupProvider(entry.config, keyPool = pool.copy(keys = pool.keys + pool.keys.first())))))
        ) {
            val error = assertThrows(SettingsBackupValidationException::class.java) { SettingsBackupValidation.validate(invalid) }
            org.junit.Assert.assertFalse(settingsBackupExportError(error).contains("proxy-secret"))
        }
        val old = json.decodeFromString(SettingsBackupFile.serializer(), """{"version":3,"settings":{}}""")
        SettingsBackupValidation.validate(old)
        assertEquals(com.androidharness.app.llm.ProviderProxy(), old.providerProxy)
    }

    private fun provider(id: String = "test", url: String = "https://example.com/v1") =
        BackupProvider(ProviderConfig(id, "Test", ProviderType.OPENAI_COMPAT, url, "model"))

    @Test fun validBackup() {
        SettingsBackupValidation.validate(SettingsBackupFile(settings = AppSettings(activeProviderId = "test"), providers = listOf(provider())))
    }

    @Test fun localModelsCannotEnterNewSettingsBackups() {
        val config = ProviderConfig("local-model:qwen-05b", "Local", ProviderType.OPENAI_COMPAT, "local://qwen-05b", "local-model:qwen-05b")
        for (invalid in listOf(config, config.copy(baseUrl = "https://example.com"), config.copy(model = "other"), config.copy(id = "local-model:../escape"))) {
            assertThrows(IllegalArgumentException::class.java) {
                SettingsBackupValidation.validate(SettingsBackupFile(settings = AppSettings(), providers = listOf(BackupProvider(invalid))))
            }
        }
    }

    @Test fun unknownVersionRejected() {
        assertThrows(IllegalArgumentException::class.java) { SettingsBackupValidation.validate(SettingsBackupFile(version = SettingsBackupFile.VERSION + 1, settings = AppSettings())) }
    }

    @Test fun memoriesSurviveASettingsBackupAndOldVersionsStayReadable() {
        val file = SettingsBackupFile(
            version = 1,
            settings = AppSettings(),
            memories = listOf(
                BackupMemory(id = "m1", text = "Prefers concise answers", category = "PREFERENCE", createdAt = 1, updatedAt = 2),
                BackupMemory(id = "m2", text = "Ships on Fridays", scope = "PROJECT", projectId = "p1", createdAt = 1, updatedAt = 2),
            ),
        )
        SettingsBackupValidation.validate(file)
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val restored = json.decodeFromString(SettingsBackupFile.serializer(), json.encodeToString(SettingsBackupFile.serializer(), file))
        SettingsBackupValidation.validate(restored)
        assertEquals(file.memories, restored.memories)
        assertEquals(4, SettingsBackupFile.VERSION)
    }

    @Test fun invalidMemoriesRejected() {
        for (memory in listOf(
            BackupMemory(id = "", text = "Text", createdAt = 1),
            BackupMemory(id = "m1", text = "   ", createdAt = 1),
            BackupMemory(id = "m1", text = "Text", scope = "PROJECT", projectId = null, createdAt = 1),
            BackupMemory(id = "m1", text = "Text", category = "UNKNOWN", createdAt = 1),
            BackupMemory(id = "m1", text = "Text", scope = "UNKNOWN", createdAt = 1),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                SettingsBackupValidation.validate(SettingsBackupFile(settings = AppSettings(), memories = listOf(memory)))
            }
        }
    }

    @Test fun duplicateMemoryIdsRejected() {
        val memory = BackupMemory(id = "m1", text = "Text", createdAt = 1)
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupValidation.validate(SettingsBackupFile(settings = AppSettings(), memories = listOf(memory, memory)))
        }
    }

    @Test fun invalidLimitsRejected() {
        for (s in listOf(AppSettings(maxContextTokens = -1), AppSettings(maxOutputTokens = Int.MAX_VALUE), AppSettings(maxIterations = -1))) {
            assertThrows(IllegalArgumentException::class.java) { SettingsBackupValidation.validate(SettingsBackupFile(settings = s)) }
        }
    }

    @Test fun danglingModelProviderRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupValidation.validate(SettingsBackupFile(settings = AppSettings(planningProviderId = "missing")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupValidation.validate(SettingsBackupFile(settings = AppSettings(subagentProviderId = "missing")))
        }
    }

    @Test fun separateSubagentModelSurvivesSettingsSerialization() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val settings = AppSettings(activeProviderId = "test", subagentProviderId = "research", subagentModel = "small")
        val original = SettingsBackupFile(settings = settings, providers = listOf(provider(), provider("research")))
        val restored = json.decodeFromString(SettingsBackupFile.serializer(), json.encodeToString(SettingsBackupFile.serializer(), original))
        SettingsBackupValidation.validate(restored)
        org.junit.Assert.assertEquals(settings, restored.settings)
        org.junit.Assert.assertNull(json.decodeFromString(AppSettings.serializer(), "{}").subagentProviderId)
    }

    @Test fun duplicateProvidersRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupValidation.validate(SettingsBackupFile(settings = AppSettings(), providers = listOf(provider(), provider())))
        }
    }

    @Test fun unsafeUrlsRejected() {
        for (url in listOf("file:///private/key", "javascript:alert(1)", "https://user:password@example.com", "https:///missing-host")) {
            assertThrows(IllegalArgumentException::class.java) {
                SettingsBackupValidation.validate(SettingsBackupFile(settings = AppSettings(), providers = listOf(provider(url = url))))
            }
        }
    }

    @Test fun workingCustomHttpEndpointsSurviveEncryptedBackup() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        for (url in listOf("http://my_server:11434/v1", "https://例え.テスト/v1", "HTTPS://EXAMPLE.COM/v1", "http://[::1]:11434/v1")) {
            val original = SettingsBackupFile(settings = AppSettings(activeProviderId = "test"), providers = listOf(provider(url = url)))
            SettingsBackupValidation.validate(original)
            val encrypted = BackupEncryption.encrypt(json.encodeToString(SettingsBackupFile.serializer(), original).toByteArray(), "backup test password".toCharArray())
            val restored = json.decodeFromString(SettingsBackupFile.serializer(), BackupEncryption.decrypt(encrypted, "backup test password".toCharArray()).toString(Charsets.UTF_8))
            SettingsBackupValidation.validate(restored)
            org.junit.Assert.assertEquals(url, restored.providers.single().config.baseUrl)
        }
    }

    @Test fun invalidSettingsExplainTheBlockerWithoutExposingConnectionSecrets() {
        val cases = listOf(
            SettingsBackupFile(settings = AppSettings(maxContextTokens = -1)) to "Run limits",
            SettingsBackupFile(settings = AppSettings(voiceEngine = "x".repeat(201))) to "Voice",
            SettingsBackupFile(settings = AppSettings(), providers = listOf(provider(url = "https://user:secret@example.com"))) to "provider URL",
            SettingsBackupFile(settings = AppSettings(), providers = listOf(provider(url = "https://example.com/a path?token=secret"))) to "provider URL",
            SettingsBackupFile(settings = AppSettings(), servers = listOf(McpServerConfig("secret", type = "unknown"))) to "MCP",
        )
        for ((file, hint) in cases) {
            val error = assertThrows(SettingsBackupValidationException::class.java) { SettingsBackupValidation.validate(file) }
            val displayed = settingsBackupExportError(error)
            org.junit.Assert.assertTrue(displayed, displayed.contains(hint))
            org.junit.Assert.assertFalse(displayed, displayed.contains("secret"))
            org.junit.Assert.assertFalse(displayed, displayed.contains("example.com"))
        }
    }

    @Test fun reservedCredentialSlotsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupValidation.validate(SettingsBackupFile(settings = AppSettings(), searchKeys = mapOf("github_pat" to "secret")))
        }
    }

    @Test fun duplicateMcpNamesRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupValidation.validate(SettingsBackupFile(settings = AppSettings(), servers = listOf(McpServerConfig("x"), McpServerConfig("x"))))
        }
    }
}
