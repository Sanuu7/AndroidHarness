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
    val catalog by manager.models.collectAsStateWithLifecycle()
    val installed by manager.installed.collectAsStateWithLifecycle()
    val statuses by manager.status.collectAsStateWithLifecycle()
    val running by manager.running.collectAsStateWithLifecycle()
    val work by remember { WorkManager.getInstance(container.appContext).getWorkInfosByTagFlow("local-model-download") }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var device by remember { mutableStateOf(manager.device()) }
    var customDialog by remember { mutableStateOf(false) }
    var contexts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var showAll by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf<LocalModelSpec?>(null) }
    var removing by remember { mutableStateOf<LocalModelSpec?>(null) }
    var editing by remember { mutableStateOf<LocalModelSpec?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(catalog, installed) { while (true) {
        contexts = withContext(Dispatchers.IO) { catalog.associate { it.id to (runCatching { manager.limits(it.id).context }.getOrNull() ?: it.defaultContext) } }
        device = manager.device()
        delay(3000)
    } }
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
    OutlinedButton(onClick = { customDialog = true }) { Text("Add custom model from link") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Show models outside available RAM", modifier = Modifier.weight(1f))
        Switch(checked = showAll, onCheckedChange = { showAll = it })
    }
    manager.catalogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    val retained = installed + work.filter { !it.state.isFinished }.flatMap { it.tags }
        .filter { it.startsWith("model:") }.map { it.removePrefix("model:") }
    val models = visibleLocalModels(catalog, device, showAll, retained.toSet(), contexts)
    Text("Uses available RAM and your saved context. Installed models and active downloads stay visible for management.", style = MaterialTheme.typography.bodySmall)
    if (!showAll && models.size < catalog.size) Text("${catalog.size - models.size} models hidden by RAM requirements.", style = MaterialTheme.typography.bodySmall)
    if (models.isEmpty()) Text("No models fit the RAM available right now. Close other apps or enable the switch to see requirements.")
    models.forEach { model ->
        val hasModel = model.id in installed
        val downloading = work.any { info -> !info.state.isFinished && info.tags.contains("model:${model.id}") }
        val failed = work.firstOrNull { it.tags.contains("model:${model.id}") && it.state == WorkInfo.State.FAILED }
            ?.outputData?.getString("error")
        val context = contexts[model.id] ?: model.defaultContext
        val fit = device.canLoad(model, context)
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.title, style = MaterialTheme.typography.titleMedium)
                Text("${if (model.custom) "Custom GGUF" else "Q4_K_M"} · ${size(model.bytes)}", style = MaterialTheme.typography.labelMedium)
                Text(if (fit) "Fits available RAM · about ${size(model.estimatedMemory(context) + 256L * 1024 * 1024)} at $context context"
                    else if (!device.fits(model, context)) "Outside device memory or architecture limits"
                    else "Not enough available RAM. Close other apps or lower context.",
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
                        }, enabled = fit && busy == null) { Text("Use") }
                        OutlinedButton(onClick = { editing = model }, enabled = busy == null) { Text("Limits") }
                    } else if (downloading) {
                        OutlinedButton(onClick = { scope.launch {
                            busy = model.id
                            runCatching { manager.remove(model.id) }.onFailure { message = it.message }
                            busy = null
                        } }, enabled = busy == null) { Text("Cancel") }
                    } else {
                        Button(onClick = { download = model }, enabled = fit && busy == null && device.freeStorage >= model.bytes + 256L * 1024 * 1024) { Text("Download") }
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
            text = { Text("Download ${size(model.bytes)} from ${model.url.substringAfter("https://").substringBefore('/')} using your current connection. License: ${model.license}. " +
                if (model.sha256.isNotEmpty()) "The source SHA-256 checksum is verified before installation."
                else "File size and GGUF header are checked. This source does not provide a checksum.") },
            confirmButton = { TextButton(enabled = busy == null && device.canLoad(model, contexts[model.id] ?: model.defaultContext) && device.freeStorage >= model.bytes + 256L * 1024 * 1024, onClick = {
                busy = model.id
                scope.launch {
                    runCatching { if (model.custom) manager.downloadCustom(model) else manager.download(model.id) }
                        .onSuccess { download = null }.onFailure { message = it.message; download = null }
                    busy = null
                }
            }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { download = null }) { Text("Cancel") } })
    }
    if (customDialog) CustomModelDialog(manager, onDismiss = { customDialog = false }, onDownload = { customDialog = false; download = it })
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
    editing?.let { model -> LocalLimitsDialog(model, manager, onDismiss = { editing = null; scope.launch { contexts = withContext(Dispatchers.IO) { catalog.associate { it.id to (runCatching { manager.limits(it.id).context }.getOrNull() ?: it.defaultContext) } } } }) }
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

@Composable
internal fun CustomModelDialog(manager: LocalModelManager, onDismiss: () -> Unit, onDownload: (LocalModelSpec) -> Unit) {
    CustomModelLinkDialog(resolve = manager::resolveLink, inspect = manager::inspectCustom,
        device = manager::device, onDismiss = onDismiss, onDownload = onDownload)
}

@Composable
internal fun CustomModelLinkDialog(resolve: suspend (String) -> List<LocalModelSpec>,
    inspect: suspend (LocalModelSpec) -> LocalModelSpec, device: () -> LocalDeviceProfile,
    onDismiss: () -> Unit, onDownload: (LocalModelSpec) -> Unit) {
    var link by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<LocalModelSpec>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add custom model") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Paste a public Hugging Face model page, GGUF file page, or direct HTTPS .gguf link. Choose an Instruct/chat model. Safetensors, split models and vision projectors are unsupported.")
            OutlinedTextField(link, { link = it; candidates = emptyList(); error = null },
                label = { Text("Model link") }, enabled = !loading, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking model files…") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            candidates.forEach { model ->
                Text(model.filename.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall)
                Text(size(model.bytes))
                OutlinedButton(enabled = !loading, onClick = {
                    loading = true; error = null
                    scope.launch {
                        try {
                            val checked = inspect(model)
                            val profile = device()
                            when {
                                !profile.canLoad(checked) -> error = "This model needs about ${size(checked.estimatedMemory(checked.defaultContext) + 256L * 1024 * 1024)} of available RAM and a compatible 64-bit device. Choose a smaller file or close other apps."
                                profile.freeStorage < checked.bytes + 256L * 1024 * 1024 -> error = "Not enough storage. Free at least ${size(checked.bytes + 256L * 1024 * 1024)}."
                                else -> onDownload(checked)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Could not check model." }
                        finally { loading = false }
                    }
                }) { Text("Choose file") }
            }
        }
    }, confirmButton = { TextButton(enabled = link.isNotBlank() && !loading, onClick = {
        loading = true; error = null; candidates = emptyList()
        scope.launch {
            try { candidates = resolve(link) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not find model files." }
            finally { loading = false }
        }
    }) { Text("Find model files") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
