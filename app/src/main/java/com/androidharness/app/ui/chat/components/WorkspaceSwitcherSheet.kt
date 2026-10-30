package com.androidharness.app.ui.chat.components

import com.androidharness.app.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.workspace.WorkspaceDescription
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

/**
 * Quick workspace switching without leaving chat: every project as a
 * divider-separated row with its capability ("full shell" vs "file tools"),
 * tap to activate, delete non-app workspaces, one tap to add more.
 *
 * Why a sheet: picking a workspace affects what every tool touches next, so
 * it stays one tap from anywhere (chat overflow, drawer) rather than buried
 * in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSwitcherSheet(
    projects: List<ProjectEntity>,
    currentProjectId: String?,
    describe: (ProjectEntity) -> WorkspaceDescription,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: ((ProjectEntity) -> Unit)? = null,
    chatWorkspace: Boolean = false,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (chatWorkspace) uiContext.getString(R.string.ui_chat_workspace) else uiContext.getString(R.string.ui_workspace), style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        if (chatWorkspace) uiContext.getString(R.string.ui_assigned_to_this_chat) else uiContext.getString(R.string.ui_default_for_new_chats),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = uiContext.getString(R.string.settings_action_close))
                }
            }
            // Bounded height: wrap-content lists inside sheets fight the
            // dismiss gesture (same rule as every other sheet).
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(top = 8.dp),
            ) {
                itemsIndexed(projects, key = { _, p -> p.id }) { index, project ->
                    val desc = describe(project)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(project.id)
                                onDismiss()
                            }
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                project.name,
                                style = MaterialTheme.typography.titleSmallEmphasized,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                desc.kindLabel +
                                    if (desc.shellCapable) uiContext.getString(R.string.ui_full_shell) else uiContext.getString(R.string.ui_file_tools_only),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (project.id == currentProjectId) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = uiContext.getString(R.string.settings_workspace_active_badge),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        if (onDelete != null && project.kind != "APP") {
                            IconButton(onClick = {
                                onDismiss()
                                onDelete(project)
                            }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = uiContext.getString(R.string.settings_workspace_delete_cd, (project.name).toString()),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    if (index < projects.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                item {
                    TextButton(onClick = {
                        onDismiss()
                        onAdd()
                    }) { Text(uiContext.getString(R.string.ui_add_workspace)) }
                }
            }
        }
    }
}
