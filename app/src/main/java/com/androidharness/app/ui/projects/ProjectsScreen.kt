package com.androidharness.app.ui.projects

import com.androidharness.app.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.automation.AutomationTask
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.ui.common.AddWorkspaceDialog
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.common.formatRelativeTime
import com.androidharness.app.ui.github.GitHubWorkspaceDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.ExpressiveDropdownMenu
import com.androidharness.app.ui.common.ExpressiveDropdownMenuItem
import com.androidharness.app.ui.common.ExpressiveEmptyState
import com.androidharness.app.ui.common.ExpressiveIconBadge
import com.androidharness.app.ui.common.ExpressiveIconButton
import com.androidharness.app.ui.common.ExpressiveListRow
import com.androidharness.app.ui.common.ExpressiveRefreshButton
import com.androidharness.app.ui.common.ExpressiveSectionLabel
import com.androidharness.app.ui.common.workspaceKindIcon
import com.androidharness.app.ui.settings.SettingsChoiceGroup
import com.androidharness.app.ui.settings.SettingsExpressiveTheme
import com.androidharness.app.ui.settings.SettingsHeroBadge
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import androidx.compose.foundation.lazy.itemsIndexed
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

@Composable
fun ProjectsScreen(container: AppContainer, onBack: () -> Unit, onOpenProject: (String) -> Unit,
    onProjectDeleted: () -> Unit = {}) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val projects by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val sessions by container.sessions.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val counts = remember(sessions, settings.archivedSessions) {
        sessions.filterNot { it.id in settings.archivedSessions }.groupingBy { it.projectId }.eachCount()
    }
    var adding by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ProjectEntity?>(null) }
    val scope = rememberCoroutineScope()
    fun finishAdd(id: String?) {
        adding = false
        id?.let(onOpenProject)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) finishAdd(null) else scope.launch {
            try { finishAdd(container.workspace.addPickedFolder(uri, activate = false).id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message; finishAdd(null) }
        }
    }

    SettingsExpressiveTheme {
    Scaffold(topBar = { AppHeader(uiContext.getString(R.string.wp_workspaces), subtitle = uiContext.getString(R.string.wp_workspaces_sub), onBack = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            itemsIndexed(projects, key = { _, it -> it.id }) { index, project ->
                val count = counts[project.id] ?: 0
                var menu by remember { mutableStateOf(false) }
                ExpressiveListRow(index, projects.size, project.name,
                    subtitle = "${container.workspace.describe(project).kindLabel} · $count ${if (count == 1) "chat" else "chats"}",
                    onClick = { onOpenProject(project.id) }, onLongClick = { if (project.kind != com.androidharness.app.workspace.WorkspaceManager.KIND_APP) menu = true },
                    modifier = Modifier.animateItem(fadeInSpec = expressiveEffectsSpec(), placementSpec = expressiveSpatialSpec(), fadeOutSpec = expressiveEffectsSpec()),
                    leading = { ExpressiveIconBadge(workspaceKindIcon(project.kind)) },
                    trailing = {
                        if (project.kind != com.androidharness.app.workspace.WorkspaceManager.KIND_APP) Box {
                            ExpressiveIconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, uiContext.getString(R.string.wp_actions_for_workspace, project.name)) }
                            ExpressiveDropdownMenu(menu, { menu = false }) {
                                ExpressiveDropdownMenuItem(text = { Text(uiContext.getString(R.string.wp_workspace_delete), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; deleting = project })
                            }
                        }
                    })
            }
            item(key = "\u0000add") {
                ExpressiveListRow(0, 1, uiContext.getString(R.string.wp_add_workspace), subtitle = uiContext.getString(R.string.wp_add_sheet_sub),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(top = 14.dp),
                    onClick = { scope.launch { container.workspace.currentProjectOnce(); adding = true } },
                    leading = { ExpressiveIconBadge(Icons.Outlined.Add, container = MaterialTheme.colorScheme.primaryContainer, tint = MaterialTheme.colorScheme.onPrimaryContainer) })
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) } }
        }
    }
    }
    if (adding) AddWorkspaceDialog(container, onDismiss = { finishAdd(null) },
        onPickSaf = { adding = false; picker.launch(null) }, onSelected = { finishAdd(it) }, activateWorkspace = false)
    deleting?.let { project -> ProjectDeletionDialog(container, project,
        onDismiss = { deleting = null }, onDeleted = { deleting = null; onProjectDeleted() }) }
}

@Composable
fun ProjectScreen(
    container: AppContainer,
    projectId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onNewChat: (String) -> Unit,
    creatingChat: Boolean,
    onMemory: () -> Unit,
    onAutomation: (String?, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenBuildTest: () -> Unit,
    onSendPrompt: (String) -> Unit,
    onProjectDeleted: () -> Unit = onBack,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val projects by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val sessions by container.sessions.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val tasks by container.automation.repository.tasks.collectAsStateWithLifecycle()
    val running by container.runManager.runningSessionIds.collectAsStateWithLifecycle()
    val checks by container.projectChecks.results.collectAsStateWithLifecycle()
    val terminal by container.terminal.state.collectAsStateWithLifecycle()
    val project = projects.firstOrNull { it.id == projectId }
    val chats = remember(sessions, projectId, settings.archivedSessions, settings.pinnedSessions) {
        sessions.filter { it.projectId == projectId && it.id !in settings.archivedSessions }
            .sortedByDescending { it.id in settings.pinnedSessions }
    }
    val projectTasks = remember(tasks, projectId) { tasks.filter { it.projectId == projectId } }
    var tab by rememberSaveable(projectId) { mutableIntStateOf(0) }
    var showRepository by rememberSaveable(projectId) { mutableStateOf(false) }
    var showDetails by rememberSaveable(projectId) { mutableStateOf(false) }
    var deletingProject by remember(projectId) { mutableStateOf<ProjectEntity?>(null) }
    var repository by remember(projectId) { mutableStateOf<String?>(null) }
    val environment by container.linuxEnv.state.collectAsStateWithLifecycle()
    var refresh by remember(projectId) { mutableIntStateOf(0) }
    var git by remember(projectId) { mutableStateOf<ProjectGitOverview?>(null) }
    var staticPreview by remember(projectId) { mutableStateOf<String?>(null) }
    var backgroundPreview by remember(projectId) { mutableStateOf<String?>(null) }
    var openPreview by remember(projectId) { mutableStateOf<String?>(null) }
    val workspace = remember(project) { project?.let { runCatching { container.workspace.fsFor(it) }.getOrNull() } }
    val launchPrefs = remember { container.appContext.getSharedPreferences("project_launch", android.content.Context.MODE_PRIVATE) }
    val serverCommand = launchPrefs.getString("command_$projectId", null)
    val serverRunning = serverCommand != null && terminal.lastCommand == serverCommand && terminal.busy
    val serverPreview = if (serverRunning && workspace !is com.androidharness.app.workspace.SshFs)
        com.androidharness.app.core.ProjectLaunch.previewUrl(terminal.lines.joinToString("\n")) else null
    val preview = serverPreview ?: backgroundPreview ?: staticPreview
    val projectRunning = sessions.any { it.projectId == projectId && it.id in running }
    LaunchedEffect(workspace, environment, refresh, projectRunning, showRepository) {
        val fs = workspace ?: return@LaunchedEffect
        git = null
        git = withContext(Dispatchers.IO) { loadProjectGitOverview(container, fs) }
        staticPreview = withContext(Dispatchers.IO) {
            listOf("index.html", "public/index.html").firstOrNull { runCatching { fs.resolve(it).isFile }.getOrDefault(false) }
        }
    }
    LaunchedEffect(workspace, refresh) {
        backgroundPreview = null
        val root = workspace?.shellRoot ?: return@LaunchedEffect
        val rootPath = withContext(Dispatchers.IO) { root.canonicalPath }
        while (isActive) {
            backgroundPreview = withContext(Dispatchers.IO) {
                try {
                    container.backgroundProcesses.list().map { it.first }.filter {
                        val cwd = java.io.File(it.cwd).canonicalPath
                        cwd == rootPath || cwd.startsWith("$rootPath/")
                    }.sortedByDescending { it.startedAt }.firstNotNullOfOrNull { job ->
                        runCatching {
                            // Logs can grow for days. Read only the tail while the overview is open.
                            java.io.RandomAccessFile(java.io.File(job.cwd, job.logPath), "r").use { log ->
                                val bytes = ByteArray(minOf(log.length(), 8_000L).toInt())
                                log.seek(log.length() - bytes.size)
                                log.readFully(bytes)
                                com.androidharness.app.core.ProjectLaunch.previewUrl(bytes.toString(Charsets.UTF_8))
                            }
                        }.getOrNull()
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }
            delay(5_000)
        }
    }
    openPreview?.let { target ->
        com.androidharness.app.ui.chat.components.WebPreviewSheet(initialTarget = target, workspace = workspace,
            browserController = container.browser, onSendPrompt = { openPreview = null; onSendPrompt(it) },
            onDismiss = { openPreview = null })
    }
    LaunchedEffect(project, environment, showRepository, refresh) {
        repository = null
        if (project != null && !showRepository && container.linuxEnv.gitToolsReady) {
            withContext(Dispatchers.IO) {
                try {
                    val fs = container.workspace.fsFor(project)
                    if (fs.shellRoot != null) container.githubRepositories.inspect(fs).remoteUrl.takeIf { it.isNotBlank() }
                    else null
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }?.let { repository = it }
        }
    }

    val ssh = remember(project) { project?.let { runCatching { container.workspace.fsFor(it) }.getOrNull() as? com.androidharness.app.workspace.SshFs } }
    val scheme = MaterialTheme.colorScheme
    SettingsExpressiveTheme {
    Scaffold(topBar = {
        Column {
            AppHeader(project?.name ?: uiContext.getString(R.string.wp_workspace_page), subtitle = project?.let { container.workspace.describe(it).kindLabel }, onBack = onBack,
                actions = { ExpressiveRefreshButton(uiContext.getString(R.string.wp_refresh_workspace), onClick = { refresh++ }) })
            com.androidharness.app.ui.common.SshWorkspaceBar(container, ssh, includeStatusBarPadding = false)
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp)) {
            if (project == null) {
                item { ExpressiveEmptyState(Icons.Outlined.FolderOff, uiContext.getString(R.string.wp_workspace_unavailable), seed = 4) }
            } else {
                item(key = "hero") {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SettingsHeroBadge(project.id.hashCode(), size = 72.dp) { tint -> Icon(workspaceKindIcon(project.kind), null, Modifier.size(32.dp), tint = tint) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(project.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(git?.branch?.let { uiContext.getString(R.string.ui_branch_1649, (it).toString()) } ?: git?.note ?: uiContext.getString(R.string.ui_checking_repository),
                                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                            git?.changedFiles?.let { count -> Text(if (count == 0) uiContext.getString(R.string.ui_working_tree_clean) else "$count changed ${if (count == 1) "file" else "files"}",
                                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                            if (git?.branch != null && git?.note != null) Text(git!!.note!!, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                }
                item(key = "open") {
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onOpenFiles, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(uiContext.getString(R.string.ui_files))
                        }
                        FilledTonalButton(onClick = onOpenTerminal, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                            Icon(Icons.Outlined.Terminal, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(uiContext.getString(R.string.ui_terminal))
                        }
                    }
                }
                item(key = "tools") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ProjectRow(uiContext.getString(R.string.ui_repository), repository ?: uiContext.getString(R.string.ui_connect_or_manage_repository), Icons.Outlined.AccountTree,
                            index = 0, count = 3, onClick = { showRepository = true })
                        val check = checks[projectId]
                        ProjectRow(uiContext.getString(R.string.ui_build_test), check?.let {
                            val status = when (it.status) {
                                com.androidharness.app.data.ProjectCheckStatus.RUNNING -> uiContext.getString(R.string.ui_running_1587)
                                com.androidharness.app.data.ProjectCheckStatus.PASSED -> uiContext.getString(R.string.ui_passed)
                                com.androidharness.app.data.ProjectCheckStatus.FAILED -> uiContext.getString(R.string.ui_failed_64)
                                com.androidharness.app.data.ProjectCheckStatus.INTERRUPTED -> uiContext.getString(R.string.ui_interrupted)
                            }
                            uiContext.getString(R.string.ui_last_check, (it.name).toString(), (status).toString(), (formatRelativeTime(it.updatedAt)).toString())
                        } ?: uiContext.getString(R.string.ui_no_checks_run_yet), Icons.Outlined.Build, index = 1, count = 3, onClick = onOpenBuildTest)
                        ProjectRow(uiContext.getString(R.string.ui_preview), when {
                            serverPreview != null || backgroundPreview != null -> uiContext.getString(R.string.ui_running_1654, (preview).toString())
                            serverRunning -> uiContext.getString(R.string.ui_server_running_open_build_test)
                            staticPreview != null -> uiContext.getString(R.string.ui_open_1656, (staticPreview).toString())
                            else -> uiContext.getString(R.string.ui_run_your_project_from_build_test)
                        }, Icons.Outlined.Language, index = 2, count = 3, onClick = { if (preview != null) openPreview = preview else onOpenBuildTest() })
                    }
                }
                item(key = "tabs") {
                    SettingsChoiceGroup(
                        options = listOf(0 to uiContext.getString(R.string.ui_chats), 1 to uiContext.getString(R.string.ui_automations)),
                        selected = { it == tab }, onSelect = { tab = it }, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
                }
                if (tab == 0) {
                    item(key = "new-chat") {
                        Button(onClick = { onNewChat(projectId) }, enabled = !creatingChat,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).heightIn(min = 56.dp)) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Text(if (creatingChat) uiContext.getString(R.string.ui_creating) else uiContext.getString(R.string.settings_telegram_new_chat), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (chats.isEmpty()) item { Text(uiContext.getString(R.string.wp_workspace_no_chats), color = scheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                    itemsIndexed(chats, key = { _, it -> it.id }) { index, session ->
                        ProjectRow(session.title, if (session.id in running) uiContext.getString(R.string.ui_running_1587) else formatRelativeTime(session.updatedAt),
                            Icons.Outlined.ChatBubbleOutline, index = index, count = chats.size, onClick = { onOpenChat(session.id) },
                            modifier = Modifier.animateItem(fadeInSpec = expressiveEffectsSpec(), placementSpec = expressiveSpatialSpec(), fadeOutSpec = expressiveEffectsSpec()))
                    }
                } else {
                    if (projectTasks.isEmpty()) item { Text(uiContext.getString(R.string.ui_no_automations_yet), color = scheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                    val extra = if (projectTasks.isNotEmpty()) 2 else 1
                    itemsIndexed(projectTasks, key = { _, it -> it.id }) { index, task ->
                        ProjectRow(task.title, task.projectStatus(), Icons.Outlined.Schedule, index = index, count = projectTasks.size + extra,
                            onClick = { onAutomation(task.id, false) })
                    }
                    item { ProjectRow(uiContext.getString(R.string.ui_add_automation), icon = Icons.Outlined.Add, index = projectTasks.size, count = projectTasks.size + extra, onClick = { onAutomation(null, true) }) }
                    if (projectTasks.isNotEmpty()) item { ProjectRow(uiContext.getString(R.string.ui_manage_automations), icon = Icons.Outlined.AutoMode, index = projectTasks.size + 1, count = projectTasks.size + extra, onClick = { onAutomation(null, false) }) }
                }
                item(key = "more") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ExpressiveSectionLabel(uiContext.getString(R.string.wp_workspace_more))
                        ProjectRow(uiContext.getString(R.string.wp_workspace_memory), icon = Icons.Outlined.Description, index = 0, count = 2, onClick = onMemory)
                        ProjectRow(uiContext.getString(R.string.wp_workspace_details), icon = Icons.Outlined.Tune, index = 1, count = 2, onClick = { showDetails = true })
                    }
                }
            }
        }
    }
    }
    if (showRepository && project != null) GitHubWorkspaceDialog(container, project.id, project.name,
        onOpenSettings = onOpenSettings, onDismiss = { showRepository = false })
    if (showDetails && project != null) ProjectDetailsDialog(container, project, onDismiss = { showDetails = false },
        onDelete = { showDetails = false; deletingProject = project })
    deletingProject?.let { target -> ProjectDeletionDialog(container, target,
        onDismiss = { deletingProject = null }, onDeleted = { deletingProject = null; onProjectDeleted() }) }
}

private fun AutomationTask.projectStatus(): String = when {
    activeRun != null -> "${lastStatus.name.lowercase().replaceFirstChar { it.titlecase() }} · This project"
    !enabled -> "Paused · This project"
    schedule == com.androidharness.app.automation.AutomationSchedule.MANUAL -> "Manual · This project"
    else -> "Scheduled · This project"
}

@Composable
internal fun ProjectRow(title: String, subtitle: String? = null, icon: ImageVector, index: Int = 0, count: Int = 1,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    ExpressiveListRow(index, count, title, modifier = modifier, subtitle = subtitle, onClick = onClick,
        leading = { ExpressiveIconBadge(icon, container = MaterialTheme.colorScheme.surfaceContainerHighest, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) })
}

@Composable
private fun ProjectDetailsDialog(container: AppContainer, project: ProjectEntity, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var name by rememberSaveable(project.id) { mutableStateOf(project.name) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SettingsExpressiveTheme {
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(uiContext.getString(R.string.wp_workspace_details)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { if (it.length <= 100) name = it }, label = { Text(uiContext.getString(R.string.wp_workspace_name)) },
                enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(uiContext.getString(R.string.ui_workspace), style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(runCatching { container.workspace.fsFor(project).displayPath }.getOrDefault(uiContext.getString(R.string.ui_unavailable)),
                style = MaterialTheme.typography.bodySmall) }
            if (project.kind != com.androidharness.app.workspace.WorkspaceManager.KIND_APP) {
                HorizontalDivider()
                TextButton(onClick = onDelete, enabled = !saving,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(uiContext.getString(R.string.wp_workspace_delete))
                }
            } else Text(uiContext.getString(R.string.ui_the_app_workspace_cannot_be_deleted), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !saving && name.isNotBlank(), onClick = {
            saving = true
            scope.launch {
                try { container.workspace.renameProject(project.id, name); onDismiss() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message }
                finally { saving = false }
            }
        }) { Text(if (saving) uiContext.getString(R.string.ui_saving) else uiContext.getString(R.string.settings_action_save)) }
    }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(uiContext.getString(R.string.settings_action_cancel)) } })
    }
}
