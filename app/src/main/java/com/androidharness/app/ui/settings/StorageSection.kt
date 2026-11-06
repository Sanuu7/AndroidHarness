package com.androidharness.app.ui.settings

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.storage.StorageCandidate
import com.androidharness.app.data.storage.StorageCategory
import com.androidharness.app.data.storage.StorageGroup
import com.androidharness.app.data.storage.StorageInventory
import com.androidharness.app.data.storage.StorageItem
import com.androidharness.app.data.storage.StorageProgress
import com.androidharness.app.data.storage.StorageReport
import com.androidharness.app.data.storage.StorageRole
import com.androidharness.app.ui.common.AppLinearProgressIndicator
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

internal fun storageSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

private enum class StorageView { CATEGORIES, LARGEST, NOT_MEASURED }

@Composable
internal fun StorageSection(container: AppContainer) {
    val uiContext = LocalContext.current
    val scope = rememberCoroutineScope()
    val inventory by container.storage.last.collectAsState()
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<StorageProgress?>(null) }
    var stopped by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    var view by rememberSaveable { mutableStateOf(StorageView.CATEGORIES) }
    var query by rememberSaveable { mutableStateOf("") }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmation by remember { mutableStateOf<List<StorageCandidate>?>(null) }
    var itemDetail by remember { mutableStateOf<String?>(null) }
    var toolDetail by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<Pair<String, String>?>(null) }
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scanning = scanJob?.isActive == true

    fun rescan() {
        if (scanJob?.isActive == true) return
        stopped = false; error = null
        progress = StorageProgress(0, 0, null)
        scanJob = scope.launch {
            try {
                val result = container.storage.scan { p -> progress = p }
                selected = selected.intersect(result.candidates.map { it.id }.toSet())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: uiContext.getString(R.string.ui_couldn_t_measure_storage_try_again) }
            finally { progress = null; scanJob = null }
        }
    }
    fun stop() { scanJob?.cancel(); scanJob = null; progress = null; stopped = true }

    LaunchedEffect(Unit) { rescan() }
    val busy = scanning || cleaning
    val heading = remember { BringIntoViewRequester() }
    LaunchedEffect(view, reviewing) { withFrameNanos { }; heading.bringIntoView() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().bringIntoViewRequester(heading), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (reviewing) stringResource(R.string.settings_storage_review_cleanup) else stringResource(R.string.ui_storage_on_this_device),
                    style = MaterialTheme.typography.titleMedium)
                inventory?.let {
                    val now = System.currentTimeMillis()
                    Text(if (now - it.report.finishedAt < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.storage_scanned_now)
                        else stringResource(R.string.storage_scanned_at, DateUtils.getRelativeTimeSpanString(it.report.finishedAt,
                            now, DateUtils.MINUTE_IN_MILLIS).toString()),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (scanning) OutlinedButton(onClick = ::stop) {
                Icon(Icons.Outlined.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.storage_stop))
            } else FilledTonalButton(onClick = ::rescan, enabled = !cleaning && confirmation == null) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.storage_rescan))
            }
        }
        AnimatedVisibility(scanning) { progress?.let { ScanProgressCard(it) } }
        if (cleaning) AppLinearProgressIndicator(Modifier.fillMaxWidth())
        if (stopped && !scanning) Text(stringResource(if (inventory != null) R.string.storage_scan_stopped else R.string.storage_scan_stopped_empty),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        report?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        val data = inventory
        if (data == null) {
            if (!scanning) {
                Text(stringResource(R.string.ui_storage_is_unavailable), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = ::rescan) { Text(stringResource(R.string.settings_action_try_again)) }
            }
        } else if (reviewing) {
            CleanupReview(data, selected, busy, onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                onInfo = { title, body -> detail = title to body }, onReview = { confirmation = it }, onBack = { reviewing = false })
        } else {
            val reportData = data.report
            StorageSummary(reportData, data.candidates.sumOf { it.bytes })
            ExpressiveSearchField(query, { query = it.take(120) }, stringResource(R.string.storage_search_hint))
            ConnectedChoice(listOf(
                Triple(StorageView.CATEGORIES, stringResource(R.string.storage_view_categories), null),
                Triple(StorageView.LARGEST, stringResource(R.string.storage_view_largest), null),
                Triple(StorageView.NOT_MEASURED, stringResource(R.string.storage_view_unmeasured), reportData.notMeasured.size.takeIf { it > 0 }),
            ), view, { view = it })
            val shown = reportData.items.filter { StorageReport.matches(it, storageTitle(uiContext, it), query) }
            val open: (StorageItem) -> Unit = { itemDetail = it.id }
            when (view) {
                StorageView.CATEGORIES -> {
                    val groups = reportData.groups(shown)
                    if (groups.isEmpty()) EmptyNote(stringResource(R.string.storage_no_matches))
                    groups.forEach { group -> CategoryCard(group, reportData, query.isNotBlank(), open) }
                }
                StorageView.LARGEST -> {
                    val largest = reportData.largest(30, shown)
                    if (largest.isEmpty()) EmptyNote(stringResource(R.string.storage_no_matches))
                    else ItemPanel(largest, reportData, showCategory = true, open)
                }
                StorageView.NOT_MEASURED -> {
                    val missing = shown.filter { it.notMeasured }
                    if (missing.isEmpty()) EmptyNote(stringResource(if (query.isBlank()) R.string.storage_all_measured else R.string.storage_no_matches))
                    else ItemPanel(missing, reportData, showCategory = true, open)
                }
            }
            Button(onClick = { reviewing = true; selected = data.candidates.filter { it.group == "Downloads" }.map { it.id }.toSet() },
                enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.CleaningServices, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_storage_review_cleanup))
            }
            Text(stringResource(R.string.storage_measure_note_full), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    itemDetail?.let { id ->
        val data = inventory
        val item = data?.report?.item(id)
        if (item != null) ItemDetailDialog(item, data.report, onDismiss = { itemDetail = null },
            onRequirements = if (item.role == StorageRole.LINUX_PACKAGE) ({ itemDetail = null; toolDetail = item.name }) else null)
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
                    try { container.linuxEnv.releasePackageRequirement(name, owner); release = null; rescan() }
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
                            scanJob?.cancel()
                            val result = container.storage.clean(chosen)
                            report = uiContext.getString(R.string.ui_removed_from_files, (storageSize(result.removedBytes)).toString(), (result.removedFiles).toString()) +
                                if (result.skipped > 0) uiContext.getString(R.string.ui_changed_or_unavailable_items_skipped, (result.skipped).toString()) else ""
                            confirmation = null; selected = emptySet(); reviewing = false
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: uiContext.getString(R.string.ui_cleanup_failed_scan_again); confirmation = null }
                        finally { cleaning = false }
                        rescan()
                    }
                }) { Text(if (cleaning) uiContext.getString(R.string.ui_cleaning) else uiContext.getString(R.string.ui_free, (storageSize(chosen.sumOf { it.bytes })).toString())) }
            }, dismissButton = { TextButton(enabled = !cleaning, onClick = { confirmation = null }) { Text(stringResource(R.string.settings_action_cancel)) } })
    }
}

private fun candidatePath(candidate: StorageCandidate): String = candidate.target.absolutePath

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScanProgressCard(progress: StorageProgress) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadingIndicator(Modifier.size(36.dp))
                Column(Modifier.weight(1f)) {
                    Text(when {
                        progress.total == 0 -> stringResource(R.string.storage_progress_starting)
                        progress.category == null -> stringResource(R.string.storage_progress_cleanup)
                        else -> stringResource(storageCategoryName(progress.category))
                    }, style = MaterialTheme.typography.titleSmall)
                    if (progress.total > 0) Text(stringResource(R.string.storage_progress_count, minOf(progress.done + 1, progress.total), progress.total),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            if (progress.total > 0) AppLinearProgressIndicator({ progress.fraction }, Modifier.fillMaxWidth())
            else AppLinearProgressIndicator(Modifier.fillMaxWidth())
            if (progress.current.isNotBlank()) Text(progress.current, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun categoryColor(category: StorageCategory): Color {
    val s = MaterialTheme.colorScheme
    return when (category) {
        StorageCategory.APP_DATA -> s.primary
        StorageCategory.PLUGINS -> s.tertiary
        StorageCategory.SKILLS -> s.secondary
        StorageCategory.WORKSPACES -> s.inversePrimary
        StorageCategory.LINUX_PACKAGES -> s.onPrimaryContainer
        StorageCategory.LOCAL_MODELS -> s.onTertiaryContainer
        StorageCategory.CACHES -> s.outline
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageSummary(report: StorageReport, cleanable: Long) {
    val groups = report.groups()
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(storageSize(report.total), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.storage_total_label), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val total = report.total.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                groups.filter { it.bytes > 0 }.forEach { group ->
                    Box(Modifier.weight((group.bytes.toFloat() / total).coerceAtLeast(0.01f)).fillMaxHeight().background(categoryColor(group.category)))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { group ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(categoryColor(group.category)))
                        Text(stringResource(R.string.storage_category_summary, stringResource(storageCategoryName(group.category)), storageSize(group.bytes)),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill(Icons.Outlined.Inventory2, pluralStringResource(R.plurals.storage_items, report.items.size, report.items.size))
                if (report.notMeasured.isNotEmpty()) StatPill(Icons.Outlined.ErrorOutline,
                    stringResource(R.string.storage_not_measured_count, report.notMeasured.size),
                    container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
                if (report.partial > 0) StatPill(Icons.Outlined.VisibilityOff, stringResource(R.string.storage_partial_count, report.partial))
                if (cleanable > 0) StatPill(Icons.Outlined.CleaningServices, stringResource(R.string.ui_available_to_review, storageSize(cleanable)),
                    container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (report.notMeasured.isNotEmpty()) Text(
                pluralStringResource(R.plurals.storage_total_excludes, report.notMeasured.size, report.notMeasured.size),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (report.timedOut) Text(stringResource(R.string.storage_timed_out_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CategoryBadge(category: StorageCategory) {
    val scheme = MaterialTheme.colorScheme
    val shape = when (category) {
        StorageCategory.APP_DATA -> MaterialShapes.Square
        StorageCategory.PLUGINS -> MaterialShapes.Pill
        StorageCategory.SKILLS -> MaterialShapes.Cookie9Sided
        StorageCategory.WORKSPACES -> MaterialShapes.Arch
        StorageCategory.LINUX_PACKAGES -> MaterialShapes.Cookie6Sided
        StorageCategory.LOCAL_MODELS -> MaterialShapes.Clover4Leaf
        StorageCategory.CACHES -> MaterialShapes.Sunny
    }
    Surface(Modifier.size(44.dp), shape = shape.toShape(), color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer) {
        Box(contentAlignment = Alignment.Center) { Icon(storageCategoryIcon(category), null, Modifier.size(22.dp)) }
    }
}

@Composable
private fun CategoryCard(group: StorageGroup, report: StorageReport, searching: Boolean, onOpen: (StorageItem) -> Unit) {
    var expanded by rememberSaveable(group.category) { mutableStateOf(false) }
    var all by rememberSaveable(group.category) { mutableStateOf(false) }
    val open = expanded || searching
    Surface(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryBadge(group.category)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(storageCategoryName(group.category)), style = MaterialTheme.typography.titleSmall)
                    Text(pluralStringResource(R.plurals.storage_items, group.items.size, group.items.size),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (group.notMeasured > 0) StatPill(Icons.Outlined.ErrorOutline, group.notMeasured.toString(),
                    container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
                Text(storageSize(group.bytes), style = MaterialTheme.typography.titleSmall)
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (open) {
                val limit = 8
                val visible = if (all || searching || group.items.size <= limit + 2) group.items else group.items.take(limit)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                visible.forEachIndexed { index, item ->
                    InventoryRow(item, report, showCategory = false) { onOpen(item) }
                    if (index < visible.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
                if (!searching && group.items.size > limit + 2) TextButton(onClick = { all = !all }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (all) stringResource(R.string.storage_show_less) else stringResource(R.string.storage_show_all, group.items.size))
                }
            }
        }
    }
}

@Composable
private fun ItemPanel(items: List<StorageItem>, report: StorageReport, showCategory: Boolean, onOpen: (StorageItem) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            items.forEachIndexed { index, item ->
                InventoryRow(item, report, showCategory) { onOpen(item) }
                if (index < items.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

/** The status line under an item: why it was not measured, where it is counted, or what it is. */
@Composable
private fun itemStatus(item: StorageItem, report: StorageReport): String {
    val context = LocalContext.current
    val gap = item.gap
    return when {
        gap == null -> storageNote(context, item)
        gap.included -> item.countedIn?.let { report.item(it) }?.let { stringResource(R.string.storage_counted_in, storageTitle(context, it)) }
            ?: stringResource(storageGapText(gap))
        else -> stringResource(storageGapText(gap))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InventoryRow(item: StorageItem, report: StorageReport, showCategory: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(storageTitle(context, item), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(itemStatus(item, report), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (item.notMeasured) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            item.path?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val pills = buildList {
                if (showCategory) add(stringResource(storageCategoryName(item.category)))
                if (item.shared) add(stringResource(R.string.storage_shared))
                if (item.partial) add(stringResource(R.string.storage_partial))
                if (item.enabled == false) add(stringResource(R.string.storage_disabled))
                if (item.role == StorageRole.PLUGIN) add(item.scope ?: stringResource(R.string.storage_global))
                else item.scope?.let(::add)
                item.version?.let(::add)
            }
            if (pills.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                pills.forEach { StatPill(null, it) }
            }
        }
        when {
            item.bytes != null -> Text(storageSize(item.bytes), style = MaterialTheme.typography.labelLarge)
            item.notMeasured -> StatPill(null, stringResource(R.string.storage_not_measured),
                container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun EmptyNote(text: String) = Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Text(text, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ItemDetailDialog(item: StorageItem, report: StorageReport, onDismiss: () -> Unit, onRequirements: (() -> Unit)?) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(storageCategoryIcon(item.category), null) },
        title = { Text(storageTitle(context, item)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(storageNote(context, item), style = MaterialTheme.typography.bodyMedium)
                DetailLine(Icons.Outlined.DataUsage, stringResource(R.string.storage_detail_size), when {
                    item.bytes != null -> storageSize(item.bytes) + " · " + pluralStringResource(R.plurals.storage_detail_files, item.files, item.files)
                    item.gap?.included == true -> itemStatus(item, report)
                    else -> stringResource(R.string.storage_not_measured)
                })
                item.gap?.takeUnless { it.included && it != com.androidharness.app.data.storage.StorageGap.UNTRACKED }?.let { gap ->
                    DetailLine(Icons.Outlined.ErrorOutline, stringResource(R.string.storage_detail_reason), stringResource(storageGapText(gap)),
                        tint = if (item.notMeasured) MaterialTheme.colorScheme.error else null)
                    if (item.seenBytes > 0) Text(stringResource(R.string.storage_timed_out_seen, storageSize(item.seenBytes)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.partial) Text(stringResource(R.string.storage_partial_detail), style = MaterialTheme.typography.bodySmall)
                if (item.shared) Text(stringResource(R.string.storage_shared_detail), style = MaterialTheme.typography.bodySmall)
                item.version?.let { Text(stringResource(R.string.storage_detail_version, it), style = MaterialTheme.typography.bodySmall) }
                item.scope?.let { Text(stringResource(R.string.storage_detail_scope, it), style = MaterialTheme.typography.bodySmall) }
                if (item.owners.isNotEmpty()) Text(stringResource(
                    if (item.role == StorageRole.LINUX_SHARED_FILES) R.string.storage_detail_shared_by else R.string.storage_detail_used_by,
                    item.owners.joinToString()), style = MaterialTheme.typography.bodySmall)
                item.path?.let { first ->
                    val many = item.morePaths.isNotEmpty()
                    val path = (listOf(first) + item.morePaths).joinToString("\n")
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(if (many) R.string.storage_detail_locations else R.string.storage_detail_location),
                            style = MaterialTheme.typography.labelLarge)
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            SelectionContainer {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    (listOf(first) + item.morePaths).forEach {
                                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(path)); copied = true }) {
                            Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(when {
                                copied && many -> R.string.storage_paths_copied
                                copied -> R.string.storage_path_copied
                                many -> R.string.storage_copy_paths
                                else -> R.string.storage_copy_path
                            }))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_done)) } },
        dismissButton = onRequirements?.let { action -> { TextButton(onClick = action) { Text(stringResource(R.string.storage_manage_requirements)) } } })
}

@Composable
private fun DetailLine(icon: ImageVector, label: String, value: String, tint: Color? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = tint ?: MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun CleanupReview(data: StorageInventory, selected: Set<String>, busy: Boolean, onToggle: (String) -> Unit,
    onInfo: (String, String) -> Unit, onReview: (List<StorageCandidate>) -> Unit, onBack: () -> Unit) {
    val uiContext = LocalContext.current
    Text(stringResource(R.string.settings_storage_cleanup_note), style = MaterialTheme.typography.bodySmall)
    if (data.candidates.isEmpty()) Text(stringResource(R.string.settings_storage_nothing_to_clean), style = MaterialTheme.typography.bodyMedium)
    data.candidates.groupBy { it.group }.forEach { (group, items) ->
        Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column {
                items.forEachIndexed { index, candidate ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Checkbox) { onToggle(candidate.id) }
                        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
    SettingsPanel(Modifier.fillMaxWidth()) {
        StorageRow(Icons.Outlined.Inventory2, uiContext.getString(R.string.ui_unused_managed_packages),
            uiContext.getString(R.string.ui_unknown_ownership_or_workspace_requirements_stay_protected), uiContext.getString(R.string.ui_details)) {
            onInfo(uiContext.getString(R.string.ui_managed_packages), uiContext.getString(R.string.ui_new_installs_record_files_dependencies_and_requirements))
        }
        HorizontalDivider()
        StorageRow(Icons.Outlined.Shield, uiContext.getString(R.string.settings_storage_always_kept),
            uiContext.getString(R.string.ui_source_folders_required_dependencies_models_history_undo), "") {
            onInfo(uiContext.getString(R.string.settings_storage_always_kept), uiContext.getString(R.string.settings_storage_always_kept_detail))
        }
    }
    val chosen = data.candidates.filter { it.id in selected }
    Button(onClick = { onReview(chosen) }, enabled = chosen.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_storage_review_size, storageSize(chosen.sumOf { it.bytes })))
    }
    TextButton(onClick = onBack, enabled = !busy) { Text(stringResource(R.string.settings_storage_back)) }
}

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
