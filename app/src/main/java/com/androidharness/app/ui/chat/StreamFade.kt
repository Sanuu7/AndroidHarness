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

/** True inside the live (still streaming) part of an answer. */
internal val LocalStreamFade = compositionLocalOf { false }

private const val FADE_MS = 260L

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
        else if (length > known) runs += Run(known, length, SystemClock.uptimeMillis())
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
