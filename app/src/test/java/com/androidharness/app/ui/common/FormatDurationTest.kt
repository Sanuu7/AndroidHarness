package com.androidharness.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDurationTest {
    @Test fun secondsStayUnderAMinute() {
        assertEquals("", formatDuration(0))
        assertEquals("0s", formatDuration(400))
        assertEquals("59s", formatDuration(59_999))
    }

    @Test fun minutesDropAZeroSecondPart() {
        assertEquals("2m", formatDuration(120_000))
        assertEquals("5m", formatDuration(300_000))
        assertEquals("3m 20s", formatDuration(200_000))
    }

    @Test fun hoursAndDaysTakeOver() {
        assertEquals("1h", formatDuration(3_600_000))
        assertEquals("1h 4m", formatDuration(3_840_000))
        assertEquals("1d", formatDuration(86_400_000))
        assertEquals("2d 3h", formatDuration(183_600_000))
    }
}
