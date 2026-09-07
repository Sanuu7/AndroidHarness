package com.androidharness.app.ui.chat

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.ui.chat.components.activeToolIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnActivityTest {
    @Test
    fun `completed groups stay idle while a later group runs`() {
        val first = ToolCallData("one", "grep", "{}")
        val second = ToolCallData("two", "read_file", "{}")
        val third = ToolCallData("three", "shell", "{}")
        val results = mapOf(
            "one" to ChatMessage(Role.TOOL),
            "two" to ChatMessage(Role.TOOL, isError = true),
        )
        // Even a stale running ID cannot override an available result.
        val running = setOf("one", "two", "three")
        assertTrue(activeToolIds(listOf(first, second), results, running).isEmpty())
        assertEquals(setOf("three"), activeToolIds(listOf(first, second, third), results, running))
    }

    @Test
    fun `activity keeps narration reasoning and calls in order but excludes final answer`() {
        val first = ChatMessage(Role.ASSISTANT, text = "I will inspect", thinking = "Plan",
            turnId = "a", toolCalls = listOf(ToolCallData("one", "grep", "{}")))
        val progress = ChatMessage(Role.ASSISTANT, text = "Found the problem", turnId = "a")
        val final = ChatMessage(Role.ASSISTANT, text = "Fixed", thinking = "Checked", turnId = "a")
        val activity = completedTurnActivities(listOf(
            ChatMessage(Role.USER, text = "Fix it", turnId = "a"),
            first, ChatMessage(Role.TOOL, text = "result", turnId = "a"), progress,
            ChatMessage(Role.ASSISTANT, text = "Child", turnId = "a", toolCallId = "child"),
            final,
            ChatMessage(Role.ASSISTANT, text = "Other turn", turnId = "b"),
        ))
        assertEquals(listOf(first, progress, final.copy(text = "")), activity["a"])
        assertTrue(activity["b"].orEmpty().isEmpty())
    }

    @Test
    fun `final answer without activity needs no disclosure`() {
        assertTrue(completedTurnActivities(listOf(
            ChatMessage(Role.ASSISTANT, text = "Hello", turnId = "a"),
        ))["a"].orEmpty().isEmpty())
    }
}
