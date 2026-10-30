package com.androidharness.app.ui.files

import com.androidharness.app.R
import android.graphics.Typeface
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.core.Diff
import com.androidharness.app.ui.common.AppHeader
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoFixHigh
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

/** Files above this many bytes are offered only for share/open-with (16 MB). */
private const val MAX_EDIT_BYTES = 16_000_000L

/**
 * Full code editor for workspace files, backed by sora-editor: gutter line
 * numbers, undo/redo, incremental highlighting via [BasicCodeLanguage], and a
 * find bar with match-case + regex toggles plus replace (in edit mode).
 * Saving restores the original BOM/charset/EOL shape. Binary and oversized
 * files degrade to an info card with Share / Open-with instead of pretending
 * to be text.
 *
 * When [sessionId] is set (chat context), manual saves also feed the session's
 * cumulative "Files changed" tracker, using the load-time content as the
 * diff baseline candidate (the repository keeps the earliest baseline).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun CodeEditorScreen(
    container: AppContainer,
    path: String,
    initialLine: Int? = null,
    sessionId: String? = null,
    onBack: () -> Unit,
    navigationGuard: EditorNavigationGuard? = null,
    projectId: String? = null,
    onAskAgent: ((String?, String) -> Unit)? = null,
    suppliedBuffer: EditorBuffer? = null,
    workspaceChrome: (@Composable () -> Unit)? = null,
    actions: EditorActions? = null,
    onQuickOpen: (() -> Unit)? = null,
    onProjectSearch: (() -> Unit)? = null,
    onCommands: (() -> Unit)? = null,
    smart: SmartEditorController? = null,
    onOpenSmartLocation: ((SmartLocation) -> Unit)? = null,
    initialColumn: Int? = null,
    jumpRequest: Long = 0,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val fs by androidx.compose.runtime.key(sessionId, projectId) {
        remember(sessionId, projectId) { container.workspace.forTools(sessionId, projectId) }
            .collectAsStateWithLifecycle(initialValue = null)
    }

    // ---- load state ----
    val draftKey = remember(path, sessionId, projectId, fs?.displayPath) {
        fs?.let { EditorDraftStore.key(sessionId, projectId, it.displayPath, path) }
    }
    var loadState by remember(draftKey) { mutableStateOf<Load>(Load.Loading) }
    val buffers: EditorBufferViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val buffer = remember(draftKey) {
        suppliedBuffer ?: draftKey?.let { buffers.acquire(it) } ?: buffers.buffer
    }
    val diskBaseline = buffer.baseline
    val lastSavedText = buffer.saved
    val decoded = buffer.decoded

    LaunchedEffect(path, fs) {
        if (fs == null) return@LaunchedEffect
        if (buffer.text.value != null && buffer.dirty.value) {
            loadState = Load.Ready
            return@LaunchedEffect
        }
        val key = draftKey ?: return@LaunchedEffect
        try {
            container.editorDrafts.read(key)?.let { draft ->
                buffer.restore(draft)
                loadState = Load.Ready
                return@LaunchedEffect
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {
            loadState = Load.Failed(uiContext.getString(R.string.ui_could_not_recover_the_editor_draft_it))
            return@LaunchedEffect
        }
        val node = withContext(Dispatchers.IO) {
            fs?.let { f -> runCatching { f.resolve(path).takeIf { it.exists && it.isFile } }.getOrNull() }
        }
        if (node == null) {
            loadState = Load.Failed(uiContext.getString(R.string.ui_file_not_found_or_workspace_unavailable))
            return@LaunchedEffect
        }
        val fileLength = withContext(Dispatchers.IO) { node.length }
        if (fileLength > MAX_EDIT_BYTES) {
            loadState = Load.TooLarge(fileLength)
            return@LaunchedEffect
        }
        val bytes = withContext(Dispatchers.IO) {
            runCatching { node.openInputStream()?.use { it.readBytes() } }.getOrNull()
        }
        when {
            bytes == null -> loadState = Load.Failed(uiContext.getString(R.string.ui_could_not_read_file))
            else -> {
                val dec = EditorFileCodec.decode(bytes)
                if (dec == null) loadState = Load.Binary(fileLength)
                else {
                    decoded.value = dec
                    if (buffer.text.value == null || buffer.diskVersion.value != EditorDiskSnapshot(bytes).version) {
                        buffer.document = null
                        buffer.text.value = dec.text
                        diskBaseline.value = dec.text
                        lastSavedText.value = dec.text
                        buffer.diskVersion.value = EditorDiskSnapshot(bytes).version
                        buffer.loaded = true
                    }
                    loadState = Load.Ready
                }
            }
        }
    }

    // ---- editor state ----
    val sourceProject by remember(sessionId, projectId) { container.workspace.projectForTools(sessionId, projectId) }
        .collectAsStateWithLifecycle(initialValue = null)
    var selection by remember(path) { mutableStateOf<SelectedCode?>(null) }
    var selectedLength by remember(path) { mutableIntStateOf(0) }
    var editorRef by remember(draftKey) { mutableStateOf<CodeEditor?>(null) }
    var editRevision by remember(buffer) { mutableIntStateOf(0) }
    var completionRevision by remember(buffer) { mutableIntStateOf(0) }
    var completionAt by remember(buffer) { mutableIntStateOf(0) }
    var editing by buffer.editing
    var dirty by buffer.dirty
    var saving by buffer.saving
    var showExitPrompt by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var gotoOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var smartAction by remember(path) { mutableStateOf<SmartEditorAction?>(null) }
    var languageSupport by remember { mutableStateOf(false) }
    val editorSettings by container.settings.settings.collectAsStateWithLifecycle(initialValue = com.androidharness.app.data.AppSettings())
    val wordwrapOn = editorSettings.editorWordWrap
    val draftStatus by remember(draftKey) { container.editorDrafts.status(draftKey.orEmpty()) }
        .collectAsStateWithLifecycle(initialValue = EditorDraftStatus.IDLE)
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var conflict by remember(buffer) { mutableStateOf<EditorDiskSnapshot?>(null) }
    var conflictThenExit by remember(buffer) { mutableStateOf(false) }
    var compareConflict by remember(buffer) { mutableStateOf(false) }

    fun persistDraft(immediate: Boolean = false) {
        val key = draftKey ?: return
        if (buffer.loaded) container.editorDrafts.submit(key, buffer.draft(), immediate)
    }
    val editorLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(buffer, draftKey, editorLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) persistDraft(immediate = true)
        }
        editorLifecycle.addObserver(observer)
        onDispose {
            editorLifecycle.removeObserver(observer)
            persistDraft(immediate = true)
        }
    }

    fun editorText(): String = editorRef?.text?.toString().orEmpty()
    fun leave() {
        val action = pendingNavigation ?: onBack
        pendingNavigation = null
        action()
    }
    fun requestNavigation(action: () -> Unit) {
        if (saving) return
        if (dirty && editorText() != lastSavedText.value) {
            pendingNavigation = action
            showExitPrompt = true
        } else action()
    }
    androidx.compose.runtime.SideEffect { navigationGuard?.confirm = ::requestNavigation }
    androidx.compose.runtime.DisposableEffect(navigationGuard) {
        onDispose { navigationGuard?.confirm = null }
    }

    fun save(thenExit: Boolean = false, reviewedVersion: String? = null) {
        if (saving) return
        val dec = decoded.value ?: return
        val ed = editorRef ?: return
        val f = fs ?: return
        val expected = reviewedVersion ?: buffer.diskVersion.value ?: return
        val newText = editorText()
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    container.runManager.withIdleWorkspace(f, uiContext.getString(R.string.ui_an_agent_is_working_in_this_workspace)) {
                        saveEditorFile(f, path, expected, EditorFileCodec.encode(dec, newText))
                    }
                }
            }
            saving = false
            var saved = false
            result
                .onSuccess { outcome ->
                    if (outcome is EditorSaveResult.Conflict) {
                        conflict = outcome.current
                        conflictThenExit = thenExit
                        compareConflict = false
                        return@onSuccess
                    }
                    outcome as EditorSaveResult.Saved
                    saved = true
                    conflict = null
                    buffer.diskVersion.value = outcome.snapshot.version
                    buffer.text.value = editorText()
                    lastSavedText.value = newText
                    dirty = buffer.text.value != newText
                    buffer.recovered.value = false
                    persistDraft(immediate = true)
                    Toast.makeText(context, uiContext.getString(R.string.ui_saved), Toast.LENGTH_SHORT).show()
                    // Feed the Files-changed tracker; repository keeps the
                    // earliest baseline even though we resend ours each time.
                    val sid = sessionId
                    if (sid != null) {
                        runCatching {
                            container.sessions.recordFileChange(
                                sessionId = sid,
                                relPath = path,
                                added = Diff.lineCounts(outcome.previous.text.orEmpty(), newText).first.toLong(),
                                removed = Diff.lineCounts(outcome.previous.text.orEmpty(), newText).second.toLong(),
                                existedBefore = outcome.previous.bytes != null,
                                existsAfter = true,
                                beforeText = diskBaseline.value,
                            )
                        }
                    }
                }
                .onFailure { e ->
                    Toast.makeText(context, uiContext.getString(R.string.ui_save_failed, (e.message).toString()), Toast.LENGTH_LONG).show()
                }
            if (thenExit && saved && !dirty) leave()
        }
    }

    val keyboardSave by androidx.compose.runtime.rememberUpdatedState<() -> Unit>({ if (!saving && dirty) save() })
    BackHandler(enabled = com.androidharness.app.ui.LocalWorkspacePaneActive.current && dirty && !saving) { requestNavigation(onBack) }

    androidx.compose.runtime.SideEffect {
        actions?.ready = loadState != Load.Loading && (loadState != Load.Ready || editorRef != null)
        actions?.textAvailable = loadState == Load.Ready && editorRef != null
        actions?.close = ::requestNavigation
        actions?.save = { if (dirty) save() }
        actions?.find = { searchOpen = true }
        actions?.goToLine = { gotoOpen = true }
        actions?.toggleWrap = { container.editorScope.launch { container.settings.setEditorWordWrap(!wordwrapOn) } }
        actions?.outline = { smartAction = SmartEditorAction.OUTLINE }
        actions?.format = { smartAction = SmartEditorAction.FORMAT }
        actions?.definition = { smartAction = SmartEditorAction.DEFINITION }
        actions?.references = { smartAction = SmartEditorAction.REFERENCES }
        actions?.rename = { smartAction = SmartEditorAction.RENAME }
    }
    val undoAvailable = editRevision.let { editorRef?.text?.canUndo() == true }
    val redoAvailable = editRevision.let { editorRef?.text?.canRedo() == true }
    val completionImeHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(completionRevision, editorRef, completionImeHeight) {
        if (completionRevision == 0 || smart == null) return@LaunchedEffect
        val ed = editorRef ?: return@LaunchedEffect
        val at = completionAt
        // Hardware keyboards do not always trigger Sora's IME completion path. Debounce both paths.
        delay(180)
        if (smartAction == null && !saving && ed.isEditable && ed.hasFocus() && !ed.cursor.isSelected && ed.cursor.left == at) {
            ed.ensurePositionVisible(ed.cursor.leftLine, ed.cursor.leftColumn)
            ed.getComponent(EditorAutoCompletion::class.java)?.requireCompletion()
        }
    }
    val controls: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        IconButton(
            onClick = { editorRef?.text?.undo() },
            enabled = undoAvailable,
        ) { Icon(Icons.Outlined.Undo, contentDescription = uiContext.getString(R.string.ui_undo)) }
        IconButton(
            onClick = { editorRef?.text?.redo() },
            enabled = redoAvailable,
        ) { Icon(Icons.Outlined.Redo, contentDescription = uiContext.getString(R.string.ui_redo)) }
        if (workspaceChrome != null) Spacer(Modifier.weight(1f))
        if (smart != null && SmartEditorSupport.outline(path)) IconButton(onClick = { smartAction = SmartEditorAction.OUTLINE }, enabled = editorRef != null && !saving) {
            Icon(Icons.Outlined.AccountTree, contentDescription = uiContext.getString(R.string.ui_code_outline))
        }
        IconButton(onClick = {
            editing = !editing
            editorRef?.isEditable = editing
            if (editing) searchOpen = false
            persistDraft()
        }) {
            Icon(
                if (editing) Icons.Outlined.Check else Icons.Outlined.Edit,
                contentDescription = if (editing) uiContext.getString(R.string.ui_done_editing) else uiContext.getString(R.string.settings_action_edit),
                tint = if (editing) scheme.primary else scheme.onSurfaceVariant,
            )
        }
        if (dirty && !saving) {
            IconButton(onClick = { save() }) {
                Icon(Icons.Outlined.Save, contentDescription = uiContext.getString(R.string.settings_action_save), tint = scheme.primary)
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = uiContext.getString(R.string.ui_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (smart != null) {
                    if (SmartEditorSupport.format(path)) DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_format_document)) }, leadingIcon = { Icon(Icons.Outlined.AutoFixHigh, null) }, enabled = editorRef != null && !saving,
                        onClick = { menuOpen = false; smartAction = SmartEditorAction.FORMAT })
                    if (SmartEditorSupport.navigation(path)) {
                        DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_go_to_definition)) }, enabled = editorRef != null, onClick = { menuOpen = false; smartAction = SmartEditorAction.DEFINITION })
                        DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_find_references)) }, enabled = editorRef != null, onClick = { menuOpen = false; smartAction = SmartEditorAction.REFERENCES })
                    }
                    if (SmartEditorSupport.rename(path)) DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_rename_symbol)) }, enabled = editorRef != null && !saving, onClick = { menuOpen = false; smartAction = SmartEditorAction.RENAME })
                    DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_language_support)) }, onClick = { menuOpen = false; languageSupport = true })
                    androidx.compose.material3.HorizontalDivider()
                }
                onQuickOpen?.let { action -> DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_quick_open)) }, leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) }, onClick = { menuOpen = false; action() }) }
                onProjectSearch?.let { action -> DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_search_project)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, onClick = { menuOpen = false; action() }) }
                onCommands?.let { action -> DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_command_palette)) }, onClick = { menuOpen = false; action() }) }
                if (onQuickOpen != null) androidx.compose.material3.HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(if (searchOpen) uiContext.getString(R.string.ui_hide_find) else uiContext.getString(R.string.ui_find)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    onClick = {
                        menuOpen = false
                        searchOpen = !searchOpen
                        if (!searchOpen) editorRef?.searcher?.stopSearch()
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (wordwrapOn) uiContext.getString(R.string.ui_word_wrap_on) else uiContext.getString(R.string.ui_word_wrap_off)) },
                    onClick = {
                        menuOpen = false
                        val enabled = !wordwrapOn
                        editorRef?.setWordwrap(enabled)
                        container.editorScope.launch { container.settings.setEditorWordWrap(enabled) }
                    },
                )
                DropdownMenuItem(
                    text = { Text(uiContext.getString(R.string.ui_go_to_line)) },
                    onClick = { menuOpen = false; gotoOpen = true },
                )
                DropdownMenuItem(
                    text = { Text(uiContext.getString(R.string.ui_share)) },
                    leadingIcon = { Icon(Icons.Outlined.Share, null) },
                    onClick = {
                        menuOpen = false
                        val f = fs ?: return@DropdownMenuItem
                        scope.launch {
                            runCatching {
                                val node = withContext(Dispatchers.IO) { f.resolve(path) }
                                FileOps.share(context, node)
                            }.onFailure { e ->
                                Toast.makeText(context, e.message ?: uiContext.getString(R.string.ui_share_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text(uiContext.getString(R.string.ui_open_externally_830)) },
                    leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) },
                    onClick = {
                        menuOpen = false
                        val f = fs ?: return@DropdownMenuItem
                        scope.launch {
                            runCatching {
                                val node = withContext(Dispatchers.IO) { f.resolve(path) }
                                FileOps.openWith(context, node)
                            }.onFailure { e ->
                                Toast.makeText(context, e.message ?: uiContext.getString(R.string.ui_no_app_can_open_this), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }
        }
    }
    Scaffold(
        containerColor = scheme.surface,
        topBar = {
            Column {
                if (workspaceChrome != null) {
                    workspaceChrome()
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, content = controls)
                    androidx.compose.material3.HorizontalDivider(color = scheme.outlineVariant)
                } else AppHeader(title = path.substringAfterLast('/'), subtitle = path, onBack = { requestNavigation(onBack) }, actions = controls)
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when (val st = loadState) {
                Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                is Load.Failed -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(st.message, color = scheme.error)
                }

                is Load.Binary -> NotTextView(
                    sizeBytes = st.sizeBytes,
                    detail = uiContext.getString(R.string.ui_binary_file_it_can_t_be_edited),
                    onShare = {
                        val f = fs ?: return@NotTextView
                        scope.launch {
                            runCatching {
                                val node = withContext(Dispatchers.IO) { f.resolve(path) }
                                FileOps.share(context, node)
                            }
                        }
                    },
                    onOpenWith = {
                        val f = fs ?: return@NotTextView
                        scope.launch {
                            runCatching {
                                val node = withContext(Dispatchers.IO) { f.resolve(path) }
                                FileOps.openWith(context, node)
                            }
                        }
                    },
                )

                is Load.TooLarge -> NotTextView(
                    sizeBytes = st.sizeBytes,
                    detail = uiContext.getString(R.string.ui_too_large_to_edit_here_over, (formatFileSize(MAX_EDIT_BYTES)).toString()),
                    onShare = {
                        val f = fs ?: return@NotTextView
                        scope.launch {
                            runCatching {
                                val node = withContext(Dispatchers.IO) { f.resolve(path) }
                                FileOps.share(context, node)
                            }
                        }
                    },
                    onOpenWith = {
                        val f = fs ?: return@NotTextView
                        scope.launch {
                            runCatching {
                                val node = withContext(Dispatchers.IO) { f.resolve(path) }
                                FileOps.openWith(context, node)
                            }
                        }
                    },
                )

                Load.Ready -> Column(Modifier.fillMaxSize()) {
                    if (dirty) Text(
                        when {
                            draftStatus == EditorDraftStatus.FAILED -> uiContext.getString(R.string.ui_draft_recovery_could_not_be_saved_keep)
                            draftStatus == EditorDraftStatus.SAVING -> uiContext.getString(R.string.ui_saving_draft_on_device)
                            buffer.recovered.value -> uiContext.getString(R.string.ui_recovered_unsaved_draft_file_unchanged)
                            else -> uiContext.getString(R.string.ui_draft_saved_on_device_file_unsaved)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (draftStatus == EditorDraftStatus.FAILED) scheme.error else scheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    if (onAskAgent != null && selectedLength > 0) Surface(color = scheme.secondaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (selectedLength > SelectedCode.MAX_CHARS) uiContext.getString(R.string.ui_select_less_code_to_ask_the_agent) else uiContext.getString(R.string.ui_selected_code),
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            TextButton(enabled = selectedLength <= SelectedCode.MAX_CHARS, onClick = {
                                val ed = editorRef ?: return@TextButton
                                selection = SelectedCode.capture(path, ed.text.toString(), ed.cursor.left, ed.cursor.right, dirty)
                                ed.hideSoftInput(); ed.hideEditorWindows()
                            }) { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(uiContext.getString(R.string.ui_ask_agent)) }
                        }
                    }
                    androidx.compose.runtime.key(draftKey) {
                        com.androidharness.app.ui.common.LeftToRightContent {
                            AndroidView(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .imePadding()
                                    .background(scheme.surface),
                                factory = { ctx ->
                                    CodeEditor(ctx).apply {
                                        textDirection = android.view.View.TEXT_DIRECTION_LTR
                                        setOnKeyListener { _, keyCode, event ->
                                            val smartKeyAction = when {
                                                smart == null -> null
                                                keyCode == android.view.KeyEvent.KEYCODE_F12 && SmartEditorSupport.navigation(path) -> if (event.isShiftPressed) SmartEditorAction.REFERENCES else SmartEditorAction.DEFINITION
                                                keyCode == android.view.KeyEvent.KEYCODE_F2 && SmartEditorSupport.rename(path) -> SmartEditorAction.RENAME
                                                event.isCtrlPressed && event.isShiftPressed && keyCode == android.view.KeyEvent.KEYCODE_I && SmartEditorSupport.format(path) -> SmartEditorAction.FORMAT
                                                else -> null
                                            }
                                            if (smartKeyAction != null) {
                                                if (event.action == android.view.KeyEvent.ACTION_UP && !saving) smartAction = smartKeyAction
                                                true
                                            } else if (event.isCtrlPressed && keyCode == android.view.KeyEvent.KEYCODE_S) {
                                                if (event.action == android.view.KeyEvent.ACTION_UP) keyboardSave()
                                                true
                                            } else if (event.isCtrlPressed && keyCode == android.view.KeyEvent.KEYCODE_P && onQuickOpen != null) {
                                                if (event.action == android.view.KeyEvent.ACTION_UP) { if (event.isShiftPressed) onCommands?.invoke() else onQuickOpen.invoke() }
                                                true
                                            } else if (event.isCtrlPressed && event.isShiftPressed && keyCode == android.view.KeyEvent.KEYCODE_F && onProjectSearch != null) {
                                                if (event.action == android.view.KeyEvent.ACTION_UP) onProjectSearch.invoke()
                                                true
                                            } else false
                                        }
                                        typefaceText = Typeface.MONOSPACE
                                        typefaceLineNumber = Typeface.MONOSPACE
                                        isHighlightCurrentLine = false
                                        props.stickyScroll = false
                                        getComponent(EditorAutoCompletion::class.java)?.setEnabled(smart != null && SmartEditorSupport.completion(path))
                                        if (smart != null && SmartEditorSupport.completion(path)) getComponent(EditorAutoCompletion::class.java)?.setAdapter(MaterialCompletionAdapter())
                                        isEditable = editing
                                        setWordwrap(wordwrapOn)
                                        setEditorLanguage(BasicCodeLanguage(path, smart))
                                        setText(buffer.document ?: io.github.rosemoe.sora.text.Content(buffer.text.value ?: decoded.value!!.text), true, null)
                                        buffer.document = text
                                        val left = text.indexer.getCharPosition(buffer.selectionStart.coerceIn(0, text.length))
                                        val right = text.indexer.getCharPosition(buffer.selectionEnd.coerceIn(0, text.length))
                                        setSelectionRegion(left.line, left.column, right.line, right.column, false)
                                        val savedX = buffer.offsetX
                                        val savedY = buffer.offsetY
                                        post {
                                            if (!isReleased && initialLine == null) {
                                                scroller.startScroll(0, 0, savedX.coerceAtMost(scrollMaxX), savedY.coerceAtMost(scrollMaxY), 0)
                                                scroller.setEditorOffsets(); invalidate()
                                            }
                                        }
                                        applyEditorTheme(this, scheme)
                                        // Dirty flag via the event bus, survives setText
                                        // swapping out the Content object.
                                        subscribeEvent(
                                            io.github.rosemoe.sora.event.ContentChangeEvent::class.java,
                                        ) { event, _ ->
                                            editRevision++
                                            buffer.document = text
                                            buffer.text.value = text.toString()
                                            dirty = buffer.text.value != lastSavedText.value
                                            persistDraft()
                                            if (smart != null && smartAction == null && SmartEditorSupport.completion(path) && isEditable && hasFocus() &&
                                                event.action == io.github.rosemoe.sora.event.ContentChangeEvent.ACTION_INSERT &&
                                                !event.isCausedByUndoManager && event.changedText.length in 1..2) {
                                                completionAt = event.changeEnd.index
                                                completionRevision++
                                            }
                                        }
                                        subscribeEvent(io.github.rosemoe.sora.event.SelectionChangeEvent::class.java) { _, _ ->
                                            selectedLength = cursor.right - cursor.left
                                            buffer.selectionStart = cursor.left
                                            buffer.selectionEnd = cursor.right
                                        }
                                        subscribeEvent(io.github.rosemoe.sora.event.ScrollEvent::class.java) { _, _ ->
                                            buffer.offsetX = offsetX; buffer.offsetY = offsetY
                                        }
                                        editorRef = this
                                    }
                                },
                                onRelease = { ed ->
                                    buffer.document = ed.text
                                    buffer.selectionStart = ed.cursor.left; buffer.selectionEnd = ed.cursor.right
                                    buffer.offsetX = ed.offsetX; buffer.offsetY = ed.offsetY
                                    ed.release()
                                },
                                update = { ed ->
                                    applyEditorTheme(ed, scheme)
                                    if (ed.isWordwrap != wordwrapOn) ed.setWordwrap(wordwrapOn)
                                },
                            )
                        }
                    }

                    if (searchOpen) {
                        FindBar(
                            editor = editorRef,
                            editingEnabled = editing,
                            matchCase = editorSettings.editorMatchCase,
                            regexOn = editorSettings.editorRegexSearch,
                            onMatchCaseChanged = { enabled ->
                                container.editorScope.launch { container.settings.setEditorSearchOptions(matchCase = enabled) }
                            },
                            onRegexChanged = { enabled ->
                                container.editorScope.launch { container.settings.setEditorSearchOptions(regex = enabled) }
                            },
                            onClosed = { searchOpen = false },
                        )
                    }

                    LaunchedEffect(initialLine, initialColumn, jumpRequest, editorRef) {
                        val ed = editorRef ?: return@LaunchedEffect
                        val line = (initialLine ?: 0) - 1
                        if (line >= 0 && ed.text.lineCount > 0) {
                            delay(120)
                            val target = line.coerceAtMost(ed.text.lineCount - 1).coerceAtLeast(0)
                            val column = ((initialColumn ?: 1) - 1).coerceIn(0, ed.text.getColumnCount(target))
                            ed.setSelection(target, column)
                            ed.ensurePositionVisible(target, column)
                        }
                    }
                }
            }
        }
    }

    selection?.let { captured ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { selection = null },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(uiContext.getString(R.string.ui_ask_about_selected_code), style = MaterialTheme.typography.titleLarge)
                Text(captured.location, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = scheme.surfaceContainer) {
                    Text(captured.text.take(700) + if (captured.text.length > 700) "\n…" else "",
                        fontFamily = com.androidharness.app.ui.theme.HarnessMono, style = MaterialTheme.typography.bodySmall,
                        maxLines = 7, modifier = Modifier.padding(12.dp))
                }
                Text(uiContext.getString(R.string.ui_choose_an_action_then_review_and_send), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                CodeAction.entries.forEach { action ->
                    Surface(onClick = {
                        selection = null
                        requestNavigation { onAskAgent?.invoke(sourceProject?.id, captured.prompt(action)) }
                    }, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = scheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(action.label, style = MaterialTheme.typography.titleSmall)
                            Text(action.description, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (gotoOpen) GoToLineDialog(editor = editorRef, onDismiss = { gotoOpen = false })
    if (smart != null && editorRef != null) smartAction?.let { action ->
        androidx.compose.runtime.key(action, path) {
            SmartEditorSheet(action, path, editorRef!!, buffer, smart, onDismiss = { smartAction = null }, onOpenLocation = onOpenSmartLocation)
        }
    }
    if (languageSupport) AlertDialog(onDismissRequest = { languageSupport = false }, title = { Text(uiContext.getString(R.string.ui_language_support)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiContext.getString(R.string.ui_javascript_typescript_autocomplete_definitions_references_rename))
            Text(uiContext.getString(R.string.ui_html_css_scss_less_json_autocomplete_outline))
            Text(uiContext.getString(R.string.ui_markdown_outline_and_formatting_yaml_formatting))
            Text(uiContext.getString(R.string.ui_other_languages_keep_syntax_highlighting_and_the))
            Text(uiContext.getString(R.string.ui_formatting_reads_json_prettier_settings_javascript_configs))
        } }, confirmButton = { TextButton(onClick = { languageSupport = false }) { Text(uiContext.getString(R.string.settings_action_got_it)) } })

    conflict?.let { reviewed ->
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text(if (compareConflict) uiContext.getString(R.string.ui_compare_changes) else uiContext.getString(R.string.ui_file_changed_on_disk)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(uiContext.getString(R.string.ui_the_saved_file_changed_after_you_opened))
                    if (compareConflict) {
                        Text(uiContext.getString(R.string.ui_current_saved_file), style = MaterialTheme.typography.titleSmall)
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(reviewed.text?.take(12000) ?: if (reviewed.bytes == null) uiContext.getString(R.string.ui_file_was_deleted) else uiContext.getString(R.string.ui_file_is_no_longer_text),
                                fontFamily = com.androidharness.app.ui.theme.HarnessMono, style = MaterialTheme.typography.bodySmall)
                        }
                        androidx.compose.material3.HorizontalDivider()
                        Text(uiContext.getString(R.string.ui_your_edits), style = MaterialTheme.typography.titleSmall)
                        Text(editorText().take(12000), fontFamily = com.androidharness.app.ui.theme.HarnessMono, style = MaterialTheme.typography.bodySmall)
                        if ((reviewed.text?.length ?: 0) > 12000 || editorText().length > 12000) Text(uiContext.getString(R.string.ui_preview_shortened_to_12_000_characters_per))
                    } else TextButton(onClick = { compareConflict = true }) { Text(uiContext.getString(R.string.ui_compare)) }
                    if (reviewed.text != null) TextButton(onClick = {
                        val text = requireNotNull(reviewed.text)
                        decoded.value = EditorFileCodec.decode(requireNotNull(reviewed.bytes))
                        editorRef?.setText(text)
                        buffer.text.value = text
                        lastSavedText.value = text
                        buffer.diskVersion.value = reviewed.version
                        dirty = false
                        buffer.recovered.value = false
                        persistDraft(immediate = true)
                        conflict = null
                        pendingNavigation = null
                    }) { Text(uiContext.getString(R.string.ui_reload_file)) }
                }
            },
            confirmButton = { TextButton(enabled = !saving, onClick = { save(conflictThenExit, reviewed.version) }) { Text(uiContext.getString(R.string.ui_keep_my_edits)) } },
            dismissButton = { TextButton(onClick = { conflict = null; pendingNavigation = null }) { Text(uiContext.getString(R.string.settings_action_cancel)) } },
        )
    }
    if (showExitPrompt) {
        AlertDialog(
            onDismissRequest = { showExitPrompt = false; pendingNavigation = null },
            title = { Text(uiContext.getString(R.string.ui_unsaved_changes)) },
            text = { Text(uiContext.getString(R.string.ui_has_unsaved_changes, (path.substringAfterLast('/')).toString())) },
            confirmButton = {
                TextButton(onClick = { showExitPrompt = false; save(thenExit = true) }) {
                    Text(uiContext.getString(R.string.ui_save_exit))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showExitPrompt = false; pendingNavigation = null }) { Text(uiContext.getString(R.string.settings_action_cancel)) }
                    TextButton(onClick = {
                        showExitPrompt = false
                        editorRef?.setText(lastSavedText.value)
                        buffer.text.value = lastSavedText.value
                        dirty = false
                        buffer.recovered.value = false
                        persistDraft(immediate = true)
                        leave()
                    }) {
                        Text(uiContext.getString(R.string.settings_action_discard))
                    }
                }
            },
        )
    }
}

/** Maps rich syntax highlight palette onto the editor slots based on current theme mode. */
private fun applyEditorTheme(ed: CodeEditor, scheme: androidx.compose.material3.ColorScheme) {
    val cs = ed.colorScheme
    val isDark = CodeTokenColors.isDarkSurface(scheme.surface)
    fun set(slot: Int, color: androidx.compose.ui.graphics.Color) = cs.setColor(slot, color.toArgb())
    fun token(type: TokenType) = CodeTokenColors.of(type, isDark, scheme.onSurface)

    set(EditorColorScheme.WHOLE_BACKGROUND, scheme.surface)
    set(EditorColorScheme.LINE_NUMBER_BACKGROUND, scheme.surfaceContainerLowest)
    set(EditorColorScheme.LINE_NUMBER, scheme.outline.copy(alpha = 0.55f))
    set(EditorColorScheme.LINE_NUMBER_CURRENT, scheme.primary)
    set(EditorColorScheme.TEXT_NORMAL, scheme.onSurface)
    set(EditorColorScheme.SELECTION_INSERT, scheme.primary.copy(alpha = 0.35f))
    set(EditorColorScheme.COMPLETION_WND_BACKGROUND, scheme.surfaceContainerHigh)
    set(EditorColorScheme.COMPLETION_WND_CORNER, scheme.outlineVariant)
    set(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, scheme.onSurface)
    set(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, scheme.onSurfaceVariant)
    set(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, scheme.secondaryContainer)

    // Colors come from the palette shared with chat code blocks, so the two
    // render the same snippet identically.
    set(EditorColorScheme.HTML_TAG, token(TokenType.HTML_TAG))
    set(EditorColorScheme.IDENTIFIER_VAR, token(TokenType.ATTRIBUTE_NAME))
    set(EditorColorScheme.ATTRIBUTE_NAME, token(TokenType.KEYWORD_CONTROL))
    set(EditorColorScheme.KEYWORD, token(TokenType.KEYWORD))
    set(EditorColorScheme.IDENTIFIER_NAME, token(TokenType.TYPE_NAME))
    set(EditorColorScheme.FUNCTION_NAME, token(TokenType.FUNCTION_NAME))
    set(EditorColorScheme.LITERAL, token(TokenType.STRING))
    set(EditorColorScheme.ATTRIBUTE_VALUE, token(TokenType.NUMBER))
    set(EditorColorScheme.COMMENT, token(TokenType.COMMENT))
    set(EditorColorScheme.ANNOTATION, token(TokenType.ANNOTATION))
    set(EditorColorScheme.OPERATOR, token(TokenType.OPERATOR))
}

// ---------------------------------------------------------------------------
// Non-text outcomes

private sealed interface Load {
    data object Loading : Load
    data object Ready : Load
    data class Failed(val message: String) : Load
    data class Binary(val sizeBytes: Long) : Load
    data class TooLarge(val sizeBytes: Long) : Load
}

@Composable
private fun NotTextView(
    sizeBytes: Long,
    detail: String,
    onShare: () -> Unit,
    onOpenWith: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.OpenInNew, null, Modifier.size(40.dp), tint = scheme.onSurfaceVariant.copy(alpha = 0.6f))
        Spacer(Modifier.height(14.dp))
        Text(formatFileSize(sizeBytes), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onShare) {
                Icon(Icons.Outlined.Share, null, Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(uiContext.getString(R.string.ui_share))
            }
            Button(onClick = onOpenWith) {
                Icon(Icons.Outlined.OpenInNew, null, Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(uiContext.getString(R.string.ui_open_with))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Find / replace bar

/**
 * Owns its query state and uses saved matching preferences. Re-runs search on debounced typing;
 * matches read back from the searcher with a short settle poll (the search
 * pass runs on a worker thread).
 */
@Composable
private fun FindBar(
    editor: CodeEditor?,
    editingEnabled: Boolean,
    matchCase: Boolean,
    regexOn: Boolean,
    onMatchCaseChanged: (Boolean) -> Unit,
    onRegexChanged: (Boolean) -> Unit,
    onClosed: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }
    var patternError by remember { mutableStateOf(false) }
    var matchInfo by remember { mutableStateOf(0 to 0) } // count to current

    Surface(color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(uiContext.getString(R.string.ui_find_861), style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    isError = patternError,
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                )
                Text(
                    if (matchInfo.first == 0) "-" else "${matchInfo.second + 1}/${matchInfo.first}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                IconButton(onClick = {
                    val s = editor?.searcher ?: return@IconButton
                    s.gotoPrevious()
                    matchInfo = matchInfo.first to s.getCurrentMatchedPositionIndex()
                }) { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = uiContext.getString(R.string.ui_previous_match)) }
                IconButton(onClick = {
                    val s = editor?.searcher ?: return@IconButton
                    s.gotoNext()
                    matchInfo = matchInfo.first to s.getCurrentMatchedPositionIndex()
                }) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = uiContext.getString(R.string.ui_next_match)) }
                ToggleChip(uiContext.getString(R.string.ui_aa), matchCase, uiContext.getString(R.string.ui_match_case), onMatchCaseChanged)
                ToggleChip(".*", regexOn, uiContext.getString(R.string.ui_regex), onRegexChanged)
                IconButton(onClick = {
                    editor?.searcher?.stopSearch()
                    onClosed()
                }) { Icon(Icons.Outlined.Close, contentDescription = uiContext.getString(R.string.ui_close_find)) }
            }
            if (editingEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = replaceWith,
                        onValueChange = { replaceWith = it },
                        placeholder = { Text(uiContext.getString(R.string.ui_replace_with), style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                    )
                    TextButton(onClick = {
                        editor?.searcher?.replaceCurrentMatch(replaceWith)
                    }) { Text(uiContext.getString(R.string.ui_replace)) }
                    TextButton(onClick = {
                        editor?.searcher?.replaceAll(replaceWith)
                    }) { Text(uiContext.getString(R.string.settings_local_models_filter_all)) }
                }
            }
        }
    }

    LaunchedEffect(editor, query, matchCase, regexOn) {
        val s = editor?.searcher ?: return@LaunchedEffect
        delay(250)
        if (query.isEmpty()) {
            s.stopSearch()
            matchInfo = 0 to 0
            patternError = false
            return@LaunchedEffect
        }
        try {
            s.search(
                query,
                EditorSearcher.SearchOptions(
                    if (regexOn) EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION
                    else EditorSearcher.SearchOptions.TYPE_NORMAL,
                    !matchCase,
                ),
            )
            patternError = false
            var settled = -1
            repeat(15) {
                delay(60)
                val c = s.getMatchedPositionCount()
                if (c == settled && c >= 0) {
                    matchInfo = c to s.getCurrentMatchedPositionIndex()
                    return@LaunchedEffect
                }
                settled = c
            }
            matchInfo = settled to s.getCurrentMatchedPositionIndex()
        } catch (_: Exception) {
            patternError = true
            matchInfo = 0 to 0
        }
    }
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, description: String, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = Modifier.padding(start = 4.dp).semantics { contentDescription = description },
    )
}

@Composable
private fun GoToLineDialog(editor: CodeEditor?, onDismiss: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiContext.getString(R.string.ui_go_to_line_870)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { v -> value = v.filter(Char::isDigit) },
                singleLine = true,
                placeholder = { Text(uiContext.getString(R.string.ui_line_number)) },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val n = value.toIntOrNull()
                if (n != null && editor != null && editor.text.lineCount > 0) {
                    val line = (n - 1).coerceIn(0, editor.text.lineCount - 1)
                    editor.setSelection(line, 0)
                    editor.ensurePositionVisible(line, 0)
                }
                onDismiss()
            }) { Text(uiContext.getString(R.string.ui_go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiContext.getString(R.string.settings_action_cancel)) } },
    )
}
