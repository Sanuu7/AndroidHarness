package com.androidharness.app.reminders

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID

@Serializable
enum class ReminderRepeat { NONE, DAILY, WEEKLY }

/**
 * A reminder the user asked for in Chat. [localAt] is wall-clock time, so a
 * 9:00 reminder stays at 9:00 after a time zone or daylight saving change.
 */
@Serializable
data class Reminder(
    val id: String = UUID.randomUUID().toString().replace("-", "").take(8),
    val text: String,
    val localAt: String,
    val repeat: ReminderRepeat = ReminderRepeat.NONE,
    val sessionId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** When a one-time reminder went off. It stays a while so Snooze still has its text. */
    val firedAt: Long? = null,
) {
    val at: LocalDateTime get() = LocalDateTime.parse(localAt)
    val pending: Boolean get() = firedAt == null

    fun millis(zone: ZoneId = ZoneId.systemDefault()): Long = at.atZone(zone).toInstant().toEpochMilli()

    companion object {
        const val MAX_TEXT = 500
    }
}

object ReminderTime {
    private val whenFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH)

    fun format(at: LocalDateTime): String = whenFormat.format(at)

    fun format(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone))

    fun describe(reminder: Reminder): String = when (reminder.repeat) {
        ReminderRepeat.NONE -> format(reminder.at)
        ReminderRepeat.DAILY -> "Every day at %02d:%02d, next ${format(reminder.at)}".format(reminder.at.hour, reminder.at.minute)
        ReminderRepeat.WEEKLY -> "Every ${reminder.at.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} at " +
            "%02d:%02d, next ${format(reminder.at)}".format(reminder.at.hour, reminder.at.minute)
    }

    /** The first occurrence of a repeating reminder strictly after [now]. One-time reminders have none. */
    fun nextAfter(reminder: Reminder, now: LocalDateTime): LocalDateTime? {
        val step = when (reminder.repeat) {
            ReminderRepeat.NONE -> return null
            ReminderRepeat.DAILY -> 1L
            ReminderRepeat.WEEKLY -> 7L
        }
        var next = reminder.at
        if (next.isAfter(now)) return next
        // Jump close in one step, then walk the last few, so a reminder missed for months is cheap.
        val days = java.time.Duration.between(next, now).toDays()
        next = next.plusDays(days / step * step)
        while (!next.isAfter(now)) next = next.plusDays(step)
        return next
    }

    /**
     * Turns the tool's arguments into a local time. [day] is today, tomorrow,
     * a weekday name or YYYY-MM-DD; without it the next [time] is used.
     */
    fun resolve(now: LocalDateTime, inMinutes: Int?, day: String?, time: String?, repeat: ReminderRepeat): LocalDateTime {
        if (inMinutes != null) {
            if (repeat != ReminderRepeat.NONE) throw IllegalArgumentException("in_minutes only works for a one-time reminder. Use time instead.")
            if (inMinutes < 1) throw IllegalArgumentException("in_minutes must be at least 1")
            if (inMinutes > 60 * 24 * 366) throw IllegalArgumentException("in_minutes is too far ahead. Use day and time instead.")
            return now.withSecond(0).withNano(0).plusMinutes(inMinutes.toLong())
        }
        val clock = time?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Give time as HH:MM, or in_minutes for a one-time reminder.")
        val localTime = try { LocalTime.parse(clock.padStart(5, '0')) }
            catch (_: Exception) { throw IllegalArgumentException("time must look like 08:30 in 24-hour form") }
        val today = now.toLocalDate()
        val text = day?.trim()?.lowercase(Locale.ENGLISH)?.takeIf { it.isNotEmpty() }
        val weekday = text?.let { name -> DayOfWeek.entries.firstOrNull { it.name.lowercase() == name || it.name.lowercase().take(3) == name } }
        val date = when {
            text == null -> if (localTime.isAfter(now.toLocalTime())) today else today.plusDays(1)
            text == "today" -> today
            text == "tomorrow" -> today.plusDays(1)
            weekday != null -> {
                val candidate = today.with(TemporalAdjusters.nextOrSame(weekday))
                if (candidate == today && !localTime.isAfter(now.toLocalTime())) candidate.plusDays(7) else candidate
            }
            else -> try { java.time.LocalDate.parse(text) }
                catch (_: Exception) { throw IllegalArgumentException("day must be today, tomorrow, a weekday name or YYYY-MM-DD") }
        }
        if (repeat == ReminderRepeat.WEEKLY && text == null)
            throw IllegalArgumentException("A weekly reminder needs day set to a weekday, for example monday.")
        var at = date.atTime(localTime)
        if (!at.isAfter(now)) {
            if (repeat == ReminderRepeat.NONE)
                throw IllegalArgumentException("That time has already passed. It is now ${format(now)}.")
            at = nextAfter(Reminder(text = "", localAt = at.toString(), repeat = repeat), now)!!
        }
        if (at.isAfter(now.plusYears(5))) throw IllegalArgumentException("That is more than five years away.")
        return at
    }
}
