package com.androidharness.app.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.androidharness.app.AgentService
import com.androidharness.app.MainActivity
import com.androidharness.app.R
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** What the Chat reminder tools need, so they can be tested without Android. */
interface ReminderBackend {
    suspend fun add(reminder: Reminder): Reminder
    suspend fun upcoming(): List<Reminder>
    suspend fun cancel(id: String): Reminder?
    /** False when Android may deliver reminders a few minutes late. */
    fun exactTiming(): Boolean
    fun notificationsAllowed(): Boolean
}

/**
 * Schedules Chat reminders with AlarmManager and shows them as notifications.
 * Exact alarms are used when the user allows them; otherwise Android may hold
 * a reminder back by a few minutes, which is still better than not showing it.
 */
class ReminderManager(private val context: Context) : ReminderBackend {
    val store = ReminderStore(File(context.filesDir, "reminders.json"))
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    override fun exactTiming(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms?.canScheduleExactAlarms() == true

    override fun notificationsAllowed(): Boolean = notifications?.areNotificationsEnabled() == true &&
        notifications?.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    /** Opens the system page where the user allows on-time reminders, or null below Android 12. */
    fun exactTimingSettingsIntent(): Intent? = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) null else
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override suspend fun add(reminder: Reminder): Reminder {
        store.add(reminder)
        schedule(reminder)
        return reminder
    }

    override suspend fun upcoming(): List<Reminder> = store.all().filter { it.pending }.sortedBy { it.at }

    override suspend fun cancel(id: String): Reminder? {
        alarms?.cancel(alarmIntent(id))
        notifications?.cancel(TAG, id.hashCode())
        return store.remove(id)
    }

    /** Shows a reminder that is due, then moves a repeating one to its next time. */
    suspend fun fire(id: String) {
        val reminder = store.get(id)?.takeIf { it.pending } ?: return
        if (reminder.millis() > System.currentTimeMillis() + EARLY_TOLERANCE_MS) { schedule(reminder); return }
        show(reminder)
        val next = ReminderTime.nextAfter(reminder, LocalDateTime.now())
        if (next == null) store.update(reminder.copy(firedAt = System.currentTimeMillis()))
        else reminder.copy(localAt = next.toString()).also { store.update(it); schedule(it) }
    }

    /** Snoozing a repeating reminder adds a one-time copy, so the next regular time stays where it was. */
    suspend fun snooze(id: String, minutes: Long = SNOOZE_MINUTES) {
        notifications?.cancel(TAG, id.hashCode())
        val reminder = store.get(id) ?: return
        val later = LocalDateTime.now().withSecond(0).withNano(0).plusMinutes(minutes).toString()
        val snoozed = if (reminder.repeat == ReminderRepeat.NONE) reminder.copy(localAt = later, firedAt = null).also { store.update(it) }
            else Reminder(text = reminder.text, localAt = later, sessionId = reminder.sessionId).also { runCatching { store.add(it) } }
        schedule(snoozed)
    }

    suspend fun done(id: String) {
        notifications?.cancel(TAG, id.hashCode())
        val reminder = store.get(id) ?: return
        if (reminder.repeat == ReminderRepeat.NONE && !reminder.pending) store.remove(id)
    }

    /** After a reboot, an app update or a clock change, every alarm is set again and missed ones are shown. */
    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        store.all().filter { it.pending }.forEach { reminder ->
            if (reminder.millis() <= now) fire(reminder.id) else schedule(reminder)
        }
    }

    private fun schedule(reminder: Reminder) {
        val manager = alarms ?: return
        val at = reminder.millis(ZoneId.systemDefault())
        val pending = alarmIntent(reminder.id)
        try {
            if (exactTiming()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (_: SecurityException) {
            // The user took the exact alarm permission back between the check and the call.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun alarmIntent(id: String): PendingIntent = PendingIntent.getBroadcast(
        context, id.hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(action: String, id: String): PendingIntent = PendingIntent.getBroadcast(
        context, (action + id).hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun ensureChannel() {
        notifications?.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.reminders_channel_desc) },
        )
    }

    private fun show(reminder: Reminder) {
        val manager = notifications ?: return
        ensureChannel()
        val open = Intent(context, MainActivity::class.java)
            .apply { reminder.sessionId?.let { putExtra(AgentService.EXTRA_SESSION_ID, it) } }
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.reminders_notification_title))
            .setContentText(reminder.text)
            .setStyle(Notification.BigTextStyle().bigText(reminder.text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, reminder.id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setDeleteIntent(actionIntent(ReminderReceiver.ACTION_DONE, reminder.id))
            .addAction(Notification.Action.Builder(null, context.getString(R.string.reminders_snooze),
                actionIntent(ReminderReceiver.ACTION_SNOOZE, reminder.id)).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.reminders_done),
                actionIntent(ReminderReceiver.ACTION_DONE, reminder.id)).build())
            .build()
        // Posted even when run notifications are off in the app: the user asked for this one directly.
        runCatching { manager.notify(TAG, reminder.id.hashCode(), notification) }
    }

    companion object {
        const val CHANNEL = "reminders"
        private const val TAG = "reminder"
        const val SNOOZE_MINUTES = 10L
        /** Inexact alarms can arrive slightly early; anything closer than this counts as due. */
        private const val EARLY_TOLERANCE_MS = 60_000L
    }
}
