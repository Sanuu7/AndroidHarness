package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared bits for chat rendering: the tool icon map, monospace blocks and
 * clickable grep/search output lines.
 *
 * Icons are one family — Material Outlined, tinted by usage, never by colored
 * container circles — so a screen full of tool cards stays calm.
 */
internal fun toolIcon(name: String): ImageVector = when (name) {
    "shell", "shell_background" -> Icons.Outlined.Terminal
    "read_file" -> Icons.Outlined.Article
    "write_file", "edit_file", "multi_edit" -> Icons.Outlined.Edit
    "apply_patch" -> Icons.Outlined.Difference
    "list_dir" -> Icons.Outlined.Folder
    "search_files", "grep" -> Icons.Outlined.Search
    "create_dir" -> Icons.Outlined.CreateNewFolder
    "delete_file" -> Icons.Outlined.Delete
    "move_file" -> Icons.Outlined.DriveFileMove
    "web_fetch", "web_search", "http_request" -> Icons.Outlined.Public
    "todo_write" -> Icons.Outlined.Checklist
    "memory_write" -> Icons.Outlined.EditNote
    "bg_list", "bg_kill" -> Icons.Outlined.Terminal
    else -> Icons.Outlined.Build
}

@Composable
internal fun MonoBlock(text: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(10.dp)
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

/** Shared pretty-print instance — one per call site was allocated before. */
private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

private val linePattern = Regex("^(?s)(.+?):(\\d+): (.+)$")

internal fun prettyArgs(argumentsJson: String): String = runCatching {
    prettyJson.encodeToString(
        kotlinx.serialization.json.JsonElement.serializer(),
        prettyJson.parseToJsonElement(argumentsJson),
    ).take(3000)
}.getOrDefault(argumentsJson.take(3000))

/** Grep/search output with tappable `path:line:` prefixes that open the code viewer. */
@Composable
internal fun ClickableOutput(
    text: String,
    onOpenFile: (path: String, line: Int?) -> Unit,
) {
    // Line splitting + regex matching are memoized; expanded output can hold
    // ~200 rows and the card recomposes on every state emission.
    val lines = remember(text) { text.lines() }
    val matches = remember(lines) { lines.map { linePattern.find(it) } }
    Column {
        lines.take(200).forEachIndexed { index, raw ->
            val match = matches[index]
            if (match != null) {
                val (path, lineNum, content) = match.destructured
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenFile(path, lineNum.toIntOrNull()) }
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        "$path:$lineNum",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        content,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    raw,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (lines.size > 200) {
            Text(
                "[${lines.size - 200} more lines]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
