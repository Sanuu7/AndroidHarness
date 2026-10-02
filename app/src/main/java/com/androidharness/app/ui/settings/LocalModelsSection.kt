package com.androidharness.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.androidharness.app.AppContainer
import com.androidharness.app.data.AppSettings
import com.androidharness.app.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val work by remember { WorkManager.getInstance(container.appContext).getWorkInfosByTagFlow("local-model-download") }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var device by remember { mutableStateOf(manager.device()) }
    var contexts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var format by rememberSaveable { mutableStateOf(LocalModelFormat.GGUF) }
    var customDialog by remember { mutableStateOf(false) }
    var deviceDialog by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<LocalModelSpec?>(null) }
    var projectorModel by remember { mutableStateOf<LocalModelSpec?>(null) }
    var download by remember { mutableStateOf<LocalModelSpec?>(null) }
    var removing by remember { mutableStateOf<LocalModelSpec?>(null) }
    var editing by remember { mutableStateOf<LocalModelSpec?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var importProgress by remember { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var onlyAbliterated by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable(format, search, onlyAbliterated) { mutableStateOf(false) }
    fun importFiles(uri: android.net.Uri, kind: LocalModelFormat) {
        importJob = scope.launch {
            importProgress = "Checking model files"
            try {
                val model = manager.importFromFiles(uri, kind) { importProgress = it }
                message = "${model.title} imported. Tap Use in chat to select it."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Could not import model." }
            finally { importProgress = null; importJob = null }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importFiles(it, LocalModelFormat.GGUF) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { importFiles(it, LocalModelFormat.SAFETENSORS) }
    }
    DisposableEffect(Unit) { onDispose { importJob?.cancel() } }
    LaunchedEffect(catalog, installed) {
        while (true) {
            contexts = withContext(Dispatchers.IO) { catalog.associate { it.id to manager.limits(it.id).context } }
            device = manager.device()
            delay(3000)
        }
    }
    fun activeWork(model: LocalModelSpec) = work.firstOrNull { !it.state.isFinished && "model:${model.id}" in it.tags }
    fun removeNow(model: LocalModelSpec) {
        scope.launch {
            busy = model.id
            try { manager.remove(model.id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message }
            finally { busy = null }
        }
    }
    val savedModels = catalog.filter { it.id in installed }.sortedByDescending {
        settings.activeProviderId == LocalModelCatalog.PROVIDER_PREFIX + it.id
    }
    val transfers = catalog.filter { it.id !in installed && activeWork(it) != null }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("On this phone", style = MaterialTheme.typography.titleSmall)
                Text("${size(device.availableRam)} RAM free · ${size(device.freeStorage)} storage free",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { deviceDialog = true }) { Icon(Icons.Default.Info, "Device and local model information") }
        }
    }
    manager.catalogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().toggleable(value = settings.localModelAgentContext, role = Role.Switch,
                onValueChange = { enabled -> scope.launch { container.settings.setLocalModelAgentContext(enabled) } }),
                verticalAlignment = Alignment.CenterVertically) {
                Text("System instructions and tools", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(12.dp))
                Switch(checked = settings.localModelAgentContext, onCheckedChange = null)
            }
            Text("Off by default. Local models receive only your chat messages. Turn on to let them use Harness instructions and tools to work on your project.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Enabling this adds a lot of input, uses more memory and processing power, and can take several minutes before a reply starts. Changes apply to the next reply.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    message?.let { text ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = { message = null }) { Icon(Icons.Default.Close, "Dismiss message") }
        }
    }
    Text("Your models", style = MaterialTheme.typography.titleMedium)
    if (savedModels.isEmpty()) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("No models added yet", style = MaterialTheme.typography.titleSmall)
                Text("Download a GGUF below or import your own model to get started.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    savedModels.forEach { model ->
        val selected = settings.activeProviderId == LocalModelCatalog.PROVIDER_PREFIX + model.id
        LocalModelCard(model, contexts[model.id] ?: model.defaultContext, device, selected = selected, installed = true,
            status = statuses[model.id]?.takeUnless { it == "Ready" || it == "Installed" },
            working = running == model.id, enabled = busy == null,
            onPrimary = {
                scope.launch {
                    busy = model.id
                    try {
                        manager.confirmResources(model)
                        container.settings.setActiveModel(null)
                        container.settings.setActiveProvider(LocalModelCatalog.PROVIDER_PREFIX + model.id)
                        message = "${model.title} is selected for chat."
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message }
                    finally { busy = null }
                }
            }, onSettings = { editing = model }, onDetails = { details = model },
            onImageSupport = if (model.custom) ({ projectorModel = model }) else null,
            onRemove = { removing = model }, onStop = { manager.stop(model.id) })
    }
    if (transfers.isNotEmpty()) {
        Text("Downloads", style = MaterialTheme.typography.titleMedium)
        transfers.forEach { model ->
            LocalModelCard(model, contexts[model.id] ?: model.defaultContext, device,
                transfer = activeWork(model), status = statuses[model.id], enabled = busy == null,
                onPrimary = { removeNow(model) }, onDetails = { details = model })
        }
    }
    importProgress?.let { text ->
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { importProgress = "Cancelling import…"; importJob?.cancel() }) { Text("Cancel import") }
            }
        }
    }
    Text("Add a model", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    TabRow(selectedTabIndex = format.ordinal) {
        LocalModelFormat.entries.forEach { option ->
            Tab(selected = format == option, onClick = { format = option }, text = {
                Text(if (option == LocalModelFormat.GGUF) "GGUF" else "Safetensors")
            })
        }
    }
    Text(if (format == LocalModelFormat.GGUF) "Choose a download, paste a link, or open a GGUF from your files."
        else "Import from a link or choose a folder with weights, config.json and tokenizer.json. Conversion happens on your phone.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { customDialog = true }, enabled = importJob == null, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Link, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("From link")
        }
        OutlinedButton(onClick = { if (format == LocalModelFormat.GGUF) filePicker.launch(arrayOf("*/*")) else folderPicker.launch(null) },
            enabled = importJob == null, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(if (format == LocalModelFormat.GGUF) "Import file" else "Import folder")
        }
    }
    Text("Recommendations", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(search, { search = it }, placeholder = { Text("Search models") }, singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = {
            if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, "Clear model search") }
        }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !onlyAbliterated, onClick = { onlyAbliterated = false }, label = { Text("All") })
        FilterChip(selected = onlyAbliterated, onClick = { onlyAbliterated = true }, label = { Text("Abliterated") })
    }
    val recommendations = catalog.filter { model ->
        model.format == format && !model.localFile && model.id !in installed && activeWork(model) == null &&
            (!onlyAbliterated || model.abliterated) && search.trim().split(Regex("\\s+")).all { term ->
                "${model.title} ${model.description} ${model.repository}".contains(term, ignoreCase = true)
            }
    }
    if (recommendations.isEmpty()) Text("No matching models. Try another search or import your own.", style = MaterialTheme.typography.bodySmall)
    (if (showAll) recommendations else recommendations.take(6)).forEach { model ->
        val latest = work.filter { "model:${model.id}" in it.tags }.maxByOrNull { it.generation }
        val failure = latest?.takeIf { it.state == WorkInfo.State.FAILED }?.outputData?.getString("error")
        LocalModelCard(model, contexts[model.id] ?: model.defaultContext, device, status = failure,
            enabled = busy == null, onPrimary = { download = model }, onDetails = { details = model })
    }
    if (!showAll && recommendations.size > 6) TextButton(onClick = { showAll = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Show all ${recommendations.size} models")
    }
    if (onlyAbliterated) Text("Community variants with reduced refusals. Quality and tool calling vary by model.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (format == LocalModelFormat.SAFETENSORS) {
        Text("Supports dense Qwen 2/2.5, Qwen 3 and Llama 3 models with BPE tokenizers. Imports need extra storage. For other models, use GGUF.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (deviceDialog) LocalDeviceDialog(device) { deviceDialog = false }
    details?.let { model -> LocalModelDetailsDialog(model, contexts[model.id] ?: model.defaultContext) { details = null } }
    projectorModel?.let { model -> ProjectorDialog(model, manager) { projectorModel = null } }
    download?.let { model ->
        val downloadLimits = LocalModelLimits(context = model.defaultContext, input = (model.defaultContext * 3 / 4).coerceAtLeast(1),
            output = (model.defaultContext / 4).coerceAtLeast(1), threads = device.cores.coerceIn(1, 4))
        val warnings = localModelWarnings(model, device, downloadLimits, downloading = true)
        AlertDialog(onDismissRequest = { if (busy == null) download = null }, title = { Text("Download model") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(model.title, style = MaterialTheme.typography.titleSmall)
                Text("${size(model.downloadBytes)} · ${model.url.substringAfter("https://").substringBefore('/')}\nLicense: ${model.license}")
                if (model.format == LocalModelFormat.SAFETENSORS) Text("The model will be converted on your phone before you can use it.")
                warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton(enabled = busy == null, onClick = {
                busy = model.id
                scope.launch {
                    try {
                        manager.acknowledgeResources(model, downloadLimits, downloading = true)
                        if (model.custom) manager.downloadCustom(model) else manager.download(model.id)
                        download = null
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message; download = null }
                    finally { busy = null }
                }
            }) { Text(if (warnings.isEmpty()) "Download" else "Continue") } },
            dismissButton = { TextButton(enabled = busy == null, onClick = { download = null }) { Text("Cancel") } })
    }
    if (customDialog) CustomModelDialog(manager, format, onDismiss = { customDialog = false },
        onDownload = { customDialog = false; download = it })
    removing?.let { model ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove model?") },
            text = { Text("Remove ${model.title} and its saved settings from this phone? Your chats will stay. You can download the model again later.") },
            confirmButton = { TextButton(onClick = { removing = null; removeNow(model) }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } })
    }
    editing?.let { model -> LocalLimitsDialog(model, manager, onDismiss = { editing = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalModelCard(model: LocalModelSpec, context: Int, device: LocalDeviceProfile,
    selected: Boolean = false, installed: Boolean = false, working: Boolean = false,
    transfer: WorkInfo? = null, status: String? = null, enabled: Boolean,
    onPrimary: () -> Unit, onDetails: () -> Unit, onSettings: (() -> Unit)? = null,
    onImageSupport: (() -> Unit)? = null, onRemove: (() -> Unit)? = null, onStop: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(model.title, style = MaterialTheme.typography.titleSmall)
                    Text("${size(if (installed) model.bytes else model.downloadBytes)} · ${if (model.format == LocalModelFormat.GGUF) "GGUF" else "Safetensors import"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.MoreVert, "Options for ${model.title}")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Model details") }, onClick = { menu = false; onDetails() })
                        onImageSupport?.let { action -> DropdownMenuItem(text = { Text("Image support") },
                            enabled = enabled, onClick = { menu = false; action() }) }
                        onRemove?.let { action -> DropdownMenuItem(text = { Text("Remove model", color = MaterialTheme.colorScheme.error) },
                            enabled = enabled, onClick = { menu = false; action() }) }
                    }
                }
            }
            if (!installed && transfer == null && !model.localFile) {
                Text(model.description, style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (selected) LocalModelBadge("Selected", positive = true)
                if (model.abliterated) LocalModelBadge("Abliterated")
                if (installed && model.projector != null) LocalModelBadge("Vision file added", positive = true)
                Text("Estimated RAM ${size(model.estimatedMemory(context))}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                if (!device.canLoad(model, context)) LocalModelBadge("May exceed available RAM")
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (transfer != null) {
                val received = transfer.progress.getLong("received", 0)
                val total = transfer.progress.getLong("total", 0)
                val converting = status?.contains("convert", ignoreCase = true) == true || status?.contains("quantiz", ignoreCase = true) == true
                if (total > 0 && !converting) {
                    LinearProgressIndicator(progress = { (received.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${size(received)} of ${size(total)} downloaded", style = MaterialTheme.typography.bodySmall)
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!selected || !installed) Button(onClick = onPrimary, enabled = enabled) {
                    Text(if (transfer != null) "Cancel download" else if (installed) "Use in chat" else "Download")
                }
                onSettings?.let { action -> OutlinedButton(onClick = action, enabled = enabled) {
                    Icon(Icons.Default.Tune, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Settings")
                } }
                if (working) onStop?.let { action -> OutlinedButton(onClick = action) { Text("Stop") } }
            }
        }
    }
}

@Composable
private fun LocalModelBadge(text: String, positive: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.small,
        color = if (positive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
            color = if (positive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
private fun LocalDeviceDialog(device: LocalDeviceProfile, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("About local models") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Models run on your phone without an API key. Agent tools follow your existing permissions; some tools may use the internet.")
            Text("${size(device.totalRam)} total RAM\n${size(device.availableRam)} available RAM\n${size(device.freeStorage)} free storage\n${device.cores} CPU cores · ${device.abi}")
            if (!device.supported) Text("This device has no compatible local engine.", color = MaterialTheme.colorScheme.error)
            Text("Memory estimates are a guide. You can continue past warnings. Larger models and longer conversations may be slower or cause Android to close the app.")
            Text("Image input needs a vision model and its matching vision file. Tool calling depends on the model.")
            Text("Interrupted downloads restart. Models are unloaded after each reply.")
            TextButton(onClick = { uri.openUri("https://github.com/ggml-org/llama.cpp") }) { Text("llama.cpp · MIT license") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
private fun LocalModelDetailsDialog(model: LocalModelSpec, context: Int, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Model details") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(model.title, style = MaterialTheme.typography.titleSmall)
            Text("${size(if (model.localFile) model.bytes else model.downloadBytes)} ${if (model.localFile) "on this phone" else "download"}\n${size(model.estimatedMemory(context))} estimated RAM\n$context conversation tokens\nLicense: ${model.license}")
            Text(model.filename, style = MaterialTheme.typography.bodySmall)
            if (model.description.isNotBlank()) Text(model.description, style = MaterialTheme.typography.bodySmall)
            Text(if (model.localFile) "Copied from your files. Your original files were kept."
                else if (model.sha256.isNotBlank()) "The download is verified against the source checksum."
                else "File size and model headers are checked. Source checksums are verified when available.", style = MaterialTheme.typography.bodySmall)
            if (!model.localFile) TextButton(onClick = { uri.openUri(model.modelPage) }) { Text("Open model page") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
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
    var advanced by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("Model settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(model.title, style = MaterialTheme.typography.titleSmall)
            Text("Tokens are pieces of text. Larger values use more memory. Changes apply to the next reply.", style = MaterialTheme.typography.bodySmall)
            LocalNumberField("Conversation size", context, "tokens", "Space for instructions, chat history and the reply.", !saving) { context = it }
            LocalNumberField("Reply length", output, "tokens", "Maximum length of one reply.", !saving) { output = it }
            Text("Estimated RAM ${size(model.estimatedMemory(context.toIntOrNull()?.coerceAtLeast(1) ?: limits.context))}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advanced = !advanced }, enabled = !saving) {
                Text(if (advanced) "Hide advanced settings" else "Advanced settings")
                Spacer(Modifier.width(6.dp)); Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (advanced) {
                LocalNumberField("Input budget", input, "tokens", "Includes instructions and chat history. You can continue past this estimate.", !saving) { input = it }
                LocalNumberField("Processing threads", threads, "threads", "More threads can increase heat and may not improve speed.", !saving) { threads = it }
            }
            TextButton(enabled = !saving, onClick = {
                context = model.defaultContext.toString()
                input = (model.defaultContext * 3 / 4).coerceAtLeast(1).toString()
                output = (model.defaultContext / 4).coerceAtLeast(1).toString()
                threads = manager.device().cores.coerceIn(1, 4).toString()
                error = null
            }) { Text("Reset to defaults") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !saving, onClick = {
        scope.launch {
            saving = true
            try {
                manager.saveLimits(model.id, LocalModelLimits(context.toIntOrNull() ?: 0, input.toIntOrNull() ?: 0,
                    output.toIntOrNull() ?: 0, threads.toIntOrNull() ?: 0))
                onDismiss()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { saving = false }
        }
    }) { Text("Save") } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun LocalNumberField(label: String, value: String, unit: String, help: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, suffix = { Text(unit) }, supportingText = { Text(help) },
        singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}

private fun size(bytes: Long): String = if (bytes < 1_000_000_000L) String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
    else String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)

@Composable
internal fun CustomModelDialog(manager: LocalModelManager, format: LocalModelFormat = LocalModelFormat.GGUF, onDismiss: () -> Unit, onDownload: (LocalModelSpec) -> Unit) {
    CustomModelLinkDialog(resolve = { manager.resolveLink(it, format) }, inspect = manager::inspectCustom, format = format,
        device = manager::device, onDismiss = onDismiss, onDownload = onDownload)
}

@Composable
internal fun CustomModelLinkDialog(resolve: suspend (String) -> List<LocalModelSpec>,
    inspect: suspend (LocalModelSpec) -> LocalModelSpec, device: () -> LocalDeviceProfile,
    onDismiss: () -> Unit, onDownload: (LocalModelSpec) -> Unit, format: LocalModelFormat = LocalModelFormat.GGUF) {
    var link by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<LocalModelSpec>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (format == LocalModelFormat.GGUF) "Add GGUF" else "Import safetensors") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (format == LocalModelFormat.GGUF) "Paste a Hugging Face page or a direct GGUF download link."
                else "Paste a Hugging Face model page. The files will be downloaded and converted on your phone.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(link, { link = it; candidates = emptyList(); error = null },
                label = { Text("Model link") }, enabled = !loading, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking model files…", style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (candidates.isNotEmpty()) Text("Choose a file", style = MaterialTheme.typography.titleSmall)
            candidates.forEach { model ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(model.filename.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(size(model.downloadBytes), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(enabled = !loading, onClick = {
                                loading = true; error = null
                                scope.launch {
                                    try { onDownload(inspect(model)) }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message ?: "Could not check model." }
                                    finally { loading = false }
                                }
                            }) { Text("Choose file") }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(enabled = link.isNotBlank() && !loading, onClick = {
        loading = true; error = null; candidates = emptyList()
        scope.launch {
            try {
                candidates = resolve(link)
                if (candidates.isEmpty()) error = "No supported model files were found at this link."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not find model files." }
            finally { loading = false }
        }
    }) { Text("Find model files") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
