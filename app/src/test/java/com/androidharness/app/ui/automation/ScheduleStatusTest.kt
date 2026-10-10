package com.androidharness.app.ui.automation

import com.androidharness.app.automation.*
import org.junit.Assert.*
import org.junit.Test

class ScheduleStatusTest {
    private val task = AutomationTask(title = "Build", prompt = "Build", projectId = "p", projectName = "Project",
        schedule = AutomationSchedule.ONCE, nextRunAt = 1000, unmeteredOnly = true)
    @Test fun dueScheduleShowsWhyItIsWaiting() {
        assertEquals("Waiting for unmetered internet", scheduleStatus(task, AutomationConnection(true, false), 1000))
        assertEquals("Waiting to start", scheduleStatus(task, AutomationConnection(true, true), 1000))
    }
    @Test fun pauseAndCancellationDoNotLookLikeWaitingForNetwork() {
        assertEquals("Paused", scheduleStatus(task.copy(enabled = false, lastStatus = AutomationStatus.PAUSED), AutomationConnection(), 2000))
        assertEquals("Cancelled", scheduleStatus(task.copy(enabled = false, lastStatus = AutomationStatus.CANCELLED), AutomationConnection(), 2000))
    }
    @Test fun blockedRunsNeedAttentionEvenWhenConnectionReturns() {
        assertEquals("Needs your attention", scheduleStatus(task.copy(enabled = false, lastStatus = AutomationStatus.BLOCKED), AutomationConnection(true, true), 2000))
    }
}
