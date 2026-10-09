package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class HarnessCatalogTest {
    private val catalog = """{"data":[
        {"id":"kilo-auto/free","isFree":true,"context_length":256000,"supported_parameters":["tools","reasoning"]},
        {"id":"inclusionai/ling-3.1-flash","isFree":true,"context_length":128000,"supported_parameters":["tools"]},
        {"id":"z-ai/glm-5.2","isFree":false,"supported_parameters":["tools"]},
        {"id":"fake:free","isFree":false,"supported_parameters":["tools"]},
        {"id":"unknown:free","supported_parameters":["tools"]},
        {"id":"safety:free","isFree":true,"supported_parameters":[]}
    ]}"""

    @After fun resetCatalog() {
        HarnessProvider.restoreModels(HarnessProvider.fallbackModels)
        HarnessProvider.customModelIds = emptySet()
    }

    private fun client(body: String, code: Int = 200, inspect: (Request) -> Unit = {}) =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            inspect(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message(if (code == 200) "OK" else "Unavailable")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

    @Test fun freeCatalogRequiresExplicitFreeAccessAndTools() {
        val models = HarnessProvider.parseKiloCatalog(catalog)
        assertEquals(setOf("kilo-auto/free", "inclusionai/ling-3.1-flash", "openai-fast"), models.map { it.id }.toSet())
        val auto = models.first { it.id == "kilo-auto/free" }
        assertEquals(256000L, auto.contextTokens)
        assertEquals(true, auto.reasoning)
        assertEquals(HarnessProvider.KILO_NOTE, auto.note)
    }

    @Test fun refreshUsesKiloWithoutCredentialsOrZenHeaders() = runBlocking {
        var requested = false
        val result = ModelCatalog.listModels(HarnessProvider.config, "borrowed-zen-secret", client(catalog) { request ->
            requested = true
            assertEquals("https://api.kilo.ai/api/gateway/models", request.url.toString())
            assertNull(request.header("Authorization"))
            assertNull(request.header("x-api-key"))
            assertNull(request.header(HarnessProvider.SESSION_HEADER))
        })
        assertTrue(requested)
        assertTrue(result is ModelCatalog.Result.Models)
        assertTrue((result as ModelCatalog.Result.Models).models.any { it.id == "inclusionai/ling-3.1-flash" })
        assertFalse(result.models.any { it.id == "z-ai/glm-5.2:free" })
    }

    @Test fun failedOrMalformedRefreshDoesNotEvictLastSuccessfulCatalog() = runBlocking {
        val saved = HarnessProvider.parseKiloCatalog(catalog)
        HarnessProvider.restoreModels(saved)
        for ((body, status) in listOf("offline" to 503, "not-json" to 200, "{}" to 200,
            """{"data":[]}""" to 200)) {
            assertTrue(ModelCatalog.listModels(HarnessProvider.config, "", client(body, status)) is ModelCatalog.Result.Failed)
            assertEquals(saved, HarnessProvider.pooledModels)
        }
    }

    @Test fun cacheRestoresLiveModelsButDiscardsLegacyAndCorruptSnapshots() {
        val saved = HarnessProvider.parseKiloCatalog(catalog)
        val encoded = Json.encodeToString(saved)
        assertEquals(saved, HarnessProvider.cachedModels(encoded, HarnessProvider.CATALOG_SOURCE))
        assertEquals(HarnessProvider.fallbackModels, HarnessProvider.cachedModels(encoded, null))
        assertEquals(HarnessProvider.fallbackModels, HarnessProvider.cachedModels("bad-json", HarnessProvider.CATALOG_SOURCE))
        assertEquals(HarnessProvider.fallbackModels, HarnessProvider.cachedModels("[]", HarnessProvider.CATALOG_SOURCE))
    }

    @Test fun newlyDiscoveredBareIdRoutesToKiloAnonymously() = runBlocking {
        HarnessProvider.restoreModels(HarnessProvider.parseKiloCatalog(catalog))
        var requested = false
        val http = client("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n") { request ->
            requested = true
            assertEquals("https://api.kilo.ai/api/gateway/chat/completions", request.url.toString())
            assertNull(request.header("Authorization"))
            assertNull(request.header(HarnessProvider.SESSION_HEADER))
            val buffer = okio.Buffer()
            request.body!!.writeTo(buffer)
            assertTrue(buffer.readUtf8().contains("inclusionai/ling-3.1-flash"))
        }
        val events = HarnessProvider.create(http).streamChat(
            HarnessProvider.config.copy(model = "inclusionai/ling-3.1-flash"), "borrowed-zen-secret", "",
            listOf(ChatMessage(role = Role.USER, text = "Hi")), emptyList(), RequestOptions(),
        ).toList()
        assertTrue(requested)
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "ok" })
        assertFalse(events.any { it is StreamEvent.Failure })
    }

    @Test fun retiredModelCannotFallThroughToZenDuringARun() = runBlocking {
        val events = HarnessProvider.create().streamChat(
            HarnessProvider.config.copy(model = "z-ai/glm-5.2:free"), "borrowed-zen-secret", "",
            listOf(ChatMessage(role = Role.USER, text = "Hi")), emptyList(), RequestOptions(),
        ).toList()
        assertTrue(events.single() is StreamEvent.Failure)
        assertTrue((events.single() as StreamEvent.Failure).message.contains("no longer available"))
    }
}
