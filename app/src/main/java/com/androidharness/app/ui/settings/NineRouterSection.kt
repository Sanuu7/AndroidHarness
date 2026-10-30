package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.router.*
import com.androidharness.app.ui.common.AppLinearProgressIndicator
import kotlinx.coroutines.delay
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun NineRouterSection(container: AppContainer) {
    val manager = remember(container) { container.nineRouter }
    val state by manager.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(manager) { manager.checkUpdates(automatic = true); while (true) { manager.refresh(); delay(3000) } }
    NineRouterPanel(state, manager::installLinux, manager::install, manager::start, manager::stop,
        { runCatching { uri.openUri(NineRouterManager.DASHBOARD) }.onFailure { android.widget.Toast.makeText(context, R.string.nine_router_browser_error, android.widget.Toast.LENGTH_LONG).show() } },
        onCheckUpdate = { manager.checkUpdates() }, onUpdate = manager::update, onUninstall = manager::uninstall,
        onAutoStart = manager::setAutoStart, onAddProvider = manager::addProvider, onRemoveProvider = manager::removeProvider)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NineRouterPanel(
    state: NineRouterState, onInstallLinux: () -> Unit, onInstall: () -> Unit,
    onStart: () -> Unit, onStop: () -> Unit, onOpen: () -> Unit,
    onCheckUpdate: () -> Unit = {}, onUpdate: () -> Unit = {}, onUninstall: () -> Unit = {},
    onAutoStart: (Boolean) -> Unit = {}, onAddProvider: () -> Unit = {}, onRemoveProvider: () -> Unit = {},
) {
    val running = state.phase == NineRouterPhase.RUNNING
    val installed = state.installedVersion != null
    var confirmUninstall by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val tint by animateColorAsState(if (running) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer, label = "Router status")
    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().animateContentSize(spring())) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(shape = RoundedCornerShape(if (running) 24.dp else 16.dp), color = tint) {
                        Icon(Icons.Outlined.AccountTree, null, Modifier.padding(16.dp).size(28.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.nine_router_title), style = MaterialTheme.typography.headlineSmall)
                        AnimatedContent(state.phase, label = "Router state") { phase ->
                            Text(stringResource(when (phase) {
                                NineRouterPhase.SETUP_LINUX -> R.string.nine_router_setup
                                NineRouterPhase.INSTALL -> R.string.nine_router_not_installed
                                NineRouterPhase.READY -> R.string.nine_router_ready
                                NineRouterPhase.RUNNING -> R.string.nine_router_running
                                NineRouterPhase.STARTING -> R.string.nine_router_starting
                                NineRouterPhase.STOPPING -> R.string.nine_router_stopping
                                NineRouterPhase.UPDATING -> R.string.nine_router_updating
                                NineRouterPhase.UNINSTALLING -> R.string.nine_router_uninstalling
                                NineRouterPhase.CONNECTING -> R.string.nine_router_connecting
                                NineRouterPhase.REMOVING_PROVIDER -> R.string.nine_router_removing
                                else -> R.string.nine_router_installing
                            }), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        state.installedVersion?.let { Text(stringResource(R.string.nine_router_version, it), style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Text(stringResource(R.string.nine_router_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AnimatedVisibility(state.busy) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppLinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.detail.isNotBlank()) Text(state.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.error?.let { error ->
                    var expanded by remember(error) { mutableStateOf(false) }
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(14.dp).animateContentSize()) {
                            Text(error.substringBefore('\n'), style = MaterialTheme.typography.bodySmall)
                            if ('\n' in error) {
                                TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.nine_router_hide_details else R.string.nine_router_show_details)) }
                                AnimatedVisibility(expanded) { androidx.compose.foundation.text.selection.SelectionContainer { Text(error.substringAfter('\n'), style = MaterialTheme.typography.bodySmall) } }
                            }
                        }
                    }
                }
                state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.busy) {
                        Button(onClick = when (state.phase) {
                            NineRouterPhase.SETUP_LINUX -> onInstallLinux
                            NineRouterPhase.INSTALL -> onInstall
                            NineRouterPhase.RUNNING -> onOpen
                            else -> onStart
                        }, shapes = ButtonDefaults.shapes()) {
                            Icon(if (running) Icons.Outlined.OpenInBrowser else if (!installed) Icons.Outlined.Download else Icons.Outlined.PlayArrow, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(when (state.phase) {
                                NineRouterPhase.SETUP_LINUX -> R.string.nine_router_install_linux
                                NineRouterPhase.INSTALL -> R.string.nine_router_install
                                NineRouterPhase.RUNNING -> R.string.nine_router_open_dashboard
                                else -> R.string.nine_router_start
                            }))
                        }
                    }
                    if (running) OutlinedButton(onClick = onStop) { Icon(Icons.Outlined.Stop, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.nine_router_stop)) }
                    if (installed) FilledTonalButton(onClick = if (state.updateAvailable) onUpdate else onCheckUpdate,
                        enabled = !state.busy && !state.checkingUpdate, shapes = ButtonDefaults.shapes()) {
                        AnimatedContent(Triple(state.checkingUpdate, state.updateAvailable, state.latestVersion), label = "Update button") { (checking, available, version) ->
                            Text(if (checking) stringResource(R.string.nine_router_checking) else if (available) stringResource(R.string.nine_router_update, version.orEmpty()) else stringResource(R.string.nine_router_check_update))
                        }
                    }
                }
                if (installed) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.nine_router_auto_start), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.nine_router_auto_start_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.autoStart, onCheckedChange = onAutoStart, enabled = !state.busy)
                    }
                    FilledTonalButton(onClick = onAddProvider, enabled = !state.busy, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (state.providerAdded) R.string.nine_router_refresh_provider else R.string.nine_router_add_provider))
                    }
                    Text(stringResource(R.string.nine_router_provider_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.hasFiles || installed || state.providerAdded) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.providerAdded) TextButton(onClick = { confirmRemove = true }, enabled = !state.busy) { Text(stringResource(R.string.nine_router_remove_provider)) }
                        TextButton(onClick = { confirmUninstall = true }, enabled = !state.busy) { Text(stringResource(R.string.nine_router_uninstall), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        if (confirmUninstall) AlertDialog(onDismissRequest = { confirmUninstall = false },
            title = { Text(stringResource(R.string.nine_router_uninstall_title)) },
            text = { Text(stringResource(R.string.nine_router_uninstall_body)) },
            confirmButton = { TextButton(onClick = { confirmUninstall = false; onUninstall() }) { Text(stringResource(R.string.nine_router_uninstall)) } },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text(stringResource(R.string.settings_action_cancel)) } })
        if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.nine_router_remove_provider)) },
            text = { Text(stringResource(R.string.nine_router_remove_provider_body)) },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemoveProvider() }) { Text(stringResource(R.string.nine_router_remove_provider)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.settings_action_cancel)) } })
    }
}
