package com.androidharness.app.ui.automation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.automation.AutomationConnection
import com.androidharness.app.automation.AutomationStatus
import com.androidharness.app.automation.AutomationTask
import java.text.DateFormat
import java.util.Date

internal fun scheduleStatus(task: AutomationTask, connection: AutomationConnection, now: Long): String = when {
    task.lastStatus == AutomationStatus.RUNNING -> "Running"
    task.lastStatus == AutomationStatus.BLOCKED || task.lastStatus == AutomationStatus.FAILED -> "Needs your attention"
    !task.enabled -> when (task.lastStatus) {
        AutomationStatus.COMPLETED, AutomationStatus.PASSED -> "Completed"
        AutomationStatus.CANCELLED -> "Cancelled"
        else -> "Paused"
    }
    task.lastStatus == AutomationStatus.QUEUED || task.nextRunAt?.let { it <= now } == true ->
        if (!connection.allows(task)) connection.waitingMessage(task) else task.lastMessage?.takeIf { it.startsWith("Waiting for this chat") } ?: "Waiting to start"
    task.nextRunAt != null -> "Scheduled ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(task.nextRunAt))}"
    else -> "Ready"
}

@Composable
internal fun ScheduledMessageRow(tasks: List<AutomationTask>, connection: AutomationConnection, now: Long, onOpen: () -> Unit) {
    if (tasks.isEmpty()) return
    Surface(onClick = onOpen, color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Schedule, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(if (tasks.size == 1) scheduleStatus(tasks.first(), connection, now) else "${tasks.size} scheduled messages",
                    style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (tasks.size == 1) Text(tasks.first().title, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, "Open scheduled messages", modifier = Modifier.size(18.dp))
        }
    }
}
