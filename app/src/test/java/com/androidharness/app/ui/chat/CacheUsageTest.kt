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
        val price = ModelsDev.ModelCost(input = 10.0, output = 30.0, cacheRead = 2.0, cacheWrite = 12.5)
        assertEquals(11.25, cacheMissCost(row(1000000, 0, writes = 500000), price)!!, 0.00001)
        assertNull(cacheMissCost(row(100, 0, false), price))
        assertNull(cacheMissCost(row(100, 0), null))
    }

    @Test fun `cost includes only cached reads at that models read price`() {
        val price = ModelsDev.ModelCost(input = 10.0, output = 30.0, cacheRead = 2.0, cacheWrite = 12.5)
        assertEquals(0.4, cacheReadCost(row(1000000, 200000, writes = 500000), price)!!, 0.00001)
        assertNull(cacheReadCost(row(100, 80), null))
        assertNull(cacheReadCost(row(100, 0, false), price))
        assertEquals(0.0, cacheReadCost(row(100, 0), null)!!, 0.0)
        assertEquals(0.0, cacheReadCost(row(100, 80), price.copy(cacheRead = 0.0))!!, 0.0)
    }
}
