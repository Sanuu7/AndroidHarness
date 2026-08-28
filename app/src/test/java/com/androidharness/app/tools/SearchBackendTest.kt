package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser and backend-selection guarantees for the web_search API backends.
 * Network calls are not exercised; the JSON shapes mirror real responses.
 */
class SearchBackendTest {

    @Test
    fun `brave parser maps web results`() {
        val body = """
            {"web":{"results":[
                {"title":"Kotlin Lang","url":"https://kotlinlang.org","description":"A modern language"},
                {"title":"No url here","description":"dropped"},
                {"url":"https://no-title.example","description":"dropped too"}
            ]}}
        """.trimIndent()
        val results = BraveSearchParser.parse(body)
        assertEquals(1, results.size)
        assertEquals("Kotlin Lang", results[0].title)
        assertEquals("https://kotlinlang.org", results[0].url)
        assertEquals("A modern language", results[0].snippet)
    }

    @Test
    fun `brave parser survives garbage and misses`() {
        assertEquals(emptyList<WebSearchResult>(), BraveSearchParser.parse("not json"))
        assertEquals(emptyList<WebSearchResult>(), BraveSearchParser.parse("{}"))
        assertEquals(emptyList<WebSearchResult>(), BraveSearchParser.parse("{\"web\":{}}"))
    }

    @Test
    fun `tavily parser maps results with content snippets`() {
        val body = """
            {"results":[
                {"title":"Tavily","url":"https://tavily.com","content":"Search API for LLMs"},
                {"title":"Bad","url":"not-a-url","content":"filtered"}
            ]}
        """.trimIndent()
        val results = TavilySearchParser.parse(body)
        assertEquals(1, results.size)
        assertEquals("Tavily", results[0].title)
        assertEquals("https://tavily.com", results[0].url)
        assertEquals("Search API for LLMs", results[0].snippet)
        assertEquals(emptyList<WebSearchResult>(), TavilySearchParser.parse("garbage"))
    }

    @Test
    fun `backend selection follows provider and key`() {
        assertNull(searchBackendFor(null))
        assertNull(searchBackendFor(SearchApiConfig("keyless", "ignored")))
        assertNull(searchBackendFor(SearchApiConfig("brave", "   ")))
        assertNull(searchBackendFor(SearchApiConfig("unknown-api", "key")))
        assertTrue(searchBackendFor(SearchApiConfig("brave", "BSA123")) is BraveApiBackend)
        assertTrue(searchBackendFor(SearchApiConfig("tavily", "tvly-1")) is TavilyApiBackend)
        // Provider ids are matched case-insensitively and trimmed.
        assertTrue(searchBackendFor(SearchApiConfig(" BRAVE ", "BSA123")) is BraveApiBackend)
    }

    @Test
    fun `keyless parser routes by engine id`() {
        val ddg = KeylessSearchBackend().parse(
            "duckduckgo",
            "<a class=\"result__a\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa\">Example A</a>" +
                "<a class=\"result__snippet\" href=\"#\">Snippet text</a>",
        )
        assertEquals(1, ddg.size)
        assertEquals("https://example.com/a", ddg[0].url)
        assertEquals("Example A", ddg[0].title)
        assertEquals("Snippet text", ddg[0].snippet)
        // Unknown engines fall through to the google parser, which finds nothing here.
        assertEquals(emptyList<WebSearchResult>(), KeylessSearchBackend().parse("???", "<html></html>"))
    }
}
