package com.androidharness.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.androidharness.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * In-app folder browser for attaching a real path as a workspace — the
 * "Folder path (full shell)" flow without typing. Folders only, parent-row
 * navigation, "Use this folder" to confirm.
 *
 * Listing real storage needs privilege: direct `File` listing when All-files
 * access is granted, otherwise an `ls` through the Shizuku shell tier. With
 * neither, the dialog explains exactly which permission to grant instead of
 * showing a dead empty list.
 */
@Composable
fun FolderPickerDialog(
    container: AppContainer,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val root = remember { android.os.Environment.getExternalStorageDirectory().absolutePath }
    var currentDir by remember { mutableStateOf(root) }
    // Bumped after a grant attempt so the listing re-evaluates permissions.
    var permTick by remember { mutableIntStateOf(0) }

    val canList = container.shellRouter.isAllFilesAccess() ||
        (container.shizuku.isGranted())

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = scheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                // ----- Top bar -----
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    IconButton(
                        onClick = {
                            val parent = File(currentDir).parent
                            if (parent != null && currentDir != root &&
                                parent.startsWith(root)
                            ) {
                                currentDir = parent
                            }
                        },
                        enabled = currentDir != root,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Up",
                            tint = if (currentDir != root) scheme.onSurface
                            else scheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Choose a folder", style = MaterialTheme.typography.titleMediumEmphasized)
                        Text(
                            currentDir,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))

                if (!canList) {
                    // ----- Permission gate -----
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                    ) {
                        Text(
                            "Storage access needed",
                            style = MaterialTheme.typography.titleSmallEmphasized,
                        )
                        Text(
                            "Browsing device folders needs one of these permissions. " +
                                "Grant either to use the in-app file manager.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                SystemGrants.openAllFilesAccess(context)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Grant storage access") }
                        OutlinedButton(
                            onClick = {
                                container.shizuku.requestPermission()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Use Shizuku instead") }
                        TextButton(
                            onClick = { permTick++ },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("I've granted it — check again") }
                    }
                } else {
                    // ----- Folder list -----
                    val entries by produceState(initialValue = emptyList<String>(), currentDir, permTick) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                if (container.shellRouter.isAllFilesAccess()) {
                                    File(currentDir).listFiles()
                                        .orEmpty()
                                        .filter { it.isDirectory }
                                        .map { it.name }
                                        .sortedBy { it.lowercase() }
                                } else {
                                    val res = container.shellRouter.run(
                                        "ls -1Ap \"$currentDir\"",
                                        File(currentDir),
                                        10_000,
                                        60_000,
                                    )
                                    if (res.exitCode != 0) emptyList()
                                    else res.rawOutput.lines()
                                        .map { it.trim() }
                                        .filter { it.endsWith("/") }
                                        .map { it.dropLast(1) }
                                        .filter { it.isNotBlank() }
                                        .sortedBy { it.lowercase() }
                                }
                            }.getOrDefault(emptyList())
                        }
                    }

                    if (currentDir != root) {
                        FolderRow(
                            name = "..",
                            onClick = {
                                File(currentDir).parent?.let { currentDir = it }
                            },
                        )
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(entries, key = { it }) { name ->
                            FolderRow(
                                name = name,
                                onClick = { currentDir = "$currentDir/$name" },
                            )
                        }
                        if (entries.isEmpty()) {
                            item {
                                Text(
                                    "No folders here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(20.dp),
                                )
                            }
                        }
                    }
                }

                // ----- Confirm bar -----
                if (canList) {
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            currentDir,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = {
                            onPick(currentDir)
                            onDismiss()
                        }) { Text("Use this folder") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
        modifier = Modifier.padding(start = 48.dp),
    )
}
