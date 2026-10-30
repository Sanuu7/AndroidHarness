package com.androidharness.app.ui.chat

import com.androidharness.app.R
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidharness.app.llm.ModelPrices
import com.androidharness.app.ui.common.ThinLinearProgress

import com.androidharness.app.ui.common.formatTokenCount
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.common.TextButton

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
    onContextLimits: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val used = state.contextUsed.toLong().coerceAtLeast(0)
    val provider = state.activeProvider
    val local = provider?.let { com.androidharness.app.local.LocalModelCatalog.isLocal(it.id) } == true
    val modelLimit = provider?.let { p -> state.catalogs[p.id]?.firstOrNull { it.id == state.effectiveModel }?.contextTokens }
    val localLimit = if (local && state.taskControl.provider?.id == provider?.id) state.taskControl.maxContext else com.androidharness.app.agent.ChatContextPolicy.LIMIT
    val max = (if (state.conversation.isChat) com.androidharness.app.agent.ChatContextPolicy.limit(localLimit, modelLimit, local)
        else state.maxContextTokens).toLong()
    val fraction = (used.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    val estimate = state.estimate
    val estimateTotal = estimate?.total?.takeIf { it > 0 } ?: 1
    // Fresh prompt tokens = total input minus cache reads/writes (pi semantics).
    val freshInput = (state.usage.totalInput - state.usage.totalCached - state.usage.totalCacheWrite)
        .coerceAtLeast(0)
    val model = state.effectiveModel
    val providerKey = state.activeProvider?.let { com.androidharness.app.llm.ModelsDev.providerKeyFor(it.baseUrl) }
    val usesChatGptPlan = com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(state.activeProvider?.id) ||
        state.sessionModelUsage.any { it.providerName.startsWith("ChatGPT · ") }
    val estimatedCost: Double? = if (usesChatGptPlan) null else if (state.sessionModelUsage.isNotEmpty()) {
        state.sessionModelUsage.sumOf { row ->
            val pKey = com.androidharness.app.llm.ModelsDev.providerKeyFor(row.providerName)
            ModelPrices.estimateSplit(
                model = row.model,
                totalInputTokens = row.inputTokens,
                outputTokens = row.outputTokens,
                cachedTokens = row.cachedTokens,
                cacheWriteTokens = row.cacheWriteTokens,
                peakInputTokens = row.peakInputTokens,
                peakOutputTokens = row.peakOutputTokens,
                peakCachedTokens = row.peakCachedTokens,
                peakCacheWriteTokens = row.peakCacheWriteTokens,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (!state.conversation.isChat) TextButton(onClick = onContextLimits) { Text(uiContext.getString(R.string.ui_context_limits_extra71)) }
                TextButton(onClick = onDismiss) { Text(uiContext.getString(R.string.settings_action_close)) }
            }
        },
        title = { Text(uiContext.getString(R.string.ui_context_and_usage)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Column {
                    Text(
                        uiContext.getString(R.string.ui_context_window),
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
                    BreakdownRow(uiContext.getString(R.string.ui_messages), estimate?.messagesTokens, estimateTotal)
                    BreakdownRow(uiContext.getString(R.string.ui_tools_and_skills), estimate?.toolsTokens, estimateTotal)
                    BreakdownRow(uiContext.getString(R.string.ui_system_prompt), estimate?.systemTokens, estimateTotal)
                    BreakdownRow(uiContext.getString(R.string.ui_meta_context), estimate?.metaTokens, estimateTotal)
                }

                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        uiContext.getString(R.string.ui_session_tokens),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    TokenRow(uiContext.getString(R.string.ui_fresh_input_tokens), formatTokenCount(freshInput))
                    TokenRow(uiContext.getString(R.string.ui_cached_tokens_hits), formatTokenCount(state.usage.totalCached))
                    if (state.usage.totalCacheWrite > 0) {
                        TokenRow(uiContext.getString(R.string.ui_cache_writes_created), formatTokenCount(state.usage.totalCacheWrite))
                    }
                    TokenRow(uiContext.getString(R.string.ui_output_tokens), formatTokenCount(state.usage.totalOutput))
                    TokenRow(uiContext.getString(R.string.ui_total_requests), state.usage.requests.toString())
                }

                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val hitRateText = when {
                        state.usage.requests == 0L && state.busy -> uiContext.getString(R.string.ui_calculating)
                        state.usage.totalInput > 0 -> "%.1f%%".format(state.usage.avgCacheHitRate * 100)
                        else -> "0.0%"
                    }
                    TokenRow(uiContext.getString(R.string.ui_overall_cache_hit_rate), hitRateText)
                    Text(
                        uiContext.getString(R.string.ui_hit_rate_updates_after_each_completed_turn),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(uiContext.getString(R.string.ui_estimated_cost), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (usesChatGptPlan) "ChatGPT plan" else "$%.4f".format(estimatedCost ?: 0.0),
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = HarnessMono,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    val subText = if (usesChatGptPlan) {
                        uiContext.getString(R.string.ui_review_plan_usage_and_credits_in_chatgpt)
                    } else if (state.sessionModelUsage.size > 1) {
                        uiContext.getString(R.string.ui_sum_across_models_used_in_this_session, (state.sessionModelUsage.size).toString())
                    } else {
                        uiContext.getString(R.string.ui_calculated_from_rates, (model ?: "current model").toString())
                    }
                    Text(
                        subText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }

                if (usesChatGptPlan) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    TextButton(onClick = {
                        com.androidharness.app.ui.common.openOAuthBrowser(context, android.net.Uri.parse(com.androidharness.app.chatgpt.ChatGptProtocol.USAGE_URL))
                    }) { Text(uiContext.getString(R.string.ui_manage_usage)) }
                } else Text(
                    uiContext.getString(R.string.ui_reasoning_and_thinking_tokens_are_billed_as),
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
            fontFamily = HarnessMono,
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
