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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.data.audio.VoiceProviderConfig
import kotlinx.coroutines.launch

/**
 * Picker for the voice providers the user configured, plus the entry point to
 * manage them. Mirrors the provider list pattern used by chat connections.
 */
@Composable
internal fun VoiceProviderPickerDialog(
    providers: List<VoiceProviderConfig>,
    selectedId: String?,
    forTts: Boolean,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val usable = providers.filter { if (forTts) it.supportsTts else it.supportsStt }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (forTts) R.string.settings_tts_provider else R.string.settings_stt_provider)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (usable.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_voice_no_providers),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                usable.forEach { provider ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = provider.id == selectedId,
                            onClick = { onSelect(provider.id) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                provider.protocol.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onManage() }) {
                Icon(Icons.Outlined.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.settings_voice_manage_providers))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_done)) }
        },
    )
}

/**
 * Model list for the selected provider. Falls back to the preset catalog when
 * the provider has no /models route, and always allows a free-form id.
 */
@Composable
internal fun VoiceModelPickerDialog(
    title: String,
    models: List<String>?,
    selected: String,
    labelFor: (String) -> String,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    models == null -> Text(
                        stringResource(R.string.settings_tts_models_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> models.forEach { id ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = id == selected, onClick = { onSelect(id) })
                            Column(Modifier.weight(1f)) {
                                Text(labelFor(id), style = MaterialTheme.typography.bodyLarge)
                                if (labelFor(id) != id) {
                                    Text(
                                        id,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text(stringResource(R.string.settings_tts_custom_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.androidharness.app.ui.common.Button(
                        enabled = custom.isNotBlank(),
                        onClick = { onSelect(custom.trim()) },
                    ) { Text(stringResource(R.string.settings_tts_save)) }
                    TextButton(onClick = onRefresh) {
                        Text(stringResource(R.string.settings_tts_refresh))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_done)) }
        },
    )
}

/**
 * One provider card: state, key management and removal. Follows the same
 * SettingsPanel shape as the old single-provider card.
 */
@Composable
internal fun VoiceProviderCard(
    container: com.androidharness.app.AppContainer,
    provider: VoiceProviderConfig,
    scope: kotlinx.coroutines.CoroutineScope,
    keyEpoch: Int,
    onKeyChanged: () -> Unit,
    onEdit: () -> Unit,
) {
    val uiContext = LocalContext.current
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val hasKey = remember(keyEpoch, provider.id) { container.voiceProviders.apiKeyFor(provider.id) != null }

    SettingsPanel(Modifier.fillMaxWidth().settingsAnchor(R.string.settings_tts_groq_key)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(provider.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (hasKey) stringResource(R.string.settings_voice_key_saved)
                        else stringResource(R.string.settings_voice_key_needed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusText(
                    if (hasKey) stringResource(R.string.ui_on) else stringResource(R.string.ui_no_key),
                    ok = hasKey,
                )
            }

            when {
                expanded -> {
                    if (provider.keyUrl.isNotBlank()) {
                        com.androidharness.app.ui.common.TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(provider.keyUrl),
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_voice_get_key))
                        }
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        label = {
                            Text(
                                if (hasKey) stringResource(R.string.ui_replace_groq_api_key)
                                else stringResource(R.string.ui_paste_groq_api_key),
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.androidharness.app.ui.common.Button(
                            enabled = keyDraft.isNotBlank() && !checking,
                            onClick = {
                                checking = true
                                status = uiContext.getString(R.string.ui_checking)
                                scope.launch {
                                    val key = keyDraft.trim()
                                    val result = com.androidharness.app.data.audio.VoiceGateway
                                        .validateKey(provider, key)
                                    if (result.isSuccess) {
                                        container.voiceProviders.saveApiKey(provider.id, key)
                                        keyDraft = ""
                                        expanded = false
                                        status = uiContext.getString(R.string.ui_key_verified_and_saved)
                                        onKeyChanged()
                                    } else {
                                        status = result.exceptionOrNull()?.message
                                            ?: uiContext.getString(R.string.ui_verification_failed)
                                    }
                                    checking = false
                                }
                            },
                        ) { Text(if (checking) stringResource(R.string.ui_checking) else stringResource(R.string.ui_save_and_check)) }
                        TextButton(onClick = { expanded = false; keyDraft = ""; status = null }) {
                            Text(stringResource(R.string.settings_action_cancel))
                        }
                    }
                }
                hasKey -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.androidharness.app.ui.common.TextButton(onClick = { expanded = true }) {
                        Text(stringResource(R.string.settings_voice_replace_key))
                    }
                    com.androidharness.app.ui.common.TextButton(
                        onClick = {
                            container.voiceProviders.removeApiKey(provider.id)
                            onKeyChanged()
                        },
                    ) {
                        Text(
                            stringResource(R.string.settings_action_remove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                else -> com.androidharness.app.ui.common.Button(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_voice_add_key)) }
            }

            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.androidharness.app.ui.common.TextButton(onClick = onEdit) {
                    Text(stringResource(R.string.settings_voice_edit_provider))
                }
                if (provider.custom) {
                    com.androidharness.app.ui.common.TextButton(
                        onClick = {
                            scope.launch {
                                container.voiceProviders.delete(provider.id)
                                onKeyChanged()
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.settings_action_remove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
