@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.androidharness.app.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.audio.EdgeVoiceGroup
import com.androidharness.app.data.audio.TtsController
import com.androidharness.app.data.audio.TtsError
import com.androidharness.app.data.audio.TtsPhase
import com.androidharness.app.data.audio.TtsVoiceOption
import com.androidharness.app.data.audio.VoiceGateway
import com.androidharness.app.data.audio.VoiceProtocol
import com.androidharness.app.data.audio.VoiceProviderConfig
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.TtsTermsDialog
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

// ---------------------------------------------------------------------------
// Voice & speech
//
// One page for hearing you and speaking back, in the order a person uses it:
// a headline that says whether voice works, Listening (speech to text), Auto send
// for dictation, Speaking (text to speech), and the list of providers with Add
// first. Choosing a provider, its key, model and voice all happen on the page
// itself; sheets only open for the longer jobs (a provider's details, adding one,
// a long list of models or voices).
// ---------------------------------------------------------------------------

private enum class VoicePicker { STT_MODEL, TTS_MODEL, VOICE }

@Composable
internal fun VoiceProvidersSection(
    container: AppContainer,
    settings: AppSettings,
    scope: CoroutineScope,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val loaded by container.voiceProviders.providers.collectAsStateWithLifecycle(initialValue = null)
    val providers = loaded ?: return

    var keyEpoch by remember { mutableStateOf(0) }
    var picker by remember { mutableStateOf<VoicePicker?>(null) }
    var modelEpoch by remember { mutableStateOf(0) }
    var voiceEpoch by remember { mutableStateOf(0) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<VoiceProviderConfig?>(null) }
    var editingNew by remember { mutableStateOf(false) }
    var ttsTermsError by remember { mutableStateOf<TtsError?>(null) }

    // Keys are read once per change, not on every recomposition.
    val keyed = remember(providers, keyEpoch) {
        providers.filter { container.voiceProviders.apiKeyFor(it.id) != null }.map { it.id }.toSet()
    }
    val listen = remember(settings.voiceEngine, providers, keyed) {
        engineState(providers, keyed, settings.voiceEngine, AppSettings.VOICE_ENGINE_INBUILT, forTts = false)
    }
    val speak = remember(settings.ttsEngine, providers, keyed) {
        engineState(providers, keyed, settings.ttsEngine, AppSettings.TTS_ENGINE_SYSTEM, forTts = true)
    }

    val actions = remember(container) {
        VoiceActions(
            addProvider = { editingNew = true; editing = container.voiceProviders.newCustomProvider() },
            openProvider = { id -> detailId = id },
            pickSttModel = { picker = VoicePicker.STT_MODEL },
            pickTtsModel = { picker = VoicePicker.TTS_MODEL },
            pickVoice = { picker = VoicePicker.VOICE },
            keyChanged = { keyEpoch++ },
        )
    }

    // "Test voice" failures otherwise have no surface to appear on.
    LaunchedEffect(Unit) {
        container.tts.errors.collect { error ->
            if (error.termsRequired) ttsTermsError = error
            else android.widget.Toast.makeText(uiContext, error.message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // Model lists come from the provider when it exposes /models, so new ids
    // appear without an app update. Fetched only while the sheet is open.
    val sttProvider = listen.provider
    val ttsProvider = speak.provider
    val sttModels by produceState<List<String>?>(null, picker, sttProvider?.id, modelEpoch) {
        value = if (picker == VoicePicker.STT_MODEL && sttProvider != null) {
            VoiceGateway.sttModels(sttProvider, container.voiceProviders.apiKeyFor(sttProvider.id).orEmpty())
        } else null
    }
    val ttsModels by produceState<List<String>?>(null, picker, ttsProvider?.id, modelEpoch) {
        value = if (picker == VoicePicker.TTS_MODEL && ttsProvider != null) {
            VoiceGateway.ttsModels(ttsProvider, container.voiceProviders.apiKeyFor(ttsProvider.id).orEmpty())
        } else null
    }

    // --- The page -----------------------------------------------------------
    VoiceStatusHero(listen, speak, onAddKey = { provider -> detailId = provider.id })

    VoiceListeningCard(container, settings, scope, providers, keyed, listen, actions)

    VoiceAutoSendGroup(container, settings, scope)

    VoiceSpeakingCard(container, settings, scope, providers, keyed, speak, actions)

    VoiceProvidersHub(
        providers = providers,
        keyed = keyed,
        settings = settings,
        onAdd = actions.addProvider,
        onOpen = actions.openProvider,
    )

    // --- Sheets and dialogs -------------------------------------------------
    if (picker == VoicePicker.STT_MODEL && sttProvider != null) {
        val selected = settings.voiceSelections[sttProvider.id]?.sttModel?.ifBlank { null } ?: sttProvider.sttModel
        VoiceChoiceSheet(
            title = stringResource(R.string.settings_stt_model),
            options = sttModels?.map { VoiceChoice(it, it) },
            selectedId = selected,
            customLabel = stringResource(R.string.settings_tts_custom_model),
            onSelect = { id ->
                picker = null
                scope.launch { container.settings.setVoiceProviderSttModel(sttProvider.id, id) }
            },
            onDismiss = { picker = null },
            onRefresh = { modelEpoch++ },
        )
    }
    if (picker == VoicePicker.TTS_MODEL && ttsProvider != null) {
        val selected = settings.voiceSelections[ttsProvider.id]?.ttsModel?.ifBlank { null } ?: ttsProvider.ttsModel
        VoiceChoiceSheet(
            title = stringResource(R.string.settings_tts_model),
            options = ttsModels?.map { id ->
                val label = VoiceGateway.modelLabel(ttsProvider, id)
                VoiceChoice(id, label, id.takeIf { label != id })
            },
            selectedId = selected,
            customLabel = stringResource(R.string.settings_tts_custom_model),
            onSelect = { id ->
                picker = null
                scope.launch {
                    container.settings.setVoiceProviderTtsModel(ttsProvider.id, id)
                    VoiceGateway.defaultVoiceFor(ttsProvider, id)?.let { container.settings.setVoiceProviderTtsVoice(ttsProvider.id, it) }
                }
            },
            onDismiss = { picker = null },
            onRefresh = { modelEpoch++ },
        )
    }
    if (picker == VoicePicker.VOICE) {
        VoicePickerHost(
            container = container,
            settings = settings,
            scope = scope,
            provider = ttsProvider,
            onPhone = speak.onPhone,
            voiceEpoch = voiceEpoch,
            onRefreshEdge = { scope.launch { container.tts.refreshEdgeVoices(); voiceEpoch++ } },
            onDismiss = { picker = null },
        )
    }

    detailId?.let { id ->
        providers.firstOrNull { it.id == id }?.let { provider ->
            VoiceProviderSheet(
                container = container,
                provider = provider,
                settings = settings,
                saved = provider.id in keyed,
                scope = scope,
                onKeyChanged = { keyEpoch++ },
                onEdit = {
                    detailId = null
                    editingNew = false
                    editing = provider
                },
                onRemove = {
                    detailId = null
                    scope.launch {
                        container.voiceProviders.delete(provider.id)
                        // Nothing may keep pointing at a provider that no longer exists.
                        if (settings.voiceEngine == provider.id) container.settings.setVoiceEngine(AppSettings.VOICE_ENGINE_INBUILT)
                        if (settings.ttsEngine == provider.id) container.settings.setTtsEngine(AppSettings.TTS_ENGINE_SYSTEM)
                        keyEpoch++
                    }
                },
                onDismiss = { detailId = null },
            )
        }
    }

    editing?.let { provider ->
        val isNew = editingNew
        VoiceProviderEditorSheet(
            initial = provider,
            isNew = isNew,
            onSave = { updated ->
                editing = null
                scope.launch {
                    container.voiceProviders.save(updated)
                    keyEpoch++
                    // A new provider opens straight to its key.
                    if (isNew) detailId = updated.id
                }
            },
            onDismiss = { editing = null },
        )
    }

    ttsTermsError?.let { TtsTermsDialog(it) { ttsTermsError = null } }
}

/** Where one direction stands: on the phone, on a provider (with or without its key), or on one that is gone. */
private fun engineState(
    providers: List<VoiceProviderConfig>,
    keyed: Set<String>,
    id: String,
    phoneId: String,
    forTts: Boolean,
): VoiceEngineState {
    if (id == phoneId) return VoiceEngineState(provider = null, onPhone = true, keyReady = true)
    val provider = providers.firstOrNull { it.id == id && (if (forTts) it.supportsTts else it.supportsStt) }
        ?: return VoiceEngineState(provider = null, onPhone = false, keyReady = false)
    return VoiceEngineState(provider, onPhone = false, keyReady = !provider.requiresKey || provider.id in keyed)
}

// ---------------------------------------------------------------------------
// Headline
// ---------------------------------------------------------------------------

private data class HeroText(val title: String, val body: String)

/**
 * Says at a glance whether voice works. It fills with the primary tone and shows a
 * check while both directions are ready; otherwise it turns secondary, the badge
 * turns a quarter, and a button goes to whatever is missing.
 */
@Composable
private fun VoiceStatusHero(
    listen: VoiceEngineState,
    speak: VoiceEngineState,
    onAddKey: (VoiceProviderConfig) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val problem = listOf(listen, speak).firstOrNull { it.readiness != VoiceReadiness.READY }
    val ready = problem == null
    val keyProvider = problem?.provider?.takeIf { problem.readiness == VoiceReadiness.NEEDS_KEY }

    val fill by animateColorAsState(if (ready) scheme.primaryContainer else scheme.secondaryContainer, motion.defaultEffectsSpec(), label = "heroFill")
    val content by animateColorAsState(if (ready) scheme.onPrimaryContainer else scheme.onSecondaryContainer, motion.defaultEffectsSpec(), label = "heroContent")
    val badge by animateColorAsState(if (ready) scheme.primary else scheme.secondary, motion.defaultEffectsSpec(), label = "heroBadge")
    val glyph by animateColorAsState(if (ready) scheme.onPrimary else scheme.onSecondary, motion.defaultEffectsSpec(), label = "heroGlyph")
    val corner by animateDpAsState(if (ready) 40.dp else 28.dp, motion.defaultSpatialSpec(), label = "heroCorner")
    val turn by animateFloatAsState(if (ready) 0f else 90f, motion.slowSpatialSpec(), label = "heroTurn")

    val phone = stringResource(R.string.settings_voice_engine_phone_lower)
    val text = when {
        ready -> HeroText(
            stringResource(R.string.settings_voice_status_ready),
            stringResource(R.string.settings_voice_status_ready_body, listen.provider?.name ?: phone, speak.provider?.name ?: phone),
        )
        keyProvider != null -> HeroText(
            stringResource(R.string.settings_voice_status_attention),
            stringResource(R.string.settings_voice_status_attention_body, keyProvider.name),
        )
        else -> HeroText(
            stringResource(R.string.settings_voice_status_attention),
            stringResource(R.string.settings_voice_status_missing_body),
        )
    }

    Surface(
        shape = RoundedCornerShape(corner.coerceAtLeast(0.dp)),
        color = fill,
        contentColor = content,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(56.dp).graphicsLayer { rotationZ = turn }.background(badge, MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = ready to (keyProvider != null),
                    transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
                    label = "heroGlyphSwap",
                    modifier = Modifier.graphicsLayer { rotationZ = -turn },
                ) { (isReady, isKey) ->
                    Icon(
                        when {
                            isReady -> Icons.Rounded.Check
                            isKey -> Icons.Rounded.Key
                            else -> Icons.Rounded.Warning
                        },
                        contentDescription = null,
                        tint = glyph,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedContent(
                    targetState = text,
                    transitionSpec = {
                        (fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 4 }) togetherWith
                            (fadeOut(motion.fastEffectsSpec()) + slideOutVertically(motion.fastSpatialSpec()) { -it / 4 })
                    },
                    label = "heroText",
                ) { shown ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(shown.title, style = MaterialTheme.typography.titleLargeEmphasized)
                        Text(shown.body, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.78f))
                    }
                }
                AnimatedVisibility(
                    visible = keyProvider != null,
                    enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()),
                    exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(motion.fastSpatialSpec()),
                ) {
                    Button(onClick = { keyProvider?.let(onAddKey) }) {
                        Icon(Icons.Rounded.Key, null, Modifier.size(18.dp))
                        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.settings_voice_status_add_key))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Auto send
// ---------------------------------------------------------------------------

/** Dictation hands-free: send on silence, a shortcut in the + menu, and how long the silence lasts. */
@Composable
private fun VoiceAutoSendGroup(container: AppContainer, settings: AppSettings, scope: CoroutineScope) {
    val ms = settings.voiceAutoSendTimeoutMs
    var slide by remember(ms) { mutableFloatStateOf(ms.toFloat()) }
    SettingsAnchor(R.string.settings_anchor_auto_send) {
        SettingsGroup {
            item(onClick = { scope.launch { container.settings.setVoiceAutoSend(!settings.voiceAutoSend) } }) {
                SettingsItemRow(
                    title = stringResource(R.string.settings_voice_auto_send),
                    subtitle = stringResource(R.string.settings_voice_auto_send_desc),
                    leading = { SettingsIconBadge(Icons.AutoMirrored.Rounded.Send, SettingsTone.PRIMARY, 40.dp) },
                    trailing = {
                        Switch(
                            checked = settings.voiceAutoSend,
                            onCheckedChange = { on -> scope.launch { container.settings.setVoiceAutoSend(on) } },
                        )
                    },
                )
            }
            item(onClick = { scope.launch { container.settings.setVoiceAutoSendShowInPlus(!settings.voiceAutoSendShowInPlus) } }) {
                SettingsItemRow(
                    title = stringResource(R.string.settings_voice_auto_send_show_in_plus),
                    subtitle = stringResource(R.string.settings_voice_auto_send_show_in_plus_desc),
                    leading = { SettingsIconBadge(Icons.Rounded.Add, SettingsTone.SECONDARY, 40.dp) },
                    trailing = {
                        Switch(
                            checked = settings.voiceAutoSendShowInPlus,
                            onCheckedChange = { on -> scope.launch { container.settings.setVoiceAutoSendShowInPlus(on) } },
                        )
                    },
                )
            }
            item {
                val label = stringResource(R.string.settings_voice_auto_send_timeout)
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        SettingsIconBadge(Icons.Rounded.Timer, SettingsTone.SECONDARY, 40.dp)
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        SettingsStatusPill(
                            stringResource(R.string.settings_voice_auto_send_timeout_value, String.format(Locale.getDefault(), "%.1f", slide / 1000f)),
                            ok = false,
                        )
                    }
                    Slider(
                        value = slide,
                        onValueChange = { slide = it },
                        onValueChangeFinished = {
                            val snapped = (slide / 100).roundToInt() * 100
                            slide = snapped.toFloat()
                            scope.launch { container.settings.setVoiceAutoSendTimeoutMs(snapped) }
                        },
                        valueRange = 1000f..5000f,
                        steps = 39,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                    )
                    Text(
                        stringResource(R.string.settings_voice_auto_send_timeout_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Voice list
// ---------------------------------------------------------------------------

/** Voice list for the engine in use. Edge has its own grouped, searchable picker; everything else shares the choice sheet. */
@Composable
private fun VoicePickerHost(
    container: AppContainer,
    settings: AppSettings,
    scope: CoroutineScope,
    provider: VoiceProviderConfig?,
    onPhone: Boolean,
    voiceEpoch: Int,
    onRefreshEdge: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val isEdge = provider?.protocol == VoiceProtocol.EDGE
    val selection = provider?.let { settings.voiceSelections[it.id] }
    val model = selection?.ttsModel?.ifBlank { null } ?: provider?.ttsModel.orEmpty()
    val voice = selection?.ttsVoice?.ifBlank { null } ?: provider?.ttsVoice.orEmpty()

    // Samples ride on the same player as "Test voice"; their message id carries the voice.
    val playback by container.tts.state.collectAsStateWithLifecycle()
    val sample = remember(playback) {
        val playing = playback.messageId
            ?.takeIf { it.startsWith(TtsController.SAMPLE_PREFIX) && playback.phase != TtsPhase.IDLE }
            ?.removePrefix(TtsController.SAMPLE_PREFIX)
        VoiceSample(playing, playback.phase) { id ->
            if (playing == id) container.tts.stop() else container.tts.previewVoice(id)
        }
    }
    // Closing the picker, with or without a pick, ends whatever sample is still sounding.
    DisposableEffect(Unit) {
        onDispose {
            val now = container.tts.state.value
            if (now.messageId?.startsWith(TtsController.SAMPLE_PREFIX) == true && now.phase != TtsPhase.IDLE) container.tts.stop()
        }
    }

    if (isEdge && provider != null) {
        val groups by produceState(initialValue = emptyList<EdgeVoiceGroup>(), provider.id, voiceEpoch) {
            value = container.tts.edgeVoiceGroups()
        }
        EdgeVoicePickerDialog(
            groups = groups,
            selectedId = voice,
            loading = groups.isEmpty(),
            onSelect = { picked ->
                onDismiss()
                scope.launch { container.settings.setVoiceProviderTtsVoice(provider.id, picked.shortName) }
            },
            onRefresh = onRefreshEdge,
            onDismiss = onDismiss,
            sample = sample,
        )
        return
    }

    // null while the list is still being asked for.
    val options by produceState<List<TtsVoiceOption>?>(null, provider?.id, model, onPhone) {
        value = if (onPhone) {
            listOf(TtsVoiceOption("", uiContext.getString(R.string.settings_tts_voice_automatic))) + container.tts.systemVoices()
        } else container.tts.providerVoices(provider?.id, model)
    }
    VoiceChoiceSheet(
        title = stringResource(R.string.settings_tts_voice),
        options = options?.map { VoiceChoice(it.id, it.label, it.id.takeIf { id -> id.isNotBlank() && id != it.label }) },
        selectedId = if (onPhone) settings.ttsSystemVoice.orEmpty() else voice,
        customLabel = if (onPhone) null else stringResource(R.string.settings_tts_custom_voice),
        onSelect = { id ->
            onDismiss()
            scope.launch {
                if (onPhone) container.settings.setTtsSystemVoice(id.ifBlank { null })
                else provider?.let { container.settings.setVoiceProviderTtsVoice(it.id, id) }
            }
        },
        onDismiss = onDismiss,
        sample = sample,
    )
}
