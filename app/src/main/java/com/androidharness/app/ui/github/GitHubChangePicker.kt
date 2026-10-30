package com.androidharness.app.ui.github

import com.androidharness.app.R
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.github.GitChange
import com.androidharness.app.github.GitChangeTree
import com.androidharness.app.github.toggleGitPaths
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun GitHubChangePicker(
    changes: List<GitChange>,
    selected: Set<String>,
    directory: String,
    onDirectoryChange: (String) -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    enabled: Boolean,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val tree = remember(changes) { GitChangeTree(changes) }
    val entries = remember(tree, directory) { tree.entries(directory) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (directory.isNotEmpty()) {
                IconButton(onClick = { onDirectoryChange(directory.substringBeforeLast('/', "")) }, enabled = enabled) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiContext.getString(R.string.ui_up_one_folder))
                }
            }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onDirectoryChange("") }, enabled = enabled && directory.isNotEmpty()) {
                    Text(uiContext.getString(R.string.ui_workspace))
                }
                var path = ""
                for (part in directory.split('/').filter { it.isNotEmpty() }) {
                    path = if (path.isEmpty()) part else "$path/$part"
                    val destination = path
                    Text("/", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onDirectoryChange(destination) }, enabled = enabled && destination != directory) {
                        Text(part, maxLines = 1)
                    }
                }
            }
        }
        Text(uiContext.getString(R.string.ui_tap_folders_to_open_hold_to_select), style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { onSelectionChange(toggleGitPaths(selected, tree.paths)) }, enabled = enabled) {
            Text(if (selected.containsAll(tree.paths)) uiContext.getString(R.string.ui_deselect_all_files) else uiContext.getString(R.string.ui_select_all_files))
        }
        // A bounded list keeps navigation and publish controls reachable on a phone.
        key(directory) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                items(entries, key = { "${it.isDirectory}:${it.path}" }) { entry ->
                    val count = entry.selectedCount(selected)
                    val selection = when (count) {
                        0 -> ToggleableState.Off
                        entry.paths.size -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    }
                    fun toggle() = onSelectionChange(toggleGitPaths(selected, entry.paths))
                    Row(Modifier.fillMaxWidth().combinedClickable(
                        enabled = enabled,
                        onClick = { if (entry.isDirectory) onDirectoryChange(entry.path) else toggle() },
                        onLongClick = { toggle() },
                        onLongClickLabel = if (selection == ToggleableState.On) uiContext.getString(R.string.ui_deselect) else uiContext.getString(R.string.settings_provider_select),
                    ), verticalAlignment = Alignment.CenterVertically) {
                        TriStateCheckbox(state = selection, onClick = { toggle() }, enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = uiContext.getString(R.string.settings_provider_select_cd, (entry.name).toString()) })
                        Icon(if (entry.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
                            contentDescription = null, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp)) {
                            Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (entry.isDirectory) "$count of ${entry.paths.size} selected" else
                                entry.changes.single().let { it.originalPath?.let { old -> uiContext.getString(R.string.ui_renamed_from, (old).toString()) } ?: it.status.trim() },
                                style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (entry.isDirectory) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ColumnScope.GitHubCommitFooter(
    selectedCount: Int,
    totalCount: Int,
    message: String,
    onMessageChange: (String) -> Unit,
    enabled: Boolean,
    onPublish: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(uiContext.getString(R.string.ui_of_files_selected, (selectedCount).toString(), (totalCount).toString()), style = MaterialTheme.typography.labelMedium)
    OutlinedTextField(message, onValueChange = onMessageChange, label = { Text(uiContext.getString(R.string.ui_commit_message)) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(onClick = onPublish, enabled = enabled && selectedCount > 0 && message.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(uiContext.getString(R.string.ui_commit_selected_files_push))
    }
}
