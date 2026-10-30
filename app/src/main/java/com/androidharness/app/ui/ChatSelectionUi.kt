package com.androidharness.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.ui.common.formatRelativeTime
import com.androidharness.app.ui.common.formatTokens
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun ChatSelectionToolbar(count: Int, allSelected: Boolean, allArchived: Boolean, busy: Boolean,
    onClose: () -> Unit, onSelectAll: () -> Unit, onAction: (SidebarChatAction) -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, enabled = !busy) { Icon(Icons.Outlined.Close, stringResource(R.string.sidebar_exit_selection)) }
                Text(stringResource(R.string.sidebar_selected_count, count), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onSelectAll, enabled = !busy) { Text(stringResource(if (allSelected) R.string.sidebar_clear_selection else R.string.sidebar_select_all), style = MaterialTheme.typography.labelMedium) }
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Triple(SidebarChatAction.DETAILS, R.string.sidebar_details, Icons.Outlined.Info),
                    Triple(SidebarChatAction.ARCHIVE, if (allArchived) R.string.ui_unarchive else R.string.ui_archive, Icons.Outlined.Archive),
                    Triple(SidebarChatAction.DELETE, R.string.settings_action_delete, Icons.Outlined.DeleteOutline)).forEach { (action, label, icon) ->
                    FilledTonalButton(onClick = { onAction(action) }, enabled = !busy, modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 10.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(icon, null, Modifier.size(20.dp), tint = if (action == SidebarChatAction.DELETE) MaterialTheme.colorScheme.error else LocalContentColor.current)
                            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, maxLines = 2,
                                color = if (action == SidebarChatAction.DELETE) MaterialTheme.colorScheme.error else LocalContentColor.current)
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

@Composable
internal fun ChatBatchDetailsDialog(sessions: List<SessionEntity>, workspace: (SessionEntity) -> String,
    model: (SessionEntity) -> String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.ui_chat_details)) },
        text = { LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.sidebar_selected_count, sessions.size), style = MaterialTheme.typography.labelLarge) }
            items(sessions, key = { it.id }) { session ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(session.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.ui_workspace_1707, workspace(session)), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.ui_model_1708, model(session)), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.ui_tokens_used, formatTokens(session.totalInputTokens + session.totalOutputTokens)), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.ui_updated_1710, formatRelativeTime(session.updatedAt)), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_close)) } })
}

@Composable
internal fun ChatBatchDeleteDialog(sessions: List<SessionEntity>, running: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.sidebar_delete_selected)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sidebar_delete_selected_message))
            if (running) Text(stringResource(R.string.sidebar_delete_running))
            LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sessions, key = { it.id }) { Text("• ${it.title}", style = MaterialTheme.typography.bodyMedium) }
            }
        } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_action_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } })
}
