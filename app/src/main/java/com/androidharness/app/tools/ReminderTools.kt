package com.androidharness.app.tools

import com.androidharness.app.reminders.ClockBackend
import com.androidharness.app.reminders.Reminder
import com.androidharness.app.reminders.ReminderBackend
import com.androidharness.app.reminders.ReminderRepeat
import com.androidharness.app.reminders.ReminderTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** Chat-only tools that set, list and cancel reminders shown as phone notifications. */
object ReminderTools {
    const val SET = "reminder_set"
    const val LIST = "reminder_list"
    const val CANCEL = "reminder_cancel"
    const val ALARM = "alarm_set"
    const val TIMER = "timer_set"
    val names = setOf(SET, LIST, CANCEL, ALARM, TIMER)
    /** Tools that need the user's approval card every time. */
    val confirmed = setOf(SET, ALARM, TIMER)

    fun all(backend: () -> ReminderBackend, clock: () -> ClockBackend): List<Tool> =
        listOf(ReminderSetTool(backend), ReminderListTool(backend), ReminderCancelTool(backend), AlarmSetTool(clock), TimerSetTool(clock))

    internal fun timingNote(backend: ReminderBackend): String = buildString {
        if (!backend.notificationsAllowed()) append(" Notifications are blocked for AndroidHarness, so the user will not see it " +
            "until they allow notifications in Android settings. Tell them.")
        if (!backend.exactTiming()) append(" On-time reminders are off, so Android may show it a few minutes late. " +
            "The user can turn them on in Settings, Chat, Reminders.")
    }
}

/**
 * Sets a reminder. Every call is shown to the user for approval, because a web
 * page or file read in the chat must never be able to plant reminders.
 */
class ReminderSetTool(private val backend: () -> ReminderBackend) : Tool {
    override val name = ReminderTools.SET
    override val description = "Set a reminder that appears as a notification on this phone at a local time, once, " +
        "every day or every week. Use it when the user asks to be reminded about something. For something that should ring, " +
        "like a wake-up alarm, use alarm_set instead, and timer_set for a countdown. Write text the way the user " +
        "should read it when it goes off. For a relative time like in 2 hours use in_minutes; otherwise give time and, " +
        "if it is not the next occurrence, day. The user approves each reminder before it is saved."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(
        mapOf(
            "text" to Schema.string("What to remind the user about, as they should read it. For example: Call the bank about the card."),
            "in_minutes" to Schema.integer("For a one-time reminder this many minutes from now. Use instead of day and time."),
            "time" to Schema.string("Local time as HH:MM in 24-hour form, for example 09:00."),
            "day" to Schema.string("Optional: today, tomorrow, a weekday name such as monday, or a date as YYYY-MM-DD. " +
                "Leave it out for the next time the clock shows time. Required for weekly."),
            "repeat" to Schema.string("One of: none, daily, weekly. Default none."),
        ),
        required = listOf("text"),
    )

    internal fun draft(args: JsonObject, now: LocalDateTime, sessionId: String?): Reminder {
        val text = args.text("text") ?: throw IllegalArgumentException("text is required")
        val repeat = when (args.text("repeat")?.lowercase() ?: "none") {
            "none", "once" -> ReminderRepeat.NONE
            "daily", "day", "every day" -> ReminderRepeat.DAILY
            "weekly", "week", "every week" -> ReminderRepeat.WEEKLY
            else -> throw IllegalArgumentException("repeat must be none, daily or weekly")
        }
        val at = ReminderTime.resolve(now, args["in_minutes"]?.jsonPrimitive?.intOrNull, args.text("day"), args.text("time"), repeat)
        return Reminder(text = text.take(Reminder.MAX_TEXT), localAt = at.toString(), repeat = repeat, sessionId = sessionId)
    }

    /** What the user sees on the approval card. */
    fun approvalPreview(args: JsonObject): String = try {
        val reminder = draft(args, LocalDateTime.now(), null)
        "${ReminderTime.describe(reminder)}\n\n${reminder.text}" + userNote().let { if (it.isEmpty()) "" else "\n\n$it" }
    } catch (e: IllegalArgumentException) {
        "Invalid reminder: ${e.message}\n\n${args.text("text").orEmpty().take(Reminder.MAX_TEXT)}"
    }

    private fun userNote(): String = buildList {
        if (!backend().notificationsAllowed()) add("Notifications are blocked for AndroidHarness, so this will not show until you allow them.")
        if (!backend().exactTiming()) add("On-time reminders are off, so this may show a few minutes late. Turn them on in Settings, Chat, Reminders.")
    }.joinToString("\n")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val reminder = try { draft(args, LocalDateTime.now(), ctx.sessionId) }
            catch (e: IllegalArgumentException) { return ToolResult(false, e.message ?: "Invalid reminder") }
        return try {
            val saved = backend().add(reminder)
            ToolResult(true, "Reminder set (id ${saved.id}): ${ReminderTime.describe(saved)}. \"${saved.text}\"." +
                ReminderTools.timingNote(backend()))
        } catch (e: IllegalStateException) {
            ToolResult(false, e.message ?: "Could not save the reminder")
        }
    }
}

class ReminderListTool(private val backend: () -> ReminderBackend) : Tool {
    override val name = ReminderTools.LIST
    override val description = "List the user's upcoming reminders with their ids and times, and the current local time. " +
        "Use it before cancelling one, or when the user asks what reminders they have."
    override val isReadOnly = true
    override val parametersSchema = Schema.obj(emptyMap())

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val now = "It is now ${ReminderTime.format(LocalDateTime.now())}."
        val upcoming = backend().upcoming()
        if (upcoming.isEmpty()) return ToolResult(true, "$now No reminders are set.")
        return ToolResult(true, now + "\n" + upcoming.joinToString("\n") { "- ${it.id}: ${ReminderTime.describe(it)}. \"${it.text}\"" })
    }
}

class ReminderCancelTool(private val backend: () -> ReminderBackend) : Tool {
    override val name = ReminderTools.CANCEL
    override val description = "Cancel a reminder by id (from reminder_list). A repeating reminder stops for good."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(mapOf("id" to Schema.string("The reminder id.")), required = listOf("id"))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = args.text("id") ?: return ToolResult(false, "id is required")
        val removed = backend().cancel(id) ?: return ToolResult(false, "No reminder has id $id. Call reminder_list.")
        return ToolResult(true, "Cancelled the reminder \"${removed.text}\".")
    }
}

/** Shared by the alarm and timer tools: the Clock app must exist and AndroidHarness must be on screen. */
private fun clockProblem(clock: ClockBackend): String? = when {
    !clock.clockAvailable() -> "This phone has no Clock app that accepts alarms or timers. Offer a reminder with reminder_set instead."
    !clock.inForeground() -> "AndroidHarness is not on screen, and Android only lets it open the Clock app while it is. " +
        "Ask the user to open the chat and try again, or offer a reminder with reminder_set."
    else -> null
}

/** Sets a real alarm in the phone's Clock app. Confirmed by the user every time. */
class AlarmSetTool(private val clock: () -> ClockBackend) : Tool {
    override val name = ReminderTools.ALARM
    override val description = "Set a real alarm in the phone's Clock app, which rings like any alarm the user sets by hand. " +
        "Use it for wake-up alarms or whenever the user asks for an alarm. It can be one-time (the next time the clock shows time) " +
        "or repeat on chosen weekdays. Once set, the Clock app owns it: you cannot list, change or delete it, so tell the user " +
        "to do that in the Clock app. The user approves each alarm first."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(
        mapOf(
            "time" to Schema.string("Local time as HH:MM in 24-hour form, for example 06:30."),
            "label" to Schema.string("Optional short label shown when the alarm rings, for example Gym."),
            "days" to Schema.string("Optional weekdays it repeats on, comma separated, for example mon,tue,wed,thu,fri. " +
                "Leave out for a one-time alarm."),
        ),
        required = listOf("time"),
    )

    internal data class Draft(val time: LocalTime, val label: String?, val days: List<DayOfWeek>)

    internal fun draft(args: JsonObject): Draft {
        val raw = args.text("time") ?: throw IllegalArgumentException("time is required, as HH:MM")
        val time = try { LocalTime.parse(raw.padStart(5, '0')) }
            catch (_: Exception) { throw IllegalArgumentException("time must look like 06:30 in 24-hour form") }
        val days = args.text("days")?.split(',', ' ')?.map { it.trim().lowercase(Locale.ENGLISH) }?.filter { it.isNotEmpty() }?.map { name ->
            when (name) {
                "weekdays" -> listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                "weekends", "weekend" -> listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                "everyday", "daily" -> DayOfWeek.entries
                else -> listOf(DayOfWeek.entries.firstOrNull { it.name.lowercase() == name || it.name.lowercase().take(3) == name.take(3) && name.length >= 3 }
                    ?: throw IllegalArgumentException("Unknown day \"$name\". Use names like mon or monday."))
            }
        }?.flatten()?.distinct()?.sorted().orEmpty()
        return Draft(time, args.text("label")?.take(80), days)
    }

    internal fun describe(draft: Draft): String {
        val clock = "%02d:%02d".format(draft.time.hour, draft.time.minute)
        val repeat = if (draft.days.isEmpty()) "once" else "every " + draft.days.joinToString(", ") {
            it.name.lowercase().replaceFirstChar { c -> c.uppercase() }.take(3) }
        return "Alarm at $clock, $repeat" + (draft.label?.let { ", \"$it\"" } ?: "") + ". It goes into your Clock app."
    }

    fun approvalPreview(args: JsonObject): String = try { describe(draft(args)) }
        catch (e: IllegalArgumentException) { "Invalid alarm: ${e.message}" }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val draft = try { draft(args) } catch (e: IllegalArgumentException) { return ToolResult(false, e.message ?: "Invalid alarm") }
        val backend = clock()
        clockProblem(backend)?.let { return ToolResult(false, it) }
        backend.setAlarm(draft.time.hour, draft.time.minute, draft.label, draft.days)?.let { return ToolResult(false, it) }
        return ToolResult(true, describe(draft).removeSuffix(" It goes into your Clock app.") + " Sent to the Clock app. " +
            "To change or delete it, the user opens the Clock app.")
    }
}

/** Starts a countdown timer in the phone's Clock app. Confirmed by the user every time. */
class TimerSetTool(private val clock: () -> ClockBackend) : Tool {
    override val name = ReminderTools.TIMER
    override val description = "Start a countdown timer in the phone's Clock app, for example for cooking. " +
        "It rings when it ends. The user approves each timer first."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(
        mapOf(
            "minutes" to Schema.integer("Length in minutes."),
            "seconds" to Schema.integer("Extra seconds, or the whole length for a short timer."),
            "label" to Schema.string("Optional short label, for example Pasta."),
        ),
    )

    internal fun length(args: JsonObject): Int {
        val total = (args["minutes"]?.jsonPrimitive?.intOrNull ?: 0) * 60 + (args["seconds"]?.jsonPrimitive?.intOrNull ?: 0)
        if (total < 1) throw IllegalArgumentException("Give minutes or seconds, at least 1 second.")
        if (total > 24 * 60 * 60) throw IllegalArgumentException("A timer can be at most 24 hours. Use alarm_set for longer.")
        return total
    }

    private fun describe(seconds: Int, label: String?): String {
        val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
        val parts = listOfNotNull(h.takeIf { it > 0 }?.let { "$it h" }, m.takeIf { it > 0 }?.let { "$it min" }, s.takeIf { it > 0 }?.let { "$it s" })
        return "Timer for ${parts.joinToString(" ")}" + (label?.let { ", \"$it\"" } ?: "") + "."
    }

    fun approvalPreview(args: JsonObject): String = try { describe(length(args), args.text("label")) + " It runs in your Clock app." }
        catch (e: IllegalArgumentException) { "Invalid timer: ${e.message}" }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val seconds = try { length(args) } catch (e: IllegalArgumentException) { return ToolResult(false, e.message ?: "Invalid timer") }
        val backend = clock()
        clockProblem(backend)?.let { return ToolResult(false, it) }
        val label = args.text("label")?.take(80)
        backend.setTimer(seconds, label)?.let { return ToolResult(false, it) }
        return ToolResult(true, describe(seconds, label) + " Started in the Clock app.")
    }
}
