package com.androidharness.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.SettingsRepository
import com.androidharness.app.local.*
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.theme.HarnessGlassModalBottomSheet as ModalBottomSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Memory presets in context tokens; each one splits 3:1 between conversation and reply. */
private val memoryPresets = listOf(2048, 4096, 8192)

/**
 * One model's settings as a sheet of plain choices. Everyday options (memory,
 * speed boost, thinking, processor) are one tap each; exact token and thread
 * numbers wait under Fine-tune for people who want them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LocalModelSettingsSheet(model: LocalModelSpec, manager: LocalModelManager, onDismiss: () -> Unit) {
    val uiContext = LocalContext.current
    var saved by remember { mutableStateOf<LocalModelLimits?>(null) }
    LaunchedEffect(model.id) { saved = withContext(Dispatchers.IO) { manager.limits(model.id) } }
    val limits = saved ?: return
    val device = remember { manager.device() }
    val defaultThreads = device.cores.coerceIn(1, 4)

    var context by remember { mutableStateOf(limits.context.toString()) }
    var input by remember { mutableStateOf(limits.input.toString()) }
    var output by remember { mutableStateOf(limits.output.toString()) }
    var threads by remember { mutableStateOf(limits.threads.toString()) }
    var promptThreads by remember { mutableStateOf((limits.promptThreads ?: limits.threads).toString()) }
    var backend by remember { mutableStateOf(limits.backend) }
    var thinkingMode by remember { mutableStateOf(limits.thinkingMode) }
    var speculative by remember { mutableStateOf(limits.speculative) }
    var fineTune by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val experimental by remember(uiContext) { SettingsRepository(uiContext) }.settings
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    var gpuAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { gpuAvailable = manager.gpuDevice().isNotBlank() }

    val presets = remember(model.maxContext) { memoryPresets.map { minOf(it, model.maxContext) }.distinct() }
    fun applyPreset(size: Int) {
        context = size.toString()
        input = (size * 3 / 4).coerceAtLeast(1).toString()
        output = (size / 4).coerceAtLeast(1).toString()
    }
    val contextValue = context.toIntOrNull()
    val activePreset = presets.firstOrNull { size ->
        contextValue == size && input.toIntOrNull() == (size * 3 / 4).coerceAtLeast(1) && output.toIntOrNull() == (size / 4).coerceAtLeast(1)
    }
    val ramContext = contextValue?.coerceAtLeast(1) ?: limits.context
    val fits = device.fits(model, ramContext)

    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                    SettingsIconBadge(Icons.Rounded.Tune, SettingsTone.PRIMARY, size = 48.dp, shape = settingsPageShape(model.id.hashCode()))
                    Column(Modifier.weight(1f)) {
                        Text(model.title, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 2)
                        Text(stringResource(R.string.lms_applies_next_reply), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Memory: how much of the chat the model can keep in mind.
                SettingsSectionLabel(stringResource(R.string.lms_memory_title), top = 4.dp)
                SettingsGroup {
                    item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.lms_memory_help), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val labels = listOf(R.string.lms_memory_short, R.string.lms_memory_medium, R.string.lms_memory_long)
                            SettingsChoiceGroup(
                                options = presets.mapIndexed { i, size -> size to stringResource(labels[minOf(i, labels.lastIndex)]) },
                                selected = { it == activePreset }, onSelect = { applyPreset(it); error = null }, enabled = !saving,
                            )
                            if (activePreset == null) Text(stringResource(R.string.lms_memory_custom), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsStatusPill(stringResource(R.string.lms_memory_ram, size(model.estimatedMemory(ramContext))), ok = fits)
                                if (!fits) Text(stringResource(R.string.lms_memory_too_much), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                // Speed boost: draftless n-gram speculative decoding.
                SettingsSectionLabel(stringResource(R.string.lms_speed_title))
                val boostOn = speculative != LocalSpeculativeMode.OFF
                SettingsGroup {
                    item(onClick = { if (!saving) speculative = if (boostOn) LocalSpeculativeMode.OFF else LocalSpeculativeMode.NGRAM_SIMPLE }) {
                        SettingsItemRow(
                            title = stringResource(R.string.lms_boost_title),
                            subtitle = stringResource(R.string.lms_boost_help),
                            leading = { SettingsIconBadge(Icons.Rounded.Bolt, SettingsTone.TERTIARY) },
                        ) {
                            Switch(boostOn, { speculative = if (it) LocalSpeculativeMode.NGRAM_SIMPLE else LocalSpeculativeMode.OFF }, enabled = !saving)
                        }
                    }
                    if (experimental.localModelExperimental) item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                SettingsIconBadge(Icons.Rounded.Memory, SettingsTone.SECONDARY)
                                Text(stringResource(R.string.lms_processor_title), style = MaterialTheme.typography.titleMedium)
                            }
                            val choices = buildList {
                                add(LocalBackend.AUTO to stringResource(R.string.local_gpu_auto))
                                add(LocalBackend.CPU to "CPU")
                                if (gpuAvailable || backend == LocalBackend.GPU) add(LocalBackend.GPU to "GPU")
                            }
                            SettingsChoiceGroup(choices, { it == backend }, { backend = it }, enabled = !saving)
                            Text(stringResource(when (backend) {
                                LocalBackend.AUTO -> R.string.lms_processor_auto
                                LocalBackend.CPU -> R.string.lms_processor_cpu
                                LocalBackend.GPU -> R.string.lms_processor_gpu
                            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Thinking: whether the model reasons before answering.
                SettingsSectionLabel(stringResource(R.string.lms_thinking_title))
                SettingsGroup {
                    item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsChoiceGroup(
                                options = listOf(
                                    LocalThinkingMode.AUTO to stringResource(R.string.lms_thinking_auto),
                                    LocalThinkingMode.THINKING to stringResource(R.string.lms_thinking_always),
                                    LocalThinkingMode.DIRECT to stringResource(R.string.lms_thinking_never),
                                ),
                                selected = { it == thinkingMode }, onSelect = { thinkingMode = it }, enabled = !saving,
                            )
                            Text(stringResource(when (thinkingMode) {
                                LocalThinkingMode.AUTO -> R.string.lms_thinking_auto_help
                                LocalThinkingMode.THINKING -> R.string.lms_thinking_always_help
                                LocalThinkingMode.DIRECT -> R.string.lms_thinking_never_help
                            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Fine-tune: the exact numbers behind the choices above.
                Spacer(Modifier.height(4.dp))
                val arrow by animateFloatAsState(if (fineTune) 180f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "fineTuneArrow")
                SettingsGroup {
                    item(onClick = { fineTune = !fineTune }, selected = fineTune) {
                        SettingsItemRow(
                            title = stringResource(R.string.lms_fine_tune_title),
                            subtitle = stringResource(R.string.lms_fine_tune_help),
                            leading = { SettingsIconBadge(Icons.Rounded.Tune, SettingsTone.NEUTRAL) },
                        ) { Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(arrow)) }
                    }
                }
                AnimatedVisibility(fineTune, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SettingsNumberField(stringResource(R.string.lms_field_context), context, stringResource(R.string.settings_local_models_tokens_unit),
                            stringResource(R.string.lms_field_context_help), !saving) { context = it }
                        SettingsNumberField(stringResource(R.string.lms_field_input), input, stringResource(R.string.settings_local_models_tokens_unit),
                            stringResource(R.string.lms_field_input_help), !saving) { input = it }
                        SettingsNumberField(stringResource(R.string.lms_field_output), output, stringResource(R.string.settings_local_models_tokens_unit),
                            stringResource(R.string.lms_field_output_help), !saving) { output = it }
                        SettingsNumberField(stringResource(R.string.lms_field_threads), threads, stringResource(R.string.lms_unit_cores),
                            stringResource(R.string.lms_field_threads_help), !saving) { threads = it }
                        SettingsNumberField(stringResource(R.string.lms_field_prompt_threads), promptThreads, stringResource(R.string.lms_unit_cores),
                            stringResource(R.string.lms_field_prompt_threads_help), !saving) { promptThreads = it }
                        if (boostOn) {
                            Text(stringResource(R.string.lms_boost_method), style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                            SettingsChoiceGroup(
                                options = listOf(
                                    LocalSpeculativeMode.NGRAM_SIMPLE to stringResource(R.string.lms_boost_simple),
                                    LocalSpeculativeMode.NGRAM_MOD to stringResource(R.string.lms_boost_mod),
                                ),
                                selected = { it == speculative }, onSelect = { speculative = it }, enabled = !saving,
                            )
                            Text(stringResource(if (speculative == LocalSpeculativeMode.NGRAM_MOD) R.string.lms_boost_mod_help else R.string.lms_boost_simple_help),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(4.dp)) }
                Spacer(Modifier.height(8.dp))
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !saving, onClick = {
                    applyPreset(model.defaultContext)
                    threads = defaultThreads.toString()
                    promptThreads = threads
                    backend = LocalBackend.AUTO
                    thinkingMode = LocalThinkingMode.AUTO
                    speculative = LocalSpeculativeMode.OFF
                    error = null
                }) { Text(stringResource(R.string.settings_action_reset_defaults)) }
                Spacer(Modifier.weight(1f))
                TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(enabled = !saving, onClick = {
                    scope.launch {
                        saving = true
                        try {
                            manager.saveLimits(model.id, LocalModelLimits(context.toIntOrNull() ?: 0, input.toIntOrNull() ?: 0,
                                output.toIntOrNull() ?: 0, threads.toIntOrNull() ?: 0, backend, promptThreads.toIntOrNull() ?: 0,
                                thinkingMode, speculative))
                            onDismiss()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message; fineTune = true }
                        finally { saving = false }
                    }
                }) { Text(stringResource(R.string.settings_action_save)) }
            }
        }
    }
}

@Composable
private fun SettingsNumberField(label: String, value: String, unit: String, help: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, { new -> onChange(new.filter(Char::isDigit)) }, label = { Text(label) }, suffix = { Text(unit) },
        supportingText = { Text(help) }, singleLine = true, enabled = enabled, shape = MaterialTheme.shapes.large,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}
