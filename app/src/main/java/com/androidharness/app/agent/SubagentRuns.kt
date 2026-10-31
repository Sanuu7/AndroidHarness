package com.androidharness.app.agent

import com.androidharness.app.core.ToolCallData
import com.androidharness.app.tools.SecretRedactor
import com.androidharness.app.tools.ToolResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

@Serializable
enum class SubagentStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, STOPPED, INTERRUPTED;
    val active: Boolean get() = this == QUEUED || this == RUNNING
}

@Serializable
data class SubagentRun(
    val sessionId: String,
    val turnId: String,
    val call: ToolCallData,
    val name: String,
    val prompt: String,
    val model: String,
    val providerId: String,
    val background: Boolean,
    val workspacePaths: List<String> = emptyList(),
    val status: SubagentStatus = SubagentStatus.QUEUED,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val steps: List<String> = emptyList(),
    val preview: String = "",
    val output: String = "",
    val delivered: Boolean = false,
) {
    val id: String get() = call.id
    fun report(): String = buildString {
        append(name).append(" [").append(id).append("]: ").append(status.name.lowercase())
        append("\nTask: ").append(prompt.take(1000))
        steps.lastOrNull()?.let { append("\nLatest activity: ").append(it) }
        if (output.isNotBlank()) append("\nResult:\n").append(output.take(16000))
        else if (preview.isNotBlank()) append("\nVisible update: ").append(preview)
    }
}

/** Application-owned jobs. No ViewModel, producer flow or screen owns a background job. */
class SubagentRuns(
    private val file: File,
    private val scope: CoroutineScope,
    private val onEvent: suspend (SubagentRun, AgentEvent) -> Unit = { _, _ -> },
    private val onFinished: suspend (SubagentRun) -> Unit = {},
    private val acquireKeepalive: () -> Unit = {},
    private val releaseKeepalive: () -> Unit = {},
    parallelism: Int = 2,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val slots = Semaphore(parallelism)
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val earlyStops = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val values = MutableStateFlow<List<SubagentRun>>(emptyList())
    val runs: StateFlow<List<SubagentRun>> = values.asStateFlow()
    val activeSessionIds = runs.map { list -> list.filter { it.status.active }.map { it.sessionId }.toSet() }
        .distinctUntilChanged()
    private val ready = scope.async(Dispatchers.IO) {
        mutex.withLock {
            val stored = if (file.exists()) json.decodeFromString<List<SubagentRun>>(file.readText()) else emptyList()
            values.value = stored.map {
                if (it.status.active) it.copy(status = SubagentStatus.INTERRUPTED,
                    finishedAt = System.currentTimeMillis(), output = "Interrupted when the app process stopped. Start a new task to retry.") else it
            }
            if (stored.any { it.status.active }) save()
        }
    }

    fun forSession(sessionId: String): Flow<List<SubagentRun>> = runs.map { list ->
        list.filter { it.sessionId == sessionId }
    }.distinctUntilChanged()

    suspend fun awaitReady() { ready.await() }

    private fun key(sessionId: String, id: String) = "$sessionId:$id"

    private fun save(snapshot: List<SubagentRun> = values.value) {
        file.parentFile?.mkdirs()
        val next = File(file.path + ".next")
        FileOutputStream(next).use { stream ->
            stream.write(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(next.renameTo(file)) { "Could not save subagent progress" }
    }

    private suspend fun change(sessionId: String, id: String, durable: Boolean = true, transform: (SubagentRun) -> SubagentRun) {
        ready.await()
        withContext(Dispatchers.IO) { mutex.withLock {
            val next = values.value.map { if (it.sessionId == sessionId && it.id == id) transform(it) else it }
            if (durable) save(next)
            values.value = next
        } }
    }

    private suspend fun register(run: SubagentRun) {
        ready.await()
        withContext(Dispatchers.IO) { mutex.withLock {
            check(values.value.none { it.sessionId == run.sessionId && it.id == run.id }) { "This subagent already exists." }
            // Retain every live task and recent history without accumulating unbounded previews.
            val keep = values.value.filter { it.sessionId != run.sessionId || it.status.active } +
                values.value.filter { it.sessionId == run.sessionId && !it.status.active }.takeLast(39)
            val next = keep + run
            save(next)
            values.value = next
        } }
    }

    suspend fun record(sessionId: String, id: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.SubagentStep -> change(sessionId, id) {
                it.copy(steps = (it.steps + SecretRedactor.redact(event.line).take(240)).takeLast(6))
            }
            is AgentEvent.SubagentPreview -> change(sessionId, id, durable = false) {
                it.copy(preview = SecretRedactor.redact(event.text).takeLast(360))
            }
            is AgentEvent.SubagentMessageCommitted -> if (event.message.text.isNotBlank()) change(sessionId, id) {
                it.copy(preview = SecretRedactor.redact(event.message.text).takeLast(360))
            }
            is AgentEvent.Usage -> change(sessionId, id) { it.copy(model = event.model) }
            is AgentEvent.ProviderUsed -> change(sessionId, id) { it.copy(providerId = event.providerId) }
            else -> Unit
        }
    }

    /** Foreground delegates share the deck, but remain children of their calling run. */
    suspend fun foreground(run: SubagentRun, emit: suspend (AgentEvent) -> Unit,
        execute: suspend (suspend (AgentEvent) -> Unit) -> ToolResult): ToolResult {
        val caller = currentCoroutineContext()[Job]!!
        // A child job lets Stop cancel only this delegate, never its main agent.
        return supervisorScope {
            val child = async(start = CoroutineStart.LAZY) {
                execute { event -> record(run.sessionId, run.id, event); emit(event) }
            }
            jobs[key(run.sessionId, run.id)] = child
            try {
                register(run.copy(status = SubagentStatus.RUNNING, startedAt = System.currentTimeMillis()))
                child.start()
                val result = child.await()
                finish(run, result)
                result
            } catch (e: CancellationException) {
                withContext(NonCancellable) { finish(run, ToolResult(false, "Stopped by user."), SubagentStatus.STOPPED) }
                caller.ensureActive()
                ToolResult(false, "Subagent stopped by user.")
            } catch (e: Exception) {
                val result = ToolResult(false, SecretRedactor.redact(e.message ?: "Subagent failed."))
                finish(run, result)
                result
            } finally { child.cancel(); jobs.remove(key(run.sessionId, run.id), child) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun background(run: SubagentRun, inherited: CoroutineContext = EmptyCoroutineContext,
        execute: suspend (suspend (AgentEvent) -> Unit) -> ToolResult) {
        try { register(run) }
        catch (e: CancellationException) {
            withContext(NonCancellable) { finish(run, ToolResult(false, "Stopped by user."), SubagentStatus.STOPPED) }
            throw e
        }
        acquireKeepalive()
        val start = CompletableDeferred<Unit>()
        val job = scope.launch(inherited.minusKey(Job) + Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            try {
                start.await()
                slots.withPermit {
                    change(run.sessionId, run.id) { it.copy(status = SubagentStatus.RUNNING, startedAt = System.currentTimeMillis()) }
                    val result = execute { event ->
                        record(run.sessionId, run.id, event)
                        onEvent(run, event)
                    }
                    finish(run, result)
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { finish(run, ToolResult(false, "Stopped by user."), SubagentStatus.STOPPED) }
            } catch (e: Exception) {
                withContext(NonCancellable) { finish(run, ToolResult(false, SecretRedactor.redact(e.message ?: "Subagent failed."))) }
            } finally {
                try {
                    withContext(NonCancellable) { values.value.firstOrNull { it.sessionId == run.sessionId && it.id == run.id }?.let { onFinished(it) } }
                } finally {
                    releaseKeepalive()
                    jobs.remove(key(run.sessionId, run.id), currentCoroutineContext()[Job]!!)
                    earlyStops.remove(key(run.sessionId, run.id))
                }
            }
        }
        jobs[key(run.sessionId, run.id)] = job
        if (earlyStops.remove(key(run.sessionId, run.id))) job.cancel()
        start.complete(Unit)
    }

    private suspend fun finish(run: SubagentRun, result: ToolResult, status: SubagentStatus? = null) =
        change(run.sessionId, run.id) {
            it.copy(status = status ?: if (result.ok) SubagentStatus.COMPLETED else SubagentStatus.FAILED,
                finishedAt = System.currentTimeMillis(), output = SecretRedactor.redact(result.output).take(32000))
        }

    suspend fun list(sessionId: String): List<SubagentRun> { ready.await(); return values.value.filter { it.sessionId == sessionId } }

    suspend fun waitFor(sessionId: String, id: String, timeoutMs: Long): SubagentRun? {
        ready.await()
        if (list(sessionId).none { it.id == id }) return null
        return withTimeoutOrNull(timeoutMs.coerceIn(1, 30000)) {
            forSession(sessionId).first { list -> list.any { it.id == id && !it.status.active } }.first { it.id == id }
        } ?: list(sessionId).firstOrNull { it.id == id }
    }

    fun stop(sessionId: String, id: String) {
        val key = key(sessionId, id)
        val job = jobs[key]
        if (job != null) job.cancel()
        else if (values.value.any { it.sessionId == sessionId && it.id == id && it.status.active }) earlyStops += key
    }
    fun stopSession(sessionId: String) { values.value.filter { it.sessionId == sessionId && it.status.active }.forEach { stop(sessionId, it.id) } }
    suspend fun stopSessionAndJoin(sessionId: String) {
        ready.await()
        val active = values.value.filter { it.sessionId == sessionId }.mapNotNull { jobs[key(sessionId, it.id)] }
        active.forEach { it.cancel() }; active.joinAll()
    }
    suspend fun stopAllAndJoin() { ready.await(); val active = ArrayList(jobs.values); active.forEach { it.cancel() }; active.joinAll() }

    /** Caller persists these messages before acknowledging; a crash cannot silently lose a result. */
    suspend fun pending(sessionId: String) = list(sessionId).filter { it.background && !it.status.active && !it.delivered }
    suspend fun acknowledge(sessionId: String, id: String) = change(sessionId, id) { it.copy(delivered = true) }
}
