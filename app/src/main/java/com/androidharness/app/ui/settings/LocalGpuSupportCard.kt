package com.androidharness.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.BuildConfig
import com.androidharness.app.R
import com.androidharness.app.local.*
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun LocalGpuSupportCard(manager: LocalModelManager) {
    val state by manager.gpuSupport.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var device by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.phase) { device = if (state.phase == GpuInstallPhase.READY) manager.gpuDevice() else null }
    val progress by animateFloatAsState(state.progress, label = "GPU download")
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.local_gpu_download_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (BuildConfig.PLAY_DISTRIBUTION) R.string.local_gpu_delivery_play else R.string.local_gpu_delivery_github),
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri("https://github.com/Sanuu7/AH-GPU-Runtime") }) {
                Text(stringResource(R.string.local_gpu_runtime_repository))
            }
            val phase = when (state.phase) {
                GpuInstallPhase.CHECKING -> R.string.local_gpu_checking
                GpuInstallPhase.NOT_INSTALLED -> R.string.local_gpu_optional_download
                GpuInstallPhase.WAITING -> R.string.local_gpu_waiting
                GpuInstallPhase.DOWNLOADING -> R.string.local_gpu_downloading
                GpuInstallPhase.INSTALLING -> R.string.local_gpu_installing
                GpuInstallPhase.CONFIRMATION -> R.string.local_gpu_confirmation
                GpuInstallPhase.READY -> R.string.local_gpu_installed
                GpuInstallPhase.CANCELLED -> R.string.local_gpu_cancelled
                GpuInstallPhase.UNSUPPORTED -> R.string.local_gpu_download_unsupported
                GpuInstallPhase.FAILED -> R.string.local_gpu_download_failed
            }
            Text(stringResource(phase), style = MaterialTheme.typography.bodyMedium)
            if (state.phase == GpuInstallPhase.DOWNLOADING && state.total > 0) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.local_gpu_download_progress, Formatter.formatShortFileSize(context, state.downloaded),
                    Formatter.formatShortFileSize(context, state.total), (state.progress * 100).toInt()), style = MaterialTheme.typography.labelMedium)
            } else if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.phase == GpuInstallPhase.READY && device != null) {
                Text(if (device!!.isNotBlank()) stringResource(R.string.local_gpu_detected, device!!) else stringResource(R.string.local_gpu_unavailable),
                    style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.phase) {
                    GpuInstallPhase.CONFIRMATION -> Button(onClick = { context.activity()?.let(manager.gpuSupport::confirm) }) {
                        Text(stringResource(R.string.local_gpu_continue))
                    }
                    GpuInstallPhase.NOT_INSTALLED, GpuInstallPhase.CANCELLED, GpuInstallPhase.FAILED -> Button(onClick = manager.gpuSupport::install) {
                        Text(stringResource(if (state.phase == GpuInstallPhase.FAILED) R.string.local_gpu_retry_download else R.string.local_gpu_download_action))
                    }
                    else -> Unit
                }
                if (state.phase in setOf(GpuInstallPhase.WAITING, GpuInstallPhase.DOWNLOADING, GpuInstallPhase.CONFIRMATION)) {
                    TextButton(onClick = manager.gpuSupport::cancel) { Text(stringResource(R.string.local_gpu_cancel_download)) }
                }
            }
        }
    }
}
private fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }
