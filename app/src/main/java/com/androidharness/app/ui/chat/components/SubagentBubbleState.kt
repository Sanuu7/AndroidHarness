package com.androidharness.app.ui.chat.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import com.androidharness.app.agent.SubagentRun

/** Only work observed running in this screen can produce a completion popup. */
internal data class SubagentBubbleLifecycle(
    val tracked: Set<String> = emptySet(),
    val active: Set<String> = emptySet(),
    val finishedAt: Long? = null,
) {
    fun update(runs: List<SubagentRun>, now: Long): SubagentBubbleLifecycle {
        val running = runs.filter { it.status.active }
        val turns = running.map { it.turnId }.toSet()
        val ids = (tracked + runs.filter { it.status.active || it.turnId in turns }.map { it.id })
            .intersect(runs.map { it.id }.toSet())
        val current = running.map { it.id }.toSet()
        return SubagentBubbleLifecycle(ids, current, when {
            current.isNotEmpty() -> null
            active.isNotEmpty() -> now
            else -> finishedAt
        })
    }

    fun visible(now: Long, reading: Boolean): Boolean = active.isNotEmpty() ||
        (tracked.isNotEmpty() && (reading || finishedAt?.let { now - it < COMPLETION_MS } == true))

    companion object { const val COMPLETION_MS = 2_000L }
}

internal data class SubagentBubblePosition(val x: Float = 1f, val y: Float = 1f) {
    fun normalized() = SubagentBubblePosition(x.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f,
        y.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f)

    fun moved(delta: Offset, bounds: IntRect): SubagentBubblePosition = SubagentBubblePosition(
        x + if (bounds.width > 0) delta.x / bounds.width else 0f,
        y + if (bounds.height > 0) delta.y / bounds.height else 0f,
    ).normalized()
}
