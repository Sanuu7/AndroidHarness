package com.androidharness.app.ui.common

import com.androidharness.app.R
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.androidharness.app.ui.settings.SettingsExpressiveTheme
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.ui.theme.HarnessMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * In-app folder browser for attaching a real path as a workspace, the
 * "Folder path (full shell)" flow without typing. Folders only, parent-row
 * navigation, "Use this folder" to confirm.
 *
 * Listing real storage needs privilege: direct `File` listing when All-files
 * access is granted, otherwise an `ls` through the Shizuku shell tier. With
 * neither, the dialog explains exactly which permission to grant instead of
 * showing a dead empty list.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FolderPickerDialog(
    container: AppContainer,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val root = remember { android.os.Environment.getExternalStorageDirectory().absolutePath }
    var currentDir by remember { mutableStateOf(root) }
    // Bumped after a grant attempt so the listing re-evaluates permissions.
    var permTick by remember { mutableIntStateOf(0) }
    val storageAccess = rememberStorageAccess()
    val shizukuState by container.shizuku.state.collectAsStateWithLifecycle()

    val canList = storageAccess.granted ||
        shizukuState == com.androidharness.app.data.env.ShizukuState.GRANTED

    fun goUp() {
        val parent = File(currentDir).parent
        if (parent != null && currentDir != root && parent.startsWith(root)) currentDir = parent
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        SettingsExpressiveTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = scheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    ExpressiveIconButton(onClick = ::goUp, enabled = currentDir != root, colors = IconButtonDefaults.filledTonalIconButtonColors()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiContext.getString(R.string.ui_up))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(uiContext.getString(R.string.ui_choose_a_folder), style = MaterialTheme.typography.titleLarge)
                        Text(currentDir, style = MaterialTheme.typography.labelMedium, fontFamily = HarnessMono,
                            color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    }
                    ExpressiveIconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = uiContext.getString(R.string.settings_action_close))
                    }
                }

                if (!canList) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                    ) {
                        ExpressiveEmptyState(Icons.Outlined.Lock, uiContext.getString(R.string.ui_storage_access_needed), seed = 3,
                            modifier = Modifier.padding(bottom = 0.dp))
                        Text(
                            uiContext.getString(R.string.ui_browsing_device_folders_needs_one_of_these) +
                                uiContext.getString(R.string.ui_grant_either_to_use_the_in_app),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        Button(onClick = storageAccess.request, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(uiContext.getString(R.string.ui_grant_storage_access))
                        }
                        FilledTonalButton(onClick = { container.shizuku.requestPermission() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(uiContext.getString(R.string.ui_use_shizuku_instead))
                        }
                        TextButton(
                            onClick = {
                                storageAccess.refresh()
                                container.shizuku.refresh()
                                permTick++
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(uiContext.getString(R.string.ui_i_ve_granted_it_check_again)) }
                    }
                } else {
                    // Null while a folder is being read, so the list does not flash "empty" between folders.
                    val entries by produceState<List<String>?>(initialValue = null, currentDir, permTick, storageAccess.granted, shizukuState) {
                        value = null
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
                    val folders = entries
                    if (folders == null) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        LoadingIndicator(Modifier.size(56.dp))
                    } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (currentDir != root) item(key = "\u0000parent") {
                            ExpressiveListRow(0, 1, uiContext.getString(R.string.wp_picker_parent), dense = true, onClick = ::goUp,
                                color = scheme.surfaceContainerHigh, modifier = Modifier.padding(bottom = 6.dp),
                                leading = { ExpressiveIconBadge(Icons.AutoMirrored.Outlined.DriveFileMove, size = 36.dp,
                                    container = scheme.tertiaryContainer, tint = scheme.onTertiaryContainer) })
                        }
                        itemsIndexed(folders, key = { _, name -> name }) { index, name ->
                            ExpressiveListRow(index, folders.size, name, dense = true, onClick = { currentDir = "$currentDir/$name" },
                                modifier = Modifier.animateItem(fadeInSpec = expressiveEffectsSpec(), placementSpec = expressiveSpatialSpec(), fadeOutSpec = expressiveEffectsSpec()),
                                leading = { ExpressiveIconBadge(Icons.Outlined.Folder, size = 36.dp) },
                                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(end = 8.dp).size(20.dp), tint = scheme.onSurfaceVariant) })
                        }
                        if (folders.isEmpty()) item {
                            ExpressiveEmptyState(Icons.Outlined.FolderOff, uiContext.getString(R.string.ui_no_folders_here), seed = 5)
                        }
                    }
                }

                if (canList) {
                    Surface(color = scheme.surfaceContainer, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                currentDir.substringAfterLast('/').ifBlank { currentDir },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Button(onClick = {
                                onPick(currentDir)
                                onDismiss()
                            }, modifier = Modifier.heightIn(min = 52.dp)) { Text(uiContext.getString(R.string.ui_use_this_folder)) }
                        }
                    }
                }
            }
        }
        }
    }
}
