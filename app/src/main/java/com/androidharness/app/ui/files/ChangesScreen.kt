package com.androidharness.app.ui.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.core.Diff
import com.androidharness.app.data.db.SessionFileChangeEntity
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.common.VisualDiffViewer
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.workspace.normalizeRelPath
import java.util.zip.GZIPInputStream

/**
 * GitHub-style "Files changed" review for one chat session: every file the
 * agent (or an in-app save) touched this conversation, with cumulative "+N −M"
 * counters and tap-to-expand diffs against the session's baseline content.
 *
 * Baselines are pinned when the session first touches each file (gzipped
 * snapshots), so old sessions keep their diffs forever.
 */
@Composable
fun ChangesScreen(
    container: AppContainer,
    sessionId: String,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalStatusColors.current
    val fs by produceState<com.androidharness.app.workspace.WorkspaceFs?>(null, sessionId) {
        val projectId = container.sessions.session(sessionId)?.projectId
        val project = container.workspace.projects.first().firstOrNull { it.id == projectId }
        value = project?.let { container.workspace.fsFor(it) }
    }
    val running by container.runManager.runningSessionIds.collectAsStateWithLifecycle()
    val changes by container.sessions.fileChangesFor(sessionId)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // Rows recorded before path normalization (or via mixed spellings) merge
    // here so a file always appears once with stacked counts.
    val merged = remember(changes) { mergeSessionChanges(changes) }

    val totalAdded = merged.sumOf { it.added }
    val totalRemoved = merged.sumOf { it.removed }

    Scaffold(
        containerColor = scheme.surface,
        topBar = {
            AppHeader(
                title = "Files changed",
                subtitle = "${merged.size} " +
                    (if (merged.size == 1) "file" else "files") +
                    " · this chat",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Commit-summary band like the PR header.
            Surface(
                color = scheme.surfaceContainerLowest,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Text(
                        buildString {
                            append(merged.count { it.isNew }); append(" new · ")
                            append(merged.size); append(" changed")
                        },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    DiffStatText(totalAdded, totalRemoved)
                }
            }

            if (merged.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No files changed in this chat yet.\nEdits made by the agent show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(merged, key = { _, c -> c.relPath }) { _, change ->
                    ChangeRow(fs = fs, change = change, successColor = colors.success,
                        canUndo = running.isEmpty(), onUndo = { preview, section ->
                            container.runManager.undoSelection(sessionId, change, preview.current, preview.exists, section)
                        })
                    HorizontalDivider(
                        color = scheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(
    fs: com.androidharness.app.workspace.WorkspaceFs?,
    change: SessionFileChangeEntity,
    successColor: Color,
    canUndo: Boolean,
    onUndo: suspend (ChangeDiff, Diff.UndoSection?) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember(change.relPath) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var applying by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Pair<ChangeDiff, Diff.UndoSection?>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var showSections by remember { mutableStateOf(false) }
    confirmation?.let { selection ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(if (selection.second == null) "Undo this file?" else "Undo this section?") },
            text = { Text("Restore ${change.relPath} to the shown original content. Other files and chat history stay intact.") },
            confirmButton = { TextButton(onClick = {
                confirmation = null; applying = true; error = null
                scope.launch {
                    try { onUndo(selection.first, selection.second) }
                    catch (e: Exception) { error = e.message ?: "Undo failed" }
                    finally { applying = false; refresh++ }
                }
            }) { Text("Undo") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
    }

    val fileName = change.relPath.substringAfterLast('/')
    val dirName = change.relPath.substringBeforeLast('/', "")

    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .background(
                        when {
                            change.isDeleted -> scheme.error
                            change.isNew -> successColor
                            else -> LocalStatusColors.current.warning
                        },
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (dirName.isNotBlank()) {
                    Text(
                        dirName,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    fileName,
                    style = MaterialTheme.typography.bodyMediumEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            DiffStatText(change.added, change.removed)
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(18.dp)
                    .padding(start = 4.dp),
                tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }

        if (expanded) {
            val diffState = produceState<ChangeDiff?>(
                initialValue = null,
                change.relPath, change.updatedAt, expanded, fs, refresh,
            ) {
                value = computeSessionDiff(fs, change)
            }
            Box(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                val current = diffState.value
                when {
                    current == null -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    !current.hasBase -> Text(
                        "Diff unavailable: the pre-change content was too large to track.",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    else -> Column {
                        SessionDiffView(current.unified)
                        error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (current.current != current.base || current.exists == change.isNew) {
                            TextButton(onClick = { confirmation = current to null }, enabled = canUndo && !applying) {
                                Text(if (applying) "Undoing…" else "Undo file")
                            }
                            if (current.sections.isNotEmpty()) TextButton(onClick = { showSections = !showSections }) {
                                Text(if (showSections) "Hide sections" else "Review ${current.sections.size} sections")
                            }
                            if (showSections) Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                            current.sections.forEachIndexed { index, section ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Section ${index + 1} · ${section.after.count { it == '\n' }} changed lines",
                                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                    TextButton(onClick = { confirmation = current to section }, enabled = canUndo && !applying) { Text("Undo section") }
                                }
                                Text((section.after.ifEmpty { section.before }).take(180), maxLines = 3,
                                    overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                            }
                            current.sectionNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (!canUndo) Text("Pause active tasks before undoing changes.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/** Result payload for one expanded diff computation. */
private data class ChangeDiff(
    val hasBase: Boolean, val unified: String, val base: String = "", val current: String = "",
    val exists: Boolean = false, val sections: List<Diff.UndoSection> = emptyList(), val sectionNote: String? = null,
)

private suspend fun computeSessionDiff(
    fs: com.androidharness.app.workspace.WorkspaceFs?, change: SessionFileChangeEntity,
): ChangeDiff = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    if (!change.hasBase || fs == null) return@withContext ChangeDiff(false, "")
    runCatching {
        val base = if (change.isNew) "" else change.baseGzip?.let { gunzipText(it) }
            ?: return@withContext ChangeDiff(false, "")
        val node = fs.resolve(change.relPath)
        require(!node.exists || (node.isFile && !node.isBinary() && node.length <= 1000000))
        val current = if (node.exists) node.readText() else ""
        val sections = runCatching { Diff.undoSections(base, current) }
        ChangeDiff(true, Diff.unified(base, current, change.relPath), base, current, node.exists,
            sections.getOrDefault(emptyList()), sections.exceptionOrNull()?.message)
    }.getOrElse { ChangeDiff(false, "") }
}

private fun gunzipText(bytes: ByteArray): String =
    GZIPInputStream(bytes.inputStream()).use { stream ->
        stream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
    }

/**
 * Collapses rows that describe the same file under different path spellings
 * ("./a.html" vs "a.html", backslashes) into one row with stacked counts.
 * Newest row decides deleted-ness; newness and baselines carry over from any
 * variant that has them.
 */
internal fun mergeSessionChanges(
    rows: List<SessionFileChangeEntity>,
): List<SessionFileChangeEntity> = rows
    .groupBy { normalizeRelPath(it.relPath) }
    .map { (_, variants) ->
        if (variants.size == 1) return@map variants.first()
        val byRecency = variants.sortedByDescending { it.updatedAt }
        val key = variants.first()
        key.copy(
            relPath = normalizeRelPath(key.relPath),
            added = variants.sumOf { it.added },
            removed = variants.sumOf { it.removed },
            isNew = variants.any { it.isNew },
            isDeleted = byRecency.first().isDeleted,
            baseGzip = variants.firstOrNull { it.baseGzip != null }?.baseGzip,
            hasBase = variants.any { it.hasBase },
            updatedAt = byRecency.first().updatedAt,
        )
    }
    .sortedByDescending { it.updatedAt }

/** Upgraded to interactive, native visual diff viewer with line numbers and copy action. */
@Composable
internal fun SessionDiffView(diff: String) {
    VisualDiffViewer(
        diffText = diff,
        maxHeight = 420.dp,
        showFileHeader = false,
    )
}
