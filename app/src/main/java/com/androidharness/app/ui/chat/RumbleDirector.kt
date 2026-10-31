package com.androidharness.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.RumbleCue
import com.androidharness.app.ui.common.RumbleFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Turns conversation state into AH Rumble cues that are not tied to the Chat activity row: the
 * end of a turn, a prompt waiting for the user, and, in Code, each tool starting and finishing.
 * Whatever is already true when the screen opens stays silent.
 *
 * While [flow] is set (Chat is streaming prose or building a visual) it breathes a slow rumble, one
 * [breathe] call at a time, and only while the screen is on show. [breathe] returns the wait to the next.
 */
@Composable
internal fun RumbleDirector(
    busy: Boolean,
    failed: Boolean,
    waitingPrompt: Any?,
    runningCalls: List<ToolCallData>,
    codeTools: Boolean,
    play: (RumbleCue) -> Unit,
    flow: RumbleFlow? = null,
    breathe: (RumbleFlow) -> Long = { 0L },
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(flow) {
        if (flow == null) return@LaunchedEffect
        // Let the cue that marks the start of this phase finish before the first breath.
        delay(FLOW_LEAD_IN_MS)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) delay(breathe(flow).coerceAtLeast(FLOW_MIN_WAIT_MS))
        }
    }
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

private const val FLOW_LEAD_IN_MS = 500L
private const val FLOW_MIN_WAIT_MS = 400L
