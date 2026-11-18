package com.androidharness.app.ui.chat

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.flow.first

/** True inside the live (still streaming) part of an answer. */
internal val LocalStreamFade = compositionLocalOf { false }

private const val FADE_MS = 260L
private const val MERGE_MS = 50L

/**
 * Remembers when each run of text arrived in one live text element, so the
 * newest characters can fade in instead of popping. Text that shrinks (a
 * rewritten draft) resets the history; text that never grows never fades.
 */
internal class StreamFadeState {
    private class Run(val start: Int, val end: Int, val at: Long)
    private val runs = ArrayList<Run>()
    private var known = 0
    var frame by mutableLongStateOf(0L)

    fun observe(length: Int) {
        if (length < known) runs.clear()
        else if (length > known) {
            val now = SystemClock.uptimeMillis()
            val last = runs.lastOrNull()
            // Paced text grows every frame; runs that start within one step share
            // a timestamp, so a frame draws a handful of paths, not dozens.
            if (last != null && last.end == known && now - last.at < MERGE_MS) runs[runs.lastIndex] = Run(last.start, length, last.at)
            else runs += Run(known, length, now)
            runs.removeAll { now - it.at >= FADE_MS }
        }
        known = length
    }

    val fading: Boolean get() = runs.any { SystemClock.uptimeMillis() - it.at < FADE_MS }

    fun settle() { runs.clear() }

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, layout: TextLayoutResult) {
        val now = SystemClock.uptimeMillis()
        val textLength = layout.layoutInput.text.length
        for (run in runs) {
            val remaining = 1f - (now - run.at).toFloat() / FADE_MS
            if (remaining <= 0f) continue
            val end = minOf(run.end, textLength)
            if (run.start >= end) continue
            // Erase part of the new glyphs: they start faint and reach full ink as the run ages.
            scope.drawPath(layout.getPathForRange(run.start, end), Color.Black, alpha = remaining.coerceIn(0f, 1f), blendMode = BlendMode.DstOut)
        }
    }
}

/** Null when this text is not live or the user turned animations off. */
@Composable
internal fun rememberStreamFade(length: Int): StreamFadeState? {
    if (!LocalStreamFade.current) return null
    val context = LocalContext.current
    val motion = remember(context) {
        android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    if (!motion) return null
    val state = remember { StreamFadeState() }
    state.observe(length)
    LaunchedEffect(length) {
        while (state.fading) withFrameMillis { state.frame = it }
        state.settle()
        state.frame = SystemClock.uptimeMillis()
    }
    return state
}

internal fun Modifier.streamFade(state: StreamFadeState?, layout: () -> TextLayoutResult?): Modifier =
    if (state == null) this else this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            state.frame // read so each animation frame redraws
            layout()?.let { state.draw(this, it) }
        }

/**
 * Spreads bursty stream chunks over the frames between them. Providers often
 * send a few words at once and then nothing for a beat; showing each burst
 * as it lands makes the reply jump a line at a time. This reveals the text at
 * a steady pace that speeds up with the backlog, so it never trails the
 * stream by more than a fraction of a second. Finished text is returned as is.
 */
@Composable
internal fun rememberPacedText(target: String, live: Boolean): String {
    if (!live) return target
    val latest = androidx.compose.runtime.rememberUpdatedState(target)
    val shown = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // A rewritten draft (not an extension) restarts from what still matches.
    val current = shown.intValue.coerceAtMost(target.length)
    if (current != shown.intValue) shown.intValue = current
    LaunchedEffect(Unit) {
        var lastFrame = 0L
        while (true) {
            // Sleep until there is something to reveal; no frames tick while idle.
            androidx.compose.runtime.snapshotFlow { latest.value.length > shown.intValue }.first { it }
            lastFrame = 0L
            while (latest.value.length > shown.intValue) {
                withFrameMillis { now ->
                    val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1L, 64L)
                    lastFrame = now
                    val backlog = latest.value.length - shown.intValue
                    // Clear the backlog in about PACE_WINDOW_MS, never slower than MIN_CHARS_PER_SECOND.
                    val step = maxOf(backlog * dt / PACE_WINDOW_MS, MIN_CHARS_PER_SECOND * dt / 1000, 1L).toInt()
                    shown.intValue = minOf(latest.value.length, shown.intValue + step)
                }
            }
        }
    }
    var end = shown.intValue.coerceAtMost(target.length)
    // Never split an emoji or other surrogate pair.
    if (end in 1 until target.length && Character.isHighSurrogate(target[end - 1])) end++
    return target.substring(0, end)
}

private const val PACE_WINDOW_MS = 140L
private const val MIN_CHARS_PER_SECOND = 90L
