package com.androidharness.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.RadioButton
import com.androidharness.app.ui.common.TextButton

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LocalBenchmarkScreen(
    initialModel: LocalModelSpec,
    manager: LocalModelManager,
    onDismiss: () -> Unit,
    onApplied: (LocalBenchmarkRecommendation) -> Unit,
    models: List<LocalModelSpec> = listOf(initialModel),
) {
    var model by remember { mutableStateOf(initialModel) }
    var modelMenu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmStart by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val gpu by manager.gpuSupport.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember(context) { com.androidharness.app.data.SettingsRepository(context) }
    val preferences by settings.settings.collectAsStateWithLifecycle(initialValue = com.androidharness.app.data.AppSettings())
    val gpuReady = preferences.localModelExperimental && gpu.phase == GpuInstallPhase.READY
    val scope = rememberCoroutineScope()
    val updates = remember(model.id) { MutableStateFlow(LocalBenchmarkProgress()) }
    val progress by updates.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(LocalBenchmarkMode.CPU) }
    var running by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val best = progress.recommendations.maxByOrNull { it.generationTps }
    LaunchedEffect(running) {
        if (running) {
            val start = android.os.SystemClock.elapsedRealtime()
            while (true) { elapsed = (android.os.SystemClock.elapsedRealtime() - start) / 1000; kotlinx.coroutines.delay(1000) }
        }
    }
    LaunchedEffect(gpuReady) { if (!gpuReady) mode = LocalBenchmarkMode.CPU }
    val view = LocalView.current
    DisposableEffect(running, view) {
        val wasKeptAwake = view.keepScreenOn
        if (running) view.keepScreenOn = true
        onDispose { if (running) view.keepScreenOn = wasKeptAwake }
    }
    DisposableEffect(model.id) {
        onDispose { if (running) { manager.stop(model.id); job?.cancel() } }
    }
    fun stop() {
        stopping = true
        manager.stop(model.id)
        job?.cancel()
    }
    fun start() {
        updates.value = LocalBenchmarkProgress()
        error = null
        details = false
        applied = false
        elapsed = 0
        running = true
        stopping = false
        job = scope.launch {
            try {
                manager.benchmark(model.id, mode) { updates.value = it }
            } catch (_: CancellationException) {
                updates.update { it.copy(finished = true, stopped = true) }
            } catch (e: Exception) {
                error = e.message
                updates.update { it.copy(finished = true) }
            } finally { running = false; stopping = false; job = null }
        }
    }
    Dialog(onDismissRequest = { if (running) confirmStop = true else if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MaterialTheme(motionScheme = MotionScheme.expressive()) {
        val dialogView = LocalView.current
        val dialogWindow = (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        val darkIcons = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            dialogWindow?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window, dialogView).apply {
                    isAppearanceLightStatusBars = darkIcons
                    isAppearanceLightNavigationBars = darkIcons
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(title = { Text(stringResource(R.string.local_benchmark_title)) },
                    navigationIcon = { IconButton(enabled = !saving, onClick = { if (running) confirmStop = true else onDismiss() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_action_cancel))
                    } })

                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                AnimatedContent(targetState = if (running) 1 else if (progress.finished) 2 else 0, label = "performance-stage",
                    modifier = Modifier.fillMaxSize()) { stage ->
                    val scroll = rememberScrollState()
                    LaunchedEffect(stage, applied) { scroll.animateScrollTo(0) }
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        when (stage) {
                            0 -> {
                                Text(stringResource(R.string.perf_setup_title), style = MaterialTheme.typography.headlineLarge)
                                Text(stringResource(R.string.perf_setup_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.perf_model), style = MaterialTheme.typography.labelLarge)
                                Box {
                                    OutlinedCard(onClick = { modelMenu = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                                        Row(Modifier.padding(18.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                            Text(model.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                            Icon(Icons.Default.ExpandMore, null)
                                        }
                                    }
                                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                        models.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.title) }, onClick = { model = candidate; modelMenu = false }) }
                                    }
                                }
                                Text(stringResource(R.string.perf_compare), style = MaterialTheme.typography.labelLarge)
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    LocalBenchmarkMode.entries.forEach { option ->
                                        val enabled = option == LocalBenchmarkMode.CPU || gpuReady
                                        val selected = mode == option
                                        Surface(onClick = { mode = option }, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                                            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(if (option == LocalBenchmarkMode.BOTH) stringResource(R.string.local_benchmark_both) else option.name, style = MaterialTheme.typography.titleMedium)
                                                    Text(stringResource(when (option) {
                                                        LocalBenchmarkMode.CPU -> R.string.perf_cpu_hint
                                                        LocalBenchmarkMode.GPU -> R.string.perf_gpu_hint
                                                        LocalBenchmarkMode.BOTH -> R.string.perf_both_hint
                                                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                RadioButton(selected = selected, onClick = null, enabled = enabled)
                                            }
                                        }
                                    }
                                }
                                if (!gpuReady) Text(stringResource(R.string.local_benchmark_gpu_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { details = !details }) { Text(stringResource(R.string.perf_how)) }
                                AnimatedVisibility(details) { Text(stringResource(R.string.local_benchmark_how), style = MaterialTheme.typography.bodyMedium) }
                            }
                            1 -> {
                                Text(model.title, style = MaterialTheme.typography.titleMedium)
                                val fraction by animateFloatAsState(if (progress.total > 0) progress.completed.toFloat() / progress.total else 0f, label = "performance-progress")
                                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    CircularProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.size(152.dp), strokeWidth = 10.dp,
                                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.displaySmall)
                                        Text("${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                                Text(stringResource(when (progress.phase) {
                                    LocalBenchmarkPhase.WARMUP -> R.string.local_benchmark_warmup
                                    LocalBenchmarkPhase.GENERATION -> R.string.local_benchmark_generation
                                    LocalBenchmarkPhase.PROMPT -> R.string.local_benchmark_prompt
                                    LocalBenchmarkPhase.FINAL -> R.string.local_benchmark_final
                                }), style = MaterialTheme.typography.headlineSmall)
                                Text(if (progress.total > 0) "${progress.backend?.name.orEmpty()} · " + stringResource(R.string.local_benchmark_progress, progress.completed, progress.total) else stringResource(R.string.local_benchmark_preparing), style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SpeedTile(stringResource(R.string.perf_reply_speed), progress.liveGenerationTps.takeIf { it > 0 }, Modifier.weight(1f))
                                    SpeedTile(stringResource(R.string.perf_prompt_speed), progress.livePromptTps.takeIf { it > 0 }, Modifier.weight(1f))
                                }
                                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(if (progress.generationThreads > 0) stringResource(R.string.local_benchmark_threads, progress.generationThreads, progress.promptThreads) else stringResource(R.string.local_benchmark_preparing), style = MaterialTheme.typography.bodyMedium)
                                        Text(stringResource(R.string.perf_repeat, progress.repeat.coerceAtLeast(1)), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(R.string.perf_prompt_number, progress.currentPrompt + 1), style = MaterialTheme.typography.labelLarge)
                                        Text(LOCAL_BENCHMARK_PROMPTS[progress.currentPrompt], style = MaterialTheme.typography.bodyMedium)
                                        HorizontalDivider()
                                        Text(progress.liveAnswer.ifBlank { stringResource(R.string.perf_waiting_answer) }, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                Text(stringResource(R.string.perf_keep_open), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            2 -> {
                                Text(stringResource(when {
                                    applied -> R.string.perf_applied
                                    progress.stopped -> R.string.local_benchmark_cancelled
                                    best == null -> R.string.perf_no_result
                                    else -> R.string.perf_complete
                                }), style = MaterialTheme.typography.headlineLarge)
                                Text(model.title, style = MaterialTheme.typography.bodyLarge)
                                if (best != null && !progress.stopped) {
                                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text(stringResource(R.string.local_benchmark_best, best.backend.name), style = MaterialTheme.typography.titleLarge)
                                            Text(java.lang.String.format(java.util.Locale.getDefault(), "%.1f", best.generationTps), style = MaterialTheme.typography.displayMedium)
                                            Text(stringResource(R.string.perf_reply_unit), style = MaterialTheme.typography.labelLarge)
                                            Text(stringResource(R.string.local_benchmark_threads, best.generationThreads, best.promptThreads), style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    Text(stringResource(R.string.perf_apply_hint), style = MaterialTheme.typography.bodyMedium)
                                }
                                if (progress.recommendations.isNotEmpty()) {
                                    Text(stringResource(R.string.local_benchmark_results), style = MaterialTheme.typography.titleMedium)
                                    progress.recommendations.sortedByDescending { it.generationTps }.forEach { result ->
                                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(result.backend.name, style = MaterialTheme.typography.titleMedium)
                                                Text(stringResource(R.string.local_benchmark_result_speed, result.generationTps, result.promptTps), style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                }
                                if (progress.samples.isNotEmpty()) {
                                    TextButton(onClick = { details = !details }) { Text(stringResource(if (details) R.string.perf_hide_details else R.string.perf_details)) }
                                    AnimatedVisibility(details) {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text(stringResource(R.string.local_benchmark_how), style = MaterialTheme.typography.bodySmall)
                                            progress.answers.forEach { answer ->
                                                Text("${answer.backend.name} · " + stringResource(R.string.perf_prompt_number, answer.promptIndex + 1) +
                                                    " · " + stringResource(if (answer.phase == LocalBenchmarkPhase.WARMUP) R.string.local_benchmark_warmup else if (answer.matchesReference == false) R.string.perf_output_differs else R.string.perf_output_recorded), style = MaterialTheme.typography.labelLarge)
                                                Text(stringResource(R.string.local_benchmark_threads, answer.generationThreads, answer.promptThreads), style = MaterialTheme.typography.bodySmall)
                                                Text(answer.text, style = MaterialTheme.typography.bodyMedium)
                                                Text(stringResource(R.string.perf_token_counts, answer.inputTokens, answer.outputTokens), style = MaterialTheme.typography.bodySmall)
                                                HorizontalDivider()
                                            }
                                            progress.samples.forEach { sample ->
                                                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                                    Text("${sample.backend.name} · " + stringResource(R.string.local_benchmark_threads, sample.generationThreads, sample.promptThreads), style = MaterialTheme.typography.labelLarge)
                                                    Text(stringResource(R.string.local_benchmark_result_speed, sample.generationTps, sample.promptTps), style = MaterialTheme.typography.bodySmall)
                                                    HorizontalDivider(Modifier.padding(top = 10.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                                if (progress.answers.isNotEmpty()) {
                                    Text(stringResource(R.string.perf_answers), style = MaterialTheme.typography.titleMedium)
                                    progress.answers.filter { it.phase != LocalBenchmarkPhase.WARMUP }.distinctBy { it.promptIndex }.forEach { answer ->
                                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(LOCAL_BENCHMARK_PROMPTS[answer.promptIndex], style = MaterialTheme.typography.labelLarge)
                                                Text(answer.text, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                    val differences = progress.answers.count { it.matchesReference == false }
                                    if (differences > 0) Text(stringResource(R.string.perf_answer_differences, differences), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        progress.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                }
            

                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            when {
                                running -> FilledTonalButton(onClick = { confirmStop = true }, enabled = !stopping,
                                    modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                                    Text(stringResource(if (stopping) R.string.local_benchmark_stopping else R.string.local_benchmark_stop))
                                }
                                applied -> Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                                    Text(stringResource(R.string.settings_action_done))
                                }
                                progress.finished -> {
                                    if (best != null && !progress.stopped) Button(enabled = !saving, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes(), onClick = {
                                        saving = true
                                        scope.launch {
                                            try {
                                                val current = manager.limits(model.id)
                                                manager.saveLimits(model.id, current.copy(backend = best.backend, threads = best.generationThreads, promptThreads = best.promptThreads))
                                                applied = true
                                                onApplied(best)
                                            } catch (e: CancellationException) { throw e }
                                            catch (e: Exception) { error = e.message }
                                            finally { saving = false }
                                        }
                                    }) { Text(stringResource(R.string.local_benchmark_apply)) }
                                    TextButton(enabled = !saving, onClick = { updates.value = LocalBenchmarkProgress(); error = null; details = false }, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.local_benchmark_test_again))
                                    }
                                }
                                else -> Button(onClick = { confirmStart = true }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                                    Text(stringResource(R.string.local_benchmark_start))
                                }
                            }
                        }
                    }
                
            }
        }
        }
        if (confirmStart) AlertDialog(onDismissRequest = { confirmStart = false },
            title = { Text(stringResource(R.string.perf_start_title)) },
            text = { Text(stringResource(R.string.perf_start_warning)) },
            confirmButton = { TextButton(onClick = { confirmStart = false; start() }) { Text(stringResource(R.string.perf_begin)) } },
            dismissButton = { TextButton(onClick = { confirmStart = false }) { Text(stringResource(R.string.settings_action_cancel)) } })
        if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.local_benchmark_stop)) },
            text = { Text(stringResource(R.string.perf_stop_hint)) },
            confirmButton = { TextButton(onClick = { confirmStop = false; stop() }) { Text(stringResource(R.string.local_benchmark_stop)) } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.settings_action_continue)) } })
    }
}

@Composable
private fun SpeedTile(label: String, speed: Double?, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(if (speed != null) java.lang.String.format(java.util.Locale.getDefault(), "%.1f", speed) else "—", style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.perf_tokens_unit), style = MaterialTheme.typography.bodySmall)
        }
    }
}
