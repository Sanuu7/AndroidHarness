package com.androidharness.app.tools

import com.androidharness.app.data.SearchDepth
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import java.io.File

class WebSearchApiTest {
    @Before fun clearSearchCache() { WebResultCache.clear() }
    private val context = ToolContext(FileFs(File("/tmp")))

    private fun client(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain -> handler(chain.request()) }.build()

    private fun reply(request: Request, body: String, code: Int = 200): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("stub")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun query(value: String) = buildJsonObject { put("query", value) }

    @Test fun offNeverContactsASearchBackendAndDoesNotBlockPageReads() = runBlocking {
        var requests = 0
        val tool = WebSearchTool(client { request ->
            requests++
            reply(request, "{}")
        }, { SearchApiConfig("brave", "key") }).forDepth(SearchDepth.OFF)
        tool.execute(query("a unique query while search is off"), context)
        assertEquals(0, requests)
        assertEquals(0, tool.budget()!!.remaining)
        assertTrue(tool.budget()!!.claimRead())
    }

    @Test fun braveKeyDrivesSearchAndIsNotEchoed() = runBlocking {
        var sentKey: String? = null
        val tool = WebSearchTool(client { request ->
            assertEquals("api.search.brave.com", request.url.host)
            sentKey = request.header("X-Subscription-Token")
            reply(request, """{"web":{"results":[{"title":"Brave result","url":"https://brave.test/a","description":"Snippet"}]}}""")
        }, { SearchApiConfig("brave", "brave-test-key") })
        val result = tool.execute(query("android"), context)
        assertEquals("brave-test-key", sentKey)
        assertTrue(result.output.contains("https://brave.test/a"))
        assertTrue(result.output.contains("[via Brave Search API]"))
        assertFalse(result.output.contains("brave-test-key"))
    }

    @Test fun tavilyKeyAuthenticatesTheSelectedApiWithoutEnteringThePayload() = runBlocking {
        var posted = ""
        val tool = WebSearchTool(client { request ->
            assertEquals("api.tavily.com", request.url.host)
            assertEquals("Bearer tvly-test-key", request.header("Authorization"))
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            posted = buffer.readUtf8()
            reply(request, """{"results":[{"title":"Tavily result","url":"https://tavily.test/a","content":"Snippet"}]}""")
        }, { SearchApiConfig("tavily", "tvly-test-key") })
        val result = tool.execute(query("android"), context)
        assertFalse(posted.contains("tvly-test-key"))
        assertTrue(result.output.contains("https://tavily.test/a"))
        assertTrue(result.output.contains("[via Tavily Search API]"))
        assertFalse(result.output.contains("tvly-test-key"))
    }

    @Test fun researchDepthCopyKeepsTheApiAndSpendsItsBudget() = runBlocking {
        val tool = WebSearchTool(client { request ->
            reply(request, """{"web":{"results":[
                {"title":"A","url":"https://a.test","description":""},
                {"title":"B","url":"https://b.test","description":""}]}}""")
        }, { SearchApiConfig("brave", "key") })
        assertSame(tool, tool.forDepth(SearchDepth.AUTO))
        val result = tool.forDepth(SearchDepth.DEEP_RESEARCH).execute(query("android"), context)
        assertTrue(result.output.contains("[via Brave Search API]"))
        assertTrue(result.output.contains("sources this turn: 2/50, 48 remaining"))
    }

    @Test fun rejectedSelectedApiIsReportedWithoutSilentlySwitchingToScraping() = runBlocking {
        var braveAttempted = false
        val tool = WebSearchTool(client { request ->
            if (request.url.host == "api.search.brave.com") {
                braveAttempted = true
                reply(request, """{"message":"bad key"}""", code = 401)
            } else {
                reply(request, """<a class="result__a" href="https://fallback.test">Fallback</a><div class="result__snippet">Snippet</div>""")
            }
        }, { SearchApiConfig("brave", "bad-key") })
        val result = tool.execute(buildJsonObject {
            put("query", "android")
            put("engine", "duckduckgo")
        }, context)
        assertTrue(braveAttempted)
        assertFalse(result.ok)
        assertTrue(result.output.contains("Brave Search API failed: HTTP 401"))
        assertFalse(result.output.contains("https://fallback.test"))
        assertTrue(result.output.contains("not replaced"))
    }

    @Test fun tavilyMovesToTheNextSavedKeyWhenOneIsRejectedOrOutOfCredits() = runBlocking {
        val id = java.util.UUID.randomUUID()
        val revoked = "tvly-revoked-$id"
        val spent = "tvly-spent-$id"
        val good = "tvly-good-$id"
        val sent = mutableListOf<String?>()
        val tool = WebSearchTool(client { request ->
            val auth = request.header("Authorization")
            sent += auth
            when (auth) {
                "Bearer $revoked" -> reply(request, """{"detail":"invalid key"}""", code = 401)
                "Bearer $spent" -> reply(request, """{"detail":"usage limit"}""", code = 432)
                else -> reply(request, """{"results":[{"title":"Tavily result","url":"https://tavily.test/rotated","content":"Snippet"}]}""")
            }
        }, { SearchApiConfig("tavily", revoked, listOf(spent, good)) })
        val result = tool.execute(query("rotation $id"), context)
        assertTrue(result.ok)
        assertEquals(listOf("Bearer $revoked", "Bearer $spent", "Bearer $good"), sent)
        assertTrue(result.output.contains("https://tavily.test/rotated"))
        assertFalse(result.output.contains(good))
    }

    @Test fun everySavedKeyRefusedIsReportedAsSuch() = runBlocking {
        val id = java.util.UUID.randomUUID()
        val tool = WebSearchTool(client { request -> reply(request, """{"detail":"rate limited"}""", code = 429) },
            { SearchApiConfig("tavily", "tvly-a-$id", listOf("tvly-b-$id")) })
        val result = tool.execute(query("all refused $id"), context)
        assertFalse(result.ok)
        assertTrue(result.output.contains("All 2 saved API keys were refused"))
        assertFalse(result.output.contains("tvly-a-$id"))
    }

    @Test fun serverErrorsDoNotBurnTheOtherKeys() = runBlocking {
        val id = java.util.UUID.randomUUID()
        var requests = 0
        val tool = WebSearchTool(client { request -> requests++; reply(request, "{}", code = 500) },
            { SearchApiConfig("tavily", "tvly-a-$id", listOf("tvly-b-$id")) })
        val result = tool.execute(query("server error $id"), context)
        assertFalse(result.ok)
        assertEquals(1, requests)
    }

    @Test fun searchCacheIsSharedAcrossRotationOrder() {
        assertEquals(SearchApiConfig("tavily", "a", listOf("b")).credentialIdentity(),
            SearchApiConfig("tavily", "b", listOf("a")).credentialIdentity())
        assertFalse(SearchApiConfig("tavily", "a", listOf("b")).toString().contains("b"))
    }

    @Test fun backendSelectionRequiresAProviderAndAKey() {
        assertEquals("Brave Search API", searchBackendFor(SearchApiConfig("brave", "key"))?.label)
        assertEquals("Tavily Search API", searchBackendFor(SearchApiConfig("tavily", "key"))?.label)
        assertNull(searchBackendFor(SearchApiConfig("brave", "")))
        assertNull(searchBackendFor(SearchApiConfig("keyless", "key")))
        assertNull(searchBackendFor(null))
    }
}
