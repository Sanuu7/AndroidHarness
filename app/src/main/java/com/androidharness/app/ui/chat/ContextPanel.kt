package com.androidharness.app.ui.chat

import android.content.Context
import com.androidharness.app.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.ContextBreakdown
import com.androidharness.app.agent.ContextEstimate
import com.androidharness.app.llm.ModelPrices
import com.androidharness.app.ui.theme.expressiveDefaultSpatialSpec
import com.androidharness.app.ui.theme.expressiveEffectsSpec
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

                ContextBreakdownSection(estimate = estimate, used = used.toInt(), limit = max.toInt())

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

private data class BreakdownGroup(
    val label: String,
    val tokens: Int,
    val color: Color,
    val rows: List<Pair<String, Int>>,
)

/**
 * The estimated request split into messages, tool definitions, system prompt and
 * meta overhead: a bar drawn against the whole window, one row per group that
 * opens into what the group is made of, then the largest tool results and a few
 * ways to free space.
 */
@Composable
private fun ContextBreakdownSection(estimate: ContextEstimate?, used: Int, limit: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val breakdown = estimate?.breakdown
    val groups = remember(estimate, scheme) {
        listOf(
            BreakdownGroup(context.getString(R.string.ui_messages), estimate?.messagesTokens ?: 0, scheme.primary,
                breakdown?.messages.orEmpty().map { sliceLabel(context, it) to it.tokens }),
            BreakdownGroup(context.getString(R.string.context_group_tools), estimate?.toolsTokens ?: 0, scheme.tertiary,
                breakdown?.tools.orEmpty().map { sliceLabel(context, it) to it.tokens }),
            BreakdownGroup(context.getString(R.string.ui_system_prompt), estimate?.systemTokens ?: 0, scheme.secondary,
                breakdown?.system.orEmpty().map { sliceLabel(context, it) to it.tokens }),
            BreakdownGroup(context.getString(R.string.ui_meta_context), estimate?.metaTokens ?: 0, scheme.outline, emptyList()),
        )
    }
    val total = groups.sumOf { it.tokens }.coerceAtLeast(1)
    val span = maxOf(limit, total).toFloat()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            context.getString(R.string.context_breakdown_title),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(scheme.surfaceContainerHighest),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            groups.filter { it.tokens > 0 }.forEach { group ->
                val weight by animateFloatAsState(
                    targetValue = (group.tokens / span).coerceAtLeast(0.001f),
                    animationSpec = expressiveDefaultSpatialSpec(),
                    label = "contextSegment",
                )
                Box(Modifier.weight(weight).fillMaxHeight().background(group.color))
            }
            val free = span - total
            if (free > 0f) Spacer(Modifier.weight((free / span).coerceAtLeast(0.001f)))
        }
        groups.forEach { group -> BreakdownGroupRow(group, span) }
        BreakdownRowLine(
            label = context.getString(R.string.context_free_space),
            tokens = (span - total).toInt().coerceAtLeast(0),
            span = span,
            dot = scheme.surfaceContainerHighest,
        )
        Text(
            context.getString(R.string.context_breakdown_note),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        if (breakdown != null) {
            if (breakdown.largestResults.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    context.getString(R.string.context_largest_results),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
                breakdown.largestResults.forEach { result ->
                    TokenRow(result.toolName, formatTokenCount(result.tokens))
                }
            }
            val hints = remember(breakdown, used, limit) { breakdown.hints(used, limit) }
            if (hints.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    context.getString(R.string.context_hints_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
                hints.forEach { hint ->
                    Text(
                        hintText(context, hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun BreakdownGroupRow(group: BreakdownGroup, span: Float) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by rememberSaveable(group.label) { mutableStateOf(false) }
    val canExpand = group.rows.isNotEmpty()
    Column {
        BreakdownRowLine(
            label = group.label,
            tokens = group.tokens,
            span = span,
            dot = group.color,
            modifier = if (canExpand) Modifier.clickable { expanded = !expanded } else Modifier,
            trailing = if (canExpand) {
                {
                    Icon(
                        if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = context.getString(
                            if (expanded) R.string.context_breakdown_collapse else R.string.context_breakdown_expand,
                        ),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else null,
        )
        AnimatedVisibility(
            visible = expanded && canExpand,
            enter = expandVertically(expressiveDefaultSpatialSpec()) + fadeIn(expressiveEffectsSpec()),
            exit = shrinkVertically(expressiveDefaultSpatialSpec()) + fadeOut(expressiveEffectsSpec()),
        ) {
            Column(
                modifier = Modifier.padding(start = 18.dp, top = 2.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                group.rows.forEach { (label, tokens) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatTokenCount(tokens),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = HarnessMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakdownRowLine(
    label: String,
    tokens: Int,
    span: Float,
    dot: Color,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            formatTokenCount(tokens),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = HarnessMono,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "%.1f%%".format(tokens / span * 100),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
            textAlign = TextAlign.End,
        )
        if (trailing != null) { Spacer(Modifier.width(4.dp)); trailing() }
        else Spacer(Modifier.width(22.dp))
    }
}

private fun sliceLabel(context: Context, slice: ContextBreakdown.Slice): String = when (slice.part) {
    ContextBreakdown.Part.CORE -> context.getString(R.string.context_part_core)
    ContextBreakdown.Part.SKILLS -> context.getString(R.string.context_part_skills)
    ContextBreakdown.Part.ENVIRONMENT -> context.getString(R.string.context_part_environment)
    ContextBreakdown.Part.INSTRUCTIONS -> context.getString(R.string.context_part_instructions)
    ContextBreakdown.Part.MEMORY -> context.getString(R.string.context_part_memory)
    ContextBreakdown.Part.REPO_MAP -> context.getString(R.string.context_part_repo_map)
    ContextBreakdown.Part.TOOLS_BUILT_IN -> context.getString(R.string.context_part_tools_built_in, slice.count)
    ContextBreakdown.Part.TOOLS_CODEGRAPH -> context.getString(R.string.context_part_tools_codegraph, slice.count)
    ContextBreakdown.Part.TOOLS_MCP -> context.getString(R.string.context_part_tools_mcp, slice.detail, slice.count)
    ContextBreakdown.Part.USER -> context.getString(R.string.context_part_user)
    ContextBreakdown.Part.ASSISTANT -> context.getString(R.string.context_part_assistant)
    ContextBreakdown.Part.TOOL_CALLS -> context.getString(R.string.context_part_tool_calls)
    ContextBreakdown.Part.TOOL_RESULTS -> context.getString(R.string.context_part_tool_results)
    ContextBreakdown.Part.NOTES -> context.getString(R.string.context_part_notes)
}

private fun hintText(context: Context, hint: ContextBreakdown.Hint): String = when (hint.kind) {
    ContextBreakdown.HintKind.NEAR_LIMIT -> context.getString(R.string.context_hint_near_limit)
    ContextBreakdown.HintKind.TOOL_RESULTS ->
        if (hint.detail.isBlank()) context.getString(R.string.context_hint_tool_results_unnamed, formatTokenCount(hint.tokens))
        else context.getString(R.string.context_hint_tool_results, formatTokenCount(hint.tokens), hint.detail)
    ContextBreakdown.HintKind.MCP_TOOLS -> context.getString(R.string.context_hint_mcp_tools, formatTokenCount(hint.tokens))
    ContextBreakdown.HintKind.SKILLS -> context.getString(R.string.context_hint_skills, formatTokenCount(hint.tokens))
    ContextBreakdown.HintKind.MEMORY -> context.getString(R.string.context_hint_memory, formatTokenCount(hint.tokens))
}
