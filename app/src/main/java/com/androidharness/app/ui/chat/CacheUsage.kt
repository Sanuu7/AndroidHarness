package com.androidharness.app.ui.chat

import com.androidharness.app.R
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
        else -> "Cache hit · ${cachePercentage(cached, input)}%"
    } + if (unknown > 0 && rate != null) " · partial" else ""
}

/** Two decimals, reserving 100% for a fully cached prompt and 0% for a true miss. */
internal fun cachePercentage(cached: Long, input: Long): String = when {
    cached == input -> "100"
    cached * 100.0 / input < 0.01 -> "<0.01"
    else -> java.math.BigDecimal.valueOf(cached).multiply(java.math.BigDecimal.valueOf(100))
        .divide(java.math.BigDecimal.valueOf(input), 2, java.math.RoundingMode.HALF_UP)
        .min(java.math.BigDecimal("99.99"))
        .stripTrailingZeros().toPlainString()
}

internal fun validCacheUsage(row: UsageEventEntity): Boolean = row.cacheReported &&
    row.inputTokens > 0 && row.cachedTokens in 0..row.inputTokens &&
    row.cacheWriteTokens in 0..(row.inputTokens - row.cachedTokens)

internal fun cacheUsageSummary(rows: List<UsageEventEntity>): CacheUsageSummary {
    val known = rows.filter(::validCacheUsage)
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
internal fun cacheReadCost(row: UsageEventEntity, price: ModelsDev.CachePrices?): Double? =
    if (!validCacheUsage(row)) null
    else if (row.cachedTokens <= 0) 0.0
    else price?.takeIf { it.read != null }?.let { row.cachedTokens.coerceIn(0, row.inputTokens.coerceAtLeast(0)) / 1_000_000.0 * it.read!! }

/** Input charges on a miss include any cache creation premium, never output. */
internal fun cacheMissCost(row: UsageEventEntity, price: ModelsDev.CachePrices?): Double? {
    if (!validCacheUsage(row)) return null
    val cached = row.cachedTokens.coerceIn(0, row.inputTokens)
    val writes = row.cacheWriteTokens.coerceIn(0, row.inputTokens - cached)
    val uncached = row.inputTokens - cached - writes
    if (writes > 0 && price?.write == null) return null
    return price?.let { (uncached * it.input + writes * (it.write ?: 0.0)) / 1_000_000.0 }
}

/**
 * The price saved with the request when its endpoint matched the catalog exactly; otherwise the
 * catalog's list price for the model (custom endpoints and proxies never match exactly), and
 * nothing at all when the catalog does not know the model. Local models are free.
 */
internal fun cachePricesFor(row: UsageEventEntity): ModelsDev.CachePrices? {
    row.inputPrice?.let { return ModelsDev.CachePrices(it, row.cacheReadPrice, row.cacheWritePrice) }
    if (com.androidharness.app.local.LocalModelCatalog.isLocal(row.model)) return ModelsDev.CachePrices(0.0, 0.0, 0.0)
    val cost = ModelsDev.findCost(null, row.model) ?: return null
    return ModelsDev.CachePrices(cost.input, cost.cacheRead, cost.cacheWrite)
}

private fun cacheMoney(cost: Double): String = when {
    cost == 0.0 -> "$0"
    cost < 0.0001 -> "<$0.0001"
    else -> String.format(Locale.US, "$%.4f", cost)
}

/**
 * Turn footer in Material 3 Expressive style: a speed pill and a tonal cache pill whose
 * ring fills to the hit rate while the percentage rolls up to it. Tapping springs the
 * pill open into a panel with the cost, a wavy meter and one bar per request, which
 * cascade in one after another.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CacheUsageFooter(rows: List<UsageEventEntity>, running: Boolean = false, speed: TurnSpeed? = null) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val speedLabel = turnPerformanceLabel(null, speed?.copy(firstTokenMs = null))
    if (rows.isEmpty() && speedLabel.isBlank()) return
    var expanded by remember { mutableStateOf(false) }
    val summary = remember(rows) { cacheUsageSummary(rows) }
    val indicator = remember(rows, running) { cacheIndicatorSummary(rows, running) }
    val prices = remember(rows) { rows.map(::cachePricesFor) }
    val approximate = remember(rows) { rows.any { it.inputPrice == null } }
    val costs = rows.mapIndexed { index, row ->
        if (summary.cached > 0) cacheReadCost(row, prices[index]) else cacheMissCost(row, prices[index])
    }
    val total = if (costs.all { it != null }) costs.filterNotNull().sum() else null
    val scheme = MaterialTheme.colorScheme
    val successColor = LocalStatusColors.current.success
    val hit = indicator.rate != null && indicator.cached > 0
    val missed = indicator.rate != null && indicator.cached == 0L
    val rate = (indicator.rate ?: 0.0).toFloat() / 100f
    // Fills from empty the first time it shows, then glides to each new request's rate.
    val ring = remember { Animatable(0f) }
    LaunchedEffect(rate) { ring.animateTo(rate, spring(dampingRatio = 0.7f, stiffness = 120f)) }
    val container by animateColorAsState(when {
        expanded -> scheme.secondaryContainer
        missed -> scheme.errorContainer.copy(alpha = 0.6f)
        else -> scheme.surfaceContainerHigh
    }, spring(stiffness = 500f), label = "Cache pill color")
    val onContainer = when {
        expanded -> scheme.onSecondaryContainer
        missed -> scheme.onErrorContainer
        else -> scheme.onSurfaceVariant
    }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "Cache chevron")
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val squish by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.5f, stiffness = 700f), label = "Cache pill press")
    val corner by animateDpAsState(if (pressed) 10.dp else 50.dp, spring(dampingRatio = 0.6f, stiffness = 600f), label = "Cache pill corner")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (speedLabel.isNotBlank()) {
                Row(
                    Modifier.background(scheme.surfaceContainerHigh, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.Filled.Bolt, null, Modifier.size(13.dp), tint = scheme.primary)
                    Text(speedLabel, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .graphicsLayer { scaleX = squish; scaleY = squish }
                    .clip(RoundedCornerShape(corner))
                    .background(container)
                    .clickable(interactionSource = interaction, indication = ripple(), enabled = rows.isNotEmpty()) { expanded = !expanded }
                    .padding(start = 6.dp, end = if (rows.isNotEmpty()) 6.dp else 10.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (indicator.rate != null) CacheRing({ ring.value }, if (hit) successColor else scheme.error, scheme.outlineVariant.copy(alpha = .5f),
                    hit, Modifier.size(20.dp).semantics {
                        contentDescription = uiContext.getString(if (hit) R.string.ui_cache_hit else R.string.ui_cache_missed)
                    })
                Text(
                    when {
                        indicator.rate == null -> indicator.label
                        missed -> uiContext.getString(R.string.ui_cache_missed)
                        else -> uiContext.getString(R.string.ui_cache_hit) + " · " +
                            (if (ring.value >= rate - 0.0005f) cachePercentage(indicator.cached, indicator.input)
                             else String.format(Locale.US, "%.1f", ring.value * 100f)) + "%"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = onContainer,
                    maxLines = 1,
                )
                androidx.compose.animation.AnimatedVisibility(running,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandHorizontally(),
                    exit = androidx.compose.animation.fadeOut(tween(90)) + androidx.compose.animation.shrinkHorizontally(tween(160))) {
                    Text(uiContext.getString(R.string.ui_last_completed).trimStart(' ', '·').trim(),
                        style = MaterialTheme.typography.labelSmall, color = onContainer.copy(alpha = .7f), maxLines = 1)
                }
                if (rows.isNotEmpty()) Box(Modifier.size(22.dp).background(scheme.surfaceContainerHighest.copy(alpha = .7f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ExpandMore,
                        if (expanded) uiContext.getString(R.string.ui_hide_cache_details) else uiContext.getString(R.string.ui_show_cache_details),
                        Modifier.size(16.dp).graphicsLayer { rotationZ = chevron }, tint = onContainer)
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(expanded,
            enter = androidx.compose.animation.fadeIn(tween(180)) +
                androidx.compose.animation.expandVertically(spring(dampingRatio = 0.7f, stiffness = 380f), expandFrom = Alignment.Top) +
                androidx.compose.animation.scaleIn(spring(dampingRatio = 0.6f, stiffness = 420f), initialScale = 0.92f,
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)),
            // Closing is quick and decisive; only opening gets the springy overshoot.
            exit = androidx.compose.animation.fadeOut(tween(90)) +
                androidx.compose.animation.shrinkVertically(tween(170, easing = androidx.compose.animation.core.FastOutLinearInEasing), shrinkTowards = Alignment.Top) +
                androidx.compose.animation.scaleOut(tween(120), targetScale = 0.96f,
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f))) {
            Surface(color = scheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (summary.rate == null) "–" else cachePercentage(summary.cached, summary.input) + "%",
                            style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = if (summary.cached > 0) successColor else scheme.error)
                        Text(uiContext.getString(R.string.ui_of_input_tokens_served_from_cache, (summary.cached).toString(), (summary.input).toString()),
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
                    }
                    val meter = remember { Animatable(0f) }
                    val target = ((summary.rate ?: 0.0) / 100.0).toFloat()
                    LaunchedEffect(target) { meter.animateTo(target, spring(dampingRatio = 0.75f, stiffness = 90f)) }
                    LinearWavyProgressIndicator(progress = { meter.value }, modifier = Modifier.fillMaxWidth(),
                        color = if (summary.cached > 0) successColor else scheme.error, trackColor = scheme.surfaceContainerHighest)
                    if (running) Text(uiContext.getString(R.string.ui_last_completed_request, (indicator.label).toString()),
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    val costLabel = if (summary.cached > 0) uiContext.getString(R.string.ui_cache_hit_cost_this_turn) else uiContext.getString(R.string.ui_input_cost_this_turn)
                    Row(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(costLabel, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(total?.let { "est. ${cacheMoney(it)}" } ?: uiContext.getString(R.string.ui_unavailable),
                            style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                    }
                    if (approximate && total != null) Text(uiContext.getString(R.string.code_cache_catalog_price),
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    Text(if (summary.cached > 0) uiContext.getString(R.string.ui_estimated_cached_reads_only_excluding_output_and)
                        else uiContext.getString(R.string.ui_estimated_uncached_input_and_cache_writes_excluding),
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    if (summary.unknown > 0) Text(uiContext.getString(R.string.ui_requests_did_not_report_cache_usage_excluded, (summary.unknown).toString()),
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    if (rows.size > 1) rows.forEachIndexed { i, r -> CacheRequestRow(i, r, successColor) }
                }
            }
        }
    }
}

/** Ring that sweeps to the hit rate, with a check or cross that pops in once it lands. */
@Composable
private fun CacheRing(progress: () -> Float, color: androidx.compose.ui.graphics.Color, track: androidx.compose.ui.graphics.Color,
    hit: Boolean, modifier: Modifier) {
    val pop = remember(hit) { Animatable(0f) }
    LaunchedEffect(hit) { kotlinx.coroutines.delay(250); pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.5.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawArc(color, -90f, 360f * progress().coerceIn(0f, 1f), false, topLeft, arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Icon(if (hit) Icons.Filled.Check else Icons.Filled.Close, null,
            Modifier.size(11.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }, tint = color)
    }
}

/** One request: a bar of its cached share that grows in, each row arriving a beat after the last. */
@Composable
private fun CacheRequestRow(index: Int, row: UsageEventEntity, success: androidx.compose.ui.graphics.Color) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val valid = validCacheUsage(row)
    val share = if (valid) row.cachedTokens.toFloat() / row.inputTokens.coerceAtLeast(1) else 0f
    val arrive = remember { Animatable(0f) }
    val fill = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(40L * index.coerceAtMost(12))
        arrive.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 420f))
    }
    LaunchedEffect(share) {
        kotlinx.coroutines.delay(40L * index.coerceAtMost(12) + 80)
        fill.animateTo(share, spring(dampingRatio = 0.8f, stiffness = 160f))
    }
    Column(Modifier.fillMaxWidth().graphicsLayer {
        alpha = arrive.value
        translationY = (1f - arrive.value) * 10.dp.toPx()
    }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(uiContext.getString(R.string.ui_request, (index + 1).toString()), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f))
            Text(
                if (!valid) uiContext.getString(R.string.ui_unavailable) else "${row.cachedTokens} cached",
                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
            )
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(scheme.surfaceContainerHighest)) {
            if (valid) Box(Modifier.fillMaxHeight().fillMaxWidth(fill.value.coerceIn(0f, 1f)).clip(CircleShape)
                .background(if (row.cachedTokens > 0) success else scheme.error))
        }
    }
}
