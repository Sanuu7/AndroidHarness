package com.androidharness.app.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.work.*
import com.androidharness.app.HarnessApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class AutomationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val app = applicationContext as? HarnessApp ?: return Result.failure()
        val manager = app.container.automation
        val task = manager.repository.task(taskId) ?: return Result.success()
        val scheduled = inputData.getBoolean("scheduled", false)
        if (scheduled && !task.enabled) return Result.success()
        if (!manager.connection.value.allows(task)) return Result.retry()
        try {
            setForeground(getForegroundInfo())
            val outcome = coroutineScope {
                val connectionGuard = launch {
                    manager.connection.first { !it.allows(task) }
                    throw AutomationConnectionLost()
                }
                try {
                    manager.execute(taskId, scheduled, inputData.getString(KEY_OCCURRENCE)
                        ?: task.activeRun?.occurrence ?: task.nextRunAt?.toString() ?: id.toString())
                } finally { connectionGuard.cancel() }
            }
            return if (outcome == AutomationOutcome.RETRY) Result.retry() else Result.success()
        } catch (e: AutomationConnectionLost) {
            return Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            manager.repository.task(taskId)?.let { current ->
                manager.repository.save(current.copy(lastStatus = AutomationStatus.BLOCKED,
                    lastMessage = "Could not start in the background. Open the app and retry."))
            }
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
            .addAction(Notification.Action.Builder(null, "Pause", PendingIntent.getBroadcast(applicationContext,
                id.hashCode(), Intent(applicationContext, AutomationActionReceiver::class.java)
                    .setAction("pause-$id").putExtra(KEY_TASK_ID, inputData.getString(KEY_TASK_ID)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
        return ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_OCCURRENCE = "occurrence"
    }
}
