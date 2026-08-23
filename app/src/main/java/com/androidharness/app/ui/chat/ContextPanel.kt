package com.androidharness.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.androidharness.app.llm.ModelPrices
import com.androidharness.app.ui.common.ThinLinearProgress

fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0).replace(".0M", "M")
    tokens >= 1_000 -> "%.1fK".format(tokens / 1_000.0).replace(".0K", "K")
    else -> tokens.toString()
}

/**
 * Current-context dialog, modeled on GUI harnesses like Cline: a live
 * context-window bar (last request's total vs the model's window), the
 * cumulative ↑in / ↓out / cache reads/writes breakdown for this session,
 * and a list-price cost estimate at the same bucket rates they use.
 */
@Composable
fun ContextUsageDialog(
    state: ChatUiState,
    onDismiss: () -> Unit,
) {
    val used = state.contextUsed.toLong().coerceAtLeast(0)
    val max = state.maxContextTokens.toLong()
    val fraction = (used.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    val estimate = state.estimate
    val estimateTotal = estimate?.total?.takeIf { it > 0 } ?: 1
    // Fresh prompt tokens = total input minus cache reads/writes (pi semantics).
    val freshInput = (state.usage.totalInput - state.usage.totalCached - state.usage.totalCacheWrite)
        .coerceAtLeast(0)
    val model = state.activeProvider?.model
    val estimatedCost = model?.let {
        ModelPrices.estimate(
            it,
            totalInputTokens = state.usage.totalInput,
            outputTokens = state.usage.totalOutput,
            cachedTokens = state.usage.totalCached,
            cacheWriteTokens = state.usage.totalCacheWrite,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("Context") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Column {
                    Text(
                        "Context window",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${formatTokenCount(used)}/${formatTokenCount(max)} " +
                            "(%.1f%%)".format(fraction * 100),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    ThinLinearProgress(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BreakdownRow("Messages", estimate?.messagesTokens, estimateTotal)
                    BreakdownRow("System tools", estimate?.toolsTokens, estimateTotal)
                    BreakdownRow("System prompt", estimate?.systemTokens, estimateTotal)
                    BreakdownRow("Meta context", estimate?.metaTokens, estimateTotal)
                }

                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Arrow directions match Cline's task-header convention.
                    TokenRow("↑ Input (fresh)", formatTokenCount(freshInput))
                    TokenRow("↓ Output", formatTokenCount(state.usage.totalOutput))
                    if (state.usage.totalCached > 0) {
                        TokenRow("→ Cache reads", formatTokenCount(state.usage.totalCached))
                    }
                    if (state.usage.totalCacheWrite > 0) {
                        TokenRow("← Cache writes", formatTokenCount(state.usage.totalCacheWrite))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Estimated cost", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            estimatedCost?.let { "$%.4f".format(it) } ?: "—",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (estimatedCost != null && model != null) {
                        Text(
                            "at $model list prices",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Cache hit rate", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (state.usage.totalInput > 0)
                            "%.1f%%".format(state.usage.avgCacheHitRate * 100)
                        else "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Text(
                    "Session: ${formatTokenCount(freshInput)} fresh in · " +
                        "${formatTokenCount(state.usage.totalCached)} cached · " +
                        "${formatTokenCount(state.usage.totalOutput)} out · " +
                        "${state.usage.requests} requests",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Reasoning shown in chat is billed as output and never re-sent as input.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        },
    )
}

@Composable
private fun TokenRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun BreakdownRow(label: String, tokens: Int?, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            tokens?.let { "%.1f%%".format(it.toFloat() / total * 100) } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
