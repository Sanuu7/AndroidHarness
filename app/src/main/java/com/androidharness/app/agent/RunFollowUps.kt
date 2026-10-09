package com.androidharness.app.agent

import java.util.UUID

/** One completion can advance only its own chat, once, unless a user has stopped or replaced it. */
internal class RunFollowUps(private val controls: TaskControlStore) {
    data class End(
        val sessionId: String,
        val runId: String,
        val finishedNotification: Boolean,
        val recoverableInterruption: Boolean,
    )

    sealed interface Action {
        data class Send(val prompt: QueuedPrompt) : Action
        data object Continue : Action
    }

    private data class Run(val id: String, var handled: Boolean = false)
    private val runs = mutableMapOf<String, Run>()

    @Synchronized fun begin(sessionId: String): String = UUID.randomUUID().toString().also { runs[sessionId] = Run(it) }
    @Synchronized fun advance(sessionId: String, previousRunId: String): String? =
        if (runs[sessionId]?.id == previousRunId) begin(sessionId) else null
    @Synchronized fun isCurrent(sessionId: String, runId: String): Boolean = runs[sessionId]?.id == runId
    @Synchronized fun stop(sessionId: String) { runs.remove(sessionId) }
    @Synchronized fun stopAll() { runs.clear() }

    /** Called only after the job finishes, under the run-start mutex. Re-read edits and settings here. */
    @Synchronized fun next(end: End): Action? {
        val run = runs[end.sessionId] ?: return null
        if (run.id != end.runId || run.handled) return null
        run.handled = true
        val saved = controls.flow(end.sessionId).value
        if (end.finishedNotification && saved.status == "idle") {
            saved.queue.firstOrNull()?.let { return Action.Send(it) }
        }
        if (!end.recoverableInterruption || saved.status !in listOf("paused", "interrupted") ||
            !saved.autoContinue || saved.autoContinueAttempts >= saved.autoContinueLimit.coerceIn(1, 5)) {
            runs.remove(end.sessionId)
            return null
        }
        // Persist before sending so an interrupted restart cannot erase the retry count.
        controls.update(end.sessionId) { it.copy(autoContinueAttempts = it.autoContinueAttempts + 1) }
        return Action.Continue
    }
}
