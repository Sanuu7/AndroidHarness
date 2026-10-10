package com.androidharness.app.llm

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    // Relevant fields captured from llm7.io /v1/models for issue #19.
    private val llm7Catalog = """{"object":"list","data":[
        {"id":"DeepSeek-V4-Flash-0731","reasoning":true,
         "context_window":{"tokens":400000,"chars":null},"capabilities":{"reasoning":true}},
        {"id":"codestral-latest","reasoning":false,
         "context_window":{"tokens":32000,"chars":null},"capabilities":{"reasoning":false}},
        {"id":"anthropic/claude-opus-4-6","reasoning":true,
         "context_window":{"tokens":null,"chars":null}}
    ]}"""

    @Test fun llm7SetupLoadsModelsWithStructuredContextWindows() = runBlocking {
        val config = ProviderConfig("", "", ProviderType.OPENAI_COMPAT, "https://api.llm7.io/v1", "")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://api.llm7.io/v1/models", request.url.toString())
            assertEquals("Bearer test-key", request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(llm7Catalog.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = ModelCatalog.listModels(config, "test-key", client)
        assertTrue("Setup failed: $result", result is ModelCatalog.Result.Models)
        val models = (result as ModelCatalog.Result.Models).models.associateBy { it.id }
        assertEquals(3, models.size)
        assertEquals(400000L, models.getValue("DeepSeek-V4-Flash-0731").contextTokens)
        assertEquals(true, models.getValue("DeepSeek-V4-Flash-0731").reasoning)
        assertEquals(32000L, models.getValue("codestral-latest").contextTokens)
        assertEquals(false, models.getValue("codestral-latest").reasoning)
        assertNull(models.getValue("anthropic/claude-opus-4-6").contextTokens)
    }

    @Test fun scalarWindowsAndOpenRouterCapabilitiesStillParseAcrossProtocols() {
        val body = """{"data":[
            {"id":"z","context_length":128000,"supported_parameters":["tools","reasoning"]},
            {"id":"a","max_tokens":"64000","supported_parameters":["tools"]},
            {"id":"m","context_window":32000,"reasoning":false}
        ]}"""
        for (type in listOf(ProviderType.OPENAI_COMPAT, ProviderType.OPENAI_RESPONSES, ProviderType.ANTHROPIC)) {
            assertEquals(listOf(ModelEntry("a", false, 64000), ModelEntry("m", false, 32000),
                ModelEntry("z", true, 128000)), ModelCatalog.parseCatalog(type, body))
        }
    }

    @Test fun structuredOptionalMetadataCannotDiscardValidModels() {
        val body = """{"data":[null,[],{"id":{}},{"id":false},{"id":""},
            {"id":"unknown","context_window":{"tokens":null,"chars":100000},"reasoning":{}},
            {"id":"fallback","context_length":{},"max_tokens":null,"context_window":{"tokens":"32000"},
             "reasoning":{},"capabilities":{"reasoning":false}},
            {"id":"mixed","supported_parameters":[null,{},[],"reasoning"],"context_window":[]}
        ]}"""
        assertEquals(listOf(ModelEntry("fallback", false, 32000), ModelEntry("mixed", true),
            ModelEntry("unknown")), ModelCatalog.parseCatalog(ProviderType.OPENAI_COMPAT, body))
    }

    @Test fun topLevelReasoningTakesPrecedenceOverNestedCapabilities() {
        val body = """{"data":[
            {"id":"explicit","reasoning":false,"capabilities":{"reasoning":true}},
            {"id":"nested","capabilities":{"reasoning":true}}
        ]}"""
        assertEquals(listOf(ModelEntry("explicit", false), ModelEntry("nested", true)),
            ModelCatalog.parseCatalog(ProviderType.OPENAI_COMPAT, body))
    }

    @Test fun geminiSkipsMalformedNamesAndRetainsValidModels() {
        val body = """{"models":[null,{"name":{}},{"name":null},{"name":""},
            {"name":"models/gemini-2.5-flash"}]}"""
        assertEquals(listOf(ModelEntry("gemini-2.5-flash", true)),
            ModelCatalog.parseCatalog(ProviderType.GEMINI, body))
    }
}
