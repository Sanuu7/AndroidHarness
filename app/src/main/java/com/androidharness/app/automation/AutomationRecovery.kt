package com.androidharness.app.automation

import com.androidharness.app.agent.TaskRecord

internal enum class AutomationRecovery { START, RESUME, FINISHED, CANCELLED, REVIEW }

internal fun automationRecovery(run: AutomationRun, record: TaskRecord): AutomationRecovery = when {
    record.automationRunId == run.id && record.resumable -> AutomationRecovery.RESUME
    record.automationRunId == run.id && record.status == "idle" ->
        if (record.completed) AutomationRecovery.FINISHED else AutomationRecovery.CANCELLED
    run.agentStarted -> AutomationRecovery.REVIEW
    else -> AutomationRecovery.START
}
