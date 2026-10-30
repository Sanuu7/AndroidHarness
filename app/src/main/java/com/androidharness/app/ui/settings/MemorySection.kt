package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.MemoryCategory
import com.androidharness.app.data.MemoryPrompt
import com.androidharness.app.data.MemorySection
import com.androidharness.app.data.MemoryConsolidation
import com.androidharness.app.data.MemoryScope
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
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
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
    val entries = memories.filter { it.consolidatedInto == null }
    val active = entries.count { it.enabled }
    val visible = entries.filter {
        (section == null || it.section == section) &&
            (query.isBlank() || it.text.contains(query.trim(), ignoreCase = true) || it.category.contains(query.trim(), ignoreCase = true))
    }.sortedWith(compareByDescending<MemoryEntity> { it.pinned }.thenByDescending { it.updatedAt })
    val plans = remember(memories, section) { MemoryConsolidation.plans(entries.filter { section == null || it.section == section }) }
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = section == null, onClick = { section = null }, label = { Text(stringResource(R.string.memory_all_sections)) })
                MemorySection.entries.forEach { option ->
                    FilterChip(selected = section == option.name, onClick = { section = option.name }, label = { Text(stringResource(sectionLabel(option))) })
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
                stringResource(R.string.settings_memory_budget, MemoryPrompt.approxTokens(MemoryPrompt.MAX_CHARS)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
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
        MemoryConsolidationDialog(plan, saving, error, onDismiss = { if (!saving) { consolidation = null; error = null } }) { text ->
            mutate({ container.memory.consolidate(plan, text) }) { consolidation = null }
        }
    }

    if (visible.isEmpty()) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Text(
                stringResource(if (memories.isEmpty()) R.string.settings_memory_empty else R.string.settings_memory_empty_search),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    } else {
        SettingsPanel(Modifier.fillMaxWidth()) {
            visible.forEachIndexed { index, memory ->
                MemoryRow(
                    memory = memory,
                    projectName = memory.projectId?.let { projectNames[it] },
                    agentName = agentSettings.chatAgents.firstOrNull { it.id == memory.agentId }?.name,
                    onEdit = { if (!saving) { editing = memory; error = null } },
                    onPinned = { pinned -> mutate({ container.memory.setPinned(memory.id, pinned) }) },
                    onEnabled = { enabled -> mutate({ container.memory.setEnabled(memory.id, enabled) }) },
                    onRestore = if (memory.isSummary && memories.any { it.consolidatedInto == memory.id }) ({ mutate({ container.memory.restoreOriginals(memory.id) }) }) else null,
                    enabled = !saving,
                )
                if (index < visible.lastIndex) HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f),
                )
            }
        }
    }

    if (memories.isNotEmpty()) {
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
            initialSection = section?.let { MemorySection.fromStored(it) } ?: MemorySection.CHAT,
            onSave = { text, category, memoryScope, projectId, owner ->
                val target = editing
                mutate({
                    if (target == null) container.memory.add(text, category, memoryScope, projectId, owner)
                    else container.memory.update(target, text, category, memoryScope, projectId, owner)
                }) { adding = false; editing = null }
            },
            onDelete = {
                editing?.let { deleting = it }
                adding = false
                editing = null
            },
        )
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

@Composable
private fun MemoryRow(
    memory: MemoryEntity,
    projectName: String?,
    agentName: String? = null,
    onEdit: () -> Unit,
    onPinned: (Boolean) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onRestore: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Column {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onEdit).heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
                    stringResource(if (memory.isSummary) R.string.memory_consolidated else categoryLabel(memory.category)),
                    if (memory.scope == MemoryScope.PROJECT.name) projectName ?: stringResource(R.string.settings_memory_project)
                    else stringResource(R.string.settings_memory_global),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(enabled = enabled, onClick = { onPinned(!memory.pinned) }) {
            Icon(
                if (memory.pinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = stringResource(R.string.settings_memory_pin),
                tint = if (memory.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = memory.enabled, enabled = enabled, onCheckedChange = onEnabled)
    }
    if (onRestore != null) TextButton(enabled = enabled, onClick = onRestore, modifier = Modifier.padding(start = 8.dp)) {
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
    onSave: (String, MemoryCategory, MemoryScope, String?, MemorySection) -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean = true,
    initialSection: MemorySection = MemorySection.CHAT,
    error: String? = null,
) {
    var owner by remember(initial?.id) { mutableStateOf(initial?.let { MemorySection.fromStored(it.section) } ?: initialSection) }
    val maxChars = if (initial?.isSummary == true) MemoryConsolidation.MAX_CHARS else 600
    var text by remember(initial?.id) { mutableStateOf(initial?.text.orEmpty()) }
    var category by remember(initial?.id) { mutableStateOf(MemoryCategory.fromStored(initial?.category)) }
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
                    isError = text.length > maxChars,
                    supportingText = { Text("${text.length}/$maxChars") },
                    label = { Text(stringResource(R.string.settings_memory_text_label)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.memory_section_label), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemorySection.entries.forEach { option ->
                        FilterChip(enabled = enabled && initial?.agentId == null, selected = owner == option, onClick = { owner = option }, label = { Text(stringResource(sectionLabel(option))) })
                    }
                }
                Text(stringResource(R.string.settings_memory_category_label), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemoryCategory.entries.forEach { option ->
                        FilterChip(
                            enabled = enabled,
                            selected = category == option,
                            onClick = { category = option },
                            label = { Text(stringResource(categoryLabel(option.name))) },
                        )
                    }
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
                enabled = enabled && text.isNotBlank() && text.length <= maxChars,
                onClick = { onSave(text, category, scope, if (scope == MemoryScope.PROJECT) projectId else null, owner) },
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

private fun categoryLabel(category: String): Int = when (MemoryCategory.fromStored(category)) {
    MemoryCategory.PROFILE -> R.string.settings_memory_category_profile
    MemoryCategory.PREFERENCE -> R.string.settings_memory_category_preference
    MemoryCategory.PROJECT -> R.string.settings_memory_category_project
    MemoryCategory.DECISION -> R.string.settings_memory_category_decision
    MemoryCategory.FACT -> R.string.settings_memory_category_fact
}

@Composable
private fun MemoryPreferenceRow(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
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
private fun MemoryConsolidationDialog(plan: MemoryConsolidation, saving: Boolean, error: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(plan) { mutableStateOf(plan.text) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.memory_consolidate)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.memory_consolidate_review, plan.originals.size))
                OutlinedTextField(value = text, onValueChange = { text = it }, enabled = !saving,
                    label = { Text(stringResource(R.string.memory_review_digest)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 10,
                    isError = text.length > MemoryConsolidation.MAX_CHARS)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !saving && text.isNotBlank() && text.length <= MemoryConsolidation.MAX_CHARS, onClick = { onSave(text) }) { Text(stringResource(R.string.settings_memory_save)) } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } })
}
