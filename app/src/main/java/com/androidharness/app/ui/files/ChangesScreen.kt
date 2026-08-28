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
    val fs by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)
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
                    ChangeRow(fs = fs, change = change, successColor = colors.success)
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
) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember(change.relPath) { mutableStateOf(false) }

    val fileName = change.relPath.substringAfterLast('/')
    val dirName = change.relPath.substringBeforeLast('/', "")

    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
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
                change.relPath, change.updatedAt, expanded, fs,
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
                    else -> SessionDiffView(current.unified)
                }
            }
        }
    }
}

/** Result payload for one expanded diff computation. */
private data class ChangeDiff(val hasBase: Boolean, val unified: String)

private suspend fun computeSessionDiff(
    fs: com.androidharness.app.workspace.WorkspaceFs?,
    change: SessionFileChangeEntity,
): ChangeDiff {
    val base = when {
        !change.hasBase -> return ChangeDiff(false, "")
        change.isNew || change.baseGzip == null -> ""
        else -> runCatching { gunzipText(change.baseGzip!!) }.getOrDefault("")
    }
    val current = when {
        change.isDeleted -> ""
        fs == null -> return ChangeDiff(true, "")
        else -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val node = fs.resolve(change.relPath)
                if (node.exists && node.isFile && node.length <= 1_000_000) node.readText() else null
            }.getOrNull()
        } ?: return ChangeDiff(true, "")
    }
    val unified = Diff.unified(base, current, change.relPath)
    return ChangeDiff(true, unified)
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

/** Same coloring rules as the chat approval cards' DiffView. */
@Composable
internal fun SessionDiffView(diff: String) {
    val scheme = MaterialTheme.colorScheme
    val addBg = LocalStatusColors.current.success.copy(alpha = 0.14f)
    val delBg = scheme.error.copy(alpha = 0.14f)
    val hunkColor = scheme.primary
    Surface(
        color = scheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val annotated = remember(diff) { annotate(diff.lines(), addBg, delBg, hunkColor) }
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(10.dp)
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

private fun annotate(lines: List<String>, addBg: Color, delBg: Color, hunkColor: Color) =
    buildAnnotatedString {
        lines.forEachIndexed { idx, line ->
            if (idx > 0) append('\n')
            when {
                line.startsWith("+++") || line.startsWith("---") ->
                    withStyle(SpanStyle(color = Color.Gray)) { append(line) }
                line.startsWith("+") -> withStyle(SpanStyle(background = addBg)) { append(line) }
                line.startsWith("-") -> withStyle(SpanStyle(background = delBg)) { append(line) }
                line.startsWith("@") -> withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, color = hunkColor),
                ) { append(line) }
                else -> append(line)
            }
        }
    }
