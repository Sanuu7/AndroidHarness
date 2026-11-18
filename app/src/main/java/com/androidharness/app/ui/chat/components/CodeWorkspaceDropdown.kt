package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.androidharness.app.R
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.workspace.WorkspaceDescription

/**
 * Picks the workspace a new Code chat starts in, styled like the Chat section's
 * personalization picker. The last row opens the usual add-workspace sheet.
 */
@Composable
internal fun CodeWorkspaceDropdown(
    workspaces: List<ProjectEntity>,
    selectedId: String?,
    enabled: Boolean,
    describe: (ProjectEntity) -> WorkspaceDescription,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val selected = workspaces.firstOrNull { it.id == selectedId }
    val fullShell = stringResource(R.string.wp_full_shell)
    val fileTools = stringResource(R.string.wp_file_tools)
    ChatStartDropdown(selected?.name ?: stringResource(R.string.ui_choose_workspace), enabled, modifier) { dismiss ->
        // Stable order: the source list is sorted by recency and would jump on every pick.
        for (workspace in workspaces.sortedBy { it.name.lowercase() }) {
            val description = describe(workspace)
            ChatStartDropdownItem(
                workspace.name,
                description.kindLabel + " · " + if (description.shellCapable) fullShell else fileTools,
                workspace.id == selectedId,
            ) {
                dismiss()
                if (workspace.id != selectedId) onSelect(workspace.id)
            }
        }
        androidx.compose.material3.HorizontalDivider()
        ChatStartDropdownItem(stringResource(R.string.wp_add_workspace), stringResource(R.string.wp_add_sheet_sub), false) {
            dismiss(); onAdd()
        }
    }
}
