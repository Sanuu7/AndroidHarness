package com.androidharness.app.ui.chat.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.db.WorkProjectEntity
import com.androidharness.app.ui.common.AddWorkspaceDialog
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.ExpressiveIconBadge
import com.androidharness.app.ui.common.ExpressiveListRow
import com.androidharness.app.ui.common.ExpressiveSectionLabel
import com.androidharness.app.ui.common.ExpressiveSheetHeader
import com.androidharness.app.ui.common.workspaceKindIcon
import com.androidharness.app.ui.settings.SettingsChoiceGroup
import com.androidharness.app.ui.settings.SettingsExpressiveTheme
import com.androidharness.app.ui.theme.HarnessGlassModalBottomSheet as ModalBottomSheet
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import com.androidharness.app.workspace.ChatWorkspaceGrant
import com.androidharness.app.workspace.ChatWorkspaceSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Creates a project or edits one: its name and the workspaces its chats work across.
 * This is the only place where several workspaces are put together, where one is made
 * read only, and where the primary workspace is chosen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProjectEditorSheet(
    container: AppContainer,
    project: WorkProjectEntity?,
    initialWorkspaceId: String?,
    onDismiss: () -> Unit,
    onSaved: (WorkProjectEntity) -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val workspaces by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    var name by rememberSaveable(project?.id) { mutableStateOf(project?.name.orEmpty()) }
    // Kept as raw JSON so an empty draft survives while the user is still choosing.
    var draftJson by rememberSaveable(project?.id) {
        val start = project?.let { runCatching { ChatWorkspaceSelection.decode(it.selectionJson) }.getOrNull() }
            ?: ChatWorkspaceSelection(initialWorkspaceId.orEmpty(), listOfNotNull(initialWorkspaceId?.let { ChatWorkspaceGrant(it, true) }))
        mutableStateOf(Json.encodeToString(ChatWorkspaceSelection.serializer(), start))
    }
    val draft = remember(draftJson) { Json.decodeFromString(ChatWorkspaceSelection.serializer(), draftJson) }
    fun update(value: ChatWorkspaceSelection) {
        val primary = if (value.grants.any { it.projectId == value.primaryId }) value.primaryId else value.grants.firstOrNull()?.projectId.orEmpty()
        draftJson = Json.encodeToString(ChatWorkspaceSelection.serializer(), value.copy(primaryId = primary))
    }
    fun include(id: String) { if (draft.grants.none { it.projectId == id }) update(draft.copy(grants = draft.grants + ChatWorkspaceGrant(id, true))) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            try { include(container.workspace.addPickedFolder(uri, activate = false).id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: context.getString(R.string.ui_could_not_add_workspace) }
        }
    }
    SettingsExpressiveTheme {
        ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().imePadding()) {
                ExpressiveSheetHeader(context.getString(if (project == null) R.string.wp_editor_new_title else R.string.wp_editor_edit_title),
                    context.getString(R.string.wp_editor_sub), context.getString(R.string.ui_close_without_saving), onDismiss, enabled = !saving)
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)) {
                    item(key = "name") {
                        TextField(name, { if (it.length <= 100) name = it }, enabled = !saving, singleLine = true,
                            label = { Text(context.getString(R.string.wp_editor_name)) }, shape = RoundedCornerShape(20.dp),
                            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent, focusedContainerColor = scheme.surfaceContainerHigh,
                                unfocusedContainerColor = scheme.surfaceContainerHigh),
                            modifier = Modifier.fillMaxWidth())
                    }
                    item(key = "label") { ExpressiveSectionLabel(context.getString(R.string.wp_editor_workspaces), Modifier.padding(top = 0.dp)) }
                    items(workspaces, key = { it.id }) { workspace ->
                        val grant = draft.grants.firstOrNull { it.projectId == workspace.id }
                        val primary = grant != null && draft.primaryId == workspace.id
                        Column(Modifier.animateItem(fadeInSpec = expressiveEffectsSpec(), placementSpec = expressiveSpatialSpec(), fadeOutSpec = expressiveEffectsSpec())) {
                            ExpressiveListRow(0, 1, workspace.name, subtitle = container.workspace.describe(workspace).kindLabel,
                                selected = grant != null, enabled = !saving,
                                onClick = {
                                    update(draft.copy(grants = if (grant == null) draft.grants + ChatWorkspaceGrant(workspace.id, true)
                                        else draft.grants.filterNot { it.projectId == workspace.id }))
                                },
                                leading = { ExpressiveIconBadge(if (grant != null) Icons.Rounded.Check else workspaceKindIcon(workspace.kind), selected = grant != null) })
                            AnimatedVisibility(grant != null,
                                enter = expandVertically(expressiveSpatialSpec()) + fadeIn(expressiveEffectsSpec()),
                                exit = shrinkVertically(expressiveSpatialSpec()) + fadeOut(expressiveEffectsSpec())) {
                                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SettingsChoiceGroup(
                                        options = listOf(true to context.getString(R.string.ui_can_edit), false to context.getString(R.string.ui_read_only)),
                                        selected = { it == (grant?.canEdit ?: true) },
                                        onSelect = { canEdit -> update(draft.copy(grants = draft.grants.map { if (it.projectId == workspace.id) it.copy(canEdit = canEdit) else it })) },
                                        modifier = Modifier.weight(1f), enabled = !saving)
                                    ToggleButton(checked = primary, onCheckedChange = { if (!primary) update(draft.copy(primaryId = workspace.id)) }, enabled = !saving) {
                                        Icon(Icons.Rounded.Star, null, Modifier.size(18.dp))
                                        Text(context.getString(if (primary) R.string.wp_editor_primary else R.string.wp_editor_make_primary), Modifier.padding(start = 6.dp), maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                    items(draft.grants.filter { grant -> workspaces.none { it.id == grant.projectId } }, key = { "missing-${it.projectId}" }) { grant ->
                        ExpressiveListRow(0, 1, context.getString(R.string.wp_editor_unavailable), subtitle = context.getString(R.string.wp_editor_unavailable_sub),
                            enabled = !saving, onClick = { update(draft.copy(grants = draft.grants - grant)) },
                            leading = { ExpressiveIconBadge(Icons.Outlined.LinkOff, container = scheme.errorContainer, tint = scheme.onErrorContainer) })
                    }
                    item(key = "add") {
                        ExpressiveListRow(0, 1, context.getString(R.string.wp_editor_add_workspace), subtitle = context.getString(R.string.wp_editor_add_workspace_sub),
                            enabled = !saving, onClick = { adding = true }, color = scheme.surfaceContainerHigh,
                            leading = { ExpressiveIconBadge(Icons.Outlined.Add, container = scheme.primaryContainer, tint = scheme.onPrimaryContainer) })
                    }
                    item(key = "hints") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (draft.grants.size > 1) Text(context.getString(R.string.wp_editor_primary_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            if (draft.grants.any { !it.canEdit }) Text(context.getString(R.string.wp_editor_read_only_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            if (draft.grants.size > 1) Text(context.getString(R.string.ui_workspace_mcp_servers_aren_t_available_in), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                }
                error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                Button(enabled = !saving && name.isNotBlank() && draft.grants.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).heightIn(min = 56.dp), onClick = {
                        saving = true; error = null
                        scope.launch {
                            try {
                                val selection = draft.validated()
                                val saved = if (project == null) container.workspace.createWorkProject(name, selection) else {
                                    container.runManager.assignProjectWorkspaces(project.id, selection)
                                    container.workspace.renameWorkProject(project.id, name)
                                    requireNotNull(container.workspace.workProject(project.id))
                                }
                                onSaved(saved)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: context.getString(R.string.wp_editor_error) }
                            finally { saving = false }
                        }
                    }) {
                    Text(context.getString(when { saving -> R.string.wp_editor_saving; project == null -> R.string.wp_editor_create; else -> R.string.wp_editor_save }),
                        style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
    if (adding) AddWorkspaceDialog(container, onDismiss = { adding = false },
        onSelected = { adding = false; include(it) }, onPickSaf = { adding = false; picker.launch(null) }, activateWorkspace = false)
}
