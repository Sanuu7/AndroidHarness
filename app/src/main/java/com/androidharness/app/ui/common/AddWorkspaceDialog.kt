package com.androidharness.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.androidharness.app.workspace.PathAssessment
import kotlinx.coroutines.launch

/**
 * The three ways to attach a workspace, shared by Settings and the chat
 * workspace switcher:
 * 1. App workspace — private folder, shell always works.
 * 2. Folder path — any real path; PRIVILEGED: needs Shizuku (or "All files
 *    access") for the shell to run there.
 * 3. Folder picker — SAF; file tools only, shell stays in the app workspace.
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
    var path by remember { mutableStateOf("") }
    var assessment by remember { mutableStateOf<PathAssessment?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showFolderBrowser by remember { mutableStateOf(false) }

    fun assess() {
        error = null
        val a = container.workspace.assessPath(path.trim())
        assessment = a
        if (!a.directoryExists) {
            error = "That folder doesn't exist. Check the path."
        } else when (a.region) {
            PathClassifier.Region.APP_DATA -> error = "That's the app's internal storage — use the App workspace instead."
            PathClassifier.Region.SHARED_STORAGE -> {
                val allFiles = container.shellRouter.isAllFilesAccess()
                if (!allFiles && !container.shizuku.isGranted()) {
                    error = "Shell here needs Shizuku or \"All files access\". You can still add it, but shell may be denied."
                }
            }
            PathClassifier.Region.SYSTEM -> {
                if (!container.shizuku.isGranted()) {
                    error = "System paths need Shizuku to be running and granted."
                }
            }
            null -> Unit
        }
    }

    if (showFolderBrowser) {
        FolderPickerDialog(
            container = container,
            onPick = { picked ->
                path = picked
                showFolderBrowser = false
                assess()
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

                Text("App workspace (most private)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "A folder only this app can see. Shell always works here.",
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

                Text("Folder path (full shell)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Any folder on the device. Privileged: the shell needs " +
                        "Shizuku or \"All files access\" to run here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it; error = null; assessment = null },
                    label = { Text("Folder path") },
                    placeholder = { Text("/storage/emulated/0/Projects/my-app") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { showFolderBrowser = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Browse folders…") }
                if (error == null && assessment != null && assessment!!.directoryExists) {
                    Button(
                        onClick = {
                            scope.launch {
                                container.workspace.addShellProject(path.trim())
                                onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add this folder") }
                } else if (path.isNotBlank()) {
                    OutlinedButton(onClick = { assess() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Check path")
                    }
                }

                HorizontalDivider()

                Text("Pick a folder (file tools only)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Uses the system picker. Folders on internal storage or SD " +
                        "cards are upgraded to full shell automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onPickSaf, modifier = Modifier.fillMaxWidth()) {
                    Text("Open folder picker…")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
