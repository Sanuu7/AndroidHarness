package com.androidharness.app.automation

import androidx.work.*
import com.androidharness.app.AppContainer
import com.androidharness.app.agent.AgentMode
import com.androidharness.app.core.Role
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Daily schedules are best effort: Android may defer work while idle. */
class AutomationManager(private val c: AppContainer) {
    val repository = AutomationRepository(c.appContext)
    private val work get() = WorkManager.getInstance(c.appContext)
    private val mutex = Mutex()

    fun save(task: AutomationTask) {
        require(task.title.isNotBlank() && task.prompt.isNotBlank())
        require(task.hour in 0..23 && task.minute in 0..59)
        repository.save(task)
        val name = "automation-schedule-${task.id}"
        if (task.enabled && task.schedule == AutomationSchedule.DAILY) {
            val next = nextDaily(task.hour, task.minute)
            repository.save(task.copy(nextRunAt = next))
            work.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<AutomationWorker>(24, TimeUnit.HOURS)
                    .setNextScheduleTimeOverride(next)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf(AutomationWorker.KEY_TASK_ID to task.id, "scheduled" to true))
                    .build())
        } else {
            work.cancelUniqueWork(name)
            repository.save(task.copy(nextRunAt = null))
        }
    }

    fun runNow(id: String) {
        val task = repository.task(id) ?: return
        if (task.lastSessionId?.let { c.runManager.live(it).value.running } == true) return
        repository.save(task.copy(lastStatus = AutomationStatus.QUEUED, lastMessage = "Waiting for network and a worker"))
        work.enqueueUniqueWork("automation-run-$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutomationWorker>()
                .setInputData(workDataOf(AutomationWorker.KEY_TASK_ID to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build())
    }

    fun cancel(id: String) {
        work.cancelUniqueWork("automation-run-$id")
        work.cancelUniqueWork("automation-schedule-$id")
        repository.task(id)?.let { task ->
            task.lastSessionId?.let(c.runManager::stop)
            repository.save(task.copy(enabled = false, nextRunAt = null, lastStatus = AutomationStatus.CANCELLED))
        }
    }

    fun delete(id: String) { cancel(id); repository.delete(id) }

    suspend fun execute(id: String, scheduled: Boolean = false) = mutex.withLock {
        val task = repository.task(id) ?: return@withLock
        if (scheduled && !task.enabled) return@withLock
        val entry = AutomationHistoryEntry(taskId = id, title = task.title,
            startedAt = System.currentTimeMillis(), status = AutomationStatus.RUNNING)
        repository.addHistory(entry)
        var sid: String? = null
        fun status(value: AutomationStatus, message: String?, finished: Boolean = false) {
            repository.task(id)?.let { current ->
                repository.save(current.copy(lastStatus = value, lastMessage = message,
                    lastRunAt = entry.startedAt, lastSessionId = sid))
            }
            repository.updateHistory(entry.id) { it.copy(sessionId = sid, status = value,
                message = message, finishedAt = if (finished) System.currentTimeMillis() else null) }
        }
        try {
            status(AutomationStatus.RUNNING, "Preparing workspace")
            val project = c.workspace.projects.first().firstOrNull { it.id == task.projectId }
                ?: error("Workspace was removed. Edit this automation.")
            val fs = c.workspace.fsFor(project)
            check(c.mcp.unapprovedWorkspaceServers(fs).isEmpty()) {
                "Workspace MCP configuration needs approval in chat."
            }
            val settings = c.settings.settings.first()
            val providerId = if (settings.planningModelsEnabled)
                settings.executionProviderId ?: settings.activeProviderId else settings.activeProviderId
            val provider = c.providers.providers.first().firstOrNull { it.id == providerId }
                ?: error("Choose a provider in Settings.")
            val model = (if (settings.planningModelsEnabled) settings.executionModel else settings.activeModel)
                ?.takeIf { it.isNotBlank() } ?: provider.model
            val key = c.providers.apiKey(provider.id) ?: error("Provider credentials are missing.")
            if (provider.id == com.androidharness.app.llm.HarnessProvider.ID) {
                if (c.providers.wire(model) == null) {
                    com.androidharness.app.llm.HarnessProvider.probeWire(model)?.let {
                        c.providers.pinWire(model, it.name)
                    }
                }
                com.androidharness.app.llm.HarnessProvider.pins = c.providers.harnessWires.first()
            }
            sid = c.sessions.createSession(task.title, task.projectId)
            val session = requireNotNull(sid)
            var feedback = ""
            val attempts = if (task.checkCommand.isBlank()) 1 else task.maxAttempts.coerceIn(1, 20)
            for (attempt in 1..attempts) {
                currentCoroutineContext().ensureActive()
                c.runManager.startRun(session, task.prompt + feedback +
                    "\n\nWork autonomously toward this task. Run the actual checks and fix failures. " +
                    "Do not weaken tests to make them pass. Report evidence and any unresolved blockers.",
                    emptyList(), provider.copy(model = model), key, settings.permissionMode,
                    AgentMode.ACT, settings.maxOutputTokens, settings.maxContextTokens,
                    settings.thinkingLevel, settings.maxIterations, workspaceOverride = fs)
                var previous: String? = null
                while (true) {
                    val live = c.runManager.live(session).value
                    if (!live.running) break
                    val message = c.runManager.actionText(live)
                    if (message != previous) {
                        status(if (live.pendingApproval != null || live.pendingQuestion != null ||
                            live.pendingEnvironment != null) AutomationStatus.BLOCKED else AutomationStatus.RUNNING, message)
                        previous = message
                    }
                    delay(1000)
                }
                if (c.runManager.live(session).value.cancelled) {
                    status(AutomationStatus.CANCELLED, "Stopped by user.", true)
                    break
                }
                val error = c.runManager.live(session).value.error
                if (error != null) {
                    status(AutomationStatus.FAILED, error, true)
                    break
                }
                val result = c.sessions.messages(session).lastOrNull { it.role == Role.ASSISTANT }?.text
                if (task.checkCommand.isBlank()) {
                    status(AutomationStatus.COMPLETED, result?.take(4000) ?: "Run finished without a summary.", true)
                    break
                }
                status(AutomationStatus.RUNNING, "Verifying result · attempt $attempt of $attempts")
                val check = com.androidharness.app.tools.ShellTool(c.linuxEnv, c.shellRouter).execute(
                    kotlinx.serialization.json.buildJsonObject {
                        put("command", kotlinx.serialization.json.JsonPrimitive(task.checkCommand))
                        put("timeout_seconds", kotlinx.serialization.json.JsonPrimitive(600))
                    }, com.androidharness.app.tools.ToolContext(fs, sessionId = session))
                c.sessions.addMessage(session, com.androidharness.app.core.ChatMessage(role = Role.USER,
                    text = "Automation success check: " + task.checkCommand + "\n" + check.output))
                if (check.ok) {
                    status(AutomationStatus.PASSED, "Success check passed.\n" + check.output.takeLast(3500), true)
                    break
                }
                if (attempt == attempts) {
                    status(AutomationStatus.FAILED, "Success check still fails after $attempts attempts.\n" +
                        check.output.takeLast(3500), true)
                } else {
                    feedback = "\nThe success check still fails. Fix it and verify again:\n" + check.output.takeLast(12000)
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                sid?.let { c.runManager.stopAndJoin(it) }
                status(AutomationStatus.CANCELLED, "Run interrupted. Retry from history.", true)
            }
            throw e
        } catch (e: Exception) {
            sid?.let { c.runManager.stopAndJoin(it) }
            status(AutomationStatus.BLOCKED, e.message ?: "Could not start task.", true)
        } finally {
            if (scheduled) repository.task(id)?.takeIf { it.enabled }?.let { save(it) }
        }
    }

    companion object {
        fun nextDaily(hour: Int, minute: Int, now: ZonedDateTime = ZonedDateTime.now()): Long {
            require(hour in 0..23 && minute in 0..59)
            var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return next.toInstant().toEpochMilli()
        }
    }
}
