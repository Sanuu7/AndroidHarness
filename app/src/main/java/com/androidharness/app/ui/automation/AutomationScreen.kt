package com.androidharness.app.ui.automation

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.automation.AutomationAiDraft
import com.androidharness.app.automation.AutomationAiPlanner
import com.androidharness.app.automation.AutomationAiReply
import com.androidharness.app.automation.AutomationAiTurn
import com.androidharness.app.automation.AutomationHistoryEntry
import com.androidharness.app.automation.AutomationSchedule
import com.androidharness.app.automation.AutomationStatus
import com.androidharness.app.automation.AutomationTask
import com.androidharness.app.core.Role
import com.androidharness.app.data.AppSettings
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.settings.ProviderManagerSheet
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
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
                        it.enabled && it.schedule != AutomationSchedule.MANUAL
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
            container = container,
            task = editing,
            projectName = editing?.projectName ?: project?.name.orEmpty(),
            onDismiss = { showEditor = false },
            onDelete = editing?.let { task ->
                {
                    act { manager.delete(task.id) }
                    showEditor = false
                }
            },
            onSave = { title, prompt, checkCommand, schedule, scheduledAt, hour, minute, providerId, model ->
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
                            schedule = schedule,
                            scheduledAt = scheduledAt,
                            hour = hour,
                            minute = minute,
                            providerId = providerId,
                            model = model,
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
        body = "Describe what you want to AI, or configure a manual, one-time, or daily automation yourself.",
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    task.model ?: "App default AI",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill(
                    icon = Icons.Outlined.Schedule,
                    text = when (task.schedule) {
                        AutomationSchedule.MANUAL -> "Manual"
                        AutomationSchedule.ONCE -> "One time"
                        AutomationSchedule.HOURLY -> "Every hour"
                        AutomationSchedule.DAILY -> "%02d:%02d daily".format(task.hour, task.minute)
                    },
                )
                if (task.schedule != AutomationSchedule.MANUAL) {
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

            AutomationModelPill(entry.model)

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
private fun AutomationModelPill(model: String?) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = CircleShape,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(
                model?.substringAfterLast('/') ?: "Unknown model",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

private enum class AutomationEditorMode { ASK_AI, MANUAL }

@Composable
private fun AutomationModePicker(
    mode: AutomationEditorMode,
    onModeChange: (AutomationEditorMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AutomationModeCard(
            title = "Ask AI",
            subtitle = "Describe it naturally",
            icon = Icons.Outlined.AutoAwesome,
            selected = mode == AutomationEditorMode.ASK_AI,
            modifier = Modifier.weight(1f),
        ) { onModeChange(AutomationEditorMode.ASK_AI) }
        AutomationModeCard(
            title = "Manual",
            subtitle = "Configure every detail",
            icon = Icons.Outlined.Tune,
            selected = mode == AutomationEditorMode.MANUAL,
            modifier = Modifier.weight(1f),
        ) { onModeChange(AutomationEditorMode.MANUAL) }
    }
}

@Composable
private fun AutomationModeCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.weight(1f))
                if (selected) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationEditorDialog(
    container: AppContainer,
    task: AutomationTask?,
    projectName: String,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (String, String, String, AutomationSchedule, Long?, Int, Int, String?, String?) -> Unit,
) {
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val catalogs by container.providers.catalogs.collectAsStateWithLifecycle(initialValue = emptyMap())
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val fallbackProviderId = if (settings.planningModelsEnabled) {
        settings.executionProviderId ?: settings.activeProviderId
    } else settings.activeProviderId

    var mode by remember(task) {
        mutableStateOf(if (task == null) AutomationEditorMode.ASK_AI else AutomationEditorMode.MANUAL)
    }
    var title by remember(task) { mutableStateOf(task?.title.orEmpty()) }
    var prompt by remember(task) { mutableStateOf(task?.prompt.orEmpty()) }
    var checkCommand by remember(task) { mutableStateOf(task?.checkCommand.orEmpty()) }
    var schedule by remember(task) { mutableStateOf(task?.schedule ?: AutomationSchedule.MANUAL) }
    var onceAt by remember(task) { mutableLongStateOf(task?.scheduledAt ?: defaultOneTimeRun()) }
    var hour by remember(task) { mutableStateOf((task?.hour ?: 8).toString()) }
    var minute by remember(task) { mutableStateOf((task?.minute ?: 0).toString()) }
    var selectedProviderId by remember(task) { mutableStateOf(task?.providerId) }
    var selectedModel by remember(task) { mutableStateOf(task?.model) }
    var modelSelectionInitialized by remember(task) {
        mutableStateOf(task?.let { !it.providerId.isNullOrBlank() && !it.model.isNullOrBlank() } == true)
    }
    var showModelPicker by remember { mutableStateOf(false) }
    var showProviderManager by remember { mutableStateOf(false) }

    val fallbackProvider = providers.firstOrNull { it.id == fallbackProviderId }
    val fallbackModel = (if (settings.planningModelsEnabled) settings.executionModel else settings.activeModel)
        ?.takeIf { it.isNotBlank() } ?: fallbackProvider?.model

    LaunchedEffect(task?.id, fallbackProviderId, fallbackModel) {
        if (!modelSelectionInitialized && fallbackProviderId != null && !fallbackModel.isNullOrBlank()) {
            selectedProviderId = fallbackProviderId
            selectedModel = fallbackModel
            modelSelectionInitialized = true
        }
    }

    val effectiveProviderId = selectedProviderId
    val selectedProvider = providers.firstOrNull { it.id == effectiveProviderId }
    val effectiveModel = selectedModel?.takeIf { it.isNotBlank() }

    val aiTurns = remember(task) { mutableStateListOf<AutomationAiTurn>() }
    var aiInput by remember(task) { mutableStateOf("") }
    var aiDraft by remember(task) { mutableStateOf<AutomationAiDraft?>(null) }
    var aiBusy by remember(task) { mutableStateOf(false) }
    var aiError by remember(task) { mutableStateOf<String?>(null) }
    val planner = remember(container) { AutomationAiPlanner(container) }
    val scope = rememberCoroutineScope()

    fun applyDraft(draft: AutomationAiDraft) {
        title = draft.title
        prompt = draft.prompt
        checkCommand = draft.checkCommand
        schedule = draft.schedule
        draft.scheduledAt?.let { onceAt = it }
        hour = draft.hour.toString()
        minute = draft.minute.toString()
    }

    fun askAi(text: String) {
        val clean = text.trim()
        if (clean.isBlank() || aiBusy || aiDraft != null) return
        aiInput = ""
        aiError = null
        aiTurns += AutomationAiTurn(Role.USER, clean)
        aiBusy = true
        scope.launch {
            runCatching {
                planner.reply(
                    projectName = projectName,
                    turns = aiTurns.toList(),
                    selectedProviderId = effectiveProviderId,
                    selectedModel = effectiveModel,
                )
            }
                .onSuccess { reply ->
                    when (reply) {
                        is AutomationAiReply.Question -> aiTurns += AutomationAiTurn(Role.ASSISTANT, reply.message)
                        is AutomationAiReply.Draft -> {
                            aiTurns += AutomationAiTurn(Role.ASSISTANT, reply.message)
                            aiDraft = reply.automation
                            applyDraft(reply.automation)
                        }
                    }
                }
                .onFailure { aiError = it.message ?: "Could not ask AI." }
            aiBusy = false
        }
    }

    val hourValue = hour.toIntOrNull()
    val minuteValue = minute.toIntOrNull()
    val validTime = hourValue in 0..23 && minuteValue in 0..59
    val manualCanSave = title.isNotBlank() && prompt.isNotBlank() && when (schedule) {
        AutomationSchedule.MANUAL -> true
        AutomationSchedule.ONCE -> onceAt > System.currentTimeMillis()
        AutomationSchedule.HOURLY -> true
        AutomationSchedule.DAILY -> validTime
    }
    val hasModel = effectiveProviderId != null && !effectiveModel.isNullOrBlank()
    val canSave = hasModel && if (mode == AutomationEditorMode.ASK_AI) aiDraft != null else manualCanSave
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (task == null) "New automation" else "Edit automation",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (projectName.isNotBlank()) {
                        Text(
                            projectName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AutomationModePicker(mode = mode, onModeChange = { mode = it })

                AutomationModelSelector(
                    providerName = selectedProvider?.name,
                    model = effectiveModel,
                    onClick = { showModelPicker = true },
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (mode == AutomationEditorMode.ASK_AI) {
                    AskAiAutomationEditor(
                        turns = aiTurns,
                        input = aiInput,
                        onInputChange = { aiInput = it },
                        busy = aiBusy,
                        error = aiError,
                        draft = aiDraft,
                        onAsk = ::askAi,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        mode = AutomationEditorMode.MANUAL
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    ) {
                        ManualAutomationEditor(
                            title, { title = it }, prompt, { prompt = it }, checkCommand, { checkCommand = it },
                            schedule, { schedule = it }, onceAt, { onceAt = it }, hour, { hour = it }, minute, { minute = it },
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    onDelete?.let {
                        TextButton(
                            onClick = it,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Delete") }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        enabled = canSave,
                        onClick = {
                            val draft = aiDraft
                            if (mode == AutomationEditorMode.ASK_AI && draft != null) {
                                onSave(
                                    draft.title, draft.prompt, draft.checkCommand, draft.schedule, draft.scheduledAt,
                                    draft.hour, draft.minute, effectiveProviderId, effectiveModel,
                                )
                            } else {
                                onSave(
                                    title.trim(), prompt.trim(), checkCommand.trim(), schedule,
                                    onceAt.takeIf { schedule == AutomationSchedule.ONCE }, hourValue ?: 8, minuteValue ?: 0,
                                    effectiveProviderId, effectiveModel,
                                )
                            }
                        },
                    ) {
                        Text(if (task == null) "Create automation" else "Save changes")
                    }
                }
            }
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            providers = providers,
            activeProviderId = effectiveProviderId,
            activeModel = effectiveModel,
            catalogs = catalogs,
            onDismiss = { showModelPicker = false },
            onSelect = { providerId, model ->
                selectedProviderId = providerId
                selectedModel = model ?: providers.firstOrNull { it.id == providerId }?.model
                modelSelectionInitialized = true
                showModelPicker = false
            },
            onRefreshCatalog = { providerId ->
                val provider = providers.firstOrNull { it.id == providerId }
                val key = container.providers.apiKey(providerId)
                when {
                    provider == null -> "Unknown provider"
                    key.isNullOrBlank() -> "No API key for this provider"
                    else -> when (val result = com.androidharness.app.llm.ModelCatalog.listModels(provider, key)) {
                        is com.androidharness.app.llm.ModelCatalog.Result.Models -> {
                            container.providers.saveCatalog(providerId, result.models)
                            null
                        }
                        is com.androidharness.app.llm.ModelCatalog.Result.Failed -> result.message
                    }
                }
            },
            onAddCustomModel = { providerId, model, reasoning ->
                scope.launch { container.providers.addCustomModel(providerId, model, reasoning) }
            },
            onDeleteCustomModel = { providerId, model ->
                scope.launch { container.providers.removeCustomModel(providerId, model) }
            },
            onManageProviders = {
                showModelPicker = false
                showProviderManager = true
            },
        )
    }

    if (showProviderManager) {
        ProviderManagerSheet(
            providers = providers,
            activeProviderId = effectiveProviderId,
            apiKey = container.providers::apiKey,
            onDismiss = { showProviderManager = false },
            onSetActive = { providerId ->
                selectedProviderId = providerId
                selectedModel = providers.firstOrNull { it.id == providerId }?.model
                modelSelectionInitialized = true
                showProviderManager = false
            },
            onDelete = { providerId ->
                scope.launch { container.providers.delete(providerId) }
                if (providerId == selectedProviderId) {
                    selectedProviderId = null
                    selectedModel = null
                }
            },
            onSave = { existing, name, type, baseUrl, model, apiKey ->
                scope.launch {
                    val saved = if (existing == null) {
                        container.providers.add(name, type, baseUrl, model, apiKey)
                    } else {
                        val updated = existing.copy(name = name, type = type, baseUrl = baseUrl, model = model)
                        container.providers.update(updated, apiKey)
                        updated
                    }
                    selectedProviderId = saved.id
                    selectedModel = saved.model
                    modelSelectionInitialized = true
                    showProviderManager = false
                }
            },
        )
    }
}

@Composable
private fun AutomationModelSelector(
    providerName: String?,
    model: String?,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text("Run model", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (providerName != null && model != null) "$providerName · $model" else "Choose a provider and model",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Saved with this automation. Chat model changes won't affect it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Text("Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AskAiAutomationEditor(
    turns: List<AutomationAiTurn>,
    input: String,
    onInputChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    draft: AutomationAiDraft?,
    onAsk: (String) -> Unit,
    modifier: Modifier = Modifier,
    onEditManually: () -> Unit,
) {
    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (turns.isEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                        ) {
                            Icon(
                                Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(11.dp).size(24.dp),
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "Build an automation with AI",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Say what it should do and when it should run.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "QUICK START",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AutomationPromptSuggestion(
                            title = "Keep the project building",
                            subtitle = "Every hour · find and fix build failures",
                            onClick = { onAsk("Every hour, check the project for build failures and fix them.") },
                        )
                        AutomationPromptSuggestion(
                            title = "Build a debug APK",
                            subtitle = "Tomorrow at 8 PM · fix errors if needed",
                            onClick = { onAsk("Tomorrow at 8 PM, build the debug APK and fix any build errors.") },
                        )
                        AutomationPromptSuggestion(
                            title = "Run the test suite",
                            subtitle = "Daily at 9 AM · fix failing tests",
                            onClick = { onAsk("Every day at 9 AM, run the project tests and fix failures.") },
                        )
                    }
                }
            } else {
                items(turns) { turn -> AutomationAiBubble(turn) }
            }

            if (busy) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Working out the automation…", style = MaterialTheme.typography.bodySmall)
                    }
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
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            draft?.let { ready ->
                item { AutomationAiDraftCard(ready, onEditManually) }
            }
        }

        if (draft == null) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    enabled = !busy,
                    placeholder = {
                        Text(if (turns.isEmpty()) "Describe your automation…" else "Answer AI…")
                    },
                    minLines = 2,
                    maxLines = 2,
                    shape = RoundedCornerShape(18.dp),
                    trailingIcon = {
                        FilledIconButton(
                            onClick = { onAsk(input) },
                            enabled = input.isNotBlank() && !busy,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(88.dp),
                )
                }
            }
        }
    }
}

@Composable
private fun AutomationPromptSuggestion(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AutomationAiBubble(turn: AutomationAiTurn) {
    val user = turn.role == Role.USER
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            border = if (user) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(turn.text, style = MaterialTheme.typography.bodyMedium,
                color = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun AutomationAiDraftCard(draft: AutomationAiDraft, onEditManually: () -> Unit) {
    OutlinedCard(shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(7.dp).size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ready to create", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(draft.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(draft.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 5, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill(Icons.Outlined.Schedule, draftScheduleLabel(draft))
                if (draft.checkCommand.isNotBlank()) InfoPill(Icons.Outlined.CheckCircle, "Success check")
            }
            if (draft.checkCommand.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
                ) {
                    Text(draft.checkCommand, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(10.dp))
                }
            }
            TextButton(onClick = onEditManually, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp)); Text("Edit manually")
            }
        }
    }
}

@Composable
private fun ManualAutomationEditor(
    title: String, onTitleChange: (String) -> Unit,
    prompt: String, onPromptChange: (String) -> Unit,
    checkCommand: String, onCheckCommandChange: (String) -> Unit,
    schedule: AutomationSchedule, onScheduleChange: (AutomationSchedule) -> Unit,
    onceAt: Long, onOnceAtChange: (Long) -> Unit,
    hour: String, onHourChange: (String) -> Unit,
    minute: String, onMinuteChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val once = remember(onceAt, zone) { Instant.ofEpochMilli(onceAt).atZone(zone) }

    AutomationEditorSection(
        title = "Task details",
        subtitle = "What should the agent work on?",
        icon = Icons.Outlined.AutoMode,
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            label = { Text("Name") },
            placeholder = { Text("Build debug APK") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            label = { Text("Instructions") },
            placeholder = { Text("Build the debug APK and fix any build errors.") },
            minLines = 4,
            maxLines = 7,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    AutomationEditorSection(
        title = "Schedule",
        subtitle = "When should it run?",
        icon = Icons.Outlined.Schedule,
    ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ScheduleChoiceChip("Manual", schedule == AutomationSchedule.MANUAL, Modifier.weight(1f)) {
                        onScheduleChange(AutomationSchedule.MANUAL)
                    }
                    ScheduleChoiceChip("Once", schedule == AutomationSchedule.ONCE, Modifier.weight(1f)) {
                        onScheduleChange(AutomationSchedule.ONCE)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ScheduleChoiceChip("Hourly", schedule == AutomationSchedule.HOURLY, Modifier.weight(1f)) {
                        onScheduleChange(AutomationSchedule.HOURLY)
                    }
                    ScheduleChoiceChip("Daily", schedule == AutomationSchedule.DAILY, Modifier.weight(1f)) {
                        onScheduleChange(AutomationSchedule.DAILY)
                    }
                }
            }
            if (schedule == AutomationSchedule.ONCE) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        DatePickerDialog(context, { _, year, month, day ->
                            val updated = ZonedDateTime.of(year, month + 1, day, once.hour, once.minute, 0, 0, zone)
                            onOnceAtChange(updated.toInstant().toEpochMilli())
                        }, once.year, once.monthValue - 1, once.dayOfMonth).apply { datePicker.minDate = System.currentTimeMillis() }.show()
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp))
                        Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(onceAt)))
                    }
                    OutlinedButton(onClick = {
                        TimePickerDialog(context, { _, selectedHour, selectedMinute ->
                            onOnceAtChange(once.withHour(selectedHour).withMinute(selectedMinute).withSecond(0).withNano(0).toInstant().toEpochMilli())
                        }, once.hour, once.minute, false).show()
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp))
                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(onceAt)))
                    }
                }
                if (onceAt <= System.currentTimeMillis()) {
                    Text("Choose a future date and time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (schedule == AutomationSchedule.HOURLY) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Runs every hour. The first scheduled run is about one hour after you create it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            if (schedule == AutomationSchedule.DAILY) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = hour, onValueChange = { onHourChange(it.filter(Char::isDigit).take(2)) },
                        label = { Text("Hour") }, supportingText = { Text("0 to 23") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = minute, onValueChange = { onMinuteChange(it.filter(Char::isDigit).take(2)) },
                        label = { Text("Minute") }, supportingText = { Text("0 to 59") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f))
                }
            }
            if (schedule != AutomationSchedule.MANUAL) {
                Text("Android may delay background work slightly to respect system scheduling limits.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
    }

    AutomationEditorSection(
        title = "Success check",
        subtitle = "Optional verification after the agent finishes.",
        icon = Icons.Outlined.CheckCircle,
    ) {
        OutlinedTextField(
            value = checkCommand,
            onValueChange = onCheckCommandChange,
            label = { Text("Command") },
            placeholder = { Text("./gradlew test") },
            supportingText = { Text("Exit code 0 means success. Failures can be retried automatically.") },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AutomationEditorSection(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(7.dp).size(18.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun ScheduleChoiceChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun defaultOneTimeRun(): Long =
    ZonedDateTime.now().plusHours(1).withSecond(0).withNano(0).toInstant().toEpochMilli()

private fun draftScheduleLabel(draft: AutomationAiDraft): String = when (draft.schedule) {
    AutomationSchedule.MANUAL -> "Manual"
    AutomationSchedule.ONCE -> draft.scheduledAt?.let(::formatAutomationTime) ?: "One time"
    AutomationSchedule.HOURLY -> "Every hour"
    AutomationSchedule.DAILY -> "%02d:%02d daily".format(draft.hour, draft.minute)
}

private fun AutomationStatus.label() = name.lowercase().replaceFirstChar { it.uppercase() }
