package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextHygieneTest {

    @Test
    fun `recent tool results stay intact`() {
        val history = (1..3).map { i ->
            ChatMessage(role = Role.TOOL, text = "full-$i-" + "x".repeat(2000), toolCallId = "c$i", toolName = "shell")
        }
        val out = ContextHygiene.shrinkToolResults(history)
        out.forEach { assertFalse(it.text.contains("truncated")) }
        assertEquals(history[0].text, out[0].text)
    }

    @Test
    fun `stale tool dumps are truncated`() {
        val stale = ChatMessage(
            role = Role.TOOL,
            text = "START-" + "y".repeat(20_000) + "-END",
            toolCallId = "old",
            toolName = "shell",
        )
        val recent = (1..ContextHygiene.RECENT_FULL_TOOLS).map { i ->
            ChatMessage(role = Role.TOOL, text = "keep-$i", toolCallId = "n$i", toolName = "grep")
        }
        val user = ChatMessage(role = Role.USER, text = "go")
        val out = ContextHygiene.shrinkToolResults(listOf(user, stale) + recent)
        assertTrue(out[1].text.contains("truncated"))
        assertTrue(out[1].text.startsWith("START-"))
        assertTrue(out[1].text.endsWith("-END"))
        assertTrue(out[1].text.length < stale.text.length)
        assertEquals("keep-1", out[2].text)
    }

    @Test
    fun `non-tool messages are never touched`() {
        val big = ChatMessage(role = Role.ASSISTANT, text = "a".repeat(50_000))
        val out = ContextHygiene.shrinkToolResults(listOf(big))
        assertEquals(big.text, out[0].text)
    }

    @Test
    fun `compaction summary is a system message not a user message`() {
        val msg = ContextHygiene.summaryMessage("goal was X")
        assertEquals(Role.SYSTEM, msg.role)
        assertTrue(msg.text.contains("goal was X"))
        assertTrue(msg.text.contains(AgentEngine.COMPACTION_PREFIX))
        assertFalse(msg.text.startsWith("goal was X"))
    }

    @Test
    fun `forModel starts at the last compaction summary`() {
        val history = listOf(
            ChatMessage(role = Role.USER, text = "old goal"),
            ChatMessage(role = Role.TOOL, text = "y".repeat(20_000), toolCallId = "old", toolName = "shell"),
            ContextHygiene.summaryMessage("kept goal"),
            ChatMessage(role = Role.USER, text = "continue"),
            ChatMessage(role = Role.TOOL, text = "fresh", toolCallId = "n1", toolName = "grep"),
        )
        val out = ContextHygiene.forModel(history)
        assertEquals(Role.SYSTEM, out.first().role)
        assertTrue(out.first().text.contains("kept goal"))
        assertTrue(out.none { it.text == "old goal" })
        assertEquals("continue", out[1].text)
        assertEquals("fresh", out[2].text)
    }
}
