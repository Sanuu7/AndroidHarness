package com.androidharness.app.ui.stats

import com.androidharness.app.ui.common.localizedOption
import com.androidharness.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.data.SessionRepository
import com.androidharness.app.data.db.CodeActivityTotals
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.common.formatTokenCount
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import com.androidharness.app.ui.common.TextButton

private enum class StatsSort(val label: String) { TOKENS("Tokens"), PRICE("Price"), REQUESTS("Requests") }
private data class UsageView(val stats: StatsSnapshot = StatsSnapshot(), val code: CodeActivityTotals = CodeActivityTotals())

@Composable
fun StatsScreen(container: AppContainer, onBack: () -> Unit) = StatsScreen(container.sessions, onBack)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatsScreen(repository: SessionRepository, onBack: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var range by rememberSaveable { mutableStateOf(StatsRange.WEEK) }
    var sort by rememberSaveable { mutableStateOf(StatsSort.TOKENS) }
    var definitions by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(System.currentTimeMillis(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = System.currentTimeMillis(); delay(60_000) }
        }
    }
    val window = range.window(now)
    val flow = remember(repository, window, range) {
        combine(repository.sessions, repository.statsUsageBetween(window.since, window.until), repository.codeActivityBetween(window.since, window.until)) { sessions, usage, code ->
            UsageView(statsSnapshot(usage, sessions.takeIf { range == StatsRange.LIFETIME }), code)
        }
    }
    val view = key(window, range) { flow.collectAsStateWithLifecycle(initialValue = UsageView()).value }
    val bundle = view.stats.bundle
    val models = view.stats.byModel.map { row -> row to com.androidharness.app.llm.ModelPrices.estimateSplit(
        model = row.model, totalInputTokens = row.inputTokens, outputTokens = row.outputTokens,
        cachedTokens = row.cachedTokens, cacheWriteTokens = row.cacheWriteTokens,
        peakInputTokens = row.peakInputTokens, peakOutputTokens = row.peakOutputTokens,
        peakCachedTokens = row.peakCachedTokens, peakCacheWriteTokens = row.peakCacheWriteTokens,
        providerKey = com.androidharness.app.llm.ModelsDev.providerKeyFor(row.providerName)) }
    val knownPrices = models.mapNotNull { it.second }
    val cost = knownPrices.takeIf { it.isNotEmpty() }?.sum()
    val code = view.code
    val scheme = MaterialTheme.colorScheme
    val green = LocalStatusColors.current.success
    Scaffold(containerColor = scheme.surface, topBar = {
        AppHeader(uiContext.getString(R.string.settings_page_usage_title), subtitle = uiContext.getString(R.string.ui_model_activity_and_ai_code_changes), onBack = onBack)
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (LocalDensity.current.fontScale > 1.2f) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatsRange.entries.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            FilterChip(selected = range == option, onClick = { range = option }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                label = { Text(localizedOption(option.label), Modifier.fillMaxWidth(), textAlign = TextAlign.Center) })
                        }
                    }
                }
            } else SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                StatsRange.entries.forEachIndexed { index, option ->
                    SegmentedButton(selected = range == option, onClick = { range = option }, icon = {},
                        shape = SegmentedButtonDefaults.itemShape(index, StatsRange.entries.size)) {
                        Text(localizedOption(option.label), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(uiContext.getString(R.string.ui_model_activity), style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer)
                    Text(formatTokenCount(bundle.input + bundle.output), style = MaterialTheme.typography.displayMedium,
                        fontFamily = HarnessMono, color = scheme.onPrimaryContainer)
                    Text(uiContext.getString(R.string.ui_total_tokens_1396, (bundle.requests).toString(), (if (bundle.requests == 1L) "request" else "requests").toString(), (bundle.sessionCount).toString(), (if (bundle.sessionCount == 1) "session" else "sessions").toString()),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer)
                    HorizontalDivider(color = scheme.onPrimaryContainer.copy(alpha = .18f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        UsageMetric("Estimated cost", cost?.let { "$${"%.2f".format(it)}" } ?: "Unavailable", Modifier.weight(1f))
                        UsageMetric("Cache hit rate", if (bundle.input > 0) "${"%.1f".format(bundle.cached.toDouble() / bundle.input * 100)}%" else "—", Modifier.weight(1f))
                    }
                    if (knownPrices.size < models.size) Text(uiContext.getString(R.string.ui_cost_includes_models_with_known_list_prices), style = MaterialTheme.typography.labelSmall)
                }
            }
            UsageCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Code, null, tint = scheme.primary)
                    Column {
                        Text(uiContext.getString(R.string.ui_ai_code_changes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(uiContext.getString(R.string.ui_tracked_edits, (range.label).toString(), (code.edits).toString()), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = green.copy(alpha = .09f)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("+${lineCount(code.added)}", style = if (lineCount(code.added).length > 8) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, fontFamily = HarnessMono, color = green)
                            Text(uiContext.getString(R.string.ui_lines_added), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = scheme.errorContainer.copy(alpha = .45f)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("−${lineCount(code.removed)}", style = if (lineCount(code.removed).length > 8) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, fontFamily = HarnessMono, color = scheme.error)
                            Text(uiContext.getString(R.string.ui_lines_removed), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                val total = code.added + code.removed
                if (total > 0) Row(Modifier.fillMaxWidth().height(6.dp)) {
                    if (code.added > 0) Box(Modifier.weight(code.added.toFloat() / total).fillMaxHeight().background(green, RoundedCornerShape(3.dp)))
                    if (code.removed > 0) Box(Modifier.weight(code.removed.toFloat() / total).fillMaxHeight().background(scheme.error, RoundedCornerShape(3.dp)))
                }
                val net = code.added - code.removed
                Text(uiContext.getString(R.string.ui_net_change_lines, (if (net >= 0) "+" else "−").toString(), (lineCount(kotlin.math.abs(net))).toString()), style = MaterialTheme.typography.bodyMedium)
                if (code.edits == 0L) Text(uiContext.getString(R.string.ui_no_tracked_code_changes_in_this_period), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Text(uiContext.getString(R.string.ui_source_code_and_configuration_totals_stay_after), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            UsageCard {
                Text(uiContext.getString(R.string.ui_token_breakdown), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    UsageMetric(uiContext.getString(R.string.ui_fresh_input), formatTokenCount(bundle.freshInput), Modifier.weight(1f))
                    UsageMetric(uiContext.getString(R.string.ui_output_1407), formatTokenCount(bundle.output), Modifier.weight(1f))
                }
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = .5f))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    UsageMetric(uiContext.getString(R.string.ui_cache_reads), formatTokenCount(bundle.cached), Modifier.weight(1f))
                    UsageMetric(uiContext.getString(R.string.ui_cache_writes_1409), formatTokenCount(bundle.cacheWrite), Modifier.weight(1f))
                }
            }
            UsageCard {
                Text(uiContext.getString(R.string.ui_by_model), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    StatsSort.entries.forEachIndexed { index, option ->
                        SegmentedButton(selected = sort == option, onClick = { sort = option }, icon = {}, shape = SegmentedButtonDefaults.itemShape(index, StatsSort.entries.size)) { Text(localizedOption(option.label)) }
                    }
                }
                val sorted = when (sort) {
                    StatsSort.TOKENS -> models.sortedByDescending { it.first.totalTokens }
                    StatsSort.PRICE -> models.sortedByDescending { it.second ?: -1.0 }
                    StatsSort.REQUESTS -> models.sortedByDescending { it.first.requests }
                }
                if (sorted.isEmpty()) Text(uiContext.getString(R.string.ui_no_dated_model_activity_in_this_period), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                val max = sorted.maxOfOrNull { it.first.totalTokens }?.coerceAtLeast(1) ?: 1
                sorted.forEachIndexed { index, (model, price) ->
                    if (index > 0) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = .45f))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(model.model, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(uiContext.getString(R.string.ui_requests_extra84, (model.providerName).toString(), (model.requests).toString()), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(uiContext.getString(R.string.settings_agent_context_value, (formatTokenCount(model.totalTokens)).toString()), fontFamily = HarnessMono, style = MaterialTheme.typography.bodySmall)
                            Text(price?.let { "≈ $${"%.2f".format(it)}" } ?: uiContext.getString(R.string.ui_price_unavailable), style = MaterialTheme.typography.bodySmall)
                        }
                        LinearProgressIndicator(progress = { model.totalTokens.toFloat() / max }, modifier = Modifier.fillMaxWidth().height(4.dp))
                    }
                }
            }
            UsageCard {
                TextButton(onClick = { definitions = !definitions }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                    Text(uiContext.getString(R.string.ui_how_usage_is_counted), Modifier.weight(1f), color = scheme.onSurface)
                    Icon(if (definitions) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
                if (definitions) {
                    Text(uiContext.getString(R.string.ui_ai_code_changes_count_applied_source_and), style = MaterialTheme.typography.bodySmall)
                    Text(uiContext.getString(R.string.ui_tracked_file_tools_and_eligible_local_foreground), style = MaterialTheme.typography.bodySmall)
                    Text(uiContext.getString(R.string.ui_today_uses_your_device_s_calendar_day), style = MaterialTheme.typography.bodySmall)
                    Text(uiContext.getString(R.string.ui_token_totals_include_subagents_and_compaction_cache), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun UsageCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

private fun lineCount(value: Long): String = java.text.NumberFormat.getIntegerInstance().format(value)

@Composable
private fun UsageMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontFamily = HarnessMono)
    }
}
