package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun LocalExperimentalSwitch(container: AppContainer) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var warning by remember { mutableStateOf(false) }
    val onToggle: (Boolean) -> Unit = { enabled ->
        if (enabled) warning = true else {
            container.localModels.running.value?.let { id ->
                container.localModels.stop(id)
            }
            scope.launch { container.settings.setLocalModelExperimental(false) }
        }
    }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().toggleable(settings.localModelExperimental, role = Role.Switch, onValueChange = onToggle)
            .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Science, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.local_experimental_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.local_experimental_subtitle), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = settings.localModelExperimental, onCheckedChange = null)
        }
    }
    if (warning) AlertDialog(onDismissRequest = { warning = false },
        icon = { Icon(Icons.Default.Science, null) },
        title = { Text(stringResource(R.string.local_experimental_warning_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.local_experimental_warning_acceleration))
            Text(stringResource(R.string.local_experimental_warning_limits), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, confirmButton = { TextButton(onClick = { scope.launch {
            container.settings.setLocalModelExperimental(true); warning = false
        } }) { Text(stringResource(R.string.local_experimental_enable)) } },
        dismissButton = { TextButton(onClick = { warning = false }) { Text(stringResource(R.string.settings_action_cancel)) } })
}

