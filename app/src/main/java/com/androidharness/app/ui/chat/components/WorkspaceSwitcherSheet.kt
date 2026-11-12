package com.androidharness.app.ui.chat.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.data.db.WorkProjectEntity
import com.androidharness.app.ui.common.AddWorkspaceDialog
import com.androidharness.app.ui.common.ExpressiveIconBadge
import com.androidharness.app.ui.common.ExpressiveIconButton
import com.androidharness.app.ui.common.ExpressiveListRow
import com.androidharness.app.ui.common.ExpressivePill
import com.androidharness.app.ui.common.ExpressiveSectionLabel
import com.androidharness.app.ui.common.ExpressiveSheetHeader
import com.androidharness.app.ui.common.workspaceKindIcon
import com.androidharness.app.ui.settings.SettingsExpressiveTheme
import com.androidharness.app.ui.theme.HarnessGlassModalBottomSheet as ModalBottomSheet
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import com.androidharness.app.workspace.ChatWorkspaceSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class SwitcherStage { LIST, ADD, PICKING, NEW_PROJECT, MOVE, EDIT_PROJECT }

/**
 * The workspace control of a chat, the file manager and the sidebar.
 *
 * An ordinary chat has one workspace, so this is a plain list: tap a folder to switch.
 * Working across several folders is what a project is for, so instead of attaching
 * more workspaces to the chat the sheet offers to make a project or move the chat
 * into one. A chat that already belongs to a project shows the project's workspaces
 * and a way to edit them.
 *
 * Stays composed until [onDismiss]; the folder picker and the follow-up sheets live here.
 */
@Composable
fun WorkspaceSwitcher(
    container: AppContainer,
    sessionId: String?,
    currentWorkspaceId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
    onOpenProject: ((String) -> Unit)? = null,
    onManageWorkspaces: (() -> Unit)? = null,
    /** False where there is no chat at all, such as a workspace page: only switching is offered. */
    chatContext: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workspaces by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val projects by container.workspace.workProjects.collectAsStateWithLifecycle(initialValue = emptyList())
    val chatProject by remember(sessionId) { container.workspace.workProjectForChat(sessionId) }.collectAsStateWithLifecycle(initialValue = null)
    var stage by remember { mutableStateOf(SwitcherStage.LIST) }
    fun fail(e: Exception) = onError(e.message ?: context.getString(R.string.ui_could_not_add_workspace))
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) onDismiss() else scope.launch {
            try { onSelect(container.workspace.addPickedFolder(uri, activate = sessionId == null).id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
            onDismiss()
        }
    }
    when (stage) {
        SwitcherStage.LIST -> WorkspaceSwitcherSheet(
            workspaces = workspaces, currentWorkspaceId = currentWorkspaceId, chat = sessionId != null,
            project = chatProject, describe = container.workspace::describe,
            onSelect = { onSelect(it); onDismiss() },
            onAddFolder = { stage = SwitcherStage.ADD },
            onNewProject = if (chatContext) ({ stage = SwitcherStage.NEW_PROJECT }) else null,
            onMoveToProject = if (chatContext && sessionId != null && projects.isNotEmpty()) ({ stage = SwitcherStage.MOVE }) else null,
            onEditProject = { stage = SwitcherStage.EDIT_PROJECT },
            onManage = onManageWorkspaces?.let { { onDismiss(); it() } },
            onDismiss = onDismiss,
            onDelete = { workspace ->
                scope.launch {
                    try { container.workspace.deleteProject(workspace) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { fail(e) }
                }
            },
        )
        SwitcherStage.ADD -> AddWorkspaceDialog(container, onDismiss = onDismiss,
            onPickSaf = { stage = SwitcherStage.PICKING; picker.launch(null) }, onSelected = { onSelect(it) }, activateWorkspace = sessionId == null)
        SwitcherStage.PICKING -> Unit
        SwitcherStage.NEW_PROJECT -> ProjectEditorSheet(container, project = null, initialWorkspaceId = currentWorkspaceId,
            onDismiss = onDismiss, onSaved = { created ->
                scope.launch {
                    try {
                        if (sessionId != null) container.runManager.moveChatToProject(sessionId, created.id)
                        else onOpenProject?.invoke(created.id)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { fail(e); onOpenProject?.invoke(created.id) }
                    onDismiss()
                }
            })
        SwitcherStage.EDIT_PROJECT -> chatProject?.let { ProjectEditorSheet(container, project = it, initialWorkspaceId = null,
            onDismiss = onDismiss, onSaved = { onDismiss() }) } ?: onDismiss()
        SwitcherStage.MOVE -> ProjectChoiceSheet(projects, onDismiss = onDismiss, onChoose = { target ->
            scope.launch {
                try { container.runManager.moveChatToProject(requireNotNull(sessionId), target.id) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { fail(e) }
                onDismiss()
            }
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceSwitcherSheet(
    workspaces: List<ProjectEntity>,
    currentWorkspaceId: String?,
    chat: Boolean,
    project: WorkProjectEntity?,
    describe: (ProjectEntity) -> com.androidharness.app.workspace.WorkspaceDescription,
    onSelect: (String) -> Unit,
    onAddFolder: () -> Unit,
    onNewProject: (() -> Unit)?,
    onMoveToProject: (() -> Unit)?,
    onEditProject: () -> Unit,
    onManage: (() -> Unit)?,
    onDismiss: () -> Unit,
    onDelete: (ProjectEntity) -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    SettingsExpressiveTheme {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
                val selection = remember(project) { project?.let { runCatching { ChatWorkspaceSelection.decode(it.selectionJson) }.getOrNull() } }
                ExpressiveSheetHeader(
                    title = project?.name ?: context.getString(R.string.wp_switch_title),
                    subtitle = when {
                        project != null -> context.getString(R.string.wp_project_chat_sub, project.name)
                        chat -> context.getString(R.string.wp_switch_chat_sub)
                        else -> context.getString(R.string.wp_switch_default_sub)
                    },
                    closeLabel = context.getString(R.string.settings_action_close), onClose = onDismiss,
                )
                // A bounded list keeps the sheet's drag gesture from fighting the scroll.
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
                    if (project != null && selection != null) {
                        val members = selection.grants.mapNotNull { grant -> workspaces.firstOrNull { it.id == grant.projectId }?.let { it to grant } }
                        itemsIndexed(members, key = { _, it -> it.first.id }) { index, (workspace, grant) ->
                            ExpressiveListRow(index, members.size, workspace.name, subtitle = describe(workspace).kindLabel,
                                modifier = Modifier.animateItem(fadeInSpec = expressiveEffectsSpec(), placementSpec = expressiveSpatialSpec(), fadeOutSpec = expressiveEffectsSpec()),
                                leading = { ExpressiveIconBadge(workspaceKindIcon(workspace.kind), selected = workspace.id == selection.primaryId) },
                                trailing = {
                                    if (workspace.id == selection.primaryId) ExpressivePill(context.getString(R.string.ui_primary), strong = true)
                                    else if (!grant.canEdit) ExpressivePill(context.getString(R.string.ui_read_only))
                                })
                        }
                        item(key = "edit-gap") { ExpressiveSectionLabel(context.getString(R.string.ui_project)) }
                        item(key = "edit") {
                            ExpressiveListRow(0, 1, context.getString(R.string.wp_edit_project), subtitle = context.getString(R.string.wp_edit_project_sub),
                                onClick = onEditProject, leading = { ExpressiveIconBadge(Icons.Outlined.Edit, container = scheme.primaryContainer, tint = scheme.onPrimaryContainer) })
                        }
                    } else {
                        itemsIndexed(workspaces, key = { _, it -> it.id }) { index, workspace ->
                            val current = workspace.id == currentWorkspaceId
                            val description = describe(workspace)
                            ExpressiveListRow(index, workspaces.size, workspace.name,
                                subtitle = description.kindLabel + " · " + context.getString(if (description.shellCapable) R.string.wp_full_shell else R.string.wp_file_tools),
                                selected = current, onClick = { onSelect(workspace.id) },
                                modifier = Modifier.animateItem(fadeInSpec = expressiveEffectsSpec(), placementSpec = expressiveSpatialSpec(), fadeOutSpec = expressiveEffectsSpec()),
                                leading = { ExpressiveIconBadge(workspaceKindIcon(workspace.kind), selected = current) },
                                trailing = {
                                    if (current) ExpressivePill(context.getString(R.string.wp_current), strong = true)
                                    else if (workspace.kind != "APP") ExpressiveIconButton(onClick = { onDelete(workspace) }) {
                                        Icon(Icons.Outlined.DeleteOutline, context.getString(R.string.wp_remove_workspace, workspace.name),
                                            Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                                    }
                                })
                        }
                        val actions = buildList {
                            add(Triple(Icons.Outlined.CreateNewFolder, R.string.wp_add_folder to R.string.wp_add_folder_sub, onAddFolder))
                            onNewProject?.let { add(Triple(Icons.Outlined.Layers, R.string.wp_new_project to (if (chat) R.string.wp_new_project_from_chat_sub else R.string.wp_new_project_sub), it)) }
                            onMoveToProject?.let { add(Triple(Icons.AutoMirrored.Outlined.DriveFileMove, R.string.wp_move_to_project to R.string.wp_move_to_project_sub, it)) }
                            onManage?.let { add(Triple(Icons.Outlined.Tune, R.string.wp_manage_workspaces to R.string.wp_manage_workspaces_sub, it)) }
                        }
                        item(key = "actions-gap") { ExpressiveSectionLabel(context.getString(R.string.wp_workspace_more)) }
                        itemsIndexed(actions, key = { _, it -> "action-${it.second.first}" }) { index, (icon, text, action) ->
                            ExpressiveListRow(index, actions.size, context.getString(text.first), subtitle = context.getString(text.second), onClick = action,
                                leading = { ExpressiveIconBadge(icon, container = if (index == 0) scheme.primaryContainer else scheme.tertiaryContainer,
                                    tint = if (index == 0) scheme.onPrimaryContainer else scheme.onTertiaryContainer) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectChoiceSheet(projects: List<WorkProjectEntity>, onDismiss: () -> Unit, onChoose: (WorkProjectEntity) -> Unit) {
    val context = LocalContext.current
    SettingsExpressiveTheme {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
                ExpressiveSheetHeader(context.getString(R.string.wp_choose_project), context.getString(R.string.wp_choose_project_sub),
                    context.getString(R.string.settings_action_close), onDismiss)
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
                    itemsIndexed(projects, key = { _, it -> it.id }) { index, project ->
                        ExpressiveListRow(index, projects.size, project.name, subtitle = projectWorkspaceCount(context, project),
                            onClick = { onChoose(project) }, leading = { ExpressiveIconBadge(Icons.Outlined.Layers) })
                    }
                }
            }
        }
    }
}

internal fun projectWorkspaceCount(context: android.content.Context, project: WorkProjectEntity): String {
    val count = runCatching { ChatWorkspaceSelection.decode(project.selectionJson).grants.size }.getOrDefault(0)
    return if (count == 1) context.getString(R.string.wp_workspace_count_one) else context.getString(R.string.wp_workspace_count, count)
}
