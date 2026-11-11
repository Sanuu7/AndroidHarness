package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.audio.VoiceProviderConfig
import kotlinx.coroutines.launch

/**
 * The API key field for one provider, shown inside that provider's own panel.
 *
 * There is no standalone "API key" section any more: the key belongs to the
 * provider, and choosing a provider is the moment the user needs it. Without a
 * saved key the field is open; with one, a compact row offers replace/remove.
 */
@Composable
internal fun VoiceProviderKeyRow(
    container: AppContainer,
    provider: VoiceProviderConfig,
    scope: kotlinx.coroutines.CoroutineScope,
    keyEpoch: Int,
    onKeyChanged: () -> Unit,
) {
    val context = LocalContext.current
    val uiContext = LocalContext.current
    var editing by remember(provider.id) { mutableStateOf(false) }
    var draft by remember(provider.id) { mutableStateOf("") }
    var checking by remember(provider.id) { mutableStateOf(false) }
    var status by remember(provider.id) { mutableStateOf<String?>(null) }
    val hasKey = remember(keyEpoch, provider.id) { container.voiceProviders.apiKeyFor(provider.id) != null }
    // Open the field straight away when the provider still needs a key.
    val open = editing || !hasKey

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_voice_provider_key, provider.name),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasKey) {
                Text(
                    stringResource(R.string.ui_on),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (open) {
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
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.settings_voice_provider_key, provider.name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.androidharness.app.ui.common.Button(
                    enabled = draft.isNotBlank() && !checking,
                    onClick = {
                        checking = true
                        status = uiContext.getString(R.string.ui_checking)
                        scope.launch {
                            val key = draft.trim()
                            val result = com.androidharness.app.data.audio.VoiceGateway.validateKey(provider, key)
                            if (result.isSuccess) {
                                container.voiceProviders.saveApiKey(provider.id, key)
                                draft = ""
                                editing = false
                                status = uiContext.getString(R.string.ui_key_verified_and_saved)
                                onKeyChanged()
                            } else {
                                status = result.exceptionOrNull()?.message
                                    ?: uiContext.getString(R.string.ui_verification_failed)
                            }
                            checking = false
                        }
                    },
                ) {
                    Text(
                        if (checking) stringResource(R.string.ui_checking)
                        else stringResource(R.string.ui_save_and_check),
                    )
                }
                if (hasKey) {
                    com.androidharness.app.ui.common.TextButton(onClick = {
                        container.voiceProviders.removeApiKey(provider.id)
                        editing = false
                        onKeyChanged()
                    }) {
                        Text(
                            stringResource(R.string.settings_action_remove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            com.androidharness.app.ui.common.TextButton(
                onClick = { editing = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_voice_replace_key))
            }
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
