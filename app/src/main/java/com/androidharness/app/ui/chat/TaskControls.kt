package com.androidharness.app.ui.chat

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

@Composable
fun TaskProgressCard(record: TaskRecord, busy: Boolean, onResume: () -> Unit, onSettings: () -> Unit) {
    if (!record.resumable || record.status == "running" || busy) return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(if (record.status == "interrupted") "Task interrupted" else "Task paused",
                style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(record.reason ?: "Your progress is saved. Continue from the last completed action.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            record.reason?.let { CopyIconButton(it) }
            if (record.autoContinue && record.autoContinueAttempts >= record.autoContinueLimit.coerceIn(1, 5)) {
                Text("Auto-continue limit reached. Resume manually to keep going.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSettings) { Text("Context & limits") }
                FilledTonalButton(onClick = onResume) { Text("Resume task") }
            }
        }
    }
}

@Composable
fun MessageQueueCard(
    queue: List<QueuedPrompt>, onEdit: (String, String) -> Unit,
    onMove: (String, Int) -> Unit, onSendNow: (String) -> Unit,
) {
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
                    Text("${queue.size} queued", style = MaterialTheme.typography.labelLarge)
                    if (!expanded) Text(queue.first().text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Manage queue")
            }
            if (expanded) Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                Text("Sends after this chat’s Run finished notification.", modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                queue.forEachIndexed { index, item ->
                    HorizontalDivider()
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(item.text, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { editing = item }.padding(4.dp),
                            style = MaterialTheme.typography.bodySmall)
                        FlowRow(Modifier.fillMaxWidth()) {
                            IconButton(onClick = { onMove(item.id, -1) }, enabled = index > 0) {
                                Icon(Icons.Default.KeyboardArrowUp, "Move message up")
                            }
                            IconButton(onClick = { onMove(item.id, 1) }, enabled = index < queue.lastIndex) {
                                Icon(Icons.Default.KeyboardArrowDown, "Move message down")
                            }
                            IconButton(onClick = { editing = item }) { Icon(Icons.Default.Edit, "Edit queued message") }
                            IconButton(onClick = { onEdit(item.id, "") }) { Icon(Icons.Default.Close, "Remove queued message") }
                            TextButton(onClick = { onSendNow(item.id) }) { Text("Send now") }
                        }
                    }
                }
            }
        }
    }
    editing?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.text) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Edit queued message") },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8) },
            confirmButton = { TextButton(onClick = { onEdit(item.id, text); editing = null }, enabled = text.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSettingsSheet(
    state: ChatUiState, onDismiss: () -> Unit,
    onSave: (String, String?, TaskLimits, Boolean) -> Unit,
    onRecoveryChange: (Boolean, Int) -> Unit,
) {
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
                    Text("Context & limits", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { usage = true }) { Text("Usage") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = section == 0, onClick = { section = 0 }, label = { Text("Context") })
                    FilterChip(selected = section == 1, onClick = { section = 1 }, label = { Text("Task limits") })
                    FilterChip(selected = section == 2, onClick = { section = 2 }, label = { Text("Recovery") })
                }
                Column(Modifier.weight(1f, fill = false).heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.busy && section != 2) Text("Pause the task to edit these settings.", style = MaterialTheme.typography.bodySmall)
                    if (section == 0) {
                        Text("Keep the instructions that matter and choose what the agent remembers.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(pins, { pins = it }, label = { Text("Pinned instructions") },
                            placeholder = { Text("Use Kotlin. Keep the current theme.") },
                            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, enabled = !state.busy)
                        OutlinedTextField(summary, { summary = it }, label = { Text("Saved summary") },
                            placeholder = { Text("No summary saved yet") },
                            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 7, enabled = !state.busy)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Remove older context", style = MaterialTheme.typography.bodyMedium)
                                Text("Keep the latest user turn, summary and pins. Chat history stays visible.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(clearOlder, { clearOlder = it }, enabled = !state.busy)
                        }
                    } else if (section == 1) {
                        Text("Optional limits for the whole task, including subagents. Leave blank for unlimited.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(tokens, { tokens = it }, label = { Text("Total tokens") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
                        OutlinedTextField(cost, { cost = it }, label = { Text("Estimated cost · USD") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
                        OutlinedTextField(minutes, { minutes = it }, label = { Text("Active time · minutes") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
                        Text("Pauses before the next request or action. In-flight work may exceed a limit. Cost uses catalog prices or estimated rates. Raise a reached limit before resuming.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (record.usedTokens > 0 || record.elapsedMs > 0) Text(
                            "Used: ${record.usedTokens} tokens · $${"%.4f".format(record.usedCost)} · ${record.elapsedMs / 60000} min",
                            style = MaterialTheme.typography.labelMedium)
                        if (!valid) Text("Enter a positive number or leave blank.", color = MaterialTheme.colorScheme.error)
                    } else {
                        Row(Modifier.fillMaxWidth().clickable {
                            onRecoveryChange(!record.autoContinue, record.autoContinueLimit)
                        }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("Auto-continue", style = MaterialTheme.typography.bodyLarge)
                                Text("Resume saved progress after a server or connection interruption.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(record.autoContinue, { onRecoveryChange(it, record.autoContinueLimit) })
                        }
                        Text("Uses the same prompt as Resume task. Queued messages wait until this chat finishes successfully. Stops and task limits need your attention.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Retry limit per task", modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRecoveryChange(record.autoContinue, record.autoContinueLimit - 1) },
                                enabled = record.autoContinueLimit > 1) { Icon(Icons.Default.Remove, "Decrease retry limit") }
                            Text("${record.autoContinueLimit.coerceIn(1, 5)}")
                            IconButton(onClick = { onRecoveryChange(record.autoContinue, record.autoContinueLimit + 1) },
                                enabled = record.autoContinueLimit < 5) { Icon(Icons.Default.Add, "Increase retry limit") }
                        }
                        Text("Automatic Continues used: ${record.autoContinueAttempts}. A new task resets the count.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Changes are saved immediately.", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (section == 2) {
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) { Text("Done") }
                } else Button(onClick = {
                    onSave(pins, if (summary == savedSummary && !clearOlder) record.summaryOverride else summary,
                        TaskLimits(tokens.toLongOrNull() ?: 0, cost.toDoubleOrNull() ?: 0.0, minutes.toLongOrNull() ?: 0), clearOlder)
                    onDismiss()
                }, enabled = valid && !state.busy, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) { Text("Save changes") }
            }
        }
    }
}
