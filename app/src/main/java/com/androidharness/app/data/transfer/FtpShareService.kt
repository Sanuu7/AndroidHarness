package com.androidharness.app.data.transfer

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.androidharness.app.HarnessApp
import com.androidharness.app.R

class FtpShareService : Service() {
    private val manager get() = (application as HarnessApp).container.ftpShare
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.ftp_server_title), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { manager.stop(); stopSelf(); return START_NOT_STICKY }
        val stop = PendingIntent.getService(this, 0, Intent(this, FtpShareService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, com.androidharness.app.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(ID, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.ftp_server_title)).setContentText(getString(R.string.ftp_server_notification))
            .setOngoing(true).setContentIntent(open).addAction(0, getString(R.string.ftp_server_stop), stop).build())
        if (!(application as HarnessApp).container.runtimePolicy.state.value.canRun || !manager.open()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { manager.stop(getString(R.string.ftp_server_timeout)); stopSelf() }
    override fun onDestroy() { manager.stop(manager.state.value.error); super.onDestroy() }
    companion object { private const val CHANNEL = "ftp-sharing"; private const val ID = 1407; private const val STOP = "com.androidharness.app.STOP_FTP_SHARING" }
}
