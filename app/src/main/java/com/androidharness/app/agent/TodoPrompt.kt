package com.androidharness.app.agent

/** Renders the live todo list for the system prompt. */
object TodoPrompt {
    fun format(items: List<TodoItem>): String {
        if (items.isEmpty()) return ""
        return buildString {
            append("Current task list (keep this updated via todo_write):\n")
            for (item in items) {
                append("- [").append(item.status.name.lowercase()).append("] ")
                    .append(item.content).append('\n')
            }
        }
    }
}
