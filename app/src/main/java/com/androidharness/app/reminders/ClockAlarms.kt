package com.androidharness.app.reminders

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.time.DayOfWeek

/** What the Chat alarm tools need, so they can be tested without Android. */
interface ClockBackend {
    fun clockAvailable(): Boolean
    /** Android only lets an app open the Clock app while the app is on screen. */
    fun inForeground(): Boolean
    /** Returns null on success, or a reason it failed. */
    fun setAlarm(hour: Int, minute: Int, label: String?, days: List<DayOfWeek>): String?
    fun setTimer(seconds: Int, label: String?): String?
}

/**
 * Hands alarms and timers to the phone's own Clock app, so they ring like any
 * other alarm. This needs only the normal SET_ALARM permission; the Clock app
 * owns the alarm afterwards, so it cannot be listed or removed from here.
 */
class ClockAlarms(private val context: Context) : ClockBackend {
    override fun clockAvailable(): Boolean =
        Intent(AlarmClock.ACTION_SET_ALARM).resolveActivity(context.packageManager) != null

    override fun inForeground(): Boolean = ActivityManager.RunningAppProcessInfo()
        .also { ActivityManager.getMyMemoryState(it) }
        .importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

    override fun setAlarm(hour: Int, minute: Int, label: String?, days: List<DayOfWeek>): String? =
        launch(Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply {
                label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                // Calendar numbers Sunday as 1 and Saturday as 7.
                if (days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { it.value % 7 + 1 }))
            })

    override fun setTimer(seconds: Int, label: String?): String? =
        launch(Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } })

    private fun launch(intent: Intent): String? {
        if (intent.resolveActivity(context.packageManager) == null) return "No Clock app on this phone accepts alarms or timers."
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (_: ActivityNotFoundException) {
            "No Clock app on this phone accepts alarms or timers."
        } catch (e: SecurityException) {
            "The Clock app refused: ${e.message}"
        }
    }
}
