package com.androidharness.app.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.ui.common.AppHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FilesScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val fs by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)
    var currentPath by remember { mutableStateOf(".") }
    val scheme = MaterialTheme.colorScheme

    val entries by produceState(initialValue = emptyList(), fs, currentPath) {
        value = withContext(Dispatchers.IO) {
            val node = fs?.let { runCatching { it.resolve(currentPath) }.getOrNull() }
            node?.list()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
        }
    }
    val folderAccessible = entries.isNotEmpty() || currentPath == "."

    Scaffold(
        containerColor = scheme.surface,
        topBar = {
            AppHeader(
                title = "Files",
                subtitle = run {
                    val base = fs?.displayPath.orEmpty()
                    if (currentPath == ".") base else "$base/$currentPath"
                },
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (currentPath != ".") {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { currentPath = parentOf(currentPath) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(14.dp))
                        Text("..", style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider(
                        color = scheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 49.dp),
                    )
                }
            }
            items(entries, key = { it.relPath }) { node ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (node.isDirectory) {
                                currentPath = node.relPath
                            } else {
                                onOpenFile(node.relPath)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                ) {
                    Icon(
                        if (node.isDirectory) Icons.Outlined.Folder
                        else Icons.AutoMirrored.Outlined.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            node.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (node.isFile) {
                            Text(
                                formatSize(node.length),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (node.isDirectory) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
                HorizontalDivider(
                    color = scheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 49.dp),
                )
            }
            if (entries.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Outlined.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                        Text(
                            if (folderAccessible) "Empty directory.\nAsk the agent to create some files."
                            else "Folder not accessible.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun parentOf(path: String): String {
    val segments = path.split('/').filter { it.isNotBlank() }
    return if (segments.size <= 1) "." else segments.dropLast(1).joinToString("/")
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
