package com.androidharness.app.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.*
import com.androidharness.app.HarnessApp
import kotlinx.coroutines.CancellationException

class AutomationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val app = applicationContext as? HarnessApp ?: return Result.failure()
        try {
            setForeground(getForegroundInfo())
            app.container.automation.execute(taskId, inputData.getBoolean("scheduled", false))
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(workDataOf("error" to (e.message ?: "Automation could not start")))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("automations", "Automations", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(applicationContext, "automations")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("AndroidHarness automation")
            .setContentText("Working on your saved task")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build())
            .build()
        return ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    companion object { const val KEY_TASK_ID = "task_id" }
}
