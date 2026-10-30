package com.androidharness.app.ui.files

import com.androidharness.app.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.common.ExpressiveIconButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.TextButton

internal class EditorActions {
    var ready by mutableStateOf(false)
    var textAvailable by mutableStateOf(false)
    var close: ((() -> Unit) -> Unit)? = null
    var save: (() -> Unit)? = null
    var find: (() -> Unit)? = null
    var goToLine: (() -> Unit)? = null
    var toggleWrap: (() -> Unit)? = null
    var outline: (() -> Unit)? = null
    var format: (() -> Unit)? = null
    var definition: (() -> Unit)? = null
    var references: (() -> Unit)? = null
    var rename: (() -> Unit)? = null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WorkspaceEditorScreen(
    container: AppContainer,
    path: String,
    initialLine: Int? = null,
    sessionId: String? = null,
    projectId: String? = null,
    requestId: String = path,
    onBack: () -> Unit,
    navigationGuard: EditorNavigationGuard? = null,
    onAskAgent: ((String?, String) -> Unit)? = null,
    onOpenTerminal: (() -> Unit)? = null,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val fs by remember(sessionId, projectId) { container.workspace.forTools(sessionId, projectId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val project by remember(sessionId, projectId) { container.workspace.projectForTools(sessionId, projectId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val identity = fs?.displayPath
    val workspaceKey = remember(identity, sessionId, projectId) { identity?.let { EditorDraftStore.key(sessionId, projectId, it, "<open-tabs>") } }
    var workspace by remember(workspaceKey) { mutableStateOf<EditorWorkspace?>(null) }
    var failure by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var tools by remember(workspaceKey) { mutableStateOf<WorkspaceToolMode?>(null) }
    var openFiles by remember(workspaceKey) { mutableStateOf(false) }
    var pendingClose by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var jumpLine by remember(workspaceKey) { mutableStateOf<Int?>(null) }
    var jumpColumn by remember(workspaceKey) { mutableStateOf<Int?>(null) }
    var jumpRequest by remember(workspaceKey) { mutableLongStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(workspaceKey, requestId, path, initialLine) {
        val key = workspaceKey ?: return@LaunchedEffect
        try {
            val state = container.editorTabs.workspace(key, identity.orEmpty())
            if (state.requestId != requestId) {
                state.requestId = requestId; state.open(path); jumpLine = initialLine
                jumpColumn = null; jumpRequest++
                container.editorTabs.persist(state)
            }
            // Recover inactive dirty dots as well as the active draft, without native editor views.
            for (tab in state.paths.toList()) {
                val buffer = state.buffer(tab)
                if (!buffer.loaded) {
                    val draft = withContext(Dispatchers.IO) { container.editorDrafts.read(EditorDraftStore.key(sessionId, projectId, identity.orEmpty(), tab)) }
                    if (draft != null) buffer.restore(draft)
                }
            }
            workspace = state
        } catch (e: Exception) { if (e is CancellationException) throw e; failure = e.message ?: uiContext.getString(R.string.ui_could_not_restore_editor_tabs) }
    }
    val state = workspace
    DisposableEffect(state) { onDispose { state?.let(container.editorTabs::persist) } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(state, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) state?.let(container.editorTabs::persist)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val saving = state?.buffers?.values?.any { it.saving.value } == true
    fun select(tab: String, line: Int? = null) { if(!saving) { state?.open(tab); jumpLine = line; jumpColumn = null; jumpRequest++; state?.let(container.editorTabs::persist) } }
    val active = state?.active
    val actions = remember(active, state?.revision) { EditorActions() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val smart = remember(workspaceKey, state, fs) {
        val workspaceFs = fs
        if (state == null || workspaceFs == null) null else SmartEditorController(context, workspaceFs,
            container.editorDrafts, { file -> EditorDraftStore.key(sessionId, projectId, identity.orEmpty(), file) },
            buffers = { state.buffers.toMap() }, bufferFor = state::buffer, openRenamed = { files ->
                val current = state.active
                files.forEach(state::open)
                current?.let(state::open)
                container.editorTabs.persist(state)
            })
    }
    DisposableEffect(smart) { onDispose { smart?.close() } }
    LaunchedEffect(pendingClose, active, actions.ready) {
        val target = pendingClose ?: return@LaunchedEffect
        if (active == target && actions.ready) {
            pendingClose = null
            actions.close?.invoke { state?.close(target); state?.let(container.editorTabs::persist) }
        }
    }
    fun close(tab: String) {
        if (saving) return
        openFiles = false
        if (tab != active && state?.buffers?.get(tab)?.dirty?.value != true) {
            state?.close(tab); state?.let(container.editorTabs::persist)
        } else { select(tab); pendingClose = tab }
    }
    fun runCommand(action: (() -> Unit)?) { tools = null; action?.invoke() }
    val commands = listOf(
        WorkspaceCommand("Save file", "Save the active document · Ctrl+S", Icons.Outlined.Save) { runCommand(actions.save) },
        WorkspaceCommand("Find in current file", "Search inside the active editor", Icons.Outlined.FindInPage) { runCommand(actions.find) },
        WorkspaceCommand("Go to line", "Jump to a line in the active document", Icons.Outlined.FormatListNumbered) { runCommand(actions.goToLine) },
        WorkspaceCommand("Toggle word wrap", "Wrap long lines in the editor", Icons.Outlined.WrapText) { runCommand(actions.toggleWrap) },
    ).filter { actions.textAvailable } + listOfNotNull(
        active?.takeIf { SmartEditorSupport.outline(it) }?.let { WorkspaceCommand("Code outline", "Jump to a function, class or document symbol", Icons.Outlined.AccountTree) { runCommand(actions.outline) } },
        active?.takeIf { SmartEditorSupport.format(it) }?.let { WorkspaceCommand("Format Document", "Format the draft · Undo available", Icons.Outlined.AutoFixHigh) { runCommand(actions.format) } },
        active?.takeIf { SmartEditorSupport.navigation(it) }?.let { WorkspaceCommand("Go to Definition", "Open the symbol's project definition", Icons.Outlined.SubdirectoryArrowRight) { runCommand(actions.definition) } },
        active?.takeIf { SmartEditorSupport.navigation(it) }?.let { WorkspaceCommand("Find References", "Find uses of this symbol", Icons.Outlined.ManageSearch) { runCommand(actions.references) } },
        active?.takeIf { SmartEditorSupport.rename(it) }?.let { WorkspaceCommand("Rename Symbol", "Review semantic edits across the project", Icons.Outlined.DriveFileRenameOutline) { runCommand(actions.rename) } },
    ).filter { actions.textAvailable } + listOf(
        WorkspaceCommand("Open files", "Switch or close an editor tab", Icons.Outlined.Tab) { tools = null; openFiles = true },
    ) + listOfNotNull(onOpenTerminal?.let { action -> WorkspaceCommand("Open terminal", "Terminal for this workspace", Icons.Outlined.Terminal) { tools = null; actions.close?.invoke(action) ?: action() } })
    val chrome: @Composable () -> Unit = {
        Column {
            AppHeader(title = uiContext.getString(R.string.ui_editor), subtitle = if(state?.paths?.size == 1) active else project?.name ?: identity?.substringBefore(" [primary=")?.substringAfterLast('/') ?: "Workspace", onBack = { actions.close?.invoke(onBack) ?: onBack() },
                actions = {
                    FilledTonalButton(onClick = { openFiles = true }, contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = uiContext.getString(R.string.ui_open_files_open, (state?.paths?.size ?: 0).toString()) }) {
                        Icon(Icons.Outlined.Tab, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text((state?.paths?.size ?: 0).toString())
                    }
                    ExpressiveIconButton(onClick = { active?.let(::close) }, enabled = active != null && !saving) { Icon(Icons.Outlined.Close, uiContext.getString(R.string.ui_close_current_file)) }
                })
            if (state != null && state.paths.size > 1) {
                val selected = state.paths.indexOf(active).coerceAtLeast(0)
                PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow, divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }) {
                    state.paths.forEach { tab ->
                        val unsaved = state.buffers[tab]?.dirty?.value == true
                        Tab(selected = tab == active, enabled = !saving, onClick = { select(tab) }, modifier = Modifier.semantics { contentDescription = uiContext.getString(R.string.ui_tab, (tab).toString(), (if(unsaved) ", unsaved changes" else "").toString()) },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Text(tab.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
                                    if(unsaved) Surface(Modifier.size(6.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {}
                                }
                            })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(active.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if(state?.persistenceError != null) Text(uiContext.getString(R.string.ui_open_tabs_could_not_be_saved_your),
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if(event.isCtrlPressed && !event.isAltPressed) {
                val mode = when { event.key == Key.P && event.isShiftPressed -> WorkspaceToolMode.COMMANDS; event.key == Key.P -> WorkspaceToolMode.FILES; event.key == Key.F && event.isShiftPressed -> WorkspaceToolMode.SEARCH; else -> null }
                if(mode != null) { if(event.type == KeyEventType.KeyDown) tools = mode; true } else false
            } else false
        }) {
            if(active != null && state != null) key(workspaceKey, active, state.revision) {
                CodeEditorScreen(container, active, initialLine = jumpLine, sessionId = sessionId, projectId = projectId,
                    initialColumn = jumpColumn, jumpRequest = jumpRequest,
                    onBack = onBack, navigationGuard = navigationGuard, onAskAgent = onAskAgent,
                    suppliedBuffer = state.buffer(active), workspaceChrome = chrome, actions = actions,
                    onQuickOpen = { tools = WorkspaceToolMode.FILES }, onProjectSearch = { tools = WorkspaceToolMode.SEARCH },
                    onCommands = { tools = WorkspaceToolMode.COMMANDS }, smart = smart,
                    onOpenSmartLocation = { location -> select(location.path, location.line); jumpColumn = location.column })
            } else Scaffold(topBar = chrome) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        failure != null -> Text(failure.orEmpty(), color = MaterialTheme.colorScheme.error)
                        state == null -> CircularProgressIndicator()
                        else -> { Icon(Icons.Outlined.Description, null, Modifier.size(32.dp)); Spacer(Modifier.height(12.dp)); Text(uiContext.getString(R.string.ui_no_files_open), style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(16.dp)); Button(onClick = { tools = WorkspaceToolMode.FILES }) { Text(uiContext.getString(R.string.ui_open_a_file)) } }
                    }
                }
            }
        }
        if(openFiles && state != null) ModalBottomSheet(onDismissRequest = { openFiles = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(uiContext.getString(R.string.ui_open_files_925), style = MaterialTheme.typography.titleLargeEmphasized)
                Text(uiContext.getString(R.string.ui_open_unsaved, (state.paths.size).toString(), (state.paths.count { state.buffers[it]?.dirty?.value == true }).toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.paths, key = { it }) { tab ->
                        Surface(color = if(tab == active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(18.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Surface(onClick = { openFiles = false; select(tab) }, Modifier.weight(1f), color = androidx.compose.ui.graphics.Color.Transparent) {
                                    Row(Modifier.heightIn(min = 66.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Icon(Icons.Outlined.Description, null, Modifier.size(21.dp))
                                        Column(Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(tab.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                                if(state.buffers[tab]?.dirty?.value == true) Surface(Modifier.size(6.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {}
                                            }
                                            Text(tab.substringBeforeLast('/', "."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                                ExpressiveIconButton(onClick = { close(tab) }) { Icon(Icons.Outlined.Close, uiContext.getString(R.string.ui_close_tab, (tab).toString())) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp)); HorizontalDivider()
                TextButton(onClick = { openFiles = false; tools = WorkspaceToolMode.FILES }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(uiContext.getString(R.string.ui_open_another_file))
                }
            }
        }
        tools?.let { mode -> WorkspaceToolsSheet(container, sessionId, projectId, mode, onDismiss = { tools = null }, onOpenFile = ::select, commands = commands) }
    }
}
