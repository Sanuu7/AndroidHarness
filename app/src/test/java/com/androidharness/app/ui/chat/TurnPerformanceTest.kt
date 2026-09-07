package com.androidharness.app.ui.chat

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnPerformanceTest {
    @Test
    fun `speed uses measured model time rather than tool time or subagent usage`() {
        val messages = listOf(
            ChatMessage(Role.ASSISTANT, outputTokens = 100, generationMs = 1_000, createdAt = 1_000),
            ChatMessage(Role.TOOL, createdAt = 120_000),
            ChatMessage(Role.ASSISTANT, outputTokens = 300, generationMs = 9_000, createdAt = 130_000),
            ChatMessage(Role.ASSISTANT, toolCallId = "child", outputTokens = 9000, generationMs = 100),
        )
        assertEquals(40.0, turnTokensPerSecond(messages)!!, 0.001)
    }

    @Test
    fun `missing token usage and old messages do not invent a speed`() {
        assertNull(turnTokensPerSecond(listOf(
            ChatMessage(Role.ASSISTANT, generationMs = 10_000),
            ChatMessage(Role.ASSISTANT, outputTokens = 520),
            ChatMessage(Role.ASSISTANT),
        )))
        assertEquals("2m 13s", turnPerformanceLabel(133_000, null))
    }

    @Test
    fun `footer formats duration beside measured speed`() {
        assertEquals("2m 13s · 52 tk/s", turnPerformanceLabel(133_000, 52.1))
        assertEquals("0s · 52 tk/s", turnPerformanceLabel(0, 52.0))
        assertEquals("", turnPerformanceLabel(null, Double.NaN))
    }
}
