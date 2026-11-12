@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.androidharness.app.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.audio.TtsController
import com.androidharness.app.data.audio.TtsPhase
import com.androidharness.app.data.audio.VoiceGateway
import com.androidharness.app.data.audio.VoiceProtocol
import com.androidharness.app.data.audio.VoiceProviderConfig
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * The two engine cards of the Voice & speech page: Listening (speech to text) and
 * Speaking (text to speech). Each one is a segmented group whose first segment
 * lists every engine as a toggle chip, so choosing a provider never hides behind
 * a dialog and "Add" is always one tap away. What sits below the chips depends on
 * the choice: a key field, the model and voice rows, and for Speaking the speed,
 * "speak every reply" and test controls. The group animates its height while the
 * rows beneath the chips change.
 */

internal enum class VoiceReadiness { READY, NEEDS_KEY, MISSING }

/** Where one direction (listening or speaking) currently stands. */
internal class VoiceEngineState(
    val provider: VoiceProviderConfig?,
    val onPhone: Boolean,
    /** True when the provider needs no key or one is saved. */
    val keyReady: Boolean,
) {
    val readiness: VoiceReadiness
        get() = when {
            onPhone -> VoiceReadiness.READY
            provider == null -> VoiceReadiness.MISSING
            !keyReady -> VoiceReadiness.NEEDS_KEY
            else -> VoiceReadiness.READY
        }
}

/** What the cards can ask the page to do. */
internal class VoiceActions(
    val addProvider: () -> Unit,
    val openProvider: (String) -> Unit,
    val pickSttModel: () -> Unit,
    val pickTtsModel: () -> Unit,
    val pickVoice: () -> Unit,
    val keyChanged: () -> Unit,
)

// ---------------------------------------------------------------------------
// Listening
// ---------------------------------------------------------------------------

@Composable
internal fun VoiceListeningCard(
    container: AppContainer,
    settings: AppSettings,
    scope: CoroutineScope,
    providers: List<VoiceProviderConfig>,
    keyed: Set<String>,
    engine: VoiceEngineState,
    actions: VoiceActions,
) {
    val motion = MaterialTheme.motionScheme
    val usable = remember(providers) { providers.filter { it.supportsStt } }
    val provider = engine.provider
    val model = provider?.let { settings.voiceSelections[it.id]?.sttModel?.ifBlank { null } ?: it.sttModel }.orEmpty()
    val (pillText, pillOk) = engineStatus(engine)

    SettingsAnchor(R.string.settings_anchor_stt) {
        SettingsGroup(Modifier.animateContentSize(motion.defaultSpatialSpec())) {
            item {
                VoiceEngineHeader(
                    icon = Icons.Rounded.Mic,
                    shape = MaterialShapes.Cookie6Sided.toShape(),
                    turned = !engine.onPhone,
                    title = stringResource(R.string.settings_voice_hear_title),
                    subtitle = stringResource(R.string.settings_voice_hear_desc),
                    pillText = pillText,
                    pillOk = pillOk,
                ) {
                    VoiceEngineChips(
                        phoneSelected = engine.onPhone,
                        providers = usable,
                        selectedId = settings.voiceEngine,
                        keyed = keyed,
                        onPhone = { scope.launch { container.settings.setVoiceEngine(AppSettings.VOICE_ENGINE_INBUILT) } },
                        onProvider = { id -> scope.launch { container.settings.setVoiceEngine(id) } },
                        onAdd = actions.addProvider,
                    )
                }
            }
            when {
                engine.onPhone -> item {
                    VoiceNote(Icons.Rounded.Lock, stringResource(R.string.settings_voice_engine_phone_hear))
                }
                provider == null -> item {
                    VoiceNote(Icons.Rounded.Key, stringResource(R.string.settings_voice_status_missing_body))
                }
                else -> {
                    providerSetupItems(container, provider, provider.id in keyed, scope, actions.keyChanged)
                    item(onClick = actions.pickSttModel) {
                        VoiceValueRow(
                            modifier = Modifier.settingsAnchor(R.string.settings_anchor_whisper),
                            icon = Icons.Rounded.RecordVoiceOver,
                            title = stringResource(R.string.settings_stt_model),
                            value = model.ifBlank { stringResource(R.string.settings_voice_choose_model) },
                        )
                    }
                    item(onClick = { actions.openProvider(provider.id) }) {
                        VoiceValueRow(
                            icon = Icons.Rounded.Tune,
                            title = stringResource(R.string.settings_voice_provider_details),
                            value = stringResource(R.string.settings_voice_provider_details_desc),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Speaking
// ---------------------------------------------------------------------------

@Composable
internal fun VoiceSpeakingCard(
    container: AppContainer,
    settings: AppSettings,
    scope: CoroutineScope,
    providers: List<VoiceProviderConfig>,
    keyed: Set<String>,
    engine: VoiceEngineState,
    actions: VoiceActions,
) {
    val motion = MaterialTheme.motionScheme
    val usable = remember(providers) { providers.filter { it.supportsTts } }
    val provider = engine.provider
    val selection = provider?.let { settings.voiceSelections[it.id] }
    val model = selection?.ttsModel?.ifBlank { null } ?: provider?.ttsModel.orEmpty()
    val voice = selection?.ttsVoice?.ifBlank { null } ?: provider?.ttsVoice.orEmpty()
    val automatic = stringResource(R.string.settings_tts_voice_automatic)
    val voiceText = if (engine.onPhone) settings.ttsSystemVoice?.replace('_', ' ') ?: automatic else voice.ifBlank { automatic }
    val (pillText, pillOk) = engineStatus(engine)

    SettingsAnchor(R.string.settings_anchor_tts) {
        SettingsGroup(Modifier.animateContentSize(motion.defaultSpatialSpec())) {
            item {
                VoiceEngineHeader(
                    icon = Icons.Rounded.VolumeUp,
                    shape = MaterialShapes.Sunny.toShape(),
                    turned = !engine.onPhone,
                    title = stringResource(R.string.settings_voice_speak_title),
                    subtitle = stringResource(R.string.settings_voice_speak_desc),
                    pillText = pillText,
                    pillOk = pillOk,
                ) {
                    VoiceEngineChips(
                        phoneSelected = engine.onPhone,
                        providers = usable,
                        selectedId = settings.ttsEngine,
                        keyed = keyed,
                        onPhone = { scope.launch { container.settings.setTtsEngine(AppSettings.TTS_ENGINE_SYSTEM) } },
                        onProvider = { id -> scope.launch { container.settings.setTtsEngine(id) } },
                        onAdd = actions.addProvider,
                    )
                }
            }
            when {
                engine.onPhone -> item {
                    VoiceNote(Icons.Rounded.PhoneAndroid, stringResource(R.string.settings_voice_engine_phone_speak))
                }
                provider == null -> item {
                    VoiceNote(Icons.Rounded.Key, stringResource(R.string.settings_voice_status_missing_body))
                }
                else -> {
                    providerSetupItems(container, provider, provider.id in keyed, scope, actions.keyChanged)
                    // Edge has a single voice list and no model choice.
                    if (provider.protocol != VoiceProtocol.EDGE) {
                        item(onClick = actions.pickTtsModel) {
                            VoiceValueRow(
                                modifier = Modifier.settingsAnchor(R.string.settings_tts_model),
                                icon = Icons.Rounded.Tune,
                                title = stringResource(R.string.settings_tts_model),
                                value = VoiceGateway.modelLabel(provider, model).ifBlank { stringResource(R.string.settings_voice_choose_model) },
                            )
                        }
                    }
                }
            }
            item(onClick = actions.pickVoice) {
                VoiceValueRow(
                    modifier = Modifier.settingsAnchor(R.string.settings_tts_voice),
                    icon = Icons.Rounded.RecordVoiceOver,
                    title = stringResource(R.string.settings_tts_voice),
                    value = voiceText,
                )
            }
            item {
                val speeds = remember(settings.ttsSpeed) {
                    (listOf(0.75f, 1f, 1.25f, 1.5f) + settings.ttsSpeed).distinct().sorted()
                }
                Column(
                    Modifier.fillMaxWidth().settingsAnchor(R.string.settings_tts_speed).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SettingsIconBadge(Icons.Rounded.Speed, SettingsTone.SECONDARY, 40.dp)
                        Text(stringResource(R.string.settings_tts_speed), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        SettingsStatusPill(speedLabel(settings.ttsSpeed), ok = false)
                    }
                    SettingsChoiceGroup(
                        options = speeds.map { it to speedLabel(it) },
                        selected = { it == settings.ttsSpeed },
                        onSelect = { value -> scope.launch { container.settings.setTtsSpeed(value) } },
                    )
                }
            }
            item(onClick = { scope.launch { container.settings.setTtsAlwaysSpeak(!settings.ttsAlwaysSpeak) } }) {
                SettingsItemRow(
                    title = stringResource(R.string.settings_tts_always_speak),
                    subtitle = stringResource(R.string.settings_tts_always_speak_desc),
                    modifier = Modifier.settingsAnchor(R.string.settings_tts_always_speak),
                    leading = { SettingsIconBadge(Icons.Rounded.VolumeUp, SettingsTone.SECONDARY, 40.dp) },
                    trailing = {
                        Switch(
                            checked = settings.ttsAlwaysSpeak,
                            onCheckedChange = { on -> scope.launch { container.settings.setTtsAlwaysSpeak(on) } },
                        )
                    },
                )
            }
            item { VoiceTestRow(container) }
            if (provider != null && !engine.onPhone) {
                item(onClick = { actions.openProvider(provider.id) }) {
                    VoiceValueRow(
                        icon = Icons.Rounded.Tune,
                        title = stringResource(R.string.settings_voice_provider_details),
                        value = stringResource(R.string.settings_voice_provider_details_desc),
                    )
                }
            }
        }
    }
}

/** Plays a sample, then becomes a stop button while it prepares and speaks. */
@Composable
private fun VoiceTestRow(container: AppContainer) {
    val motion = MaterialTheme.motionScheme
    val state by container.tts.state.collectAsStateWithLifecycle()
    val phase = if (state.messageId == TtsController.PREVIEW_ID) state.phase else TtsPhase.IDLE
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = { if (phase == TtsPhase.IDLE) container.tts.preview() else container.tts.stop() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                },
                label = "voiceTest",
            ) { current ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (current) {
                        TtsPhase.IDLE -> {
                            Icon(Icons.Rounded.VolumeUp, null, Modifier.size(20.dp))
                            Text(stringResource(R.string.settings_tts_test))
                        }
                        TtsPhase.PREPARING -> {
                            LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current)
                            Text(stringResource(R.string.settings_voice_test_preparing))
                        }
                        TtsPhase.SPEAKING -> {
                            Icon(Icons.Rounded.Stop, null, Modifier.size(20.dp))
                            Text(stringResource(R.string.settings_voice_test_stop))
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.settings_voice_test_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Pieces shared by both cards
// ---------------------------------------------------------------------------

@Composable
private fun engineStatus(engine: VoiceEngineState): Pair<String, Boolean> = when (engine.readiness) {
    VoiceReadiness.READY -> when {
        engine.onPhone -> stringResource(R.string.settings_voice_pill_phone)
        engine.provider?.requiresKey == false -> stringResource(R.string.settings_voice_pill_free)
        else -> stringResource(R.string.settings_voice_pill_ready)
    } to true
    VoiceReadiness.NEEDS_KEY -> stringResource(R.string.ui_no_key) to false
    VoiceReadiness.MISSING -> stringResource(R.string.settings_voice_pill_missing) to false
}

private fun speedLabel(value: Float): String = value.toString().removeSuffix(".0") + "×"

/**
 * Top of an engine card: a Material shape badge that turns when the engine moves
 * off the phone, the title, a status pill that swaps with a spring, and the chips.
 */
@Composable
private fun VoiceEngineHeader(
    icon: ImageVector,
    shape: Shape,
    turned: Boolean,
    title: String,
    subtitle: String,
    pillText: String,
    pillOk: Boolean,
    chips: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val turn by animateFloatAsState(if (turned) 45f else 0f, motion.slowSpatialSpec(), label = "voiceBadgeTurn")
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(52.dp).graphicsLayer { rotationZ = turn }.background(scheme.primaryContainer, shape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, null,
                    Modifier.size(26.dp).graphicsLayer { rotationZ = -turn },
                    tint = scheme.onPrimaryContainer,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            AnimatedContent(
                targetState = pillText to pillOk,
                transitionSpec = {
                    (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.8f)) togetherWith
                        fadeOut(motion.fastEffectsSpec())
                },
                label = "voicePill",
            ) { (text, ok) -> SettingsStatusPill(text, ok) }
        }
        chips()
    }
}

/** Every engine as a toggle chip, then a tonal Add chip. The chosen chip morphs and shows a check. */
@Composable
private fun VoiceEngineChips(
    phoneSelected: Boolean,
    providers: List<VoiceProviderConfig>,
    selectedId: String?,
    keyed: Set<String>,
    onPhone: () -> Unit,
    onProvider: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VoiceChip(
            checked = phoneSelected,
            label = stringResource(R.string.settings_voice_engine_phone),
            onClick = onPhone,
            leading = Icons.Rounded.PhoneAndroid,
        )
        providers.forEach { provider ->
            key(provider.id) {
                VoiceChip(
                    checked = !phoneSelected && provider.id == selectedId,
                    label = provider.name,
                    onClick = { onProvider(provider.id) },
                    needsKey = provider.requiresKey && provider.id !in keyed,
                )
            }
        }
        FilledTonalButton(
            onClick = onAdd,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.settings_voice_chip_add))
        }
    }
}

@Composable
internal fun VoiceChip(
    checked: Boolean,
    label: String,
    onClick: () -> Unit,
    leading: ImageVector? = null,
    needsKey: Boolean = false,
) {
    val motion = MaterialTheme.motionScheme
    ToggleButton(
        checked = checked,
        onCheckedChange = { if (!checked) onClick() },
        modifier = Modifier.semantics { role = Role.RadioButton },
        shapes = ToggleButtonDefaults.shapesFor(ToggleButtonDefaults.MinHeight),
        colors = ToggleButtonDefaults.toggleButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        AnimatedVisibility(
            visible = checked,
            enter = fadeIn(motion.defaultEffectsSpec()) + expandHorizontally(motion.defaultSpatialSpec()),
            exit = fadeOut(motion.fastEffectsSpec()) + shrinkHorizontally(motion.fastSpatialSpec()),
        ) {
            Row {
                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
        }
        if (leading != null) {
            Icon(leading, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (needsKey) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.Key, stringResource(R.string.ui_no_key), Modifier.size(14.dp))
        }
    }
}

/** An icon and a line or two of explanation, for rows that only inform. */
@Composable
internal fun VoiceNote(
    icon: ImageVector,
    text: String,
    title: String? = null,
    good: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (good) {
            val success = LocalStatusColors.current.success
            Box(Modifier.size(40.dp).background(success.copy(alpha = 0.16f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = success)
            }
        } else {
            SettingsIconBadge(icon, SettingsTone.NEUTRAL, 40.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A tappable setting with its current value underneath. */
@Composable
internal fun VoiceValueRow(icon: ImageVector, title: String, value: String, modifier: Modifier = Modifier) {
    SettingsItemRow(
        title = title,
        subtitle = value,
        modifier = modifier,
        leading = { SettingsIconBadge(icon, SettingsTone.SECONDARY, 40.dp) },
        trailing = {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
