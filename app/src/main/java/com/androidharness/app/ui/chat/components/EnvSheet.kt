package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.androidharness.app.ui.settings.PackageManagerSheet
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.data.env.EnvState
import kotlinx.coroutines.launch

/**
 * Linux environment management from the chat: status, check-missing report,
 * update (installs anything missing/broken), install (fresh) and uninstall
 * (with confirmation). Mirrors the Settings card so the user never has to
 * leave the conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvSheet(
    container: AppContainer,
    envState: EnvState,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var checkResult by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var showPackagesSheet by remember { mutableStateOf(false) }
    val linuxEnv = container.linuxEnv

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Linux environment", style = MaterialTheme.typography.titleMedium)
            Text(
                when (envState) {
                    is EnvState.Ready -> "Installed"
                    is EnvState.Preparing -> "Resolving packages…"
                    is EnvState.Downloading -> "Downloading ${envState.pkg} (${envState.index + 1}/${envState.total})…"
                    is EnvState.Installing -> "Installing ${envState.pkg} (${envState.index + 1}/${envState.total})…"
                    is EnvState.Failed -> "Failed: ${envState.message}"
                    EnvState.NotInstalled -> "Not installed"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (envState is EnvState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (envState) {
                is EnvState.Ready -> {
                    val installed = linuxEnv.installedPackages()
                    if (installed.isNotEmpty()) {
                        Text(
                            "Packages (${installed.size}): ${installed.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    checkResult?.let { report ->
                        Text(
                            report,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (report.startsWith("All present")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FilledTonalButton(onClick = { showPackagesSheet = true }) {
                            Icon(Icons.Outlined.Inventory2, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Packages")
                        }
                        OutlinedButton(
                            enabled = !checking,
                            onClick = {
                                checking = true
                                scope.launch {
                                    checkResult = linuxEnv.checkMissing()
                                    checking = false
                                }
                            },
                        ) { Text(if (checking) "Checking…" else "Check missing") }
                        if (checkResult != null && !checkResult!!.startsWith("All present")) {
                            Button(onClick = {
                                checkResult = null
                                scope.launch { linuxEnv.updateEnvironment() }
                            }) { Text("Update") }
                        }
                    }
                    OutlinedButton(
                        onClick = { confirmUninstall = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Uninstall") }
                }
                is EnvState.NotInstalled, is EnvState.Failed -> {
                    Text(
                        if (envState is EnvState.Failed) "Fix the issue (usually network) and install again."
                        else "Installs bash, git, python 3, pip, node.js and npm into private storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { scope.launch { linuxEnv.install(linuxEnv.fullPackages) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (envState is EnvState.Failed) "Retry install" else "Install full environment") }
                }
                is EnvState.Downloading, is EnvState.Installing, is EnvState.Preparing -> {
                    com.androidharness.app.ui.common.ThinLinearProgress(Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text("Uninstall Linux environment?") },
            text = { Text("This deletes the whole toolchain (bash, git, python, node, npm and all downloaded packages). The agent falls back to toybox sh until it is installed again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    scope.launch { linuxEnv.uninstall() }
                }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text("Cancel") }
            },
        )
    }

    if (showPackagesSheet) {
        PackageManagerSheet(
            container = container,
            onDismiss = { showPackagesSheet = false },
        )
    }
}
