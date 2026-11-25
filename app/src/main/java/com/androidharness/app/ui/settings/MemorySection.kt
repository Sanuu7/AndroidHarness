package com.androidharness.app.ui.settings

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.MemoryCategory
import com.androidharness.app.data.MemoryPrompt
import com.androidharness.app.data.MemorySection
import com.androidharness.app.data.MemoryConsolidation
import com.androidharness.app.data.MemoryFolderTarget
import com.androidharness.app.data.MemoryFolderAgents
import com.androidharness.app.data.MemoryRepository
import com.androidharness.app.data.MemoryScope
import com.androidharness.app.data.MemoryText
import com.androidharness.app.data.db.MemoryEntity
import com.androidharness.app.data.db.ProjectEntity
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun MemoryOptionsSection(
    container: AppContainer,
    settings: AppSettings,
    onEnabled: (Boolean) -> Unit,
    onOpen: () -> Unit,
    showManage: Boolean = true,
) {
    val memories by container.memory.memories.collectAsStateWithLifecycle(initialValue = emptyList())
    val count = memories.count { it.consolidatedInto == null }
    val work = rememberCoroutineScope()
    var editingLimit by rememberSaveable { mutableStateOf(false) }
    SettingsAnchor(R.string.settings_memory) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.settings_memory), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(if (settings.memoryEnabled) R.string.settings_memory_on else R.string.settings_memory_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.memoryEnabled, onCheckedChange = onEnabled)
                }
                MemoryPreferenceRow(stringResource(R.string.memory_chat_enabled), stringResource(R.string.memory_chat_enabled_desc),
                    settings.chatMemoryEnabled, settings.memoryEnabled) { value -> work.launch { container.settings.setSectionMemoryEnabled(MemorySection.CHAT, value) } }
                MemoryPreferenceRow(stringResource(R.string.memory_chat_cross), stringResource(R.string.memory_cross_desc),
                    settings.chatRecallCodeMemory, settings.memoryEnabled && settings.chatMemoryEnabled && settings.codeMemoryEnabled) { value -> work.launch { container.settings.setCrossSectionMemory(MemorySection.CHAT, value) } }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                MemoryPreferenceRow(stringResource(R.string.memory_code_enabled), stringResource(R.string.memory_code_enabled_desc),
                    settings.codeMemoryEnabled, settings.memoryEnabled) { value -> work.launch { container.settings.setSectionMemoryEnabled(MemorySection.CODE, value) } }
                MemoryPreferenceRow(stringResource(R.string.memory_code_cross), stringResource(R.string.memory_cross_desc),
                    settings.codeRecallChatMemory, settings.memoryEnabled && settings.codeMemoryEnabled && settings.chatMemoryEnabled) { value -> work.launch { container.settings.setCrossSectionMemory(MemorySection.CODE, value) } }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                MemoryPreferenceRow(stringResource(R.string.memory_entry_unlimited), stringResource(R.string.memory_entry_unlimited_desc),
                    settings.memoryEntryUnlimited, settings.memoryEnabled) { value -> work.launch { container.settings.setMemoryEntryUnlimited(value) } }
                SettingsAnchor(R.string.memory_entry_max_chars) {
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = settings.memoryEnabled && !settings.memoryEntryUnlimited) { editingLimit = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(R.string.memory_entry_max_chars), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.memory_entry_max_chars_desc), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (settings.memoryEntryUnlimited) stringResource(R.string.memory_entry_unlimited_value)
                            else stringResource(R.string.memory_entry_limit_value, settings.memoryEntryMaxChars), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.memory_shared_explanation), modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (showManage) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onOpen)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(R.string.settings_memory_manage), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.settings_memory_manage_desc, count),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (editingLimit) MemoryEntryLimitDialog(settings.memoryEntryMaxChars, onDismiss = { editingLimit = false }) { value ->
        work.launch { container.settings.setMemoryEntryMaxChars(value); editingLimit = false }
    }
}

@Composable
private fun MemoryEntryLimitDialog(initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial.toString()) }
    val limit = value.toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_entry_max_chars)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.all { character -> character in '0'..'9' }) value = it },
                label = { Text(stringResource(R.string.memory_entry_max_chars)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = limit == null,
                supportingText = { Text(stringResource(if (limit == null) R.string.memory_entry_limit_invalid else R.string.memory_entry_max_chars_desc)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = limit != null, onClick = { limit?.let(onSave) }) { Text(stringResource(R.string.settings_memory_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryManagerSection(container: AppContainer) {
    val memories by container.memory.memories.collectAsStateWithLifecycle(initialValue = emptyList())
    val projects by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var deleting by remember { mutableStateOf<MemoryEntity?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val agentSettings by container.settings.settings.collectAsStateWithLifecycle(initialValue = com.androidharness.app.data.AppSettings())
    val projectNames = remember(projects) { projects.associate { it.id to it.name } }
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var consolidation by remember { mutableStateOf<MemoryConsolidation?>(null) }
    val folderNames = rememberMemoryFolderNames(container)
    // "c:FACT" is a built-in folder, "f:Name" one the user or agent created.
    var folderFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf<String?>(null) } // "" creates, a name renames
    var removingFolder by remember { mutableStateOf<String?>(null) }
    var limitingFolder by remember { mutableStateOf<String?>(null) }
    val folderRows by container.memory.folders.collectAsStateWithLifecycle(initialValue = emptyList())
    val folderLimits = remember(folderRows) { MemoryFolderAgents.of(folderRows) }
    var undo by remember { mutableStateOf<Pair<MemoryRepository.FolderDeletion, String>?>(null) }
    val builtInLabels = MemoryCategory.entries.associateWith { stringResource(categoryLabel(it.name)) }
    val entries = memories.filter { it.consolidatedInto == null }
    val active = entries.count { it.enabled }
    fun inFolder(memory: MemoryEntity, key: String?): Boolean = when {
        key == null -> true
        key.startsWith("c:") -> memory.folder == null && memory.category == key.removePrefix("c:")
        else -> memory.folder == key.removePrefix("f:")
    }
    val inSection = entries.filter { section == null || it.section == section }
    LaunchedEffect(folderNames) {
        // A folder renamed or deleted elsewhere (by the agent, or a restore) drops out of the filter.
        if (folderFilter?.startsWith("f:") == true && folderFilter?.removePrefix("f:") !in folderNames) folderFilter = null
    }
    val visible = inSection.filter {
        inFolder(it, folderFilter) &&
            (query.isBlank() || it.text.contains(query.trim(), ignoreCase = true) || it.keywords.contains(query.trim(), ignoreCase = true) ||
                (it.folder ?: builtInLabels[MemoryCategory.fromStored(it.category)].orEmpty()).contains(query.trim(), ignoreCase = true))
    }.sortedWith(compareByDescending<MemoryEntity> { it.pinned }.thenByDescending { it.updatedAt })
    val plans = remember(memories, section) { MemoryConsolidation.plans(entries.filter { section == null || it.section == section }) }
    // Long-press a memory to start selecting; the selection empties itself when its memories go away.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val selecting = selected.isNotEmpty()
    fun toggleSelected(id: String) { selected = if (id in selected) selected - id else selected + id }
    LaunchedEffect(entries) { val ids = entries.mapTo(HashSet()) { it.id }; if (!selected.all { it in ids }) selected = selected.filter { it in ids }.toSet() }
    androidx.activity.compose.BackHandler(enabled = selecting) { selected = emptySet() }
    var moving by remember { mutableStateOf(false) }
    var deletingSelected by remember { mutableStateOf(false) }
    var mapOpen by rememberSaveable { mutableStateOf(false) }
    val collapsed = remember { mutableStateListOf<String>() }
    fun mutate(action: suspend () -> Unit, finished: () -> Unit = {}) {
        if (saving) return
        saving = true; error = null
        scope.launch {
            try { action(); finished() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { saving = false }
        }
    }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.settings_memory_manage), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_memory_count, active, entries.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { adding = true }) { Text(stringResource(R.string.settings_memory_add)) }
            }
            if (entries.isNotEmpty()) MemoryMapEntry(onClick = { mapOpen = true })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = section == null, onClick = { section = null }, label = { Text(stringResource(R.string.memory_all_sections)) })
                MemorySection.entries.forEach { option ->
                    FilterChip(selected = section == option.name, onClick = { section = option.name }, label = { Text(stringResource(sectionLabel(option))) })
                }
            }
            Text(stringResource(R.string.memory_folder_label), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = folderFilter == null, onClick = { folderFilter = null }, label = { Text(stringResource(R.string.memory_folders_all)) })
                MemoryCategory.entries.forEach { category ->
                    val key = "c:${category.name}"
                    FilterChip(selected = folderFilter == key, onClick = { folderFilter = key },
                        label = { Text(stringResource(R.string.memory_folder_chip, builtInLabels.getValue(category), inSection.count { inFolder(it, key) })) })
                }
                folderNames.forEach { name ->
                    val key = "f:$name"
                    FilterChip(selected = folderFilter == key, onClick = { folderFilter = key },
                        label = { Text(stringResource(R.string.memory_folder_chip, name, inSection.count { inFolder(it, key) }), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
                AssistChip(enabled = !saving, onClick = { naming = ""; error = null }, label = { Text(stringResource(R.string.memory_folder_new)) },
                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) })
            }
            folderFilter?.takeIf { it.startsWith("f:") }?.removePrefix("f:")?.let { name ->
                MemoryFolderAgents.allowed(name, folderLimits)?.let { ids ->
                    val names = ids.map { id -> agentSettings.chatAgents.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: id.take(8) }.sorted()
                    Text(stringResource(R.string.memory_folder_agents_row, names.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !saving, onClick = { naming = name; error = null }) { Text(stringResource(R.string.memory_folder_rename)) }
                    OutlinedButton(enabled = !saving, onClick = { limitingFolder = name; error = null }) { Text(stringResource(R.string.memory_folder_agents)) }
                    OutlinedButton(enabled = !saving, onClick = { removingFolder = name; error = null }) {
                        Text(stringResource(R.string.memory_folder_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.settings_memory_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.settings_memory_budget_scaled, MemoryPrompt.approxTokens(MemoryPrompt.MIN_CHARS),
                    MemoryPrompt.approxTokens(MemoryPrompt.LARGE_MAX_CHARS)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Folder dialogs show their own error; showing it here too would repeat it behind the dialog.
    if (naming == null && removingFolder == null && limitingFolder == null) error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
    undo?.let { (deletion, message) ->
        SettingsPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(enabled = !saving, onClick = { mutate({ container.memory.restoreFolder(deletion) }) { undo = null } }) {
                    Text(stringResource(R.string.memory_folder_undo))
                }
            }
        }
    }
    if (plans.isNotEmpty()) SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (plans.any { it.originals.size >= 10 }) R.string.memory_cleanup_suggestion else R.string.memory_consolidate), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.memory_consolidate_desc), style = MaterialTheme.typography.bodySmall)
            plans.forEach { plan ->
                OutlinedButton(enabled = !saving, onClick = { consolidation = plan }) {
                    Text(stringResource(R.string.memory_consolidate_group, plan.originals.size,
                        stringResource(sectionLabel(plan.section)), plan.projectId?.let { projectNames[it] ?: stringResource(R.string.settings_memory_project) } ?: stringResource(R.string.settings_memory_global)))
                }
            }
        }
    }
    consolidation?.let { plan ->
        MemoryConsolidationDialog(plan, MemoryText.maxChars(agentSettings, summary = true), saving, error, onDismiss = { if (!saving) { consolidation = null; error = null } }) { text ->
            mutate({ container.memory.consolidate(plan, text) }) { consolidation = null }
        }
    }

    @Composable
    fun rows(list: List<MemoryEntity>) {
        list.forEachIndexed { index, memory ->
            MemoryRow(
                memory = memory,
                folderLabel = memory.folder ?: builtInLabels.getValue(MemoryCategory.fromStored(memory.category)),
                projectName = memory.projectId?.let { projectNames[it] },
                agentName = agentSettings.chatAgents.firstOrNull { it.id == memory.agentId }?.name,
                onEdit = { if (!saving) { editing = memory; error = null } },
                onPinned = { pinned -> mutate({ container.memory.setPinned(memory.id, pinned) }) },
                onEnabled = { enabled -> mutate({ container.memory.setEnabled(memory.id, enabled) }) },
                onRestore = if (memory.isSummary && memories.any { it.consolidatedInto == memory.id }) ({ mutate({ container.memory.restoreOriginals(memory.id) }) }) else null,
                enabled = !saving,
                selecting = selecting,
                checked = memory.id in selected,
                onToggleSelected = { toggleSelected(memory.id) },
            )
            if (index < list.lastIndex) HorizontalDivider(
                Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f),
            )
        }
    }

    if (visible.isEmpty()) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Text(
                stringResource(when {
                    memories.isEmpty() -> R.string.settings_memory_empty
                    folderFilter != null && query.isBlank() -> R.string.memory_folder_empty
                    else -> R.string.settings_memory_empty_search
                }),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    } else if (folderFilter == null) {
        // "All folders" shows each folder as its own collapsible group instead of one long mixed list.
        val groups = (MemoryCategory.entries.map { "c:${it.name}" } + folderNames.map { "f:$it" })
            .map { key -> key to visible.filter { inFolder(it, key) } }
            .filter { it.second.isNotEmpty() }
        groups.forEach { (key, list) ->
            val name = if (key.startsWith("c:")) builtInLabels.getValue(MemoryCategory.fromStored(key.removePrefix("c:"))) else key.removePrefix("f:")
            val open = key !in collapsed
            val chosen = list.count { it.id in selected }
            SettingsPanel(Modifier.fillMaxWidth()) {
                MemoryGroupHeader(
                    name = name,
                    count = list.size,
                    color = memoryFolderColor(key, folderNames),
                    open = open,
                    selecting = selecting,
                    selection = when (chosen) { 0 -> androidx.compose.ui.state.ToggleableState.Off; list.size -> androidx.compose.ui.state.ToggleableState.On
                        else -> androidx.compose.ui.state.ToggleableState.Indeterminate },
                    onToggleOpen = { if (open) collapsed.add(key) else collapsed.remove(key) },
                    onToggleSelection = {
                        val ids = list.map { it.id }.toSet()
                        selected = if (chosen == list.size) selected - ids else selected + ids
                    },
                )
                androidx.compose.animation.AnimatedVisibility(open,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()) {
                    Column {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                        rows(list)
                    }
                }
            }
        }
    } else {
        SettingsPanel(Modifier.fillMaxWidth()) { rows(visible) }
    }
    // Room for the floating selection toolbar so it never hides the last memory.
    if (selecting) Spacer(Modifier.height(88.dp))

    if (memories.isNotEmpty() && !selecting) {
        TextButton(onClick = { clearing = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_memory_clear_all), color = MaterialTheme.colorScheme.error)
        }
    }

    if (adding || editing != null) {
        MemoryEditDialog(
            initial = editing,
            projects = projects,
            onDismiss = { if (!saving) { adding = false; editing = null; error = null } },
            enabled = !saving,
            error = error,
            settings = agentSettings,
            initialSection = section?.let { MemorySection.fromStored(it) } ?: MemorySection.CHAT,
            folders = folderNames,
            initialFolder = folderFilter?.let { key ->
                if (key.startsWith("c:")) MemoryFolderTarget(MemoryCategory.fromStored(key.removePrefix("c:")), null)
                else MemoryFolderTarget(MemoryCategory.FACT, key.removePrefix("f:"))
            },
            onSave = { draft ->
                val target = editing
                mutate({
                    if (target == null) container.memory.add(draft.text, draft.folder.category, draft.scope, draft.projectId, draft.section,
                        folder = draft.folder.folder, keywords = draft.keywords, triggerOnly = draft.triggerOnly)
                    else container.memory.update(target, draft.text, draft.folder.category, draft.scope, draft.projectId, draft.section,
                        folder = draft.folder.folder, keywords = draft.keywords, triggerOnly = draft.triggerOnly)
                }) { adding = false; editing = null }
            },
            onDelete = {
                editing?.let { deleting = it }
                adding = false
                editing = null
            },
        )
    }

    if (selecting && !moving && !deletingSelected) MemorySelectionToolbar(
        count = selected.size,
        allSelected = visible.isNotEmpty() && visible.all { it.id in selected },
        enabled = !saving,
        onClose = { selected = emptySet() },
        onSelectAll = { selected = if (visible.all { it.id in selected }) emptySet() else selected + visible.map { it.id } },
        onMove = { moving = true; error = null },
        onDelete = { deletingSelected = true; error = null },
    )

    if (moving) MemoryMoveDialog(selected.size, folderNames, builtInLabels, saving, error,
        onDismiss = { if (!saving) { moving = false; error = null } }) { target ->
        mutate({ container.memory.moveMemories(selected, target) }) { moving = false; selected = emptySet() }
    }

    if (deletingSelected) AlertDialog(
        onDismissRequest = { if (!saving) deletingSelected = false },
        title = { Text(pluralStringResource(R.plurals.memory_bulk_delete_title, selected.size, selected.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.memory_bulk_delete_body))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = { mutate({ container.memory.removeMany(selected) }) { deletingSelected = false; selected = emptySet() } }) {
                Text(stringResource(R.string.settings_memory_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = { deletingSelected = false }) { Text(stringResource(R.string.settings_action_cancel)) } },
    )

    if (mapOpen) MemoryMapDialog(
        memories = entries,
        folderNames = folderNames,
        builtInLabels = builtInLabels,
        onDismiss = { mapOpen = false },
        onEdit = { memory -> mapOpen = false; editing = memory; error = null },
    )

    naming?.let { current ->
        MemoryFolderNameDialog(current, saving, error, onDismiss = { if (!saving) { naming = null; error = null } }) { name ->
            mutate({
                val saved = if (current.isEmpty()) container.memory.createFolder(name) else container.memory.renameFolder(current, name)
                folderFilter = "f:$saved"
            }) { naming = null }
        }
    }

    removingFolder?.let { name ->
        val count = entries.count { it.folder == name }
        val deletedMoved = stringResource(R.string.memory_folder_deleted_moved, name)
        val deletedAll = stringResource(R.string.memory_folder_deleted_all, name)
        val deletedEmpty = stringResource(R.string.memory_folder_deleted_empty, name)
        MemoryFolderDeleteDialog(name, count, folderNames.filter { it != name }, builtInLabels, saving, error,
            onDismiss = { if (!saving) { removingFolder = null; error = null } }) { moveTo ->
            mutate({
                val deletion = container.memory.deleteFolder(name, moveTo)
                undo = deletion to when {
                    deletion.before.isEmpty() -> deletedEmpty
                    deletion.moved -> deletedMoved
                    else -> deletedAll
                }
                folderFilter = null
            }) { removingFolder = null }
        }
    }

    limitingFolder?.let { name ->
        MemoryFolderAgentsDialog(name, MemoryFolderAgents.allowed(name, folderLimits).orEmpty(), agentSettings.chatAgents, saving, error,
            onDismiss = { if (!saving) { limitingFolder = null; error = null } }) { ids ->
            mutate({ container.memory.setFolderAgents(name, ids) }) { limitingFolder = null }
        }
    }

    deleting?.let { memory ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.settings_memory_delete_confirm)) },
            text = { Text(stringResource(if (memory.isSummary) R.string.memory_delete_digest_body else R.string.settings_memory_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    mutate({ container.memory.remove(memory.id) }) { deleting = null }
                }) { Text(stringResource(R.string.settings_memory_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.settings_action_cancel)) }
            },
        )
    }

    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(stringResource(R.string.settings_memory_clear_confirm)) },
            text = { Text(stringResource(R.string.settings_memory_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    mutate({ container.memory.clearAll() }) { clearing = false }
                }) { Text(stringResource(R.string.settings_memory_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { clearing = false }) { Text(stringResource(R.string.settings_action_cancel)) }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MemoryRow(
    memory: MemoryEntity,
    folderLabel: String,
    projectName: String?,
    agentName: String? = null,
    onEdit: () -> Unit,
    onPinned: (Boolean) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onRestore: (() -> Unit)? = null,
    enabled: Boolean = true,
    selecting: Boolean = false,
    checked: Boolean = false,
    onToggleSelected: () -> Unit = {},
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val highlight by androidx.compose.animation.animateColorAsState(
        if (checked) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent, label = "memorySelected")
    Column(Modifier.background(highlight)) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(
                enabled = enabled,
                onClick = { if (selecting) onToggleSelected() else onEdit() },
                onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onToggleSelected()
                },
                onLongClickLabel = stringResource(R.string.memory_select),
            )
            .heightIn(min = 64.dp)
            .padding(start = if (selecting) 8.dp else 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selecting) Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                memory.text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                color = if (memory.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOfNotNull(
                    if (memory.agentId == null) stringResource(sectionLabel(MemorySection.fromStored(memory.section))) else
                        stringResource(R.string.chat_agents_private_memory_label) + " · " + (agentName ?: memory.agentId.take(8)),
                    if (memory.isSummary) stringResource(R.string.memory_consolidated) + " · " + folderLabel else folderLabel,
                    if (memory.scope == MemoryScope.PROJECT.name) projectName ?: stringResource(R.string.settings_memory_project)
                    else stringResource(R.string.settings_memory_global),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (memory.keywords.isNotBlank()) Text(
                stringResource(if (memory.triggerOnly) R.string.memory_trigger_only_row else R.string.memory_triggers_row, memory.keywords),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // While selecting, a tap anywhere on the row selects it, so the per-row controls step aside.
        if (!selecting) {
            IconButton(enabled = enabled, onClick = { onPinned(!memory.pinned) }) {
                Icon(
                    if (memory.pinned) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = stringResource(R.string.settings_memory_pin),
                    tint = if (memory.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = memory.enabled, enabled = enabled, onCheckedChange = onEnabled)
        } else if (memory.pinned) Icon(Icons.Rounded.Star, contentDescription = stringResource(R.string.settings_memory_pin),
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp).size(20.dp))
    }
    if (onRestore != null && !selecting) TextButton(enabled = enabled, onClick = onRestore, modifier = Modifier.padding(start = 8.dp)) {
        Text(stringResource(R.string.memory_restore_originals))
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryEditDialog(
    initial: MemoryEntity?,
    projects: List<ProjectEntity>,
    onDismiss: () -> Unit,
    onSave: (MemoryDraft) -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean = true,
    initialSection: MemorySection = MemorySection.CHAT,
    error: String? = null,
    settings: AppSettings = AppSettings(),
    folders: List<String> = emptyList(),
    initialFolder: MemoryFolderTarget? = null,
) {
    var owner by remember(initial?.id) { mutableStateOf(initial?.let { MemorySection.fromStored(it.section) } ?: initialSection) }
    val maxChars = MemoryText.maxChars(settings, summary = initial?.isSummary == true)
    var text by remember(initial?.id) { mutableStateOf(initial?.text.orEmpty()) }
    var folder by remember(initial?.id) { mutableStateOf(initial?.let { MemoryFolderTarget.of(it) } ?: initialFolder ?: MemoryFolderTarget(MemoryCategory.FACT, null)) }
    var keywords by remember(initial?.id) { mutableStateOf(initial?.keywords.orEmpty()) }
    var triggerOnly by remember(initial?.id) { mutableStateOf(initial?.triggerOnly == true) }
    val hasTriggers = keywords.split(',', '\n', ';').any { it.isNotBlank() }
    var scope by remember(initial?.id) { mutableStateOf(MemoryScope.fromStored(initial?.scope)) }
    var projectId by remember(initial?.id, projects) {
        mutableStateOf(initial?.projectId ?: projects.firstOrNull()?.id)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.settings_memory_add_title else R.string.settings_memory_edit_title))
        },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = enabled,
                    isError = maxChars != null && text.length > maxChars,
                    supportingText = { Text(if (maxChars == null) stringResource(R.string.memory_entry_count_unlimited, text.length)
                        else stringResource(R.string.memory_entry_count_limited, text.length, maxChars)) },
                    label = { Text(stringResource(R.string.settings_memory_text_label)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                initial?.let { memory ->
                    val format = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }
                    Text(stringResource(R.string.memory_dates, format.format(java.util.Date(memory.createdAt)), format.format(java.util.Date(memory.updatedAt))),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.memory_section_label), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemorySection.entries.forEach { option ->
                        FilterChip(enabled = enabled && initial?.agentId == null, selected = owner == option, onClick = { owner = option }, label = { Text(stringResource(sectionLabel(option))) })
                    }
                }
                Text(stringResource(R.string.memory_folder_label), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemoryCategory.entries.forEach { option ->
                        FilterChip(
                            enabled = enabled,
                            selected = folder.folder == null && folder.category == option,
                            onClick = { folder = MemoryFolderTarget(option, null) },
                            label = { Text(stringResource(categoryLabel(option.name))) },
                        )
                    }
                    (folders + listOfNotNull(folder.folder)).distinct().forEach { name ->
                        FilterChip(
                            enabled = enabled,
                            selected = folder.folder == name,
                            onClick = { folder = MemoryFolderTarget(folder.category, name) },
                            label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.memory_triggers_label)) },
                    supportingText = { Text(stringResource(R.string.memory_triggers_help)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().toggleable(triggerOnly && hasTriggers, enabled = enabled && hasTriggers, role = Role.Switch,
                        onValueChange = { triggerOnly = it }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.memory_trigger_only), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.memory_trigger_only_desc), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = triggerOnly && hasTriggers, enabled = enabled && hasTriggers, onCheckedChange = null)
                }
                Text(stringResource(R.string.settings_memory_scope_label), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        enabled = enabled,
                        selected = scope == MemoryScope.GLOBAL,
                        onClick = { scope = MemoryScope.GLOBAL },
                        label = { Text(stringResource(R.string.settings_memory_global)) },
                    )
                    projects.forEach { project ->
                        FilterChip(
                            enabled = enabled && initial?.agentId == null,
                            selected = scope == MemoryScope.PROJECT && projectId == project.id,
                            onClick = { scope = MemoryScope.PROJECT; projectId = project.id },
                            label = { Text(project.name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = enabled && text.isNotBlank() && (maxChars == null || text.length <= maxChars),
                onClick = { onSave(MemoryDraft(text, folder, scope, if (scope == MemoryScope.PROJECT) projectId else null, owner, keywords, triggerOnly && hasTriggers)) },
            ) { Text(stringResource(R.string.settings_memory_save)) }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(enabled = enabled, onClick = onDelete) {
                        Text(stringResource(R.string.settings_memory_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) }
            }
        },
    )
}

/** What the memory edit dialog hands back. */
internal data class MemoryDraft(
    val text: String,
    val folder: MemoryFolderTarget,
    val scope: MemoryScope,
    val projectId: String?,
    val section: MemorySection,
    val keywords: String,
    val triggerOnly: Boolean = false,
)

/** Folders the user or agent created, plus any a restored memory names, in creation order. */
@Composable
internal fun rememberMemoryFolderNames(container: AppContainer): List<String> {
    val rows by container.memory.folders.collectAsStateWithLifecycle(initialValue = emptyList())
    val memories by container.memory.memories.collectAsStateWithLifecycle(initialValue = emptyList())
    return remember(rows, memories) {
        (rows.map { it.name } + memories.mapNotNull { it.folder }).distinctBy { it.lowercase() }
    }
}

@Composable
private fun MemoryFolderNameDialog(current: String, saving: Boolean, error: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (current.isEmpty()) R.string.memory_folder_new else R.string.memory_folder_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = !saving,
                    label = { Text(stringResource(R.string.memory_folder_name)) },
                    singleLine = true,
                    isError = name.length > com.androidharness.app.data.MemoryFolders.MAX_NAME,
                    supportingText = { Text("${name.length} / ${com.androidharness.app.data.MemoryFolders.MAX_NAME}") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && name.isNotBlank() && name.trim() != current, onClick = { onSave(name) }) {
                Text(stringResource(if (current.isEmpty()) R.string.memory_folder_create else R.string.settings_memory_save))
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}

/** Picks which chat agents may see a folder. An empty choice opens it to everyone. */
@Composable
private fun MemoryFolderAgentsDialog(
    name: String,
    current: Set<String>,
    agents: List<com.androidharness.app.data.ChatAgentProfile>,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    var limited by remember(name) { mutableStateOf(current.isNotEmpty()) }
    // Agents deleted since the limit was set stay selected, so saving never widens the folder by surprise.
    var chosen by remember(name) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_folder_agents_title, name)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(false, true).forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !saving) { limited = option }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = limited == option, onClick = null, enabled = !saving)
                        Text(stringResource(if (option) R.string.memory_folder_agents_only else R.string.memory_folder_agents_everyone),
                            Modifier.padding(start = 8.dp))
                    }
                }
                if (limited) {
                    if (agents.isEmpty()) Text(stringResource(R.string.memory_folder_agents_none), Modifier.padding(start = 40.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    agents.forEach { agent ->
                        Row(
                            Modifier.fillMaxWidth().toggleable(agent.id in chosen, enabled = !saving, role = Role.Checkbox,
                                onValueChange = { on -> chosen = if (on) chosen + agent.id else chosen - agent.id })
                                .padding(start = 32.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = agent.id in chosen, onCheckedChange = null, enabled = !saving)
                            Text(agent.name.ifBlank { agent.id.take(8) }, Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(stringResource(if (chosen.isEmpty() && agents.isNotEmpty()) R.string.memory_folder_agents_pick else R.string.memory_folder_agents_note),
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                        color = if (chosen.isEmpty() && agents.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && (!limited || chosen.isNotEmpty()), onClick = { onSave(if (limited) chosen else emptySet()) }) {
                Text(stringResource(R.string.settings_memory_save))
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}

/** Deleting a folder asks where its memories go; nothing is removed without that choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryFolderDeleteDialog(
    name: String,
    count: Int,
    others: List<String>,
    builtInLabels: Map<MemoryCategory, String>,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onDelete: (MemoryFolderTarget?) -> Unit,
) {
    // null deletes the memories too.
    var destination by remember(name) { mutableStateOf<MemoryFolderTarget?>(MemoryFolderTarget(MemoryCategory.FACT, null)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_folder_delete_title, name)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (count == 0) Text(stringResource(R.string.memory_folder_delete_empty))
                else {
                    Text(pluralStringResource(R.plurals.memory_folder_delete_count, count, count))
                    Text(stringResource(R.string.memory_folder_delete_move), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MemoryCategory.entries.forEach { category ->
                            val option = MemoryFolderTarget(category, null)
                            FilterChip(enabled = !saving, selected = destination == option, onClick = { destination = option },
                                label = { Text(builtInLabels.getValue(category)) })
                        }
                        others.forEach { other ->
                            val option = MemoryFolderTarget(MemoryCategory.FACT, other)
                            FilterChip(enabled = !saving, selected = destination == option, onClick = { destination = option },
                                label = { Text(other, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !saving) { destination = null }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = destination == null, onClick = null, enabled = !saving)
                        Text(stringResource(R.string.memory_folder_delete_with_memories), Modifier.padding(start = 8.dp),
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = { onDelete(if (count == 0) null else destination) }) {
                Text(stringResource(R.string.memory_folder_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}

private fun categoryLabel(category: String): Int = when (MemoryCategory.fromStored(category)) {
    MemoryCategory.PROFILE -> R.string.settings_memory_category_profile
    MemoryCategory.PREFERENCE -> R.string.settings_memory_category_preference
    MemoryCategory.PROJECT -> R.string.settings_memory_category_project
    MemoryCategory.DECISION -> R.string.settings_memory_category_decision
    MemoryCategory.FACT -> R.string.settings_memory_category_fact
}

@Composable
private fun MemoryPreferenceRow(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().settingsAnchor(title).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

internal fun sectionLabel(section: MemorySection): Int = when (section) {
    MemorySection.CHAT -> R.string.memory_section_chat
    MemorySection.CODE -> R.string.memory_section_code
    MemorySection.SHARED -> R.string.memory_shared
}

@Composable
private fun MemoryConsolidationDialog(plan: MemoryConsolidation, maxChars: Int?, saving: Boolean, error: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(plan) { mutableStateOf(plan.text) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.memory_consolidate)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.memory_consolidate_review, plan.originals.size))
                OutlinedTextField(value = text, onValueChange = { text = it }, enabled = !saving,
                    label = { Text(stringResource(R.string.memory_review_digest)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 10,
                    isError = maxChars != null && text.length > maxChars)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !saving && text.isNotBlank() && (maxChars == null || text.length <= maxChars), onClick = { onSave(text) }) { Text(stringResource(R.string.settings_memory_save)) } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } })
}
