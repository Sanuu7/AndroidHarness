package com.androidharness.app.ui.chat.components

import com.androidharness.app.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.workspace.ChatWorkspaceGrant
import com.androidharness.app.workspace.ChatWorkspaceSelection
import com.androidharness.app.workspace.WorkspaceDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.RadioButton
import com.androidharness.app.ui.common.TextButton

@Composable
fun ChatWorkspaceManagerSheet(
    container: com.androidharness.app.AppContainer,
    sessionId: String?,
    onDismiss: () -> Unit,
    onSave: (suspend (ChatWorkspaceSelection) -> Unit)? = null,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val projects by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val selected by remember(sessionId) { container.workspace.selectionForChat(sessionId) }.collectAsStateWithLifecycle(initialValue = null)
    val primary by remember(sessionId) { container.workspace.projectForChat(sessionId) }.collectAsStateWithLifecycle(initialValue = null)
    val initial = selected ?: primary?.let { ChatWorkspaceSelection.single(it.id) }
        ?: projects.firstOrNull()?.let { ChatWorkspaceSelection.single(it.id) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            try { container.workspace.addPickedFolder(uri, activate = false) }
            catch (e: Exception) { android.widget.Toast.makeText(context, e.message ?: uiContext.getString(R.string.ui_could_not_add_workspace), android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    if (initial != null) ChatWorkspacesSheet(projects, initial, container.workspace::describe,
        onSave = { value -> if (onSave != null) onSave(value) else container.runManager.assignWorkspaces(requireNotNull(sessionId), value) },
        onDismiss = onDismiss, onAdd = { adding = true })
    if (adding) com.androidharness.app.ui.common.AddWorkspaceDialog(container,
        onSelected = { adding = false }, onDismiss = { adding = false },
        onPickSaf = { adding = false; picker.launch(null) }, activateWorkspace = false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatWorkspacesSheet(
    projects: List<ProjectEntity>,
    selection: ChatWorkspaceSelection,
    describe: (ProjectEntity) -> WorkspaceDescription,
    onSave: suspend (ChatWorkspaceSelection) -> Unit,
    onDismiss: () -> Unit,
    onAdd: (() -> Unit)? = null,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var draftJson by rememberSaveable(selection) { mutableStateOf(ChatWorkspaceSelection.encode(selection)) }
    // Empty drafts are allowed while choosing; validation belongs to Save.
    val draft = remember(draftJson) { kotlinx.serialization.json.Json.decodeFromString<ChatWorkspaceSelection>(draftJson) }
    fun update(value: ChatWorkspaceSelection) { draftJson = kotlinx.serialization.json.Json.encodeToString(ChatWorkspaceSelection.serializer(), value) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(uiContext.getString(R.string.ui_workspaces_for_this_chat), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss, enabled = !saving) { Icon(Icons.Filled.Close, uiContext.getString(R.string.ui_close_without_saving)) }
            }
            Text(uiContext.getString(R.string.ui_choose_access_for_each_project_primary_is),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 430.dp)) {
                items(projects, key = { it.id }) { project ->
                    val grant = draft.grants.firstOrNull { it.projectId == project.id }
                    var accessMenu by remember { mutableStateOf(false) }
                    val desc = describe(project)
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth().toggleable(value = grant != null, enabled = !saving, role = Role.Checkbox) { checked ->
                            val grants = if (checked) draft.grants + ChatWorkspaceGrant(project.id) else draft.grants.filterNot { it.projectId == project.id }
                            update(draft.copy(grants = grants, primaryId = if (grants.any { it.projectId == draft.primaryId }) draft.primaryId else grants.firstOrNull()?.projectId.orEmpty()))
                        }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = grant != null, onCheckedChange = null, modifier = Modifier.padding(end = 10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(project.name, style = MaterialTheme.typography.titleSmall)
                                Text(desc.kindLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (grant != null) FlowRow(Modifier.padding(start = 30.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Box {
                                OutlinedButton(onClick = { accessMenu = true }, enabled = !saving,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.semantics { contentDescription = uiContext.getString(R.string.ui_access, (project.name).toString(), (if (grant.canEdit) uiContext.getString(R.string.ui_can_edit) else uiContext.getString(R.string.ui_read_only)).toString()) }) {
                                    Text(if (grant.canEdit) uiContext.getString(R.string.ui_can_edit) else uiContext.getString(R.string.ui_read_only), style = MaterialTheme.typography.labelMedium)
                                    Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp))
                                }
                                DropdownMenu(accessMenu, { accessMenu = false }) {
                                    listOf(false to uiContext.getString(R.string.ui_read_only), true to uiContext.getString(R.string.ui_can_edit)).forEach { (canEdit, label) ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = {
                                            update(draft.copy(grants = draft.grants.map { if (it.projectId == project.id) it.copy(canEdit = canEdit) else it }))
                                            accessMenu = false
                                        })
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.toggleable(draft.primaryId == project.id, enabled = !saving, role = Role.RadioButton) {
                                    update(draft.copy(primaryId = project.id))
                                }.heightIn(min = 44.dp).semantics { contentDescription = uiContext.getString(R.string.ui_primary_workspace, (project.name).toString()) }) {
                                RadioButton(draft.primaryId == project.id, onClick = null)
                                Text(uiContext.getString(R.string.ui_primary), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                }
                val missing = draft.grants.filter { grant -> projects.none { it.id == grant.projectId } }
                items(missing, key = { "missing-${it.projectId}" }) { grant ->
                    TextButton(enabled = !saving, onClick = {
                        val remaining = draft.grants - grant
                        update(draft.copy(grants = remaining, primaryId = if (draft.primaryId == grant.projectId) remaining.firstOrNull()?.projectId.orEmpty() else draft.primaryId))
                    }) { Text(uiContext.getString(R.string.ui_remove_unavailable_workspace)) }
                }
                if (onAdd != null) item { TextButton(onClick = onAdd, enabled = !saving) { Text(uiContext.getString(R.string.ui_add_workspace)) } }
            }
            if (draft.grants.any { !it.canEdit }) Text(uiContext.getString(R.string.ui_commands_and_integrations_require_edit_access_to),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp))
            if (draft.grants.size > 1) Text(uiContext.getString(R.string.ui_workspace_mcp_servers_aren_t_available_in),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            Button(enabled = !saving && draft.grants.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), onClick = {
                saving = true; error = null
                scope.launch {
                    try { onSave(draft.validated()); onDismiss() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: uiContext.getString(R.string.ui_could_not_save_workspace_access) }
                    finally { saving = false }
                }
            }) { Text(if (saving) uiContext.getString(R.string.ui_saving) else uiContext.getString(R.string.ui_use_workspace, (draft.grants.size).toString(), (if (draft.grants.size == 1) "" else "s").toString())) }
        }
    }
}
