package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings

@Composable
internal fun SubagentSettingsSection(
    settings: AppSettings,
    onSubagentTools: (Boolean) -> Unit,
    onBackgroundSubagents: (Boolean) -> Unit = {},
    onCompletionNotifications: (Boolean) -> Unit = {},
    onOpenBackgroundSettings: () -> Unit = {},
    onSubagentsEnabled: (Boolean) -> Unit = {},
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val on = settings.subagentsEnabled
    SettingsPanel(Modifier.fillMaxWidth()) {
        SubagentSettingSwitch(R.string.subagents_enable_title, R.string.subagents_enable_desc, on, onSubagentsEnabled)
    }
    if (!on) Text(
        stringResource(R.string.subagents_enable_off_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingsPanel(Modifier.fillMaxWidth().alpha(if (on) 1f else .5f)) {
        SubagentSettingSwitch(R.string.subagents_allow_background, R.string.subagents_background_description,
            settings.backgroundSubagents, onBackgroundSubagents, enabled = on)
        SubagentSettingSwitch(R.string.subagents_notify_completion, R.string.subagents_notify_description,
            settings.notificationsEnabled && settings.subagentCompletionNotifications, onCompletionNotifications,
            enabled = on && settings.notificationsEnabled)
        SettingsAnchor(R.string.settings_anchor_subagent_tools) {
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(settings.subagentFullAccess, enabled = on, role = Role.Switch, onValueChange = onSubagentTools)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_anchor_subagent_tools), style = MaterialTheme.typography.titleSmall)
                    Text(
                        uiContext.getString(R.string.ui_let_subagents_edit_and_run_commands_in),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.subagentFullAccess, onCheckedChange = null, enabled = on)
            }
        }
    }
    SettingsPanel(Modifier.fillMaxWidth()) {
        SettingRow(androidx.compose.material.icons.Icons.Outlined.Notifications,
            stringResource(R.string.background_settings_title), stringResource(R.string.background_settings_description),
            onClick = onOpenBackgroundSettings)
    }
}

@Composable
private fun SubagentSettingSwitch(title: Int, description: Int, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().settingsAnchor(title).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(description), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
