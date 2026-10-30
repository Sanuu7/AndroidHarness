package com.androidharness.app.ui.settings

import com.androidharness.app.ui.common.AppLinearProgressIndicator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.storage.StorageCandidate
import com.androidharness.app.data.storage.StorageInventory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

internal fun storageSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

@Composable
internal fun StorageSection(container: AppContainer) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var inventory by remember { mutableStateOf<StorageInventory?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmation by remember { mutableStateOf<List<StorageCandidate>?>(null) }
    var toolDetail by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<Pair<String, String>?>(null) }
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun scan() {
        scanning = true; error = null
        try {
            inventory = container.storage.scan()
            selected = selected.intersect(inventory!!.candidates.map { it.id }.toSet())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: uiContext.getString(R.string.ui_couldn_t_measure_storage_try_again) }
        finally { scanning = false }
    }
    LaunchedEffect(Unit) { scan() }
    val busy = scanning || cleaning
    val heading = remember { BringIntoViewRequester() }
    LaunchedEffect(tab, reviewing) { withFrameNanos { }; heading.bringIntoView() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().bringIntoViewRequester(heading), verticalAlignment = Alignment.CenterVertically) {
            Text(if (reviewing) uiContext.getString(R.string.settings_storage_review_cleanup) else uiContext.getString(R.string.ui_storage_on_this_device), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { scope.launch { scan() } }, enabled = !busy && confirmation == null) {
                Icon(Icons.Outlined.Refresh, uiContext.getString(R.string.ui_refresh_storage))
            }
        }
        if (busy) AppLinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        report?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        val data = inventory
        if (data == null) {
            Text(if (scanning) uiContext.getString(R.string.ui_measuring_files) else uiContext.getString(R.string.ui_storage_is_unavailable), style = MaterialTheme.typography.bodyMedium)
            if (!scanning) OutlinedButton(onClick = { scope.launch { scan() } }) { Text(stringResource(R.string.settings_action_try_again)) }
        } else if (reviewing) {
            Text(stringResource(R.string.settings_storage_cleanup_note), style = MaterialTheme.typography.bodySmall)
            if (data.candidates.isEmpty()) Text(stringResource(R.string.settings_storage_nothing_to_clean), style = MaterialTheme.typography.bodyMedium)
            data.candidates.groupBy { it.group }.forEach { (group, items) ->
                Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SettingsPanel(Modifier.fillMaxWidth()) {
                    Column {
                        items.forEachIndexed { index, candidate ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Checkbox) {
                                selected = if (candidate.id in selected) selected - candidate.id else selected + candidate.id
                            }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(candidate.id in selected, onCheckedChange = null)
                                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                    Text(candidate.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(candidate.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(candidatePath(candidate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(storageSize(candidate.bytes), style = MaterialTheme.typography.labelMedium)
                            }
                            if (index < items.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }
            StorageRow(Icons.Outlined.Inventory2, uiContext.getString(R.string.ui_unused_managed_packages), uiContext.getString(R.string.ui_unknown_ownership_or_workspace_requirements_stay_protected), uiContext.getString(R.string.ui_details)) {
                detail = uiContext.getString(R.string.ui_managed_packages) to uiContext.getString(R.string.ui_new_installs_record_files_dependencies_and_requirements)
            }
            val alwaysKept = stringResource(R.string.settings_storage_always_kept) to
                stringResource(R.string.settings_storage_always_kept_detail)
            StorageRow(Icons.Outlined.Shield, uiContext.getString(R.string.settings_storage_always_kept), uiContext.getString(R.string.ui_source_folders_required_dependencies_models_history_undo), "") {
                detail = alwaysKept
            }
            val chosen = data.candidates.filter { it.id in selected }
            Button(onClick = { confirmation = chosen }, enabled = chosen.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_storage_review_size, storageSize(chosen.sumOf { it.bytes })))
            }
            TextButton(onClick = { reviewing = false }, enabled = !busy) { Text(stringResource(R.string.settings_storage_back)) }
        } else {
            val tabs: @Composable () -> Unit = {
                listOf(uiContext.getString(R.string.ui_overview), uiContext.getString(R.string.ui_shared), uiContext.getString(R.string.settings_page_workspace_title)).forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title, maxLines = 1, softWrap = false) })
                }
            }
            if (LocalDensity.current.fontScale > 1.2f) {
                PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp, tabs = tabs)
            } else PrimaryTabRow(selectedTabIndex = tab, tabs = tabs)

            when (tab) {
                0 -> {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(storageSize(data.bytes), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.settings_storage_measured_note), style = MaterialTheme.typography.bodySmall)
                            Text(if (data.candidates.isEmpty()) uiContext.getString(R.string.ui_nothing_safe_to_clean_right_now) else
                                uiContext.getString(R.string.ui_available_to_review, (storageSize(data.candidates.sumOf { it.bytes })).toString()), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    SettingsPanel(Modifier.fillMaxWidth()) {
                        Column {
                            data.usage.forEachIndexed { index, usage ->
                                StorageRow(when(index) { 0 -> Icons.Outlined.Terminal; 1 -> Icons.Outlined.Download; 2 -> Icons.Outlined.Folder;
                                    3 -> Icons.Outlined.Memory; 4 -> Icons.Outlined.History; else -> Icons.Outlined.Storage },
                                    usage.title, usage.note, storageSize(usage.bytes)) {
                                    when (index) {
                                        0 -> tab = 1
                                        2 -> tab = 2
                                        else -> detail = usage.title to when (index) {
                                            1 -> uiContext.getString(R.string.ui_npm_and_pip_use_a_shared_download)
                                            3 -> uiContext.getString(R.string.ui_installed_model_files_stay_on_this_device)
                                            4 -> uiContext.getString(R.string.ui_chat_databases_include_messages_undo_checkpoints_and)
                                            else -> uiContext.getString(R.string.ui_includes_settings_other_app_caches_and_the)
                                        }
                                    }
                                }
                                if (index < data.usage.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                    Button(onClick = { reviewing = true; selected = data.candidates.filter { it.group == "Downloads" }.map { it.id }.toSet() },
                        enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_storage_review_cleanup)) }
                }
                1 -> {
                    Text(stringResource(R.string.settings_storage_toolchain_note), style = MaterialTheme.typography.bodySmall)
                    StorageRow(Icons.Outlined.Download, uiContext.getString(R.string.ui_shared_download_caches), uiContext.getString(R.string.ui_npm_pip_existing_gradle_home), uiContext.getString(R.string.ui_details)) {
                        detail = uiContext.getString(R.string.ui_shared_caches) to uiContext.getString(R.string.ui_downloads_can_be_reused_across_local_workspaces)
                    }
                    if (data.packages.isEmpty()) Text(stringResource(R.string.settings_storage_no_packages), style = MaterialTheme.typography.bodyMedium)
                    SettingsPanel(Modifier.fillMaxWidth()) {
                        Column {
                            data.packages.forEachIndexed { index, name ->
                                val tool = data.managedTools[name]
                                StorageRow(Icons.Outlined.Inventory2, name,
                                    data.protections[name] ?: uiContext.getString(R.string.ui_no_recorded_requirements_review_cleanup),
                                    if (tool?.complete == true) storageSize(tool.files.sumOf { it.size }) else uiContext.getString(R.string.ui_unknown)) {
                                    toolDetail = name
                                }
                                if (index < data.packages.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
                2 -> {
                    Text(stringResource(R.string.settings_storage_totals_note), style = MaterialTheme.typography.bodySmall)
                    if (data.workspaces.isEmpty()) Text(stringResource(R.string.settings_storage_no_workspaces))
                    data.workspaces.forEach { workspace ->
                        SettingsPanel(Modifier.fillMaxWidth()) {
                            StorageRow(Icons.Outlined.Folder, workspace.name, workspace.note,
                                workspace.bytes?.let(::storageSize) ?: uiContext.getString(R.string.ui_not_measured)) {
                                detail = workspace.name to (workspace.path.takeIf { it.isNotEmpty() }?.let {
                                    uiContext.getString(R.string.ui_shared_tools_and_download_caches_are_measured, (it).toString(), (workspace.note).toString())
                                } ?: workspace.note)
                            }
                        }
                    }
                }
            }
            Text(
                stringResource(
                    R.string.settings_storage_measure_note,
                    if (data.incomplete) stringResource(R.string.settings_storage_measure_incomplete) else "",
                ),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    toolDetail?.let { name ->
        val tool = inventory?.managedTools?.get(name)
        val protection = inventory?.protections?.get(name)
        AlertDialog(onDismissRequest = { toolDetail = null }, title = { Text(name) },
            text = {
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(protection ?: uiContext.getString(R.string.ui_no_recorded_requirements_or_installed_dependents_eligible))
                    Text(stringResource(R.string.settings_storage_requirements_note), style = MaterialTheme.typography.bodySmall)
                    if (tool == null) Text(stringResource(R.string.settings_storage_no_manifest), style = MaterialTheme.typography.bodySmall)
                    else {
                        if (!tool.complete) Text(stringResource(R.string.settings_storage_unverified), style = MaterialTheme.typography.bodySmall)
                        if (tool.depends.isNotEmpty()) Text(
                            stringResource(R.string.settings_storage_dependencies, tool.depends.joinToString()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        tool.requirements.sorted().forEach { owner ->
                            Text(owner, style = MaterialTheme.typography.bodySmall)
                            if (protection != "Required by the app toolchain") OutlinedButton(onClick = { release = name to owner }, enabled = !busy) { Text(stringResource(R.string.settings_storage_release_confirm)) }
                        }
                        Text(stringResource(R.string.settings_storage_release_note), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }, confirmButton = { TextButton(onClick = { toolDetail = null }) { Text(stringResource(R.string.settings_action_done)) } })
    }
    release?.let { (name, owner) ->
        AlertDialog(onDismissRequest = { release = null }, title = { Text(stringResource(R.string.settings_storage_release_title, name)) },
            text = { Text(stringResource(R.string.settings_storage_release_message, owner, name)) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                scope.launch {
                    try { container.linuxEnv.releasePackageRequirement(name, owner); release = null; scan() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message; release = null }
                }
            }) { Text(stringResource(R.string.settings_storage_release_confirm)) } },
            dismissButton = { TextButton(onClick = { release = null }) { Text(stringResource(R.string.settings_action_cancel)) } })
    }
    detail?.let { (title, body) ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(title) }, text = { Text(body) },
            confirmButton = { TextButton(onClick = { detail = null }) { Text(stringResource(R.string.settings_action_done)) } })
    }
    confirmation?.let { chosen ->
        AlertDialog(onDismissRequest = { if (!cleaning) confirmation = null },
            title = { Text(stringResource(R.string.settings_storage_free_title, storageSize(chosen.sumOf { it.bytes }))) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_storage_free_note))
                    chosen.forEach {
                        Text(
                            stringResource(R.string.settings_storage_candidate, it.title, storageSize(it.bytes), candidatePath(it)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(stringResource(R.string.settings_storage_cleanup_skip_note))
                }
            },
            confirmButton = {
                TextButton(enabled = !cleaning, onClick = {
                    cleaning = true; error = null
                    scope.launch {
                        try {
                            val result = container.storage.clean(chosen)
                            report = uiContext.getString(R.string.ui_removed_from_files, (storageSize(result.removedBytes)).toString(), (result.removedFiles).toString()) +
                                if (result.skipped > 0) uiContext.getString(R.string.ui_changed_or_unavailable_items_skipped, (result.skipped).toString()) else ""
                            confirmation = null; selected = emptySet(); reviewing = false
                            scan()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: uiContext.getString(R.string.ui_cleanup_failed_scan_again); confirmation = null }
                        finally { cleaning = false }
                    }
                }) { Text(if (cleaning) uiContext.getString(R.string.ui_cleaning) else uiContext.getString(R.string.ui_free, (storageSize(chosen.sumOf { it.bytes })).toString())) }
            }, dismissButton = { TextButton(enabled = !cleaning, onClick = { confirmation = null }) { Text(stringResource(R.string.settings_action_cancel)) } })
    }
}

private fun candidatePath(candidate: StorageCandidate): String = candidate.target.absolutePath

@Composable
private fun StorageRow(icon: ImageVector, title: String, subtitle: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 64.dp).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(max = 90.dp))
    }
}
