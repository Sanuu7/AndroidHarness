package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import com.androidharness.app.ui.common.localizedOption
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.env.*
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.RadioButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun RootAccessSection(container: AppContainer) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val access = container.deviceAccess
    val prefs by access.preferences.collectAsStateWithLifecycle()
    val root by access.root.state.collectAsStateWithLifecycle()
    val paused by access.paused.collectAsStateWithLifecycle()
    val allowance by access.allowanceLabel.collectAsStateWithLifecycle()
    val last by access.lastExecution.collectAsStateWithLifecycle()
    val shizukuUid by access.shizukuUid.collectAsStateWithLifecycle()
    val projects by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val project by container.workspace.currentProject.collectAsStateWithLifecycle(initialValue = null)
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = com.androidharness.app.data.AppSettings())
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var projectPicker by remember { mutableStateOf(false) }
    var browser by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var useMenu by remember { mutableStateOf(false) }
    var confirmUnlimited by remember { mutableStateOf(false) }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.AdminPanelSettings, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_root_title), style = MaterialTheme.typography.titleMedium)
                    Text(localizedOption(root.status.label), style = MaterialTheme.typography.bodySmall,
                        color = if (root.status == RootStatus.READY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (busy || root.status == RootStatus.REQUESTING) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            Text(root.detail, style = MaterialTheme.typography.bodySmall,
                color = if (root.status == RootStatus.DENIED || (root.status == RootStatus.UNAVAILABLE && root.checkedAt > 0))
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            root.provider?.let { provider ->
                Text(stringResource(R.string.settings_root_manager, provider.label), style = MaterialTheme.typography.labelMedium,
                    color = if (root.status == RootStatus.READY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (shizukuUid == 0) Text(stringResource(R.string.settings_root_shizuku_available), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            val rootEnabled = rootChipEnabled(
                root.status,
                access.rootUseActive(settings.lastActiveSessionId, prefs),
            )
            AccessActionPair(first = { buttonModifier ->
                if (rootEnabled) Surface(
                    modifier = buttonModifier.heightIn(min = 48.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.settings_root_enabled), style = MaterialTheme.typography.titleSmall)
                    }
                } else Button(onClick = { scope.launch {
                    busy = true
                    try { access.grantRoot(project?.id, settings.lastActiveSessionId) } finally { busy = false }
                } }, enabled = !busy && root.status != RootStatus.REQUESTING, modifier = buttonModifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(if (root.status == RootStatus.READY) uiContext.getString(R.string.ui_enable_root_use) else uiContext.getString(R.string.ui_grant_root_access))
                }
            }, second = { buttonModifier ->
                OutlinedButton(onClick = { scope.launch {
                    busy = true
                    try { report = access.checkConnections() } finally { busy = false }
                } }, enabled = !busy, modifier = buttonModifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) { Text(stringResource(R.string.settings_root_check_access)) }
            })
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.settings_root_auto_fallback), style = MaterialTheme.typography.titleSmall)
                    Text(if (prefs.mode == AccessMode.AUTO) uiContext.getString(R.string.ui_always_on_root_shizuku_normal) else uiContext.getString(R.string.ui_root_shizuku_normal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = prefs.mode == AccessMode.AUTO || prefs.autoFallback, enabled = prefs.mode != AccessMode.AUTO, onCheckedChange = { scope.launch { access.store.setFallback(it) } })
            }
            Text(stringResource(R.string.settings_root_access_mode), style = MaterialTheme.typography.titleSmall)
            AccessModeChoices(prefs.mode) { scope.launch { access.store.setMode(it) } }
            Text(when {
                prefs.mode == AccessMode.AUTO -> uiContext.getString(R.string.ui_auto_chooses_available_access_root_then_shizuku)
                prefs.mode == AccessMode.BOTH -> uiContext.getString(R.string.ui_both_stay_connected_shizuku_runs_ordinary_commands)
                !prefs.autoFallback && prefs.mode != AccessMode.NORMAL -> uiContext.getString(R.string.ui_commands_fail_if_the_selected_access_is)
                else -> uiContext.getString(R.string.ui_fallback_applies_before_a_command_starts_interrupted)
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.settings_root_temp_mode), style = MaterialTheme.typography.titleSmall)
                    Text(if (prefs.temporaryRoot) uiContext.getString(R.string.ui_root_is_limited_to_an_app_session)
                        else uiContext.getString(R.string.ui_root_is_allowed_in_every_project_and),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (prefs.temporaryRoot) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
                Switch(checked = prefs.temporaryRoot, onCheckedChange = { enabled ->
                    if (enabled) scope.launch { access.store.setTemporaryRoot(true) } else confirmUnlimited = true
                })
            }
            if (prefs.temporaryRoot) {
                Text(stringResource(R.string.settings_root_temp_use), style = MaterialTheme.typography.titleSmall)
                Box {
                    OutlinedButton(onClick = { useMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(localizedOption(prefs.rootUse.label), Modifier.weight(1f)); Icon(Icons.Outlined.ExpandMore, null)
                    }
                    DropdownMenu(expanded = useMenu, onDismissRequest = { useMenu = false }) {
                        RootUse.entries.forEach { use -> DropdownMenuItem(text = { Text(localizedOption(use.label)) },
                            enabled = use != RootUse.CHAT || settings.lastActiveSessionId != null,
                            onClick = { useMenu = false; access.disableRootUse(); scope.launch { access.store.setRootUse(use) } }) }
                    }
                }
                Text(allowance, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text(stringResource(R.string.settings_root_temp_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { projectPicker = true }, enabled = prefs.temporaryRoot, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_root_project_permissions), Modifier.weight(1f))
                Text(stringResource(R.string.settings_root_project_count, projects.count { it.id in prefs.rootProjects }.toString()), style = MaterialTheme.typography.labelMedium)
            }
            if (!prefs.temporaryRoot) Text(stringResource(R.string.settings_root_project_permissions_unused),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AccessActionPair(first = { buttonModifier ->
                FilledTonalButton(onClick = { browser = true }, enabled = root.status == RootStatus.READY || shizukuUid == 0,
                    modifier = buttonModifier) { Text(stringResource(R.string.settings_root_browser)) }
            }, second = { buttonModifier ->
                OutlinedButton(onClick = { report = access.report() }, modifier = buttonModifier) { Text(stringResource(R.string.settings_root_diagnostics)) }
            })
            Text(stringResource(R.string.settings_root_last_used, last), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (paused) {
                FilledTonalButton(onClick = { scope.launch { report = access.resume() } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_root_resume)) }
            } else {
                OutlinedButton(onClick = { scope.launch {
                    busy = true
                    try { report = access.stopPrivileged() } finally { busy = false }
                } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.StopCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.settings_root_stop_tasks))
                }
            }
        }
    }
    if (projectPicker) AlertDialog(onDismissRequest = { projectPicker = false }, title = { Text(stringResource(R.string.settings_root_project_permissions)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_root_project_permissions_desc), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(projects, key = { it.id }) { item ->
                    val checked = item.id in prefs.rootProjects
                    Row(Modifier.fillMaxWidth().clickable { scope.launch { access.store.allowProject(item.id, !checked) } }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = { value -> scope.launch { access.store.allowProject(item.id, value) } })
                        Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = { projectPicker = false }) { Text(stringResource(R.string.settings_action_done)) } })
    if (confirmUnlimited) AlertDialog(
        onDismissRequest = { confirmUnlimited = false },
        icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.settings_root_everywhere_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_root_everywhere_note))
            Text(stringResource(R.string.settings_root_everywhere_warning))
            Text(stringResource(R.string.settings_root_everywhere_caution), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { TextButton(onClick = {
            confirmUnlimited = false
            scope.launch { access.store.setTemporaryRoot(false) }
        }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.settings_root_everywhere_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmUnlimited = false }) { Text(stringResource(R.string.settings_root_keep_temp)) } },
    )
    report?.let { text -> AlertDialog(onDismissRequest = { report = null }, title = { Text(stringResource(R.string.settings_root_device_diagnostics)) },
        text = { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) } },
        confirmButton = { TextButton(onClick = { report = null }) { Text(stringResource(R.string.settings_action_done)) } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text(stringResource(R.string.settings_root_copy_report)) } }) }
    if (browser) RootBrowserDialog(access, project?.id, settings.lastActiveSessionId) { browser = false }
}

/**
 * "Root enabled" describes the direct root service only. Shizuku has its own
 * status line; counting it here claimed root was enabled after a denied
 * KernelSU/Magisk grant while the detail said no connection was active.
 */
internal fun rootChipEnabled(status: RootStatus, useActive: Boolean): Boolean =
    status == RootStatus.READY && useActive

@Composable
internal fun AccessModeChoices(selected: AccessMode, onSelect: (AccessMode) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 280.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f) 1 else 2
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AccessMode.entries.chunked(columns).forEach { modes ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    modes.forEach { mode ->
                        OutlinedCard(modifier = Modifier.weight(1f).selectable(selected == mode, role = Role.RadioButton, onClick = { onSelect(mode) }),
                            colors = CardDefaults.outlinedCardColors(containerColor = if (selected == mode) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected == mode, onClick = null, modifier = Modifier.size(20.dp))
                                Text(if (mode == AccessMode.SHIZUKU) stringResource(R.string.shell_access_providers) else localizedOption(mode.label), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessActionPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 280.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.fillMaxWidth()); second(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.weight(1f)); second(Modifier.weight(1f))
            }
        }
    }
}
