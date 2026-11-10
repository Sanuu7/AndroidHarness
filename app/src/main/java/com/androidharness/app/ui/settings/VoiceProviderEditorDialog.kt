package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.audio.VoiceProtocol
import com.androidharness.app.data.audio.VoiceProviderConfig

/**
 * Add or edit one voice provider: name, protocol, base URL, models and which
 * directions it serves. Saving an untouched preset is harmless — the preset
 * identity is kept so future updates still reach it.
 */
@Composable
internal fun VoiceProviderEditorDialog(
    initial: VoiceProviderConfig,
    onSave: (VoiceProviderConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf(initial.name) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial.name.isBlank()) R.string.settings_voice_new_provider
                    else R.string.settings_voice_edit_provider,
                ),
            )
        },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_voice_provider_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.settings_voice_base_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    stringResource(R.string.settings_voice_protocol),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val protocols = VoiceProtocol.entries
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    protocols.chunked(1).forEach { row ->
                        row.forEach { protocol ->
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = draft.protocol == protocol,
                                    onClick = {
                                        draft = draft.copy(
                                            protocol = protocol,
                                            supportsStt = if (protocol == VoiceProtocol.CARTESIA) false else draft.supportsStt,
                                            supportsTts = if (protocol == VoiceProtocol.ASSEMBLYAI) false else draft.supportsTts,
                                        )
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 1),
                                ) { Text(protocol.label) }
                            }
                        }
                    }
                }

                androidx.compose.material3.OutlinedTextField(
                    value = draft.sttModel,
                    onValueChange = { draft = draft.copy(sttModel = it) },
                    label = { Text(stringResource(R.string.settings_stt_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = draft.ttsModel,
                    onValueChange = { draft = draft.copy(ttsModel = it, ttsVoice = draft.ttsVoice) },
                    label = { Text(stringResource(R.string.settings_tts_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = draft.ttsVoice,
                    onValueChange = { draft = draft.copy(ttsVoice = it) },
                    label = { Text(stringResource(R.string.settings_tts_voice)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_stt_engine),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = draft.supportsStt,
                        onCheckedChange = { draft = draft.copy(supportsStt = it) },
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_tts_output),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = draft.supportsTts,
                        onCheckedChange = { draft = draft.copy(supportsTts = it) },
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_voice_requires_key),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = draft.requiresKey,
                        onCheckedChange = { draft = draft.copy(requiresKey = it) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && baseUrl.isNotBlank(),
                onClick = {
                    onSave(
                        draft.copy(
                            name = name.trim(),
                            baseUrl = baseUrl.trim().trimEnd('/'),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.settings_tts_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) }
        },
    )
}

/** Read-only overview of the built-in presets the user can restore. */
@Composable
internal fun VoicePresetHint() {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.settings_voice_presets_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
