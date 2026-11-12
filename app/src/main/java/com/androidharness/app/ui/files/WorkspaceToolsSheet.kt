package com.androidharness.app.ui.files

import com.androidharness.app.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import com.androidharness.app.ui.common.AppLinearProgressIndicator
import com.androidharness.app.ui.common.ExpressiveEmptyState
import com.androidharness.app.ui.common.ExpressiveIconBadge
import com.androidharness.app.ui.common.ExpressiveListRow
import com.androidharness.app.ui.common.ExpressivePill
import com.androidharness.app.ui.settings.SettingsChoiceGroup
import com.androidharness.app.ui.settings.SettingsExpressiveTheme
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.core.Diff
import com.androidharness.app.ui.common.ExpressiveIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

internal enum class WorkspaceToolMode { FILES, SEARCH, COMMANDS }
internal val LocalWorkspaceToolLauncher = staticCompositionLocalOf<((WorkspaceToolMode) -> Unit)?> { null }
internal data class WorkspaceCommand(val title: String, val detail: String, val icon: ImageVector, val action: () -> Unit)
private data class ReplacementPreview(val change: ProjectReplacement, val diff: String, val blocked: String?)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WorkspaceToolsSheet(
    container: AppContainer,
    sessionId: String?,
    projectId: String?,
    initialMode: WorkspaceToolMode,
    onDismiss: () -> Unit,
    onOpenFile: (String, Int?) -> Unit,
    commands: List<WorkspaceCommand> = emptyList(),
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val fs by remember(sessionId, projectId) { container.workspace.forTools(sessionId, projectId) }
        .collectAsStateWithLifecycle(initialValue = null)
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var query by rememberSaveable(mode) { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var replaceOpen by rememberSaveable { mutableStateOf(false) }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var regex by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var include by rememberSaveable { mutableStateOf("") }
    var exclude by rememberSaveable { mutableStateOf("") }
    var index by remember(fs) { mutableStateOf<WorkspaceFileIndex?>(null) }
    var result by remember(fs) { mutableStateOf<ProjectSearchResult?>(null) }
    var resultOptions by remember { mutableStateOf<ProjectSearchOptions?>(null) }
    var busy by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var error by remember(fs) { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<ReplacementPreview>?>(null) }
    var previewFs by remember { mutableStateOf<com.androidharness.app.workspace.WorkspaceFs?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var receipt by remember { mutableStateOf<ReplacementReceipt?>(null) }
    val options = ProjectSearchOptions(query, matchCase, regex, include, exclude)
    val focus = remember { FocusRequester() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var highlighted by remember(mode, query) { mutableIntStateOf(0) }
    val currentFs = fs
    LaunchedEffect(mode, sheetState.currentValue) {
        // Requesting focus during the opening animation can leave a blank sheet with a hardware keyboard.
        if (sheetState.currentValue == SheetValue.Expanded) focus.requestFocus()
    }

    LaunchedEffect(currentFs) {
        index = null
        if (currentFs == null) return@LaunchedEffect
        try { index = withContext(Dispatchers.IO) { WorkspaceSearch.index(currentFs) } }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: uiContext.getString(R.string.ui_could_not_read_workspace) }
    }
    LaunchedEffect(currentFs, index, options, mode, receipt) {
        result = null; resultOptions = null; error = null
        if (mode != WorkspaceToolMode.SEARCH || query.isEmpty() || preview != null || receipt != null) return@LaunchedEffect
        val f = currentFs ?: return@LaunchedEffect
        val files = index ?: return@LaunchedEffect
        busy = true
        try {
            delay(250)
            result = withContext(Dispatchers.IO) { WorkspaceSearch.search(f, files, options) }
            resultOptions = options
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: uiContext.getString(R.string.ui_search_failed) }
        finally { busy = false }
    }
    val files = remember(index, query) {
        WorkspaceSearch.quickOpen(index?.paths.orEmpty(), WorkspaceSearch.quickOpenQuery(query).name)
    }
    val line = WorkspaceSearch.quickOpenQuery(query).line
    fun open(path: String, atLine: Int? = null) { onDismiss(); onOpenFile(path, atLine) }
    fun changeMode(next: WorkspaceToolMode) { if (!busy && !applying) { mode = next; preview = null; receipt = null; error = null } }
    val allCommands = listOf(
        WorkspaceCommand("Quick Open", "Find a file by name · Ctrl+P", Icons.Outlined.FindInPage) { changeMode(WorkspaceToolMode.FILES) },
        WorkspaceCommand("Search and replace in project", "Find text across files · Ctrl+Shift+F", Icons.Outlined.ManageSearch) { changeMode(WorkspaceToolMode.SEARCH) },
    ) + commands
    val matchingCommands = allCommands.filter { query.isBlank() || (it.title + " " + it.detail).contains(query.trim(), true) }
    fun activateHighlighted() {
        when(mode) {
            WorkspaceToolMode.FILES -> {
                val target = WorkspaceSearch.quickOpenQuery(query)
                val current = WorkspaceSearch.quickOpen(index?.paths.orEmpty(), target.name)
                current.getOrNull(highlighted.coerceAtMost((current.size - 1).coerceAtLeast(0)))?.let { open(it, target.line) }
            }
            WorkspaceToolMode.COMMANDS -> {
                val current = allCommands.filter { query.isBlank() || (it.title + " " + it.detail).contains(query.trim(), true) }
                current.getOrNull(highlighted.coerceAtMost((current.size - 1).coerceAtLeast(0)))?.action?.invoke()
            }
            WorkspaceToolMode.SEARCH -> Unit
        }
    }
    suspend fun hasDraft(path: String): Boolean {
        val f = currentFs ?: return true
        return container.editorDrafts.read(EditorDraftStore.key(sessionId, projectId, f.displayPath, path)) != null
    }
    fun review() {
        val found = result ?: return
        if (resultOptions != options || busy) return
        keyboard?.hide()
        busy = true; error = null
        scope.launch {
            try {
                val changes = withContext(Dispatchers.Default) { WorkspaceSearch.replacements(found, options, replacement) }
                val rows = changes.map { change -> ReplacementPreview(change,
                    Diff.unified(change.source.decoded.text, change.text, change.source.path), withContext(Dispatchers.IO) {
                        val f = currentFs
                        when {
                            f is com.androidharness.app.workspace.ChatWorkspaceFs && !f.target(change.source.path).first.canEdit -> uiContext.getString(R.string.ui_read_only_workspace)
                            hasDraft(change.source.path) -> uiContext.getString(R.string.ui_unsaved_draft_save_or_discard_first)
                            else -> null
                        }
                    }) }
                preview = rows
                previewFs = currentFs
                selected = rows.filter { it.blocked == null }.map { it.change.source.path }.toSet()
                expanded = rows.firstOrNull()?.let { setOf(it.change.source.path) }.orEmpty()
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: uiContext.getString(R.string.ui_could_not_preview_replacements) }
            finally { busy = false }
        }
    }
    fun apply() {
        val f = previewFs ?: return
        if (currentFs?.displayPath != f.displayPath) { error = uiContext.getString(R.string.ui_workspace_changed_reopen_project_search_to_review); return }
        val chosen = preview.orEmpty().filter { it.change.source.path in selected }.map { it.change }
        if (chosen.isEmpty() || applying) return
        applying = true; error = null
        container.editorScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    container.runManager.withIdleWorkspace(f, uiContext.getString(R.string.ui_an_agent_is_working_in_this_workspace_879)) {
                        WorkspaceSearch.apply(f, chosen, ::hasDraft) { change ->
                            sessionId?.let { sid ->
                                val counts = Diff.lineCounts(change.source.decoded.text, change.text)
                                container.sessions.recordFileChange(sid, change.source.path, counts.first.toLong(), counts.second.toLong(),
                                    existedBefore = true, existsAfter = true, beforeText = change.source.decoded.text)
                            }
                        }
                    }
                }
                container.editorTabs.invalidateSaved(f.displayPath, outcome.saved.toSet())
                receipt = outcome; preview = null; result = null
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: uiContext.getString(R.string.ui_replacement_failed) }
            finally { applying = false }
        }
    }

    SettingsExpressiveTheme {
        ModalBottomSheet(
            onDismissRequest = { if (!applying) onDismiss() },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).imePadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (preview != null) uiContext.getString(R.string.ui_review_replacements) else when (mode) {
                            WorkspaceToolMode.FILES -> uiContext.getString(R.string.ui_quick_open)
                            WorkspaceToolMode.SEARCH -> uiContext.getString(R.string.ui_project_search)
                            WorkspaceToolMode.COMMANDS -> uiContext.getString(R.string.ui_command_palette)
                        }, style = MaterialTheme.typography.headlineSmall)
                        Text(if (preview != null) uiContext.getString(R.string.ui_choose_which_files_to_update) else uiContext.getString(R.string.ui_current_workspace), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ExpressiveIconButton(onClick = { if (!applying) onDismiss() }, enabled = !applying, colors = IconButtonDefaults.filledTonalIconButtonColors()) { Icon(Icons.Outlined.Close, uiContext.getString(R.string.ui_close_workspace_tools)) }
                }
                Spacer(Modifier.height(12.dp))
                val reviewed = preview
                val completed = receipt
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item(key = "workspace-controls") {
                        Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if(currentFs == null && mode != WorkspaceToolMode.COMMANDS) {
                                Text(uiContext.getString(R.string.ui_open_files_and_choose_a_workspace_to), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                                commands.firstOrNull { it.title == "Open files" }?.let { command -> TextButton(onClick = command.action) { Text(uiContext.getString(R.string.ui_open_files)) } }
                            }
                            if (preview == null && receipt == null) {
                                SettingsChoiceGroup(
                                    listOf(WorkspaceToolMode.FILES to uiContext.getString(R.string.ui_files), WorkspaceToolMode.SEARCH to uiContext.getString(R.string.ui_search), WorkspaceToolMode.COMMANDS to uiContext.getString(R.string.ui_commands)),
                                    selected = { it == mode }, onSelect = { changeMode(it) }, enabled = !busy)
                                OutlinedTextField(query, { query = it; error = null }, Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                                        if(mode != WorkspaceToolMode.SEARCH && event.type == KeyEventType.KeyDown) {
                                            val count = if(mode == WorkspaceToolMode.FILES) files.size else matchingCommands.size
                                            when(event.key) {
                                                Key.DirectionDown -> { highlighted = (highlighted + 1).coerceAtMost((count - 1).coerceAtLeast(0)); true }
                                                Key.DirectionUp -> { highlighted = (highlighted - 1).coerceAtLeast(0); true }
                                                Key.Enter, Key.NumPadEnter -> { activateHighlighted(); true }
                                                else -> false
                                            }
                                        } else false
                                    }.semanticsDescription(when(mode) { WorkspaceToolMode.FILES -> uiContext.getString(R.string.ui_file_name_or_path); WorkspaceToolMode.SEARCH -> uiContext.getString(R.string.ui_find_in_project); WorkspaceToolMode.COMMANDS -> uiContext.getString(R.string.ui_search_commands) }),
                                    enabled = !applying, singleLine = true, shape = CircleShape,
                                    label = { Text(when(mode) { WorkspaceToolMode.FILES -> uiContext.getString(R.string.ui_file_name_or_path); WorkspaceToolMode.SEARCH -> uiContext.getString(R.string.ui_find_in_project); WorkspaceToolMode.COMMANDS -> uiContext.getString(R.string.ui_search_commands) }) },
                                    leadingIcon = { Icon(if (mode == WorkspaceToolMode.COMMANDS) Icons.Outlined.Terminal else Icons.Outlined.Search, null) },
                                    trailingIcon = { if(query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, uiContext.getString(R.string.ui_clear_query)) } },
                                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = {
                                        activateHighlighted()
                                    }))
                                if (mode == WorkspaceToolMode.SEARCH) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(matchCase, { matchCase = !matchCase }, label = { Text(uiContext.getString(R.string.ui_aa)) }, modifier = Modifier.semanticsDescription(uiContext.getString(R.string.ui_match_case)))
                                        FilterChip(regex, { regex = !regex }, label = { Text(".*") }, modifier = Modifier.semanticsDescription(uiContext.getString(R.string.ui_regular_expression)))
                                        FilterChip(filtersOpen, { filtersOpen = !filtersOpen }, label = { Text(uiContext.getString(R.string.ui_filters)) })
                                        FilterChip(replaceOpen, { replaceOpen = !replaceOpen }, label = { Text(uiContext.getString(R.string.ui_replace)) })
                                    }
                                    if (filtersOpen) {
                                        OutlinedTextField(include, { include = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(uiContext.getString(R.string.ui_include_files)) }, placeholder = { Text("**/*.kt, **/*.js") }, shape = RoundedCornerShape(16.dp))
                                        Spacer(Modifier.height(6.dp))
                                        OutlinedTextField(exclude, { exclude = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(uiContext.getString(R.string.ui_exclude_files)) }, placeholder = { Text("**/generated/**") }, shape = RoundedCornerShape(16.dp))
                                    }
                                    if (replaceOpen) {
                                        Spacer(Modifier.height(6.dp))
                                        OutlinedTextField(replacement, { replacement = it }, Modifier.fillMaxWidth().semanticsDescription(uiContext.getString(R.string.ui_replace_with)), singleLine = true, label = { Text(uiContext.getString(R.string.ui_replace_with)) },
                                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), supportingText = if(regex) {{ Text(uiContext.getString(R.string.ui_use_1_for_capture_groups)) }} else null, shape = RoundedCornerShape(20.dp))
                                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                                            Button(onClick = ::review, enabled = !busy && resultOptions == options && result?.files?.isNotEmpty() == true && result?.limited == false) {
                                                Icon(Icons.Outlined.Difference, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(uiContext.getString(R.string.ui_review_changes))
                                            }
                                        }
                                    }
                                }
                            }
                            if (busy || applying || currentFs != null && index == null && mode != WorkspaceToolMode.COMMANDS && error == null) AppLinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                            error?.let { Text(it, Modifier.fillMaxWidth().padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                        }
                    }
                    when {
                        completed != null -> {
                            item { Text(uiContext.getString(R.string.ui_updated, (completed.saved.size).toString(), (if(completed.saved.size == 1) "file" else "files").toString()), style = MaterialTheme.typography.titleMedium) }
                            items(completed.saved, key = { "saved:$it" }) { path -> ListItem(headlineContent = { Text(path, maxLines = 2, overflow = TextOverflow.Ellipsis) }, leadingContent = { Icon(Icons.Outlined.CheckCircle, null) }) }
                            items(completed.failed.toList(), key = { "failed:${it.first}" }) { (path, failure) -> ListItem(headlineContent = { Text(path) }, supportingContent = { Text(failure, color = MaterialTheme.colorScheme.error) }) }
                            item { TextButton(onClick = { receipt = null; query = ""; result = null }) { Text(uiContext.getString(R.string.ui_new_search)) } }
                        }
                        reviewed != null -> {
                            if (reviewed.isEmpty()) item { Text(uiContext.getString(R.string.ui_no_changes_the_replacement_produces_the_same)) }
                            items(reviewed, key = { it.change.source.path }) { row ->
                                val path = row.change.source.path
                                Surface(Modifier.padding(vertical = 3.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(path in selected, { checked -> selected = if(checked) selected + path else selected - path }, enabled = row.blocked == null && !applying)
                                            Column(Modifier.weight(1f)) {
                                                Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(path.substringBeforeLast('/', uiContext.getString(R.string.ui_workspace_root_732)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                Text(row.blocked ?: "${row.change.source.count} ${if(row.change.source.count == 1) "match" else "matches"}", style = MaterialTheme.typography.labelSmall,
                                                    color = if(row.blocked != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            IconButton(onClick = { expanded = if(path in expanded) expanded - path else expanded + path }, modifier = Modifier.semanticsDescription(uiContext.getString(R.string.ui_preview_903, (path).toString()))) { Icon(if(path in expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) }
                                        }
                                        if(path in expanded) {
                                            HorizontalDivider()
                                            androidx.compose.foundation.text.selection.SelectionContainer {
                                                val colors = MaterialTheme.colorScheme
                                                val success = com.androidharness.app.ui.theme.LocalStatusColors.current.success
                                                val diff = androidx.compose.ui.text.buildAnnotatedString {
                                                    row.diff.lineSequence().forEach { line ->
                                                        val color = when { line.startsWith("+++") || line.startsWith("---") -> colors.onSurfaceVariant; line.startsWith('+') -> success; line.startsWith('-') -> colors.error; else -> colors.onSurfaceVariant }
                                                        pushStyle(androidx.compose.ui.text.SpanStyle(color = color)); append(line); append('\n'); pop()
                                                    }
                                                }
                                                Text(diff, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        mode == WorkspaceToolMode.COMMANDS -> {
                            if(matchingCommands.isEmpty()) item { ExpressiveEmptyState(Icons.Outlined.Terminal, uiContext.getString(R.string.ui_no_matching_commands), seed = 2) }
                            itemsIndexed(matchingCommands, key = { _, it -> it.title }) { i, command ->
                                ExpressiveListRow(i, matchingCommands.size, command.title, subtitle = command.detail, selected = i == highlighted,
                                    onClick = command.action,
                                    modifier = Modifier.animateItem(fadeInSpec = expressiveEffectsSpec(), placementSpec = expressiveSpatialSpec(), fadeOutSpec = expressiveEffectsSpec()),
                                    leading = { ExpressiveIconBadge(command.icon, selected = i == highlighted) },
                                    trailing = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp)) })
                            }
                        }
                        mode == WorkspaceToolMode.FILES -> {
                            item { Text(if(query.isBlank()) uiContext.getString(R.string.ui_project_files) else "${files.size} matching ${if(files.size == 1) "file" else "files"}", Modifier.padding(start = 16.dp, top = 6.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                            if(files.isEmpty() && index != null) item { ExpressiveEmptyState(Icons.Outlined.Search, uiContext.getString(R.string.ui_no_matching_files), seed = 4) }
                            itemsIndexed(files, key = { _, it -> it }) { i, path ->
                                ExpressiveListRow(i, files.size, path.substringAfterLast('/'), dense = true, selected = i == highlighted,
                                    subtitle = path.substringBeforeLast('/', uiContext.getString(R.string.ui_workspace_root_732)),
                                    onClick = { open(path, line) },
                                    modifier = Modifier.semanticsDescription(uiContext.getString(R.string.ui_open_file_907, (path).toString()))
                                        .animateItem(fadeInSpec = expressiveEffectsSpec(), placementSpec = expressiveSpatialSpec(), fadeOutSpec = expressiveEffectsSpec()),
                                    leading = { ExpressiveIconBadge(Icons.Outlined.Description, size = 36.dp, selected = i == highlighted,
                                        container = MaterialTheme.colorScheme.surfaceContainerHighest, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
                            }
                            if(index?.limited == true) item { Text(uiContext.getString(R.string.ui_file_list_limited_to_10_000_files), style = MaterialTheme.typography.bodySmall) }
                        }
                        mode == WorkspaceToolMode.SEARCH -> {
                            val found = result
                            item {
                                Text(when { query.isEmpty() -> uiContext.getString(R.string.ui_search_file_contents_across_this_project); busy -> uiContext.getString(R.string.ui_searching); found == null -> ""; found.files.isEmpty() -> uiContext.getString(R.string.ui_no_matches); else -> "${found.count} ${if(found.count == 1) "match" else "matches"} in ${found.files.size} ${if(found.files.size == 1) "file" else "files"}" },
                                    modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            if(found?.limited == true) item { Text(uiContext.getString(R.string.ui_results_are_limited_narrow_your_query_or), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            if(found != null && found.skipped > 0) item { Text(uiContext.getString(R.string.ui_binary_large_or_unreadable_files_skipped, (found.skipped).toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            found?.files?.forEach { file ->
                                item(key = "file:${file.path}") {
                                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Description, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(10.dp))
                                        Text(file.path, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.width(8.dp))
                                        ExpressivePill(file.count.toString(), strong = true)
                                    }
                                }
                                itemsIndexed(file.hits, key = { _, it -> "hit:${file.path}:${it.line}:${it.column}" }) { i, hit ->
                                    Surface(onClick = { open(file.path, hit.line) }, shape = com.androidharness.app.ui.settings.settingsSegmentShape(i, file.hits.size), color = MaterialTheme.colorScheme.surfaceContainer) {
                                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text(hit.line.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                            Text(hit.preview.ifEmpty { "↵" }, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if(reviewed != null) {
                    FlowRow(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { preview = null; error = null }, enabled = !applying) { Text(uiContext.getString(R.string.ui_back_to_search)) }
                        Button(onClick = ::apply, enabled = selected.isNotEmpty() && !applying) { Text(if(applying) uiContext.getString(R.string.ui_replacing) else uiContext.getString(R.string.ui_replace_in, (selected.size).toString(), (if(selected.size == 1) "file" else "files").toString())) }
                    }
                }
            }
        }
    }
}

private fun Modifier.semanticsDescription(label: String) = this.then(Modifier.semantics { contentDescription = label })
