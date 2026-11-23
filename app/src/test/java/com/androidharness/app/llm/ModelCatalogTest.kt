package com.androidharness.app.llm

import com.androidharness.app.agent.CustomReasoning
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.agent.ThinkingSpecs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun providerReportedEffortsReachNativeRequestsAndCustomDefaults() = runBlocking {
        val config = ProviderConfig("detected", "DeepSeek", ProviderType.OPENAI_COMPAT, "https://api.deepseek.com/v1", "new-model")
        val response = """{"data":[{"id":"new-model","effort":{"supported_levels":["low","high","max"],"default_level":"high"}}]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val entry = (ModelCatalog.listModels(config, "fixture", client) as ModelCatalog.Result.Models).models.single()
        assertEquals(listOf("low", "high", "max"), entry.reasoningEfforts)
        assertEquals("high", entry.defaultReasoningEffort)
        assertEquals(true, entry.reasoning)
        val options = RequestOptions(thinking = ThinkingLevel.MAX,
            modelCatalogs = mapOf(config.id to listOf(entry)))
        val resolved = options.resolveThinking(config)
        assertEquals("Max", resolved.label)
        assertEquals("max", resolved.effort)
        assertEquals(listOf("Low", "High", "Max"), CustomReasoning.defaultLevels(resolved.spec))
        val body = OpenAiCompatProvider(client, ProviderFactory.json).buildRequestBody(config, "fixture", emptyList(), emptyList(), options)
        assertEquals("max", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val restored = ProviderFactory.json.decodeFromString(ModelEntry.serializer(), ProviderFactory.json.encodeToString(ModelEntry.serializer(), entry))
        assertEquals(entry, restored)
    }

    @Test fun openRouterReasoningObjectEffortsNormalizeAndEnableLocalLanProvider() {
        val body = """{"data":[{
            "id":"gemini-4-neon",
            "context_length":1048576,
            "supported_parameters":["tools","reasoning","reasoning_effort"],
            "architecture":{"input_modalities":["text","image"]},
            "reasoning":{"mandatory":true,"default_enabled":true,"default_effort":"high","supported_efforts":["xhigh","high","medium","low"]}
        }]}"""
        val entry = ModelCatalog.parseCatalog(ProviderType.OPENAI_COMPAT, body).single()
        assertEquals(listOf("low", "medium", "high", "xhigh"), entry.reasoningEfforts)
        assertEquals("high", entry.defaultReasoningEffort)
        assertEquals(true, entry.reasoning)
        assertEquals(true, entry.reasoningReported)
        assertEquals(true, entry.tools)
        assertEquals(true, entry.imageInput)
        assertEquals(1048576L, entry.contextTokens)
        val lanConfig = ProviderConfig("lan", "Local Gateway", ProviderType.OPENAI_COMPAT, "http://192.168.1.50:8080/v1", "gemini-4-neon")
        val spec = ThinkingSpecs.forProvider(lanConfig, entry)
        assertEquals(ThinkingSpecs.Style.EFFORT, spec.style)
        assertEquals(listOf(
            ThinkingLevel.LOW,
            ThinkingLevel.MEDIUM,
            ThinkingLevel.HIGH,
            ThinkingLevel.XHIGH,
        ), spec.levels)
        assertEquals(ThinkingLevel.HIGH, spec.defaultLevel)
        assertEquals(listOf("Low", "Medium", "High", "X-High"), spec.reportedLevels)
    }

    @Test fun knownEffortsAreOrderedLowToHighButCustomNamesKeepTheirOrder() {
        val models = ModelCatalog.parseCatalog(ProviderType.OPENAI_COMPAT, """{"data":[
            {"id":"jumbled","reasoning":{"supported_efforts":["high","none","max","low","medium"]}},
            {"id":"custom","reasoning":{"supported_efforts":["turbo","high","low"]}}
        ]}""").associateBy { it.id }
        assertEquals(listOf("none", "low", "medium", "high", "max"), models.getValue("jumbled").reasoningEfforts)
        assertEquals(listOf("turbo", "high", "low"), models.getValue("custom").reasoningEfforts)
    }

    @Test fun malformedEffortListsStayUnknownAndUnavailableDefaultsAreDiscarded() {
        val models = ModelCatalog.parseCatalog(ProviderType.OPENAI_COMPAT, """{"data":[
            {"id":"malformed","effort":{"supported_levels":["high",null]}},
            {"id":"empty","effort":{"supported_levels":[]}},
            {"id":"case","supported_reasoning_levels":[{"effort":"LOW"},"High","high"],"default_reasoning_effort":"max"}
        ]}""").associateBy { it.id }
        assertNull(models.getValue("malformed").reasoningEfforts)
        assertNull(models.getValue("malformed").reasoning)
        assertEquals(emptyList<String>(), models.getValue("empty").reasoningEfforts)
        assertEquals(listOf("low", "high"), models.getValue("case").reasoningEfforts)
        assertNull(models.getValue("case").defaultReasoningEffort)
    }
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
            assertEquals(listOf(ModelEntry("a", false, 64000, tools = true), ModelEntry("m", false, 32000),
                ModelEntry("z", true, 128000, tools = true)), ModelCatalog.parseCatalog(type, body))
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
        assertEquals(listOf(ModelEntry("gemini-2.5-flash")),
            ModelCatalog.parseCatalog(ProviderType.GEMINI, body))
    }

    @Test fun geminiUsesReportedThinkingAndKeepsMissingMetadataUnknown() {
        val body = """{"models":[{"name":"models/unknown"},
            {"name":"models/reasoner","thinking":true},
            {"name":"models/gemini-2.5-no-thinking","thinking":false}]}"""
        val entries = ModelCatalog.parseCatalog(ProviderType.GEMINI, body).associateBy { it.id }
        assertEquals(ModelReasoning.REPORTED, ModelReasoning.of(entries.getValue("reasoner"), ProviderType.GEMINI))
        assertEquals(ModelReasoning.UNSUPPORTED, ModelReasoning.of(entries.getValue("gemini-2.5-no-thinking"), ProviderType.GEMINI))
        assertNull(entries.getValue("unknown").reasoning)
    }
}
