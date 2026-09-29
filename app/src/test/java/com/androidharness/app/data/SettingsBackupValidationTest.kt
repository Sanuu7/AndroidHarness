package com.androidharness.app.data

import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.tools.mcp.McpServerConfig
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsBackupValidationTest {
    private fun provider(id: String = "test", url: String = "https://example.com/v1") =
        BackupProvider(ProviderConfig(id, "Test", ProviderType.OPENAI_COMPAT, url, "model"))

    @Test fun validBackup() {
        SettingsBackupValidation.validate(SettingsBackupFile(settings = AppSettings(activeProviderId = "test"), providers = listOf(provider())))
    }

    @Test fun localModelReferenceDoesNotNeedHttpOrAnApiKey() {
        val config = ProviderConfig("local-model:qwen-05b", "Local", ProviderType.OPENAI_COMPAT, "local://qwen-05b", "local-model:qwen-05b")
        SettingsBackupValidation.validate(SettingsBackupFile(settings = AppSettings(activeProviderId = config.id), providers = listOf(BackupProvider(config))))
        for (invalid in listOf(config.copy(baseUrl = "https://example.com"), config.copy(model = "other"), config.copy(id = "local-model:../escape"))) {
            assertThrows(IllegalArgumentException::class.java) {
                SettingsBackupValidation.validate(SettingsBackupFile(settings = AppSettings(), providers = listOf(BackupProvider(invalid))))
            }
        }
    }

    @Test fun unknownVersionRejected() {
        assertThrows(IllegalArgumentException::class.java) { SettingsBackupValidation.validate(SettingsBackupFile(version = 2, settings = AppSettings())) }
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
