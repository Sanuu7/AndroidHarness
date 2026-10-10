package com.androidharness.app.ui.stats

import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.data.db.StatsUsagePojo
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StatsDataTest {
    @Test fun todayBeginsAtLocalMidnightAndExcludesTheNextDay() {
        val zone = ZoneId.of("Asia/Kolkata")
        val window = StatsRange.DAY.window(Instant.parse("2026-10-10T12:00:00Z").toEpochMilli(), zone)
        assertEquals(Instant.parse("2026-10-09T18:30:00Z").toEpochMilli(), window.since)
        assertEquals(Instant.parse("2026-10-10T18:30:00Z").toEpochMilli(), window.until)
    }

    @Test fun calendarWindowsHandleDstAndAdvanceWhenTheDayChanges() {
        val zone = ZoneId.of("America/New_York")
        val now = Instant.parse("2026-03-08T16:00:00Z").toEpochMilli()
        val day = StatsRange.DAY.window(now, zone)
        assertEquals(23 * 60 * 60 * 1000L, day.until - day.since)
        assertEquals(day.until, StatsRange.DAY.window(day.until, zone).since)
        val week = StatsRange.WEEK.window(now, zone)
        assertEquals(Instant.parse("2026-03-02T05:00:00Z").toEpochMilli(), week.since)
        assertEquals(day.until, week.until)
        val fall = StatsRange.DAY.window(Instant.parse("2026-11-01T16:00:00Z").toEpochMilli(), zone)
        assertEquals(25 * 60 * 60 * 1000L, fall.until - fall.since)
        assertEquals(StatsWindow(0, Long.MAX_VALUE), StatsRange.LIFETIME.window(now, zone))
    }

    @Test fun windowTotalsAndModelRowsShareCountsAcrossSessionsAndModels() {
        val rows = listOf(
            StatsUsagePojo("chat-a", "Provider", "main", 1000, 100, 200, 50, 2),
            StatsUsagePojo("chat-a", "Provider", "subagent", 500, 50, 100, 0, 1),
            StatsUsagePojo("chat-b", "Provider", "main", 500, 100, 400, 0, 1),
        )
        val stats = statsSnapshot(rows)
        assertEquals(2000L, stats.bundle.input)
        assertEquals(250L, stats.bundle.output)
        assertEquals(700L, stats.bundle.cached)
        assertEquals(50L, stats.bundle.cacheWrite)
        assertEquals(1250L, stats.bundle.freshInput)
        assertEquals(4L, stats.bundle.requests)
        assertEquals(2, stats.bundle.sessionCount)
        assertEquals(0.8, stats.bundle.peakHitRate!!, 0.0001)
        assertEquals(stats.bundle.input, stats.byModel.sumOf { it.inputTokens })
        assertEquals(stats.bundle.output, stats.byModel.sumOf { it.outputTokens })
        assertEquals(stats.bundle.cached, stats.byModel.sumOf { it.cachedTokens })
        assertEquals(stats.bundle.requests, stats.byModel.sumOf { it.requests })
        assertEquals(3L, stats.byModel.first().requests)
    }

    @Test fun lifetimePreservesUndatedTotalsWithoutAddingThemToDateFilteredUsage() {
        val session = SessionEntity("same-chat", "Five-day chat", 1, 100, totalInputTokens = 10000,
            totalOutputTokens = 1000, totalCachedTokens = 4000, totalCacheWriteTokens = 100, requestCount = 50)
        val today = listOf(StatsUsagePojo("same-chat", "P", "model", 100, 10, 20, 0, 1))
        assertEquals(110L, statsSnapshot(today).bundle.let { it.input + it.output })
        val lifetime = statsSnapshot(today, listOf(session))
        assertEquals(11000L, lifetime.bundle.let { it.input + it.output })
        assertEquals(50L, lifetime.bundle.requests)
        assertEquals(4000L, lifetime.bundle.cached)
        assertEquals(110L, lifetime.byModel.single().totalTokens)
    }

    @Test fun emptyWindowHasNoRequestsSessionsOrCacheScore() {
        assertEquals(StatsSnapshot(), statsSnapshot(emptyList()))
    }
}
