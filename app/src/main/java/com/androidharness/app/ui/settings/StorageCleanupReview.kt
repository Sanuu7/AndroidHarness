package com.androidharness.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.storage.CleanupEntry
import com.androidharness.app.data.storage.CleanupKind
import com.androidharness.app.data.storage.CleanupOutcome
import com.androidharness.app.data.storage.CleanupPlan
import com.androidharness.app.data.storage.KeepReason
import com.androidharness.app.data.storage.KeptEntry
import com.androidharness.app.data.storage.PackageBlock
import com.androidharness.app.data.storage.PackageProtection
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

private fun effectText(kind: CleanupKind): Int = when (kind) {
    CleanupKind.WEB_DATA -> R.string.storage_cleanup_effect_web
    CleanupKind.CHAT_IMAGES -> R.string.storage_cleanup_effect_images
    CleanupKind.DOWNLOADS -> R.string.storage_cleanup_effect_downloads
    CleanupKind.SCRATCH -> R.string.storage_cleanup_effect_scratch
    CleanupKind.CACHE -> R.string.storage_cleanup_effect_cache
    CleanupKind.CANVAS -> R.string.storage_cleanup_effect_canvas
    CleanupKind.MODEL -> R.string.storage_cleanup_effect_model
    CleanupKind.CHAT_HISTORY -> R.string.storage_cleanup_effect_history
    CleanupKind.SETTINGS -> R.string.storage_cleanup_effect_settings
    CleanupKind.PLUGIN -> R.string.storage_cleanup_effect_plugin
    CleanupKind.SKILL -> R.string.storage_cleanup_effect_skill
    CleanupKind.LINUX_PACKAGE -> R.string.storage_cleanup_effect_package
}

private fun keepText(reason: KeepReason): Pair<Int, Int> = when (reason) {
    KeepReason.ROUTER -> R.string.storage_keep_router to R.string.storage_keep_router_note
    KeepReason.LINUX_CORE -> R.string.storage_keep_linux to R.string.storage_keep_linux_note
    KeepReason.SECRETS -> R.string.storage_keep_secrets to R.string.storage_keep_secrets_note
    KeepReason.APP_PACKAGE -> R.string.storage_keep_app to R.string.storage_keep_app_note
    KeepReason.PROJECT_FILES -> R.string.storage_keep_projects to R.string.storage_keep_projects_note
    KeepReason.UNDO_HISTORY -> R.string.storage_keep_undo to R.string.storage_keep_undo_note
    KeepReason.APP_RUNTIME -> R.string.storage_keep_runtime to R.string.storage_keep_runtime_note
    KeepReason.PACKAGE -> R.string.storage_keep_packages to R.string.storage_keep_packages_note
    KeepReason.PACKAGE_MANAGER -> R.string.storage_keep_npm_pip to R.string.storage_keep_npm_pip_note
}

private fun keepIcon(reason: KeepReason) = when (reason) {
    KeepReason.ROUTER -> Icons.Rounded.Router
    KeepReason.LINUX_CORE -> Icons.Rounded.Terminal
    KeepReason.SECRETS -> Icons.Rounded.Key
    KeepReason.APP_PACKAGE -> Icons.Rounded.Android
    KeepReason.PROJECT_FILES -> Icons.Rounded.Folder
    KeepReason.UNDO_HISTORY -> Icons.Rounded.History
    KeepReason.APP_RUNTIME -> Icons.Rounded.Settings
    KeepReason.PACKAGE -> Icons.Rounded.Inventory2
    KeepReason.PACKAGE_MANAGER -> Icons.Rounded.Code
}

@Composable
internal fun packageProtectionText(protection: PackageProtection): String {
    val names = protection.names.joinToString()
    return when (protection.block) {
        PackageBlock.REQUIRED -> stringResource(R.string.storage_package_required)
        PackageBlock.UNTRACKED -> stringResource(R.string.storage_package_untracked)
        PackageBlock.UNVERIFIED -> stringResource(R.string.storage_package_unverified)
        PackageBlock.KEPT_FOR -> stringResource(R.string.storage_package_kept_for, names)
        PackageBlock.DEPENDENTS -> stringResource(R.string.storage_package_dependents, names)
        PackageBlock.SHARED_FILES -> stringResource(R.string.storage_package_shared_files, names)
        PackageBlock.CHANGED -> stringResource(R.string.storage_package_changed)
    }
}

/** The review: every removable item with its size, grouped like the inventory, then what is always kept. */
@Composable
internal fun CleanupReview(
    plan: CleanupPlan, selected: Set<String>, busy: Boolean, outcome: CleanupOutcome?,
    onToggle: (String) -> Unit, onSelect: (Set<String>) -> Unit, onClean: () -> Unit, onBack: () -> Unit,
) {
    val context = LocalContext.current
    val chosen = plan.chosen(selected)
    CleanupSummary(plan, chosen, busy, onSelect)
    outcome?.let { CleanupResult(it) }
    if (plan.entries.isEmpty()) Text(stringResource(R.string.storage_cleanup_nothing), style = MaterialTheme.typography.bodyMedium)
    plan.groups().forEachIndexed { order, (category, entries) ->
        Column(Modifier.settingsEntrance(order), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsSectionLabel(stringResource(storageCategoryName(category)), top = 4.dp)
            SettingsGroup {
                entries.forEach { entry ->
                    val checked = entry.id in selected
                    item(onClick = { if (!busy) onToggle(entry.id) }, selected = checked) {
                        CleanupRow(storageTitle(context, entry.item), entry, checked)
                    }
                }
            }
        }
    }
    KeptPanel(plan)
    Button(onClick = onClean, enabled = chosen.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Icon(Icons.Rounded.CleaningServices, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.storage_cleanup_button, storageSize(chosen.sumOf { it.bytes })))
    }
    TextButton(onClick = onBack, enabled = !busy) { Text(stringResource(R.string.settings_storage_back)) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CleanupSummary(plan: CleanupPlan, chosen: List<CleanupEntry>, busy: Boolean, onSelect: (Set<String>) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()), shape = RoundedCornerShape(28.dp),
        color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.storage_cleanup_selected, storageSize(chosen.sumOf { it.bytes })),
                style = MaterialTheme.typography.headlineSmallEmphasized, fontWeight = FontWeight.SemiBold)
            Text(pluralStringResource(R.plurals.storage_cleanup_selected_items, chosen.size, chosen.size) + " · " +
                stringResource(R.string.storage_cleanup_intro), style = MaterialTheme.typography.bodyMedium)
            if (plan.entries.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onSelect(plan.entries.map { it.id }.toSet()) }, enabled = !busy) { Text(stringResource(R.string.storage_cleanup_select_all)) }
                OutlinedButton(onClick = { onSelect(emptySet()) }, enabled = !busy && chosen.isNotEmpty()) { Text(stringResource(R.string.storage_cleanup_select_none)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CleanupRow(title: String, entry: CleanupEntry, checked: Boolean) {
    val pop by animateFloatAsState(if (checked) 1f else 0.92f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "cleanupCheck")
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 8.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked, onCheckedChange = null, modifier = Modifier.scale(pop))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(effectText(entry.kind)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.kind.confirm) StatPill(Icons.Rounded.WarningAmber, stringResource(R.string.storage_cleanup_cannot_undo),
                container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
        }
        Text(storageSize(entry.bytes), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun KeptPanel(plan: CleanupPlan) {
    val groups = plan.keptGroups()
    if (groups.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsSectionLabel(stringResource(R.string.storage_cleanup_kept_title), top = 4.dp)
        Text(stringResource(R.string.storage_cleanup_kept_note), Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val fill = MaterialTheme.colorScheme.surfaceContainerLow
        SettingsGroup {
            groups.forEach { (reason, kept) ->
                item(color = fill) { KeptRow(reason, kept) }
            }
        }
    }
}

@Composable
private fun KeptRow(reason: KeepReason, kept: List<KeptEntry>) {
    val context = LocalContext.current
    val named = kept.mapNotNull { it.item }
    var open by rememberSaveable(reason) { mutableStateOf(false) }
    val (title, note) = keepText(reason)
    Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
        SettingsItemRow(stringResource(title), subtitle = stringResource(note),
            leading = { SettingsIconBadge(keepIcon(reason), tone = SettingsTone.NEUTRAL) }) {
            val bytes = kept.sumOf { it.bytes }
            if (bytes > 0) Text(storageSize(bytes), style = MaterialTheme.typography.labelLarge)
            if (named.size > 1 || kept.any { it.protection != null }) IconButton(onClick = { open = !open }) {
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 70.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                kept.filter { it.item != null }.forEach { entry ->
                    Column {
                        Text(storageTitle(context, entry.item!!), style = MaterialTheme.typography.bodyMedium)
                        entry.protection?.let {
                            Text(packageProtectionText(it), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanupResult(outcome: CleanupOutcome) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val failed = outcome.failed.isNotEmpty()
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = if (failed) scheme.errorContainer else scheme.secondaryContainer,
        contentColor = if (failed) scheme.onErrorContainer else scheme.onSecondaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, null)
                Text(stringResource(R.string.storage_cleanup_freed, storageSize(outcome.freedBytes)), style = MaterialTheme.typography.titleSmall)
            }
            if (failed) {
                Text(stringResource(R.string.storage_cleanup_failed_title), style = MaterialTheme.typography.labelLarge)
                outcome.failed.forEach {
                    Text(stringResource(R.string.storage_cleanup_failed_line, storageTitle(context, it.entry.item), it.message),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The last step. Choices that cannot be undone are spelled out, item by item, before the red button. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CleanupConfirmDialog(chosen: List<CleanupEntry>, cleaning: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val losses = chosen.filter { it.kind.confirm }
    AlertDialog(onDismissRequest = { if (!cleaning) onDismiss() },
        icon = { Icon(if (losses.isEmpty()) Icons.Rounded.CleaningServices else Icons.Rounded.WarningAmber, null) },
        title = { Text(stringResource(R.string.storage_cleanup_confirm_title, storageSize(chosen.sumOf { it.bytes }))) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (cleaning) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LoadingIndicator(Modifier.size(36.dp))
                        Text(stringResource(R.string.storage_cleanup_working), style = MaterialTheme.typography.titleSmall)
                    }
                }
                if (losses.isNotEmpty()) Surface(shape = RoundedCornerShape(16.dp), color = scheme.errorContainer, contentColor = scheme.onErrorContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.storage_cleanup_confirm_loss) + " · " + stringResource(R.string.storage_cleanup_cannot_undo),
                            style = MaterialTheme.typography.labelLarge)
                        losses.forEach { entry ->
                            val name = storageTitle(context, entry.item)
                            Text("• " + when (entry.kind) {
                                CleanupKind.CHAT_HISTORY -> stringResource(R.string.storage_cleanup_loss_history)
                                CleanupKind.SETTINGS -> stringResource(R.string.storage_cleanup_loss_settings) + ". " +
                                    stringResource(R.string.storage_keep_secrets_note) + "."
                                CleanupKind.CHAT_IMAGES -> stringResource(R.string.storage_cleanup_loss_images)
                                CleanupKind.MODEL -> stringResource(R.string.storage_cleanup_loss_model, name)
                                CleanupKind.LINUX_PACKAGE -> stringResource(R.string.storage_cleanup_loss_package, name)
                                CleanupKind.SKILL -> stringResource(R.string.storage_cleanup_loss_skill, name)
                                else -> name
                            }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(stringResource(R.string.storage_cleanup_confirm_list), style = MaterialTheme.typography.labelLarge)
                chosen.forEach { entry ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(storageTitle(context, entry.item), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(storageSize(entry.bytes), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !cleaning, colors = if (losses.isEmpty()) ButtonDefaults.buttonColors()
                else ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError)) {
                Text(stringResource(R.string.storage_cleanup_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !cleaning) { Text(stringResource(R.string.settings_action_cancel)) } })
}
