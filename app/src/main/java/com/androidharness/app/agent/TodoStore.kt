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

/** Session-scoped task list the agent maintains via the todo_write tool. */
class TodoStore {
    private val _todos = MutableStateFlow<List<TodoItem>>(emptyList())
    val todos: StateFlow<List<TodoItem>> = _todos

    fun setAll(items: List<TodoItem>) {
        _todos.update { items }
    }
}
