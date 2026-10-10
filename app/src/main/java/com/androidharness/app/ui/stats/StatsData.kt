package com.androidharness.app.ui.stats

import com.androidharness.app.data.db.ModelUsagePojo
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.data.db.StatsUsagePojo
import java.time.Instant
import java.time.ZoneId

internal data class StatsWindow(val since: Long, val until: Long)

internal enum class StatsRange(val label: String, private val days: Long?) {
    DAY("Today", 1),
    WEEK("1 week", 7),
    MONTH("1 month", 30),
    LIFETIME("Lifetime", null);

    fun window(now: Long, zone: ZoneId = ZoneId.systemDefault()): StatsWindow {
        val count = days ?: return StatsWindow(0, Long.MAX_VALUE)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return StatsWindow(today.minusDays(count - 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    }
}

internal data class StatsBundle(
    val input: Long = 0,
    val output: Long = 0,
    val cached: Long = 0,
    val cacheWrite: Long = 0,
    val requests: Long = 0,
    val sessionCount: Int = 0,
    /** Best session cache hit rate from usage in the selected window. */
    val peakHitRate: Double? = null,
) {
    val freshInput: Long get() = (input - cached - cacheWrite).coerceAtLeast(0)
}

internal data class StatsSnapshot(val bundle: StatsBundle = StatsBundle(), val byModel: List<ModelUsagePojo> = emptyList())

/** The hero, breakdown and cache metrics share one dated request snapshot. */
internal fun statsSnapshot(rows: List<StatsUsagePojo>, lifetimeSessions: List<SessionEntity>? = null): StatsSnapshot {
    // Older versions saved only session totals. Keep them in Lifetime without
    // inventing dates for those requests or attributing them to a recent chat touch.
    val totals = lifetimeSessions?.map { s ->
        StatsUsagePojo(s.id, "", "", s.totalInputTokens, s.totalOutputTokens,
            s.totalCachedTokens, s.totalCacheWriteTokens, s.requestCount)
    } ?: rows
    val sessionTotals = totals.groupBy { it.sessionId }
    val bundle = StatsBundle(totals.sumOf { it.inputTokens }, totals.sumOf { it.outputTokens },
        totals.sumOf { it.cachedTokens }, totals.sumOf { it.cacheWriteTokens }, totals.sumOf { it.requests },
        sessionTotals.size, sessionTotals.values.mapNotNull { session ->
            val input = session.sumOf { it.inputTokens }
            if (input > 0) session.sumOf { it.cachedTokens }.toDouble() / input else null
        }.maxOrNull())
    val models = rows.groupBy { it.providerName to it.model }.map { (identity, modelRows) ->
        ModelUsagePojo(identity.first, identity.second, modelRows.sumOf { it.inputTokens },
            modelRows.sumOf { it.outputTokens }, modelRows.sumOf { it.cachedTokens },
            modelRows.sumOf { it.cacheWriteTokens }, modelRows.sumOf { it.requests })
    }.sortedByDescending { it.totalTokens }
    return StatsSnapshot(bundle, models)
}
