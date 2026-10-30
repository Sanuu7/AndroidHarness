package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.core.ChatActivityPresentation
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.data.MemorySection
import com.androidharness.app.data.db.MemoryEntity
import com.androidharness.app.ui.settings.MemoryEditDialog
import com.androidharness.app.ui.settings.sectionLabel
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.TextButton

/** A saved/updated receipt only. Supplied-memory metadata stays out of the transcript. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatMemoryReceipt(messages: List<ChatMessage>, container: AppContainer, busy: Boolean,
    supplied: List<com.androidharness.app.core.SuppliedMemory> = emptyList()) {
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var deleting by remember { mutableStateOf<MemoryEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val label = when (ChatActivityPresentation.memoryAction(messages)) {
        "updated" -> R.string.chat_memory_updated
        "unchanged" -> R.string.chat_memory_unchanged
        else -> R.string.chat_memory_saved
    }
    ChatMemorySaved(label = label) { open = true }
    if (!open) return
    val memories by container.memory.memories.collectAsStateWithLifecycle(initialValue = emptyList())
    val projects by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val ids = remember(messages, supplied) { ChatActivityPresentation.savedMemoryIds(messages) + supplied.map { it.id } }
    val currentIds = ids.map { id -> memories.firstOrNull { it.id == id }?.consolidatedInto ?: id }.toSet()
    val saved = memories.filter { it.id in currentIds }
    if (editing == null && deleting == null) ModalBottomSheet(
        onDismissRequest = { if (!saving) open = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.settings_action_close)) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (saved.isEmpty()) Text(stringResource(R.string.chat_memory_unavailable))
                saved.forEach { memory ->
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.fillMaxWidth().clickable(enabled = !busy && !saving) { editing = memory; error = null }
                            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(stringResource(sectionLabel(MemorySection.fromStored(memory.section))), modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                TextButton(enabled = !busy && !saving, onClick = { editing = memory; error = null }) {
                                    Text(stringResource(R.string.settings_memory_edit_title))
                                }
                            }
                            Text(memory.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (busy) Text(stringResource(R.string.chat_memory_wait), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    editing?.let { memory ->
        if (saved.none { it.id == memory.id }) { LaunchedEffect(memory.id) { editing = null }; return@let }
        MemoryEditDialog(memory, projects, enabled = !busy && !saving, error = error,
            onDismiss = { if (!saving) { editing = null; error = null } },
            onSave = { text, category, memoryScope, project, section ->
                if (!saving && !busy) { saving = true; scope.launch {
                    try { container.memory.update(memory, text, category, memoryScope, project, section); editing = null }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = e.message }
                    finally { saving = false }
                } }
            }, onDelete = { if (!saving && !busy) { deleting = memory; editing = null } })
    }
    deleting?.let { memory ->
        AlertDialog(
            onDismissRequest = { if (!saving) deleting = null },
            title = { Text(stringResource(R.string.settings_memory_delete_confirm)) },
            text = { Text(stringResource(if (memory.isSummary) R.string.memory_delete_digest_body else R.string.settings_memory_delete_body)) },
            confirmButton = {
                TextButton(enabled = !saving && !busy, onClick = {
                    if (!saving && !busy) {
                        saving = true
                        scope.launch {
                            try { container.memory.remove(memory.id); deleting = null }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { error = e.message; deleting = null }
                            finally { saving = false }
                        }
                    }
                }) { Text(stringResource(R.string.settings_memory_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = { deleting = null }) { Text(stringResource(R.string.settings_action_cancel)) } },
        )
    }
}
