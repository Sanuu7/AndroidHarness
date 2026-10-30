package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun LocalGpuInformation(manager: LocalModelManager) {
    val uri = LocalUriHandler.current
    var device by remember { mutableStateOf("") }
    val support by manager.gpuSupport.state.collectAsState()
    LaunchedEffect(support.phase) { device = manager.gpuDevice() }
    Text(stringResource(R.string.local_gpu_scope), style = MaterialTheme.typography.bodySmall)
    Text(if (device.isNotBlank()) stringResource(R.string.local_gpu_detected, device) else stringResource(R.string.local_gpu_unavailable),
        style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { uri.openUri(LocalAccelerationPolicy.ISSUES_URL) }) { Text(stringResource(R.string.local_gpu_request)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalBackendPicker(manager: LocalModelManager, selected: LocalBackend, enabled: Boolean, onSelect: (LocalBackend) -> Unit) {
    var available by remember { mutableStateOf(false) }
    val support by manager.gpuSupport.state.collectAsState()
    LaunchedEffect(support.phase) { available = manager.gpuDevice().isNotBlank() }
    Text(stringResource(R.string.local_gpu_processor), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LocalBackend.entries.forEach { backend ->
            FilterChip(selected == backend, { onSelect(backend) }, enabled = enabled && (backend != LocalBackend.GPU || available),
                label = { Text(if (backend == LocalBackend.AUTO) stringResource(R.string.local_gpu_auto) else backend.name) })
        }
    }
    Text(stringResource(R.string.local_gpu_auto_note), style = MaterialTheme.typography.bodySmall)
}
