package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.agent.AgentEngine
import com.androidharness.app.core.Role
import com.androidharness.app.ui.chat.ChatUiState
import com.androidharness.app.ui.common.ExpressiveIconButton
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.common.formatTokenCount

/** A compact, live view of the working context, independent of the composer's draft. */
@Composable
internal fun ContextWindowButton(
    state: ChatUiState,
    onOpenLimits: () -> Unit,
    onCompact: () -> Unit,
) {
    if (state.conversation.isChat) return
    var expanded by remember(state.sessionId) { mutableStateOf(false) }
    val used = state.contextUsed.coerceAtLeast(0)
    // Local runs can use a smaller device-configured window than the global setting.
    val localRun = state.activeProvider?.let { provider ->
        com.androidharness.app.local.LocalModelCatalog.isLocal(provider.id) &&
            state.taskControl.provider?.id == provider.id &&
            state.taskControl.provider.model == state.effectiveModel
    } == true
    val limit = (if (localRun) state.taskControl.maxContext else state.maxContextTokens).coerceAtLeast(1)
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
    val label = stringResource(R.string.context_window_button)
    val scheme = MaterialTheme.colorScheme
    val color = when {
        !known -> scheme.onSurfaceVariant
        fraction >= .9f -> scheme.error
        fraction >= .75f -> scheme.tertiary
        else -> scheme.primary
    }
    val summarized = !state.taskControl.summaryOverride.isNullOrBlank() || state.messages.any {
        it.role == Role.SYSTEM && it.text.startsWith(AgentEngine.COMPACTION_PREFIX)
    }
    val canCompact = !state.busy && state.compactionNote == null && state.sessionId != null &&
        state.messages.isNotEmpty() && state.conversationReady && !state.conversationSaving &&
        state.modelSelectionReady && !state.modelSelectionBusy && state.modelSelectionError == null &&
        state.activeProvider != null && !state.isLoadingMessages && state.attachmentImports.isEmpty()

    Box {
        ExpressiveIconButton(
            onClick = { expanded = !expanded },
            glassDecoration = false,
            modifier = Modifier.size(48.dp).testTag("composer-context")
                .semantics {
                    contentDescription = label
                    stateDescription = if (known) usage else pending
                },
            shape = CircleShape,
            colors = IconButtonDefaults.iconButtonColors(contentColor = color),
        ) {
            Canvas(Modifier.size(23.dp)) {
                val stroke = 2.5.dp.toPx()
                val inset = stroke / 2
                val bounds = Size(size.width - stroke, size.height - stroke)
                drawArc(scheme.outlineVariant, 0f, 360f, false, Offset(inset, inset), bounds,
                    style = Stroke(stroke))
                if (known && fraction > 0f) drawArc(color, -90f, 360f * fraction, false,
                    Offset(inset, inset), bounds, style = Stroke(stroke, cap = StrokeCap.Round))
                drawCircle(color, radius = 2.dp.toPx())
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
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
                    TextButton(onClick = { expanded = false; onOpenLimits() }) {
                        Text(stringResource(R.string.ui_context_limits_extra71))
                    }
                    TextButton(onClick = { expanded = false; onCompact() }, enabled = canCompact,
                        modifier = Modifier.testTag("context-window-compact")) {
                        Text(stringResource(R.string.context_window_compact))
                    }
                }
            }
        }
    }
}
