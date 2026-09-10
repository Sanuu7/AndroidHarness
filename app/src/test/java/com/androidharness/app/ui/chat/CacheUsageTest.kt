package com.androidharness.app.ui.chat

import com.androidharness.app.data.db.UsageEventEntity
import com.androidharness.app.llm.ModelsDev
import org.junit.Assert.*
import org.junit.Test

class CacheUsageTest {
    private fun row(input: Long, cached: Long, reported: Boolean = true, writes: Long = 0) =
        UsageEventEntity(sessionId = "s", providerName = "p", model = "m", inputTokens = input,
            outputTokens = 50, cachedTokens = cached, cacheWriteTokens = writes, createdAt = 1,
            turnId = "t", cacheReported = reported)

    @Test fun `invalid provider counts are unavailable instead of clamped into a fake full hit`() {
        val rows = listOf(row(100, 101), row(100, -1), row(100, 80, writes = 30))
        assertNull(cacheUsageSummary(rows).rate)
        assertEquals(3, cacheUsageSummary(rows).unknown)
        rows.forEach { assertNull(cacheReadCost(it, ModelsDev.CachePrices(2.0, 0.5, 2.5))) }
    }

    @Test fun `near full and tiny cache hits are never rounded to complete hit or miss`() {
        assertEquals("Cache hit · 99.94%", cacheUsageSummary(listOf(row(125260, 125184))).label)
        assertEquals("Cache hit · 99.99%", cacheUsageSummary(listOf(row(1000000, 999999))).label)
        assertEquals("Cache hit · <0.01%", cacheUsageSummary(listOf(row(1000000, 1))).label)
        assertEquals("Cache hit · 100%", cacheUsageSummary(listOf(row(100, 100))).label)
    }

    @Test fun `missing explicit cache rates are not guessed`() {
        val price = ModelsDev.CachePrices(10.0, null, null)
        assertNull(cacheReadCost(row(100, 80), price))
        assertNull(cacheMissCost(row(100, 0, writes = 100), price))
        assertEquals(0.001, cacheMissCost(row(100, 0), price)!!, 0.00001)
    }

    @Test fun `live indicator follows hit then miss while completion combines both`() {
        val hit = listOf(row(100, 80))
        assertEquals("Cache hit · 80%", cacheIndicatorSummary(hit, running = true).label)
        val mixed = hit + row(100, 0)
        assertEquals("Cache missed", cacheIndicatorSummary(mixed, running = true).label)
        assertEquals("Cache hit · 40%", cacheIndicatorSummary(mixed, running = false).label)
        assertEquals(80, cacheUsageSummary(mixed).cached.toInt())
    }

    @Test fun `live indicator follows miss then hit without averaging the latest hit`() {
        val mixed = listOf(row(900, 0), row(100, 80))
        assertEquals("Cache hit · 80%", cacheIndicatorSummary(mixed, running = true).label)
        assertEquals("Cache hit · 8%", cacheIndicatorSummary(mixed, running = false).label)
    }

    @Test fun `latest unreported usage cannot leave a stale hit or invent a miss`() {
        val rows = listOf(row(100, 80), row(100, 0, reported = false))
        assertEquals("Cache unavailable", cacheIndicatorSummary(rows, running = true).label)
        assertEquals("Cache hit · 80% · partial", cacheIndicatorSummary(rows, running = false).label)
        assertNull(cacheIndicatorSummary(emptyList(), running = true).rate)
    }

    @Test fun `rate weights tokens across requests and counts writes as misses`() {
        val result = cacheUsageSummary(listOf(row(100, 80), row(900, 0, writes = 900)))
        assertEquals(8.0, result.rate!!, 0.00001)
        assertEquals(1, result.hits)
        assertEquals(1, result.misses)
        assertEquals("Cache hit · 8%", result.label)
    }

    @Test fun `missing usage is not a miss or part of the denominator`() {
        val result = cacheUsageSummary(listOf(row(100, 80), row(900, 0, reported = false)))
        assertEquals(80.0, result.rate!!, 0.00001)
        assertEquals(0, result.misses)
        assertEquals(1, result.unknown)
        assertTrue(result.label.contains("partial"))
        assertEquals("Cache unavailable", cacheUsageSummary(listOf(row(100, 0, false))).label)
        assertEquals("Cache missed", cacheUsageSummary(listOf(row(100, 0))).label)
    }

    @Test fun `miss cost includes uncached input and cache writes without output`() {
        val price = ModelsDev.CachePrices(input = 10.0, read = 2.0, write = 12.5)
        assertEquals(11.25, cacheMissCost(row(1000000, 0, writes = 500000), price)!!, 0.00001)
        assertNull(cacheMissCost(row(100, 0, false), price))
        assertNull(cacheMissCost(row(100, 0), null))
    }

    @Test fun `cost includes only cached reads at that models read price`() {
        val price = ModelsDev.CachePrices(input = 10.0, read = 2.0, write = 12.5)
        assertEquals(0.4, cacheReadCost(row(1000000, 200000, writes = 500000), price)!!, 0.00001)
        assertNull(cacheReadCost(row(100, 80), null))
        assertNull(cacheReadCost(row(100, 0, false), price))
        assertEquals(0.0, cacheReadCost(row(100, 0), null)!!, 0.0)
        assertEquals(0.0, cacheReadCost(row(100, 80), price.copy(read = 0.0))!!, 0.0)
    }
}
