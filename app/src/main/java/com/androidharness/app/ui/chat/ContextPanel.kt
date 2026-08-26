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
    val model = state.effectiveModel
    val providerKey = state.activeProvider?.let { com.androidharness.app.llm.ModelsDev.providerKeyFor(it.baseUrl) }
    val estimatedCost: Double? = if (state.sessionModelUsage.isNotEmpty()) {
        state.sessionModelUsage.sumOf { row ->
            val pKey = com.androidharness.app.llm.ModelsDev.providerKeyFor(row.providerName)
            ModelPrices.estimate(
                model = row.model,
                totalInputTokens = row.inputTokens,
                outputTokens = row.outputTokens,
                cachedTokens = row.cachedTokens,
                cacheWriteTokens = row.cacheWriteTokens,
                providerKey = pKey,
            ) ?: 0.0
        }
    } else {
        model?.let {
            ModelPrices.estimate(
                it,
                totalInputTokens = state.usage.totalInput,
                outputTokens = state.usage.totalOutput,
                cachedTokens = state.usage.totalCached,
                cacheWriteTokens = state.usage.totalCacheWrite,
                providerKey = providerKey,
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("Context and Usage") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Column {
                    Text(
                        "Context Window",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${formatTokenCount(used)} / ${formatTokenCount(max)} tokens (" +
                            "%.1f%%".format(fraction * 100) + ")",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    ThinLinearProgress(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BreakdownRow("Messages", estimate?.messagesTokens, estimateTotal)
                    BreakdownRow("Tools and Skills", estimate?.toolsTokens, estimateTotal)
                    BreakdownRow("System Prompt", estimate?.systemTokens, estimateTotal)
                    BreakdownRow("Meta Context", estimate?.metaTokens, estimateTotal)
                }

                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Session Tokens",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    TokenRow("Fresh input tokens", formatTokenCount(freshInput))
                    TokenRow("Cached tokens (hits)", formatTokenCount(state.usage.totalCached))
                    if (state.usage.totalCacheWrite > 0) {
                        TokenRow("Cache writes (created)", formatTokenCount(state.usage.totalCacheWrite))
                    }
                    TokenRow("Output tokens", formatTokenCount(state.usage.totalOutput))
                    TokenRow("Total requests", state.usage.requests.toString())
                }

                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TokenRow(
                        "Cache hit rate",
                        if (state.usage.totalInput > 0) "%.1f%%".format(state.usage.avgCacheHitRate * 100) else "0.0%",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Estimated cost", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "$%.4f".format(estimatedCost ?: 0.0),
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    val subText = if (state.sessionModelUsage.size > 1) {
                        "Sum across ${state.sessionModelUsage.size} models used in this session"
                    } else {
                        "Calculated from ${model ?: "current model"} rates"
                    }
                    Text(
                        subText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }

                Text(
                    "Reasoning and thinking tokens are billed as output and are not re-sent as input.",
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
            tokens?.let { "%.1f%%".format(it.toFloat() / total * 100) } ?: "0.0%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
