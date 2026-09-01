package com.androidharness.app.ui.files

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
@Composable
fun CodeEditorScreen(
    container: AppContainer,
    path: String,
    initialLine: Int? = null,
    sessionId: String? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val fs by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)

    // ---- load state ----
    var loadState by remember(path) { mutableStateOf<Load>(Load.Loading) }
    val decoded = remember { mutableStateOf<EditorFileCodec.Decoded?>(null) }
    val diskBaseline = remember { mutableStateOf("") }
    val lastSavedText = remember { mutableStateOf("") }

    LaunchedEffect(path, fs) {
        val node = fs?.let { f -> runCatching { f.resolve(path) }.getOrNull() }
        if (node == null || !node.exists || !node.isFile) {
            loadState = Load.Failed("File not found or workspace unavailable.")
            return@LaunchedEffect
        }
        if (node.length > MAX_EDIT_BYTES) {
            loadState = Load.TooLarge(node.length)
            return@LaunchedEffect
        }
        val bytes = withContext(Dispatchers.IO) {
            runCatching { node.openInputStream()?.use { it.readBytes() } }.getOrNull()
        }
        when {
            bytes == null -> loadState = Load.Failed("Could not read file.")
            else -> {
                val dec = EditorFileCodec.decode(bytes)
                if (dec == null) loadState = Load.Binary(node.length)
                else {
                    decoded.value = dec
                    diskBaseline.value = dec.text
                    lastSavedText.value = dec.text
                    loadState = Load.Ready
                }
            }
        }
    }

    // ---- editor state ----
    var editorRef by remember { mutableStateOf<CodeEditor?>(null) }
    var editing by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var showExitPrompt by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var gotoOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var wordwrapOn by remember { mutableStateOf(false) }

    fun editorText(): String = editorRef?.text?.toString().orEmpty()

    fun save(thenExit: Boolean = false) {
        val dec = decoded.value ?: return
        val ed = editorRef ?: return
        if (!ed.isEditable) return
        val preSave = lastSavedText.value
        val newText = editorText()
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { fs?.resolve(path)?.writeBytes(EditorFileCodec.encode(dec, newText)) }
            }
            saving = false
            result
                .onSuccess {
                    lastSavedText.value = newText
                    dirty = false
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                    // Feed the Files-changed tracker; repository keeps the
                    // earliest baseline even though we resend ours each time.
                    val sid = sessionId
                    if (sid != null) {
                        runCatching {
                            container.sessions.recordFileChange(
                                sessionId = sid,
                                relPath = path,
                                added = Diff.lineCounts(preSave, newText).first.toLong(),
                                removed = Diff.lineCounts(preSave, newText).second.toLong(),
                                existedBefore = true,
                                existsAfter = true,
                                beforeText = diskBaseline.value,
                            )
                        }
                    }
                }
                .onFailure { e ->
                    Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            if (thenExit && result.isSuccess) onBack()
        }
    }

    BackHandler(enabled = dirty && !saving) {
        if (editorText() == diskBaseline.value) {
            dirty = false
            onBack()
        } else {
            showExitPrompt = true
        }
    }

    Scaffold(
        containerColor = scheme.surface,
        topBar = {
            AppHeader(
                title = path.substringAfterLast('/'),
                subtitle = path,
                onBack = {
                    if (dirty && !saving && editorText() != diskBaseline.value) showExitPrompt = true else onBack()
                },
                actions = {
                    IconButton(
                        onClick = { editorRef?.text?.undo() },
                        enabled = editorRef != null && editorRef!!.text.canUndo(),
                    ) { Icon(Icons.Outlined.Undo, contentDescription = "Undo") }
                    IconButton(
                        onClick = { editorRef?.text?.redo() },
                        enabled = editorRef != null && editorRef!!.text.canRedo(),
                    ) { Icon(Icons.Outlined.Redo, contentDescription = "Redo") }
                    IconButton(onClick = {
                        editing = !editing
                        editorRef?.isEditable = editing
                        if (editing) searchOpen = false
                    }) {
                        Icon(
                            if (editing) Icons.Outlined.Check else Icons.Outlined.Edit,
                            contentDescription = if (editing) "Done editing" else "Edit",
                            tint = if (editing) scheme.primary else scheme.onSurfaceVariant,
                        )
                    }
                    if (dirty && !saving) {
                        IconButton(onClick = { save() }) {
                            Icon(Icons.Outlined.Save, contentDescription = "Save", tint = scheme.primary)
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (searchOpen) "Hide find" else "Find…") },
                                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                onClick = {
                                    menuOpen = false
                                    searchOpen = !searchOpen
                                    if (!searchOpen) editorRef?.searcher?.stopSearch()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (wordwrapOn) "Word wrap: on" else "Word wrap: off") },
                                onClick = {
                                    menuOpen = false
                                    wordwrapOn = !wordwrapOn
                                    editorRef?.setWordwrap(wordwrapOn)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Go to line…") },
                                onClick = { menuOpen = false; gotoOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Outlined.Share, null) },
                                onClick = {
                                    menuOpen = false
                                    val f = fs ?: return@DropdownMenuItem
                                    scope.launch {
                                        runCatching {
                                            val node = withContext(Dispatchers.IO) { f.resolve(path) }
                                            FileOps.share(context, node)
                                        }.onFailure { e ->
                                            Toast.makeText(context, e.message ?: "Share failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Open externally") },
                                leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) },
                                onClick = {
                                    menuOpen = false
                                    val f = fs ?: return@DropdownMenuItem
                                    scope.launch {
                                        runCatching {
                                            val node = withContext(Dispatchers.IO) { f.resolve(path) }
                                            FileOps.openWith(context, node)
                                        }.onFailure { e ->
                                            Toast.makeText(context, e.message ?: "No app can open this", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
            )
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
                    detail = "Binary file: it can't be edited as text.",
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
                    detail = "Too large to edit here (over ${formatFileSize(MAX_EDIT_BYTES)}).",
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
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .imePadding()
                            .background(scheme.surface),
                        factory = { ctx ->
                            CodeEditor(ctx).apply {
                                typefaceText = Typeface.MONOSPACE
                                typefaceLineNumber = Typeface.MONOSPACE
                                isHighlightCurrentLine = false
                                props.stickyScroll = false
                                getComponent(EditorAutoCompletion::class.java)?.setEnabled(false)
                                isEditable = false
                                setWordwrap(false)
                                setEditorLanguage(BasicCodeLanguage(path))
                                setText(decoded.value!!.text)
                                applyEditorTheme(this, scheme)
                                // Dirty flag via the event bus, survives setText
                                // swapping out the Content object.
                                subscribeEvent(
                                    io.github.rosemoe.sora.event.ContentChangeEvent::class.java,
                                ) { _, _ -> dirty = true }
                                editorRef = this
                            }
                        },
                        update = { ed ->
                            applyEditorTheme(ed, scheme)
                        },
                    )

                    if (searchOpen) {
                        FindBar(
                            editor = editorRef,
                            editingEnabled = editing,
                            onClosed = { searchOpen = false },
                        )
                    }

                    LaunchedEffect(initialLine, editorRef) {
                        val ed = editorRef ?: return@LaunchedEffect
                        val line = (initialLine ?: 0) - 1
                        if (line > 0 && ed.text.lineCount > 0) {
                            delay(120)
                            val target = line.coerceAtMost(ed.text.lineCount - 1).coerceAtLeast(0)
                            ed.setSelection(target, 0)
                            ed.ensurePositionVisible(target, 0)
                        }
                    }
                }
            }
        }
    }

    if (gotoOpen) GoToLineDialog(editor = editorRef, onDismiss = { gotoOpen = false })

    if (showExitPrompt) {
        AlertDialog(
            onDismissRequest = { showExitPrompt = false },
            title = { Text("Unsaved changes") },
            text = { Text("${path.substringAfterLast('/')} has unsaved changes.") },
            confirmButton = {
                TextButton(onClick = { showExitPrompt = false; save(thenExit = true) }) {
                    Text("Save & exit")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showExitPrompt = false }) { Text("Cancel") }
                    TextButton(onClick = { showExitPrompt = false; dirty = false; onBack() }) {
                        Text("Discard")
                    }
                }
            },
        )
    }
}

/** Maps rich syntax highlight palette onto the editor slots based on current theme mode. */
private fun applyEditorTheme(ed: CodeEditor, scheme: androidx.compose.material3.ColorScheme) {
    val cs = ed.colorScheme
    val isDark = scheme.surface.red * 0.299f + scheme.surface.green * 0.587f + scheme.surface.blue * 0.114f < 0.5f
    fun set(slot: Int, color: androidx.compose.ui.graphics.Color) = cs.setColor(slot, color.toArgb())

    set(EditorColorScheme.WHOLE_BACKGROUND, scheme.surface)
    set(EditorColorScheme.LINE_NUMBER_BACKGROUND, scheme.surfaceContainerLowest)
    set(EditorColorScheme.LINE_NUMBER, scheme.outline.copy(alpha = 0.55f))
    set(EditorColorScheme.LINE_NUMBER_CURRENT, scheme.primary)
    set(EditorColorScheme.TEXT_NORMAL, scheme.onSurface)
    set(EditorColorScheme.SELECTION_INSERT, scheme.primary.copy(alpha = 0.35f))

    if (isDark) {
        // Dark theme rich syntax palette
        set(EditorColorScheme.HTML_TAG, androidx.compose.ui.graphics.Color(0xFF7EE787))       // HTML/XML tags (<html, <div, <meta, <!DOCTYPE) -> Mint Green
        set(EditorColorScheme.IDENTIFIER_VAR, androidx.compose.ui.graphics.Color(0xFF79C0FF)) // HTML attributes / JSON keys (charset, name, content) -> Sky Blue
        set(EditorColorScheme.ATTRIBUTE_NAME, androidx.compose.ui.graphics.Color(0xFFFF7B72)) // Control keywords (import, return, if, etc) -> Coral Red
        set(EditorColorScheme.KEYWORD, androidx.compose.ui.graphics.Color(0xFF79C0FF))        // Core keywords (fun, class, val, var) -> Sky Blue
        set(EditorColorScheme.IDENTIFIER_NAME, androidx.compose.ui.graphics.Color(0xFFFFA657))// Types / Classes -> Warm Orange
        set(EditorColorScheme.FUNCTION_NAME, androidx.compose.ui.graphics.Color(0xFFD2A8FF))  // Functions / methods -> Soft Purple
        set(EditorColorScheme.LITERAL, androidx.compose.ui.graphics.Color(0xFFA5D6FF))        // Strings -> Cyan/Light Blue
        set(EditorColorScheme.ATTRIBUTE_VALUE, androidx.compose.ui.graphics.Color(0xFF7EE787))// Numbers & constants -> Mint Green
        set(EditorColorScheme.COMMENT, androidx.compose.ui.graphics.Color(0xFF8B949E))        // Comments -> Slate Gray
        set(EditorColorScheme.ANNOTATION, androidx.compose.ui.graphics.Color(0xFFFF9E64))     // Annotations -> Amber Orange
        set(EditorColorScheme.OPERATOR, androidx.compose.ui.graphics.Color(0xFFFF7B72))       // Operators -> Coral Red
    } else {
        // Light theme rich syntax palette
        set(EditorColorScheme.HTML_TAG, androidx.compose.ui.graphics.Color(0xFF116329))       // HTML/XML tags (<html, <div, <meta, <!DOCTYPE) -> Dark Forest Green
        set(EditorColorScheme.IDENTIFIER_VAR, androidx.compose.ui.graphics.Color(0xFF0550AE)) // HTML attributes / JSON keys (charset, name, content) -> Royal Blue
        set(EditorColorScheme.ATTRIBUTE_NAME, androidx.compose.ui.graphics.Color(0xFFCF222E)) // Control keywords (import, return, if, etc) -> Deep Crimson Red
        set(EditorColorScheme.KEYWORD, androidx.compose.ui.graphics.Color(0xFF0550AE))        // Core keywords (fun, class, val, var) -> Royal Blue
        set(EditorColorScheme.IDENTIFIER_NAME, androidx.compose.ui.graphics.Color(0xFF953800))// Types / Classes -> Rust Brown
        set(EditorColorScheme.FUNCTION_NAME, androidx.compose.ui.graphics.Color(0xFF8250DF))  // Functions / methods -> Purple
        set(EditorColorScheme.LITERAL, androidx.compose.ui.graphics.Color(0xFF0A3069))        // Strings -> Dark Blue
        set(EditorColorScheme.ATTRIBUTE_VALUE, androidx.compose.ui.graphics.Color(0xFF1A7F37))// Numbers & constants -> Forest Green
        set(EditorColorScheme.COMMENT, androidx.compose.ui.graphics.Color(0xFF6E7781))        // Comments -> Mid Gray
        set(EditorColorScheme.ANNOTATION, androidx.compose.ui.graphics.Color(0xFFB35900))     // Annotations -> Dark Amber
        set(EditorColorScheme.OPERATOR, androidx.compose.ui.graphics.Color(0xFFCF222E))       // Operators -> Deep Crimson Red
    }
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
                Text("Share")
            }
            Button(onClick = onOpenWith) {
                Icon(Icons.Outlined.OpenInNew, null, Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open with…")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Find / replace bar

/**
 * Owns its own query/options state. Re-runs search on debounced typing;
 * matches read back from the searcher with a short settle poll (the search
 * pass runs on a worker thread).
 */
@Composable
private fun FindBar(
    editor: CodeEditor?,
    editingEnabled: Boolean,
    onClosed: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var regexOn by remember { mutableStateOf(false) }
    var patternError by remember { mutableStateOf(false) }
    var matchInfo by remember { mutableStateOf(0 to 0) } // count to current

    Surface(color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Find", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    isError = patternError,
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).height(54.dp),
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
                }) { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Previous match") }
                IconButton(onClick = {
                    val s = editor?.searcher ?: return@IconButton
                    s.gotoNext()
                    matchInfo = matchInfo.first to s.getCurrentMatchedPositionIndex()
                }) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Next match") }
                ToggleChip("Aa", matchCase, "Match case") { matchCase = it }
                ToggleChip(".*", regexOn, "Regex") { regexOn = it }
                IconButton(onClick = {
                    editor?.searcher?.stopSearch()
                    onClosed()
                }) { Icon(Icons.Outlined.Close, contentDescription = "Close find") }
            }
            if (editingEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = replaceWith,
                        onValueChange = { replaceWith = it },
                        placeholder = { Text("Replace with", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f).height(54.dp),
                    )
                    TextButton(onClick = {
                        editor?.searcher?.replaceCurrentMatch(replaceWith)
                    }) { Text("Replace") }
                    TextButton(onClick = {
                        editor?.searcher?.replaceAll(replaceWith)
                    }) { Text("All") }
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
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun GoToLineDialog(editor: CodeEditor?, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to line") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { v -> value = v.filter(Char::isDigit) },
                singleLine = true,
                placeholder = { Text("Line number") },
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
            }) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
