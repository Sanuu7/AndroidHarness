package com.androidharness.app.reminders

import com.androidharness.app.core.ChatExperience
import com.androidharness.app.tools.ReminderCancelTool
import com.androidharness.app.tools.ReminderListTool
import com.androidharness.app.tools.ReminderSetTool
import com.androidharness.app.tools.ReminderTools
import com.androidharness.app.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime

class ReminderTest {
    @get:Rule val folder = TemporaryFolder()

    // A Thursday afternoon.
    private val now = LocalDateTime.parse("2026-11-19T14:30")

    private fun resolve(inMinutes: Int? = null, day: String? = null, time: String? = null, repeat: ReminderRepeat = ReminderRepeat.NONE) =
        ReminderTime.resolve(now, inMinutes, day, time, repeat)

    @Test fun relativeAndClockTimesResolveToTheNextMatchingMinute() {
        assertEquals(LocalDateTime.parse("2026-11-19T16:30"), resolve(inMinutes = 120))
        assertEquals(LocalDateTime.parse("2026-11-19T18:00"), resolve(time = "18:00"))
        // 09:00 already passed today, so it means tomorrow.
        assertEquals(LocalDateTime.parse("2026-11-20T09:00"), resolve(time = "9:00"))
        assertEquals(LocalDateTime.parse("2026-11-20T09:00"), resolve(day = "tomorrow", time = "09:00"))
        assertEquals(LocalDateTime.parse("2026-11-23T08:00"), resolve(day = "Monday", time = "08:00"))
        assertEquals(LocalDateTime.parse("2026-12-01T10:15"), resolve(day = "2026-12-01", time = "10:15"))
    }

    @Test fun pastAndMalformedTimesAreRefusedWithAReason() {
        assertTrue(runCatching { resolve(day = "today", time = "09:00") }.exceptionOrNull()!!.message!!.contains("already passed"))
        assertThrows(IllegalArgumentException::class.java) { resolve(time = "25:00") }
        assertThrows(IllegalArgumentException::class.java) { resolve(day = "someday", time = "10:00") }
        assertThrows(IllegalArgumentException::class.java) { resolve(inMinutes = 0) }
        assertThrows(IllegalArgumentException::class.java) { resolve(inMinutes = 5, repeat = ReminderRepeat.DAILY) }
        assertThrows(IllegalArgumentException::class.java) { resolve(time = "08:00", repeat = ReminderRepeat.WEEKLY) }
    }

    @Test fun thisWeekdayLaterTodayStaysTodayButEarlierMovesAWeek() {
        assertEquals(LocalDateTime.parse("2026-11-19T17:00"), resolve(day = "thu", time = "17:00"))
        assertEquals(LocalDateTime.parse("2026-11-26T08:00"), resolve(day = "thursday", time = "08:00"))
    }

    @Test fun repeatingRemindersRollForwardPastMissedOccurrences() {
        val daily = Reminder(text = "Pills", localAt = "2026-01-01T08:00", repeat = ReminderRepeat.DAILY)
        assertEquals(LocalDateTime.parse("2026-11-20T08:00"), ReminderTime.nextAfter(daily, now))
        val weekly = Reminder(text = "Bins", localAt = "2026-11-05T19:00", repeat = ReminderRepeat.WEEKLY)
        assertEquals(LocalDateTime.parse("2026-11-19T19:00"), ReminderTime.nextAfter(weekly, now))
        assertNull(ReminderTime.nextAfter(daily.copy(repeat = ReminderRepeat.NONE), now))
        // A daily time that passed today starts tomorrow instead of failing.
        assertEquals(LocalDateTime.parse("2026-11-20T08:00"), resolve(time = "08:00", day = "today", repeat = ReminderRepeat.DAILY))
    }

    @Test fun storeSurvivesReloadAndDropsOldFiredReminders() = runBlocking {
        val file = folder.newFile("reminders.json").also { it.delete() }
        var clock = 1_000_000L
        val store = ReminderStore(file) { clock }
        store.add(Reminder(id = "a", text = "One", localAt = "2026-11-20T09:00"))
        store.add(Reminder(id = "b", text = "Two", localAt = "2026-11-20T10:00", firedAt = clock))
        assertEquals(listOf("a", "b"), ReminderStore(file) { clock }.all().map { it.id })
        clock += ReminderStore.KEEP_FIRED_MS + 1
        assertEquals(listOf("a"), ReminderStore(file) { clock }.all().map { it.id })
        assertEquals("One", store.remove("a")?.text)
        assertNull(store.remove("missing"))
    }

    private class FakeBackend(var exact: Boolean = true, var notifications: Boolean = true) : ReminderBackend {
        val saved = mutableListOf<Reminder>()
        override suspend fun add(reminder: Reminder) = reminder.also { saved += it }
        override suspend fun upcoming() = saved.filter { it.pending }
        override suspend fun cancel(id: String) = saved.firstOrNull { it.id == id }?.also { saved.remove(it) }
        override fun exactTiming() = exact
        override fun notificationsAllowed() = notifications
    }

    @Test fun toolsSetListAndCancelAndWarnAboutLateOrHiddenReminders() = runBlocking {
        val backend = FakeBackend(exact = false, notifications = false)
        val ctx = ToolContext(com.androidharness.app.workspace.FileFs(folder.root), sessionId = "chat-1")
        val set = ReminderSetTool { backend }
        val args = buildJsonObject { put("text", "Call the bank"); put("in_minutes", JsonPrimitive(30)) }
        val result = set.execute(args, ctx)
        assertTrue(result.output, result.ok)
        assertTrue(result.output.contains("Notifications are blocked"))
        assertTrue(result.output.contains("a few minutes late"))
        assertEquals("chat-1", backend.saved.single().sessionId)
        assertTrue(set.approvalPreview(args).contains("Call the bank"))
        assertTrue(set.approvalPreview(buildJsonObject { put("text", "x") }).startsWith("Invalid reminder"))

        val id = backend.saved.single().id
        assertTrue(ReminderListTool { backend }.execute(buildJsonObject {}, ctx).output.contains(id))
        assertTrue(ReminderCancelTool { backend }.execute(buildJsonObject { put("id", id) }, ctx).ok)
        assertFalse(ReminderCancelTool { backend }.execute(buildJsonObject { put("id", id) }, ctx).ok)
    }

    @Test fun reminderToolsArePartOfChatOnly() {
        assertTrue(ChatExperience.tools.containsAll(ReminderTools.names))
    }
}

class ClockAlarmToolsTest {
    private class FakeClock(var available: Boolean = true, var foreground: Boolean = true) : ClockBackend {
        val alarms = mutableListOf<Triple<Int, Int, List<java.time.DayOfWeek>>>()
        var timer: Int? = null
        override fun clockAvailable() = available
        override fun inForeground() = foreground
        override fun setAlarm(hour: Int, minute: Int, label: String?, days: List<java.time.DayOfWeek>): String? {
            alarms += Triple(hour, minute, days); return null
        }
        override fun setTimer(seconds: Int, label: String?): String? { timer = seconds; return null }
    }

    private val ctx = ToolContext(com.androidharness.app.workspace.FileFs(java.io.File(".")))

    @Test fun alarmsGoToTheClockAppWithRepeatDays() = runBlocking {
        val clock = FakeClock()
        val tool = com.androidharness.app.tools.AlarmSetTool { clock }
        val ok = tool.execute(buildJsonObject { put("time", "6:30"); put("label", "Gym"); put("days", "weekdays") }, ctx)
        assertTrue(ok.output, ok.ok)
        assertEquals(6, clock.alarms.single().first)
        assertEquals(30, clock.alarms.single().second)
        assertEquals(5, clock.alarms.single().third.size)
        assertTrue(tool.approvalPreview(buildJsonObject { put("time", "07:00") }).contains("Alarm at 07:00, once"))
        assertFalse(tool.execute(buildJsonObject { put("time", "7pm") }, ctx).ok)
        assertFalse(tool.execute(buildJsonObject { put("time", "07:00"); put("days", "funday") }, ctx).ok)
    }

    @Test fun alarmsExplainWhyTheyCannotBeSet() = runBlocking {
        val clock = FakeClock(foreground = false)
        val tool = com.androidharness.app.tools.AlarmSetTool { clock }
        assertTrue(tool.execute(buildJsonObject { put("time", "07:00") }, ctx).output.contains("not on screen"))
        clock.available = false
        assertTrue(tool.execute(buildJsonObject { put("time", "07:00") }, ctx).output.contains("no Clock app"))
        assertTrue(clock.alarms.isEmpty())
    }

    @Test fun timersAddMinutesAndSeconds() = runBlocking {
        val clock = FakeClock()
        val tool = com.androidharness.app.tools.TimerSetTool { clock }
        assertTrue(tool.execute(buildJsonObject { put("minutes", JsonPrimitive(2)); put("seconds", JsonPrimitive(30)) }, ctx).ok)
        assertEquals(150, clock.timer)
        assertFalse(tool.execute(buildJsonObject {}, ctx).ok)
    }
}
