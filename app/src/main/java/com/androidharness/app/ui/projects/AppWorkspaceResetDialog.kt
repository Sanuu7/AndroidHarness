package com.androidharness.app.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.settings.storageSize
import com.androidharness.app.workspace.AppWorkspaceResetPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Empties the permanent app workspace (issue #106). Unlike [ProjectDeletionDialog] the
 * workspace row survives, because new chats always need a default workspace.
 */
@Composable
internal fun AppWorkspaceResetDialog(
    container: AppContainer,
    project: ProjectEntity,
    onDismiss: () -> Unit,
    onReset: (Set<String>) -> Unit,
) {
    val uiContext = LocalContext.current
    var preview by remember(project.id) { mutableStateOf<AppWorkspaceResetPreview?>(null) }
    var error by remember(project.id) { mutableStateOf<String?>(null) }
    var resetting by remember(project.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(project.id) {
        try { preview = container.projectDeletion.previewAppWorkspace(project.id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: uiContext.getString(R.string.wp_workspace_delete_review_failed) }
    }
    AlertDialog(
        onDismissRequest = { if (!resetting) onDismiss() },
        icon = { Icon(Icons.Outlined.RestartAlt, null) },
        title = { Text(uiContext.getString(R.string.wp_app_workspace_reset_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(project.name, style = MaterialTheme.typography.titleSmall)
                val review = preview
                if (review == null && error == null) Text(uiContext.getString(R.string.wp_workspace_delete_checking))
                if (review != null) {
                    val chats = "${review.chatIds.size} saved ${if (review.chatIds.size == 1) "chat" else "chats"}"
                    val automations = "${review.automationIds.size} ${if (review.automationIds.size == 1) "automation" else "automations"}"
                    Text(uiContext.getString(R.string.wp_app_workspace_reset_body, chats, automations, storageSize(review.folderBytes)))
                    review.blocker?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !resetting && error == null && preview?.blocker == null && preview != null,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    val review = preview ?: return@TextButton
                    resetting = true
                    scope.launch {
                        try { container.projectDeletion.resetAppWorkspace(review); onReset(review.chatIds) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: uiContext.getString(R.string.ui_could_not_delete_this_project) }
                        finally { resetting = false }
                    }
                },
            ) {
                Text(if (resetting) uiContext.getString(R.string.ui_deleting)
                    else uiContext.getString(R.string.wp_app_workspace_reset_confirm))
            }
        },
        dismissButton = { TextButton(enabled = !resetting, onClick = onDismiss) { Text(uiContext.getString(R.string.ui_keep_project)) } },
    )
}
