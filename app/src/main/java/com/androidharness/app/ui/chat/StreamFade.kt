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
import androidx.compose.ui.layout.layout
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
 * as it lands makes the reply jump a line at a time. This reveals whole words
 * at a steady pace that speeds up with the backlog, so it never trails the
 * stream by more than a fraction of a second. Updates are capped near 30 per
 * second: every reveal re-parses and re-lays the live markdown, and the fade
 * already smooths the gaps. Finished text is returned as is.
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
        var lastReveal = 0L
        fun canReveal() = wordEnd(latest.value, shown.intValue, shown.intValue + 1) > shown.intValue
        while (true) {
            // Sleep until a whole word is ready; no frames tick while idle.
            androidx.compose.runtime.snapshotFlow { canReveal() }.first { it }
            while (canReveal()) {
                withFrameMillis { now ->
                    val since = now - lastReveal
                    if (since < REVEAL_INTERVAL_MS) return@withFrameMillis
                    // After a pause, step as if one interval passed instead of dumping the backlog.
                    val dt = if (since > REVEAL_INTERVAL_MS * 4) REVEAL_INTERVAL_MS else since
                    lastReveal = now
                    val text = latest.value
                    val backlog = text.length - shown.intValue
                    // Clear the backlog in about PACE_WINDOW_MS, never slower than MIN_CHARS_PER_SECOND.
                    val step = maxOf(backlog * dt / PACE_WINDOW_MS, MIN_CHARS_PER_SECOND * dt / 1000, 1L).toInt()
                    shown.intValue = maxOf(shown.intValue, wordEnd(text, shown.intValue, shown.intValue + step))
                }
            }
        }
    }
    var end = shown.intValue.coerceAtMost(target.length)
    // Never split an emoji or other surrogate pair.
    if (end in 1 until target.length && Character.isHighSurrogate(target[end - 1])) end++
    return target.substring(0, end)
}

/**
 * Where a reveal that wants to reach [want] should stop so no word is cut in
 * half. A word whose end has arrived nearby is finished; a word still
 * arriving is held back. Long runs without spaces (URLs, CJK text) reveal by
 * character so they never stall.
 */
internal fun wordEnd(text: String, from: Int, want: Int): Int {
    val target = want.coerceIn(from, text.length)
    if (target == 0) return 0
    if (target < text.length && (text[target].isWhitespace() || text[target - 1].isWhitespace())) return target
    if (target == text.length && text[target - 1].isWhitespace()) return target
    var end = target
    while (end < text.length && end - target < MAX_WORD && !text[end].isWhitespace()) end++
    if (end < text.length && text[end].isWhitespace()) return end
    if (end - target >= MAX_WORD) return target
    // The word is still arriving: stop before it, unless it is already long.
    var start = target
    while (start > from && !text[start - 1].isWhitespace()) start--
    return if (target - start >= MAX_WORD) target else maxOf(from, start)
}

/**
 * Lets a live answer grow but never shrink while it streams. Half-typed
 * markdown can reflow a line shorter for a frame; the list then had less
 * content below the anchored question than the screen, pulled back, and
 * snapped forward again, which read as the whole chat shaking. Growing is
 * harmless because the extra height lands below the fold. The hold resets
 * when streaming ends or the draft is rewritten.
 */
@Composable
internal fun Modifier.keepLiveHeight(live: Boolean, textLength: Int): Modifier {
    val held = remember { IntArray(2) } // [height, text length]
    if (!live || textLength < held[1]) held[0] = 0
    held[1] = textLength
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = if (live) maxOf(placeable.height, held[0]).coerceAtMost(constraints.maxHeight) else placeable.height
        if (live) held[0] = height
        layout(placeable.width, height) { placeable.placeRelative(0, 0) }
    }
}

private const val PACE_WINDOW_MS = 140L
private const val MIN_CHARS_PER_SECOND = 90L
private const val REVEAL_INTERVAL_MS = 33L
private const val MAX_WORD = 24
