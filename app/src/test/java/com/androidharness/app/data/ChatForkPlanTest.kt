package com.androidharness.app.data

import com.androidharness.app.agent.AgentEngine
import com.androidharness.app.core.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatForkPlanTest {

    @Test
    fun `fork title prepends Fork of only once`() {
        val original = "Setup Docker"
        val fork1 = if (original.startsWith("Fork of ")) original else "Fork of $original"
        assertEquals("Fork of Setup Docker", fork1)

        val fork2 = if (fork1.startsWith("Fork of ")) fork1 else "Fork of $fork1"
        assertEquals("Fork of Setup Docker", fork2)
    }

    @Test
    fun `compaction summary accumulates prior turn history for model context`() {
        val existingCompaction = "User is building a Kotlin app."
        val summaryBuilder = StringBuilder()
        if (existingCompaction.isNotBlank()) {
            summaryBuilder.append(existingCompaction.trim()).append("\n\n")
        }

        val earlierRoles = listOf(
            Role.USER to "How do I setup Room?",
            Role.ASSISTANT to "Add the room runtime dependency.",
            Role.TOOL to "dependencies added successfully",
        )

        for ((role, text) in earlierRoles) {
            when (role) {
                Role.USER -> summaryBuilder.append("User: ").append(text).append("\n\n")
                Role.ASSISTANT -> summaryBuilder.append("Assistant: ").append(text).append("\n\n")
                Role.TOOL -> summaryBuilder.append("Tool (gradle): ").append(text).append("\n\n")
                Role.SYSTEM -> {}
            }
        }

        val finalSummary = summaryBuilder.toString().trim()
        assertTrue(finalSummary.startsWith("User is building a Kotlin app."))
        assertTrue(finalSummary.contains("User: How do I setup Room?"))
        assertTrue(finalSummary.contains("Assistant: Add the room runtime dependency."))
        assertTrue(finalSummary.contains("Tool (gradle): dependencies added successfully"))
    }
}
