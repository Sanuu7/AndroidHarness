package com.androidharness.app.data.env.adb

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.R
import kotlinx.coroutines.*

/** Keeps a user-enabled on-device shell alive; no connection lifetime limit. */
class WirelessAdbService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager get() = (application as HarnessApp).container.wirelessAdb
    private var connection: Job? = null
    private var status: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            connection?.cancel(); status?.cancel()
            manager.disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        if (!manager.autoReconnectEnabled) { stopSelf(); return START_NOT_STICKY }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.wireless_adb_section_title), NotificationManager.IMPORTANCE_LOW))
        val initial = notification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, initial)
        if (connection?.isActive != true) connection = scope.launch {
            var retry = 5_000L
            while (isActive && manager.autoReconnectEnabled) {
                if (!manager.isConnected) {
                    manager.connect()
                    retry = if (manager.isConnected) 5_000L else (retry * 2).coerceAtMost(60_000L)
                }
                delay(if (manager.isConnected) 15_000L else retry)
            }
            currentCoroutineContext().ensureActive()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
        if (status?.isActive != true) status = scope.launch {
            manager.state.collect { notifications.notify(ID, notification()) }
        }
        return START_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.wireless_adb_section_title))
            .setContentText(getString(if (manager.isConnected) R.string.wireless_adb_connection_kept else R.string.wireless_adb_auto_reconnecting))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.wireless_adb_disconnect), disconnect).build()).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onTimeout(startId: Int) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onTimeout(startId: Int, fgsType: Int) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }

    companion object {
        private const val CHANNEL = "wireless_adb_connection"
        private const val ID = 9105
        private const val STOP = "com.androidharness.app.WIRELESS_ADB_DISCONNECT"
        fun resume(context: Context) {
            if (Build.VERSION.SDK_INT < 30) return
            val manager = (context.applicationContext as HarnessApp).container.wirelessAdb
            if (manager.autoReconnectEnabled) runCatching { context.startForegroundService(Intent(context, WirelessAdbService::class.java)) }
                .onFailure { android.util.Log.w("WirelessAdbService", "Reconnect will resume when the app is visible", it) }
        }
        fun enable(context: Context) {
            (context.applicationContext as HarnessApp).container.wirelessAdb.setAutoReconnect(true)
            resume(context)
        }
        fun disconnect(context: Context) {
            (context.applicationContext as HarnessApp).container.wirelessAdb.disconnect()
            context.stopService(Intent(context, WirelessAdbService::class.java))
        }
    }
}

/** Package replacement is an Android-supported opportunity to restore the user's connection. */
class WirelessAdbReconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) WirelessAdbService.resume(context)
    }
}
