package com.androidharness.app.ui.files

import com.androidharness.app.R
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.common.ExpressiveIconButton
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import com.androidharness.app.ui.common.Button

internal enum class SmartEditorAction(val title: String, val operation: String) {
    OUTLINE("Code outline", "outline"), DEFINITION("Go to Definition", "definition"),
    REFERENCES("Find References", "references"), RENAME("Rename Symbol", "rename"), FORMAT("Format Document", "format"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SmartEditorSheet(action: SmartEditorAction, path: String, editor: CodeEditor,
    buffer: EditorBuffer, controller: SmartEditorController, onDismiss: () -> Unit,
    onOpenLocation: ((SmartLocation) -> Unit)?) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = remember { editor.text.toString() }
    val offset = remember { editor.cursor.left }
    var loading by remember { mutableStateOf(action != SmartEditorAction.RENAME) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<SmartAnswer?>(null) }
    var rename by remember { mutableStateOf<SmartRenamePlan?>(null) }
    var patches by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var applied by remember { mutableStateOf(false) }
    fun current() = !buffer.saving.value && editor.text.toString() == source
    suspend fun load(newName: String? = null) {
        loading = true; error = null
        try {
            check(current()) { uiContext.getString(R.string.ui_the_document_changed_close_this_sheet_and) }
            val result = controller.request(action.operation, path, source, offset, newName)
            check(current()) { uiContext.getString(R.string.ui_the_document_changed_close_this_sheet_and) }
            if (action == SmartEditorAction.FORMAT) {
                val formatted = result.result.jsonPrimitive.content
                if (formatted != source) {
                    val change = minimalSmartEdit(source, formatted)
                    editor.isEditable = true; buffer.editing.value = true
                    editor.text.beginBatchEdit()
                    try { editor.text.replace(change.first, change.second, change.third) } finally { editor.text.endBatchEdit() }
                    val pos = editor.text.indexer.getCharPosition(offset.coerceAtMost(formatted.length))
                    editor.setSelection(pos.line, pos.column)
                }
                Toast.makeText(context, if(formatted == source) uiContext.getString(R.string.ui_already_formatted) else uiContext.getString(R.string.ui_document_formatted_undo_available), Toast.LENGTH_SHORT).show()
                onDismiss()
            } else if (action == SmartEditorAction.RENAME) {
                val edits = result.result.jsonArray.map { item -> item.jsonObject.let {
                    SmartTextEdit(it.getValue("path").jsonPrimitive.content, it.getValue("start").jsonPrimitive.int,
                        it.getValue("end").jsonPrimitive.int, it.getValue("text").jsonPrimitive.content)
                } }
                rename = SmartRenamePlan(result.snapshot, edits)
                check(rename!!.changes.isNotEmpty()) { uiContext.getString(R.string.ui_the_new_name_produces_no_changes) }
                patches = rename!!.changes.mapValues { (file, text) ->
                    com.androidharness.app.core.Diff.unified(result.snapshot.sources.getValue(file).text, text, file)
                }
            }
            answer = result
        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: uiContext.getString(R.string.ui_the_language_action_could_not_finish) }
        finally { loading = false }
    }
    LaunchedEffect(Unit) {
        editor.hideEditorWindows(); editor.hideSoftInput()
        if (action != SmartEditorAction.RENAME) load()
    }
    ModalBottomSheet(onDismissRequest = { if (!loading || action != SmartEditorAction.RENAME) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).imePadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(action.title, style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ExpressiveIconButton(onClick = onDismiss, enabled = !loading || action != SmartEditorAction.RENAME) { Icon(Icons.Outlined.Close, uiContext.getString(R.string.ui_close_code_tools)) }
            }
            Spacer(Modifier.height(12.dp))
            if (action == SmartEditorAction.OUTLINE) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                label = { Text(uiContext.getString(R.string.ui_find_symbol)) }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(18.dp))
            if (action == SmartEditorAction.RENAME && !applied) {
                OutlinedTextField(name, { name = it; rename = null; error = null }, Modifier.fillMaxWidth(),
                    label = { Text(uiContext.getString(R.string.ui_new_symbol_name)) }, singleLine = true, enabled = !loading, shape = RoundedCornerShape(18.dp))
                Text(uiContext.getString(R.string.ui_review_every_affected_file_changes_open_as),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 10.dp))
                if(rename == null) Button(onClick = { scope.launch { load(name.trim()) } }, enabled = name.isNotBlank() && !loading, modifier = Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.ui_review_rename)) }
            }
            if (loading) Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp)); Text(if(action == SmartEditorAction.FORMAT) uiContext.getString(R.string.ui_formatting_draft) else uiContext.getString(R.string.ui_reading_project_symbols))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp)) }
            if (applied) {
                val count = rename?.changes?.size ?: 0
                Text(uiContext.getString(R.string.ui_renamed_in_review_the_open_drafts_and, (count).toString(), (if(count == 1) "file" else "files").toString()), modifier = Modifier.padding(vertical = 16.dp))
                Button(onClick = onDismiss, Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.ui_back_to_editor)) }
            } else if (!loading) {
                val result = answer
                if (action == SmartEditorAction.OUTLINE && result != null) {
                    val symbols = result.result.jsonArray.map { item -> item.jsonObject.let {
                        SmartSymbol(it.getValue("name").jsonPrimitive.content, it.getValue("kind").jsonPrimitive.content,
                            it.getValue("start").jsonPrimitive.int, it.getValue("end").jsonPrimitive.int,
                            it.getValue("line").jsonPrimitive.int, it.getValue("depth").jsonPrimitive.int)
                    } }.filter { query.isBlank() || it.name.contains(query, true) }
                    Text("${symbols.size} ${if(symbols.size == 1) "symbol" else "symbols"}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 12.dp))
                    if(symbols.isEmpty()) Text(if(query.isBlank()) uiContext.getString(R.string.ui_no_symbols_in_this_document) else uiContext.getString(R.string.ui_no_matching_symbols), style = MaterialTheme.typography.bodyMedium)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(symbols, key = { index, symbol -> "$index:${symbol.start}" }) { _, symbol ->
                            SmartResultRow(symbol.name, "${symbolKind(symbol.kind)} · line ${symbol.line}", depth = symbol.depth) {
                                if(current()) { val pos = editor.text.indexer.getCharPosition(symbol.start); editor.setSelection(pos.line, pos.column); editor.ensurePositionVisible(pos.line, pos.column); onDismiss() }
                                else error = uiContext.getString(R.string.ui_the_document_changed_reopen_the_outline)
                            }
                        }
                    }
                } else if ((action == SmartEditorAction.DEFINITION || action == SmartEditorAction.REFERENCES) && result != null) {
                    val locations = result.result.jsonArray.map { item -> item.jsonObject.let {
                        SmartLocation(it.getValue("path").jsonPrimitive.content, it.getValue("start").jsonPrimitive.int,
                            it.getValue("line").jsonPrimitive.int, it.getValue("column").jsonPrimitive.int, it.getValue("preview").jsonPrimitive.content)
                    } }
                    Text(if(locations.isEmpty()) uiContext.getString(R.string.ui_no_project_locations_found_place_the_cursor) else "${locations.size} ${if(locations.size == 1) "location" else "locations"}",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
                    Text(result.snapshot.notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(locations, key = { index, item -> "$index:${item.path}:${item.start}" }) { _, item ->
                            SmartResultRow("${item.path} · ${item.line}:${item.column}", item.preview) {
                                if (!current()) error = uiContext.getString(R.string.ui_the_document_changed_close_this_sheet_and)
                                else if (item.path == path) { val pos = editor.text.indexer.getCharPosition(item.start); editor.setSelection(pos.line, pos.column); editor.ensurePositionVisible(pos.line, pos.column); onDismiss() }
                                else { onDismiss(); onOpenLocation?.invoke(item) }
                            }
                        }
                    }
                } else if (action == SmartEditorAction.RENAME && rename != null) {
                    val plan = rename!!
                    Text(uiContext.getString(R.string.ui_in, (plan.edits.size).toString(), (if(plan.edits.size == 1) "reference" else "references").toString(), (plan.changes.size).toString(), (if(plan.changes.size == 1) "file" else "files").toString()), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 12.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(plan.changes.entries.toList(), key = { _, entry -> entry.key }) { _, entry ->
                            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(entry.key, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(8.dp))
                                    val patch = patches[entry.key].orEmpty()
                                    com.androidharness.app.ui.common.VisualDiffViewer(patch, filePath = entry.key, maxHeight = 300.dp, showFileHeader = false)
                                }
                            }
                        }
                    }
                    Button(onClick = { scope.launch {
                        loading = true; error = null
                        try { check(current()) { uiContext.getString(R.string.ui_the_document_changed_try_again) }; controller.applyRename(plan); applied = true }
                        catch(e: Exception) { if(e is CancellationException) throw e; error = e.message ?: uiContext.getString(R.string.ui_could_not_apply_rename) }
                        finally { loading = false }
                    } }, enabled = !loading && plan.changes.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text(uiContext.getString(R.string.ui_rename_in, (plan.changes.size).toString(), (if(plan.changes.size == 1) "file" else "files").toString())) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SmartResultRow(title: String, detail: String, depth: Int = 0, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = (12 + depth.coerceAtMost(3) * 12).dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Code, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun symbolKind(kind: String): String = when(kind) {
    "1" -> "File"; "2" -> "Module"; "3" -> "Namespace"; "4" -> "Package"; "5" -> "Class"; "6" -> "Method"
    "7" -> "Property"; "8" -> "Field"; "9" -> "Constructor"; "10" -> "Enum"; "11" -> "Interface"; "12" -> "Function"
    "13" -> "Variable"; "14" -> "Constant"; "15" -> "String"; "16" -> "Number"; "17" -> "Boolean"; "18" -> "Array"
    "19" -> "Object"; "20" -> "Key"; "21" -> "Null"; "22" -> "Enum member"; "23" -> "Struct"; "24" -> "Event"
    "25" -> "Operator"; "26" -> "Type parameter"; else -> kind.replaceFirstChar { it.uppercase() }
}

internal fun minimalSmartEdit(before: String, after: String): Triple<Int, Int, String> {
    var start = 0
    while (start < before.length && start < after.length && before[start] == after[start]) start++
    var oldEnd = before.length; var newEnd = after.length
    while (oldEnd > start && newEnd > start && before[oldEnd - 1] == after[newEnd - 1]) { oldEnd--; newEnd-- }
    return Triple(start, oldEnd, after.substring(start, newEnd))
}
