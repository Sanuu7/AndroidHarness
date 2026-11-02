package com.androidharness.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.LiveModeAccess
import com.androidharness.app.data.LiveModeSettings
import com.androidharness.app.llm.ModelCatalog
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.ui.chat.ExperimentalTag
import com.androidharness.app.ui.chat.components.LiveGlyph
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import com.androidharness.app.ui.common.RadioButton
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun LiveModeSection(container: AppContainer, settings: AppSettings, onOpenVoice: () -> Unit) {
    val live = settings.liveMode
    val scope = rememberCoroutineScope()
    fun update(transform: (LiveModeSettings) -> LiveModeSettings) {
        scope.launch { container.settings.updateLiveMode(transform) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsAnchor(R.string.live_settings_enable) {
            SettingsPanel(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().toggleable(live.enabled, role = Role.Switch) { on -> update { it.copy(enabled = on) } }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LiveGlyph(color = MaterialTheme.colorScheme.primary, size = 22.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.live_settings_enable), style = MaterialTheme.typography.titleSmall)
                            ExperimentalTag()
                        }
                        Text(stringResource(R.string.live_settings_enable_desc), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = live.enabled, onCheckedChange = null)
                }
            }
        }
        Text(stringResource(R.string.live_settings_experimental_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))

        LiveModelPanel(container, settings)
        LiveAccessPanel(live.access) { access -> update { it.copy(access = access) } }
        LiveVoicePanel(live, settings, onOpenVoice, update = ::update)
    }
}

@Composable
private fun LiveModelPanel(container: AppContainer, settings: AppSettings) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val live = settings.liveMode
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val catalogs by container.providers.catalogs.collectAsStateWithLifecycle(initialValue = emptyMap())
    val scope = rememberCoroutineScope()
    var picking by rememberSaveable { mutableStateOf(false) }
    val selected = providers.firstOrNull { it.id == live.providerId }

    SettingsAnchor(R.string.live_settings_model) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { picking = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.live_settings_model), style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                live.providerId == null -> uiContext.getString(R.string.live_settings_model_chat)
                                selected == null -> uiContext.getString(R.string.ui_selected_provider_unavailable)
                                else -> "${selected.name} · ${live.model ?: selected.model}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.live_settings_model_choose))
                }
                Text(stringResource(R.string.live_settings_model_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (live.providerId != null) {
                    TextButton(onClick = { scope.launch { container.settings.updateLiveMode { it.copy(providerId = null, model = null) } } }) {
                        Text(stringResource(R.string.live_settings_model_chat))
                    }
                }
            }
        }
    }

    if (picking) ModelPickerSheet(
        providers = providers,
        activeProviderId = live.providerId ?: settings.activeProviderId ?: providers.firstOrNull()?.id,
        activeModel = if (live.providerId == null) settings.activeModel else live.model,
        catalogs = catalogs,
        onDismiss = { picking = false },
        onSelect = { providerId, model ->
            scope.launch { container.settings.updateLiveMode { it.copy(providerId = providerId, model = model) } }
            picking = false
        },
        onRefreshCatalog = { providerId ->
            val provider = providers.firstOrNull { it.id == providerId }
            val key = container.providers.apiKey(providerId)
            when {
                provider == null -> uiContext.getString(R.string.ui_unknown_provider)
                else -> {
                    val usable = provider.effectiveApiKey(key)
                    if (usable.isBlank()) uiContext.getString(R.string.ui_no_api_key_for_this_provider)
                    else when (val result = ModelCatalog.listModels(provider, usable)) {
                        is ModelCatalog.Result.Models -> {
                            container.providers.saveCatalog(providerId, result.models)
                            null
                        }
                        is ModelCatalog.Result.Failed -> result.message
                    }
                }
            }
        },
        onAddCustomModel = { providerId, model, reasoning ->
            scope.launch { container.providers.addCustomModel(providerId, model, reasoning) }
        },
        onDeleteCustomModel = { providerId, model ->
            scope.launch { container.providers.removeCustomModel(providerId, model) }
        },
        onEnableProvider = { id -> scope.launch { container.providers.setEnabled(id, true) } },
        onManageProviders = { picking = false },
    )
}

@Composable
private fun LiveAccessPanel(access: LiveModeAccess, onSelect: (LiveModeAccess) -> Unit) {
    val choices = listOf(
        Triple(LiveModeAccess.TALK, R.string.live_access_talk, R.string.live_access_talk_desc),
        Triple(LiveModeAccess.READ, R.string.live_access_read, R.string.live_access_read_desc),
        Triple(LiveModeAccess.START_TASKS, R.string.live_access_tasks, R.string.live_access_tasks_desc),
    )
    SettingsAnchor(R.string.live_settings_access) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.live_settings_access), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.live_settings_access_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    choices.forEach { (value, title, description) ->
                        val isSelected = value == access
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().selectable(isSelected, role = Role.RadioButton) { onSelect(value) }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = isSelected, onClick = null)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(description), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveVoicePanel(
    live: LiveModeSettings,
    settings: AppSettings,
    onOpenVoice: () -> Unit,
    update: ((LiveModeSettings) -> LiveModeSettings) -> Unit,
) {
    var silence by remember { mutableFloatStateOf(live.silenceMs.toFloat()) }
    LaunchedEffect(live.silenceMs) { silence = live.silenceMs.toFloat() }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsAnchor(R.string.live_settings_speak) {
                Row(
                    Modifier.fillMaxWidth().toggleable(live.speakReplies, role = Role.Switch) { on -> update { it.copy(speakReplies = on) } },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.live_settings_speak), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.live_settings_speak_desc), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = live.speakReplies, onCheckedChange = null)
                }
            }
            SettingsAnchor(R.string.live_settings_silence) {
                val label = stringResource(R.string.live_settings_silence)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.live_settings_seconds, String.format(Locale.getDefault(), "%.1f", silence / 1000f)),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = silence,
                        onValueChange = { silence = (it / 100f).roundToInt() * 100f },
                        valueRange = LiveModeSettings.MIN_SILENCE_MS.toFloat()..LiveModeSettings.MAX_SILENCE_MS.toFloat(),
                        onValueChangeFinished = { update { it.copy(silenceMs = silence.roundToInt()) } },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                    )
                    Text(stringResource(R.string.live_settings_silence_desc), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        R.string.live_settings_engines,
                        stringResource(if (settings.voiceEngine == AppSettings.VOICE_ENGINE_GROQ) R.string.live_engine_groq else R.string.live_engine_system),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenVoice) { Text(stringResource(R.string.live_settings_open_voice)) }
            }
        }
    }
}
