package com.androidharness.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.FilledTonalIconButton
import com.androidharness.app.ui.common.TextButton

internal class PerformanceDashboardState {
    var hardware: HardwareSnapshot? = null
    var sample: TelemetrySnapshot? = null
    var history by mutableStateOf(emptyList<Float>())
    var historyTimestamp = -1L
    var showDetails by mutableStateOf(false)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LocalPerformanceSection(manager: LocalModelManager, canBenchmark: Boolean, onBenchmark: () -> Unit, onOptions: () -> Unit, cache: PerformanceDashboardState = remember { PerformanceDashboardState() }) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reader = remember(context) { DeviceTelemetry(context.applicationContext) }
    val hardware by produceState<HardwareSnapshot?>(cache.hardware, reader) {
        if (value == null) { value = withContext(Dispatchers.IO) { reader.hardware() }; cache.hardware = value }
    }
    val sample by produceState<TelemetrySnapshot?>(cache.sample, reader, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = withContext(Dispatchers.IO) { reader.sample() }; cache.sample = value; delay(2000) }
        }
    }
    val loaded by manager.loaded.collectAsStateWithLifecycle()
    val running by manager.running.collectAsStateWithLifecycle()
    val gpuRuntime by manager.gpuSupport.state.collectAsStateWithLifecycle()
    var history by cache::history
    var showDetails by cache::showDetails
    LaunchedEffect(sample?.sampledAt) { sample?.let {
        if (cache.historyTimestamp != it.sampledAt) {
            if (it.sampledAt - cache.historyTimestamp > 4000) history = emptyList()
            history = (history + it.availableRam.toFloat() / it.totalRam.coerceAtLeast(1)).takeLast(30); cache.historyTimestamp = it.sampledAt }
    } }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(hardware?.device ?: stringResource(R.string.device_overview), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.device_live), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            FilledTonalIconButton(onClick = onOptions) { Icon(Icons.Default.Tune, stringResource(R.string.local_options)) }
        }
        val live = sample
        if (live == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.device_reading))
        } else {
            val used = (1f - live.availableRam.toFloat() / live.totalRam.coerceAtLeast(1)).coerceIn(0f, 1f)
            val usage by animateFloatAsState(used, animationSpec = tween(800), label = "ram-usage")
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.device_memory), style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(progress = { usage }, modifier = Modifier.fillMaxSize(), strokeWidth = 8.dp,
                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${(usage * 100).toInt()}%", style = MaterialTheme.typography.titleLarge)
                                Text(stringResource(R.string.device_in_use), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(bytes(live.availableRam), style = MaterialTheme.typography.headlineMedium)
                            Text(stringResource(R.string.device_available), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.device_total_value, bytes(live.totalRam)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HistoryLine(history)
                    Text(stringResource(R.string.device_ram_note), style = MaterialTheme.typography.bodySmall)
                    StatRow(stringResource(R.string.device_unused_ram), live.freeRam?.let(::bytes) ?: unavailable())
                    StatRow(stringResource(R.string.device_app_ram), bytes(live.appPss))
                }
            }
            PerfCard(stringResource(R.string.device_storage)) {
                Text(stringResource(R.string.device_available_value, bytes(live.availableStorage)), style = MaterialTheme.typography.titleLarge)
                val storageUsed by animateFloatAsState((1f - live.availableStorage.toFloat() / live.totalStorage.coerceAtLeast(1)).coerceIn(0f, 1f), tween(800), label = "storage-usage")
                LinearProgressIndicator(progress = { storageUsed }, modifier = Modifier.fillMaxWidth())
                StatRow(stringResource(R.string.device_total), bytes(live.totalStorage))
                Text(stringResource(R.string.device_storage_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FilledTonalButton(onClick = onBenchmark, enabled = canBenchmark, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.local_benchmark_title))
        }
        Text(stringResource(if (canBenchmark) R.string.device_benchmark_hint else R.string.perf_download_first), style = MaterialTheme.typography.bodySmall)
        PerfCard(stringResource(R.string.device_cpu)) {
            Text(hardware?.chipset ?: stringResource(R.string.device_reading), style = MaterialTheme.typography.titleLarge)
            StatRow(stringResource(R.string.device_cores), (hardware?.coreIds?.size ?: live?.cores?.size)?.toString() ?: unavailable())
            StatRow(stringResource(R.string.device_online_cores), live?.cores?.takeIf { it.all { c -> c.online != null } }?.let { cores ->
                "${cores.count { it.online == true }} / ${cores.size}"
            } ?: unavailable())
            StatRow(stringResource(R.string.device_system_cpu), live?.systemCpu?.let { "${format(it)}%" } ?: unavailable())
            StatRow(stringResource(R.string.device_app_cpu), live?.appCpu?.let { "${format(it)}%" } ?: unavailable())
            Text(stringResource(R.string.device_cpu_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            live?.cores?.chunked(2)?.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { core ->
                        Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(stringResource(R.string.device_core_id, core.id), style = MaterialTheme.typography.labelLarge)
                                Text(if (core.online == false) stringResource(R.string.device_offline) else core.currentKhz?.let(::frequency) ?: unavailable(), style = MaterialTheme.typography.titleMedium)
                                core.maxKhz?.let { Text(stringResource(R.string.device_max_value, frequency(it)), style = MaterialTheme.typography.bodySmall) }
                                val fraction by animateFloatAsState(if (core.online == false) 0f else ((core.currentKhz ?: 0).toFloat() / (core.maxKhz ?: 1)).coerceIn(0f, 1f), tween(800), label = "core-clock-${core.id}")
                                if (core.currentKhz != null && core.maxKhz != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                                core.load?.let { Text(stringResource(R.string.device_core_load, format(it)), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        PerfCard(stringResource(R.string.device_gpu)) {
            Text(hardware?.gpu ?: unavailable(), style = MaterialTheme.typography.titleLarge)
            hardware?.gpuVendor?.let { StatRow(stringResource(R.string.device_vendor), it) }
            hardware?.graphicsVersion?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            StatRow(stringResource(R.string.device_gpu_engine), stringResource(if (gpuRuntime.phase == GpuInstallPhase.READY) R.string.device_installed else R.string.device_not_installed))
            Text(stringResource(R.string.device_gpu_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PerfCard(stringResource(R.string.device_npu)) {
            val drivers = hardware?.neuralDrivers?.filter { it.type != 2 }.orEmpty()
            if (hardware == null) Text(stringResource(R.string.device_reading))
            else if (drivers.isEmpty()) Text(stringResource(R.string.device_no_accelerators), style = MaterialTheme.typography.bodyMedium)
            drivers.forEach { driver ->
                Text(driver.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(when (driver.type) { 4 -> R.string.device_accelerator_driver; 3 -> R.string.device_gpu_driver; else -> R.string.device_other_driver }), style = MaterialTheme.typography.labelMedium)
                if (driver.version.isNotBlank()) Text(driver.version, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.device_npu_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PerfCard(stringResource(R.string.device_thermal)) {
            StatRow(stringResource(R.string.device_thermal_state), live?.thermalStatus?.let { status -> stringResource(when (status) {
                0 -> R.string.device_thermal_none; 1 -> R.string.device_thermal_light; 2 -> R.string.device_thermal_moderate
                3 -> R.string.device_thermal_severe; 4 -> R.string.device_thermal_critical; 5 -> R.string.device_thermal_emergency
                6 -> R.string.device_thermal_shutdown; else -> R.string.device_unavailable
            }) } ?: unavailable())
            StatRow(stringResource(R.string.device_battery_temp), live?.batteryCelsius?.let { "${format(it)} °C" } ?: unavailable())
            StatRow(stringResource(R.string.device_battery), live?.batteryPercent?.let { "$it%" } ?: unavailable())
        }
        PerfCard(stringResource(R.string.device_local_engine)) {
            val modelId = running ?: loaded
            Text(modelId?.let { manager.find(it)?.title } ?: stringResource(R.string.local_not_loaded), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (running != null) R.string.local_loading else if (loaded != null) R.string.local_loaded else R.string.device_no_model), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (showDetails) R.string.device_hide_specs else R.string.device_more_specs))
        }
        AnimatedVisibility(showDetails) {
            PerfCard(stringResource(R.string.device_specs)) {
                StatRow(stringResource(R.string.device_vendor), hardware?.manufacturer ?: unavailable())
                StatRow(stringResource(R.string.device_board), hardware?.board ?: unavailable())
                StatRow(stringResource(R.string.device_abi), hardware?.abi ?: unavailable())
                StatRow(stringResource(R.string.device_android), hardware?.androidVersion ?: unavailable())
                val minutes = (live?.sampledAt ?: 0) / 60_000
                StatRow(stringResource(R.string.device_uptime), stringResource(R.string.device_uptime_value, minutes / 60, minutes % 60))
            }
        }
    }
}

@Composable
private fun PerfCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable
private fun HistoryLine(values: List<Float>) {
    val color = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.device_ram_history), style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.fillMaxWidth().height(42.dp)) {
            if (values.size == 1) drawCircle(color, radius = 3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(0f, size.height * (1 - values[0].coerceIn(0f, 1f))))
            if (values.size > 1) {
                val path = Path()
                values.forEachIndexed { i, value ->
                    val x = size.width * i / (values.size - 1)
                    val y = size.height * (1 - value.coerceIn(0f, 1f))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}
@Composable private fun unavailable() = stringResource(R.string.device_unavailable)
private fun bytes(value: Long) = String.format(Locale.getDefault(), "%.1f GB", value / 1_000_000_000.0)
private fun format(value: Float) = String.format(Locale.getDefault(), "%.1f", value)
private fun frequency(khz: Long) = String.format(Locale.getDefault(), "%.2f GHz", khz / 1_000_000.0)
