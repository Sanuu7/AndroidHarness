package com.androidharness.app.ui.chat

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.ui.common.formatDuration
import java.util.Locale

/** Effective model throughput includes request latency, but excludes tools and retry waits. */
internal fun turnTokensPerSecond(messages: List<ChatMessage>): Double? {
    val measured = messages.filter {
        it.role == Role.ASSISTANT && it.toolCallId == null &&
            it.outputTokens > 0 && it.generationMs > 0
    }
    val duration = measured.sumOf { it.generationMs }
    if (duration <= 0) return null
    return measured.sumOf { it.outputTokens.toLong() } * 1000.0 / duration
}

internal fun turnPerformanceLabel(durationMs: Long?, tokensPerSecond: Double?): String =
    listOfNotNull(
        durationMs?.let { if (it <= 0) "0s" else formatDuration(it) },
        tokensPerSecond?.takeIf { it.isFinite() && it > 0 }?.let {
            String.format(Locale.US, "%.0f tk/s", it)
        },
    ).joinToString(" · ")
