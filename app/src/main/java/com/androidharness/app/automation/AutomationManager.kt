package com.androidharness.app.automation

import androidx.work.*
import com.androidharness.app.AppContainer
import com.androidharness.app.RunResultNotification
import com.androidharness.app.RunResultNotifications
import com.androidharness.app.agent.AgentMode
import com.androidharness.app.core.Role
import com.androidharness.app.llm.effectiveApiKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Scheduled automations are best effort: Android may defer work while idle. */
class AutomationManager(private val c: AppContainer) {
    val repository = AutomationRepository(c.appContext)
    private val work get() = WorkManager.getInstance(c.appContext)
    private val mutex = Mutex()
    internal val network = AutomationNetwork(c.appContext)
    internal val connection get() = network.state
    private fun constraints(task: AutomationTask) = Constraints.Builder()
        .setRequiredNetworkType(if (task.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()

    fun save(task: AutomationTask) {
        require(task.title.isNotBlank() && task.prompt.isNotBlank())
        require(task.hour in 0..23 && task.minute in 0..59)
        require(task.githubPush != null || (!task.providerId.isNullOrBlank() && !task.model.isNullOrBlank())) {
            "Choose a model for this automation."
        }
        task.githubPush?.let {
            require(it.remoteUrl == com.androidharness.app.github.gitHubRepositoryUrl(it.remoteUrl) && it.branch.isNotBlank() && it.commitMessage.isNotBlank()) {
                "Choose a GitHub repository, branch and commit message."
            }
        }
        if (task.enabled && task.schedule == AutomationSchedule.ONCE) {
            require((task.scheduledAt ?: 0) > System.currentTimeMillis()) { "Scheduled time must be in the future." }
        }
        fun merge(old: AutomationTask): AutomationTask {
            require(old.activeRun == null || run {
                old.prompt == task.prompt && old.projectId == task.projectId && old.targetSessionId == task.targetSessionId &&
                    old.providerId == task.providerId && old.model == task.model
            }) { "Cancel the unfinished run before changing its instructions or destination." }
            return task.copy(activeRun = old.activeRun, lastStatus = old.lastStatus, lastMessage = old.lastMessage,
                lastRunAt = old.lastRunAt, lastSessionId = old.lastSessionId, lastFinishedOccurrence = old.lastFinishedOccurrence)
        }
        if (repository.updateTask(task.id, ::merge) == null) repository.save(task)
        val name = "automation-schedule-${task.id}"
        when {
            task.enabled && task.schedule == AutomationSchedule.HOURLY -> {
                val next = nextHourly()
                repository.updateTask(task.id) { it.copy(nextRunAt = next, scheduledAt = null) }
                work.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<AutomationWorker>(1, TimeUnit.HOURS)
                        .setNextScheduleTimeOverride(next)
                        .setConstraints(constraints(task))
                        .setInputData(workDataOf(AutomationWorker.KEY_TASK_ID to task.id, "scheduled" to true, AutomationWorker.KEY_OCCURRENCE to next.toString()))
                        .build())
            }
            task.enabled && task.schedule == AutomationSchedule.DAILY -> {
                val next = nextDaily(task.hour, task.minute)
                repository.updateTask(task.id) { it.copy(nextRunAt = next, scheduledAt = null) }
                work.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<AutomationWorker>(24, TimeUnit.HOURS)
                        .setNextScheduleTimeOverride(next)
                        .setConstraints(constraints(task))
                        .setInputData(workDataOf(AutomationWorker.KEY_TASK_ID to task.id, "scheduled" to true, AutomationWorker.KEY_OCCURRENCE to next.toString()))
                        .build())
            }
            task.enabled && task.schedule == AutomationSchedule.ONCE -> {
                val runAt = requireNotNull(task.scheduledAt) { "Choose a date and time." }
                require(runAt > System.currentTimeMillis()) { "Scheduled time must be in the future." }
                repository.updateTask(task.id) { it.copy(nextRunAt = runAt) }
                work.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<AutomationWorker>()
                        .setInitialDelay((runAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                        .setConstraints(constraints(task))
                        .setInputData(workDataOf(AutomationWorker.KEY_TASK_ID to task.id, "scheduled" to true, AutomationWorker.KEY_OCCURRENCE to runAt.toString()))
                        .build())
            }
            else -> {
                work.cancelUniqueWork(name)
                repository.updateTask(task.id) { it.copy(nextRunAt = null, scheduledAt = if (task.schedule == AutomationSchedule.ONCE) task.scheduledAt else null) }
            }
        }
    }

    fun runNow(id: String) {
        val task = repository.task(id) ?: return
        repository.updateTask(id) { it.copy(lastStatus = AutomationStatus.QUEUED, lastFinishedOccurrence = null,
            lastMessage = if (connection.value.allows(task)) "Waiting to start" else connection.value.waitingMessage(task))
        }
        work.enqueueUniqueWork("automation-run-$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutomationWorker>()
                .setInputData(workDataOf(AutomationWorker.KEY_TASK_ID to id,
                    AutomationWorker.KEY_OCCURRENCE to (task.activeRun?.occurrence ?: java.util.UUID.randomUUID().toString())))
                .setConstraints(constraints(task))
                .build())
    }

    fun cancel(id: String) {
        work.cancelUniqueWork("automation-run-$id")
        work.cancelUniqueWork("automation-schedule-$id")
        repository.task(id)?.let { task ->
            task.activeRun?.let { active ->
                if (c.runManager.controls.flow(active.sessionId).value.automationRunId == active.id) c.runManager.stop(active.sessionId)
                repository.updateHistory(active.historyId) { it.copy(status = AutomationStatus.CANCELLED,
                    message = "Cancelled", finishedAt = System.currentTimeMillis()) }
            }
            repository.updateTask(id) { it.copy(enabled = false, nextRunAt = null, activeRun = null,
                lastStatus = AutomationStatus.CANCELLED, lastMessage = "Cancelled") }
        }
    }

    fun delete(id: String) { cancel(id); repository.delete(id) }

    fun pause(id: String) {
        work.cancelUniqueWork("automation-run-$id")
        work.cancelUniqueWork("automation-schedule-$id")
        repository.task(id)?.let { task ->
            repository.updateTask(id) { it.copy(enabled = false, nextRunAt = null, lastStatus = AutomationStatus.PAUSED, lastMessage = "Paused") }
        }
    }

    fun resumeSchedule(id: String) {
        val task = repository.task(id) ?: return
        if (task.activeRun != null || task.schedule == AutomationSchedule.ONCE && (task.scheduledAt ?: 0) <= System.currentTimeMillis()) {
            repository.updateTask(id) { it.copy(enabled = true) }
            runNow(id)
        } else {
            repository.updateTask(id) { it.copy(lastStatus = AutomationStatus.IDLE, lastMessage = null) }
            save(requireNotNull(repository.task(id)).copy(enabled = true))
        }
    }

    internal suspend fun execute(id: String, scheduled: Boolean = false, occurrence: String = java.util.UUID.randomUUID().toString()): AutomationOutcome = mutex.withLock {
        val task = repository.task(id) ?: return@withLock AutomationOutcome.DONE
        if (scheduled && !task.enabled || task.lastFinishedOccurrence == occurrence) return@withLock AutomationOutcome.DONE
        if (scheduled && task.activeRun == null && task.nextRunAt?.let { it > System.currentTimeMillis() } == true) {
            return@withLock AutomationOutcome.RETRY
        }
        if (task.activeRun != null && task.activeRun.occurrence != occurrence) return@withLock AutomationOutcome.DONE
        fun waiting(message: String): AutomationOutcome {
            repository.updateTask(id) { if (it.lastStatus == AutomationStatus.CANCELLED) it else
                it.copy(lastStatus = AutomationStatus.QUEUED, lastMessage = message) }
            return AutomationOutcome.RETRY
        }
        if (!connection.value.allows(task)) return@withLock waiting(connection.value.waitingMessage(task))
        val destination = task.activeRun?.sessionId ?: task.targetSessionId
        if (destination != null && c.runManager.isRunning(destination)) return@withLock waiting("Waiting for this chat to finish")
        if (destination != null && task.activeRun == null && c.runManager.controls.flow(destination).value.resumable) {
            return@withLock waiting("Waiting for this chat to finish")
        }
        var run = task.activeRun
        var resultPreview: String? = null
        var runKey: String? = null
        fun status(value: AutomationStatus, message: String?, finished: Boolean = false) {
            val active = run
            val updated = repository.updateTask(id) { current ->
                if (current.lastStatus == AutomationStatus.CANCELLED && !current.enabled) return@updateTask current
                val terminal = finished && value != AutomationStatus.BLOCKED && value != AutomationStatus.FAILED
                current.copy(lastStatus = value, lastMessage = message,
                    lastRunAt = active?.startedAt ?: current.lastRunAt, lastSessionId = active?.sessionId ?: current.lastSessionId,
                    activeRun = if (terminal) null else current.activeRun,
                    lastFinishedOccurrence = if (finished) occurrence else current.lastFinishedOccurrence,
                    enabled = if (finished && current.schedule == AutomationSchedule.ONCE) false else current.enabled,
                    nextRunAt = if (finished && current.schedule == AutomationSchedule.ONCE) null else current.nextRunAt)
            } ?: return
            if (updated.lastStatus == AutomationStatus.CANCELLED && value != AutomationStatus.CANCELLED) return
            active?.let { state ->
                repository.updateHistory(state.historyId) { it.copy(status = value, message = message,
                    finishedAt = if (finished) System.currentTimeMillis() else null) }
                if (finished) runCatching {
                    RunResultNotifications.post(c.appContext, RunResultNotification(state.sessionId, task.title,
                        value == AutomationStatus.COMPLETED || value == AutomationStatus.PASSED,
                        (resultPreview ?: message ?: "Run finished").take(4000),
                        notificationTitle = if (value == AutomationStatus.COMPLETED || value == AutomationStatus.PASSED)
                            "Scheduled task done" else "Scheduled task needs attention"))
                }
            }
        }
        fun saveRun(next: AutomationRun) {
            run = next
            repository.updateTask(id) {
                check(it.lastStatus != AutomationStatus.CANCELLED) { "Schedule was cancelled" }
                it.copy(activeRun = next, lastSessionId = next.sessionId)
            }
        }
        var finished = false
        try {
            val project = c.workspace.projects.first().firstOrNull { it.id == task.projectId }
                ?: error("Workspace was removed. Edit this schedule.")
            val fs = c.workspace.fsFor(project)
            val target = destination?.let { c.sessions.session(it) ?: error("Chat was removed. Choose another chat.") }
            check(target == null || target.projectId == task.projectId) { "This chat's workspace changed. Edit this schedule." }
            if (run == null) {
                val sid = destination ?: c.sessions.createSession(task.title, task.projectId)
                val state = AutomationRun(occurrence = occurrence, sessionId = sid)
                saveRun(state)
            }
            val state = requireNotNull(run)
            if (repository.history.value.none { it.id == state.historyId }) {
                repository.addHistory(AutomationHistoryEntry(id = state.historyId, taskId = id, title = task.title,
                    providerId = task.providerId, model = task.model, sessionId = state.sessionId,
                    startedAt = state.startedAt, status = AutomationStatus.RUNNING))
            }
            val session = requireNotNull(run).sessionId
            status(AutomationStatus.RUNNING, "Preparing workspace")
            if (task.githubPush != null) {
                // A push interrupted after contacting GitHub has an unknown outcome: require review.
                check(!requireNotNull(run).agentStarted) { "The previous push was interrupted. Check GitHub before running it again." }
                saveRun(requireNotNull(run).copy(agentStarted = true))
                val result = c.githubRepositories.pushPreset(fs, task.githubPush)
                c.sessions.addMessage(session, com.androidharness.app.core.ChatMessage(role = Role.ASSISTANT, text = result))
                resultPreview = result
                status(AutomationStatus.PASSED, result, true)
                finished = true
                return@withLock AutomationOutcome.DONE
            }
            check(c.mcp.unapprovedWorkspaceServers(fs).isEmpty()) { "Workspace tools need approval. Open this chat to approve them." }
            val settings = c.settings.settings.first()
            val provider = c.providers.providers.first().firstOrNull { it.id == task.providerId }
                ?: error("The saved provider is unavailable. Choose another model.")
            val model = task.model?.takeIf { it.isNotBlank() } ?: error("Choose a model for this schedule.")
            val key = if (provider.id == com.androidharness.app.llm.HarnessProvider.ID) c.providers.harnessApiKey()
                else provider.effectiveApiKey(c.providers.apiKey(provider.id)).takeIf { it.isNotBlank() }
                    ?: error("Sign in or add credentials for the saved provider.")
            runKey = key
            if (provider.id == com.androidharness.app.llm.HarnessProvider.ID) {
                if (c.providers.wire(model) == null && !com.androidharness.app.llm.HarnessProvider.isPooled(model) &&
                    c.providers.customModels(provider.id).any { it.id == model }) {
                    com.androidharness.app.llm.HarnessProvider.probeWire(model, key)?.let { c.providers.pinWire(model, it.name) }
                }
                com.androidharness.app.llm.HarnessProvider.pins = c.providers.harnessWires.first()
            }
            val attempts = if (task.checkCommand.isBlank()) 1 else task.maxAttempts.coerceIn(1, 20)
            while (requireNotNull(run).attempt <= attempts) {
                currentCoroutineContext().ensureActive()
                val active = requireNotNull(run)
                val saved = c.runManager.controls.flow(session).value
                check(!active.checking || saved.automationRunId == active.id) { "This chat has moved on. Open it to review the schedule." }
                if (!active.checking) {
                    when (automationRecovery(active, saved)) {
                        AutomationRecovery.START -> {
                            c.runManager.startRun(session, task.prompt + active.feedback +
                                "\n\nWork autonomously toward this task. Run the actual checks and fix failures. " +
                                "Do not weaken tests to make them pass. Report evidence and any unresolved blockers.",
                                emptyList(), provider.copy(model = model), key, settings.permissionMode, AgentMode.ACT,
                                settings.maxOutputTokens, settings.maxContextTokens, settings.thinkingLevel, settings.maxIterations,
                                workspaceOverride = fs, notifyOnFinish = false, onlyIfIdle = true, automationRunId = active.id)
                            saveRun(active.copy(agentStarted = true))
                        }
                        AutomationRecovery.RESUME -> c.runManager.resumeTask(session, key, active.id, notifyOnFinish = false)
                        AutomationRecovery.FINISHED -> Unit
                        AutomationRecovery.CANCELLED -> {
                            status(AutomationStatus.CANCELLED, "Stopped in chat", true); finished = true
                            return@withLock AutomationOutcome.DONE
                        }
                        AutomationRecovery.REVIEW -> error("This chat has moved on. Open it to review the interrupted schedule.")
                    }
                    var previous: String? = null
                    while (c.runManager.isRunning(session)) {
                        val live = c.runManager.live(session).value
                        val message = c.runManager.actionText(live)
                        if (message != previous) {
                            status(if (live.pendingApproval != null || live.pendingQuestion != null || live.pendingEnvironment != null)
                                AutomationStatus.BLOCKED else AutomationStatus.RUNNING, message)
                            previous = message
                        }
                        delay(250)
                    }
                    val record = c.runManager.controls.flow(session).value
                    check(record.automationRunId == active.id) { "This chat has moved on. Open it to review the schedule." }
                    val live = c.runManager.live(session).value
                    if (live.cancelled) {
                        status(AutomationStatus.CANCELLED, "Stopped in chat", true); finished = true
                        return@withLock AutomationOutcome.DONE
                    }
                    check(record.status == "idle" && live.error == null) { record.reason ?: live.error ?: "Open this chat to continue the task." }
                    resultPreview = c.sessions.messages(session).lastOrNull { it.role == Role.ASSISTANT }?.text?.trim()?.take(4000)
                    if (task.checkCommand.isBlank()) {
                        status(AutomationStatus.COMPLETED, resultPreview ?: "Run finished", true); finished = true
                        return@withLock AutomationOutcome.DONE
                    }
                    saveRun(requireNotNull(run).copy(checking = true))
                }
                status(AutomationStatus.RUNNING, "Verifying result · attempt ${active.attempt} of $attempts")
                val check = com.androidharness.app.tools.ShellTool(c.linuxEnv, c.shellRouter).execute(
                    kotlinx.serialization.json.buildJsonObject {
                        put("command", kotlinx.serialization.json.JsonPrimitive(task.checkCommand))
                        put("timeout_seconds", kotlinx.serialization.json.JsonPrimitive(600))
                    }, com.androidharness.app.tools.ToolContext(fs, sessionId = session))
                c.sessions.addMessage(session, com.androidharness.app.core.ChatMessage(role = Role.USER,
                    text = "Automation success check: ${task.checkCommand}\n${check.output}"))
                if (check.ok) {
                    status(AutomationStatus.PASSED, "Success check passed.\n" + check.output.takeLast(3500), true); finished = true
                    return@withLock AutomationOutcome.DONE
                }
                if (active.attempt == attempts) error("Success check still fails after $attempts attempts.\n" + check.output.takeLast(3500))
                saveRun(active.copy(id = java.util.UUID.randomUUID().toString(), attempt = active.attempt + 1,
                    agentStarted = false, checking = false,
                    feedback = "\nThe success check still fails. Fix it and verify again:\n" + check.output.takeLast(12000)))
            }
            AutomationOutcome.DONE
        } catch (e: com.androidharness.app.agent.RunBusyException) {
            waiting("Waiting for this chat to finish")
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                run?.let { active ->
                    val record = c.runManager.controls.flow(active.sessionId).value
                    if (record.automationRunId == active.id && record.resumable) {
                        c.runManager.stopAndJoin(active.sessionId)
                        c.runManager.controls.update(active.sessionId) {
                            if (it.completed) it else it.copy(status = "interrupted", reason = "Scheduled task interrupted")
                        }
                    }
                }
                repository.task(id)?.takeIf { it.activeRun != null }?.let { current ->
                    status(if (current.enabled) AutomationStatus.QUEUED else AutomationStatus.PAUSED,
                        if (!current.enabled) "Paused" else if (!connection.value.allows(current)) connection.value.waitingMessage(current)
                        else "Interrupted · waiting to continue")
                }
            }
            throw e
        } catch (e: Exception) {
            run?.let { active ->
                if (c.runManager.controls.flow(active.sessionId).value.automationRunId == active.id) c.runManager.stopAndJoin(active.sessionId)
            }
            val message = if (task.githubPush != null) c.github.safeMessage(e) else e.message ?: "Could not start task"
            status(AutomationStatus.BLOCKED, message, true); finished = true
            AutomationOutcome.DONE
        } finally {
            if (finished && repository.task(id)?.lastStatus in listOf(AutomationStatus.COMPLETED, AutomationStatus.PASSED)) {
                val active = run
                val key = runKey
                if (active != null && key != null) c.runManager.finishScheduledRun(active.sessionId, active.id, key)
            }
            if (finished && scheduled) repository.task(id)?.takeIf { it.enabled }?.let { current ->
                if (current.schedule == AutomationSchedule.DAILY || current.schedule == AutomationSchedule.HOURLY) save(current)
            }
        }
    }

    companion object {
        fun nextHourly(now: ZonedDateTime = ZonedDateTime.now()): Long =
            now.plusHours(1).toInstant().toEpochMilli()

        fun nextDaily(hour: Int, minute: Int, now: ZonedDateTime = ZonedDateTime.now()): Long {
            require(hour in 0..23 && minute in 0..59)
            var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return next.toInstant().toEpochMilli()
        }
    }
}
