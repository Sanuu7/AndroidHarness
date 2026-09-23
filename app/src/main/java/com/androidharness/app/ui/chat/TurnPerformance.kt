package com.androidharness.app.ui.chat

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.ui.common.formatDuration
import java.util.Locale

internal data class TurnSpeed(val tokensPerSecond: Double, val firstTokenMs: Long?, val generationOnly: Boolean)

internal fun turnTokensPerSecond(messages: List<ChatMessage>): TurnSpeed? {
    val measured = messages.filter {
        it.role == Role.ASSISTANT && it.toolCallId == null &&
            it.outputTokens > 0 && it.generationMs > 0
    }
    if (measured.isEmpty()) return null
    val hasStreamTiming = measured.all { it.firstTokenMs > 0 && it.streamMs > 0 }
    val duration = measured.sumOf { if (hasStreamTiming) it.streamMs else it.generationMs }
    if (duration <= 0) return null
    val firstTokenMs = measured.map { it.firstTokenMs }.takeIf { hasStreamTiming }?.average()?.toLong()
    return TurnSpeed(measured.sumOf { it.outputTokens.toLong() } * 1000.0 / duration, firstTokenMs, hasStreamTiming)
}

internal fun turnPerformanceLabel(durationMs: Long?, speed: TurnSpeed?): String =
    listOfNotNull(
        durationMs?.let { if (it <= 0) "0s" else formatDuration(it) },
        speed?.tokensPerSecond?.takeIf { it.isFinite() && it > 0 }?.let {
            String.format(Locale.US, "%.0f tk/s%s", it, if (speed.generationOnly) "" else " request")
        },
        speed?.firstTokenMs?.let { String.format(Locale.US, "first %.1fs", it / 1000.0) },
    ).joinToString(" · ")
