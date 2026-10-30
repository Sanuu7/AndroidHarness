package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import androidx.annotation.StringRes
import androidx.compose.material.icons.outlined.Code
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.ChatStartMode
import com.androidharness.app.data.CodeStartMode
import com.androidharness.app.data.ImageFrequency
import com.androidharness.app.data.ConversationConfig
import kotlinx.coroutines.launch
import com.androidharness.app.data.StartScreen
import com.androidharness.app.ui.common.imageFrequencyDescription
import com.androidharness.app.ui.common.imageFrequencyLabel
import com.androidharness.app.ui.common.RadioButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun ChatOptionsSection(
    settings: AppSettings,
    onStartScreen: (StartScreen) -> Unit,
    onChatStart: (ChatStartMode) -> Unit,
    onKeepThinkingCollapsed: (Boolean) -> Unit,
    onImageFrequency: (ImageFrequency) -> Unit,
    onInstructions: suspend (String) -> Unit,
    agentCount: Int = 0,
    onManageAgents: () -> Unit = {},
) {
    SettingsAnchor(R.string.chat_agents_title) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onManageAgents).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                com.androidharness.app.ui.chat.components.AgentAvatar("spark")
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.chat_agents_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (agentCount == 0) R.string.chat_agents_description else R.string.chat_agents_count, agentCount),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(androidx.compose.material.icons.Icons.Outlined.ChevronRight, null)
            }
        }
    }
    SettingsAnchor(R.string.chat_global_instructions) {
        val scope = rememberCoroutineScope()
        var editing by rememberSaveable { mutableStateOf(false) }
        var text by rememberSaveable(settings.chatInstructions) { mutableStateOf(settings.chatInstructions) }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val saveFailed = stringResource(R.string.chat_settings_save_failed)
        SettingsPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { text = settings.chatInstructions; error = null; editing = true }
                .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.chat_global_instructions), style = MaterialTheme.typography.titleSmall)
                    Text(settings.chatInstructions.ifBlank { stringResource(R.string.chat_global_instructions_summary) },
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(androidx.compose.material.icons.Icons.Outlined.Edit, null, Modifier.padding(start = 12.dp).size(20.dp))
            }
        }
        if (editing) AlertDialog(
            onDismissRequest = { if (!saving) editing = false },
            title = { Text(stringResource(R.string.chat_global_instructions)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.chat_global_instructions_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = text, onValueChange = { text = it.take(ConversationConfig.MAX_INSTRUCTIONS_CHARS) },
                    enabled = !saving, label = { Text(stringResource(R.string.chat_settings_instructions)) },
                    placeholder = { Text(stringResource(R.string.chat_settings_instructions_hint)) },
                    minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            } },
            confirmButton = { TextButton(onClick = {
                saving = true; error = null
                scope.launch {
                    try { onInstructions(text); editing = false }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: saveFailed }
                    finally { saving = false }
                }
            }, enabled = !saving) { Text(stringResource(R.string.settings_action_save)) } },
            dismissButton = { TextButton(onClick = { editing = false }, enabled = !saving) {
                Text(stringResource(R.string.settings_action_cancel))
            } },
        )
    }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().toggleable(value = settings.chatKeepThinkingCollapsed, role = Role.Switch, onValueChange = onKeepThinkingCollapsed).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.chat_keep_thinking_collapsed), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.chat_keep_thinking_collapsed_desc), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = settings.chatKeepThinkingCollapsed, onCheckedChange = null)
        }
    }
    SettingsAnchor(R.string.settings_image_frequency) {
        ChoicePanel(
            title = R.string.settings_image_frequency,
            options = ImageFrequency.entries,
            selected = settings.chatImageFrequency,
            onSelect = onImageFrequency,
            label = { imageFrequencyLabel(it) },
            description = { imageFrequencyDescription(it) },
        )
    }
    SettingsAnchor(R.string.settings_start_screen) {
        ChoicePanel(
            title = R.string.settings_start_screen,
            options = StartScreen.entries,
            selected = settings.startScreen,
            onSelect = onStartScreen,
            label = {
                when (it) {
                    StartScreen.CODE -> R.string.conversation_code
                    StartScreen.CHAT -> R.string.conversation_chat
                    StartScreen.LAST_SELECTED -> R.string.settings_start_last_selected
                }
            },
            description = {
                when (it) {
                    StartScreen.CODE -> R.string.settings_start_code_desc
                    StartScreen.CHAT -> R.string.settings_start_chat_desc
                    StartScreen.LAST_SELECTED -> R.string.settings_start_last_desc
                }
            },
        )
    }
    SettingsAnchor(R.string.settings_chat_start) {
        ChoicePanel(
            title = R.string.settings_chat_start,
            options = ChatStartMode.entries,
            selected = settings.chatStart,
            onSelect = onChatStart,
            label = {
                when (it) {
                    ChatStartMode.NEW_CHAT -> R.string.settings_chat_start_new
                    ChatStartMode.LAST_CHAT -> R.string.settings_chat_start_last
                }
            },
            description = {
                when (it) {
                    ChatStartMode.NEW_CHAT -> R.string.settings_chat_start_new_desc
                    ChatStartMode.LAST_CHAT -> R.string.settings_chat_start_last_desc
                }
            },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun CodeStartOptionsSection(settings: AppSettings, onCodeStart: (CodeStartMode) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    fun label(mode: CodeStartMode) = if (mode == CodeStartMode.NEW_CODE) R.string.settings_start_code_new else R.string.settings_start_code_last
    fun description(mode: CodeStartMode) = if (mode == CodeStartMode.NEW_CODE) R.string.settings_start_code_new_desc else R.string.settings_start_code_last_desc
    SettingsAnchor(R.string.settings_start_code) {
        SettingsFeatureRow(androidx.compose.material.icons.Icons.Outlined.Code, stringResource(R.string.settings_start_code),
            stringResource(label(settings.codeStart)), { choosing = true })
    }
    if (choosing) androidx.compose.material3.ModalBottomSheet(onDismissRequest = { choosing = false }) {
        Column(Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.settings_start_code), style = MaterialTheme.typography.titleLarge)
            CodeStartMode.entries.forEach { mode ->
                Surface(onClick = { onCodeStart(mode); choosing = false }, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    color = if (mode == settings.codeStart) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(mode == settings.codeStart, null)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(label(mode)), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(description(mode)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> ChoicePanel(
    @StringRes title: Int,
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    description: (T) -> Int,
    onSelect: (T) -> Unit,
) {
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.selectableGroup()) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
            options.forEachIndexed { index, option ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(
                        selected = selected == option,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    ).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(label(option)), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(description(option)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RadioButton(selected = selected == option, onClick = null)
                }
                if (index < options.lastIndex) HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f),
                )
            }
        }
    }
}
