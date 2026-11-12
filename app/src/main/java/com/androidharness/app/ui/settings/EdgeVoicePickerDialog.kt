package com.androidharness.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.audio.EdgeVoice
import com.androidharness.app.data.audio.EdgeVoiceGroup

/**
 * Voice picker for the Edge catalogue: 300+ voices across 140 languages, so a
 * flat list is unusable. Voices are grouped under a language header, the app's
 * language first, with a search box that matches name, language and locale.
 */
@Composable
internal fun EdgeVoicePickerDialog(
    groups: List<EdgeVoiceGroup>,
    selectedId: String,
    loading: Boolean,
    onSelect: (EdgeVoice) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    sample: VoiceSample? = null,
) {
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Filter first, then drop empty groups so a search never shows bare headers.
    val filtered = remember(groups, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            groups
        } else {
            groups.mapNotNull { group ->
                val voices = group.voices.filter { voice ->
                    voice.displayName.contains(needle, ignoreCase = true) ||
                        group.title.contains(needle, ignoreCase = true) ||
                        voice.locale.contains(needle, ignoreCase = true) ||
                        // "francisca pt" should also work.
                        (needle.contains(' ') && needle.split(' ')
                            .filter { it.isNotBlank() }
                            .all { part ->
                                voice.displayName.contains(part, ignoreCase = true) ||
                                    group.title.contains(part, ignoreCase = true) ||
                                    voice.locale.contains(part, ignoreCase = true)
                            })
                }
                if (voices.isEmpty()) null else group.copy(voices = voices)
            }
        }
    }
    val total = remember(filtered) { filtered.sumOf { it.voices.size } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_tts_voice), Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = !loading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        },
        text = {
            Column {
                VoiceField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_voice_search_voices)) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    when {
                        loading -> stringResource(R.string.settings_voice_loading_voices)
                        total == 0 -> stringResource(R.string.settings_voice_no_match)
                        else -> stringResource(R.string.settings_voice_voice_count, total)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(4.dp))
                LazyColumn(Modifier.heightIn(max = 400.dp), state = listState) {
                    filtered.forEach { group ->
                        item(key = "header-${group.locale}") {
                            Text(
                                group.title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(group.voices, key = { it.shortName }) { voice ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(voice) }
                                    .padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = voice.shortName == selectedId,
                                    onClick = { onSelect(voice) },
                                )
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            voice.displayName,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        if (voice.isMultilingual) {
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                stringResource(R.string.settings_voice_multilingual),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    val subtitle = when {
                                        voice.isFemale -> stringResource(R.string.settings_voice_female)
                                        voice.isMale -> stringResource(R.string.settings_voice_male)
                                        else -> voice.locale
                                    }
                                    Text(
                                        subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (sample != null) {
                                    VoiceListenButton(
                                        label = voice.displayName,
                                        phase = sample.phaseOf(voice.shortName),
                                        onClick = { sample.onToggle(voice.shortName) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_done)) }
        },
    )
}
