package com.androidharness.app.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Request shapes and response parsing for the keyed search APIs. JSON mirrors each provider's documented response. */
class SearchApiBackendsTest {

    private fun bodyOf(request: okhttp3.Request): kotlinx.serialization.json.JsonObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return Json.parseToJsonElement(buffer.readUtf8()).jsonObject
    }

    @Test fun `every api provider has a backend`() {
        SearchProviders.apiIds.forEach { id ->
            assertNotNull(id, searchBackendFor(SearchApiConfig(id, "key-123")))
        }
        assertNull(searchBackendFor(SearchApiConfig(SearchProviders.KEYLESS, "key")))
        assertTrue(searchBackendFor(SearchApiConfig("serper", "k")) is SerperApiBackend)
        assertTrue(searchBackendFor(SearchApiConfig(" SerpApi ", "k")) is SerpApiBackend)
        assertTrue(searchBackendFor(SearchApiConfig("linkup", "k")) is LinkupApiBackend)
        assertTrue(searchBackendFor(SearchApiConfig("langsearch", "k")) is LangSearchApiBackend)
        assertTrue(searchBackendFor(SearchApiConfig("jina", "k")) is JinaApiBackend)
        assertTrue(searchBackendFor(SearchApiConfig("firecrawl", "k")) is FirecrawlApiBackend)
    }

    @Test fun `providers that need a key get no backend without one, marginalia falls back to the shared key`() {
        assertNull(searchBackendFor(SearchApiConfig("serper", " ")))
        val marginalia = searchBackendFor(SearchApiConfig("marginalia", "")) as MarginaliaApiBackend
        assertEquals("public", marginalia.request("public", "q", 5, SearchOptions()).header("API-Key"))
    }

    @Test fun `marginalia without a saved key still searches with the shared key`() = kotlinx.coroutines.runBlocking {
        var sentKey: String? = null
        val client = fakeClient { request ->
            sentKey = request.header("API-Key")
            200 to """{"results":[{"url":"https://m.example","title":"M","description":"d"}]}"""
        }
        val tool = WebSearchTool(client, searchApi = { SearchApiConfig("marginalia", "") })
        val result = tool.execute(
            kotlinx.serialization.json.buildJsonObject { put("query", kotlinx.serialization.json.JsonPrimitive("small web ${System.nanoTime()}")) },
            ToolContext(com.androidharness.app.workspace.FileFs(java.io.File("/tmp"))),
        )
        assertTrue(result.output, result.ok)
        assertEquals("public", sentKey)
    }

    @Test fun `ids are unique and keyless is first`() {
        assertEquals(SearchProviders.ids.distinct(), SearchProviders.ids)
        assertEquals(SearchProviders.KEYLESS, SearchProviders.ids.first())
        assertEquals("Serper", SearchProviders.name("SERPER"))
    }

    @Test fun `serper request and parse`() {
        val request = SerperApiBackend(listOf("k1")).request("k1", "kotlin", 7, SearchOptions(freshness = "week", country = "US", language = "EN"))
        assertEquals("https://google.serper.dev/search", request.url.toString())
        assertEquals("k1", request.header("X-API-KEY"))
        val body = bodyOf(request)
        assertEquals("kotlin", body["q"]!!.jsonPrimitive.content)
        assertEquals("7", body["num"]!!.jsonPrimitive.content)
        assertEquals("qdr:w", body["tbs"]!!.jsonPrimitive.content)
        assertEquals("us", body["gl"]!!.jsonPrimitive.content)
        val results = SerperSearchParser.parse("""{"organic":[
            {"title":"Kotlin","link":"https://kotlinlang.org","snippet":"Modern language","date":"2026-11-01T00:00:00Z","position":1},
            {"title":"No link"}]}""")
        assertEquals(1, results.size)
        assertEquals("https://kotlinlang.org", results[0].url)
        assertEquals("2026-11-01", results[0].date)
    }

    @Test fun `a spent serper key is treated as out of credit so the next key runs`() {
        assertEquals(402, SerperApiBackend(listOf("k")).keyStatus(400))
        assertEquals(401, SerperApiBackend(listOf("k")).keyStatus(401))
        assertEquals(400, LinkupApiBackend(listOf("k")).keyStatus(400))
    }

    @Test fun `serpapi passes the key as a query parameter and parses organic results`() {
        val request = SerpApiBackend(listOf("secret")).request("secret", "a b", 5, SearchOptions(freshness = "day"))
        assertEquals("secret", request.url.queryParameter("api_key"))
        assertEquals("google", request.url.queryParameter("engine"))
        assertEquals("a b", request.url.queryParameter("q"))
        assertEquals("qdr:d", request.url.queryParameter("tbs"))
        val results = SerpApiSearchParser.parse("""{"organic_results":[{"position":1,"title":"T","link":"https://t.example","snippet":"S"}]}""")
        assertEquals(listOf(WebSearchResult("T", "https://t.example", "S")), results)
        assertEquals(emptyList<WebSearchResult>(), SerpApiSearchParser.parse("""{"error":"Invalid API key."}"""))
    }

    @Test fun `linkup asks for search results and skips images`() {
        val backend = LinkupApiBackend(listOf("k"), today = { LocalDate.of(2026, 11, 19) })
        val body = bodyOf(backend.request("k", "news", 3, SearchOptions(freshness = "month")))
        assertEquals("searchResults", body["outputType"]!!.jsonPrimitive.content)
        assertEquals("fast", body["depth"]!!.jsonPrimitive.content)
        assertEquals("2026-10-19", body["fromDate"]!!.jsonPrimitive.content)
        assertEquals("standard", bodyOf(backend.request("k", "news", 3, SearchOptions(deep = true)))["depth"]!!.jsonPrimitive.content)
        val long = "word ".repeat(300)
        val results = LinkupSearchParser.parse("""{"results":[
            {"type":"text","name":"Page","url":"https://p.example","content":"$long","publishedAt":"2026-01-02","favicon":""},
            {"type":"image","name":"Pic","url":"https://p.example/a.png"}]}""")
        assertEquals(1, results.size)
        assertEquals("2026-01-02", results[0].date)
        assertTrue("long extracts are shortened to a snippet", results[0].snippet.length < 430)
    }

    @Test fun `langsearch maps freshness, parses pages and reports an error code in the body`() {
        val body = bodyOf(LangSearchApiBackend(listOf("k")).request("k", "q", 80, SearchOptions(freshness = "year")))
        assertEquals("oneYear", body["freshness"]!!.jsonPrimitive.content)
        assertEquals("50", body["count"]!!.jsonPrimitive.content)
        val results = LangSearchParser.parse("""{"code":200,"data":{"webPages":{"value":[
            {"name":"N","url":"https://n.example","snippet":"S","datePublished":"2025-05-06T10:00:00Z"}]}}}""")
        assertEquals(WebSearchResult("N", "https://n.example", "S", "2025-05-06"), results.single())
        assertNull(LangSearchParser.status("""{"code":200,"data":{}}"""))
        assertEquals(401, LangSearchParser.status("""{"code":401,"msg":"Invalid API key"}"""))
    }

    @Test fun `jina skips page text unless pages will be read, and caches it when they will`() {
        val backend = JinaApiBackend(listOf("k"))
        assertEquals("no-content", backend.request("k", "q", 5, SearchOptions()).header("X-Respond-With"))
        assertNull(backend.request("k", "q", 5, SearchOptions(readPages = true)).header("X-Respond-With"))
        val page = "Paragraph of real article text. ".repeat(40)
        val body = """{"code":200,"status":20000,"data":[
            {"title":"J","url":"https://j.example","description":"D","content":"$page","date":"2026-03-04"},
            {"title":"Short","url":"https://s.example","description":"D2","content":"tiny"}]}"""
        assertEquals(listOf("https://j.example", "https://s.example"), JinaSearchParser.parse(body).map { it.url })
        assertEquals("D", JinaSearchParser.parse(body)[0].snippet)
        assertEquals(setOf("https://j.example"), JinaSearchParser.pages(body).keys)
        assertEquals(402, JinaSearchParser.status("""{"code":402,"name":"InsufficientBalanceError"}"""))
    }

    @Test fun `firecrawl reads v2 and v1 shapes`() {
        val body = bodyOf(FirecrawlApiBackend(listOf("fc-1")).request("fc-1", "q", 4, SearchOptions(freshness = "day", country = "de")))
        assertEquals("qdr:d", body["tbs"]!!.jsonPrimitive.content)
        assertEquals("DE", body["country"]!!.jsonPrimitive.content)
        val v2 = FirecrawlSearchParser.parse("""{"success":true,"data":{"web":[{"title":"F","url":"https://f.example","description":"D"}]}}""")
        val v1 = FirecrawlSearchParser.parse("""{"success":true,"data":[{"title":"F","url":"https://f.example","description":"D"}]}""")
        assertEquals(v2, v1)
        assertEquals(1, v2.size)
    }

    @Test fun `marginalia request and parse`() {
        val request = MarginaliaApiBackend(listOf("mine")).request("mine", "small web", 9, SearchOptions())
        assertEquals("api2.marginalia-search.com", request.url.host)
        assertEquals("small web", request.url.queryParameter("query"))
        assertEquals("9", request.url.queryParameter("count"))
        assertEquals("mine", request.header("API-Key"))
        val results = MarginaliaSearchParser.parse("""{"query":"q","license":"CC-BY","results":[{"url":"https://m.example","title":"M","description":"Small site"}]}""")
        assertEquals(WebSearchResult("M", "https://m.example", "Small site"), results.single())
    }

    @Test fun `parsers return nothing for broken bodies`() {
        listOf<(String) -> List<WebSearchResult>>(
            SerperSearchParser::parse, SerpApiSearchParser::parse, LinkupSearchParser::parse, LangSearchParser::parse,
            JinaSearchParser::parse, FirecrawlSearchParser::parse, MarginaliaSearchParser::parse,
        ).forEach { parse ->
            assertEquals(emptyList<WebSearchResult>(), parse("not json"))
            assertEquals(emptyList<WebSearchResult>(), parse("[]"))
            assertEquals(emptyList<WebSearchResult>(), parse("{}"))
        }
    }

    @Test fun `key check builds a real request for every provider`() {
        SearchProviders.apiIds.forEach { id -> assertNotNull(id, SearchKeyCheck.request(id, "key-1")) }
        assertNull(SearchKeyCheck.request(SearchProviders.KEYLESS, "key-1"))
    }

    @Test fun `key check explains refusals, credits and rate limits`() {
        assertTrue(SearchKeyCheck.explain("Serper", 400, """{"message":"Not enough credits","statusCode":400}""", null).contains("no credits"))
        assertTrue(SearchKeyCheck.explain("Jina", 401, """{"message":"Invalid token"}""", null).contains("rejected this key (Invalid token)"))
        assertTrue(SearchKeyCheck.explain("Linkup", 429, "", "12").contains("Wait 12 seconds"))
        assertTrue(SearchKeyCheck.explain("Firecrawl", 402, "", null).contains("no credits"))
        assertTrue(SearchKeyCheck.explain("X", 500, "", null).contains("HTTP 500"))
    }

    /** Answers every request locally: [respond] picks status and body from the request. */
    private fun fakeClient(respond: (okhttp3.Request) -> Pair<Int, String>) = okhttp3.OkHttpClient.Builder()
        .addInterceptor { chain ->
            val (code, body) = respond(chain.request())
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("x")
                .body(okhttp3.ResponseBody.Companion.run { body.toResponseBody("application/json".toMediaType()) }).build()
        }.build()

    private fun String.toMediaType() = okhttp3.MediaType.Companion.run { this@toMediaType.toMediaType() }

    @Test fun `refused, spent and in-body failures move on to the next saved key`() = kotlinx.coroutines.runBlocking {
        val tag = System.nanoTime().toString()
        val serperSeen = ArrayList<String>()
        val serper = SerperApiBackend(listOf("refused-$tag", "spent-$tag", "good-$tag")).fetch(fakeClient { request ->
            val key = request.header("X-API-KEY")!!
            serperSeen += key.substringBefore('-')
            when {
                key.startsWith("refused") -> 403 to """{"message":"Unauthorized."}"""
                key.startsWith("spent") -> 400 to """{"message":"Not enough credits"}"""
                else -> 200 to """{"organic":[{"title":"Ok","link":"https://ok.example","snippet":"s"}]}"""
            }
        }, "q", 5)
        assertEquals(listOf("refused", "spent", "good"), serperSeen)
        assertEquals("https://ok.example", serper.results.single().url)
        assertEquals("Serper", serper.via)

        val lang = LangSearchApiBackend(listOf("a-$tag", "b-$tag")).fetch(fakeClient { request ->
            if (request.header("Authorization")!!.contains("a-")) 200 to """{"code":"401","message":"Invalid API KEY"}"""
            else 200 to """{"code":200,"data":{"webPages":{"value":[{"name":"L","url":"https://l.example","snippet":"s"}]}}}"""
        }, "q", 5)
        assertEquals("https://l.example", lang.results.single().url)
    }

    @Test fun `a non key error is not retried with other keys`() = kotlinx.coroutines.runBlocking {
        val tag = System.nanoTime().toString()
        var calls = 0
        val failure = runCatching {
            FirecrawlApiBackend(listOf("x-$tag", "y-$tag")).fetch(fakeClient { calls++; 500 to """{"success":false}""" }, "q", 5)
        }.exceptionOrNull()
        assertTrue(failure is ResearchHttpFailure)
        assertEquals("one key tried, plus the transport's own single retry at most", true, calls <= 2)
    }

    @Test fun `freshness start dates`() {
        val today = LocalDate.of(2026, 3, 31)
        assertEquals(LocalDate.of(2026, 3, 30), sinceDate("day", today))
        assertEquals(LocalDate.of(2026, 2, 28), sinceDate("month", today))
        assertNull(sinceDate(null, today))
    }
}
