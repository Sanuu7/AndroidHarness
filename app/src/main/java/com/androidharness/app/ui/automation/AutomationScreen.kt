package com.androidharness.app.ui.automation

import com.androidharness.app.R
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.settings.ProviderManagerSheet
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledIconButton
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

@Composable
fun AutomationScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    projectId: String? = null,
    initialTaskId: String? = null,
    createOnOpen: Boolean = false,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val manager = container.automation
    val allTasks by manager.repository.tasks.collectAsStateWithLifecycle()
    val allHistory by manager.repository.history.collectAsStateWithLifecycle()
    val projects by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val tasks = if (projectId == null) allTasks else allTasks.filter { it.projectId == projectId }
    val taskIds = tasks.map { it.id }.toSet()
    val history = if (projectId == null) allHistory else allHistory.filter { it.taskId in taskIds }
    val connection by manager.connection.collectAsStateWithLifecycle()
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(60_000) }
    }
    val defaultProject by container.workspace.currentProject.collectAsStateWithLifecycle(initialValue = null)
    val project = if (projectId == null) defaultProject else projects.firstOrNull { it.id == projectId }

    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = allTasks.firstOrNull { it.id == editingId }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var advancedEditor by rememberSaveable { mutableStateOf(false) }
    var githubPresetProject by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var openedEditor by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialTaskId, createOnOpen, allTasks) {
        if (!openedEditor && (createOnOpen || initialTaskId != null)) {
            editingId = initialTaskId?.takeIf { id -> tasks.any { it.id == id } }
            if (createOnOpen || editingId != null) { showEditor = true; openedEditor = true }
        }
    }

    fun act(action: () -> Unit) {
        runCatching(action).onFailure { error = it.message ?: uiContext.getString(R.string.ui_something_went_wrong) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppHeader(
                uiContext.getString(R.string.ui_automation),
                subtitle = if (projectId == null) uiContext.getString(R.string.ui_run_agent_tasks_on_your_schedule) else project?.name ?: uiContext.getString(R.string.ui_project_unavailable),
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
                        editingId = null
                        advancedEditor = false
                        showEditor = true
                    },
                    onCreateAutomation = { editingId = null; advancedEditor = true; showEditor = true },
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
                    ) { Text(uiContext.getString(R.string.ui_tasks)) }
                    SegmentedButton(
                        selected = showHistory,
                        onClick = { showHistory = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {
                            SegmentedButtonDefaults.Icon(active = showHistory) {
                                Icon(Icons.Outlined.History, contentDescription = null)
                            }
                        },
                    ) { Text(uiContext.getString(R.string.ui_history)) }
                }
            }

            item {
                OutlinedButton(onClick = { project?.let { githubPresetProject = it.id to it.name } },
                    enabled = project != null, modifier = Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.ui_github_push_preset)) }
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
                                editingId = null
                                showEditor = true
                            },
                        )
                    }
                } else {
                    items(tasks, key = { it.id }) { task ->
                        AutomationTaskCard(
                            task = task.copy(projectName = projects.firstOrNull { it.id == task.projectId }?.name ?: task.projectName),
                            onRun = { act { manager.runNow(task.id) } },
                            onEdit = {
                                editingId = task.id
                                showEditor = true
                            },
                            onStop = { act { manager.cancel(task.id) } },
                            onDelete = { act { manager.delete(task.id) } },
                            onOpen = { task.lastSessionId?.let(onOpenSession) },
                            onToggle = { act { if (task.enabled) manager.pause(task.id) else manager.resumeSchedule(task.id) } },
                            statusText = scheduleStatus(task, connection, now),
                        )
                    }
                }
            }
        }
    }

    githubPresetProject?.let { (id, name) ->
        com.androidharness.app.ui.github.GitHubPushPresetDialog(container, id, name,
            onDismiss = { githubPresetProject = null })
    }
    if (showEditor && editing?.githubPush != null) {
        val task = requireNotNull(editing)
        com.androidharness.app.ui.github.GitHubPushPresetDialog(container, task.projectId, task.projectName,
            task = task, onDismiss = { showEditor = false })
    } else if (showEditor && (editing?.let { it.schedule != AutomationSchedule.MANUAL } ?: !advancedEditor)) {
        ScheduleMessageSheet(container, editing, onDismiss = { showEditor = false }, defaultProjectId = projectId ?: project?.id)
    } else if (showEditor) {
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
    onCreateAutomation: () -> Unit,
    createEnabled: Boolean,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var showCreateMenu by remember { mutableStateOf(false) }
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
                        projectName ?: uiContext.getString(R.string.ui_no_workspace_selected),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (projectName == null) uiContext.getString(R.string.ui_choose_a_workspace_to_create_automations)
                        else uiContext.getString(R.string.ui_automations_run_inside_this_workspace),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                FilledTonalButton(
                    onClick = { showCreateMenu = true },
                    enabled = createEnabled,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(uiContext.getString(R.string.ui_new))
                }
                DropdownMenu(showCreateMenu, { showCreateMenu = false }) {
                    DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_scheduled_message)) },
                        leadingIcon = { Icon(Icons.Outlined.Schedule, null) },
                        onClick = { showCreateMenu = false; onCreate() })
                    DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_automation_with_checks)) },
                        leadingIcon = { Icon(Icons.Outlined.AutoMode, null) },
                        onClick = { showCreateMenu = false; onCreateAutomation() })
                }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(Modifier.fillMaxWidth()) {
                AutomationMetric(uiContext.getString(R.string.ui_tasks), taskCount.toString(), Modifier.weight(1f))
                AutomationMetric(uiContext.getString(R.string.ui_scheduled), scheduledCount.toString(), Modifier.weight(1f))
                AutomationMetric(uiContext.getString(R.string.ui_runs), runCount.toString(), Modifier.weight(1f))
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
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    EmptyStateCard(
        icon = { Icon(Icons.Outlined.AutoMode, contentDescription = null, modifier = Modifier.size(28.dp)) },
        title = uiContext.getString(R.string.ui_create_your_first_automation),
        body = uiContext.getString(R.string.ui_describe_what_you_want_to_ai_or),
        action = if (projectAvailable) {
            {
                FilledTonalButton(onClick = onCreate) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(uiContext.getString(R.string.ui_new_automation))
                }
            }
        } else null,
    )
}

@Composable
private fun AutomationEmptyHistory() {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    EmptyStateCard(
        icon = { Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(30.dp)) },
        title = uiContext.getString(R.string.ui_no_runs_yet),
        body = uiContext.getString(R.string.ui_completed_and_failed_runs_will_appear_here),
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun AutomationTaskCard(
    task: AutomationTask,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    statusText: String,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(uiContext.getString(R.string.ui_delete_automation)) },
        text = { Text(uiContext.getString(R.string.ui_will_be_removed_and_any_active_or, (task.title).toString())) },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; onDelete() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(uiContext.getString(R.string.ui_delete_automation_1466))
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(uiContext.getString(R.string.ui_keep_automation)) } },
    )
    if (confirmCancel) AlertDialog(onDismissRequest = { confirmCancel = false }, title = { Text(uiContext.getString(R.string.ui_cancel_this_schedule)) },
        text = { Text(uiContext.getString(R.string.ui_future_runs_will_stop_chat_history_and)) },
        confirmButton = { TextButton(onClick = { confirmCancel = false; onStop() }) { Text(uiContext.getString(R.string.ui_cancel_schedule)) } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(uiContext.getString(R.string.ui_keep_schedule)) } })
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (task.prompt != task.title) Text(
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
                    if (task.githubPush != null) uiContext.getString(R.string.ui_github, (task.githubPush.branch).toString()) else task.model ?: uiContext.getString(R.string.ui_app_default_ai),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(task.lastStatus, when {
                    statusText.startsWith("Scheduled") -> "Scheduled"
                    statusText.startsWith("Waiting") -> "Waiting"
                    else -> statusText
                })
                InfoPill(
                    icon = Icons.Outlined.Schedule,
                    text = when (task.schedule) {
                        AutomationSchedule.MANUAL -> uiContext.getString(R.string.ui_manual)
                        AutomationSchedule.ONCE -> uiContext.getString(R.string.ui_one_time)
                        AutomationSchedule.HOURLY -> uiContext.getString(R.string.ui_every_hour)
                        AutomationSchedule.DAILY -> "%02d:%02d daily".format(task.hour, task.minute)
                    },
                )
                if (task.enabled && task.nextRunAt != null) {
                    InfoPill(
                        icon = Icons.Outlined.CheckCircle,
                        text = uiContext.getString(R.string.ui_next_1477, (formatAutomationTime(requireNotNull(task.nextRunAt))).toString()),
                    )
                }
            }

            Text(if (task.unmeteredOnly) uiContext.getString(R.string.ui_unmetered_only) else uiContext.getString(R.string.ui_any_internet), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            task.targetSessionId?.let { Text(uiContext.getString(R.string.ui_continues_an_existing_chat), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (statusText.startsWith("Waiting")) Text(statusText, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)

            task.lastMessage?.takeIf { it.isNotBlank() && it != statusText && !statusText.startsWith("Waiting") }?.let { message ->
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
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(uiContext.getString(R.string.ui_run_now))
                }
                OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(uiContext.getString(R.string.settings_action_edit))
                }
            }
            if (task.schedule != AutomationSchedule.MANUAL && task.lastStatus != AutomationStatus.CANCELLED) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onToggle, modifier = Modifier.weight(1f)) { Text(if (task.enabled) uiContext.getString(R.string.ui_pause) else uiContext.getString(R.string.ui_resume)) }
                    TextButton(onClick = { confirmCancel = true }, modifier = Modifier.weight(1f)) { Text(uiContext.getString(R.string.settings_action_cancel)) }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (task.lastSessionId != null) {
                    TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(uiContext.getString(R.string.ui_open_latest_run))
                    }
                }
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(uiContext.getString(R.string.settings_action_delete))
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
    val uiContext = androidx.compose.ui.platform.LocalContext.current
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
                        TextButton(onClick = onOpen) { Text(uiContext.getString(R.string.ui_open_run)) }
                    }
                    if (canRunAgain) {
                        TextButton(onClick = onRunAgain) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(uiContext.getString(R.string.ui_run_again))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomationModelPill(model: String?) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
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
                model?.substringAfterLast('/') ?: uiContext.getString(R.string.ui_unknown_model),
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
private fun StatusPill(status: AutomationStatus, label: String = status.label()) {
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
            label,
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
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AutomationModeCard(
            title = uiContext.getString(R.string.ui_ask_ai),
            subtitle = uiContext.getString(R.string.ui_describe_it_naturally),
            icon = Icons.Outlined.AutoAwesome,
            selected = mode == AutomationEditorMode.ASK_AI,
            modifier = Modifier.weight(1f),
        ) { onModeChange(AutomationEditorMode.ASK_AI) }
        AutomationModeCard(
            title = uiContext.getString(R.string.ui_manual),
            subtitle = uiContext.getString(R.string.ui_configure_every_detail),
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
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val catalogs by container.providers.catalogs.collectAsStateWithLifecycle(initialValue = emptyMap())
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val fallbackProviderId = if (settings.planningModelsEnabled) {
        settings.executionProviderId ?: settings.activeProviderId
    } else settings.activeProviderId

    var mode by rememberSaveable(task?.id) {
        mutableStateOf(if (task == null) AutomationEditorMode.ASK_AI else AutomationEditorMode.MANUAL)
    }
    var title by rememberSaveable(task?.id) { mutableStateOf(task?.title.orEmpty()) }
    var prompt by rememberSaveable(task?.id) { mutableStateOf(task?.prompt.orEmpty()) }
    var checkCommand by rememberSaveable(task?.id) { mutableStateOf(task?.checkCommand.orEmpty()) }
    var schedule by rememberSaveable(task?.id) { mutableStateOf(task?.schedule ?: AutomationSchedule.MANUAL) }
    var onceAt by rememberSaveable(task?.id) { mutableLongStateOf(task?.scheduledAt ?: defaultOneTimeRun()) }
    var hour by rememberSaveable(task?.id) { mutableStateOf((task?.hour ?: 8).toString()) }
    var minute by rememberSaveable(task?.id) { mutableStateOf((task?.minute ?: 0).toString()) }
    var selectedProviderId by rememberSaveable(task?.id) { mutableStateOf(task?.providerId) }
    var selectedModel by rememberSaveable(task?.id) { mutableStateOf(task?.model) }
    var modelSelectionInitialized by rememberSaveable(task?.id) {
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

    val aiTurns = remember(task?.id) { mutableStateListOf<AutomationAiTurn>() }
    var aiInput by remember(task?.id) { mutableStateOf("") }
    var aiDraft by remember(task?.id) { mutableStateOf<AutomationAiDraft?>(null) }
    var aiBusy by remember(task?.id) { mutableStateOf(false) }
    var aiError by remember(task?.id) { mutableStateOf<String?>(null) }
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
                .onFailure { aiError = it.message ?: uiContext.getString(R.string.ui_could_not_ask_ai) }
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
                        if (task == null) uiContext.getString(R.string.ui_new_automation) else uiContext.getString(R.string.ui_edit_automation),
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
                    Icon(Icons.Filled.Close, contentDescription = uiContext.getString(R.string.settings_action_close))
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
                        ) { Text(uiContext.getString(R.string.settings_action_delete)) }
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
                        Text(if (task == null) uiContext.getString(R.string.ui_create_automation) else uiContext.getString(R.string.settings_root_browser_save_confirm))
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
                    provider == null -> uiContext.getString(R.string.ui_unknown_provider)
                    else -> {
                        val usable = provider.effectiveApiKey(key)
                        if (usable.isBlank()) uiContext.getString(R.string.ui_no_api_key_for_this_provider)
                        else when (val result = com.androidharness.app.llm.ModelCatalog.listModels(provider, usable)) {
                            is com.androidharness.app.llm.ModelCatalog.Result.Models -> {
                                container.providers.saveCatalog(providerId, result.models)
                                null
                            }
                            is com.androidharness.app.llm.ModelCatalog.Result.Failed -> result.message
                        }
                    }
                }
            },
            onAddCustomModel = { providerId, model, reasoning ->
                scope.launch { container.providers.addCustomModel(providerId, model, reasoning) }
            },
            onDeleteCustomModel = { providerId, model ->
                scope.launch { container.providers.removeCustomModel(providerId, model) }
            },
            onEnableProvider = { id -> scope.launch { container.providers.setEnabled(id, true) } },
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
            apiKeys = container.providers::apiKeys,
            onDismiss = { showProviderManager = false },
            onSetActive = { providerId ->
                selectedProviderId = providerId
                selectedModel = providers.firstOrNull { it.id == providerId }?.model
                modelSelectionInitialized = true
                showProviderManager = false
            },
            catalogs = catalogs, onRefreshCatalog = container.providers::refreshCatalog,
            onDelete = { providerId ->
                container.providers.delete(providerId)
                if (providerId == selectedProviderId) {
                    selectedProviderId = null
                    selectedModel = null
                }
            },
            onEnabledChange = { id, enabled -> container.providers.setEnabled(id, enabled) },
            onMove = { id, direction, visible -> scope.launch { container.providers.move(id, direction, visible) } },
            onSave = { existing, name, type, baseUrl, model, apiKey, headers ->
                scope.launch {
                    val saved = if (existing == null) {
                        container.providers.add(name, type, baseUrl, model, apiKey, headers)
                    } else {
                        val updated = existing.copy(name = name, type = type, baseUrl = baseUrl, model = model, headers = headers)
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
    val uiContext = androidx.compose.ui.platform.LocalContext.current
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
                Text(uiContext.getString(R.string.ui_run_model), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (providerName != null && model != null) "$providerName · $model" else uiContext.getString(R.string.ui_choose_a_provider_and_model),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    uiContext.getString(R.string.ui_saved_with_this_automation_chat_model_changes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Text(uiContext.getString(R.string.ui_change), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
    val uiContext = androidx.compose.ui.platform.LocalContext.current
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
                                uiContext.getString(R.string.ui_build_an_automation_with_ai),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                uiContext.getString(R.string.ui_say_what_it_should_do_and_when),
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
                            uiContext.getString(R.string.ui_quick_start),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AutomationPromptSuggestion(
                            title = uiContext.getString(R.string.ui_keep_the_project_building),
                            subtitle = uiContext.getString(R.string.ui_every_hour_find_and_fix_build_failures),
                            onClick = { onAsk("Every hour, check the project for build failures and fix them.") },
                        )
                        AutomationPromptSuggestion(
                            title = uiContext.getString(R.string.ui_build_a_debug_apk),
                            subtitle = uiContext.getString(R.string.ui_tomorrow_at_8_pm_fix_errors_if),
                            onClick = { onAsk("Tomorrow at 8 PM, build the debug APK and fix any build errors.") },
                        )
                        AutomationPromptSuggestion(
                            title = uiContext.getString(R.string.ui_run_the_test_suite),
                            subtitle = uiContext.getString(R.string.ui_daily_at_9_am_fix_failing_tests),
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
                        Text(uiContext.getString(R.string.ui_working_out_the_automation), style = MaterialTheme.typography.bodySmall)
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
                        Text(if (turns.isEmpty()) uiContext.getString(R.string.ui_describe_your_automation) else uiContext.getString(R.string.ui_answer_ai))
                    },
                    minLines = 2,
                    maxLines = 2,
                    shape = RoundedCornerShape(18.dp),
                    trailingIcon = {
                        FilledIconButton(
                            onClick = { onAsk(input) },
                            enabled = input.isNotBlank() && !busy,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = uiContext.getString(R.string.ui_send))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 88.dp),
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
    val uiContext = androidx.compose.ui.platform.LocalContext.current
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
                    Text(uiContext.getString(R.string.ui_ready_to_create), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(draft.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(draft.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 5, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill(Icons.Outlined.Schedule, draftScheduleLabel(draft))
                if (draft.checkCommand.isNotBlank()) InfoPill(Icons.Outlined.CheckCircle, uiContext.getString(R.string.ui_success_check))
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
                Spacer(Modifier.width(6.dp)); Text(uiContext.getString(R.string.ui_edit_manually))
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
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val once = remember(onceAt, zone) { Instant.ofEpochMilli(onceAt).atZone(zone) }

    AutomationEditorSection(
        title = uiContext.getString(R.string.ui_task_details),
        subtitle = uiContext.getString(R.string.ui_what_should_the_agent_work_on),
        icon = Icons.Outlined.AutoMode,
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            label = { Text(uiContext.getString(R.string.settings_mcp_name)) },
            placeholder = { Text(uiContext.getString(R.string.ui_build_debug_apk)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            label = { Text(uiContext.getString(R.string.ui_instructions)) },
            placeholder = { Text("Build the debug APK and fix any build errors.") },
            minLines = 4,
            maxLines = 7,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    AutomationEditorSection(
        title = uiContext.getString(R.string.ui_schedule),
        subtitle = uiContext.getString(R.string.ui_when_should_it_run),
        icon = Icons.Outlined.Schedule,
    ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ScheduleChoiceChip(uiContext.getString(R.string.ui_manual), schedule == AutomationSchedule.MANUAL, Modifier.weight(1f)) {
                        onScheduleChange(AutomationSchedule.MANUAL)
                    }
                    ScheduleChoiceChip(uiContext.getString(R.string.ui_once), schedule == AutomationSchedule.ONCE, Modifier.weight(1f)) {
                        onScheduleChange(AutomationSchedule.ONCE)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ScheduleChoiceChip(uiContext.getString(R.string.ui_hourly), schedule == AutomationSchedule.HOURLY, Modifier.weight(1f)) {
                        onScheduleChange(AutomationSchedule.HOURLY)
                    }
                    ScheduleChoiceChip(uiContext.getString(R.string.ui_daily), schedule == AutomationSchedule.DAILY, Modifier.weight(1f)) {
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
                    Text(uiContext.getString(R.string.ui_choose_a_future_date_and_time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
                        uiContext.getString(R.string.ui_runs_every_hour_the_first_scheduled_run),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            if (schedule == AutomationSchedule.DAILY) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = hour, onValueChange = { onHourChange(it.filter(Char::isDigit).take(2)) },
                        label = { Text(uiContext.getString(R.string.ui_hour)) }, supportingText = { Text(uiContext.getString(R.string.ui_0_to_23)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = minute, onValueChange = { onMinuteChange(it.filter(Char::isDigit).take(2)) },
                        label = { Text(uiContext.getString(R.string.ui_minute)) }, supportingText = { Text(uiContext.getString(R.string.ui_0_to_59)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f))
                }
            }
            if (schedule != AutomationSchedule.MANUAL) {
                Text(uiContext.getString(R.string.ui_android_may_delay_background_work_slightly_to),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
    }

    AutomationEditorSection(
        title = uiContext.getString(R.string.ui_success_check),
        subtitle = uiContext.getString(R.string.ui_optional_verification_after_the_agent_finishes),
        icon = Icons.Outlined.CheckCircle,
    ) {
        OutlinedTextField(
            value = checkCommand,
            onValueChange = onCheckCommandChange,
            label = { Text(uiContext.getString(R.string.settings_mcp_command)) },
            placeholder = { Text("./gradlew test") },
            supportingText = { Text(uiContext.getString(R.string.ui_exit_code_0_means_success_failures_can)) },
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
