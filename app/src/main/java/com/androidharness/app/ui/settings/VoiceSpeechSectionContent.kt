package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.audio.TtsError
import com.androidharness.app.data.audio.TtsVoiceOption
import com.androidharness.app.ui.common.TtsTermsDialog
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Voice & speech (multiple providers)
// ---------------------------------------------------------------------------

@Composable
internal fun VoiceProvidersSection(
    container: AppContainer,
    settings: AppSettings,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val voiceEngine = settings.voiceEngine
    val isInbuiltStt = voiceEngine == AppSettings.VOICE_ENGINE_INBUILT
    val ttsEngine = settings.ttsEngine
    val isSystemTts = ttsEngine == AppSettings.TTS_ENGINE_SYSTEM

    val providers by container.voiceProviders.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val sttProvider = remember(voiceEngine, providers) {
        providers.firstOrNull { it.id == voiceEngine }
    }
    val ttsProvider = remember(ttsEngine, providers) {
        providers.firstOrNull { it.id == ttsEngine }
    }
    val sttSelection = sttProvider?.let { settings.voiceSelections[it.id] }
    val ttsSelection = ttsProvider?.let { settings.voiceSelections[it.id] }
    val sttModel = sttSelection?.sttModel?.ifBlank { null } ?: sttProvider?.sttModel.orEmpty()
    val ttsModel = ttsSelection?.ttsModel?.ifBlank { null } ?: ttsProvider?.ttsModel.orEmpty()
    val ttsVoice = ttsSelection?.ttsVoice?.ifBlank { null }
        ?: ttsProvider?.ttsVoice.orEmpty()

    var keyEpoch by remember { mutableStateOf(0) }
    var sttProviderPicker by remember { mutableStateOf(false) }
    var ttsProviderPicker by remember { mutableStateOf(false) }
    var sttModelPicker by remember { mutableStateOf(false) }
    var ttsModelPicker by remember { mutableStateOf(false) }
    var voicePicker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<com.androidharness.app.data.audio.VoiceProviderConfig?>(null) }
    var managing by remember { mutableStateOf(false) }
    var ttsTermsError by remember { mutableStateOf<TtsError?>(null) }
    var modelEpoch by remember { mutableStateOf(0) }
    var voiceEpoch by remember { mutableStateOf(0) }

    val sttKey = sttProvider?.let { remember(keyEpoch, it.id) { container.voiceProviders.apiKeyFor(it.id) } }
    val ttsKey = ttsProvider?.let { remember(keyEpoch, it.id) { container.voiceProviders.apiKeyFor(it.id) } }
    val hasSttKey = sttKey != null || sttProvider?.requiresKey == false
    val hasTtsKey = ttsKey != null || ttsProvider?.requiresKey == false

    // Model lists come from the provider when it exposes /models, so new ids
    // appear without an app update. Fetched only while the dialog is open.
    val sttModels by produceState<List<String>?>(null, sttModelPicker, sttProvider?.id, hasSttKey, modelEpoch) {
        value = if (sttModelPicker && sttProvider != null) {
            com.androidharness.app.data.audio.VoiceGateway.sttModels(
                sttProvider!!,
                container.voiceProviders.apiKeyFor(sttProvider.id).orEmpty(),
            )
        } else null
    }
    val ttsModels by produceState<List<String>?>(null, ttsModelPicker, ttsProvider?.id, hasTtsKey, modelEpoch) {
        value = if (ttsModelPicker && ttsProvider != null) {
            com.androidharness.app.data.audio.VoiceGateway.ttsModels(
                ttsProvider!!,
                container.voiceProviders.apiKeyFor(ttsProvider.id).orEmpty(),
            )
        } else null
    }

    // "Test voice" failures otherwise have no surface to appear on.
    LaunchedEffect(Unit) {
        container.tts.errors.collect { error ->
            if (error.termsRequired) ttsTermsError = error
            else android.widget.Toast.makeText(uiContext, error.message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    SettingsHeader(uiContext.getString(R.string.ui_voice_speech))

    // --- Speech to text -----------------------------------------------------
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    SettingsAnchor(R.string.settings_anchor_stt) {
                        Text(stringResource(R.string.settings_anchor_stt), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        when {
                            isInbuiltStt -> uiContext.getString(R.string.ui_inbuilt_android_recognizer)
                            sttProvider == null -> uiContext.getString(R.string.settings_voice_no_providers)
                            hasSttKey -> sttProvider.name + " · " + sttModel
                            else -> sttProvider.name + " · " + uiContext.getString(R.string.settings_tts_groq_key_needed)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusText(
                    when {
                        isInbuiltStt -> uiContext.getString(R.string.ui_inbuilt)
                        hasSttKey -> sttProvider?.name ?: ""
                        else -> uiContext.getString(R.string.ui_no_key)
                    },
                    ok = isInbuiltStt || hasSttKey,
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(
                    AppSettings.VOICE_ENGINE_INBUILT to uiContext.getString(R.string.ui_inbuilt),
                    (sttProvider?.id ?: "cloud") to (sttProvider?.name ?: uiContext.getString(R.string.settings_stt_provider_cloud)),
                ).forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = voiceEngine == value ||
                            (!isInbuiltStt && index == 1),
                        onClick = {
                            if (index == 0) {
                                scope.launch { container.settings.setVoiceEngine(AppSettings.VOICE_ENGINE_INBUILT) }
                            } else {
                                sttProviderPicker = true
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    ) { Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                }
            }

            if (isInbuiltStt) {
                Text(
                    uiContext.getString(R.string.ui_uses_the_on_device_or_system_speech),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (sttProvider != null) {
                SettingsAnchor(R.string.settings_anchor_whisper) {
                    Text(
                        stringResource(R.string.settings_stt_model),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { sttModelPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(sttModel.ifBlank { stringResource(R.string.settings_voice_choose_model) }, Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
                OutlinedButton(
                    onClick = { managing = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_voice_manage_providers), Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            } else {
                OutlinedButton(
                    onClick = { managing = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_voice_add_provider))
                }
            }
        }
    }

    // --- Keys for whichever providers are in play ---------------------------
    // Providers that need no key (Edge) never show a key card.
    val keyProviders = remember(providers, voiceEngine, ttsEngine) {
        listOf(sttProvider, ttsProvider)
            .filterNotNull()
            .filter { it.requiresKey }
            .distinctBy { it.id }
    }
    keyProviders.forEach { provider ->
        VoiceProviderCard(
            container = container,
            provider = provider,
            scope = scope,
            keyEpoch = keyEpoch,
            onKeyChanged = { keyEpoch++ },
            onEdit = { editing = provider },
        )
    }

    SettingsHeader(uiContext.getString(R.string.settings_voice_auto_send))
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.settingsAnchor(R.string.settings_anchor_auto_send), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.settings_voice_auto_send), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_voice_auto_send_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.voiceAutoSend,
                    onCheckedChange = { scope.launch { container.settings.setVoiceAutoSend(it) } },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        stringResource(R.string.settings_voice_auto_send_show_in_plus),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.settings_voice_auto_send_show_in_plus_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.voiceAutoSendShowInPlus,
                    onCheckedChange = { scope.launch { container.settings.setVoiceAutoSendShowInPlus(it) } },
                )
            }
            val ms = settings.voiceAutoSendTimeoutMs
            Text(
                uiContext.getString(R.string.settings_voice_auto_send_timeout) + " · " +
                    uiContext.getString(R.string.settings_voice_auto_send_timeout_value, (ms / 1000f).toString()),
                style = MaterialTheme.typography.titleSmall,
            )
            var slideTimeoutMs by remember(ms) { mutableStateOf(ms.toFloat()) }
            androidx.compose.material3.Slider(
                value = slideTimeoutMs,
                onValueChange = { slideTimeoutMs = it },
                onValueChangeFinished = {
                    val snapped = (slideTimeoutMs / 100).roundToInt() * 100
                    slideTimeoutMs = snapped.toFloat()
                    scope.launch { container.settings.setVoiceAutoSendTimeoutMs(snapped) }
                },
                valueRange = 1000f..5000f,
                steps = 39,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.settings_voice_auto_send_timeout_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // --- Text to speech -----------------------------------------------------
    SettingsHeader(uiContext.getString(R.string.settings_tts_output))
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    SettingsAnchor(R.string.settings_anchor_tts) {
                        Text(stringResource(R.string.settings_anchor_tts), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        when {
                            isSystemTts -> uiContext.getString(R.string.settings_tts_device_hint)
                            ttsProvider == null -> uiContext.getString(R.string.settings_voice_no_providers)
                            hasTtsKey -> ttsProvider.name + " · " + ttsModel
                            else -> ttsProvider.name + " · " + uiContext.getString(R.string.settings_tts_groq_key_needed)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusText(
                    when {
                        isSystemTts -> uiContext.getString(R.string.ui_inbuilt)
                        hasTtsKey -> ttsProvider?.name ?: ""
                        else -> uiContext.getString(R.string.ui_no_key)
                    },
                    ok = isSystemTts || hasTtsKey,
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(
                    AppSettings.TTS_ENGINE_SYSTEM to uiContext.getString(R.string.settings_tts_device),
                    (ttsProvider?.id ?: "cloud") to (ttsProvider?.name ?: uiContext.getString(R.string.settings_tts_provider)),
                ).forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = ttsEngine == value || (!isSystemTts && index == 1),
                        onClick = {
                            if (index == 0) {
                                scope.launch { container.settings.setTtsEngine(AppSettings.TTS_ENGINE_SYSTEM) }
                            } else {
                                ttsProviderPicker = true
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    ) { Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                }
            }

            if (!isSystemTts && ttsProvider != null) {
                Text(
                    stringResource(R.string.settings_tts_model),
                    Modifier.fillMaxWidth().settingsAnchor(R.string.settings_tts_model),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { ttsModelPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        com.androidharness.app.data.audio.VoiceGateway.modelLabel(ttsProvider, ttsModel),
                        Modifier.weight(1f),
                    )
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }

            Text(
                stringResource(R.string.settings_tts_voice),
                Modifier.fillMaxWidth().settingsAnchor(R.string.settings_tts_voice),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { voicePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        isSystemTts -> settings.ttsSystemVoice?.replace('_', ' ')
                            ?: uiContext.getString(R.string.settings_tts_voice_automatic)
                        else -> ttsVoice.ifBlank { uiContext.getString(R.string.settings_tts_voice_automatic) }
                    },
                    Modifier.weight(1f),
                )
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }

            Text(
                stringResource(R.string.settings_tts_speed),
                Modifier.fillMaxWidth().settingsAnchor(R.string.settings_tts_speed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(0.75f to "0.75×", 1f to "1×", 1.25f to "1.25×", 1.5f to "1.5×")
                    .forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = settings.ttsSpeed == value,
                            onClick = { scope.launch { container.settings.setTtsSpeed(value) } },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 4),
                        ) { Text(label) }
                    }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    SettingsAnchor(R.string.settings_tts_always_speak) {
                        Text(stringResource(R.string.settings_tts_always_speak), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        stringResource(R.string.settings_tts_always_speak_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.ttsAlwaysSpeak,
                    onCheckedChange = { scope.launch { container.settings.setTtsAlwaysSpeak(it) } },
                )
            }

            OutlinedButton(onClick = { container.tts.preview() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.settings_tts_test))
            }

            if (!isSystemTts) {
                OutlinedButton(onClick = { managing = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_voice_manage_providers), Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
        }
    }

    // --- Dialogs ------------------------------------------------------------
    if (sttProviderPicker) {
        VoiceProviderPickerDialog(
            providers = providers,
            selectedId = sttProvider?.id,
            forTts = false,
            onSelect = { id ->
                sttProviderPicker = false
                scope.launch { container.settings.setVoiceEngine(id) }
            },
            onManage = { managing = true },
            onDismiss = { sttProviderPicker = false },
        )
    }
    if (ttsProviderPicker) {
        VoiceProviderPickerDialog(
            providers = providers,
            selectedId = ttsProvider?.id,
            forTts = true,
            onSelect = { id ->
                ttsProviderPicker = false
                scope.launch { container.settings.setTtsEngine(id) }
            },
            onManage = { managing = true },
            onDismiss = { ttsProviderPicker = false },
        )
    }
    if (sttModelPicker && sttProvider != null) {
        val activeStt = sttProvider
        VoiceModelPickerDialog(
            title = stringResource(R.string.settings_stt_model),
            models = sttModels,
            selected = sttModel,
            labelFor = { it },
            onSelect = { id ->
                sttModelPicker = false
                scope.launch { container.settings.setVoiceProviderSttModel(activeStt.id, id) }
            },
            onRefresh = { modelEpoch++ },
            onDismiss = { sttModelPicker = false },
        )
    }
    if (ttsModelPicker && ttsProvider != null) {
        val activeTts = ttsProvider
        VoiceModelPickerDialog(
            title = stringResource(R.string.settings_tts_model),
            models = ttsModels,
            selected = ttsModel,
            labelFor = { id -> com.androidharness.app.data.audio.VoiceGateway.modelLabel(activeTts, id) },
            onSelect = { id ->
                ttsModelPicker = false
                scope.launch {
                    container.settings.setVoiceProviderTtsModel(activeTts.id, id)
                    val voice = com.androidharness.app.data.audio.VoiceGateway.defaultVoiceFor(activeTts, id)
                    if (voice != null) container.settings.setVoiceProviderTtsVoice(activeTts.id, voice)
                }
            },
            onRefresh = { modelEpoch++ },
            onDismiss = { ttsModelPicker = false },
        )
    }
    if (voicePicker) {
        val deviceVoices by produceState(initialValue = emptyList<TtsVoiceOption>(), ttsEngine) {
            value = if (ttsEngine == AppSettings.TTS_ENGINE_SYSTEM) container.tts.systemVoices() else emptyList()
        }
        // Edge has its own sectioned picker: 300+ voices need grouping and search.
        val isEdge = ttsProvider?.protocol == com.androidharness.app.data.audio.VoiceProtocol.EDGE
        val edgeGroups by produceState(initialValue = emptyList<com.androidharness.app.data.audio.EdgeVoiceGroup>(), ttsProvider?.id, voiceEpoch) {
            value = if (isEdge) container.tts.edgeVoiceGroups() else emptyList()
        }
        val providerVoices by produceState(initialValue = emptyList<TtsVoiceOption>(), ttsProvider?.id, ttsModel) {
            value = if (isSystemTts || isEdge) emptyList() else container.tts.providerVoices(ttsProvider?.id, ttsModel)
        }
        if (isEdge) {
            EdgeVoicePickerDialog(
                groups = edgeGroups,
                selectedId = ttsVoice,
                loading = edgeGroups.isEmpty(),
                onSelect = { voice ->
                    voicePicker = false
                    scope.launch {
                        ttsProvider?.let { container.settings.setVoiceProviderTtsVoice(it.id, voice.shortName) }
                    }
                },
                onRefresh = {
                    scope.launch {
                        container.tts.refreshEdgeVoices()
                        // Bumping the epoch re-runs produceState, which reads the
                        // freshly refreshed catalogue.
                        voiceEpoch++
                    }
                },
                onDismiss = { voicePicker = false },
            )
        } else {
            VoicePickerDialog(
                options = if (isSystemTts) {
                    listOf(TtsVoiceOption("", uiContext.getString(R.string.settings_tts_voice_automatic))) + deviceVoices
                } else {
                    providerVoices
                },
                selectedId = if (isSystemTts) settings.ttsSystemVoice.orEmpty() else ttsVoice,
                onSelect = { id ->
                    voicePicker = false
                    scope.launch {
                        if (isSystemTts) container.settings.setTtsSystemVoice(id.ifBlank { null })
                        else ttsProvider?.let { container.settings.setVoiceProviderTtsVoice(it.id, id) }
                    }
                },
                onDismiss = { voicePicker = false },
            )
        }
    }
    if (managing) {
        VoiceProvidersManagerDialog(
            container = container,
            providers = providers,
            scope = scope,
            onEdit = { provider ->
                managing = false
                editing = provider
            },
            onDismiss = { managing = false },
        )
    }
    editing?.let { provider ->
        VoiceProviderEditorDialog(
            initial = provider,
            onSave = { updated ->
                editing = null
                scope.launch {
                    container.voiceProviders.save(updated)
                    keyEpoch++
                }
            },
            onDismiss = { editing = null },
        )
    }

    ttsTermsError?.let { TtsTermsDialog(it) { ttsTermsError = null } }
}

/** Lists every provider, with add and edit affordances. */
@Composable
private fun VoiceProvidersManagerDialog(
    container: AppContainer,
    providers: List<com.androidharness.app.data.audio.VoiceProviderConfig>,
    scope: kotlinx.coroutines.CoroutineScope,
    onEdit: (com.androidharness.app.data.audio.VoiceProviderConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_voice_manage_providers)) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                providers.forEach { provider ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val hasKey = remember(provider.id) { container.voiceProviders.apiKeyFor(provider.id) != null }
                        Column(Modifier.weight(1f)) {
                            Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                provider.protocol.label + when {
                                    !provider.requiresKey -> " · " + uiContext.getString(R.string.settings_voice_no_key_needed)
                                    hasKey -> " · " + uiContext.getString(R.string.ui_on)
                                    else -> " · " + uiContext.getString(R.string.ui_no_key)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onEdit(provider) }) {
                            Text(stringResource(R.string.settings_voice_edit_provider))
                        }
                    }
                }
                TextButton(
                    onClick = {
                        onEdit(container.voiceProviders.newCustomProvider())
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_voice_add_provider))
                }
                VoicePresetHint()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_done)) }
        },
    )
}

/** Voice list for the selected engine, with a free-form id for unknown voices. */
@Composable
private fun VoicePickerDialog(
    options: List<TtsVoiceOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_tts_voice)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option.id == selectedId,
                            onClick = { onSelect(option.id) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            if (option.label != option.id && option.id.isNotBlank()) {
                                Text(
                                    option.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text(stringResource(R.string.settings_tts_custom_voice)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                com.androidharness.app.ui.common.Button(
                    enabled = custom.isNotBlank(),
                    onClick = { onSelect(custom.trim()) },
                ) { Text(stringResource(R.string.settings_tts_save)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_done)) }
        },
    )
}
