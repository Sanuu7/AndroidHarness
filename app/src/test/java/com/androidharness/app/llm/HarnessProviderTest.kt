package com.androidharness.app.llm

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class HarnessProviderTest {
    @Test fun retiredSlotsResetToDefault() {
        assertEquals(HarnessProvider.DEFAULT_MODEL, HarnessProvider.sanitize("laguna-s-2.1-free"))
        assertEquals(HarnessProvider.DEFAULT_MODEL, HarnessProvider.sanitize("hy3-free"))
        assertEquals(HarnessProvider.DEFAULT_MODEL, HarnessProvider.sanitize("made-up-model"))
        assertEquals(HarnessProvider.DEFAULT_MODEL, HarnessProvider.sanitize(null))
    }

    @Test fun validFreePickSurvives() {
        assertEquals("big-pickle", HarnessProvider.sanitize("big-pickle"))
        assertEquals("muse-spark-1.3-contributor-free", HarnessProvider.sanitize("muse-spark-1.3-contributor-free"))
    }

    @Test fun wirePinMapping() {
        HarnessProvider.pins = mapOf(
            "a" to ProviderType.ANTHROPIC.name,
            "b" to ProviderType.OPENAI_RESPONSES.name,
        )
        assertEquals(ProviderType.ANTHROPIC, HarnessProvider.wire("a"))
        assertEquals(ProviderType.OPENAI_RESPONSES, HarnessProvider.wire("b"))
        assertEquals(ProviderType.OPENAI_COMPAT, HarnessProvider.wire("c"))
        HarnessProvider.pins = emptyMap()
    }

    @Test fun filtersPaidModelsButRetainsBigPickle() {
        val models = HarnessProvider.models(listOf(ModelEntry("new-free"), ModelEntry("paid"), ModelEntry("ox-alpha-free")))
        assertEquals(setOf("new-free", "big-pickle"), models.map { it.id }.toSet())
    }

    @Test fun emptyCatalogKeepsFallback() {
        assertEquals(HarnessProvider.fallbackModels, HarnessProvider.models(emptyList()))
    }

    @Test fun removesCredentialsWithoutImpersonatingAnotherClient() {
        val request = HarnessProvider.anonymous(Request.Builder().url(HarnessProvider.BASE_URL)
            .header("Authorization", "Bearer secret").header("x-api-key", "secret").build())
        assertNull(request.header("Authorization"))
        assertNull(request.header("x-api-key"))
        assertEquals("AndroidHarness", request.header("User-Agent"))
    }
}
