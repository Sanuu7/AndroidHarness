package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.data.env.DhizukuManager
import com.androidharness.app.data.env.DhizukuStatus
import kotlinx.coroutines.delay
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.OutlinedButton

@Composable
internal fun DhizukuCard(manager: DhizukuManager) {
    val connection by manager.state.collectAsStateWithLifecycle()
    LaunchedEffect(manager) { while (true) { manager.refresh(); delay(3_000) } }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dhizuku_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(when (connection.status) {
                DhizukuStatus.NOT_INSTALLED -> R.string.ui_not_installed_optional
                DhizukuStatus.NOT_RUNNING -> R.string.dhizuku_not_running
                DhizukuStatus.NEEDS_PERMISSION -> R.string.ui_running_grant_androidharness_access
                DhizukuStatus.REQUESTING -> R.string.dhizuku_requesting
                DhizukuStatus.GRANTED -> R.string.dhizuku_connected
                DhizukuStatus.DENIED -> R.string.dhizuku_denied
                DhizukuStatus.ERROR -> R.string.dhizuku_error
            }), style = MaterialTheme.typography.bodySmall,
                color = if (connection.status == DhizukuStatus.GRANTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            connection.ownerPackage?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            Text(stringResource(R.string.dhizuku_explanation), style = MaterialTheme.typography.bodySmall)
            if (connection.status in setOf(DhizukuStatus.NEEDS_PERMISSION, DhizukuStatus.DENIED)) {
                Button(onClick = manager::requestPermission) { Text(stringResource(R.string.shell_access_grant, "Dhizuku")) }
            } else OutlinedButton(onClick = manager::refresh, enabled = connection.status != DhizukuStatus.REQUESTING) {
                Text(stringResource(R.string.settings_env_refresh_status))
            }
        }
    }
}
