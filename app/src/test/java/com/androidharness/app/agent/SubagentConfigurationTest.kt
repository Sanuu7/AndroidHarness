package com.androidharness.app.agent

import com.androidharness.app.data.AppSettings
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubagentConfigurationTest {
    private val main = ProviderConfig("main", "Main", ProviderType.OPENAI_COMPAT, "https://main.example/v1", "execution")
    private val other = ProviderConfig("other", "Other", ProviderType.ANTHROPIC, "https://other.example", "default")

    @Test fun selectedProviderUsesItsOwnCredentialsAndModel() = runBlocking {
        val settings = AppSettings(subagentProviderId = other.id, subagentModel = "research")
        val connection = SubagentConfiguration.forTask(main, "main-key", "execution", configured = {
            SubagentConfiguration.configured(settings, listOf(main, other)) { id ->
                assertEquals(other.id, id)
                "other-key"
            }
        }, resolver = { error("The user's selected model must bypass the main provider catalog") })
        assertEquals(other.copy(model = "research"), connection.config)
        assertEquals("other-key", connection.apiKey)
        assertEquals("execution", main.model)
    }

    @Test fun unsetSelectionInheritsTheCurrentExecutionModel() = runBlocking {
        val connection = SubagentConfiguration.forTask(main, "main-key", null, configured = {
            SubagentConfiguration.configured(AppSettings(), listOf(other)) { error("No key lookup needed") }
        }, resolver = null)
        assertEquals(main, connection.config)
        assertEquals("main-key", connection.apiKey)
    }

    @Test fun unsetSelectionKeepsPerTaskOverridesOnTheParentProvider() = runBlocking {
        val connection = SubagentConfiguration.forTask(main, "main-key", "cheaper", { null }) { request ->
            assertEquals("cheaper", request)
            SubagentModelResolution.Resolved("vendor/cheaper")
        }
        assertEquals(main.copy(model = "vendor/cheaper"), connection.config)
        assertEquals("main-key", connection.apiKey)
    }

    @Test fun providerDefaultAndKeylessCredentialsAreSupported() {
        val connection = SubagentConfiguration.configured(
            AppSettings(subagentProviderId = other.id), listOf(other),
        ) { "keyless" }!!
        assertEquals(other, connection.config)
        assertEquals("keyless", connection.apiKey)
    }

    @Test fun missingConfiguredProviderDoesNotUseTheParent() = runBlocking {
        try {
            SubagentConfiguration.forTask(main, "main-key", null, configured = {
                SubagentConfiguration.configured(AppSettings(subagentProviderId = "deleted"), listOf(main)) { "main-key" }
            }, resolver = null)
            fail("Expected the unavailable provider to be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("provider is unavailable"))
        }
    }

    @Test fun missingConfiguredCredentialsDoNotReuseTheParentKey() {
        assertThrows(IllegalStateException::class.java) {
            SubagentConfiguration.configured(AppSettings(subagentProviderId = other.id), listOf(main, other)) { null }
        }
    }

    @Test fun unknownTaskModelStillFailsClearly() = runBlocking {
        try {
            SubagentConfiguration.forTask(main, "main-key", "missing", { null }) {
                SubagentModelResolution.Unknown(listOf("known"))
            }
            fail("Expected the unknown model to be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Unknown task model 'missing': known"))
        }
    }
}
