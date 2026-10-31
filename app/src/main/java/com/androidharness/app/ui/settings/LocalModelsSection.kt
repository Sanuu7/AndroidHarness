package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import com.androidharness.app.ui.common.AppLinearProgressIndicator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.FilledTonalIconButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.RadioButton
import com.androidharness.app.ui.common.TextButton

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LocalModelsSection(container: AppContainer, scrollContent: Boolean = false) {
    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        Column(Modifier.then(if (scrollContent) Modifier.fillMaxSize() else Modifier), verticalArrangement = Arrangement.spacedBy(16.dp)) { LocalModelsContent(container, scrollContent) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.LocalModelsContent(container: AppContainer, scrollContent: Boolean) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val manager = container.localModels
    val catalog by manager.models.collectAsStateWithLifecycle()
    val installed by manager.installed.collectAsStateWithLifecycle()
    val statuses by manager.status.collectAsStateWithLifecycle()
    val running by manager.running.collectAsStateWithLifecycle()
    val loaded by manager.loaded.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val work by remember { WorkManager.getInstance(container.appContext).getWorkInfosByTagFlow("local-model-download") }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var device by remember { mutableStateOf(manager.device()) }
    var contexts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var format by rememberSaveable { mutableStateOf(LocalModelFormat.GGUF) }
    var customDialog by remember { mutableStateOf(false) }
    var panel by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(panel) { if (panel !in 0..2) panel = 0 }
    var options by remember { mutableStateOf(false) }
    var performance by remember { mutableStateOf(false) }
    val dashboard = remember { PerformanceDashboardState() }
    val experimental = settings.localModelExperimental

    var deviceDialog by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<LocalModelSpec?>(null) }
    var projectorModel by remember { mutableStateOf<LocalModelSpec?>(null) }
    var download by remember { mutableStateOf<LocalModelSpec?>(null) }
    var removing by remember { mutableStateOf<LocalModelSpec?>(null) }
    var editing by remember { mutableStateOf<LocalModelSpec?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var unloading by remember { mutableStateOf<String?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var importProgress by remember { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var onlyAbliterated by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable(format, search, onlyAbliterated) { mutableStateOf(false) }
    var hubSource by rememberSaveable { mutableStateOf(false) }
    var hubTarget by remember { mutableStateOf<HubModel?>(null) }
    var resumable by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var storage by remember { mutableStateOf<LocalStorageReport?>(null) }
    var capability by remember { mutableStateOf<LocalDeviceCapability?>(null) }
    fun importFiles(uri: android.net.Uri, kind: LocalModelFormat) {
        importJob = scope.launch {
            importProgress = uiContext.getString(R.string.ui_checking_model_files)
            try {
                val model = manager.importFromFiles(uri, kind) { importProgress = it }
                message = context.getString(R.string.settings_local_models_imported, model.title)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: context.getString(R.string.settings_local_models_import_failed) }
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
    val workStates = work.map { it.id to it.state }
    LaunchedEffect(catalog, installed, workStates) {
        while (true) {
            withContext(Dispatchers.IO) {
                resumable = catalog.filter { it.id !in installed }
                    .associate { it.id to runCatching { manager.resumableBytes(it) }.getOrDefault(0L) }.filterValues { it > 0 }
                storage = runCatching { manager.storageReport() }.getOrNull()
            }
            delay(3000)
        }
    }
    LaunchedEffect(panel == 1) {
        if (panel == 1 && capability == null) capability = withContext(Dispatchers.IO) { readDeviceCapability(context, manager.device()) }
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
    val paused = catalog.filter { it.id !in installed && activeWork(it) == null && (resumable[it.id] ?: 0L) > 0 }

    PrimaryTabRow(selectedTabIndex = panel.coerceIn(0, 2), divider = {}) {
        listOf(R.string.local_library, R.string.local_browse, R.string.device_performance).forEachIndexed { index, title ->
            Tab(selected = panel == index, onClick = { panel = index }, text = { Text(stringResource(title), maxLines = 1) })
        }
    }
    val tabScroll = rememberScrollState()
    LaunchedEffect(panel) { tabScroll.scrollTo(0) }
    Column(Modifier.then(if (scrollContent) Modifier.weight(1f).verticalScroll(tabScroll) else Modifier), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    if (panel != 2) {
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Memory, null, Modifier.padding(14.dp).size(30.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_local_models_on_device), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.settings_local_models_device_memory, size(device.availableRam), size(device.freeStorage)),
                    style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalIconButton(onClick = { options = true }) {
                Icon(Icons.Default.Tune, stringResource(R.string.local_options))
            }
        }
    }
    }
    if (performance && savedModels.isNotEmpty()) LocalBenchmarkScreen(savedModels.first(), manager,
        onDismiss = { performance = false }, onApplied = {}, models = savedModels)
    AnimatedVisibility(panel == 2, enter = fadeIn(expressiveEffectsSpec()) + expandVertically(expressiveSpatialSpec()),
        exit = fadeOut(expressiveEffectsSpec()) + shrinkVertically(expressiveSpatialSpec())) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LocalPerformanceSection(manager, canBenchmark = savedModels.isNotEmpty() && busy == null,
                onBenchmark = { performance = true }, onOptions = { options = true }, cache = dashboard)
        }
    }
    manager.catalogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (options) ModalBottomSheet(onDismissRequest = { options = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.local_options), style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.fillMaxWidth().toggleable(value = settings.localModelAgentContext, role = Role.Switch,
                onValueChange = { enabled -> scope.launch { container.settings.setLocalModelAgentContext(enabled) } }),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_local_models_system_tools), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = settings.localModelAgentContext, onCheckedChange = null)
            }
            Text(stringResource(R.string.settings_local_models_system_tools_desc), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { options = false; deviceDialog = true }) {
                Icon(Icons.Default.Info, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.settings_local_models_about))
            }
            if (experimental) LocalGpuInformation(manager)
        }
    }
    message?.let { text ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = { message = null }) { Icon(Icons.Default.Close, uiContext.getString(R.string.ui_dismiss_message)) }
        }
    }
    AnimatedVisibility(panel == 0, enter = fadeIn(expressiveEffectsSpec()) + expandVertically(expressiveSpatialSpec()),
        exit = fadeOut(expressiveEffectsSpec()) + shrinkVertically(expressiveSpatialSpec())) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    if (savedModels.isEmpty()) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_local_models_empty_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.settings_local_models_empty_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = { panel = 1 }) { Text(stringResource(R.string.local_browse)) }
            }
        }
    }
    savedModels.forEach { model ->
        val selected = settings.activeProviderId == LocalModelCatalog.PROVIDER_PREFIX + model.id
        LocalModelCard(model, contexts[model.id] ?: model.defaultContext, device, selected = selected, installed = true,
            status = statuses[model.id]?.takeUnless { it == "Ready" || it == "Installed" },
            working = running == model.id || loaded == model.id || unloading == model.id,
            loaded = loaded == model.id, unloading = unloading == model.id, enabled = busy == null && unloading == null,
            stopEnabled = unloading == null && (busy == null || running == model.id),
            onPrimary = {
                scope.launch {
                    busy = model.id
                    try {
                        manager.confirmResources(model)
                        if (!selected) {
                            container.settings.setActiveModel(null)
                            container.settings.setActiveProvider(LocalModelCatalog.PROVIDER_PREFIX + model.id)
                        }
                        manager.load(model.id)
                        message = context.getString(R.string.settings_local_models_selected_for_chat, model.title)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (!e.message.orEmpty().contains("stopped", ignoreCase = true)) message = e.message }
                    finally { busy = null }
                }
            }, onSettings = { editing = model }, onDetails = { details = model },
            onImageSupport = if (model.custom) ({ projectorModel = model }) else null,
            onRemove = { removing = model }, onStop = {
                scope.launch {
                    busy = model.id
                    unloading = model.id
                    try { manager.stopAndUnload(model.id) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message ?: context.getString(R.string.local_unload_failed) }
                    finally { busy = null; unloading = null }
                }
            })
    }
    if (transfers.isNotEmpty() || paused.isNotEmpty()) {
        Text(stringResource(R.string.settings_local_models_downloads), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.local_downloads_resume_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        transfers.forEach { model ->
            LocalModelCard(model, contexts[model.id] ?: model.defaultContext, device,
                transfer = activeWork(model), status = statuses[model.id], enabled = busy == null,
                onPrimary = { manager.cancelDownload(model.id) }, onDiscard = { removeNow(model) }, onDetails = { details = model })
        }
        paused.forEach { model ->
            val latest = work.filter { "model:${model.id}" in it.tags }.maxByOrNull { it.generation }
            val failure = latest?.takeIf { it.state == WorkInfo.State.FAILED }?.outputData?.getString("error")
            LocalModelCard(model, contexts[model.id] ?: model.defaultContext, device,
                paused = true, pausedBytes = resumable[model.id] ?: 0L, status = statuses[model.id] ?: failure, enabled = busy == null,
                onPrimary = { manager.download(model.id) }, onDiscard = { removeNow(model) }, onDetails = { details = model })
        }
    }
    storage?.let { report ->
        LocalStorageCard(report, device.freeStorage, canClearPaused = paused.isNotEmpty(), enabled = busy == null,
            onClearPaused = {
                scope.launch {
                    busy = "storage"
                    try { paused.forEach { manager.remove(it.id) } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message }
                    finally { busy = null }
                }
            },
            onClearLeftovers = {
                scope.launch {
                    busy = "storage"
                    try { message = context.getString(R.string.local_storage_cleaned, size(manager.clearLeftovers())) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message }
                    finally { busy = null }
                }
            })
    }
    LocalExperimentalSwitch(container)
    if (experimental) LocalGpuSupportCard(manager)
    }
    }
    importProgress?.let { text ->
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                AppLinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { importProgress = uiContext.getString(R.string.ui_cancelling_import); importJob?.cancel() }) { Text(stringResource(R.string.settings_local_models_cancel_import)) }
            }
        }
    }
    AnimatedVisibility(panel == 1, enter = fadeIn(expressiveEffectsSpec()) + expandVertically(expressiveSpatialSpec()),
        exit = fadeOut(expressiveEffectsSpec()) + shrinkVertically(expressiveSpatialSpec())) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    SecondaryTabRow(selectedTabIndex = format.ordinal, divider = {}) {
        LocalModelFormat.entries.forEach { option ->
            Tab(selected = format == option, onClick = { format = option }, text = {
                Text(when (option) { LocalModelFormat.GGUF -> "GGUF"; LocalModelFormat.SAFETENSORS -> "Safetensors" })
            })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { customDialog = true }, enabled = importJob == null, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Link, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.settings_local_models_from_link))
        }
        OutlinedButton(onClick = { when (format) {
            LocalModelFormat.GGUF -> filePicker.launch(arrayOf("*/*"))
            LocalModelFormat.SAFETENSORS -> folderPicker.launch(null)
        } },
            enabled = importJob == null, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(if (format != LocalModelFormat.SAFETENSORS) uiContext.getString(R.string.ui_import_file) else uiContext.getString(R.string.ui_import_folder))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !hubSource, onClick = { hubSource = false }, label = { Text(stringResource(R.string.local_hub_curated)) })
        FilterChip(selected = hubSource, onClick = { hubSource = true }, label = { Text(stringResource(R.string.local_hub_search_tab)) },
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) })
    }
    if (!hubSource) {
        val suggestions = capability?.let { LocalModelSuggestions.suggest(it, catalog, installed, format) }.orEmpty()
        if (search.isBlank() && !onlyAbliterated) {
            SuggestionsHeader(capability, suggestions.isNotEmpty())
            suggestions.forEach { pick ->
                val model = pick.model
                val latest = work.filter { "model:${model.id}" in it.tags }.maxByOrNull { it.generation }
                val failure = latest?.takeIf { it.state == WorkInfo.State.FAILED }?.outputData?.getString("error")
                LocalModelCard(model, contexts[model.id] ?: model.defaultContext, device, badge = suggestionKindLabel(pick.kind),
                    status = failure ?: suggestionReason(pick.kind), enabled = busy == null && activeWork(model) == null && model.id !in resumable,
                    onPrimary = { download = model }, onDetails = { details = model })
            }
        }
        Text(stringResource(R.string.settings_local_models_recommendations), style = MaterialTheme.typography.titleSmall)
    }
    OutlinedTextField(search, { search = it }, placeholder = { Text(stringResource(
        if (!hubSource) R.string.settings_local_models_search
        else if (format == LocalModelFormat.GGUF) R.string.local_hub_search_hint_gguf else R.string.local_hub_search_hint_safetensors)) }, singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = {
            if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, uiContext.getString(R.string.ui_clear_model_search)) }
        }, modifier = Modifier.fillMaxWidth())
    if (hubSource) HubSearchPanel(manager, format, device, search) { hubTarget = it } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !onlyAbliterated, onClick = { onlyAbliterated = false }, label = { Text(stringResource(R.string.settings_local_models_filter_all)) })
            FilterChip(selected = onlyAbliterated, onClick = { onlyAbliterated = true }, label = { Text(stringResource(R.string.settings_local_models_filter_abliterated)) })
        }
        val recommendations = catalog.filter { model ->
            model.format == format && !model.localFile && model.id !in installed && activeWork(model) == null && model.id !in resumable &&
                (!onlyAbliterated || model.abliterated) && search.trim().split(Regex("\\s+")).all { term ->
                    "${model.title} ${model.description} ${model.repository}".contains(term, ignoreCase = true)
                }
        }
        if (recommendations.isEmpty()) Text(stringResource(R.string.settings_local_models_search_empty), style = MaterialTheme.typography.bodySmall)
        (if (showAll) recommendations else recommendations.take(6)).forEach { model ->
            val latest = work.filter { "model:${model.id}" in it.tags }.maxByOrNull { it.generation }
            val failure = latest?.takeIf { it.state == WorkInfo.State.FAILED }?.outputData?.getString("error")
            LocalModelCard(model, contexts[model.id] ?: model.defaultContext, device, status = failure,
                enabled = busy == null, onPrimary = { download = model }, onDetails = { details = model })
        }
        if (!showAll && recommendations.size > 6) TextButton(onClick = { showAll = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_local_models_show_all, recommendations.size.toString()))
        }
        if (onlyAbliterated) Text(stringResource(R.string.settings_local_models_abliterated_note),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (format == LocalModelFormat.SAFETENSORS && !hubSource) {
        Text(stringResource(R.string.settings_local_models_safetensors_note),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    }
    }
    if (deviceDialog) LocalDeviceDialog(device) { deviceDialog = false }
    details?.let { model -> LocalModelDetailsDialog(model, contexts[model.id] ?: model.defaultContext) { details = null } }
    projectorModel?.let { model -> ProjectorDialog(model, manager) { projectorModel = null } }
    download?.let { model ->
        val downloadLimits = LocalModelLimits(context = model.defaultContext, input = (model.defaultContext * 3 / 4).coerceAtLeast(1),
            output = (model.defaultContext / 4).coerceAtLeast(1), threads = device.cores.coerceIn(1, 4))
        val warnings = localModelWarnings(model, device, downloadLimits, downloading = true)
        AlertDialog(onDismissRequest = { if (busy == null) download = null }, title = { Text(stringResource(R.string.settings_local_models_download)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(model.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.settings_local_models_download_meta,
                        size(model.downloadBytes),
                        model.url.substringAfter("https://").substringBefore('/'),
                        model.license,
                    ),
                )
                if (model.format == LocalModelFormat.SAFETENSORS) Text(stringResource(R.string.settings_local_models_convert_note))
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
            }) { Text(if (warnings.isEmpty()) uiContext.getString(R.string.ui_download) else uiContext.getString(R.string.settings_action_continue)) } },
            dismissButton = { TextButton(enabled = busy == null, onClick = { download = null }) { Text(stringResource(R.string.settings_action_cancel)) } })
    }
    if (customDialog) CustomModelDialog(manager, format, onDismiss = { customDialog = false },
        onDownload = { customDialog = false; download = it })
    hubTarget?.let { hit ->
        CustomModelDialog(manager, hit.format, initialLink = hit.url, onDismiss = { hubTarget = null },
            onDownload = { hubTarget = null; download = it })
    }
    removing?.let { model ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.settings_local_models_remove_title)) },
            text = { Text(stringResource(R.string.settings_local_models_remove_message, model.title)) },
            confirmButton = { TextButton(onClick = { removing = null; removeNow(model) }) { Text(stringResource(R.string.settings_action_remove), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.settings_local_models_keep)) } })
    }
    editing?.let { model -> LocalLimitsDialog(model, manager, onDismiss = { editing = null }) }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LocalModelCard(model: LocalModelSpec, context: Int, device: LocalDeviceProfile,
    selected: Boolean = false, installed: Boolean = false, working: Boolean = false,
    loaded: Boolean = false, unloading: Boolean = false, stopEnabled: Boolean = true,
    transfer: WorkInfo? = null, paused: Boolean = false, pausedBytes: Long = 0, badge: String? = null,
    status: String? = null, enabled: Boolean,
    onPrimary: () -> Unit, onDetails: () -> Unit, onSettings: (() -> Unit)? = null, onDiscard: (() -> Unit)? = null,
    onImageSupport: (() -> Unit)? = null, onRemove: (() -> Unit)? = null, onStop: (() -> Unit)? = null) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(28.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(model.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.settings_local_models_meta_format,
                            size(if (installed) model.bytes else model.downloadBytes),
                            stringResource(
                                when (model.format) {
                                    LocalModelFormat.GGUF -> R.string.settings_local_models_format_gguf
                                    LocalModelFormat.SAFETENSORS -> R.string.settings_local_models_format_safetensors
                                },
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.MoreVert, uiContext.getString(R.string.ui_options_for, (model.title).toString()))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.settings_local_models_details)) }, onClick = { menu = false; onDetails() })
                        onImageSupport?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.settings_local_models_image_support)) },
                            enabled = enabled, onClick = { menu = false; action() }) }
                        onRemove?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.settings_local_models_remove), color = MaterialTheme.colorScheme.error) },
                            enabled = enabled, onClick = { menu = false; action() }) }
                    }
                }
            }
            if (!installed && transfer == null && !model.localFile) {
                Text(model.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                badge?.let { LocalModelBadge(it, positive = true) }
                if (selected) LocalModelBadge(uiContext.getString(R.string.settings_selected), positive = true)
                if (installed && (selected || working)) {
                    LocalModelBadge(stringResource(if (unloading) R.string.local_unloading else if (loaded) R.string.local_loaded else if (working) R.string.local_loading else R.string.local_not_loaded), positive = loaded && !unloading)
                }
                if (model.abliterated) LocalModelBadge(stringResource(R.string.settings_local_models_badge_abliterated))
                if (installed && model.projector != null) LocalModelBadge(stringResource(R.string.settings_local_models_badge_vision), positive = true)
                if (!device.canLoad(model, context)) LocalModelBadge(stringResource(R.string.settings_local_models_badge_may_exceed))
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (transfer != null) {
                val received = transfer.progress.getLong("received", 0)
                val total = transfer.progress.getLong("total", 0)
                val converting = status?.contains("convert", ignoreCase = true) == true || status?.contains("quantiz", ignoreCase = true) == true
                if (total > 0 && !converting) {
                    AppLinearProgressIndicator(progress = { (received.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.settings_local_models_downloaded, size(received), size(total)), style = MaterialTheme.typography.bodySmall)
                } else AppLinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (paused && model.downloadBytes > 0) {
                AppLinearProgressIndicator(progress = { (pausedBytes.toFloat() / model.downloadBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.local_paused_status, size(pausedBytes), size(model.downloadBytes)), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!selected || !installed || !working) Button(onClick = onPrimary, enabled = enabled, shapes = ButtonDefaults.shapes()) {
                    Text(if (transfer != null) uiContext.getString(R.string.local_pause) else if (paused) uiContext.getString(R.string.local_resume) else if (installed && selected) uiContext.getString(R.string.local_load_model) else if (installed) uiContext.getString(R.string.ui_use_in_chat) else uiContext.getString(R.string.ui_download))
                }
                onSettings?.let { action -> OutlinedButton(onClick = action, enabled = enabled) {
                    Icon(Icons.Default.Tune, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.settings_local_models_settings))
                } }
                onDiscard?.let { action -> OutlinedButton(onClick = action, enabled = enabled) { Text(stringResource(R.string.local_discard)) } }
                if (working) onStop?.let { action -> OutlinedButton(onClick = action, enabled = stopEnabled) { Text(stringResource(R.string.local_stop_unload)) } }
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.settings_local_models_about)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_local_models_about_desc))
            Text(
                stringResource(
                    R.string.settings_local_models_device_report,
                    size(device.totalRam),
                    size(device.availableRam),
                    size(device.freeStorage),
                    device.cores.toString(),
                    device.abi,
                ),
            )
            if (!device.supported) Text(stringResource(R.string.settings_local_models_no_engine), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.settings_local_models_memory_note))
            Text(stringResource(R.string.settings_local_models_capability_note))
            Text(stringResource(R.string.settings_local_models_download_note))
            TextButton(onClick = { uri.openUri("https://github.com/ggml-org/llama.cpp") }) { Text(stringResource(R.string.settings_local_models_llama_license)) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_done)) } })
}

@Composable
private fun LocalModelDetailsDialog(model: LocalModelSpec, context: Int, onDismiss: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val uri = LocalUriHandler.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.settings_local_models_details)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(model.title, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.settings_local_models_detail,
                    size(if (model.localFile) model.bytes else model.downloadBytes),
                    stringResource(
                        if (model.localFile) R.string.settings_local_models_on_phone
                        else R.string.settings_local_models_download_label,
                    ),
                    size(model.estimatedMemory(context)),
                    context.toString(),
                    model.license,
                ),
            )
            Text(model.filename, style = MaterialTheme.typography.bodySmall)
            if (model.description.isNotBlank()) Text(model.description, style = MaterialTheme.typography.bodySmall)
            Text(if (model.localFile) stringResource(R.string.settings_local_models_copied_note)
                else if (model.sha256.isNotBlank()) stringResource(R.string.settings_local_models_checksum_note)
                else uiContext.getString(R.string.ui_file_size_and_model_headers_are_checked), style = MaterialTheme.typography.bodySmall)
            if (!model.localFile) TextButton(onClick = { uri.openUri(model.modelPage) }) { Text(stringResource(R.string.settings_projector_open_page)) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_done)) } })
}

@Composable
internal fun LocalLimitsDialog(model: LocalModelSpec, manager: LocalModelManager, onDismiss: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var saved by remember { mutableStateOf<LocalModelLimits?>(null) }
    LaunchedEffect(model.id) { saved = withContext(Dispatchers.IO) { manager.limits(model.id) } }
    val limits = saved ?: return
    var context by remember { mutableStateOf(limits.context.toString()) }
    var input by remember { mutableStateOf(limits.input.toString()) }
    var output by remember { mutableStateOf(limits.output.toString()) }
    var threads by remember { mutableStateOf(limits.threads.toString()) }
    var promptThreads by remember { mutableStateOf((limits.promptThreads ?: limits.threads).toString()) }
    var backend by remember { mutableStateOf(limits.backend) }
    var thinkingMode by remember { mutableStateOf(limits.thinkingMode) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val experimentalSettings = remember(uiContext) { com.androidharness.app.data.SettingsRepository(uiContext) }
    val experimental by experimentalSettings.settings
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(stringResource(R.string.settings_local_models_model_settings)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(model.title, style = MaterialTheme.typography.titleSmall)
            if (experimental.localModelExperimental) LocalBackendPicker(manager, backend, !saving) { backend = it }
            Text(stringResource(R.string.local_thinking_behavior), style = MaterialTheme.typography.titleSmall)
            Column {
                LocalThinkingMode.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().selectable(selected = thinkingMode == option, enabled = !saving,
                        role = Role.RadioButton, onClick = { thinkingMode = option }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = thinkingMode == option, onClick = null, enabled = !saving)
                        Text(stringResource(when (option) { LocalThinkingMode.AUTO -> R.string.local_thinking_auto; LocalThinkingMode.THINKING -> R.string.local_thinking_on; LocalThinkingMode.DIRECT -> R.string.local_thinking_direct }))
                    }
                }
            }
            Text(stringResource(R.string.local_thinking_help), style = MaterialTheme.typography.bodySmall)

            LocalNumberField(
                stringResource(R.string.settings_local_models_conversation_size),
                context,
                stringResource(R.string.settings_local_models_tokens_unit),
                stringResource(R.string.settings_local_models_conversation_hint),
                !saving,
            ) { context = it }
            LocalNumberField(
                stringResource(R.string.settings_local_models_reply_length),
                output,
                stringResource(R.string.settings_local_models_tokens_unit),
                stringResource(R.string.settings_local_models_reply_hint),
                !saving,
            ) { output = it }
            Text(
                stringResource(
                    R.string.settings_local_models_estimated_ram,
                    size(model.estimatedMemory(context.toIntOrNull()?.coerceAtLeast(1) ?: limits.context)),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { advanced = !advanced }, enabled = !saving) {
                Text(
                    if (advanced) stringResource(R.string.settings_local_models_advanced_hide)
                    else stringResource(R.string.settings_local_models_advanced_show),
                )
                Spacer(Modifier.width(6.dp)); Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            AnimatedVisibility(visible = advanced) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LocalNumberField(uiContext.getString(R.string.ui_input_budget), input, "tokens", uiContext.getString(R.string.ui_includes_instructions_and_chat_history_you_can), !saving) { input = it }
                LocalNumberField(uiContext.getString(R.string.local_generation_threads), threads, "threads", uiContext.getString(R.string.ui_more_threads_can_increase_heat_and_may), !saving) { threads = it }
                LocalNumberField(uiContext.getString(R.string.local_prompt_threads), promptThreads, "threads", uiContext.getString(R.string.local_prompt_threads_help), !saving) { promptThreads = it }
            }
            }
            TextButton(enabled = !saving, onClick = {
                context = model.defaultContext.toString()
                input = (model.defaultContext * 3 / 4).coerceAtLeast(1).toString()
                output = (model.defaultContext / 4).coerceAtLeast(1).toString()
                threads = manager.device().cores.coerceIn(1, 4).toString()
                promptThreads = threads
                backend = LocalBackend.AUTO
                thinkingMode = LocalThinkingMode.AUTO
                error = null
            }) { Text(stringResource(R.string.settings_action_reset_defaults)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !saving, onClick = {
        scope.launch {
            saving = true
            try {
                manager.saveLimits(model.id, LocalModelLimits(context.toIntOrNull() ?: 0, input.toIntOrNull() ?: 0,
                    output.toIntOrNull() ?: 0, threads.toIntOrNull() ?: 0, backend, promptThreads.toIntOrNull() ?: 0, thinkingMode))
                onDismiss()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { saving = false }
        }
    }) { Text(stringResource(R.string.settings_action_save)) } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } })

}

@Composable
private fun LocalNumberField(label: String, value: String, unit: String, help: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, suffix = { Text(unit) }, supportingText = { Text(help) },
        singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}

internal fun size(bytes: Long): String = if (bytes < 1_000_000_000L) String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
    else String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)

@Composable
internal fun CustomModelDialog(manager: LocalModelManager, format: LocalModelFormat = LocalModelFormat.GGUF, initialLink: String = "",
    onDismiss: () -> Unit, onDownload: (LocalModelSpec) -> Unit) {
    CustomModelLinkDialog(resolve = { manager.resolveLink(it, format) }, inspect = manager::inspectCustom, format = format,
        device = manager::device, onDismiss = onDismiss, onDownload = onDownload, initialLink = initialLink)
}

@Composable
internal fun CustomModelLinkDialog(resolve: suspend (String) -> List<LocalModelSpec>,
    inspect: suspend (LocalModelSpec) -> LocalModelSpec, device: () -> LocalDeviceProfile,
    onDismiss: () -> Unit, onDownload: (LocalModelSpec) -> Unit, format: LocalModelFormat = LocalModelFormat.GGUF,
    initialLink: String = "") {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var link by remember { mutableStateOf(initialLink) }
    var candidates by remember { mutableStateOf<List<LocalModelSpec>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val profile = remember { device() }
    fun findFiles() {
        loading = true; error = null; candidates = emptyList()
        scope.launch {
            try {
                candidates = resolve(link).sortedByDescending { it.bytes }
                if (candidates.isEmpty()) error = uiContext.getString(R.string.ui_no_supported_model_files_were_found_at)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: uiContext.getString(R.string.ui_could_not_find_model_files) }
            finally { loading = false }
        }
    }
    // A repository chosen from Hugging Face search opens with its files already listed.
    LaunchedEffect(initialLink) { if (initialLink.isNotBlank()) findFiles() }
    val bestFit = candidates.filter { LocalModelSuggestions.runsOn(profile, it) }.maxByOrNull { it.bytes }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (format == LocalModelFormat.GGUF) uiContext.getString(R.string.ui_add_gguf) else uiContext.getString(R.string.ui_import_safetensors)) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (format == LocalModelFormat.GGUF) uiContext.getString(R.string.ui_paste_a_hugging_face_page_or_a)
                else uiContext.getString(R.string.ui_paste_a_hugging_face_model_page_the), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(link, { link = it; candidates = emptyList(); error = null },
                label = { Text(stringResource(R.string.settings_local_models_model_link)) }, enabled = !loading, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            if (loading) { AppLinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.settings_local_models_checking_files), style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (candidates.isNotEmpty()) Text(stringResource(R.string.settings_local_models_choose_file), style = MaterialTheme.typography.titleSmall)
            candidates.forEach { model ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (model.format == LocalModelFormat.SAFETENSORS) model.repository else model.filename.substringAfterLast('/'),
                            style = MaterialTheme.typography.bodyMedium)
                        if (model === bestFit) Text(stringResource(R.string.local_best_fit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        else if (!LocalModelSuggestions.runsOn(profile, model)) Text(stringResource(R.string.settings_local_models_badge_may_exceed),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(size(model.downloadBytes), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(enabled = !loading, onClick = {
                                loading = true; error = null
                                scope.launch {
                                    try { onDownload(inspect(model)) }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message ?: uiContext.getString(R.string.ui_could_not_check_model) }
                                    finally { loading = false }
                                }
                            }) { Text(stringResource(R.string.settings_local_models_choose_file_short)) }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(enabled = link.isNotBlank() && !loading, onClick = ::findFiles
    ) { Text(stringResource(R.string.settings_local_models_find_files)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } })
}
