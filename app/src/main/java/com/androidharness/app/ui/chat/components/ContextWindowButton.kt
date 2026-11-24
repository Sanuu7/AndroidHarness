package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.agent.AgentEngine
import com.androidharness.app.core.Role
import com.androidharness.app.ui.chat.ChatUiState
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.common.formatTokenCount

/** A live reading of the working context, drawn as a ring around the composer's + button. */
@Immutable
internal class ContextMeter(
    val fraction: Float,
    val known: Boolean,
    val color: Color,
    val track: Color,
    /** "250K of 1M used", or the pending text before the first estimate. */
    val summary: String,
)

/** The ring, the summary and the popover that the + button shows for coding conversations. */
internal class ContextRing(
    val meter: ContextMeter,
    val popover: @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit,
)

@Composable
private fun contextLimit(state: ChatUiState): Int {
    // Local runs can use a smaller device-configured window than the global setting.
    val localRun = state.activeProvider?.let { provider ->
        com.androidharness.app.local.LocalModelCatalog.isLocal(provider.id) &&
            state.taskControl.provider?.id == provider.id &&
            state.taskControl.provider.model == state.effectiveModel
    } == true
    return (if (localRun) state.taskControl.maxContext else codeContextLimit(state)).coerceAtLeast(1)
}

@Composable
internal fun rememberContextMeter(state: ChatUiState): ContextMeter {
    val used = state.contextUsed.coerceAtLeast(0)
    val limit = contextLimit(state)
    val known = state.estimate != null || state.usage.lastInput > 0
    val fraction = (used.toFloat() / limit).coerceIn(0f, 1f)
    val scheme = MaterialTheme.colorScheme
    val color = when {
        !known -> scheme.onSurfaceVariant
        fraction >= .9f -> scheme.error
        fraction >= .75f -> scheme.tertiary
        else -> scheme.primary
    }
    val summary = if (known) stringResource(R.string.context_window_used, formatTokenCount(used), formatTokenCount(limit))
        else stringResource(R.string.context_window_pending)
    return ContextMeter(fraction, known, color, scheme.outlineVariant, summary)
}

@Composable
internal fun rememberContextRing(state: ChatUiState, onOpenLimits: () -> Unit, onCompact: () -> Unit): ContextRing? {
    if (state.conversation.isChat) return null
    val meter = rememberContextMeter(state)
    val latestState by rememberUpdatedState(state)
    val latestLimits by rememberUpdatedState(onOpenLimits)
    val latestCompact by rememberUpdatedState(onCompact)
    val popover = remember {
        @Composable { expanded: Boolean, onDismiss: () -> Unit ->
            ContextWindowPopover(latestState, expanded, onDismiss, latestLimits, latestCompact)
        }
    }
    return ContextRing(meter, popover)
}

/** Draws the context track and progress arc inside this scope's bounds. */
internal fun DrawScope.drawContextRing(meter: ContextMeter, strokeWidth: Dp, fraction: Float = meter.fraction) {
    val stroke = strokeWidth.toPx()
    val inset = stroke / 2
    val bounds = Size(size.width - stroke, size.height - stroke)
    drawArc(meter.track, 0f, 360f, false, Offset(inset, inset), bounds, style = Stroke(stroke))
    if (meter.known && fraction > 0f) drawArc(meter.color, -90f, 360f * fraction, false,
        Offset(inset, inset), bounds, style = Stroke(stroke, cap = StrokeCap.Round))
}

/** Small ring for menu rows. */
@Composable
internal fun ContextRingGlyph(meter: ContextMeter, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        drawContextRing(meter, 2.dp)
        drawCircle(meter.color, radius = 1.5.dp.toPx())
    }
}

/** Live context and cache details, with shortcuts to the limits dialog and /compact. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContextWindowPopover(
    state: ChatUiState,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenLimits: () -> Unit,
    onCompact: () -> Unit,
) {
    val used = state.contextUsed.coerceAtLeast(0)
    val limit = contextLimit(state)
    val known = state.estimate != null || state.usage.lastInput > 0
    val fraction = (used.toFloat() / limit).coerceIn(0f, 1f)
    val usage = stringResource(R.string.context_window_used, formatTokenCount(used), formatTokenCount(limit))
    val remaining = stringResource(R.string.context_window_remaining,
        formatTokenCount((limit - used).coerceAtLeast(0)))
    val cacheHitRate = when {
        state.usage.requests == 0L && state.busy -> stringResource(R.string.ui_calculating)
        state.usage.totalInput > 0 -> "%.1f%%".format(state.usage.avgCacheHitRate * 100)
        else -> "0.0%"
    }
    val pending = stringResource(R.string.context_window_pending)
    val scheme = MaterialTheme.colorScheme
    val summarized = !state.taskControl.summaryOverride.isNullOrBlank() || state.messages.any {
        it.role == Role.SYSTEM && it.text.startsWith(AgentEngine.COMPACTION_PREFIX)
    }
    val canCompact = !state.busy && state.compactionNote == null && state.sessionId != null &&
        state.messages.isNotEmpty() && state.conversationReady && !state.conversationSaving &&
        state.modelSelectionReady && !state.modelSelectionBusy && state.modelSelectionError == null &&
        state.activeProvider != null && !state.isLoadingMessages && state.attachmentImports.isEmpty()

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss,
        modifier = Modifier.width(280.dp).testTag("context-window-popover")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ui_context_and_usage), style = MaterialTheme.typography.titleSmall)
            if (known) {
                Text(usage, style = MaterialTheme.typography.titleMedium)
                ThinLinearProgress(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(remaining,
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Text(when {
                !known -> pending
                state.estimate != null -> stringResource(R.string.context_window_estimate)
                else -> stringResource(R.string.context_window_last_request)
            }, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (summarized) Text(stringResource(R.string.context_window_summarized),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            HorizontalDivider()
            listOf(
                stringResource(R.string.ui_cached_tokens_hits) to formatTokenCount(state.usage.totalCached),
                stringResource(R.string.ui_overall_cache_hit_rate) to cacheHitRate,
            ).forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(stringResource(R.string.ui_hit_rate_updates_after_each_completed_turn),
                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            HorizontalDivider()
            Text(stringResource(if (state.busy) R.string.context_window_busy else R.string.context_window_compact_help),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onDismiss(); onOpenLimits() }) {
                    Text(stringResource(R.string.ui_context_limits_extra71))
                }
                TextButton(onClick = { onDismiss(); onCompact() }, enabled = canCompact,
                    modifier = Modifier.testTag("context-window-compact")) {
                    Text(stringResource(R.string.context_window_compact))
                }
            }
        }
    }
}

/** The window a Code run budgets against for the selected model, as RunManager works it out. */
internal fun codeContextLimit(state: ChatUiState): Int {
    val provider = state.activeProvider ?: return state.maxContextTokens
    val model = state.effectiveModel ?: return state.maxContextTokens
    // A self-hosted server's own window caps the run (RunManager probes it), so the ring shows it too.
    val serverLimit = com.androidharness.app.llm.ServerContext.keyFor(provider)?.let { state.serverContextTokens[it] }
    return com.androidharness.app.agent.ChatContextPolicy.codeLimit(
        state.maxContextTokens, serverLimit,
        state.catalogs[provider.id]?.firstOrNull { it.id == model }?.contextTokens,
        state.learnedContextLimits[com.androidharness.app.agent.TextFileWrites.modelKey(provider.id, model)],
    )
}
