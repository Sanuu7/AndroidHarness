package com.androidharness.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.RUMBLE_MAX_SPACING_MS
import com.androidharness.app.data.RUMBLE_MIN_SPACING_MS
import com.androidharness.app.data.RumbleCue
import com.androidharness.app.data.RumbleCueConfig
import com.androidharness.app.data.RumbleEngine
import com.androidharness.app.data.RumblePattern
import com.androidharness.app.data.SettingsRepository
import com.androidharness.app.data.configFor
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.RadioButton
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.common.deviceHasVibrator
import com.androidharness.app.ui.common.deviceSupportsSystemHaptics
import com.androidharness.app.ui.common.rememberRumblePreview
import com.androidharness.app.ui.common.rumbleStyle
import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import androidx.compose.material3.Icon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@StringRes
private fun RumblePattern.label() = when (this) {
    RumblePattern.OFF -> R.string.rumble_pattern_off
    RumblePattern.SOFT_TICK -> R.string.rumble_pattern_soft_tick
    RumblePattern.TICK -> R.string.rumble_pattern_tick
    RumblePattern.CLICK -> R.string.rumble_pattern_click
    RumblePattern.DOUBLE_CLICK -> R.string.rumble_pattern_double_click
    RumblePattern.TRIPLE_TICK -> R.string.rumble_pattern_triple_tick
    RumblePattern.THUD -> R.string.rumble_pattern_thud
    RumblePattern.SWELL -> R.string.rumble_pattern_swell
    RumblePattern.RISE -> R.string.rumble_pattern_rise
}

@StringRes
private fun RumbleCue.label() = when (this) {
    RumbleCue.THINK -> R.string.rumble_cue_think
    RumbleCue.TOOL_START -> R.string.rumble_cue_tool_start
    RumbleCue.TOOL_DONE -> R.string.rumble_cue_tool_done
    RumbleCue.TOOL_FAILED -> R.string.rumble_cue_tool_failed
    RumbleCue.WRITE -> R.string.rumble_cue_write
    RumbleCue.ANSWER_DONE -> R.string.rumble_cue_answer_done
    RumbleCue.APPROVAL -> R.string.rumble_cue_approval
}

@StringRes
private fun RumbleCue.description() = when (this) {
    RumbleCue.THINK -> R.string.rumble_cue_think_desc
    RumbleCue.TOOL_START -> R.string.rumble_cue_tool_start_desc
    RumbleCue.TOOL_DONE -> R.string.rumble_cue_tool_done_desc
    RumbleCue.TOOL_FAILED -> R.string.rumble_cue_tool_failed_desc
    RumbleCue.WRITE -> R.string.rumble_cue_write_desc
    RumbleCue.ANSWER_DONE -> R.string.rumble_cue_answer_done_desc
    RumbleCue.APPROVAL -> R.string.rumble_cue_approval_desc
}

/** The AH Rumble page: where it rumbles, how strong and smooth, which engine, and a pattern per moment. */
@Composable
internal fun RumbleSection(settings: AppSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preview = rememberRumblePreview()
    val hasMotor = remember(context) { deviceHasVibrator(context) }
    val systemHaptics = remember(context) { deviceSupportsSystemHaptics(context) }
    var strength by remember(settings.rumbleStrength) { mutableFloatStateOf(settings.rumbleStrength.toFloat()) }
    var smoothness by remember(settings.rumbleSmoothness) { mutableFloatStateOf(settings.rumbleSmoothness.toFloat()) }
    var spacing by remember(settings.rumbleSpacingMs) { mutableFloatStateOf(settings.rumbleSpacingMs.toFloat()) }
    fun style() = settings.rumbleStyle.copy(enabled = true, strength = strength.roundToInt(), smoothness = smoothness.roundToInt())
    fun feel(cue: RumbleCue) = preview(style().config(cue), style())

    SettingsAnchor(R.string.rumble_title) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToggleRow(R.string.rumble_title, R.string.rumble_desc, settings.rumbleEnabled, hasMotor) {
                    scope.launch { repository.setRumbleEnabled(it) }
                }
                if (!hasMotor) Text(stringResource(R.string.rumble_unsupported), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (!hasMotor || !settings.rumbleEnabled) return

    SettingsAnchor(R.string.rumble_where_title) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.rumble_where_title), style = MaterialTheme.typography.titleSmall)
                ToggleRow(R.string.rumble_in_chat, R.string.rumble_in_chat_desc, settings.rumbleInChat, true) {
                    scope.launch { repository.setRumbleInChat(it) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                ToggleRow(R.string.rumble_in_code, R.string.rumble_in_code_desc, settings.rumbleInCode, true) {
                    scope.launch { repository.setRumbleInCode(it) }
                }
            }
        }
    }

    SettingsAnchor(R.string.rumble_strength) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val strengthLabel = stringResource(R.string.rumble_strength)
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(strengthLabel, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.rumble_percent, strength.roundToInt()), style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(value = strength, onValueChange = { strength = it }, valueRange = 5f..100f,
                        onValueChangeFinished = { scope.launch { repository.setRumbleStrength(strength.roundToInt()) }; feel(RumbleCue.TOOL_START) },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = strengthLabel })
                }
                val feelLabel = stringResource(R.string.rumble_feel)
                Column {
                    Text(feelLabel, style = MaterialTheme.typography.titleSmall)
                    Slider(value = smoothness, onValueChange = { smoothness = it }, valueRange = 0f..100f,
                        onValueChangeFinished = { scope.launch { repository.setRumbleSmoothness(smoothness.roundToInt()) }; feel(RumbleCue.TOOL_START) },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = feelLabel })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.rumble_feel_crisp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.rumble_feel_smooth), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        feel(RumbleCue.TOOL_START)
                        delay(350)
                        feel(RumbleCue.TOOL_DONE)
                    }
                }) { Text(stringResource(R.string.rumble_try)) }
            }
        }
    }

    SettingsAnchor(R.string.rumble_engine_title) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rumble_engine_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (systemHaptics) R.string.rumble_engine_status_system else R.string.rumble_engine_status_custom),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup()) {
                    RumbleEngine.entries.forEach { engine ->
                        val (title, desc) = when (engine) {
                            RumbleEngine.AUTO -> R.string.rumble_engine_auto to R.string.rumble_engine_auto_desc
                            RumbleEngine.SYSTEM -> R.string.rumble_engine_system to R.string.rumble_engine_system_desc
                            RumbleEngine.CUSTOM -> R.string.rumble_engine_custom to R.string.rumble_engine_custom_desc
                        }
                        Row(Modifier.fillMaxWidth().selectable(settings.rumbleEngine == engine, role = Role.RadioButton,
                            onClick = { scope.launch { repository.setRumbleEngine(engine) } }).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(selected = settings.rumbleEngine == engine, onClick = null)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(desc), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    SettingsAnchor(R.string.rumble_events_title) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.rumble_events_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.rumble_events_desc), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RumbleCue.entries.forEachIndexed { index, cue ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    CueEditor(cue, settings.rumbleCues.configFor(cue), onChange = { config ->
                        scope.launch { repository.setRumbleCue(cue, config) }
                    }, onFeel = { config -> preview(config, style()) })
                }
            }
        }
    }

    SettingsAnchor(R.string.rumble_fine_tuning_title) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.rumble_fine_tuning_title), style = MaterialTheme.typography.titleSmall)
                val spacingLabel = stringResource(R.string.rumble_spacing)
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(spacingLabel, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.rumble_spacing_ms, spacing.roundToInt()), style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(value = spacing, onValueChange = { spacing = it },
                        valueRange = RUMBLE_MIN_SPACING_MS.toFloat()..RUMBLE_MAX_SPACING_MS.toFloat(),
                        onValueChangeFinished = { scope.launch { repository.setRumbleSpacing(spacing.roundToInt()) } },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = spacingLabel })
                    Text(stringResource(R.string.rumble_spacing_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                ToggleRow(R.string.rumble_battery_saver, R.string.rumble_battery_saver_desc, settings.rumbleSkipOnBatterySaver, true) {
                    scope.launch { repository.setRumbleSkipOnBatterySaver(it) }
                }
                Text(stringResource(R.string.rumble_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { scope.launch { repository.resetRumble() } }) { Text(stringResource(R.string.rumble_reset)) }
            }
        }
    }
}

@Composable
private fun ToggleRow(@StringRes title: Int, @StringRes description: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun CueEditor(cue: RumbleCue, config: RumbleCueConfig, onChange: (RumbleCueConfig) -> Unit, onFeel: (RumbleCueConfig) -> Unit) {
    var level by remember(config.level) { mutableFloatStateOf(config.level.toFloat()) }
    val title = stringResource(cue.label())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(cue.description()), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onFeel(config.copy(level = level.roundToInt())) }, enabled = config.pattern != RumblePattern.OFF) {
                Icon(Icons.Outlined.Vibration, stringResource(R.string.rumble_play_cue, title))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RumblePattern.entries.forEach { pattern ->
                FilterChip(selected = config.pattern == pattern, onClick = {
                    val next = config.copy(pattern = pattern, level = level.roundToInt())
                    onChange(next); onFeel(next)
                }, label = { Text(stringResource(pattern.label())) })
            }
        }
        if (config.pattern != RumblePattern.OFF) {
            val levelLabel = stringResource(R.string.rumble_level)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(levelLabel, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.rumble_percent, level.roundToInt()), style = MaterialTheme.typography.bodyMedium)
            }
            Slider(value = level, onValueChange = { level = it }, valueRange = 5f..100f,
                onValueChangeFinished = {
                    val next = config.copy(level = level.roundToInt())
                    onChange(next); onFeel(next)
                },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$title $levelLabel" })
        }
    }
}
