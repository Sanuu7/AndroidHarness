package com.androidharness.app.ui.stats

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.data.SessionRepository
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.data.db.UsageEventEntity
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private suspend fun seedFiveDays(db: AppDatabase, now: Long) {
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        db.dao().insertSession(SessionEntity("chat", "Five-day chat", now - 5 * 86400000L, now,
            totalInputTokens = 4010, totalOutputTokens = 401, totalCachedTokens = 2000, requestCount = 5))
        // A recently touched legacy chat has no request dates and cannot be placed in Today.
        db.dao().insertSession(SessionEntity("legacy", "Undated history", 1, now,
            totalInputTokens = 9000, totalOutputTokens = 900, requestCount = 12))
        db.dao().insertUsageEvents((0..4).map { daysAgo ->
            UsageEventEntity(sessionId = "chat", providerName = "Fixture", model = "fixture-main",
                inputTokens = if (daysAgo == 0) 10 else 1000,
                outputTokens = if (daysAgo == 0) 1 else 100,
                cachedTokens = if (daysAgo == 0) 0 else 500,
                createdAt = today.minusDays(daysAgo.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 1,
                turnId = "day-$daysAgo", cacheReported = true)
        })
    }

    @Test fun requestDatesSurviveChatTouchesAndDatabaseReopen() = runBlocking {
        val name = "stats-dates-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val now = System.currentTimeMillis()
        try {
            seedFiveDays(db, now)
            val repository = SessionRepository(db)
            val day = StatsRange.DAY.window(now)
            val week = StatsRange.WEEK.window(now)
            val today = statsSnapshot(repository.statsUsageBetween(day.since, day.until).first())
            val weekly = statsSnapshot(repository.statsUsageBetween(week.since, week.until).first())
            assertEquals(11L, today.bundle.input + today.bundle.output)
            assertEquals(1L, today.bundle.requests)
            assertEquals(0L, today.bundle.cached)
            assertEquals(4411L, weekly.bundle.input + weekly.bundle.output)
            assertEquals(5L, weekly.bundle.requests)
            assertEquals(2000L, weekly.bundle.cached)
            assertEquals(weekly.bundle.input, weekly.byModel.sumOf { it.inputTokens })
            assertEquals(weekly.bundle.requests, weekly.byModel.sumOf { it.requests })
            repository.renameSession("chat", "Continued today")
            assertEquals(today, statsSnapshot(repository.statsUsageBetween(day.since, day.until).first()))
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(today, statsSnapshot(db.dao().statsUsageBetween(day.since, day.until).first()))
            assertEquals(weekly, statsSnapshot(db.dao().statsUsageBetween(week.since, week.until).first()))
            val all = db.dao().statsUsageBetween(0, Long.MAX_VALUE).first()
            val lifetime = statsSnapshot(all, db.dao().sessionsFlow().first())
            assertEquals(14311L, lifetime.bundle.input + lifetime.bundle.output)
            assertEquals(17L, lifetime.bundle.requests)
            assertEquals(2, lifetime.bundle.sessionCount)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun sqliteWindowIncludesStartAndExcludesEndExactly() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            for (time in listOf(999L, 1000L, 1999L, 2000L)) {
                db.dao().insertUsageEvent(UsageEventEntity(sessionId = "chat", providerName = "P", model = "M",
                    inputTokens = time, outputTokens = 1, createdAt = time))
            }
            val row = db.dao().statsUsageBetween(1000, 2000).first().single()
            assertEquals(2L, row.requests)
            assertEquals(2999L, row.inputTokens)
        } finally { db.close() }
    }

    @Test fun statsScreenFiltersFiveDayChatAndUpdatesNewRequestsLive(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val container = (context.applicationContext as HarnessApp).container
        val saved = container.settings.settings.first()
        val now = System.currentTimeMillis()
        seedFiveDays(db, now)
        val repository = SessionRepository(db)
        container.settings.setOnboardingDone(true)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme { StatsScreen(repository, {}) } } }
            awaitSummary(5, 1)
            tap("Today"); awaitSummary(1, 1); awaitText("11")
            repository.renameSession("chat", "Renamed today")
            awaitSummary(1, 1); awaitText("11")
            screenshot("stats-today-five-day-chat")
            tap("1 month"); awaitSummary(5, 1)
            tap("Lifetime"); awaitSummary(17, 2)
            tap("1 week"); awaitSummary(5, 1)
            tap("Today"); awaitSummary(1, 1)
            repository.recordUsage("chat", "Fixture", "fixture-subagent", 20, 2, 0, 0, "new-turn", true)
            repository.addUsage("chat", 20, 2, 0)
            awaitSummary(2, 1); awaitText("33")
            screenshot("stats-today-new-request")
            tap("1 week"); awaitSummary(6, 1)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            container.settings.setOnboardingDone(saved.onboardingDone)
            db.close()
        }
    }

    private fun find(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (match(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun await(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            find(match)?.let { return it }; SystemClock.sleep(100)
        }
        screenshot("stats-missing-node")
        fun describe(node: AccessibilityNodeInfo) {
            node.text?.let { println("STATS_UI_TEXT: $it") }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::describe)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::describe)
        error("Missing Stats UI node")
    }
    private fun awaitSummary(requests: Int, sessions: Int) = await {
        it.text?.toString()?.contains("total tokens · $requests requests · $sessions sessions") == true
    }
    private fun awaitText(text: String) = await { it.text?.toString() == text }
    private fun tap(text: String) {
        var node = awaitText(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(150)
    }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
