package com.androidharness.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
 * Usage statistics across all sessions: tokens in/out, cache performance
 * (overall + best-session hit rate) filtered by time window. All values come
 * from the sessions table; nothing is recomputed from provider APIs.
 */
@Composable
fun StatsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val sessions by container.sessions.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    var range by remember { mutableStateOf(StatsRange.WEEK) }

    val bundle = remember(sessions, range) {
        val cutoff = range.days?.let {
            System.currentTimeMillis() - TimeUnit.DAYS.toMillis(it)
        }
        (if (cutoff != null) sessions.filter { it.updatedAt >= cutoff } else sessions).bundle()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { AppHeader(title = "Stats", onBack = onBack) },
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

            StatCard(title = "Tokens") {
                StatRow("Total tokens", formatTokenCount(bundle.input + bundle.output))
                StatRow("Input (fresh)", formatTokenCount(bundle.freshInput))
                StatRow("Output", formatTokenCount(bundle.output))
                StatRow("Cache reads", formatTokenCount(bundle.cached))
                if (bundle.cacheWrite > 0) {
                    StatRow("Cache writes", formatTokenCount(bundle.cacheWrite))
                }
                StatRow("Requests", bundle.requests.toString())
                StatRow("Sessions", bundle.sessionCount.toString())
            }

            StatCard(title = "Cache performance") {
                StatBig(
                    label = "Average hit rate",
                    value = if (bundle.input > 0) {
                        "%.1f%%".format(bundle.cached.toDouble() / bundle.input.toDouble() * 100)
                    } else "—",
                )
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
                    "requests. Reasoning tokens are billed as output and are never re-sent as input.",
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
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
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
