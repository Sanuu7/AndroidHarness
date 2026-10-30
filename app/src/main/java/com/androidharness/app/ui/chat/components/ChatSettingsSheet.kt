package com.androidharness.app.ui.chat.components

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.OutlinedCard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ImageFrequency
import com.androidharness.app.ui.common.imageFrequencyLabel
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.RadioButton
import com.androidharness.app.ui.common.TextButton

/**
 * Per-chat instructions and the personalization switch. Personalization off
 * keeps the text stored but skips both the instructions and saved memory for
 * this conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatSettingsSheet(
    instructions: String,
    useGlobalInstructions: Boolean,
    globalInstructions: String,
    personalization: Boolean,
    imageFrequency: ImageFrequency,
    saving: Boolean,
    onSave: (String, Boolean, ImageFrequency, Boolean) -> Unit,
    onDismiss: () -> Unit,
    globalImageFrequency: ImageFrequency = ImageFrequency.DEFAULT,
    saveError: String? = null,
    onOpenAgents: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by rememberSaveable { mutableStateOf(instructions) }
    var inherit by rememberSaveable { mutableStateOf(useGlobalInstructions) }
    var enabled by rememberSaveable { mutableStateOf(personalization) }
    var frequency by rememberSaveable { mutableStateOf(imageFrequency) }
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().imePadding()
                .padding(horizontal = 20.dp).padding(bottom = 28.dp),
        ) {
            Text(stringResource(R.string.chat_settings_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (onOpenAgents != null) {
                    OutlinedCard(onClick = onOpenAgents, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.chat_agents_title), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.chat_agents_description), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Row(Modifier.fillMaxWidth().toggleable(value = enabled, enabled = !saving, role = Role.Switch,
                    onValueChange = { enabled = it }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.chat_settings_personalization),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.chat_settings_personalization_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = null, enabled = !saving)
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.chat_settings_instructions), style = MaterialTheme.typography.titleSmall)
                for (option in listOf(true, false)) {
                    Row(Modifier.fillMaxWidth().selectable(selected = inherit == option, enabled = !saving,
                        role = Role.RadioButton, onClick = { inherit = option }).padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = inherit == option, onClick = null, enabled = !saving)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (option) R.string.chat_instructions_inherit else R.string.chat_instructions_replace),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (inherit) {
                    Text(globalInstructions.ifBlank { stringResource(R.string.chat_instructions_global_empty) },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(ConversationConfig.MAX_INSTRUCTIONS_CHARS) },
                    label = { Text(stringResource(R.string.chat_settings_instructions)) },
                    placeholder = { Text(stringResource(R.string.chat_settings_instructions_hint)) },
                    supportingText = {
                        Text(stringResource(if (enabled) R.string.chat_instructions_replace_help
                            else R.string.chat_settings_instructions_off))
                    },
                    enabled = !saving,
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.chat_settings_images), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.chat_settings_images_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                ImageFrequency.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(
                                selected = frequency == option,
                                role = Role.RadioButton,
                                enabled = !saving,
                                onClick = { frequency = option },
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = frequency == option, onClick = null, enabled = !saving)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (option == ImageFrequency.DEFAULT) stringResource(R.string.chat_image_frequency_inherit,
                                stringResource(imageFrequencyLabel(globalImageFrequency)))
                            else stringResource(imageFrequencyLabel(option)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            if (saveError != null) Text(saveError, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.settings_action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(text, enabled, frequency, inherit) }, enabled = !saving) {
                    Text(stringResource(R.string.settings_action_save))
                }
            }
        }
    }
}

