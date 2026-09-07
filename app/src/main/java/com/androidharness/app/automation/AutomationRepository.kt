package com.androidharness.app.automation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class AutomationSchedule { MANUAL, ONCE, HOURLY, DAILY }

@Serializable
enum class AutomationStatus { IDLE, QUEUED, RUNNING, COMPLETED, PASSED, FAILED, BLOCKED, CANCELLED }

@Serializable
data class AutomationTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val prompt: String,
    val projectId: String,
    val projectName: String,
    val providerId: String? = null,
    val model: String? = null,
    val checkCommand: String = "",
    val maxAttempts: Int = 5,
    val schedule: AutomationSchedule = AutomationSchedule.MANUAL,
    val scheduledAt: Long? = null,
    val hour: Int = 8,
    val minute: Int = 0,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null,
    val nextRunAt: Long? = null,
    val lastStatus: AutomationStatus = AutomationStatus.IDLE,
    val lastSessionId: String? = null,
    val lastMessage: String? = null,
)

@Serializable
data class AutomationHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val title: String,
    val sessionId: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: AutomationStatus,
    val message: String? = null,
)

class AutomationRepository(context: Context) {
    private val prefs = context.getSharedPreferences("automation", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val taskSerializer = ListSerializer(AutomationTask.serializer())
    private val historySerializer = ListSerializer(AutomationHistoryEntry.serializer())
    private val lock = Any()

    private val _tasks = MutableStateFlow(loadTasks())
    val tasks: StateFlow<List<AutomationTask>> = _tasks
    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<AutomationHistoryEntry>> = _history

    fun task(id: String): AutomationTask? = _tasks.value.firstOrNull { it.id == id }

    fun save(task: AutomationTask) = synchronized(lock) {
        val next = _tasks.value.toMutableList()
        val index = next.indexOfFirst { it.id == task.id }
        if (index >= 0) next[index] = task else next.add(0, task)
        _tasks.value = next
        prefs.edit().putString("tasks", json.encodeToString(taskSerializer, next)).apply()
    }

    fun delete(id: String) = synchronized(lock) {
        val next = _tasks.value.filterNot { it.id == id }
        _tasks.value = next
        prefs.edit().putString("tasks", json.encodeToString(taskSerializer, next)).apply()
    }

    fun addHistory(entry: AutomationHistoryEntry) = synchronized(lock) {
        val next = (listOf(entry) + _history.value).take(200)
        _history.value = next
        prefs.edit().putString("history", json.encodeToString(historySerializer, next)).apply()
    }

    fun updateHistory(id: String, transform: (AutomationHistoryEntry) -> AutomationHistoryEntry) = synchronized(lock) {
        val next = _history.value.map { if (it.id == id) transform(it) else it }
        _history.value = next
        prefs.edit().putString("history", json.encodeToString(historySerializer, next)).apply()
    }

    private fun loadTasks(): List<AutomationTask> = prefs.getString("tasks", null)?.let { raw ->
        runCatching { json.decodeFromString(taskSerializer, raw) }.getOrNull()
    }.orEmpty()

    private fun loadHistory(): List<AutomationHistoryEntry> = prefs.getString("history", null)?.let { raw ->
        runCatching { json.decodeFromString(historySerializer, raw) }.getOrNull()
    }.orEmpty()
}
