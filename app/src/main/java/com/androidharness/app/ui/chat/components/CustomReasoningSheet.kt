package com.androidharness.app.ui.chat.components

import com.androidharness.app.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.*
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ReasoningMapping
import com.androidharness.app.llm.ReasoningDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomReasoningSheet(
    config: ProviderConfig,
    saved: CustomReasoningConfig?,
    spec: ThinkingSpecs.Spec,
    onDismiss: () -> Unit,
    onSave: (CustomReasoningConfig?) -> Unit,
    selectedLevel: String? = null,
    onDetectLevels: (suspend () -> List<String>)? = null,
    startAdding: Boolean = false,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val defaults = remember(spec) {
        CustomReasoning.defaultLevels(spec)
    }
    val initialLevels = saved?.levels ?: defaults
    var levels by rememberSaveable(config.id, config.model) {
        mutableStateOf(ArrayList(initialLevels + if (startAdding && initialLevels.size < 16) listOf("") else emptyList()))
    }
    var levelsEdited by rememberSaveable(config.id, config.model) { mutableStateOf(startAdding) }
    var detecting by remember { mutableStateOf(false) }
    var detectionMessage by remember { mutableStateOf<String?>(null) }
    var detectionFailed by remember { mutableStateOf(false) }
    LaunchedEffect(defaults) {
        if (saved == null && !levelsEdited && !detecting) levels = ArrayList(defaults)
    }
    var editingLevel by rememberSaveable { mutableStateOf<Int?>(if (startAdding && initialLevels.size < 16) initialLevels.size else null) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var showLevelHelp by remember { mutableStateOf(false) }
    val levelFocus = remember { FocusRequester() }
    LaunchedEffect(editingLevel) { if (editingLevel != null) levelFocus.requestFocus() }
    var mapping by rememberSaveable(config.id, config.model) { mutableStateOf(saved?.mapping.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var previewLevel by rememberSaveable {
        mutableIntStateOf(saved?.levels?.indexOfFirst { CustomReasoning.wire(it) == selectedLevel?.let(CustomReasoning::wire) }?.takeIf { it >= 0 } ?: 0)
    }
    var validating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val json = remember { Json { prettyPrint = true } }
    val requestConfig = remember(config) { com.androidharness.app.llm.OpenAiCompatProvider.requestConfig(ThinkingSpecs.routedConfig(config), hasTools = true) }
    val inheritedMapping = remember(requestConfig, spec) { ReasoningDefaults.inherited(requestConfig, spec) }
    val effectiveMapping = mapping.trim().takeIf { it.isNotEmpty() } ?: inheritedMapping
    val localControl = ReasoningDefaults.isLocal(config)
    LaunchedEffect(effectiveMapping, levels.toList(), previewLevel) {
        preview = null
        if (effectiveMapping != null && levels.isNotEmpty()) {
            delay(300)
            val name = levels[previewLevel.coerceIn(levels.indices)]
            preview = withContext(Dispatchers.Default) {
                runCatching { json.encodeToString(JsonObject.serializer(), ReasoningMapping.evaluate(effectiveMapping, name)) }
                    .getOrElse { it.message ?: uiContext.getString(R.string.ui_invalid_mapping) }
            }
        }
    }
    fun editLevels(change: MutableList<String>.() -> Unit) {
        levelsEdited = true
        detectionMessage = null
        levels = ArrayList(levels.toMutableList().apply(change)); error = null
    }
    if (showLevelHelp) AlertDialog(onDismissRequest = { showLevelHelp = false },
        title = { Text(uiContext.getString(R.string.ui_about_reasoning_levels)) }, text = { Text(CustomReasoning.LEVEL_HELP) },
        confirmButton = { TextButton(onClick = { showLevelHelp = false }) { Text(uiContext.getString(R.string.settings_action_got_it)) } })
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(uiContext.getString(R.string.ui_customize_thinking), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, uiContext.getString(R.string.ui_close_thinking_settings)) }
            }
            Text("${config.name} · ${config.model}", maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Text(uiContext.getString(R.string.ui_changes_apply_to_your_next_message),
                Modifier.padding(top = 6.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(uiContext.getString(R.string.ui_reasoning_levels), style = MaterialTheme.typography.titleSmall)
                        Text(uiContext.getString(R.string.ui_lowest_effort_first), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { showLevelHelp = true }) { Icon(Icons.Outlined.Info, uiContext.getString(R.string.ui_about_reasoning_levels), Modifier.size(20.dp)) }
                }
                if (onDetectLevels != null && !localControl) {
                    TextButton(enabled = !validating && !detecting, onClick = {
                        detecting = true; levelsEdited = true; detectionMessage = null; detectionFailed = false
                        scope.launch {
                            try {
                                val detected = CustomReasoning.normalized(CustomReasoningConfig(onDetectLevels())).levels
                                val selected = levels.getOrNull(previewLevel)?.let(CustomReasoning::wire)
                                levelsEdited = true; levels = ArrayList(detected); editingLevel = null
                                previewLevel = detected.indexOfFirst { CustomReasoning.wire(it) == selected }.coerceAtLeast(0)
                                error = null
                                detectionMessage = uiContext.getString(R.string.ui_detected_levels_save_to_apply, (detected.size).toString())
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e
                            } catch (e: Exception) {
                                detectionFailed = true
                                detectionMessage = e.message ?: uiContext.getString(R.string.ui_could_not_detect_levels_try_again)
                            } finally { detecting = false }
                        }
                    }) {
                        if (detecting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (detecting) uiContext.getString(R.string.ui_checking_provider) else uiContext.getString(R.string.ui_auto_detect_levels))
                    }
                    detectionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (detectionFailed) scheme.error else scheme.primary) }
                }
                if (saved == null && !levelsEdited && spec.reportedLevels != null) Text(uiContext.getString(R.string.ui_detected_from_provider),
                    style = MaterialTheme.typography.bodySmall, color = scheme.primary)
                Surface(shape = MaterialTheme.shapes.large, color = scheme.surfaceContainerLow) {
                  Column {
                    levels.forEachIndexed { index, name ->
                        var actions by remember(index) { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .clickable(enabled = !validating && !detecting) { editingLevel = index }
                            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", Modifier.width(28.dp).clearAndSetSemantics {}, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                            if (editingLevel == index) {
                                OutlinedTextField(name, { value -> editLevels { set(index, value.take(32)) } },
                                    modifier = Modifier.weight(1f).focusRequester(levelFocus)
                                        .semantics { contentDescription = uiContext.getString(R.string.ui_level_name, (index + 1).toString()) }, singleLine = true,
                                    enabled = !validating && !detecting, label = { Text(uiContext.getString(R.string.ui_level_name_416)) })
                                IconButton(onClick = { editingLevel = null }) { Icon(Icons.Outlined.Check, uiContext.getString(R.string.ui_done_editing_level), Modifier.size(20.dp)) }
                            } else Text(name.takeIf { it.isNotBlank() }?.let(CustomReasoning::label) ?: uiContext.getString(R.string.ui_new_level),
                                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Box {
                                IconButton(onClick = { actions = true }, enabled = !validating && !detecting) {
                                    Icon(Icons.Outlined.MoreVert, uiContext.getString(R.string.ui_level_actions, (index + 1).toString()), Modifier.size(20.dp))
                                }
                                DropdownMenu(actions, { actions = false }) {
                                    DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_rename)) }, onClick = { actions = false; editingLevel = index })
                                    DropdownMenuItem(text = { Text(uiContext.getString(R.string.settings_action_move_up)) }, enabled = index > 0,
                                        leadingIcon = { Icon(Icons.Outlined.ArrowUpward, null) }, onClick = {
                                            actions = false; editLevels { java.util.Collections.swap(this, index, index - 1) }; editingLevel = null
                                        })
                                    DropdownMenuItem(text = { Text(uiContext.getString(R.string.settings_action_move_down)) }, enabled = index < levels.lastIndex,
                                        leadingIcon = { Icon(Icons.Outlined.ArrowDownward, null) }, onClick = {
                                            actions = false; editLevels { java.util.Collections.swap(this, index, index + 1) }; editingLevel = null
                                        })
                                    DropdownMenuItem(text = { Text(uiContext.getString(R.string.settings_action_remove), color = scheme.error) },
                                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = scheme.error) }, onClick = {
                                            actions = false; editLevels { removeAt(index) }; editingLevel = null
                                        })
                                }
                            }
                        }
                        if (index < levels.lastIndex) HorizontalDivider(Modifier.padding(start = 42.dp, end = 14.dp), color = scheme.outlineVariant.copy(alpha = .5f))
                    }
                    TextButton(onClick = {
                        editLevels { add("") }; editingLevel = levels.lastIndex
                    }, enabled = !validating && !detecting && levels.size < 16) {
                        Icon(Icons.Filled.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(uiContext.getString(R.string.ui_add_level))
                    }
                  }
                }
                Text(uiContext.getString(R.string.ui_tap_a_name_to_edit_only_add),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Surface(modifier = Modifier.semantics {
                    contentDescription = uiContext.getString(R.string.ui_advanced_request_mapping)
                    stateDescription = if (advancedExpanded) uiContext.getString(R.string.ui_expanded) else uiContext.getString(R.string.ui_collapsed)
                }, shape = MaterialTheme.shapes.large, color = scheme.surfaceContainerLow,
                    onClick = { advancedExpanded = !advancedExpanded }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Code, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(uiContext.getString(R.string.ui_advanced_request_mapping), style = MaterialTheme.typography.titleSmall)
                            Text(if (mapping.isBlank()) uiContext.getString(R.string.ui_optional_provider_defaults) else uiContext.getString(R.string.ui_custom_cel_mapping_is_active),
                                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                        Icon(if (advancedExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                            null)
                    }
                }
                if (advancedExpanded) {
                Text(uiContext.getString(R.string.ui_reasoning_parameter_mapping_optional), style = MaterialTheme.typography.titleSmall)
                Text(uiContext.getString(R.string.ui_leave_blank_to_inherit_this_model_provider),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Text(uiContext.getString(R.string.ui_reasoninglevel_is_lowercase_for_example_disabled_low),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                OutlinedTextField(mapping, { mapping = it.take(8_192); error = null },
                    Modifier.fillMaxWidth().semantics { contentDescription = uiContext.getString(R.string.ui_cel_expression_optional) },
                    minLines = 4, maxLines = 8, enabled = !validating && !localControl,
                    placeholder = { Text(inheritedMapping ?: if (localControl) uiContext.getString(R.string.ui_native_on_off_control) else uiContext.getString(R.string.ui_no_inherited_mapping_enter_a_cel_expression),
                        maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                Text(when {
                    localControl -> uiContext.getString(R.string.ui_uses_native_on_off_control_on_this)
                    mapping.isNotBlank() -> uiContext.getString(R.string.ui_using_custom_cel_mapping)
                    inheritedMapping != null -> uiContext.getString(R.string.ui_using_inherited_model_provider_mapping)
                    else -> uiContext.getString(R.string.ui_no_inherited_mapping_is_available_a_custom)
                }, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                if (inheritedMapping != null) TextButton(onClick = {
                    mapping = if (mapping.isBlank()) inheritedMapping else ""; error = null
                }, enabled = !validating) {
                    Text(if (mapping.isBlank()) uiContext.getString(R.string.ui_edit_inherited_mapping) else uiContext.getString(R.string.ui_use_inherited_mapping))
                }
                if (effectiveMapping != null && levels.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(uiContext.getString(R.string.ui_request_fields_preview), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        var showLevels by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { showLevels = true }) { Text(levels[previewLevel.coerceIn(levels.indices)].ifBlank { uiContext.getString(R.string.ui_unnamed) }) }
                            DropdownMenu(showLevels, { showLevels = false }) {
                                levels.forEachIndexed { i, label -> DropdownMenuItem(text = { Text(label.ifBlank { uiContext.getString(R.string.ui_unnamed) }) },
                                    onClick = { previewLevel = i; showLevels = false }) }
                            }
                        }
                    }
                    Surface(shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerHigh) {
                        Text(preview ?: uiContext.getString(R.string.ui_checking_expression), Modifier.fillMaxWidth().padding(12.dp),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                }
                Spacer(Modifier.height(4.dp))
            }
            error?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = scheme.error) }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                val stacked = maxWidth < 300.dp * LocalDensity.current.fontScale
                val saveAction: () -> Unit = {
                    val draft = CustomReasoningConfig(levels.toList(), mapping)
                    scope.launch {
                        validating = true
                        val result = withContext(Dispatchers.Default) { runCatching { ReasoningMapping.validate(draft, requestConfig, spec) } }
                        validating = false
                        result.onSuccess(onSave).onFailure {
                            error = it.message ?: uiContext.getString(R.string.ui_check_your_reasoning_settings)
                            if (error.orEmpty().contains("mapping", ignoreCase = true) || error.orEmpty().contains("CEL")) advancedExpanded = true
                        }
                    }
                }
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (!stacked) TextButton(onClick = { onSave(null) }, enabled = !validating && !detecting) { Text(uiContext.getString(R.string.ui_use_defaults)) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss, enabled = !validating) { Text(uiContext.getString(R.string.settings_action_cancel), maxLines = 1) }
                        Button(enabled = !validating && !detecting, onClick = saveAction) { Text(if (validating) uiContext.getString(R.string.ui_checking) else uiContext.getString(R.string.settings_action_save), maxLines = 1) }
                    }
                    if (stacked) TextButton(onClick = { onSave(null) }, enabled = !validating && !detecting) { Text(uiContext.getString(R.string.ui_use_defaults)) }
                }
            }
        }
    }
}
