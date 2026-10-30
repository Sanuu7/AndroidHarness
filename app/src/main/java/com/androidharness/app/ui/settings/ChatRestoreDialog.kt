package com.androidharness.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.ChatBackupManager
import com.androidharness.app.data.ChatImportPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun ChatRestoreDialog(container: AppContainer, preview: ChatImportPreview, onDismiss: () -> Unit,
    onImported: (ChatBackupManager.ImportResult) -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val projects by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    var mapping by remember(preview) { mutableStateOf(emptyMap<String, String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            try { container.workspace.addPickedFolder(uri, activate = false) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.settings_restore_reconnect_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        R.string.settings_restore_choose_workspace,
                        if (preview.sessions.size == 1) stringResource(R.string.settings_restore_one_chat)
                        else stringResource(R.string.settings_restore_many_chats, preview.sessions.size.toString()),
                    ),
                )
                preview.missingWorkspaces.forEach { source ->
                    var expanded by remember(source.id) { mutableStateOf(false) }
                    Text(source.name, style = MaterialTheme.typography.titleSmall)
                    Box {
                        OutlinedButton(onClick = { expanded = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(projects.firstOrNull { it.id == mapping[source.id] }?.name ?: uiContext.getString(R.string.ui_choose_workspace))
                        }
                        DropdownMenu(expanded, { expanded = false }) {
                            projects.forEach { project -> DropdownMenuItem(text = { Text(project.name) }, onClick = {
                                mapping = mapping + (source.id to project.id); expanded = false; error = null
                            }) }
                        }
                    }
                }
                TextButton(onClick = { adding = true }, enabled = !busy) { Text(stringResource(R.string.settings_workspace_add)) }
                Text(stringResource(R.string.settings_restore_reconnect_note), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && preview.missingWorkspaces.all { source -> projects.any { it.id == mapping[source.id] } }, onClick = {
                busy = true; error = null
                scope.launch {
                    try { onImported(container.chatBackup.importPrepared(preview, mapping)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: uiContext.getString(R.string.ui_could_not_restore_chats) }
                    finally { busy = false }
                }
            }) { Text(if (busy) uiContext.getString(R.string.ui_restoring) else uiContext.getString(R.string.ui_restore_chats)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } })
    if (adding) com.androidharness.app.ui.common.AddWorkspaceDialog(container, onDismiss = { adding = false },
        onSelected = { adding = false }, onPickSaf = { adding = false; picker.launch(null) }, activateWorkspace = false)
}
