package com.androidharness.app.ui.chat

import com.androidharness.app.agent.SideQuestion
import com.androidharness.app.agent.SideTurn
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SideThreadUiTest {
    private fun answered(question: String, answer: String) = SideQuestionUi(
        question = question, messages = listOf(ChatMessage(role = Role.ASSISTANT, text = answer)),
    )

    @Test
    fun `history replays finished answers and skips running or failed turns`() {
        val thread = SideThreadUi("s", turns = listOf(
            answered("Why Room?", "It caches."),
            SideQuestionUi(question = "Broken?", error = "timeout"),
            answered("Migrations?", "Versioned."),
            SideQuestionUi(question = "Now?", running = true, streamingText = "Part"),
        ))
        assertEquals(listOf(SideTurn("Why Room?", "It caches."), SideTurn("Migrations?", "Versioned.")), thread.history())
        assertTrue(thread.running)
    }

    @Test
    fun `history is bounded like the request`() {
        val thread = SideThreadUi("s", turns = (1..12).map { answered("q$it", "a$it") })
        assertEquals(SideQuestion.MAX_THREAD_TURNS, thread.history().size)
        assertEquals("q12", thread.history().last().question)
    }

    @Test
    fun `updating the current turn leaves earlier turns alone`() {
        val first = answered("Why Room?", "It caches.")
        val thread = SideThreadUi("s", turns = listOf(first, SideQuestionUi(question = "Next?", running = true)))
        val updated = thread.withCurrent(thread.current!!.copy(streamingText = "Because"))
        assertEquals(first, updated.turns.first())
        assertEquals("Because", updated.current?.answer)
    }
}
