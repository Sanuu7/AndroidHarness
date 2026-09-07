package com.androidharness.app.automation

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json

class AutomationScheduleTest {
    @Test fun futureTimeIsToday() {
        val now = ZonedDateTime.parse("2026-09-07T07:30:00+05:30[Asia/Kolkata]")
        assertEquals(now.withHour(8).withMinute(0).toInstant().toEpochMilli(),
            AutomationManager.nextDaily(8, 0, now))
    }
    @Test fun elapsedTimeMovesToTomorrow() {
        val now = ZonedDateTime.parse("2026-09-07T08:00:00+05:30[Asia/Kolkata]")
        assertEquals(now.plusDays(1).toInstant().toEpochMilli(),
            AutomationManager.nextDaily(8, 0, now))
    }
    @Test fun localMorningSurvivesDaylightSavingChange() {
        val now = ZonedDateTime.parse("2026-03-07T09:00:00-05:00[America/New_York]")
        val result = AutomationManager.nextDaily(8, 0, now)
        assertEquals(ZonedDateTime.parse("2026-03-08T08:00:00-04:00[America/New_York]").toInstant().toEpochMilli(), result)
    }
    @Test fun savedTaskRetainsWorkspaceAndPrompt() {
        val task = AutomationTask(title = "Build", prompt = "Run tests\nFix failures",
            projectId = "project-a", projectName = "Project A", schedule = AutomationSchedule.DAILY)
        assertEquals(task, Json.decodeFromString<AutomationTask>(Json.encodeToString(AutomationTask.serializer(), task)))
    }
    @Test(expected = IllegalArgumentException::class)
    fun invalidHourIsRejected() { AutomationManager.nextDaily(24, 0) }
}
