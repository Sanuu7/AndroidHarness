package com.androidharness.app.ui.common

import com.androidharness.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.data.env.PathClassifier
import kotlinx.coroutines.launch

/**
 * The ways to attach a workspace, shared by Settings and the chat workspace
 * switcher:
 * 1. App workspace. Private folder, shell always works.
 * 2. Device folder. Browsed in-app, no typing. Full shell.
 * 3. System picker (SAF). Works without broad storage access. Directly writable
 *    device folders are upgraded to full shell; other picks use file tools.
 */
@Composable
fun AddWorkspaceDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
    onPickSaf: () -> Unit,
    onSelected: (String) -> Unit = {},
    activateWorkspace: Boolean = true,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    ShizukuWorkspaceWarningEffect(container.shizuku)
    var destination by remember { mutableStateOf<String?>(null) }
    if (destination == "github") {
        com.androidharness.app.ui.github.GitHubImportDialog(container, onDismiss,
            onImported = onDismiss, activateWorkspace = activateWorkspace, onProjectImported = onSelected)
        return
    }
    if (destination == "ssh") {
        SshWorkspaceDialog(container, onDismiss = onDismiss, onSelected = onSelected, activateWorkspace = activateWorkspace)
        return
    }
    if (destination == null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(uiContext.getString(R.string.settings_search_add_workspace_title)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { destination = "github" }, modifier = Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.settings_github_import)) }
                Button(onClick = { destination = "local" }, modifier = Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.ui_on_this_device)) }
                OutlinedButton(onClick = { destination = "ssh" }, modifier = Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.ui_connect_through_ssh)) }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(uiContext.getString(R.string.settings_action_cancel)) } })
        return
    }
    val scope = rememberCoroutineScope()
    val projects = container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val appProject = projects.value.firstOrNull { it.kind == "APP" }
    var error by remember { mutableStateOf<String?>(null) }
    var showFolderBrowser by remember { mutableStateOf(false) }

    fun addBrowsedFolder(path: String) {
        val trimmed = path.trim()
        val assessment = container.workspace.assessPath(trimmed)
        when {
            !assessment.directoryExists ->
                error = uiContext.getString(R.string.ui_that_folder_could_not_be_read_try)
            assessment.region == PathClassifier.Region.APP_DATA ->
                error = uiContext.getString(R.string.ui_that_is_the_app_s_own_private)
            else -> scope.launch {
                onSelected(container.workspace.addShellProject(trimmed, activateWorkspace).id)
                onDismiss()
            }
        }
    }

    if (showFolderBrowser) {
        FolderPickerDialog(
            container = container,
            onPick = { picked ->
                showFolderBrowser = false
                addBrowsedFolder(picked)
            },
            onDismiss = { showFolderBrowser = false },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiContext.getString(R.string.ui_add_a_workspace)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    uiContext.getString(R.string.ui_the_workspace_is_where_the_agent_reads),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(uiContext.getString(R.string.ui_app_workspace), style = MaterialTheme.typography.titleSmall)
                Text(
                    uiContext.getString(R.string.ui_private_folder_only_this_app_can_see),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        if (appProject != null) scope.launch {
                            if (activateWorkspace) container.workspace.setActiveProject(appProject.id)
                            onSelected(appProject.id)
                            onDismiss()
                        } else onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(uiContext.getString(R.string.ui_use_app_workspace)) }

                HorizontalDivider()

                Text(uiContext.getString(R.string.ui_device_folder), style = MaterialTheme.typography.titleSmall)
                Text(
                    uiContext.getString(R.string.ui_any_folder_on_this_phone_or_sd) +
                        uiContext.getString(R.string.ui_browser_no_typing_needed_full_shell),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { showFolderBrowser = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(uiContext.getString(R.string.ui_browse_device_folders)) }

                HorizontalDivider()

                Text(uiContext.getString(R.string.ui_system_picker), style = MaterialTheme.typography.titleSmall)
                Text(
                    uiContext.getString(R.string.ui_choose_a_folder_without_granting_broad_storage) +
                        uiContext.getString(R.string.ui_work_in_the_selected_folder_shell_commands) +
                        uiContext.getString(R.string.ui_access_or_use_the_app_workspace),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onPickSaf, modifier = Modifier.fillMaxWidth()) {
                    Text(uiContext.getString(R.string.ui_open_system_picker))
                }

                error?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(uiContext.getString(R.string.settings_action_close)) }
        },
    )
}
