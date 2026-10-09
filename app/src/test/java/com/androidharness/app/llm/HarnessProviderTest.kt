package com.androidharness.app.llm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

class HarnessProviderTest {
    @After fun resetCatalog() { HarnessProvider.restoreModels(HarnessProvider.fallbackModels) }
    @Test fun stalePicksResetToDefault() {
        assertEquals(HarnessProvider.DEFAULT_MODEL, HarnessProvider.sanitize("ling-3.0-flash-fin-free"))
        assertEquals(HarnessProvider.DEFAULT_MODEL, HarnessProvider.sanitize("big-pickle"))
        assertEquals(HarnessProvider.DEFAULT_MODEL, HarnessProvider.sanitize("made-up-model"))
        assertEquals(HarnessProvider.DEFAULT_MODEL, HarnessProvider.sanitize(null))
    }

    @Test fun pooledPickSurvives() {
        assertEquals("kilo-auto/free", HarnessProvider.sanitize("kilo-auto/free"))
        assertEquals("openai-fast", HarnessProvider.sanitize("openai-fast"))
    }

    @Test fun customModelsSurviveSanitize() {
        assertEquals("my-custom-model", HarnessProvider.sanitize("my-custom-model", setOf("my-custom-model")))
        assertEquals(HarnessProvider.DEFAULT_MODEL, HarnessProvider.sanitize("my-custom-model", setOf("other")))
    }

    @Test fun pooledModelsAreAcceptedAndLabeled() {
        HarnessProvider.restoreModels(HarnessProvider.models(listOf(ModelEntry("inclusionai/ling-3.1-flash"))))
        assertTrue(HarnessProvider.isPooled("inclusionai/ling-3.1-flash"))
        assertFalse(HarnessProvider.isPooled("z-ai/glm-5.2:free"))
        assertFalse(HarnessProvider.isPooled("deepseek/deepseek-v4-flash-0731:free"))
        assertTrue(HarnessProvider.isPooled("openai-fast"))
        assertFalse(HarnessProvider.isPooled("ling-3.0-flash-fin-free"))
        assertFalse(HarnessProvider.isPooled("Qwen3.5-397B-A17B"))

        assertEquals("inclusionai/ling-3.1-flash", HarnessProvider.sanitize("inclusionai/ling-3.1-flash"))

        val models = HarnessProvider.models(emptyList())
        val openaiFast = models.first { it.id == "openai-fast" }
        assertTrue(openaiFast.note.orEmpty().contains("Pollinations"))
        assertTrue(models.none { it.id == "nvidia/nemotron-3.5-content-safety:free" })
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

    @Test fun refreshReplacesRetiredModelsAndKeepsMetadata() {
        HarnessProvider.restoreModels(HarnessProvider.models(listOf(ModelEntry("retired:free"))))
        val fresh = ModelEntry("new-free", reasoning = true, contextTokens = 128000)
        HarnessProvider.restoreModels(HarnessProvider.models(listOf(fresh)))
        assertFalse(HarnessProvider.isPooled("retired:free"))
        assertEquals("new-free", HarnessProvider.sanitize("retired:free"))
        assertEquals(fresh.copy(note = HarnessProvider.KILO_NOTE), HarnessProvider.pooledModels.first())
        assertEquals(HarnessProvider.KILO_BASE_URL, HarnessProvider.pool["new-free"]?.baseUrl)
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

    @Test fun withSessionSendsFullIdentityHeaderSet() {
        val req = HarnessProvider.withSession(
            Request.Builder().url("https://opencode.ai/zen/v1/chat/completions"), "sess-1",
        ).build()
        assertFalse(req.header("x-opencode-request").isNullOrBlank())
        assertEquals("harness", req.header("x-opencode-client"))
        assertFalse(req.header("x-opencode-project").isNullOrBlank())
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
        assertEquals(HarnessProvider.KILO_BASE_URL + "/models", harnessReq.url.toString())
        assertNull(harnessReq.header(HarnessProvider.SESSION_HEADER))
        assertEquals("AndroidHarness", harnessReq.header("User-Agent"))
    }
}
