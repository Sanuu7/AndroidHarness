package com.androidharness.app.ui.chat.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.androidharness.app.R
import com.androidharness.app.agent.ThinkingTiming
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.chatThinkingTotals
import com.androidharness.app.core.compactThinkingTokenCount
import kotlinx.coroutines.delay

/** Only this small header label ticks; the reasoning transcript does not recompose with the clock. */
@Composable
internal fun ChatThinkingMetrics(messages: List<ChatMessage>, streamingThinking: String, timing: ThinkingTiming?,
    reasoningTokens: Int?, live: Boolean, turnStartedAt: Long? = null, streamId: String = "live") {
    var now by remember(timing) { mutableLongStateOf(ThinkingTiming.now()) }
    LaunchedEffect(live, timing, turnStartedAt) {
        if (live && (timing?.startedAt != null || turnStartedAt != null)) while (true) {
            now = ThinkingTiming.now()
            delay(200)
        }
    }
    val totals = remember(messages, streamingThinking, reasoningTokens) {
        chatThinkingTotals(messages, streamingThinking, null, reasoningTokens, 0)
    }
    // The visible total only grows during a turn: the estimate-to-reported swap
    // and the commit hand-off must not make the number jump backwards.
    var tokenFloor by remember(streamId, live) { mutableLongStateOf(0L) }
    val tokensShown = totals.tokens.coerceAtLeast(tokenFloor)
    SideEffect { if (live && tokensShown > tokenFloor) tokenFloor = tokensShown }
    // One clock for the whole live turn, thinking and tool calls alike; the
    // summed reasoning spans only cover turns without a running clock.
    val elapsedMs = if (live && turnStartedAt != null) (now - turnStartedAt).coerceAtLeast(0)
        else totals.milliseconds + (timing?.durationMs(now) ?: 0)
    val duration = com.androidharness.app.ui.common.formatDuration(elapsedMs).ifEmpty { "0s" }
    val count = compactThinkingTokenCount(tokensShown)
    val tokens = stringResource(R.string.chat_thinking_tokens_compact, if (totals.estimated) "~$count" else count)
    val description = duration + " · " + stringResource(
        if (totals.estimated) R.string.ui_thinking_estimated_tokens else R.string.ui_thinking_tokens, tokensShown)
    Text("$duration · $tokens", modifier = Modifier.semantics { contentDescription = description },
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
