package com.androidharness.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.skills.SkillDocument
import com.androidharness.app.skills.SkillMeta
import com.androidharness.app.skills.SkillParseException
import com.androidharness.app.skills.SkillParser
import com.androidharness.app.skills.SkillSource
import com.androidharness.app.tools.skillTemplate
import com.androidharness.app.ui.chat.MarkdownText
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.SwipeDismissibleSnackbarHost
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import com.androidharness.app.ui.theme.HarnessMono
import kotlinx.coroutines.launch

/** What the full-screen editor opens with. [note] explains where a save lands when that is not obvious. */
private data class SkillEditRequest(val title: String, val initial: String, val note: String? = null)

private val SOURCE_ORDER = listOf(SkillSource.USER, SkillSource.PROJECT, SkillSource.BUNDLED)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SkillsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var revision by remember { mutableIntStateOf(0) }
    val skills = remember(settings.disabledSkills, settings.cavemanInstalled, revision) { container.skills.list() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var query by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("all") }
    var source by rememberSaveable { mutableStateOf("") }
    var selectMode by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<SkillEditRequest?>(null) }
    var confirmDelete by remember { mutableStateOf<List<String>?>(null) }

    val names = remember(skills) { skills.map { it.name }.toSet() }
    val chosen = selected.filter { it in names }.toSet()
    val selecting = selectMode || chosen.isNotEmpty()
    fun stopSelecting() { selectMode = false; selected = emptyList() }
    fun toggleSelected(name: String) { selected = if (name in chosen) (chosen - name).toList() else (chosen + name).toList() }
    BackHandler(enabled = selecting) { stopSelecting() }

    val words = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val matching = skills.filter { skill ->
        (source.isBlank() || skill.source.name == source) &&
            words.all { skill.name.contains(it, true) || skill.description.contains(it, true) || skill.category.contains(it, true) }
    }
    val visible = matching.filter { status == "all" || (status == "on") == it.enabled }
    val groups = SOURCE_ORDER.mapNotNull { origin -> visible.filter { it.source == origin }.takeIf { it.isNotEmpty() }?.let { origin to it } }
    val enabledCount = skills.count { it.enabled }

    /** Applies a bulk switch and offers to put every changed skill back the way it was. */
    fun bulkEnable(on: Boolean) {
        val changed = skills.filter { it.name in chosen && it.enabled != on }.map { it.name }
        stopSelecting()
        if (changed.isEmpty()) return
        scope.launch {
            container.settings.setSkillsEnabled(changed, on)
            val message = context.resources.getQuantityString(if (on) R.plurals.sk_enabled_snack else R.plurals.sk_disabled_snack, changed.size, changed.size)
            if (snackbar.showSnackbar(message, context.getString(R.string.sk_undo), duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                container.settings.setSkillsEnabled(changed, !on)
            }
        }
    }
    fun toggle(skill: SkillMeta, on: Boolean) { scope.launch { container.settings.setSkillEnabled(skill.name, on) } }
    fun edit(document: SkillDocument) {
        editor = when (document.meta.source) {
            SkillSource.USER -> SkillEditRequest(context.getString(R.string.settings_skills_edit_title, document.meta.name), document.raw)
            SkillSource.BUNDLED -> SkillEditRequest(context.getString(R.string.settings_skills_edit_title, document.meta.name), document.raw,
                context.getString(R.string.sk_copy_note))
            SkillSource.PROJECT -> null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SwipeDismissibleSnackbarHost(snackbar) },
        topBar = {
            AnimatedContent(selecting, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "skills header") { on ->
                if (on) SelectionHeader(
                    count = chosen.size,
                    allSelected = visible.isNotEmpty() && visible.all { it.name in chosen },
                    onClose = ::stopSelecting,
                    onSelectAll = { all -> selected = if (all) (chosen + visible.map { it.name }).toList() else (chosen - visible.map { it.name }.toSet()).toList() },
                ) else AppHeader(
                    title = stringResource(R.string.settings_page_skills_title),
                    subtitle = stringResource(R.string.sk_summary, enabledCount, skills.size),
                    onBack = onBack,
                    actions = {
                        IconButton(onClick = { selectMode = true }, enabled = skills.isNotEmpty()) {
                            Icon(Icons.Outlined.Checklist, stringResource(R.string.sk_select))
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(!selecting, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                MediumExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.sk_new)) },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    onClick = { editor = SkillEditRequest(context.getString(R.string.settings_skills_add), skillTemplate()) },
                    expanded = !listState.canScrollBackward,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "controls") {
                    Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ExpressiveSearchField(query, { query = it.take(120) }, stringResource(R.string.sk_search))
                        ConnectedChoice(
                            listOf(
                                Triple("all", stringResource(R.string.pm_status_all), matching.size),
                                Triple("on", stringResource(R.string.sk_status_on), matching.count { it.enabled }),
                                Triple("off", stringResource(R.string.sk_status_off), matching.count { !it.enabled }),
                            ),
                            status, { status = it },
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SOURCE_ORDER.forEach { origin ->
                                val count = skills.count { it.source == origin }
                                if (count > 0) FilterChip(
                                    selected = source == origin.name,
                                    onClick = { source = if (source == origin.name) "" else origin.name },
                                    label = { Text("${sourceLabel(origin)} $count") },
                                    leadingIcon = { Icon(sourceIcon(origin), null, Modifier.size(FilterChipDefaults.IconSize)) },
                                    shape = CircleShape,
                                )
                            }
                        }
                        Text(stringResource(R.string.ui_the_model_sees_these_names_every_turn), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (visible.isEmpty()) item(key = "empty") {
                    SkillsEmpty(onClear = { query = ""; status = "all"; source = "" })
                }
                groups.forEach { (origin, members) ->
                    item(key = "header-${origin.name}") {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(sourceTitle(origin), Modifier.weight(1f), style = MaterialTheme.typography.titleMediumEmphasized)
                            if (selecting) {
                                val all = members.all { it.name in chosen }
                                TextButton(onClick = {
                                    selected = if (all) (chosen - members.map { it.name }.toSet()).toList() else (chosen + members.map { it.name }).toList()
                                }) { Text(stringResource(if (all) R.string.sk_clear_selection else R.string.sk_select_all)) }
                            } else Text(stringResource(R.string.sk_group_count, members.count { it.enabled }, members.size),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    itemsIndexed(members, key = { _, skill -> "${origin.name}-${skill.name}" }) { index, skill ->
                        SkillRow(
                            skill = skill,
                            shape = segmentShape(index, members.size),
                            selecting = selecting,
                            checked = skill.name in chosen,
                            onClick = { if (selecting) toggleSelected(skill.name) else detail = skill.name },
                            onLongClick = { selectMode = true; toggleSelected(skill.name) },
                            onToggle = { toggle(skill, it) },
                        )
                    }
                }
            }
            AnimatedVisibility(
                selecting,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).navigationBarsPadding(),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                val deletable = skills.filter { it.name in chosen && it.source == SkillSource.USER }.map { it.name }
                HorizontalFloatingToolbar(expanded = true, colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()) {
                    ToolbarAction(Icons.Outlined.ToggleOn, stringResource(R.string.sk_turn_on), chosen.isNotEmpty()) { bulkEnable(true) }
                    ToolbarAction(Icons.Outlined.ToggleOff, stringResource(R.string.sk_turn_off), chosen.isNotEmpty()) { bulkEnable(false) }
                    ToolbarAction(Icons.Outlined.DeleteOutline, stringResource(R.string.settings_action_delete), deletable.isNotEmpty()) { confirmDelete = deletable }
                }
            }
        }
    }

    detail?.let { name ->
        val document = remember(name, revision, settings.disabledSkills) { container.skills.document(name) }
        if (document == null) LaunchedEffect(name) { detail = null }
        else SkillDetailSheet(
            document = document,
            onDismiss = { detail = null },
            onToggle = { toggle(document.meta, it) },
            onEdit = { edit(document) },
            onDelete = { confirmDelete = listOf(document.meta.name) },
            onCopied = { scope.launch { snackbar.showSnackbar(context.getString(R.string.sk_copied)) } },
        )
    }
    editor?.let { request ->
        SkillEditor(
            request = request,
            onDismiss = { editor = null },
            onSave = { content ->
                val parsed = container.skills.saveUser(content)
                editor = null
                revision++
                detail = parsed.name
                scope.launch { snackbar.showSnackbar(context.getString(R.string.sk_saved, parsed.name)) }
            },
        )
    }
    confirmDelete?.let { targets ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, null) },
            title = { Text(pluralStringResource(R.plurals.sk_delete_title, targets.size, targets.size)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.sk_delete_hint))
                    Text(targets.take(8).joinToString(", ") + if (targets.size > 8) ", +${targets.size - 8}" else "",
                        style = MaterialTheme.typography.bodySmall, fontFamily = HarnessMono)
                }
            },
            confirmButton = {
                Button(onClick = {
                    targets.forEach { container.skills.deleteUser(it) }
                    confirmDelete = null
                    if (detail in targets) detail = null
                    stopSelecting()
                    revision++
                    scope.launch { snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.sk_deleted_snack, targets.size, targets.size)) }
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                    Text(stringResource(R.string.settings_action_delete))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.settings_action_cancel)) } },
        )
    }
}

@Composable
private fun sourceLabel(source: SkillSource) = stringResource(when (source) {
    SkillSource.USER -> R.string.sk_source_yours
    SkillSource.PROJECT -> R.string.sk_source_workspace
    SkillSource.BUNDLED -> R.string.ui_bundled
})

@Composable
private fun sourceTitle(source: SkillSource) = stringResource(when (source) {
    SkillSource.USER -> R.string.ui_your_skills
    SkillSource.PROJECT -> R.string.ui_this_workspace
    SkillSource.BUNDLED -> R.string.ui_bundled
})

private fun sourceIcon(source: SkillSource) = when (source) {
    SkillSource.USER -> Icons.Outlined.Person
    SkillSource.PROJECT -> Icons.Outlined.FolderOpen
    SkillSource.BUNDLED -> Icons.Outlined.Inventory2
}

/** Expressive grouped lists round the outer corners of a group and keep the joins tight. */
private fun segmentShape(index: Int, count: Int): Shape {
    val outer = 24.dp; val inner = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner, topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner, bottomEnd = if (index == count - 1) outer else inner,
    )
}

@Composable
private fun SelectionHeader(count: Int, allSelected: Boolean, onClose: () -> Unit, onSelectAll: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.sk_stop_selecting)) }
            AnimatedContent(count, label = "selected count", modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) { n ->
                Text(pluralStringResource(R.plurals.sk_selected, n, n), style = MaterialTheme.typography.titleMediumEmphasized)
            }
            TextButton(onClick = { onSelectAll(!allSelected) }) {
                Icon(if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (allSelected) R.string.sk_clear_selection else R.string.sk_select_all))
            }
        }
    }
}

@Composable
private fun ToolbarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = LocalContentColor.current.copy(alpha = if (enabled) 1f else .38f)) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SkillAvatar(skill: SkillMeta, size: Int = 42) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (skill.enabled) when (skill.source) {
        SkillSource.USER -> scheme.primaryContainer
        SkillSource.PROJECT -> scheme.secondaryContainer
        SkillSource.BUNDLED -> scheme.tertiaryContainer
    } else scheme.surfaceContainerHighest, label = "skill avatar")
    val content = if (!skill.enabled) scheme.onSurfaceVariant else when (skill.source) {
        SkillSource.USER -> scheme.onPrimaryContainer
        SkillSource.PROJECT -> scheme.onSecondaryContainer
        SkillSource.BUNDLED -> scheme.onTertiaryContainer
    }
    Surface(Modifier.size(size.dp), shape = MaterialShapes.Cookie6Sided.toShape(), color = container, contentColor = content) {
        Box(contentAlignment = Alignment.Center) {
            Text(skill.name.take(1).uppercase(), style = if (size > 48) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkillRow(
    skill: SkillMeta,
    shape: Shape,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val background by animateColorAsState(if (checked) scheme.secondaryContainer else scheme.surfaceContainerLow, label = "skill row")
    val enabledState = stringResource(if (skill.enabled) R.string.sk_status_on else R.string.sk_status_off)
    val state = if (selecting) stringResource(if (checked) R.string.sk_state_selected else R.string.sk_state_not_selected, enabledState) else enabledState
    Surface(
        Modifier.fillMaxWidth().clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = if (selecting) Role.Checkbox else Role.Button,
                onClickLabel = stringResource(if (selecting) R.string.sk_select else R.string.sk_open))
            .semantics { stateDescription = state },
        shape = shape,
        color = background,
    ) {
        Row(Modifier.padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AnimatedContent(selecting, transitionSpec = { scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut() }, label = "row leading") { on ->
                if (on) Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { Checkbox(checked = checked, onCheckedChange = null) }
                else SkillAvatar(skill)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(skill.name, style = MaterialTheme.typography.titleSmall, fontFamily = HarnessMono, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (skill.enabled) scheme.onSurface else scheme.onSurfaceVariant)
                if (skill.description.isNotBlank()) Text(skill.description, style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (skill.category.isNotBlank() && skill.category != "general") Text(skill.category.replaceFirstChar(Char::titlecase),
                    style = MaterialTheme.typography.labelSmall, color = scheme.primary)
            }
            if (!selecting) {
                val label = stringResource(R.string.sk_enable_named, skill.name)
                Switch(checked = skill.enabled, onCheckedChange = onToggle, modifier = Modifier.semantics { contentDescription = label })
            }
        }
    }
}

@Composable
private fun SkillsEmpty(onClear: () -> Unit) = Surface(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(32.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        @OptIn(ExperimentalMaterial3ExpressiveApi::class)
        Surface(Modifier.size(72.dp), shape = MaterialShapes.Puffy.toShape(), color = MaterialTheme.colorScheme.tertiaryContainer) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.SearchOff, null, Modifier.size(32.dp)) }
        }
        Text(stringResource(R.string.sk_empty), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(R.string.sk_empty_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = onClear) { Text(stringResource(R.string.pm_clear_filters)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SkillDetailSheet(
    document: SkillDocument,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopied: () -> Unit,
) {
    val meta = document.meta
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val parsed = remember(document.raw) { runCatching { SkillParser.parse(document.raw) }.getOrNull() }
    var markdown by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SkillAvatar(meta, size = 60)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(meta.name, style = MaterialTheme.typography.titleLargeEmphasized, fontFamily = HarnessMono)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatPill(sourceIcon(meta.source), sourceLabel(meta.source))
                        if (meta.category.isNotBlank()) StatPill(Icons.Outlined.Label, meta.category.replaceFirstChar(Char::titlecase))
                    }
                }
            }
            parsed?.description?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            Surface(shape = RoundedCornerShape(24.dp), color = if (meta.enabled) scheme.primaryContainer else scheme.surfaceContainerHighest,
                onClick = { onToggle(!meta.enabled) }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(if (meta.enabled) R.string.sk_status_on else R.string.sk_status_off), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(if (meta.enabled) R.string.sk_enabled_hint else R.string.sk_disabled_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    val label = stringResource(R.string.sk_enable_named, meta.name)
                    Switch(checked = meta.enabled, onCheckedChange = onToggle, modifier = Modifier.semantics { contentDescription = label })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (meta.source) {
                    SkillSource.USER -> Button(onClick = onEdit, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.settings_action_edit))
                    }
                    SkillSource.BUNDLED -> FilledTonalButton(onClick = onEdit, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Icon(Icons.Outlined.EditNote, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sk_edit_copy))
                    }
                    SkillSource.PROJECT -> Text(stringResource(R.string.sk_workspace_note), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(document.raw)); onCopied() }, modifier = Modifier.heightIn(min = 52.dp)) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sk_copy))
                }
                if (meta.source == SkillSource.USER) com.androidharness.app.ui.common.FilledTonalIconButton(onClick = onDelete, modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer)) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.settings_action_delete))
                }
            }
            ConnectedChoice(listOf(Triple(false, stringResource(R.string.sk_preview), null), Triple(true, stringResource(R.string.sk_markdown), null)),
                markdown, { markdown = it })
            Surface(shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.padding(16.dp)) {
                    if (markdown) SelectionContainer {
                        Text(document.raw, style = MaterialTheme.typography.bodySmall, fontFamily = HarnessMono)
                    } else MarkdownText(parsed?.body?.trim().orEmpty().ifBlank { document.raw }, media = false)
                }
            }
            if (document.files.isNotEmpty()) {
                Text(stringResource(R.string.sk_files, document.files.size), style = MaterialTheme.typography.titleSmall)
                Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        document.files.take(40).forEach { file ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Outlined.Description, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                                Text(file, style = MaterialTheme.typography.bodySmall, fontFamily = HarnessMono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (document.files.size > 40) Text("+${document.files.size - 40}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            document.folder?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = HarnessMono, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SkillEditor(
    request: SkillEditRequest,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var text by rememberSaveable(request) { mutableStateOf(request.initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val parsed = remember(text) { runCatching { SkillParser.parse(text) }.getOrNull() }
    val changed = text != request.initial
    fun close() { if (changed) discard = true else onDismiss() }
    fun save() {
        try { SkillParser.validateUserContent(text); onSave(text) }
        catch (e: SkillParseException) { error = e.message }
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = scheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::close) { Icon(Icons.Outlined.Close, stringResource(R.string.settings_action_cancel)) }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(request.title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.ui_catalog_line, parsed?.name ?: "?", parsed?.catalogDescription ?: stringResource(R.string.sk_fix_frontmatter)),
                            style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = ::save, enabled = error == null && parsed != null) {
                        Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.settings_action_save))
                    }
                }
                request.note?.let { note ->
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(20.dp), color = scheme.tertiaryContainer) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Info, null, Modifier.size(20.dp))
                            Text(note, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = runCatching { SkillParser.validateUserContent(it); null }.exceptionOrNull()?.message
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = HarnessMono),
                    label = { Text(stringResource(R.string.settings_skills_file)) },
                    shape = RoundedCornerShape(20.dp),
                    isError = error != null,
                    supportingText = {
                        Text(error ?: stringResource(R.string.sk_chars, text.length, com.androidharness.app.skills.ParsedSkill.MAX_USER_CHARS))
                    },
                )
            }
        }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.sk_discard_title)) },
        confirmButton = { TextButton(onClick = { discard = false; onDismiss() }) { Text(stringResource(R.string.sk_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.sk_keep_editing)) } },
    )
}
