package com.androidharness.app.ui.chat.components

import com.androidharness.app.R
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.ui.common.PackageInstallProgress
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

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
    onDismiss: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var checkResult by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var showPackagesSheet by remember { mutableStateOf(false) }
    val linuxEnv = container.linuxEnv
    val envState = linuxEnv.state.collectAsStateWithLifecycle().value

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(uiContext.getString(R.string.settings_search_linux_title), style = MaterialTheme.typography.titleMedium)
            Text(
                when (envState) {
                    is EnvState.Ready -> uiContext.getString(R.string.ui_installed)
                    is EnvState.Preparing -> uiContext.getString(R.string.ui_resolving_packages)
                    is EnvState.Downloading -> uiContext.getString(R.string.ui_downloading_packages)
                    is EnvState.Installing -> uiContext.getString(R.string.ui_installing_packages)
                    is EnvState.Failed -> uiContext.getString(R.string.ui_failed, (envState.message).toString())
                    EnvState.NotInstalled -> uiContext.getString(R.string.ui_not_installed)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (envState is EnvState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (envState) {
                is EnvState.Ready -> {
                    val installed = linuxEnv.installedPackages()
                    if (installed.isNotEmpty()) {
                        Text(
                            uiContext.getString(R.string.ui_packages, (installed.size).toString(), (installed.joinToString(", ")).toString()),
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
                            Text(uiContext.getString(R.string.settings_env_packages))
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
                        ) { Text(if (checking) uiContext.getString(R.string.ui_checking) else uiContext.getString(R.string.ui_check_missing)) }
                        if (checkResult != null && !checkResult!!.startsWith("All present")) {
                            Button(onClick = {
                                checkResult = null
                                scope.launch { linuxEnv.updateEnvironment() }
                            }) { Text(uiContext.getString(R.string.settings_action_update)) }
                        }
                    }
                    OutlinedButton(
                        onClick = { confirmUninstall = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(uiContext.getString(R.string.settings_action_uninstall)) }
                }
                is EnvState.NotInstalled, is EnvState.Failed -> {
                    Text(
                        if (envState is EnvState.Failed) uiContext.getString(R.string.ui_fix_the_issue_usually_network_and_install)
                        else uiContext.getString(R.string.ui_installs_bash_git_python_3_pip_node),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { scope.launch { linuxEnv.install(linuxEnv.fullPackages) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (envState is EnvState.Failed) uiContext.getString(R.string.ui_retry_install) else uiContext.getString(R.string.ui_install_full_environment)) }
                }
                is EnvState.Downloading, is EnvState.Installing, is EnvState.Preparing -> {
                    PackageInstallProgress(envState)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text(uiContext.getString(R.string.settings_env_uninstall_title)) },
            text = { Text(uiContext.getString(R.string.settings_env_uninstall_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    scope.launch {
                        try { container.storage.uninstallToolchain() }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { checkResult = e.message }
                    }
                }) { Text(uiContext.getString(R.string.settings_action_uninstall), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text(uiContext.getString(R.string.settings_action_cancel)) }
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
