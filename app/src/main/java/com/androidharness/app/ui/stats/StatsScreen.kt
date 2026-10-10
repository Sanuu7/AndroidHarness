package com.androidharness.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.data.SessionRepository
import com.androidharness.app.ui.common.formatTokenCount
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.theme.HarnessMono
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine

/** How the per-model attribution rows are ordered. */
private enum class StatsSort(val label: String) {
    TOKENS("Tokens"),
    PRICE("Price"),
    REQUESTS("Requests"),
}

/**
 * Usage statistics: a hero total, the token breakdown, a per-model spend card
 * (usage_events with share bars + price estimates), and cache performance.
 */
@Composable
fun StatsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    StatsScreen(container.sessions, onBack)
}

@Composable
internal fun StatsScreen(repository: SessionRepository, onBack: () -> Unit) {
    var range by remember { mutableStateOf(StatsRange.WEEK) }
    var sort by remember { mutableStateOf(StatsSort.TOKENS) }
    var minRequests by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(System.currentTimeMillis(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(60_000)
            }
        }
    }
    val window = range.window(now)
    val includeUndated = range == StatsRange.LIFETIME
    val statsFlow = remember(repository, window, range) {
        combine(repository.sessions, repository.statsUsageBetween(window.since, window.until)) { sessions, rows ->
            statsSnapshot(rows, sessions.takeIf { includeUndated })
        }
    }
    val stats = key(window, range) {
        statsFlow.collectAsStateWithLifecycle(initialValue = StatsSnapshot()).value
    }
    val bundle = stats.bundle
    val byModel = stats.byModel

    // Window total at list prices: same per-row math as the By model card,
    // summed, so the hero and the rows can never disagree. Null when no
    // per-model rows exist (pre-attribution sessions), so no suffix shows.
    val totalCost = remember(byModel) {
        if (byModel.isEmpty()) {
            null
        } else {
            byModel.sumOf { row ->
                com.androidharness.app.llm.ModelPrices.estimate(
                    row.model, row.inputTokens, row.outputTokens,
                    row.cachedTokens, row.cacheWriteTokens,
                ) ?: 0.0
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppHeader(
                title = "Stats",
                subtitle = "Token usage and cache performance",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                StatsRange.entries.forEachIndexed { index, r ->
                    SegmentedButton(
                        selected = range == r,
                        onClick = { range = r },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = StatsRange.entries.size,
                        ),
                    ) { Text(r.label) }
                }
            }

            Text("Date filters count requests made in that period. Earlier undated usage is kept in Lifetime.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // ----- Hero: one number that answers "how much have I run" ------
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        formatTokenCount(bundle.input + bundle.output),
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = HarnessMono,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "total tokens · ${bundle.requests} requests · ${bundle.sessionCount} sessions" +
                            (totalCost?.let { " · ≈ \$${"%.2f".format(it)}" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        MiniStat("Fresh in", formatTokenCount(bundle.freshInput), Modifier.weight(1f))
                        MiniStat("Output", formatTokenCount(bundle.output), Modifier.weight(1f))
                        MiniStat("Cache reads", formatTokenCount(bundle.cached), Modifier.weight(1f))
                        if (bundle.cacheWrite > 0) {
                            MiniStat("Cache writes", formatTokenCount(bundle.cacheWrite), Modifier.weight(1f))
                        }
                    }
                }
            }

            // ----- Per-model attribution ------------------------------------
            StatCard(title = "By model") {
                if (byModel.isEmpty()) {
                    Text(
                        "No per-model data in this window. Attribution starts with " +
                            "requests made after this version of the app: earlier " +
                            "sessions only have undivided totals.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Sort selector: tokens, estimated price, or request count.
                    val priced = byModel.map { row ->
                        row to com.androidharness.app.llm.ModelPrices.estimate(
                            row.model, row.inputTokens, row.outputTokens,
                            row.cachedTokens, row.cacheWriteTokens,
                        )
                    }
                    val sorted = when (sort) {
                        StatsSort.TOKENS -> priced.sortedByDescending { it.first.totalTokens }
                        StatsSort.PRICE -> priced.sortedByDescending { it.second ?: 0.0 }
                        StatsSort.REQUESTS -> priced.sortedByDescending { it.first.requests }
                    }
                    var shown = sorted
                    if (minRequests > 0) {
                        shown = shown.filter { it.first.requests >= minRequests }
                    }
                    // Unpriced models sink when sorting by price, but stay
                    // visible so the list never silently loses rows.
                    val maxTokens = shown.maxOfOrNull { it.first.totalTokens }?.coerceAtLeast(1) ?: 1

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        StatsSort.entries.forEachIndexed { index, s ->
                            SegmentedButton(
                                selected = sort == s,
                                onClick = { sort = s },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = StatsSort.entries.size,
                                ),
                            ) { Text(s.label) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    shown.forEachIndexed { index, (row, price) ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.model,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = HarnessMono,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${row.providerName} · ${row.requests} requests",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    formatTokenCount(row.totalTokens),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = HarnessMono,
                                    maxLines = 1,
                                )
                                Text(
                                    "≈ ${price?.let { "\$${"%.2f".format(it)}" } ?: "-"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        // Share-of-window bar: flat 2dp track, primary fill.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(1.dp),
                                ),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(row.totalTokens.toFloat() / maxTokens.toFloat())
                                    .height(2.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(1.dp),
                                    ),
                            )
                        }
                    }
                }
            }

            // ----- Cache ----------------------------------------------------
            StatCard(title = "Cache performance") {
                StatBig(
                    label = "Overall cache hit rate",
                    value = if (bundle.input > 0) {
                        "%.1f%%".format(bundle.cached.toDouble() / bundle.input.toDouble() * 100)
                    } else "-",
                )
                if (bundle.input > 0) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(1.dp),
                            ),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(
                                    (bundle.cached.toFloat() / bundle.input.toFloat()).coerceIn(0f, 1f),
                                )
                                .height(2.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)),
                        )
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                StatBig(
                    label = "Maximum cache hit score",
                    value = bundle.peakHitRate?.let { "%.1f%%".format(it * 100) } ?: "-",
                    supporting = "best session in this window",
                )
            }

            Text(
                "Definitions match open-source harnesses (pi, OpenCode): input counts " +
                    "fresh prompt tokens only; cache reads/writes are reported separately " +
                    "(reads are still part of each request's real prompt size, billed at the " +
                    "discounted rate). Hit rate = cache reads ÷ (fresh + reads + writes). " +
                    "Counts every model request the app made, including subagent and compaction " +
                    "requests. Reasoning tokens are billed as output and are never re-sent as input. " +
                    "Cost estimates use the bundled ModelPrices table and are best-effort.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatCard(title: String, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleMediumEmphasized,
            fontFamily = HarnessMono,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatBig(label: String, value: String, supporting: String? = null) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = HarnessMono,
                color = MaterialTheme.colorScheme.primary,
            )
            supporting?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }
}
