@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.androidharness.app.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.ui.graphics.graphicsLayer
import com.androidharness.app.ui.common.FilledTonalButton
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsAnchor(R.string.live_settings_enable) { LiveHeroToggle(live.enabled) { on -> update { it.copy(enabled = on) } } }
        Text(stringResource(R.string.live_settings_experimental_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))

        LiveModelPanel(container, settings)
        LiveAccessPanel(live.access) { access -> update { it.copy(access = access) } }
        LiveVoicePanel(live, settings, onOpenVoice, update = ::update)
    }
}

/** The master switch as the hero of the page: it fills with the primary tone and rounds off further while Live mode is on. */
@Composable
private fun LiveHeroToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val fill by animateColorAsState(if (enabled) scheme.primaryContainer else scheme.surfaceContainer, motion.defaultEffectsSpec(), label = "liveHeroFill")
    val content by animateColorAsState(if (enabled) scheme.onPrimaryContainer else scheme.onSurface, motion.defaultEffectsSpec(), label = "liveHeroContent")
    val badge by animateColorAsState(if (enabled) scheme.primary else scheme.surfaceContainerHighest, motion.defaultEffectsSpec(), label = "liveHeroBadge")
    val glyph by animateColorAsState(if (enabled) scheme.onPrimary else scheme.onSurfaceVariant, motion.defaultEffectsSpec(), label = "liveHeroGlyph")
    val corner by animateDpAsState(if (enabled) 40.dp else 28.dp, motion.defaultSpatialSpec(), label = "liveHeroCorner")
    val turn by animateFloatAsState(if (enabled) 90f else 0f, motion.slowSpatialSpec(), label = "liveHeroTurn")
    Surface(
        shape = RoundedCornerShape(corner.coerceAtLeast(0.dp)), color = fill, contentColor = content,
        border = if (enabled) null else settingsPanelBorder(), modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch, onValueChange = onToggle)
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(56.dp).graphicsLayer { rotationZ = turn }.background(badge, MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) { LiveGlyph(color = glyph, modifier = Modifier.graphicsLayer { rotationZ = -turn }, size = 26.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.live_settings_enable), style = MaterialTheme.typography.titleLargeEmphasized)
                ExperimentalTag()
                Text(stringResource(R.string.live_settings_enable_desc), style = MaterialTheme.typography.bodyMedium,
                    color = content.copy(alpha = 0.78f))
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
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
        SettingsGroup {
            item(onClick = { picking = true }) {
                SettingsItemRow(
                    title = stringResource(R.string.live_settings_model),
                    subtitle = when {
                        live.providerId == null -> uiContext.getString(R.string.live_settings_model_chat)
                        selected == null -> uiContext.getString(R.string.ui_selected_provider_unavailable)
                        else -> "${selected.name} · ${live.model ?: selected.model}"
                    },
                    leading = { SettingsIconBadge(Icons.Rounded.AutoAwesome, SettingsTone.TERTIARY) },
                    trailing = {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = stringResource(R.string.live_settings_model_choose),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                )
            }
            item {
                Column(Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
                    .padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.live_settings_model_desc), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (live.providerId != null) {
                        FilledTonalButton(onClick = { scope.launch { container.settings.updateLiveMode { it.copy(providerId = null, model = null) } } }) {
                            Text(stringResource(R.string.live_settings_model_chat))
                        }
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.live_settings_access), style = MaterialTheme.typography.labelLargeEmphasized,
                    color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.live_settings_access_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The chosen level fills with the secondary tone and takes the full radius.
            SettingsGroup(Modifier.selectableGroup()) {
                choices.forEach { (value, title, description) ->
                    val isSelected = value == access
                    item(selected = isSelected) {
                        Row(
                            Modifier.fillMaxWidth().selectable(isSelected, role = Role.RadioButton) { onSelect(value) }
                                .heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(description), style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
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
    SettingsGroup {
        item(onClick = { update { it.copy(speakReplies = !live.speakReplies) } }) {
            SettingsItemRow(
                title = stringResource(R.string.live_settings_speak),
                subtitle = stringResource(R.string.live_settings_speak_desc),
                modifier = Modifier.settingsAnchor(R.string.live_settings_speak),
                leading = { SettingsIconBadge(Icons.Rounded.VolumeUp, SettingsTone.SECONDARY) },
                trailing = { Switch(checked = live.speakReplies, onCheckedChange = { on -> update { it.copy(speakReplies = on) } }) },
            )
        }
        item {
            val label = stringResource(R.string.live_settings_silence)
            Column(Modifier.fillMaxWidth().settingsAnchor(R.string.live_settings_silence).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SettingsIconBadge(Icons.Rounded.Timer, SettingsTone.SECONDARY)
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    SettingsStatusPill(stringResource(R.string.live_settings_seconds, String.format(Locale.getDefault(), "%.1f", silence / 1000f)), ok = false)
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
        item(onClick = onOpenVoice) {
            SettingsItemRow(
                title = stringResource(R.string.live_settings_open_voice),
                subtitle = stringResource(
                    R.string.live_settings_engines,
                    stringResource(if (settings.voiceEngine != AppSettings.VOICE_ENGINE_INBUILT) R.string.live_engine_groq else R.string.live_engine_system),
                ),
                leading = { SettingsIconBadge(Icons.Rounded.Mic, SettingsTone.SECONDARY) },
                trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
    }
}
