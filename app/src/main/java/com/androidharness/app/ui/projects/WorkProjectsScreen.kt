package com.androidharness.app.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.db.WorkProjectEntity
import com.androidharness.app.ui.chat.components.ProjectEditorSheet
import com.androidharness.app.ui.chat.components.projectWorkspaceCount
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.ExpressiveDropdownMenu
import com.androidharness.app.ui.common.ExpressiveDropdownMenuItem
import com.androidharness.app.ui.common.ExpressiveEmptyState
import com.androidharness.app.ui.common.ExpressiveIconBadge
import com.androidharness.app.ui.common.ExpressiveIconButton
import com.androidharness.app.ui.common.ExpressiveListRow
import com.androidharness.app.ui.common.ExpressivePill
import com.androidharness.app.ui.common.ExpressiveSectionLabel
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.common.formatRelativeTime
import com.androidharness.app.ui.common.workspaceKindIcon
import com.androidharness.app.ui.settings.SettingsExpressiveTheme
import com.androidharness.app.ui.settings.SettingsHeroBadge
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import com.androidharness.app.workspace.ChatWorkspaceSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun chatCount(context: android.content.Context, count: Int) =
    if (count == 1) context.getString(R.string.wp_chat_count_one) else context.getString(R.string.wp_chat_count, count)

/** Every project the user has made. Projects are never created for them. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkProjectsScreen(container: AppContainer, onBack: () -> Unit, onOpenProject: (String) -> Unit, onCreate: () -> Unit) {
    val context = LocalContext.current
    val projects by container.workspace.workProjects.collectAsStateWithLifecycle(initialValue = emptyList())
    val sessions by container.sessions.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val counts = remember(sessions) { sessions.groupingBy { it.workProjectId }.eachCount() }
    val list = rememberLazyListState()
    val atTop by remember { derivedStateOf { list.firstVisibleItemIndex == 0 } }
    SettingsExpressiveTheme {
        Scaffold(
            topBar = { AppHeader(context.getString(R.string.ui_projects), subtitle = context.getString(R.string.wp_projects_sub), onBack = onBack) },
            floatingActionButton = {
                ExtendedFloatingActionButton(onClick = onCreate, expanded = atTop,
                    icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(context.getString(R.string.wp_new_project)) })
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), state = list, verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp)) {
                itemsIndexed(projects, key = { _, it -> it.id }) { index, project ->
                    ExpressiveListRow(index, projects.size, project.name,
                        subtitle = projectWorkspaceCount(context, project) + " · " + chatCount(context, counts[project.id] ?: 0),
                        onClick = { onOpenProject(project.id) },
                        modifier = Modifier.animateItem(fadeInSpec = expressiveEffectsSpec(), placementSpec = expressiveSpatialSpec(), fadeOutSpec = expressiveEffectsSpec()),
                        leading = { ExpressiveIconBadge(Icons.Outlined.Layers) },
                        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) })
                }
                if (projects.isEmpty()) item { ExpressiveEmptyState(Icons.Outlined.Layers, context.getString(R.string.wp_projects_empty), seed = 1) }
            }
        }
    }
}

/** One project: the workspaces its chats work across, and those chats. */
@Composable
fun WorkProjectScreen(
    container: AppContainer,
    projectId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    creatingChat: Boolean,
    onOpenWorkspace: (String) -> Unit,
    onDelete: (WorkProjectEntity) -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val projects by container.workspace.workProjects.collectAsStateWithLifecycle(initialValue = null)
    val workspaces by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val sessions by container.sessions.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val running by container.runManager.runningSessionIds.collectAsStateWithLifecycle()
    val project = projects?.firstOrNull { it.id == projectId }
    val selection = remember(project) { project?.let { runCatching { ChatWorkspaceSelection.decode(it.selectionJson) }.getOrNull() } }
    val chats = remember(sessions, projectId, settings.archivedSessions, settings.pinnedSessions) {
        sessions.filter { it.workProjectId == projectId && it.id !in settings.archivedSessions }
            .sortedByDescending { it.id in settings.pinnedSessions }
    }
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    SettingsExpressiveTheme {
        Scaffold(topBar = {
            AppHeader(project?.name ?: context.getString(R.string.ui_project), subtitle = context.getString(R.string.ui_project), onBack = onBack, actions = {
                if (project != null) {
                    ExpressiveIconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Edit, context.getString(R.string.wp_editor_edit_title)) }
                    Box {
                        ExpressiveIconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, context.getString(R.string.ui_actions_for_project, project.name)) }
                        ExpressiveDropdownMenu(menu, { menu = false }) {
                            ExpressiveDropdownMenuItem(text = { Text(context.getString(R.string.ui_delete_project), color = scheme.error) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = scheme.error) },
                                onClick = { menu = false; onDelete(project) })
                        }
                    }
                }
            })
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp)) {
                if (projects != null && project == null) item { ExpressiveEmptyState(Icons.Outlined.LinkOff, context.getString(R.string.wp_project_unavailable), seed = 2) }
                if (project != null) {
                    item(key = "hero") {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            SettingsHeroBadge(project.id.hashCode(), size = 72.dp) { tint -> Icon(Icons.Outlined.Layers, null, Modifier.size(32.dp), tint = tint) }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(project.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(projectWorkspaceCount(context, project) + " · " + chatCount(context, chats.size),
                                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                    item(key = "workspaces-label") { ExpressiveSectionLabel(context.getString(R.string.wp_editor_workspaces), Modifier.padding(top = 0.dp)) }
                    val grants = selection?.grants.orEmpty()
                    itemsIndexed(grants, key = { _, it -> "workspace-${it.projectId}" }) { index, grant ->
                        val workspace = workspaces.firstOrNull { it.id == grant.projectId }
                        ExpressiveListRow(index, grants.size + 1, workspace?.name ?: context.getString(R.string.wp_editor_unavailable),
                            subtitle = workspace?.let { container.workspace.describe(it).kindLabel },
                            onClick = workspace?.let { { onOpenWorkspace(it.id) } },
                            modifier = Modifier.animateItem(fadeInSpec = expressiveEffectsSpec(), placementSpec = expressiveSpatialSpec(), fadeOutSpec = expressiveEffectsSpec()),
                            leading = {
                                if (workspace == null) ExpressiveIconBadge(Icons.Outlined.LinkOff, container = scheme.errorContainer, tint = scheme.onErrorContainer)
                                else ExpressiveIconBadge(workspaceKindIcon(workspace.kind), selected = grant.projectId == selection?.primaryId)
                            },
                            trailing = {
                                if (grant.projectId == selection?.primaryId) ExpressivePill(context.getString(R.string.ui_primary), strong = true)
                                else if (!grant.canEdit) ExpressivePill(context.getString(R.string.ui_read_only))
                            })
                    }
                    item(key = "add-workspace") {
                        ExpressiveListRow(grants.size, grants.size + 1, context.getString(R.string.wp_editor_add_workspace),
                            subtitle = context.getString(R.string.wp_edit_project_sub), onClick = { editing = true }, color = scheme.surfaceContainerHigh,
                            leading = { ExpressiveIconBadge(Icons.Outlined.Add, container = scheme.primaryContainer, tint = scheme.onPrimaryContainer) })
                    }
                    item(key = "new-chat") {
                        Button(onClick = onNewChat, enabled = !creatingChat, modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp).heightIn(min = 56.dp)) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Text(if (creatingChat) context.getString(R.string.ui_creating) else context.getString(R.string.wp_project_new_chat), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    item(key = "chats-label") { ExpressiveSectionLabel(context.getString(R.string.wp_project_chats)) }
                    itemsIndexed(chats, key = { _, it -> "chat-${it.id}" }) { index, chat ->
                        ExpressiveListRow(index, chats.size, chat.title,
                            subtitle = if (chat.id in running) context.getString(R.string.ui_running_1587) else formatRelativeTime(chat.updatedAt),
                            onClick = { onOpenChat(chat.id) },
                            modifier = Modifier.animateItem(fadeInSpec = expressiveEffectsSpec(), placementSpec = expressiveSpatialSpec(), fadeOutSpec = expressiveEffectsSpec()),
                            leading = { ExpressiveIconBadge(Icons.Outlined.ChatBubbleOutline, container = scheme.surfaceContainerHighest, tint = scheme.onSurfaceVariant) })
                    }
                    if (chats.isEmpty()) item(key = "no-chats") {
                        Text(context.getString(R.string.wp_project_no_chats), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (editing && project != null) ProjectEditorSheet(container, project, null, onDismiss = { editing = false }, onSaved = { editing = false })
}

/** Deletes a project with its chats. Workspaces and their files are not touched. */
@Composable
fun WorkProjectDeletionDialog(container: AppContainer, project: WorkProjectEntity, onDismiss: () -> Unit, onDeleted: (Set<String>) -> Unit) {
    val context = LocalContext.current
    val sessions by container.sessions.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val count = sessions.count { it.workProjectId == project.id }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SettingsExpressiveTheme {
        AlertDialog(
            onDismissRequest = { if (!deleting) onDismiss() },
            icon = { Icon(Icons.Outlined.DeleteOutline, null) },
            title = { Text(context.getString(R.string.wp_project_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (count == 0) context.getString(R.string.wp_project_delete_body_empty, project.name)
                        else context.getString(R.string.wp_project_delete_body, project.name, chatCount(context, count)))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !deleting, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = {
                    deleting = true
                    scope.launch {
                        try {
                            val removed = container.runManager.deleteWorkProject(project.id)
                            container.automation.repository.detachDeletedChats(removed)
                            container.settings.removeProjectReferences(project.id, removed)
                            onDeleted(removed)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: context.getString(R.string.ui_could_not_delete_this_project) }
                        finally { deleting = false }
                    }
                }) { Text(if (deleting) context.getString(R.string.ui_deleting) else context.getString(R.string.wp_project_delete_confirm)) }
            },
            dismissButton = { TextButton(enabled = !deleting, onClick = onDismiss) { Text(context.getString(R.string.ui_keep_project)) } },
        )
    }
}
