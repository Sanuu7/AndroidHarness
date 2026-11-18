package com.androidharness.app.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.androidharness.app.HarnessApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Fires reminders, handles Snooze and Done, and sets every alarm again after a reboot or clock change. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? HarnessApp ?: return
        val reminders = app.container.reminders
        val id = intent.getStringExtra(EXTRA_ID)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_FIRE -> id?.let { reminders.fire(it) }
                    ACTION_SNOOZE -> id?.let { reminders.snooze(it) }
                    ACTION_DONE -> id?.let { reminders.done(it) }
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                    -> reminders.rescheduleAll()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.androidharness.app.reminders.FIRE"
        const val ACTION_SNOOZE = "com.androidharness.app.reminders.SNOOZE"
        const val ACTION_DONE = "com.androidharness.app.reminders.DONE"
        const val EXTRA_ID = "reminder_id"
    }
}
