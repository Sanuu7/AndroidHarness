package com.androidharness.app.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPassagesTest {
    private fun hit(url: String, title: String = url) = WebSearchResult(title, url, "")

    @Test fun rankingKeepsMatchingPassagesFromEverySite() {
        val filler = "Unrelated text about gardening and weather patterns in spring. ".repeat(3)
        val pages = listOf(
            hit("https://a.test/1") to "$filler\n\nThe Pixel 9 battery lasts about 24 hours in mixed use tests.\n\n$filler",
            hit("https://b.test/2") to "$filler\n\nIn our review the Pixel 9 battery reached 22 hours of screen-on mixed use.",
            hit("https://c.test/3") to filler,
        )
        val ranked = SearchPassages.rank("Pixel 9 battery life hours", pages)
        assertEquals(listOf("https://a.test/1", "https://b.test/2"), ranked.map { it.result.url })
        assertTrue(ranked.all { page -> page.passages.all { "battery" in it } })
    }

    @Test fun renderedPassagesAreReadSourcesButNotSearchResults() {
        val text = "1. Result\n   https://a.test/1\n   snippet\n\n" + SearchPassages.render(
            listOf(PagePassages(hit("https://a.test/1", "Result"), listOf("A passage."))),
        )
        assertEquals(listOf("https://a.test/1"), SearchPassages.readSources(text).map { it.url })
        assertEquals(1, WebSearchText.parse(text).size)
    }

    @Test fun slowPagesAreDroppedAndReadsAreCounted() = runBlocking {
        val reader = SearchPageReader({ url ->
            if ("slow" in url) { delay(5_000); "never" } else "Kotlin coroutines run concurrently on dispatchers in this guide."
        }, deadlineMs = 300)
        val budget = SearchSourceBudget(10, maxReads = 10)
        val outcome = reader.read("kotlin coroutines",
            listOf(hit("https://slow.test/a"), hit("https://fast.test/b"), hit("https://quick.test/c")), 2, budget)
        assertEquals(2, outcome.loaded)
        assertEquals(2, budget.pagesRead)
        assertFalse(outcome.pages.any { "slow" in it.result.url })
        assertEquals(listOf("https://slow.test/a"), outcome.unread)
    }
}
