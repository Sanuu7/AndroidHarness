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
        val speed = turnTokensPerSecond(messages)!!
        assertEquals(40.0, speed.tokensPerSecond, 0.001)
        assertEquals(false, speed.generationOnly)
    }

    @Test
    fun `stream speed excludes first-token latency and tool time`() {
        val speed = turnTokensPerSecond(listOf(
            ChatMessage(Role.ASSISTANT, outputTokens = 400, generationMs = 5_000, firstTokenMs = 3_000, streamMs = 1_000),
            ChatMessage(Role.TOOL),
            ChatMessage(Role.ASSISTANT, outputTokens = 200, generationMs = 3_000, firstTokenMs = 1_000, streamMs = 500),
        ))!!
        assertEquals(400.0, speed.tokensPerSecond, 0.001)
        assertEquals(2_000L, speed.firstTokenMs)
        assertEquals(true, speed.generationOnly)
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
        assertEquals("2m 13s · 52 tk/s request", turnPerformanceLabel(133_000, TurnSpeed(52.1, null, false)))
        assertEquals("0s · 52 tk/s · first 0.5s", turnPerformanceLabel(0, TurnSpeed(52.0, 500, true)))
        assertEquals("", turnPerformanceLabel(null, TurnSpeed(Double.NaN, null, true)))
    }
}
