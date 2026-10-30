package com.androidharness.app.ui.chat

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import com.androidharness.app.R
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.*
import com.androidharness.app.core.Role
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

@Composable
fun TaskProgressCard(record: TaskRecord, busy: Boolean, onResume: () -> Unit, onSettings: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    if (!record.resumable || record.status == "running" || busy) return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(if (record.status == "interrupted") uiContext.getString(R.string.ui_task_interrupted) else uiContext.getString(R.string.ui_task_paused),
                style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(record.reason ?: uiContext.getString(R.string.ui_your_progress_is_saved_continue_from_the),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            record.reason?.let { CopyIconButton(it) }
            if (record.autoContinue && record.autoContinueAttempts >= record.autoContinueLimit.coerceIn(1, 5)) {
                Text(uiContext.getString(R.string.ui_auto_continue_limit_reached_resume_manually_to),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSettings) { Text(uiContext.getString(R.string.ui_context_limits)) }
                FilledTonalButton(onClick = onResume) { Text(uiContext.getString(R.string.ui_resume_task)) }
            }
        }
    }
}

@Composable
fun MessageQueueCard(
    queue: List<QueuedPrompt>, onEdit: (String, String) -> Unit,
    onMove: (String, Int) -> Unit, onSendNow: (String) -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    if (queue.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<QueuedPrompt?>(null) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp)
                .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(uiContext.getString(R.string.ui_queued_extra69, (queue.size).toString()), style = MaterialTheme.typography.labelLarge)
                    if (!expanded) Text(com.androidharness.app.core.response.UserMessageContent.visibleText(queue.first().text), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, uiContext.getString(R.string.ui_manage_queue))
            }
            if (expanded) Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                Text(uiContext.getString(R.string.ui_sends_after_this_chat_s_run_finished), modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                queue.forEachIndexed { index, item ->
                    HorizontalDivider()
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(com.androidharness.app.core.response.UserMessageContent.visibleText(item.text), maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { editing = item }.padding(4.dp),
                            style = MaterialTheme.typography.bodySmall)
                        FlowRow(Modifier.fillMaxWidth()) {
                            IconButton(onClick = { onMove(item.id, -1) }, enabled = index > 0) {
                                Icon(Icons.Default.KeyboardArrowUp, uiContext.getString(R.string.ui_move_message_up))
                            }
                            IconButton(onClick = { onMove(item.id, 1) }, enabled = index < queue.lastIndex) {
                                Icon(Icons.Default.KeyboardArrowDown, uiContext.getString(R.string.ui_move_message_down))
                            }
                            IconButton(onClick = { editing = item }) { Icon(Icons.Default.Edit, uiContext.getString(R.string.ui_edit_queued_message)) }
                            IconButton(onClick = { onEdit(item.id, "") }) { Icon(Icons.Default.Close, uiContext.getString(R.string.ui_remove_queued_message)) }
                            TextButton(onClick = { onSendNow(item.id) }) { Text(uiContext.getString(R.string.ui_send_now)) }
                        }
                    }
                }
            }
        }
    }
    editing?.let { item ->
        var text by remember(item.id) { mutableStateOf(com.androidharness.app.core.response.UserMessageContent.visibleText(item.text)) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(uiContext.getString(R.string.ui_edit_queued_message)) },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8) },
            confirmButton = { TextButton(onClick = { onEdit(item.id, com.androidharness.app.core.response.UserMessageContent.edit(text, item.text)); editing = null }, enabled = text.isNotBlank()) { Text(uiContext.getString(R.string.settings_action_save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(uiContext.getString(R.string.settings_action_cancel)) } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSettingsSheet(
    state: ChatUiState, onDismiss: () -> Unit,
    onSave: (String, String?, TaskLimits, Boolean) -> Unit,
    onRecoveryChange: (Boolean, Int) -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val record = state.taskControl
    val savedSummary = record.summaryOverride ?: state.messages.lastOrNull {
        it.role == Role.SYSTEM && it.text.startsWith(AgentEngine.COMPACTION_PREFIX)
    }?.text?.removePrefix(AgentEngine.COMPACTION_PREFIX)?.trim().orEmpty()
    var pins by remember(state.sessionId) { mutableStateOf(record.pins) }
    var summary by remember(state.sessionId) { mutableStateOf(savedSummary) }
    var tokens by remember { mutableStateOf(record.limits.tokens.takeIf { it > 0 }?.toString().orEmpty()) }
    var cost by remember { mutableStateOf(record.limits.cost.takeIf { it > 0 }?.toString().orEmpty()) }
    var minutes by remember { mutableStateOf(record.limits.minutes.takeIf { it > 0 }?.toString().orEmpty()) }
    var clearOlder by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(0) }
    var usage by remember { mutableStateOf(true) }
    val valid = (tokens.isBlank() || (tokens.toLongOrNull() ?: -1) > 0) &&
        (cost.isBlank() || cost.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true) &&
        (minutes.isBlank() || (minutes.toLongOrNull() ?: -1) in 1..525600)
    if (usage) {
        ContextUsageDialog(
            state = state,
            onDismiss = onDismiss,
            onContextLimits = { usage = false },
        )
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(uiContext.getString(R.string.ui_context_limits), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { usage = true }) { Text(uiContext.getString(R.string.settings_page_usage_title)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = section == 0, onClick = { section = 0 }, label = { Text(uiContext.getString(R.string.ui_context)) })
                    FilterChip(selected = section == 1, onClick = { section = 1 }, label = { Text(uiContext.getString(R.string.ui_task_limits)) })
                    FilterChip(selected = section == 2, onClick = { section = 2 }, label = { Text(uiContext.getString(R.string.ui_recovery)) })
                }
                Column(Modifier.weight(1f, fill = false).heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.busy && section != 2) Text(uiContext.getString(R.string.ui_pause_the_task_to_edit_these_settings), style = MaterialTheme.typography.bodySmall)
                    if (section == 0) {
                        Text(uiContext.getString(R.string.ui_keep_the_instructions_that_matter_and_choose),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(pins, { pins = it }, label = { Text(uiContext.getString(R.string.ui_pinned_instructions)) },
                            placeholder = { Text(uiContext.getString(R.string.ui_use_kotlin_keep_the_current_theme)) },
                            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, enabled = !state.busy)
                        OutlinedTextField(summary, { summary = it }, label = { Text(uiContext.getString(R.string.ui_saved_summary)) },
                            placeholder = { Text(uiContext.getString(R.string.ui_no_summary_saved_yet)) },
                            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 7, enabled = !state.busy)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(uiContext.getString(R.string.ui_remove_older_context), style = MaterialTheme.typography.bodyMedium)
                                Text(uiContext.getString(R.string.ui_keep_the_latest_user_turn_summary_and),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(clearOlder, { clearOlder = it }, enabled = !state.busy)
                        }
                    } else if (section == 1) {
                        Text(uiContext.getString(R.string.ui_optional_limits_for_the_whole_task_including),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(tokens, { tokens = it }, label = { Text(uiContext.getString(R.string.ui_total_tokens)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
                        OutlinedTextField(cost, { cost = it }, label = { Text(uiContext.getString(R.string.ui_estimated_cost_usd)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
                        OutlinedTextField(minutes, { minutes = it }, label = { Text(uiContext.getString(R.string.ui_active_time_minutes)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
                        Text(uiContext.getString(R.string.ui_pauses_before_the_next_request_or_action),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (record.usedTokens > 0 || record.elapsedMs > 0) Text(
                            uiContext.getString(R.string.ui_used_tokens_min, (record.usedTokens).toString(), ("%.4f".format(record.usedCost)).toString(), (record.elapsedMs / 60000).toString()),
                            style = MaterialTheme.typography.labelMedium)
                        if (!valid) Text(uiContext.getString(R.string.ui_enter_a_positive_number_or_leave_blank), color = MaterialTheme.colorScheme.error)
                    } else {
                        Row(Modifier.fillMaxWidth().clickable {
                            onRecoveryChange(!record.autoContinue, record.autoContinueLimit)
                        }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(uiContext.getString(R.string.ui_auto_continue), style = MaterialTheme.typography.bodyLarge)
                                Text(uiContext.getString(R.string.ui_resume_saved_progress_after_a_server_or),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(record.autoContinue, { onRecoveryChange(it, record.autoContinueLimit) })
                        }
                        Text(uiContext.getString(R.string.ui_uses_the_same_prompt_as_resume_task),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(uiContext.getString(R.string.ui_retry_limit_per_task), modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRecoveryChange(record.autoContinue, record.autoContinueLimit - 1) },
                                enabled = record.autoContinueLimit > 1) { Icon(Icons.Default.Remove, uiContext.getString(R.string.ui_decrease_retry_limit)) }
                            Text("${record.autoContinueLimit.coerceIn(1, 5)}")
                            IconButton(onClick = { onRecoveryChange(record.autoContinue, record.autoContinueLimit + 1) },
                                enabled = record.autoContinueLimit < 5) { Icon(Icons.Default.Add, uiContext.getString(R.string.ui_increase_retry_limit)) }
                        }
                        Text(uiContext.getString(R.string.ui_automatic_continues_used_a_new_task_resets, (record.autoContinueAttempts).toString()),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(uiContext.getString(R.string.ui_changes_are_saved_immediately), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (section == 2) {
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) { Text(uiContext.getString(R.string.settings_action_done)) }
                } else Button(onClick = {
                    onSave(pins, if (summary == savedSummary && !clearOlder) record.summaryOverride else summary,
                        TaskLimits(tokens.toLongOrNull() ?: 0, cost.toDoubleOrNull() ?: 0.0, minutes.toLongOrNull() ?: 0), clearOlder)
                    onDismiss()
                }, enabled = valid && !state.busy, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) { Text(uiContext.getString(R.string.settings_root_browser_save_confirm)) }
            }
        }
    }
}
