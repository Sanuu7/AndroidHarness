package com.androidharness.app.ui.chat

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
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
 * Usage page of the Context and limits sheet. A wavy dial shows how full the context
 * window is, the estimated request splits into a bar whose segments grow in one after
 * another, and session totals sit on tonal tiles whose numbers count up when shown.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContextUsageContent(state: ChatUiState) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val used = state.contextUsed.toLong().coerceAtLeast(0)
    val provider = state.activeProvider
    val local = provider?.let { com.androidharness.app.local.LocalModelCatalog.isLocal(it.id) } == true
    val learnedLimit = provider?.let { p -> state.effectiveModel?.let { m ->
        state.learnedContextLimits[com.androidharness.app.agent.TextFileWrites.modelKey(p.id, m)] } }
    val modelLimit = com.androidharness.app.agent.ChatContextPolicy.modelLimit(
        provider?.let { p -> state.catalogs[p.id]?.firstOrNull { it.id == state.effectiveModel }?.contextTokens }, learnedLimit)
    val localLimit = if (local && state.taskControl.provider?.id == provider?.id) state.taskControl.maxContext else com.androidharness.app.agent.ChatContextPolicy.LIMIT
    val max = (if (state.conversation.isChat) com.androidharness.app.agent.ChatContextPolicy.limit(localLimit, modelLimit, local)
        else com.androidharness.app.ui.chat.components.codeContextLimit(state)).toLong().coerceAtLeast(1)
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
    val dial = remember { Animatable(0f) }
    LaunchedEffect(fraction) { dial.animateTo(fraction, spring(dampingRatio = 0.75f, stiffness = 70f)) }
    val tight = fraction >= 0.85f
    val dialColor = when {
        tight -> scheme.error
        fraction >= 0.6f -> scheme.tertiary
        else -> scheme.primary
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Context window dial.
        Surface(color = scheme.surfaceContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                    com.androidharness.app.ui.common.AppCircularProgressIndicator(
                        progress = { dial.value }, modifier = Modifier.fillMaxSize(),
                        color = dialColor, trackColor = scheme.surfaceContainerHighest,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%.0f%%".format(dial.value * 100), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                        Text(uiContext.getString(R.string.context_used), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(uiContext.getString(R.string.ui_context_window), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Text("${formatTokenCount(used)} / ${formatTokenCount(max)}", style = MaterialTheme.typography.titleMedium,
                        fontFamily = HarnessMono)
                    val chip by animateColorAsState(if (tight) scheme.errorContainer else scheme.secondaryContainer, label = "Context free chip")
                    Text(uiContext.getString(R.string.context_free_tokens, formatTokenCount((max - used).coerceAtLeast(0))),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (tight) scheme.onErrorContainer else scheme.onSecondaryContainer,
                        modifier = Modifier.background(chip, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }

        Surface(color = scheme.surfaceContainerLow, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.padding(16.dp)) {
                ContextBreakdownSection(estimate = estimate, used = used.toInt(), limit = max.toInt())
            }
        }

        Text(uiContext.getString(R.string.ui_session_tokens), style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        val hitRate = if (state.usage.totalInput > 0) state.usage.avgCacheHitRate.toFloat() else 0f
        val tiles = buildList<Triple<String, Long?, String?>> {
            add(Triple(uiContext.getString(R.string.ui_fresh_input_tokens), freshInput, null))
            add(Triple(uiContext.getString(R.string.ui_cached_tokens_hits), state.usage.totalCached, null))
            if (state.usage.totalCacheWrite > 0) add(Triple(uiContext.getString(R.string.ui_cache_writes_created), state.usage.totalCacheWrite, null))
            add(Triple(uiContext.getString(R.string.ui_output_tokens), state.usage.totalOutput, null))
            add(Triple(uiContext.getString(R.string.context_requests_short), null, state.usage.requests.toString()))
            add(Triple(uiContext.getString(R.string.context_cache_hit_rate_short), null,
                if (state.usage.requests == 0L && state.busy) uiContext.getString(R.string.ui_calculating) else "%.1f%%".format(hitRate * 100)))
        }
        tiles.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { column, (label, count, text) ->
                    StatTile(label, count, text, row * 2 + column, Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(uiContext.getString(R.string.ui_hit_rate_updates_after_each_completed_turn),
            style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 4.dp))

        // Cost, the one number people come here for, gets the accent container.
        Surface(color = scheme.primaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(uiContext.getString(R.string.ui_estimated_cost), style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer)
                val shown = remember { Animatable(0f) }
                LaunchedEffect(estimatedCost) { shown.animateTo((estimatedCost ?: 0.0).toFloat(), spring(dampingRatio = 1f, stiffness = 60f)) }
                Text(if (usesChatGptPlan) "ChatGPT plan" else "$%.4f".format(if (shown.isRunning) shown.value.toDouble() else estimatedCost ?: 0.0),
                    style = MaterialTheme.typography.headlineMedium, fontFamily = HarnessMono, fontWeight = FontWeight.SemiBold,
                    color = scheme.onPrimaryContainer)
                Text(when {
                    usesChatGptPlan -> uiContext.getString(R.string.ui_review_plan_usage_and_credits_in_chatgpt)
                    state.sessionModelUsage.size > 1 -> uiContext.getString(R.string.ui_sum_across_models_used_in_this_session, (state.sessionModelUsage.size).toString())
                    else -> uiContext.getString(R.string.ui_calculated_from_rates, (model ?: "current model").toString())
                }, style = MaterialTheme.typography.labelSmall, color = scheme.onPrimaryContainer.copy(alpha = 0.75f))
            }
        }
        if (usesChatGptPlan) {
            TextButton(onClick = {
                com.androidharness.app.ui.common.openOAuthBrowser(uiContext, android.net.Uri.parse(com.androidharness.app.chatgpt.ChatGptProtocol.USAGE_URL))
            }) { Text(uiContext.getString(R.string.ui_manage_usage)) }
        } else Text(
            uiContext.getString(R.string.ui_reasoning_and_thinking_tokens_are_billed_as),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** Tonal stat tile; numbers count up and the tile springs in, a beat after the one before it. */
@Composable
private fun StatTile(label: String, count: Long?, text: String?, order: Int, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val arrive = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(45L * order)
        arrive.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 380f))
    }
    val value = remember { Animatable(0f) }
    LaunchedEffect(count) { if (count != null) value.animateTo(count.toFloat(), spring(dampingRatio = 1f, stiffness = 50f)) }
    Surface(color = scheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp), modifier = modifier.graphicsLayer {
        alpha = arrive.value.coerceIn(0f, 1f)
        val s = 0.9f + 0.1f * arrive.value
        scaleX = s; scaleY = s
    }) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text ?: formatTokenCount(if (value.isRunning) value.value.toLong() else count ?: 0),
                style = MaterialTheme.typography.titleMedium, fontFamily = HarnessMono, fontWeight = FontWeight.SemiBold,
                maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 2)
        }
    }
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
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(scheme.surfaceContainerHighest),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Segments grow in from nothing one after another; the free space takes up the rest.
            var grown = 0f
            groups.filter { it.tokens > 0 }.forEachIndexed { index, group ->
                val target = (group.tokens / span).coerceAtLeast(0.001f)
                val weight = remember { Animatable(0.001f) }
                LaunchedEffect(target) {
                    kotlinx.coroutines.delay(90L * index)
                    weight.animateTo(target, spring(dampingRatio = 0.7f, stiffness = 140f))
                }
                grown += weight.value
                Box(Modifier.weight(weight.value.coerceAtLeast(0.001f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(group.color))
            }
            val free = 1f - grown
            if (free > 0.001f) Spacer(Modifier.weight(free))
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
            modifier = if (canExpand) Modifier.clip(RoundedCornerShape(12.dp)).clickable { expanded = !expanded } else Modifier,
            trailing = if (canExpand) {
                {
                    val turn by animateFloatAsState(if (expanded) 180f else 0f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "Breakdown chevron")
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = context.getString(
                            if (expanded) R.string.context_breakdown_collapse else R.string.context_breakdown_expand,
                        ),
                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else null,
        )
        AnimatedVisibility(
            visible = expanded && canExpand,
            enter = expandVertically(expressiveDefaultSpatialSpec()) + fadeIn(expressiveEffectsSpec()),
            exit = shrinkVertically(androidx.compose.animation.core.tween(160)) + fadeOut(androidx.compose.animation.core.tween(90)),
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
