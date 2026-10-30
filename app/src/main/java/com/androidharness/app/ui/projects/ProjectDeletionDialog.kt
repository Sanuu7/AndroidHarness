package com.androidharness.app.ui.projects

import com.androidharness.app.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.workspace.ProjectDeletionPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun ProjectDeletionDialog(
    container: AppContainer,
    project: ProjectEntity,
    onDismiss: () -> Unit,
    onDeleted: (Set<String>) -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var preview by remember(project.id) { mutableStateOf<ProjectDeletionPreview?>(null) }
    var error by remember(project.id) { mutableStateOf<String?>(null) }
    var deleting by remember(project.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(project.id) {
        try { preview = container.projectDeletion.preview(project.id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: uiContext.getString(R.string.ui_could_not_review_this_project) }
    }
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text(uiContext.getString(R.string.ui_delete_project_1680)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(project.name, style = MaterialTheme.typography.titleSmall)
                val review = preview
                if (review == null && error == null) Text(uiContext.getString(R.string.ui_checking_project))
                if (review != null) {
                    val chats = "${review.chatIds.size} saved ${if (review.chatIds.size == 1) "chat" else "chats"}"
                    val automations = "${review.automationIds.size} ${if (review.automationIds.size == 1) "automation" else "automations"}"
                    Text(uiContext.getString(R.string.ui_removes_this_project_and_from_the_app, (chats).toString(), (automations).toString()))
                    Text(uiContext.getString(R.string.ui_your_folder_and_files_stay_on_disk), style = MaterialTheme.typography.bodyMedium)
                    review.blocker?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !deleting && error == null && preview?.blocker == null && preview != null,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    val review = preview ?: return@TextButton
                    deleting = true
                    scope.launch {
                        try { container.projectDeletion.delete(review); onDeleted(review.chatIds) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: uiContext.getString(R.string.ui_could_not_delete_this_project) }
                        finally { deleting = false }
                    }
                },
            ) { Text(if (deleting) uiContext.getString(R.string.ui_deleting) else uiContext.getString(R.string.ui_delete_project)) }
        },
        dismissButton = { TextButton(enabled = !deleting, onClick = onDismiss) { Text(uiContext.getString(R.string.ui_keep_project)) } },
    )
}
