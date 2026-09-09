package com.androidharness.app.llm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    @Test fun customModelsSurviveSanitize() {
        assertEquals("my-custom-model", HarnessProvider.sanitize("my-custom-model", setOf("my-custom-model")))
        assertEquals(HarnessProvider.DEFAULT_MODEL, HarnessProvider.sanitize("my-custom-model", setOf("other")))
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
        assertFalse(request.header(HarnessProvider.SESSION_HEADER).isNullOrBlank())
    }

    @Test fun preservesStableOpenCodeSession() {
        val request = HarnessProvider.anonymous(Request.Builder().url(HarnessProvider.BASE_URL)
            .header(HarnessProvider.SESSION_HEADER, "conversation-123").build())
        assertEquals("conversation-123", request.header(HarnessProvider.SESSION_HEADER))
    }

    @Test fun isOpenCodeMatchesEndpointsAndNames() {
        assertTrue(HarnessProvider.isOpenCode(ProviderConfig("custom-1", "Custom", ProviderType.OPENAI_COMPAT, "https://opencode.ai/zen/v1", "model")))
        assertTrue(HarnessProvider.isOpenCode(ProviderConfig("custom-2", "Custom", ProviderType.OPENAI_COMPAT, "https://opencode.ai/v1", "model")))
        assertTrue(HarnessProvider.isOpenCode(ProviderConfig("custom-3", "OpenCode Go", ProviderType.OPENAI_COMPAT, "https://proxy.example.com/v1", "model")))
        assertTrue(HarnessProvider.isOpenCode(HarnessProvider.config))

        assertFalse(HarnessProvider.isOpenCode(ProviderConfig("p1", "OpenAI", ProviderType.OPENAI_COMPAT, "https://api.openai.com/v1", "gpt-4o")))
        assertFalse(HarnessProvider.isOpenCode(ProviderConfig("p2", "Anthropic", ProviderType.ANTHROPIC, "https://api.anthropic.com", "claude-3-5-sonnet")))
        assertFalse(HarnessProvider.isOpenCode(ProviderConfig("p3", "OpenRouter", ProviderType.OPENAI_COMPAT, "https://openrouter.ai/api/v1", "auto")))
    }

    @Test fun withSessionInjectsHeaderAndUserAgent() {
        val builder = Request.Builder().url("https://opencode.ai/zen/v1/chat/completions")
        val req = HarnessProvider.withSession(builder, "sess-xyz").build()
        assertEquals("sess-xyz", req.header(HarnessProvider.SESSION_HEADER))
        assertEquals("AndroidHarness", req.header("User-Agent"))

        val fallbackBuilder = Request.Builder().url("https://opencode.ai/zen/v1/chat/completions")
        val fallbackReq = HarnessProvider.withSession(fallbackBuilder, null).build()
        assertFalse(fallbackReq.header(HarnessProvider.SESSION_HEADER).isNullOrBlank())
        assertEquals("AndroidHarness", fallbackReq.header("User-Agent"))
    }

    @Test fun customOpenCodeGoProviderInjectsHeadersWithoutStrippingAuth() {
        val customConfig = ProviderConfig("custom-uuid", "OpenCode Go", ProviderType.OPENAI_COMPAT, "https://opencode.ai/zen/v1", "model")
        val dummyBody = "{}".toRequestBody("application/json".toMediaType())
        val req = OpenAiCompatProvider(okhttp3.OkHttpClient(), ProviderFactory.json).buildRequest(
            customConfig,
            "secret-token",
            dummyBody,
            RequestOptions(cacheKey = "my-chat-session"),
        )
        assertEquals("Bearer secret-token", req.header("Authorization"))
        assertEquals("my-chat-session", req.header(HarnessProvider.SESSION_HEADER))
        assertEquals("AndroidHarness", req.header("User-Agent"))
    }

    @Test fun modelCatalogInjectsOpenCodeHeadersWhileKeepingAuth() {
        val customConfig = ProviderConfig("custom-uuid", "OpenCode Go", ProviderType.OPENAI_COMPAT, "https://opencode.ai/zen/v1", "model")
        val req = ModelCatalog.buildRequest(customConfig, "secret-token")
        assertEquals("Bearer secret-token", req.header("Authorization"))
        assertFalse(req.header(HarnessProvider.SESSION_HEADER).isNullOrBlank())
        assertEquals("AndroidHarness", req.header("User-Agent"))

        val harnessReq = ModelCatalog.buildRequest(HarnessProvider.config, "ignored")
        assertNull(harnessReq.header("Authorization"))
        assertFalse(harnessReq.header(HarnessProvider.SESSION_HEADER).isNullOrBlank())
        assertEquals("AndroidHarness", harnessReq.header("User-Agent"))
    }
}
