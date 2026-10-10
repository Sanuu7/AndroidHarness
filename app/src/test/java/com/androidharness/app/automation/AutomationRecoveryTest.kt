package com.androidharness.app.automation

import com.androidharness.app.agent.TaskRecord
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AutomationRecoveryTest {
    private val task = AutomationTask(title = "Build", prompt = "Build project", projectId = "workspace", projectName = "Project", createdAt = 123)
    private val run = AutomationRun(id = "occurrence-a", occurrence = "due-at-8", sessionId = "chat-a", startedAt = 456)

    @Test fun olderSavedTasksKeepAnyNetworkAndNewChatDefaults() {
        val restored = Json.decodeFromString<AutomationTask>("""{"title":"Build","prompt":"Build project","projectId":"workspace","projectName":"Project"}""")
        assertFalse(restored.unmeteredOnly)
        assertNull(restored.targetSessionId)
        assertNull(restored.activeRun)
    }
    @Test fun savedOccurrenceKeepsChatAndProgressAcrossProcessRestart() {
        val saved = task.copy(targetSessionId = "chat-a", unmeteredOnly = true, activeRun = run.copy(agentStarted = true))
        val restored = Json.decodeFromString<AutomationTask>(Json.encodeToString(AutomationTask.serializer(), saved))
        assertEquals(saved, restored)
        assertEquals(AutomationRecovery.RESUME, automationRecovery(restored.activeRun!!,
            TaskRecord(status = "interrupted", automationRunId = run.id)))
    }
    @Test fun crashAfterRunStartedBeforeWorkerSavedStillResumes() {
        assertEquals(AutomationRecovery.RESUME, automationRecovery(run, TaskRecord(status = "interrupted", automationRunId = run.id)))
    }
    @Test fun finishedAgentIsNotSentThePromptAgain() {
        assertEquals(AutomationRecovery.FINISHED, automationRecovery(run, TaskRecord(status = "idle", automationRunId = run.id, completed = true)))
    }
    @Test fun stoppedRunDoesNotBecomeCompletedAfterRestart() {
        assertEquals(AutomationRecovery.CANCELLED, automationRecovery(run, TaskRecord(status = "idle", automationRunId = run.id)))
    }
    @Test fun manualTurnAfterInterruptionRequiresReview() {
        assertEquals(AutomationRecovery.REVIEW, automationRecovery(run.copy(agentStarted = true),
            TaskRecord(status = "paused", automationRunId = null)))
    }
    @Test fun firstAttemptStartsAndNewAttemptKeepsItsHistory() {
        assertEquals(AutomationRecovery.START, automationRecovery(run, TaskRecord()))
        val nextAttempt = run.copy(id = "occurrence-b", attempt = 2)
        assertEquals(run.historyId, nextAttempt.historyId)
        assertEquals(AutomationRecovery.START, automationRecovery(nextAttempt, TaskRecord(status = "idle", automationRunId = run.id)))
    }
    @Test fun unmeteredScheduleNeverAllowsMeteredOrDisconnectedNetworks() {
        val protected = task.copy(unmeteredOnly = true)
        assertFalse(AutomationConnection(false, false).allows(protected))
        assertFalse(AutomationConnection(true, false).allows(protected))
        assertTrue(AutomationConnection(true, true).allows(protected))
        assertTrue(AutomationConnection(true, false).allows(task))
        assertFalse(AutomationConnection(false, true).allows(task))
    }
}
