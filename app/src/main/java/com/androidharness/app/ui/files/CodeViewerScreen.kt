package com.androidharness.app.ui.files

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidharness.app.AppContainer
import com.androidharness.app.ui.common.AppHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Files larger than this are shown truncated (1 MB) — full read would risk OOM. */
private const val MAX_VIEW_CHARS = 1_000_000
/** Beyond this, lines render plain — tokenizing huge files freezes the UI. */
private const val HIGHLIGHT_CHAR_CAP = 300_000

@Composable
fun CodeViewerScreen(
    container: AppContainer,
    path: String,
    initialLine: Int? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val fs by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)
    val relativePath = remember(path) {
        URLDecoder.decode(path, StandardCharsets.UTF_8.toString())
    }
    var editing by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf<String?>(null) }
    var truncated by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(relativePath, fs) {
        val loaded = withContext(Dispatchers.IO) {
            fs?.let { f ->
                runCatching { f.resolve(relativePath).readText() }.getOrNull()
            }
        }
        truncated = loaded != null && loaded.length > MAX_VIEW_CHARS
        content = if (truncated) {
            loaded!!.take(MAX_VIEW_CHARS) + "\n… [truncated: file too large to view fully]"
        } else loaded
        edited = content ?: ""
    }

    LaunchedEffect(content, initialLine) {
        val line = (initialLine ?: 0) - 1
        if (line > 0 && content != null) {
            listState.scrollToItem(line.coerceAtMost(content!!.lines().size - 1))
        }
    }

    // Keyed on the scheme so theme changes re-highlight with fresh colors.
    val scheme = MaterialTheme.colorScheme
    val hs = remember(scheme) {
        CodeHighlighter.Scheme(
            kw = scheme.primary,
            comment = scheme.outline,
            string = scheme.tertiary,
            annotation = scheme.error,
            number = scheme.secondary,
        )
    }
    // Highlighting is O(file size) — never run it inside composition.
    val highlighted by produceState(initialValue = emptyList<androidx.compose.ui.text.AnnotatedString>(), path, content, hs) {
        val text = content ?: run { value = emptyList(); return@produceState }
        value = withContext(Dispatchers.Default) {
            if (text.length > HIGHLIGHT_CHAR_CAP) {
                text.lines().map { androidx.compose.ui.text.AnnotatedString(it) }
            } else {
                CodeHighlighter.highlightSync(path, text, hs)
            }
        }
    }

    Scaffold(
        containerColor = scheme.surface,
        topBar = {
            AppHeader(
                title = relativePath.substringAfterLast('/'),
                subtitle = relativePath,
                onBack = onBack,
                actions = {
                    if (editing) {
                        IconButton(onClick = {
                            val currentFs = fs
                            if (currentFs != null) {
                                scope.launch(Dispatchers.IO) {
                                    runCatching { currentFs.resolve(relativePath).writeText(edited) }
                                        .onSuccess {
                                            content = edited
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                                            }
                                        }.onFailure { e ->
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                }
                            }
                            editing = false
                        }) {
                            Icon(Icons.Outlined.Save, contentDescription = "Save", tint = scheme.primary)
                        }
                    } else {
                        // Editing a truncated view would silently overwrite the
                        // rest of the file — only offer edit for fully read files.
                        if (!truncated) {
                            IconButton(onClick = {
                                edited = content ?: ""
                                editing = true
                            }) {
                                Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = scheme.onSurfaceVariant)
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (content == null) {
            Text("Could not read this file.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        } else if (editing) {
            OutlinedTextField(
                value = edited,
                onValueChange = { edited = it },
                modifier = Modifier.fillMaxSize().padding(padding),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                itemsIndexed(
                    items = highlighted,
                    key = { index, _ -> index },
                ) { index, lineAnnotated ->
                    SelectionContainer {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            // Line-number gutter
                            Box(
                                modifier = Modifier
                                    .width(44.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(end = 8.dp, top = 2.dp).fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                )
                            }
                            Text(
                                text = lineAnnotated,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                ),
                                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
