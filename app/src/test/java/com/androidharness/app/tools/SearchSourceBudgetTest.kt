package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSourceBudgetTest {
    private fun hit(url: String) = WebSearchResult(title = url, url = url, snippet = "")

    @Test fun capsDistinctSourcesForTheTurn() {
        val budget = SearchSourceBudget(5)
        val first = budget.claim((1..4).map { hit("https://example.com/$it") })
        assertEquals(4, first.size)
        assertEquals(1, budget.remaining)

        val second = budget.claim((4..9).map { hit("https://example.com/$it") })
        assertEquals(1, second.size)
        assertEquals("https://example.com/5", second.single().url)
        assertEquals(0, budget.remaining)
        assertTrue(budget.claim(listOf(hit("https://example.com/10"))).isEmpty())
    }

    @Test fun repeatedSourcesDoNotSpendBudgetTwice() {
        val budget = SearchSourceBudget(3)
        assertEquals(2, budget.claim(listOf(hit("https://a.example"), hit("https://b.example"))).size)
        assertEquals(1, budget.remaining)
        assertEquals(0, budget.claim(listOf(hit("https://a.example/"), hit("https://b.example"))).size)
        assertEquals(1, budget.remaining)
        assertEquals(1, budget.claim(listOf(hit("https://c.example"))).size)
    }

    @Test fun emptyAndMalformedUrlsAreIgnored() {
        val budget = SearchSourceBudget(10)
        assertTrue(budget.claim(listOf(hit(""), hit("   "))).isEmpty())
        assertEquals(0, budget.used)
    }

    @Test fun researchLevelsPullFullBatchesRegardlessOfModelCount() {
        val max = com.androidharness.app.data.SearchDepth.MAX_RESEARCH
        assertEquals(20, searchBatchCount(max, null, 100))
        assertEquals(20, searchBatchCount(max, 5, 100))
        assertEquals(20, searchBatchCount(max, 50, 100))
        assertEquals(12, searchBatchCount(max, null, 12))

        val deep = com.androidharness.app.data.SearchDepth.DEEP
        assertEquals(20, searchBatchCount(deep, 8, 20))

        val instant = com.androidharness.app.data.SearchDepth.INSTANT
        assertEquals(5, searchBatchCount(instant, null, 5))
        assertEquals(5, searchBatchCount(instant, 15, 5))
        assertEquals(3, searchBatchCount(instant, null, 3))

        // Auto and Code keep honoring the model's smaller count.
        assertEquals(8, searchBatchCount(null, null, Int.MAX_VALUE))
        assertEquals(5, searchBatchCount(null, 5, Int.MAX_VALUE))
        assertEquals(15, searchBatchCount(null, 50, Int.MAX_VALUE))
    }

    @Test fun readBudgetCountsPagesAndStopsAtItsLimit() {
        val budget = SearchSourceBudget(5, maxReads = 2, minReads = 1)
        assertEquals(0, budget.pagesRead)
        assertTrue(budget.canRead())
        assertTrue(budget.claimRead())
        assertTrue(budget.claimRead())
        assertTrue(!budget.canRead())
        assertTrue(!budget.claimRead())
        assertEquals(2, budget.pagesRead)
        assertEquals(1, budget.minReads)
    }

    @Test fun researchLevelsCarryReadTargets() {
        assertEquals(0, researchReadMinimum(com.androidharness.app.data.SearchDepth.INSTANT))
        assertEquals(2, researchReadMinimum(com.androidharness.app.data.SearchDepth.DEEP))
        assertEquals(5, researchReadMinimum(com.androidharness.app.data.SearchDepth.DEEP_RESEARCH))
        assertEquals(8, researchReadMinimum(com.androidharness.app.data.SearchDepth.MAX_RESEARCH))
        assertEquals(10, researchReadLimit(com.androidharness.app.data.SearchDepth.DEEP))
        assertTrue(researchReadLimit(com.androidharness.app.data.SearchDepth.AUTO) == null)
    }

    @Test fun pageOffsetsTrackingAndRenderedFallbackDoNotConsumeDistinctReadsAgain() {
        val budget = SearchSourceBudget(100, 2, 8)
        assertTrue(budget.claimRead("https://a.test/page?utm_source=feed#top"))
        assertTrue(budget.claimRead("https://a.test/page#bottom"))
        assertEquals(1, budget.pagesRead)
        assertTrue(budget.claimRead("https://b.test/page"))
        assertTrue(budget.canRead("https://a.test/page"))
        assertTrue(budget.claimRead("https://a.test/page"))
        assertTrue(!budget.canRead("https://c.test/page"))
        assertEquals(2, budget.pagesRead)
    }

    @Test fun diversityCapsResultsPerHost() {
        val results = listOf(
            hit("https://a.test/1"), hit("https://a.test/2"), hit("https://a.test/3"),
            hit("https://a.test/4"), hit("https://b.test/1"),
        )
        assertEquals(
            listOf("https://a.test/1", "https://a.test/2", "https://a.test/3", "https://b.test/1"),
            diversifyResults(results).map { it.url },
        )
    }

    @Test fun trackingParametersAndFragmentsDoNotSpendTheBudgetTwice() {
        val budget = SearchSourceBudget(2)
        assertEquals(1, budget.claim(listOf(hit("https://a.test/x?utm_source=feed"))).size)
        assertTrue(budget.claim(listOf(hit("https://a.test/x?utm_medium=email"))).isEmpty())
        assertEquals(1, budget.claim(listOf(hit("https://a.test/y#top"))).size)
        assertTrue(budget.claim(listOf(hit("https://a.test/y#bottom"))).isEmpty())
    }

    @Test fun depthCopiesExposeTheirLimitAndAutoHasNone() {
        val client = okhttp3.OkHttpClient()
        val base = WebSearchTool(client)
        assertTrue(base.forDepth(com.androidharness.app.data.SearchDepth.AUTO).budget() == null)
        val instant = base.forDepth(com.androidharness.app.data.SearchDepth.INSTANT)
        assertTrue(instant.description.contains("at most 5 distinct sources"))
        val max = base.forDepth(com.androidharness.app.data.SearchDepth.MAX_RESEARCH)
        assertTrue(max.description.contains("at most 100 distinct sources"))
    }

    @Test fun autoReportsRepeatedResultsWithoutLimitingTheSearch() {
        val base = WebSearchTool(okhttp3.OkHttpClient())
        val auto = base.forDepth(com.androidharness.app.data.SearchDepth.AUTO)
        assertEquals("", auto.repeatNote((1..4).map { hit("https://example.com/$it") }))
        // One repeat out of four is normal overlap and stays quiet.
        assertEquals("", auto.repeatNote(listOf(hit("https://example.com/1/"), hit("https://a.test"), hit("https://b.test"), hit("https://c.test"))))
        assertTrue(auto.repeatNote(listOf(hit("https://example.com/2"), hit("https://a.test"), hit("https://new.test"), hit("https://x.test")))
            .contains("2 of 4 results were already returned"))
        assertTrue(auto.repeatNote(listOf(hit("https://example.com/3"), hit("https://b.test#top")))
            .contains("All 2 results were already returned"))

        // A new turn starts clean, and budgeted depths and Code report repeats their own way.
        assertEquals("", base.forDepth(com.androidharness.app.data.SearchDepth.AUTO).repeatNote(listOf(hit("https://example.com/1"))))
        val deep = base.forDepth(com.androidharness.app.data.SearchDepth.DEEP)
        deep.repeatNote(listOf(hit("https://example.com/1")))
        assertEquals("", deep.repeatNote(listOf(hit("https://example.com/1"))))
        base.repeatNote(listOf(hit("https://example.com/1")))
        assertEquals("", base.repeatNote(listOf(hit("https://example.com/1"))))
    }
}
