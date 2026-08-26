package com.androidharness.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * 3. System picker (SAF). Folders on internal storage or SD are upgraded to
 *    full shell automatically; only cloud picks stay file tools only.
 */
@Composable
fun AddWorkspaceDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
    onPickSaf: () -> Unit,
) {
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
                error = "That folder could not be read. Try another one."
            assessment.region == PathClassifier.Region.APP_DATA ->
                error = "That is the app's own private storage. Use the app workspace instead."
            else -> scope.launch {
                container.workspace.addShellProject(trimmed)
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
        title = { Text("Add a workspace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The workspace is where the agent reads and writes files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("App workspace", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Private folder only this app can see. Shell always works here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        if (appProject != null) scope.launch { container.workspace.setActiveProject(appProject.id) }
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use app workspace") }

                HorizontalDivider()

                Text("Device folder", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Any folder on this phone or SD card. Pick it in the built-in " +
                        "browser, no typing needed. Full shell.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { showFolderBrowser = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Browse device folders") }

                HorizontalDivider()

                Text("System picker", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Android's own folder picker. Folders on internal storage or " +
                        "SD cards become full-shell workspaces automatically. Cloud " +
                        "folders work with file tools only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onPickSaf, modifier = Modifier.fillMaxWidth()) {
                    Text("Open system picker")
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
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
