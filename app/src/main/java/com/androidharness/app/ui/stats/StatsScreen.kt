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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.ui.chat.formatTokenCount
import com.androidharness.app.ui.common.AppHeader
import java.util.concurrent.TimeUnit

private enum class StatsRange(val label: String, val days: Long?) {
    DAY("1 day", 1),
    WEEK("1 week", 7),
    MONTH("1 month", 30),
    LIFETIME("Lifetime", null),
}

private data class StatsBundle(
    val input: Long,
    val output: Long,
    val cached: Long,
    val cacheWrite: Long,
    val requests: Long,
    val sessionCount: Int,
    /** Best per-session cache hit rate in the window (0..1), only sessions with input. */
    val peakHitRate: Double?,
) {
    /**
     * Fresh prompt tokens: total input minus cache reads/writes, matching
     * pi/pi-mono's Usage.input semantics (caches reported separately).
     */
    val freshInput: Long get() = (input - cached - cacheWrite).coerceAtLeast(0)
}

private fun List<SessionEntity>.bundle(): StatsBundle {
    var input = 0L; var output = 0L; var cached = 0L; var cacheWrite = 0L; var requests = 0L
    var peak: Double? = null
    for (s in this) {
        input += s.totalInputTokens
        output += s.totalOutputTokens
        cached += s.totalCachedTokens
        cacheWrite += s.totalCacheWriteTokens
        requests += s.requestCount
        if (s.totalInputTokens > 0) {
            val rate = s.totalCachedTokens.toDouble() / s.totalInputTokens.toDouble()
            if (peak == null || rate > peak) peak = rate
        }
    }
    return StatsBundle(input, output, cached, cacheWrite, requests, size, peak)
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
    val sessions by container.sessions.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    var range by remember { mutableStateOf(StatsRange.WEEK) }

    val cutoff = range.days?.let { System.currentTimeMillis() - TimeUnit.DAYS.toMillis(it) } ?: 0L
    val bundle = remember(sessions, cutoff) {
        (if (cutoff > 0) sessions.filter { it.updatedAt >= cutoff } else sessions).bundle()
    }
    val byModel by container.sessions.usageByModelSince(cutoff)
        .collectAsStateWithLifecycle(initialValue = emptyList())

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

            // ----- Hero: one number that answers "how much have I run" ------
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        formatTokenCount(bundle.input + bundle.output),
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "total tokens · ${bundle.requests} requests · ${bundle.sessionCount} sessions",
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
                            "requests made after this version of the app — earlier " +
                            "sessions only have undivided totals.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val maxTokens = byModel.maxOf { it.totalTokens }.coerceAtLeast(1)
                    byModel.forEachIndexed { index, row ->
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
                                    fontFamily = FontFamily.Monospace,
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
                                    fontFamily = FontFamily.Monospace,
                                )
                                com.androidharness.app.llm.ModelPrices.estimate(
                                    row.model, row.inputTokens, row.outputTokens,
                                    row.cachedTokens, row.cacheWriteTokens,
                                )?.let {
                                    Text(
                                        "≈ \$${"%.2f".format(it)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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
                    label = "Average hit rate",
                    value = if (bundle.input > 0) {
                        "%.1f%%".format(bundle.cached.toDouble() / bundle.input.toDouble() * 100)
                    } else "—",
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
                    value = bundle.peakHitRate?.let { "%.1f%%".format(it * 100) } ?: "—",
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
            fontFamily = FontFamily.Monospace,
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
                fontFamily = FontFamily.Monospace,
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
