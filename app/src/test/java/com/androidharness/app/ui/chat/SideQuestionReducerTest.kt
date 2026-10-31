package com.androidharness.app.ui.chat

import com.androidharness.app.agent.AgentEvent
import com.androidharness.app.agent.SideEvent
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SideQuestionReducerTest {
    private val start = SideQuestionUi(question = "Why Room?", running = true, startedAt = 1)
    private fun reduce(vararg events: SideEvent) = events.fold(start) { ui, e -> SideQuestionReducer.reduce(ui, e, now = 9) }

    @Test
    fun `streamed text is the answer until a round commits`() {
        val ui = reduce(SideEvent.Text("Because "), SideEvent.Text("it is fast"))
        assertEquals("Because it is fast", ui.answer)
        val committed = SideQuestionReducer.reduce(ui, SideEvent.AssistantCommitted(ChatMessage(role = Role.ASSISTANT, text = "Because it is fast")))
        assertEquals("", committed.streamingText)
        assertEquals("Because it is fast", committed.answer)
    }

    @Test
    fun `tool rounds track running calls and only the final round answers`() {
        val call = ToolCallData("c1", "read_file", "{}")
        val ui = reduce(
            SideEvent.AssistantCommitted(ChatMessage(role = Role.ASSISTANT, text = "Checking", toolCalls = listOf(call))),
            SideEvent.ToolStarted(call),
        )
        assertEquals(listOf(call), ui.runningCalls)
        assertEquals("", ui.answer)
        val done = reduce(
            SideEvent.AssistantCommitted(ChatMessage(role = Role.ASSISTANT, text = "Checking", toolCalls = listOf(call))),
            SideEvent.ToolStarted(call),
            SideEvent.ToolFinished(ChatMessage(role = Role.TOOL, text = "file", toolCallId = "c1", toolName = "read_file")),
            SideEvent.AssistantCommitted(ChatMessage(role = Role.ASSISTANT, text = "It uses Room")),
            SideEvent.Finished(),
        )
        assertTrue(done.runningCalls.isEmpty())
        assertEquals("It uses Room", done.answer)
        assertEquals(1, done.toolCount)
        assertFalse(done.running)
        assertEquals(9, done.finishedAt)
    }

    @Test
    fun `usage becomes cache rows for the shared footer`() {
        val ui = reduce(SideEvent.Usage(AgentEvent.Usage(1_000, 50, 900, 0, "m", "P", cacheReported = true)))
        val row = ui.usage.single()
        assertEquals(1_000, row.inputTokens)
        assertEquals(900, row.cachedTokens)
        assertTrue(validCacheUsage(row))
        assertEquals("Cache hit · 90%", cacheUsageSummary(ui.usage).label)
    }

    @Test
    fun `stopping keeps the partial answer`() {
        val stopped = SideQuestionReducer.finish(reduce(SideEvent.Text("Half an ans")), now = 5).copy(stopped = true)
        assertFalse(stopped.running)
        assertEquals("Half an ans", stopped.answer)
        assertEquals("", stopped.streamingText)
    }

    @Test
    fun `failures end the run with the message`() {
        val failed = reduce(SideEvent.Failed("HTTP 429"))
        assertFalse(failed.running)
        assertEquals("HTTP 429", failed.error)
    }
}
