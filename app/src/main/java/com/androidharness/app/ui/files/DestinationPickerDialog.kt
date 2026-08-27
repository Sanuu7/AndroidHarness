package com.androidharness.app.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.workspace.FsNode
import com.androidharness.app.workspace.WorkspaceFs

/**
 * Mini directory browser for Copy-to / Move-to targets. Browses workspace
 * directories only; the [mustAvoidSubtreeOf] node and everything under it is
 * unselectable so folders can't be dropped into themselves.
 */
@Composable
internal fun DestinationPickerDialog(
    fs: WorkspaceFs?,
    startPath: String,
    mustAvoidSubtreeOf: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onPick: (FsNode) -> Unit,
) {
    val forbiddenBase = remember(mustAvoidSubtreeOf) { mustAvoidSubtreeOf.trim('/') }
    var dir by remember { mutableStateOf(if (isInsideForbidden(startPath, forbiddenBase)) "." else startPath) }
    var dirs by remember { mutableStateOf<List<FsNode>>(emptyList()) }

    LaunchedEffect(fs, dir) {
        val f = fs ?: return@LaunchedEffect
        dirs = runCatching {
            f.resolve(dir).list()
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a folder") },
        text = {
            Column {
                Text(
                    if (dir == ".") "Workspace root" else dir,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.fillMaxWidth().height(280.dp)) {
                    if (dir != ".") {
                        item(key = "__up__") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { dir = parentDirOf(dir) }
                                    .padding(vertical = 11.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text("..", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    items(dirs, key = { it.relPath }) { child ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { dir = child.relPath }
                                .padding(vertical = 11.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                child.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (dirs.isEmpty()) {
                        item {
                            Text(
                                "No subfolders here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val selectable = fs != null && !isInsideForbidden(dir, forbiddenBase)
            TextButton(
                enabled = selectable,
                onClick = {
                    onDismiss()
                    fs?.let { f -> runCatching { onPick(f.resolve(dir)) } }
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun isInsideForbidden(candidate: String, forbiddenBase: String): Boolean {
    val c = candidate.trim('/').trim('.')
    if (c.isEmpty()) return false
    return c == forbiddenBase || c.startsWith("$forbiddenBase/")
}

private fun parentDirOf(path: String): String {
    val segments = path.trim('/').split('/').filter { it.isNotBlank() }
    return if (segments.size <= 1) "." else segments.dropLast(1).joinToString("/")
}
