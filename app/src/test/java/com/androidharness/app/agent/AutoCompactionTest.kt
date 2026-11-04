package com.androidharness.app.agent

import org.junit.Assert.*
import org.junit.Test

class AutoCompactionTest {
    @Test fun `human context amounts resolve exactly`() {
        mapOf("250K" to 250_000, "258k" to 258_000, "350K" to 350_000,
            "1M" to 1_000_000, "1 million" to 1_000_000, "1.5m" to 1_500_000,
            "258000" to 258_000, " 350 k tokens " to 350_000,
            "1.024K" to 1_024, "2147483647" to Int.MAX_VALUE, "AUTO" to 0).forEach { (input, expected) ->
            assertEquals(input, expected, AutoCompaction.parse(input))
        }
    }

    @Test fun `invalid and overflowing values never wrap or round`() {
        listOf("", "0", "-350K", "1K", "NaN", "Infinity", "2147483648", "999999999M",
            "258K extra", "1.0241K", "default", "350KK").forEach {
            assertNull(it, AutoCompaction.parse(it))
        }
    }

    @Test fun `custom threshold replaces eighty percent and leaves reply headroom`() {
        assertEquals(800_000, AutoCompaction.threshold(0, 1_000_000, 32_768))
        assertEquals(350_000, AutoCompaction.threshold(350_000, 1_000_000, 32_768))
        assertEquals(258_000, AutoCompaction.threshold(258_000, 1_000_000, 32_768))
        assertEquals(900_000, AutoCompaction.threshold(900_000, 1_000_000, 32_768))
        assertEquals(1_000_000, AutoCompaction.threshold(1_000_000, 2_000_000, 32_768))
        assertEquals(950_000, AutoCompaction.threshold(1_000_000, 1_000_000, 32_768))
        assertEquals(32_000, AutoCompaction.threshold(350_000, 64_000, 32_768))
    }
}
