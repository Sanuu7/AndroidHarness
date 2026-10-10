package com.androidharness.app.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.androidharness.app.HarnessApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A notification pause preserves the occurrence for an explicit resume. */
class AutomationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(AutomationWorker.KEY_TASK_ID) ?: return
        val app = context.applicationContext as? HarnessApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { app.container.automation.pause(taskId) }
            finally { pending.finish() }
        }
    }
}
