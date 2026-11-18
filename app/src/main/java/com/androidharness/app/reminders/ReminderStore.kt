package com.androidharness.app.reminders

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Keeps reminders in one small JSON file, so they survive restarts without a database migration. */
class ReminderStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Reminder.serializer())
    private val state = MutableStateFlow<List<Reminder>?>(null)

    /** Null until the file has been read once. */
    val reminders: StateFlow<List<Reminder>?> = state.asStateFlow()

    suspend fun all(): List<Reminder> = lock.withLock { loadLocked() }

    suspend fun get(id: String): Reminder? = all().firstOrNull { it.id == id }

    suspend fun add(reminder: Reminder) = edit { list ->
        if (list.count { it.pending } >= MAX_PENDING) throw IllegalStateException("There are already $MAX_PENDING reminders. Cancel some first.")
        list + reminder
    }

    suspend fun update(reminder: Reminder) = edit { list -> list.map { if (it.id == reminder.id) reminder else it } }

    suspend fun remove(id: String): Reminder? {
        var removed: Reminder? = null
        edit { list -> list.filterNot { (it.id == id).also { match -> if (match) removed = it } } }
        return removed
    }

    private suspend fun edit(change: (List<Reminder>) -> List<Reminder>) = lock.withLock {
        val next = change(loadLocked())
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(serializer, next))
        if (!temp.renameTo(file)) { file.writeText(temp.readText()); temp.delete() }
        state.value = next
    }

    private fun loadLocked(): List<Reminder> {
        state.value?.let { return it }
        val read = runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList() }
            .getOrDefault(emptyList())
        // A one-time reminder that went off a day ago has done its job.
        val kept = read.filter { it.firedAt == null || clock() - it.firedAt < KEEP_FIRED_MS }
        state.value = kept
        return kept
    }

    companion object {
        const val MAX_PENDING = 200
        const val KEEP_FIRED_MS = 24L * 60 * 60 * 1000
    }
}
