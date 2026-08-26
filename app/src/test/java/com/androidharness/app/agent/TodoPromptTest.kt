package com.androidharness.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoPromptTest {

    @Test
    fun `empty list produces nothing`() {
        assertEquals("", TodoPrompt.format(emptyList()))
    }

    @Test
    fun `live todos are injected with status`() {
        val text = TodoPrompt.format(
            listOf(
                TodoItem("Fix ignore walk", TodoItem.Status.IN_PROGRESS),
                TodoItem("Install APK", TodoItem.Status.PENDING),
            ),
        )
        assertTrue(text.contains("Current task list"))
        assertTrue(text.contains("[in_progress] Fix ignore walk"))
        assertTrue(text.contains("[pending] Install APK"))
    }
}
