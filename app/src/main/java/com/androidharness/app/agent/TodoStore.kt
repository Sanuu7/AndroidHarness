package com.androidharness.app.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

@Serializable
data class TodoItem(
    val content: String,
    val status: Status = Status.PENDING,
) {
    @Serializable
    enum class Status { PENDING, IN_PROGRESS, COMPLETED }
}

/**
 * Session-scoped task list the agent maintains via the todo_write tool.
 *
 * The store is app-global but OWNED: [beginRun] claims it for the session
 * starting a run, so a finished list never bleeds into the next prompt
 * (a new run in the same session starts empty) or into another chat
 * (the UI only shows todos whose owner is the open session).
 */
class TodoStore {
    private val _todos = MutableStateFlow<List<TodoItem>>(emptyList())
    val todos: StateFlow<List<TodoItem>> = _todos

    private val _owner = MutableStateFlow<String?>(null)
    val owner: StateFlow<String?> = _owner

    /** Run start claims the store; another session's stale list is dropped. */
    fun beginRun(sessionId: String) {
        if (_owner.value != sessionId) {
            _owner.value = sessionId
            _todos.value = emptyList()
        }
    }

    fun setAll(items: List<TodoItem>) {
        _todos.update { items }
    }
}
