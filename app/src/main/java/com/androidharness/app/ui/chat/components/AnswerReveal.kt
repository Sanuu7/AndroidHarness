package com.androidharness.app.ui.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis

/**
 * Types a confirmed Chat answer into its bubble instead of dropping it in all
 * at once. The text is already complete, so this only paces the reveal: about
 * 1,800 characters a second, never longer than 1.4 seconds, ending on whole
 * words. [onDone] runs once the full text shows.
 */
@Composable
internal fun rememberAnswerReveal(text: String, reveal: Boolean, onDone: () -> Unit): String {
    var shown by remember { mutableIntStateOf(if (reveal) 0 else text.length) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(reveal, text) {
        if (!reveal || shown >= text.length) {
            shown = text.length
            if (reveal) done()
            return@LaunchedEffect
        }
        val from = shown
        val durationMs = ((text.length - from) / 1.8).coerceIn(300.0, 1_400.0)
        val start = withFrameMillis { it }
        while (shown < text.length) {
            withFrameMillis { now ->
                val progress = ((now - start) / durationMs).coerceAtMost(1.0)
                shown = maxOf(shown, from + ((text.length - from) * progress).toInt())
            }
        }
        done()
    }
    if (shown >= text.length) return text
    val cut = text.lastIndexOf(' ', shown).takeIf { it > 0 } ?: shown
    return text.substring(0, cut)
}
