package com.androidharness.app.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** web_search with several providers on: auto fall-through, a named provider, and providers that are off. */
class WebSearchProvidersTest {

    private val ctx = ToolContext(com.androidharness.app.workspace.FileFs(java.io.File("/tmp")))
    private val tag = System.nanoTime().toString()

    private fun client(respond: (okhttp3.Request) -> Pair<Int, String>) = okhttp3.OkHttpClient.Builder()
        .addInterceptor { chain ->
            val (code, body) = respond(chain.request())
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject {
        put("query", JsonPrimitive("providers $tag ${System.nanoTime()}"))
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    private val serperOk = """{"organic":[{"title":"From Serper","link":"https://serper.example","snippet":"s"}]}"""
    private val linkupOk = """{"results":[{"type":"text","name":"From Linkup","url":"https://linkup.example","content":"c"}]}"""

    private fun tool(http: okhttp3.OkHttpClient, setup: SearchSetup) =
        WebSearchTool(http, setup = { setup }, refreshSetup = { setup })

    private val both = SearchSetup(listOf(SearchApiConfig("serper", "s-$tag"), SearchApiConfig("linkup", "l-$tag")), keyless = false)

    @Test fun `auto moves on to the next provider when one fails`() = runBlocking {
        val hosts = ArrayList<String>()
        val result = tool(client { request ->
            hosts += request.url.host
            if (request.url.host == "google.serper.dev") 401 to """{"message":"Unauthorized"}""" else 200 to linkupOk
        }, both).execute(args(), ctx)
        assertTrue(result.output, result.ok)
        assertEquals("google.serper.dev", hosts.first())
        assertTrue(result.output, result.output.contains("Serper failed"))
        assertTrue(result.output, result.output.contains("used Linkup instead"))
        assertTrue(result.output, result.output.contains("https://linkup.example"))
    }

    @Test fun `auto moves on when a provider finds nothing`() = runBlocking {
        val result = tool(client { request ->
            if (request.url.host == "google.serper.dev") 200 to """{"organic":[]}""" else 200 to linkupOk
        }, both).execute(args(), ctx)
        assertTrue(result.output, result.ok)
        assertTrue(result.output, result.output.contains("Serper returned no results"))
        assertTrue(result.output, result.output.contains("https://linkup.example"))
    }

    @Test fun `a named provider is tried first`() = runBlocking {
        val hosts = ArrayList<String>()
        val result = tool(client { request ->
            hosts += request.url.host
            200 to if (request.url.host == "google.serper.dev") serperOk else linkupOk
        }, both).execute(args("provider" to "linkup"), ctx)
        assertTrue(result.output, result.ok)
        assertEquals(listOf("api.linkup.so"), hosts)
        assertTrue(result.output, result.output.contains("[via Linkup]"))
    }

    @Test fun `a provider that is off or unknown is refused with the available list`() = runBlocking {
        val search = tool(client { 200 to serperOk }, both)
        val off = runCatching { search.execute(args("provider" to "brave"), ctx) }.exceptionOrNull()
        assertTrue(off is ToolFailure)
        assertTrue(off!!.message!!, off.message!!.contains("turned off or has no saved key"))
        assertTrue(off.message!!, off.message!!.contains("serper, linkup"))
        val unknown = runCatching { search.execute(args("provider" to "altavista"), ctx) }.exceptionOrNull()
        assertTrue(unknown!!.message!!, unknown.message!!.contains("Unknown search provider"))
    }

    @Test fun `when every provider fails and keyless is off each failure is reported`() = runBlocking {
        val result = tool(client { 401 to """{"message":"Unauthorized"}""" }, both).execute(args(), ctx)
        assertFalse(result.ok)
        assertTrue(result.output, result.output.contains("Serper failed"))
        assertTrue(result.output, result.output.contains("Linkup failed"))
        assertTrue(result.output, result.output.contains("Keyless public engines are turned off"))
    }

    @Test fun `with everything off the tool says so`() = runBlocking {
        val result = tool(client { 200 to serperOk }, SearchSetup(emptyList(), keyless = false)).execute(args(), ctx)
        assertFalse(result.ok)
        assertTrue(result.output, result.output.contains("Every web search provider is turned off"))
    }

    @Test fun `the description lists what each enabled provider is good for`() {
        val description = tool(client { 200 to serperOk }, SearchSetup(listOf(SearchApiConfig("serper", "k")), keyless = true)).description
        assertTrue(description, description.contains("serper: ${SearchProviders.info("serper")!!.strength}"))
        assertTrue(description, description.contains("keyless: "))
        assertFalse(description, description.contains("linkup:"))
    }

    @Test fun `the configuration note names every ready provider`() = runBlocking {
        val note = tool(client { 200 to serperOk }, both).configurationNote()
        assertTrue(note, note.contains("Serper, Linkup"))
        assertFalse(note, note.contains("s-$tag"))
    }
}
