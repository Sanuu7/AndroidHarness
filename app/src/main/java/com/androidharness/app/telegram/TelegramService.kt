package com.androidharness.app.telegram

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** User-enabled remote coding session, with a visible notification and real Pause/Stop actions. */
internal class TelegramService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var manager: TelegramManager
    override fun onCreate() {
        super.onCreate()
        manager = (application as HarnessApp).container.telegram
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Telegram remote control", NotificationManager.IMPORTANCE_LOW))
        val notification = notification("Connecting…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, notification)
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "harness:telegram").apply { setReferenceCounted(false); acquire(600_000) }
        scope.launch {
            while (isActive) { delay(540_000); wakeLock?.acquire(600_000) }
        }
        scope.launch {
            combine(manager.setup.state, manager.runtime, manager.pairing, manager.error) { setup, runtime, pairing, error ->
                when {
                    pairing.candidate != null -> "Confirm your Telegram account in AndroidHarness"
                    pairing.challenge != null -> "Waiting for your pairing message"
                    !setup.enabled -> error ?: "Paused"
                    runtime.running -> runtime.label
                    else -> "Connecting…"
                }
            }.collect { text ->
                com.androidharness.app.AppNotifications.post(this@TelegramService, ID, notification(text))
                if (!manager.setup.state.value.enabled && manager.pairing.value.challenge == null) stopSelf()
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> { scope.launch { manager.pause(); manager.cancelPairing(); stopSelf() }; return START_NOT_STICKY }
            ACTION_STOP -> { scope.launch { manager.stopAndPause(); stopSelf() }; return START_NOT_STICKY }
        }
        if (!manager.setup.state.value.enabled && manager.pairing.value.challenge == null) { stopSelf(); return START_NOT_STICKY }
        if ((application as HarnessApp).container.runtimePolicy.state.value.let { it.ready && !it.canRun }) { stopSelf(); return START_NOT_STICKY }
        manager.startRuntime(this)
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        manager.serviceDestroyed(this)
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(ID)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
    private fun notification(text: String): Notification {
        val open = Intent(this, MainActivity::class.java).putExtra(EXTRA_SETTINGS, "telegram").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("AndroidHarness · Telegram").setContentText(text.take(180))
            .setContentIntent(PendingIntent.getActivity(this, ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
        listOf("Pause" to ACTION_PAUSE, "Stop task" to ACTION_STOP).forEachIndexed { index, (label, action) ->
            val pending = PendingIntent.getService(this, ID + index + 1, Intent(this, TelegramService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, label, pending).build())
        }
        return builder.build()
    }
    companion object {
        const val CHANNEL = "telegram_remote"
        const val ID = 9140
        const val EXTRA_SETTINGS = "telegram_settings"
        private const val ACTION_PAUSE = "com.androidharness.app.telegram.PAUSE"
        private const val ACTION_STOP = "com.androidharness.app.telegram.STOP"
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, TelegramService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, TelegramService::class.java)) }
    }
}

internal class TelegramBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        (context.applicationContext as? HarnessApp)?.container?.telegram?.restoreIfEnabled()
    }
}
