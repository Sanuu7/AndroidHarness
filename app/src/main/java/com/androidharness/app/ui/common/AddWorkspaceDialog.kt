package com.androidharness.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.env.PathClassifier
import com.androidharness.app.ui.settings.SettingsExpressiveTheme
import com.androidharness.app.ui.theme.HarnessGlassModalBottomSheet as ModalBottomSheet
import kotlinx.coroutines.launch

/**
 * The ways to add a workspace, shared by every place that offers it:
 * 1. App workspace. Private folder, shell always works.
 * 2. Device folder. Browsed in-app, no typing. Full shell.
 * 3. System picker (SAF). Works without broad storage access. Directly writable
 *    device folders are upgraded to full shell; other picks use file tools.
 * 4. GitHub import and SSH, which open their own dialogs.
 *
 * Adding a workspace never creates a project.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWorkspaceDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
    onPickSaf: () -> Unit,
    onSelected: (String) -> Unit = {},
    activateWorkspace: Boolean = true,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    ShizukuWorkspaceWarningEffect(container.shizuku)
    var destination by remember { mutableStateOf<String?>(null) }
    if (destination == "github") {
        com.androidharness.app.ui.github.GitHubImportDialog(container, onDismiss,
            onImported = onDismiss, activateWorkspace = activateWorkspace, onProjectImported = onSelected)
        return
    }
    if (destination == "ssh") {
        SshWorkspaceDialog(container, onDismiss = onDismiss, onSelected = onSelected, activateWorkspace = activateWorkspace)
        return
    }
    val scope = rememberCoroutineScope()
    val projects = container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val appProject = projects.value.firstOrNull { it.kind == "APP" }
    var error by remember { mutableStateOf<String?>(null) }

    fun addBrowsedFolder(path: String) {
        val trimmed = path.trim()
        val assessment = container.workspace.assessPath(trimmed)
        when {
            !assessment.directoryExists ->
                error = uiContext.getString(R.string.ui_that_folder_could_not_be_read_try)
            assessment.region == PathClassifier.Region.APP_DATA ->
                error = uiContext.getString(R.string.ui_that_is_the_app_s_own_private)
            else -> scope.launch {
                onSelected(container.workspace.addShellProject(trimmed, activateWorkspace).id)
                onDismiss()
            }
        }
    }

    if (destination == "browse") {
        FolderPickerDialog(
            container = container,
            onPick = { picked -> destination = null; addBrowsedFolder(picked) },
            onDismiss = { if (destination == "browse") destination = null },
        )
    }

    SettingsExpressiveTheme {
        val scheme = MaterialTheme.colorScheme
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
                ExpressiveSheetHeader(uiContext.getString(R.string.wp_add_sheet_title), uiContext.getString(R.string.wp_add_sheet_sub),
                    uiContext.getString(R.string.settings_action_close), onDismiss)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ExpressiveListRow(0, 3, uiContext.getString(R.string.wp_add_device), subtitle = uiContext.getString(R.string.wp_add_device_sub),
                        onClick = { destination = "browse" },
                        leading = { ExpressiveIconBadge(Icons.Outlined.Folder, container = scheme.primaryContainer, tint = scheme.onPrimaryContainer) })
                    ExpressiveListRow(1, 3, uiContext.getString(R.string.wp_add_picker), subtitle = uiContext.getString(R.string.wp_add_picker_sub),
                        onClick = onPickSaf, leading = { ExpressiveIconBadge(Icons.Outlined.FolderOpen) })
                    ExpressiveListRow(2, 3, uiContext.getString(R.string.wp_add_app), subtitle = uiContext.getString(R.string.wp_add_app_sub),
                        onClick = {
                            if (appProject != null) scope.launch {
                                if (activateWorkspace) container.workspace.setActiveProject(appProject.id)
                                onSelected(appProject.id)
                                onDismiss()
                            } else onDismiss()
                        }, leading = { ExpressiveIconBadge(Icons.Outlined.Home) })
                }
                ExpressiveSectionLabel(uiContext.getString(R.string.wp_workspace_more))
                Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ExpressiveListRow(0, 2, uiContext.getString(R.string.wp_add_github), subtitle = uiContext.getString(R.string.wp_add_github_sub),
                        onClick = { destination = "github" },
                        leading = { ExpressiveIconBadge(Icons.Outlined.CloudDownload, container = scheme.tertiaryContainer, tint = scheme.onTertiaryContainer) })
                    ExpressiveListRow(1, 2, uiContext.getString(R.string.wp_add_ssh), subtitle = uiContext.getString(R.string.wp_add_ssh_sub),
                        onClick = { destination = "ssh" },
                        leading = { ExpressiveIconBadge(Icons.Outlined.Dns, container = scheme.tertiaryContainer, tint = scheme.onTertiaryContainer) })
                }
                error?.let { msg ->
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = scheme.error, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
                }
            }
        }
    }
}
