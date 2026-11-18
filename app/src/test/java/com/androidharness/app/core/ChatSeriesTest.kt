package com.androidharness.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatSeriesTest {
    @Test fun plainPatternAppendsTheNumber() {
        assertEquals("ALPHA 1", ChatSeries.next("ALPHA", emptyList()))
        assertEquals("ALPHA 3", ChatSeries.next("ALPHA", listOf("ALPHA 1", "ALPHA 2", "Other")))
    }

    @Test fun hashMarksWhereTheNumberGoes() {
        assertEquals("Sprint 4: fixes", ChatSeries.next("Sprint #: fixes", listOf("Sprint 3: fixes", "Sprint 3")))
    }

    @Test fun countsPastTheHighestSoDeletedNumbersAreNotReused() {
        assertEquals("ALPHA 8", ChatSeries.next("ALPHA", listOf("ALPHA 7", "ALPHA 2")))
    }

    @Test fun onlyTitlesInTheSeriesCount() {
        assertNull(ChatSeries.numberOf("ALPHA", "ALPHABET 9"))
        assertNull(ChatSeries.numberOf("ALPHA", "ALPHA 9 notes"))
        assertEquals(9, ChatSeries.numberOf("alpha", " ALPHA 9 "))
        assertEquals(2, ChatSeries.numberOf("A.B", "A.B 2"))
        assertNull(ChatSeries.numberOf("A.B", "AxB 2"))
    }

    @Test fun blankPatternMeansNoSeries() {
        assertNull(ChatSeries.normalize("   "))
        assertEquals("ALPHA", ChatSeries.normalize("  ALPHA "))
    }
}
