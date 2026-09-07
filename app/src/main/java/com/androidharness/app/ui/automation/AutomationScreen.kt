package com.androidharness.app.ui.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.automation.AutomationHistoryEntry
import com.androidharness.app.automation.AutomationSchedule
import com.androidharness.app.automation.AutomationStatus
import com.androidharness.app.automation.AutomationTask
import com.androidharness.app.ui.common.AppHeader
import java.text.DateFormat
import java.util.Date

@Composable
fun AutomationScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val manager = container.automation
    val tasks by manager.repository.tasks.collectAsStateWithLifecycle()
    val history by manager.repository.history.collectAsStateWithLifecycle()
    val project by container.workspace.currentProject.collectAsStateWithLifecycle(initialValue = null)

    var editing by remember { mutableStateOf<AutomationTask?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun act(action: () -> Unit) {
        runCatching(action).onFailure { error = it.message ?: "Something went wrong" }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppHeader(
                "Automation",
                subtitle = "Run agent tasks on your schedule",
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                AutomationSummaryCard(
                    projectName = project?.name,
                    taskCount = tasks.size,
                    scheduledCount = tasks.count {
                        it.enabled && it.schedule == AutomationSchedule.DAILY
                    },
                    runCount = history.size,
                    onCreate = {
                        editing = null
                        showEditor = true
                    },
                    createEnabled = project != null,
                )
            }

            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !showHistory,
                        onClick = { showHistory = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = {
                            SegmentedButtonDefaults.Icon(active = !showHistory) {
                                Icon(Icons.Outlined.AutoMode, contentDescription = null)
                            }
                        },
                    ) { Text("Tasks") }
                    SegmentedButton(
                        selected = showHistory,
                        onClick = { showHistory = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {
                            SegmentedButtonDefaults.Icon(active = showHistory) {
                                Icon(Icons.Outlined.History, contentDescription = null)
                            }
                        },
                    ) { Text("History") }
                }
            }

            error?.let { message ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }

            if (showHistory) {
                if (history.isEmpty()) {
                    item { AutomationEmptyHistory() }
                } else {
                    items(history, key = { it.id }) { entry ->
                        HistoryCard(
                            entry = entry,
                            canRunAgain = tasks.any { it.id == entry.taskId },
                            onOpen = { entry.sessionId?.let(onOpenSession) },
                            onRunAgain = { act { manager.runNow(entry.taskId) } },
                        )
                    }
                }
            } else {
                if (tasks.isEmpty()) {
                    item {
                        AutomationEmptyTasks(
                            projectAvailable = project != null,
                            onCreate = {
                                editing = null
                                showEditor = true
                            },
                        )
                    }
                } else {
                    items(tasks, key = { it.id }) { task ->
                        AutomationTaskCard(
                            task = task,
                            onRun = { act { manager.runNow(task.id) } },
                            onEdit = {
                                editing = task
                                showEditor = true
                            },
                            onStop = { act { manager.cancel(task.id) } },
                            onOpen = { task.lastSessionId?.let(onOpenSession) },
                        )
                    }
                }
            }
        }
    }

    if (showEditor) {
        AutomationEditorDialog(
            task = editing,
            projectName = editing?.projectName ?: project?.name.orEmpty(),
            onDismiss = { showEditor = false },
            onDelete = editing?.let { task ->
                {
                    act { manager.delete(task.id) }
                    showEditor = false
                }
            },
            onSave = { title, prompt, checkCommand, daily, hour, minute ->
                act {
                    val base = editing ?: AutomationTask(
                        title = title,
                        prompt = prompt,
                        projectId = requireNotNull(project).id,
                        projectName = requireNotNull(project).name,
                    )
                    manager.save(
                        base.copy(
                            title = title,
                            prompt = prompt,
                            checkCommand = checkCommand,
                            schedule = if (daily) AutomationSchedule.DAILY else AutomationSchedule.MANUAL,
                            hour = hour,
                            minute = minute,
                            enabled = true,
                        ),
                    )
                    showEditor = false
                }
            },
        )
    }
}

@Composable
private fun AutomationSummaryCard(
    projectName: String?,
    taskCount: Int,
    scheduledCount: Int,
    runCount: Int,
    onCreate: () -> Unit,
    createEnabled: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                ) {
                    Icon(
                        Icons.Outlined.AutoMode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp).size(24.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        projectName ?: "No workspace selected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (projectName == null) "Choose a workspace to create automations"
                        else "Automations run inside this workspace",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalButton(
                    onClick = onCreate,
                    enabled = createEnabled,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(Modifier.fillMaxWidth()) {
                AutomationMetric("Tasks", taskCount.toString(), Modifier.weight(1f))
                AutomationMetric("Scheduled", scheduledCount.toString(), Modifier.weight(1f))
                AutomationMetric("Runs", runCount.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AutomationMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AutomationEmptyTasks(projectAvailable: Boolean, onCreate: () -> Unit) {
    EmptyStateCard(
        icon = { Icon(Icons.Outlined.AutoMode, contentDescription = null, modifier = Modifier.size(28.dp)) },
        title = "Create your first automation",
        body = "Run builds, tests, maintenance, or any agent task now or on a daily schedule.",
        action = if (projectAvailable) {
            {
                FilledTonalButton(onClick = onCreate) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New automation")
                }
            }
        } else null,
    )
}

@Composable
private fun AutomationEmptyHistory() {
    EmptyStateCard(
        icon = { Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(30.dp)) },
        title = "No runs yet",
        body = "Completed and failed runs will appear here with their latest result.",
    )
}

@Composable
private fun EmptyStateCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 30.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = CircleShape,
            ) {
                Box(Modifier.padding(14.dp)) { icon() }
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action?.invoke()
        }
    }
}

@Composable
private fun AutomationTaskCard(
    task: AutomationTask,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                ) {
                    Icon(
                        Icons.Outlined.AutoMode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(8.dp).size(18.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        task.projectName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(task.lastStatus)
            }

            Text(
                task.prompt,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill(
                    icon = Icons.Outlined.Schedule,
                    text = if (task.schedule == AutomationSchedule.DAILY) {
                        "%02d:%02d daily".format(task.hour, task.minute)
                    } else {
                        "Manual"
                    },
                )
                if (task.schedule == AutomationSchedule.DAILY) {
                    InfoPill(
                        icon = Icons.Outlined.CheckCircle,
                        text = when {
                            !task.enabled -> "Paused"
                            task.nextRunAt != null -> "Next ${formatAutomationTime(task.nextRunAt)}"
                            else -> "Active"
                        },
                    )
                }
            }

            task.lastMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        message.take(220),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onRun,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Run now")
                }
                OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                if (task.lastStatus == AutomationStatus.RUNNING || task.lastStatus == AutomationStatus.QUEUED) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Outlined.StopCircle, contentDescription = "Stop automation")
                    }
                }
            }

            if (task.lastSessionId != null) {
                TextButton(onClick = onOpen, modifier = Modifier.align(Alignment.End)) {
                    Text("Open latest run")
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: AutomationHistoryEntry,
    canRunAgain: Boolean,
    onOpen: () -> Unit,
    onRunAgain: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp).size(20.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.startedAt)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(entry.status)
            }

            entry.message?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (entry.sessionId != null || canRunAgain) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entry.sessionId != null) {
                        TextButton(onClick = onOpen) { Text("Open run") }
                    }
                    if (canRunAgain) {
                        TextButton(onClick = onRunAgain) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Run again")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = CircleShape,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun formatAutomationTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

@Composable
private fun StatusPill(status: AutomationStatus) {
    val isPositive = status == AutomationStatus.COMPLETED || status == AutomationStatus.PASSED
    val isProblem = status == AutomationStatus.FAILED || status == AutomationStatus.BLOCKED || status == AutomationStatus.CANCELLED
    val container = when {
        isPositive -> MaterialTheme.colorScheme.primaryContainer
        isProblem -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        isPositive -> MaterialTheme.colorScheme.onPrimaryContainer
        isProblem -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(color = container, shape = CircleShape) {
        Text(
            status.label(),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun AutomationEditorDialog(
    task: AutomationTask?,
    projectName: String,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (String, String, String, Boolean, Int, Int) -> Unit,
) {
    var title by remember(task) { mutableStateOf(task?.title.orEmpty()) }
    var prompt by remember(task) { mutableStateOf(task?.prompt.orEmpty()) }
    var checkCommand by remember(task) { mutableStateOf(task?.checkCommand.orEmpty()) }
    var daily by remember(task) { mutableStateOf(task?.schedule == AutomationSchedule.DAILY) }
    var hour by remember(task) { mutableStateOf((task?.hour ?: 8).toString()) }
    var minute by remember(task) { mutableStateOf((task?.minute ?: 0).toString()) }

    val hourValue = hour.toIntOrNull()
    val minuteValue = minute.toIntOrNull()
    val validTime = hourValue in 0..23 && minuteValue in 0..59
    val canSave = title.isNotBlank() && prompt.isNotBlank() && (!daily || validTime)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        icon = {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                Icon(
                    Icons.Outlined.AutoMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(10.dp).size(24.dp),
                )
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (task == null) "Create automation" else "Edit automation")
                if (projectName.isNotBlank()) {
                    Text(
                        projectName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task name") },
                    placeholder = { Text("Build debug APK") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("What should AndroidHarness do?") },
                    placeholder = { Text("Build the debug APK and fix any build errors.") },
                    minLines = 4,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = checkCommand,
                    onValueChange = { checkCommand = it },
                    label = { Text("Success check") },
                    placeholder = { Text("./gradlew test") },
                    supportingText = { Text("Optional. Exit code 0 means success; failures can be retried automatically.") },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Run every day", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Android may delay background work slightly.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = daily, onCheckedChange = { daily = it })
                        }

                        if (daily) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = hour,
                                    onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                                    label = { Text("Hour") },
                                    supportingText = { Text("0 to 23") },
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = minute,
                                    onValueChange = { minute = it.filter(Char::isDigit).take(2) },
                                    label = { Text("Minute") },
                                    supportingText = { Text("0 to 59") },
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                Text(
                    "Uses your current execution model and permission settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    onSave(
                        title.trim(),
                        prompt.trim(),
                        checkCommand.trim(),
                        daily,
                        hourValue ?: 8,
                        minuteValue ?: 0,
                    )
                },
            ) {
                Text(if (task == null) "Create" else "Save changes")
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                onDelete?.let {
                    TextButton(onClick = it, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("Delete")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun AutomationStatus.label() = name.lowercase().replaceFirstChar { it.uppercase() }
