package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.androidharness.app.AppContainer
import com.androidharness.app.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun LocalModelsSection(container: AppContainer) {
    val manager = container.localModels
    val installed by manager.installed.collectAsStateWithLifecycle()
    val statuses by manager.status.collectAsStateWithLifecycle()
    val running by manager.running.collectAsStateWithLifecycle()
    val work by remember { WorkManager.getInstance(container.appContext).getWorkInfosByTagFlow("local-model-download") }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var device by remember { mutableStateOf(manager.device()) }
    var showAll by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf<LocalModelSpec?>(null) }
    var removing by remember { mutableStateOf<LocalModelSpec?>(null) }
    var editing by remember { mutableStateOf<LocalModelSpec?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { device = manager.device(); delay(3000) } }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Your device · ${device.rank}", style = MaterialTheme.typography.titleMedium)
            Text("${size(device.totalRam)} RAM · ${size(device.availableRam)} available")
            Text("${size(device.freeStorage)} free storage · ${device.abi} · ${device.cores} CPU cores")
            Text("Android 8+ · 64-bit CPU inference · No API key", style = MaterialTheme.typography.bodySmall)
            Text("Fit is estimated, not a speed benchmark. Other apps, long context and heat affect performance.", style = MaterialTheme.typography.bodySmall)
            if (!device.supported) Text("Local inference requires arm64-v8a or x86_64.", color = MaterialTheme.colorScheme.error)
        }
    }
    Text("Models run on this device after download. No cloud fallback. These models support text chat only, not images or agent tools. Other app features can still use the network.", style = MaterialTheme.typography.bodyMedium)
    Text("Downloads are free of charge; mobile data charges and model license terms may apply. Memory is released after each reply. Interrupted downloads restart from the beginning.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { uri.openUri("https://github.com/ggml-org/llama.cpp") }) { Text("Powered by llama.cpp · MIT license") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Show models outside device fit", modifier = Modifier.weight(1f))
        Switch(checked = showAll, onCheckedChange = { showAll = it })
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    val models = LocalModelCatalog.models.filter { showAll || device.fits(it) || it.id in installed }
    if (models.isEmpty()) Text("No recommended models for this device. Enable the list above to see requirements.")
    models.forEach { model ->
        val hasModel = model.id in installed
        val downloading = work.any { info -> !info.state.isFinished && info.tags.contains("model:${model.id}") }
        val failed = work.firstOrNull { it.tags.contains("model:${model.id}") && it.state == WorkInfo.State.FAILED }
            ?.outputData?.getString("error")
        val fit = device.fits(model)
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.title, style = MaterialTheme.typography.titleMedium)
                Text("Q4_K_M · ${size(model.bytes)} · ${model.minimumRamGiB}+ GB RAM", style = MaterialTheme.typography.labelMedium)
                Text(if (fit) "Recommended fit · about ${size(model.estimatedMemory(2048))} at 2K context" else "Outside recommended device limits",
                    color = if (fit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                Text(model.description, style = MaterialTheme.typography.bodyMedium)
                if (!hasModel && device.freeStorage < model.bytes + 256L * 1024 * 1024) {
                    Text("Free at least ${size(model.bytes + 256L * 1024 * 1024)} of storage to download.", color = MaterialTheme.colorScheme.error)
                }
                Text(model.license, style = MaterialTheme.typography.bodySmall)
                statuses[model.id]?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                if (failed != null && !hasModel && !downloading) Text(failed, color = MaterialTheme.colorScheme.error)
                if (downloading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (running == model.id) Text("Running on CPU", color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasModel) {
                        Button(onClick = {
                            scope.launch {
                                container.settings.setActiveModel(null)
                                container.settings.setActiveProvider(LocalModelCatalog.PROVIDER_PREFIX + model.id)
                                message = "${model.title} selected. Also available in the chat provider picker."
                            }
                        }, enabled = busy == null) { Text("Use") }
                        OutlinedButton(onClick = { editing = model }, enabled = busy == null) { Text("Limits") }
                    } else if (downloading) {
                        OutlinedButton(onClick = { scope.launch {
                            busy = model.id
                            runCatching { manager.remove(model.id) }.onFailure { message = it.message }
                            busy = null
                        } }, enabled = busy == null) { Text("Cancel") }
                    } else {
                        Button(onClick = { download = model }, enabled = fit && busy == null && device.freeStorage > model.bytes + 256L * 1024 * 1024) { Text("Download") }
                    }
                    TextButton(onClick = { uri.openUri(model.modelPage) }) { Text("Source") }
                }
                if (hasModel) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running == model.id) OutlinedButton(onClick = { manager.stop(model.id) }) { Text("Stop") }
                    TextButton(onClick = { removing = model }, enabled = busy == null) {
                        Text(if (busy == model.id) "Removing…" else "Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    download?.let { model ->
        AlertDialog(onDismissRequest = { download = null }, title = { Text("Download ${model.title}?") },
            text = { Text("Download ${size(model.bytes)} directly from Hugging Face using your current connection. License: ${model.license}. The file's SHA-256 checksum is verified before installation.") },
            confirmButton = { TextButton(onClick = { manager.download(model.id); download = null }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { download = null }) { Text("Cancel") } })
    }
    removing?.let { model ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${model.title}?") },
            text = { Text("Stops generation and downloads, deletes model files and saved limits, and removes the chat provider. Frees ${size(model.bytes)}. Existing chats remain. Download again to reuse it.") },
            confirmButton = { TextButton(onClick = {
                removing = null
                busy = model.id
                scope.launch {
                    runCatching { manager.remove(model.id) }
                        .onSuccess { message = "${model.title} removed. Model storage released." }
                        .onFailure { message = it.message }
                    busy = null
                }
            }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } })
    }
    editing?.let { model -> LocalLimitsDialog(model, manager, onDismiss = { editing = null }) }
}

@Composable
private fun LocalLimitsDialog(model: LocalModelSpec, manager: LocalModelManager, onDismiss: () -> Unit) {
    var saved by remember { mutableStateOf<LocalModelLimits?>(null) }
    LaunchedEffect(model.id) { saved = withContext(Dispatchers.IO) { manager.limits(model.id) } }
    val limits = saved ?: return
    var context by remember { mutableStateOf(limits.context.toString()) }
    var input by remember { mutableStateOf(limits.input.toString()) }
    var output by remember { mutableStateOf(limits.output.toString()) }
    var threads by remember { mutableStateOf(limits.threads.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${model.title} limits") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Input includes instructions and chat history. Input + output must fit context. Changes apply to the next reply.")
            listOf(Triple("Context tokens", context, { v: String -> context = v }),
                Triple("Input tokens", input, { v: String -> input = v }),
                Triple("Output tokens", output, { v: String -> output = v }),
                Triple("CPU threads", threads, { v: String -> threads = v })).forEach { (label, value, change) ->
                OutlinedTextField(value, change, label = { Text(label) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
            Text("Estimated RAM: ${size(model.estimatedMemory(context.toIntOrNull()?.coerceIn(512, 8192) ?: 2048))}")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !saving, onClick = {
        scope.launch {
            saving = true
            runCatching {
                val value = LocalModelLimits(context.toIntOrNull() ?: 0, input.toIntOrNull() ?: 0,
                    output.toIntOrNull() ?: 0, threads.toIntOrNull() ?: 0)
                manager.saveLimits(model.id, value)
            }.onSuccess { onDismiss() }.onFailure { error = it.message }
            saving = false
        }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun size(bytes: Long) = String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
