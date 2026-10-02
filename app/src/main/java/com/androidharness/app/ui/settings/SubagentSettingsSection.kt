package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.androidharness.app.data.AppSettings

@Composable
internal fun SubagentSettingsSection(
    settings: AppSettings,
    onSubagentTools: (Boolean) -> Unit,
) {
    SettingsPanel(Modifier.fillMaxWidth()) {
        SettingsAnchor("Subagent action tools") {
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(settings.subagentFullAccess, role = Role.Switch, onValueChange = onSubagentTools)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Subagent action tools", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Let subagents edit and run commands in Act mode. They follow your permissions; Plan stays read-only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.subagentFullAccess, onCheckedChange = null)
            }
        }
    }
}
