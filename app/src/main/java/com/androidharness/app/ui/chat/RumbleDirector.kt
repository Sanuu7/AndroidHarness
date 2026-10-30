package com.androidharness.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.RumbleCue

/**
 * Turns conversation state into AH Rumble cues that are not tied to the Chat activity row: the
 * end of a turn, a prompt waiting for the user, and, in Code, each tool starting and finishing.
 * Whatever is already true when the screen opens stays silent.
 */
@Composable
internal fun RumbleDirector(
    busy: Boolean,
    failed: Boolean,
    waitingPrompt: Any?,
    runningCalls: List<ToolCallData>,
    codeTools: Boolean,
    play: (RumbleCue) -> Unit,
) {
    var wasBusy by remember { mutableStateOf(busy) }
    LaunchedEffect(busy) {
        if (wasBusy && !busy && !failed) play(RumbleCue.ANSWER_DONE)
        wasBusy = busy
    }
    var seenPrompt by remember { mutableStateOf(waitingPrompt) }
    LaunchedEffect(waitingPrompt) {
        if (waitingPrompt !== seenPrompt) {
            seenPrompt = waitingPrompt
            if (waitingPrompt != null) play(RumbleCue.APPROVAL)
        }
    }
    val callKey = runningCalls.joinToString(",") { it.id }
    var previous by remember { mutableStateOf(callKey) }
    LaunchedEffect(callKey, codeTools) {
        val before = previous.split(',').filter { it.isNotEmpty() }.toSet()
        val now = callKey.split(',').filter { it.isNotEmpty() }.toSet()
        previous = callKey
        if (!codeTools) return@LaunchedEffect
        if ((now - before).isNotEmpty()) play(RumbleCue.TOOL_START)
        else if ((before - now).isNotEmpty()) play(RumbleCue.TOOL_DONE)
    }
}
