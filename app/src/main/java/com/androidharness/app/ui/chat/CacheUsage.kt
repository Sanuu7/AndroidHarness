package com.androidharness.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidharness.app.data.db.UsageEventEntity
import com.androidharness.app.llm.ModelsDev
import com.androidharness.app.ui.theme.LocalStatusColors
import java.util.Locale

internal data class CacheUsageSummary(
    val input: Long,
    val cached: Long,
    val hits: Int,
    val misses: Int,
    val unknown: Int,
) {
    val rate: Double? get() = if (input > 0) cached * 100.0 / input else null
    val label: String get() = when {
        rate == null -> "Cache unavailable"
        cached == 0L -> "Cache missed"
        else -> "Cache hit · ${String.format(Locale.US, "%.0f", rate)}%"
    } + if (unknown > 0 && rate != null) " · partial" else ""
}

internal fun cacheUsageSummary(rows: List<UsageEventEntity>): CacheUsageSummary {
    val known = rows.filter { it.cacheReported && it.inputTokens > 0 }
    return CacheUsageSummary(
        input = known.sumOf { it.inputTokens },
        cached = known.sumOf { it.cachedTokens.coerceIn(0, it.inputTokens) },
        hits = known.count { it.cachedTokens > 0 },
        misses = known.count { it.cachedTokens <= 0 },
        unknown = rows.size - known.size,
    )
}

/** Rows arrive in recorded request order; unknown latest usage must not reuse an older hit. */
internal fun cacheIndicatorSummary(rows: List<UsageEventEntity>, running: Boolean): CacheUsageSummary =
    cacheUsageSummary(if (running) rows.takeLast(1) else rows)

/** Cache reads only; cache writes and uncached input are separate charges. */
internal fun cacheReadCost(row: UsageEventEntity, price: ModelsDev.ModelCost?): Double? =
    if (!row.cacheReported) null
    else if (row.cachedTokens <= 0) 0.0
    else price?.let { row.cachedTokens.coerceIn(0, row.inputTokens.coerceAtLeast(0)) / 1_000_000.0 * it.cacheRead }

/** Input charges on a miss include any cache creation premium, never output. */
internal fun cacheMissCost(row: UsageEventEntity, price: ModelsDev.ModelCost?): Double? {
    if (!row.cacheReported || row.inputTokens <= 0) return null
    val cached = row.cachedTokens.coerceIn(0, row.inputTokens)
    val writes = row.cacheWriteTokens.coerceIn(0, row.inputTokens - cached)
    val uncached = row.inputTokens - cached - writes
    return price?.let { (uncached * it.input + writes * it.cacheWrite) / 1_000_000.0 }
}

private fun cacheMoney(cost: Double): String = when {
    cost == 0.0 -> "$0"
    cost < 0.0001 -> "<$0.0001"
    else -> String.format(Locale.US, "$%.4f", cost)
}

/** One quiet footer line; request-level amounts stay behind a tap, inside chat. */
@Composable
internal fun CacheUsageFooter(rows: List<UsageEventEntity>, running: Boolean = false) {
    if (rows.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val summary = remember(rows) { cacheUsageSummary(rows) }
    val indicator = remember(rows, running) { cacheIndicatorSummary(rows, running) }
    // Refresh estimates when the catalog finishes loading or changes.
    val catalog by ModelsDev.providersFlow.collectAsState()
    val prices = remember(rows, catalog) {
        rows.map { row ->
            val providerKey = catalog.firstOrNull {
                it.name.equals(row.providerName, ignoreCase = true) || it.id.equals(row.providerName, ignoreCase = true)
            }?.id ?: ModelsDev.providerKeyFor(row.providerName)
            val price = if (row.providerName == "Harness" && com.androidharness.app.llm.HarnessProvider.isFree(row.model)) {
                ModelsDev.ModelCost(0.0, 0.0, 0.0, 0.0)
            } else ModelsDev.findCost(providerKey, row.model)
            price
        }
    }
    val costs = rows.mapIndexed { index, row ->
        if (summary.cached > 0) cacheReadCost(row, prices[index]) else cacheMissCost(row, prices[index])
    }
    val total = if (costs.all { it != null }) costs.filterNotNull().sum() else null
    val successColor = LocalStatusColors.current.success
    val errorColor = MaterialTheme.colorScheme.error
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 36.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (indicator.rate != null) {
                Icon(
                    if (indicator.cached > 0) Icons.Filled.CheckCircle else Icons.Filled.Close,
                    contentDescription = if (indicator.cached > 0) "Cache hit" else "Cache missed",
                    tint = if (indicator.cached > 0) successColor else errorColor,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                indicator.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                if (expanded) "Hide cache details" else "Show cache details",
                Modifier.padding(start = 2.dp).size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (running) Text("Latest request: ${indicator.label}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val costLabel = if (summary.cached > 0) "Cache-hit cost this turn" else "Input cost this turn"
                Text("$costLabel: ${total?.let { "est. ${cacheMoney(it)}" } ?: "unavailable"}",
                    style = MaterialTheme.typography.bodySmall)
                Text("${summary.cached} of ${summary.input} input tokens served from cache",
                    style = MaterialTheme.typography.bodySmall)
                Text(if (summary.cached > 0) "Estimated cached reads only, excluding output and cache writes."
                    else "Estimated uncached input and cache writes, excluding output.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (summary.unknown > 0) Text("${summary.unknown} requests did not report cache usage; excluded from the rate.",
                    style = MaterialTheme.typography.labelSmall)
                if (rows.size > 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    rows.forEachIndexed { i, r ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (r.cacheReported) {
                                    Icon(
                                        if (r.cachedTokens > 0) Icons.Filled.CheckCircle else Icons.Filled.Close,
                                        contentDescription = if (r.cachedTokens > 0) "Cache hit" else "Cache miss",
                                        tint = if (r.cachedTokens > 0) successColor else errorColor,
                                        modifier = Modifier.size(12.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text("Request ${i + 1}", style = MaterialTheme.typography.labelSmall)
                            }
                            Text(
                                if (!r.cacheReported) "Not reported"
                                else if (r.cachedTokens > 0) "${r.cachedTokens} cached"
                                else "0 cached",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
