package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.AppContainer
import com.androidharness.app.ui.common.SecureScreenEffect
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun RememberedPermissionsSection(container: AppContainer) {
    val grants by container.runManager.rememberedGrants.collectAsStateWithLifecycle()
    val workspace by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)
    val selected = grants.filter { it.workspacePath == workspace?.displayPath }
    SecureScreenEffect(container, selected.isNotEmpty())
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.settings_permissions_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_permissions_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected.isEmpty()) Text(stringResource(R.string.settings_permissions_empty), style = MaterialTheme.typography.bodyMedium)
            selected.forEach { grant ->
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(grant.key.replaceFirst("#", ": "), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.settings_permissions_run, grant.sessionId.take(8)), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { container.runManager.revokeGrant(grant) }) { Text(stringResource(R.string.settings_action_revoke)) }
                }
            }
        }
    }
}
