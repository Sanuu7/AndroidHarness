package com.androidharness.app.data.env.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Discovers the wireless debugging pairing port and lets the user type the
 * six-digit code from the notification shade. The notification is deliberate:
 * Android keeps the system pairing dialog on screen, so the app itself cannot
 * be used to type the code on a phone.
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var search: Job? = null
    private var mdns: AdbMdns? = null

    private val manager: WirelessAdbManager
        get() = (application as HarnessApp).container.wirelessAdb
    private val notifications: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        val nm = notifications
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.wireless_adb_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    setShowBadge(false)
                    setAllowBubbles(false)
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_PROMPT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROMPT,
                    getString(R.string.wireless_adb_enter_code),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    // The code prompt is time-sensitive. Vibration is what makes
                    // it peek on devices that do not peek for silent channels.
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 150, 100, 150)
                    setShowBadge(false)
                    setAllowBubbles(false)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginSearch()
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(EXTRA_CODE)?.toString().orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                Log.i(TAG, "Pairing reply received (code length ${code.length}, port $port)")
                notifications.cancel(PROMPT_ID)
                showForeground(working())
                finishPairing(code, port)
            }
            ACTION_STOP -> {
                finishService()
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
        return START_REDELIVER_INTENT
    }

    private fun beginSearch() {
        Log.i(TAG, "Searching for the wireless debugging pairing service")
        showForeground(searching())
        if (search?.isActive == true) return
        search = scope.launch {
            val discovery = AdbMdns(this@AdbPairingService, AdbMdns.TLS_PAIRING).also { mdns = it }
            while (isActive) {
                val port = discovery.findPort(20_000)
                if (port != null) {
                    Log.i(TAG, "Pairing service found on port $port")
                    notifications.notify(NOTIFICATION_ID, found())
                    notifications.notify(PROMPT_ID, input(port))
                    return@launch
                }
            }
        }
    }

    private fun finishPairing(code: String, port: Int) {
        scope.launch {
            Log.i(TAG, "Pairing started (port $port)")
            val pairing = runCatching {
                if (port in 1..65535) manager.pair(code, port) else manager.pair(code)
            }
            pairing.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            if (pairing.isFailure) {
                Log.w(TAG, "Pairing failed", pairing.exceptionOrNull())
                postResult(R.string.wireless_adb_pairing_failed, pairing.exceptionOrNull()?.message)
                finishService()
                return@launch
            }
            Log.i(TAG, "Paired; connecting to adbd")
            notifications.notify(NOTIFICATION_ID, connecting())
            val connection = manager.connect()
            if (connection.isSuccess) {
                Log.i(TAG, "Wireless ADB connected")
                WirelessAdbService.enable(this@AdbPairingService)
                postResult(R.string.wireless_adb_paired, null)
            } else {
                Log.w(TAG, "Paired but connect failed", connection.exceptionOrNull())
                postResult(R.string.wireless_adb_paired_connect_failed, connection.exceptionOrNull()?.message)
            }
            finishService()
        }
    }

    /** Results use their own ID so stopForeground(REMOVE) cannot erase them. */
    private fun postResult(title: Int, text: String?) {
        val notification = base(CHANNEL, ongoing = false, onlyAlertOnce = false)
            .setContentTitle(getString(title))
            .apply { if (!text.isNullOrBlank()) setContentText(text) }
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        notifications.notify(RESULT_ID, notification)
    }

    private fun showForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onTimeout(startId: Int) { finishService() }
    override fun onTimeout(startId: Int, fgsType: Int) { finishService() }

    private fun finishService() {
        search?.cancel()
        mdns?.stop()
        notifications.cancel(PROMPT_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun searching(): Notification = base(CHANNEL, ongoing = false)
        .setContentTitle(getString(R.string.wireless_adb_searching))
        .setContentText(getString(R.string.wireless_adb_searching_hint))
        .addAction(Notification.Action.Builder(null, getString(R.string.wireless_adb_stop_search), serviceIntent(ACTION_STOP, 3)).build())
        .build()

    private fun found(): Notification = base(CHANNEL, ongoing = false)
        .setContentTitle(getString(R.string.wireless_adb_pairing_found))
        .setContentText(getString(R.string.wireless_adb_pairing_found_hint))
        .build()

    private fun input(port: Int): Notification {
        val remoteInput = RemoteInput.Builder(EXTRA_CODE)
            .setLabel(getString(R.string.wireless_adb_code_label))
            .build()
        val reply = PendingIntent.getForegroundService(
            this, 2,
            Intent(this, AdbPairingService::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_PORT, port),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = getString(R.string.wireless_adb_port) + " " + port + " · " +
            getString(R.string.wireless_adb_pairing_found_hint)
        return base(CHANNEL_PROMPT, ongoing = false, onlyAlertOnce = false)
            .setContentTitle(getString(R.string.wireless_adb_pairing_found))
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.wireless_adb_enter_code), reply)
                    .addRemoteInput(remoteInput)
                    .build(),
            )
            .build()
    }

    private fun working(): Notification = base(CHANNEL, ongoing = false)
        .setContentTitle(getString(R.string.wireless_adb_pairing))
        .build()

    private fun connecting(): Notification = base(CHANNEL, ongoing = false)
        .setContentTitle(getString(R.string.wireless_adb_status_connecting))
        .build()

    private fun base(channel: String, ongoing: Boolean, onlyAlertOnce: Boolean = true): Notification.Builder {
        val open = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .apply { if (onlyAlertOnce) setOnlyAlertOnce(true) }
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, AdbPairingService::class.java).setAction(action),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        search?.cancel()
        mdns?.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AdbPairingService"
        private const val CHANNEL = "wireless_adb_pairing"
        private const val CHANNEL_PROMPT = "wireless_adb_pairing_code"
        private const val NOTIFICATION_ID = 9102
        private const val PROMPT_ID = 9103
        private const val RESULT_ID = 9104
        private const val ACTION_START = "com.androidharness.app.WIRELESS_ADB_PAIR"
        private const val ACTION_REPLY = "com.androidharness.app.WIRELESS_ADB_REPLY"
        private const val ACTION_STOP = "com.androidharness.app.WIRELESS_ADB_STOP"
        private const val EXTRA_CODE = "pairing_code"
        private const val EXTRA_PORT = "pairing_port"

        @RequiresApi(Build.VERSION_CODES.R)
        fun start(context: Context) {
            context.startForegroundService(Intent(context, AdbPairingService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AdbPairingService::class.java).setAction(ACTION_STOP))
        }
    }
}
