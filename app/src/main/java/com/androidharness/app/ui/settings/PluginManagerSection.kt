package com.androidharness.app.ui.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.plugins.*
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.*
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.common.Switch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PluginManagerSection(container: AppContainer, managerOverride: PluginManager? = null, workspaceOverride: WorkspaceFs? = null,
    catalogSearch: (suspend (String) -> List<CatalogSection>)? = null) {
    val manager = managerOverride ?: container.pluginManager
    val current by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)
    val project by container.workspace.currentProject.collectAsStateWithLifecycle(initialValue = null)
    val fs = workspaceOverride ?: current
    val scope = rememberCoroutineScope()
    val revision by manager.revision.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(tab) { listState.scrollToItem(0) }
    var input by rememberSaveable { mutableStateOf("") }
    var scopeFilter by rememberSaveable { mutableStateOf("all") }
    var kindFilter by rememberSaveable { mutableStateOf("all") }
    var installed by remember { mutableStateOf<List<InstalledPlugin>>(emptyList()) }
    var sections by remember { mutableStateOf<List<CatalogSection>?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var marketInput by rememberSaveable { mutableStateOf("") }
    var marketList by remember { mutableStateOf(manager.marketplaces.list()) }
    var loading by remember { mutableStateOf(true) }
    var searching by remember { mutableStateOf(false) }
    var registryBroken by remember { mutableStateOf(false) }
    var resetRegistry by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var preparingOrigin by remember { mutableStateOf<PluginOrigin?>(null) }
    var preparingInput by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<PluginProgress?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var prepared by remember { mutableStateOf<PreparedPlugin?>(null) }
    var choices by remember { mutableStateOf<List<PluginListing>?>(null) }
    var installer by remember { mutableStateOf<PluginRequest.RunInstaller?>(null) }
    var reviewWorkspace by remember { mutableStateOf<WorkspaceFs?>(null) }
    var reviewLabel by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<InstalledPlugin?>(null) }
    var details by remember { mutableStateOf<Pair<InstalledPlugin, PluginContents>?>(null) }
    val failed = stringResource(R.string.pm_failed)

    LaunchedEffect(manager, revision) {
        loading = true
        try { installed = withContext(Dispatchers.IO) { manager.store.list() }; registryBroken = false }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message; registryBroken = true }
        finally { loading = false }
    }
    fun search() {
        if (searching) return
        searching = true
        scope.launch {
            try { sections = catalogSearch?.invoke(query) ?: manager.search(query) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message; sections = emptyList() }
            finally { searching = false }
        }
    }
    LaunchedEffect(tab) { if (tab == 1 && sections == null) search() }
    /** Runs one plugin operation with shared progress, cancellation and the three outcomes a bundle can have. */
    fun launch(replacing: InstalledPlugin? = null, origin: PluginOrigin? = null, fromInput: Boolean = false, block: suspend () -> Unit) {
        if (busy) return
        val captured = fs
        reviewWorkspace = captured
        reviewLabel = replacing?.scopeLabel ?: project?.name ?: captured?.displayPath.orEmpty()
        busy = true; error = null; notice = null
        preparingOrigin = origin; preparingInput = fromInput
        operation = scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: MarketplaceChoices) { choices = e.listings }
            catch (e: InstallerNeeded) { installer = PluginRequest.RunInstaller(e.command, "", e.message.orEmpty()) }
            catch (e: Exception) { error = e.message ?: failed }
            finally { busy = false; progress = null; operation = null; preparingOrigin = null; preparingInput = false }
        }
    }
    fun prepare(origin: PluginOrigin, replacing: InstalledPlugin? = null) = launch(replacing, origin) {
        prepared = manager.prepare(origin, replacing) { progress = it }
    }
    fun submit(text: String) = launch(fromInput = true) {
        progress = PluginProgress("Resolving source")
        when (val request = manager.request(text)) {
            is PluginRequest.Install -> prepared = manager.prepare(request.origin) { progress = it }
            is PluginRequest.RunInstaller -> installer = request
            is PluginRequest.MarketplaceAdded -> {
                marketList = manager.marketplaces.list()
                notice = container.appContext.getString(R.string.pm_marketplace_added, request.source.repo, request.listings.size)
                choices = request.listings
                sections = null
            }
        }
    }
    fun retain(uri: Uri) { runCatching { container.appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { retain(it); prepare(PluginOrigin("archive", it.toString())) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { retain(it); prepare(PluginOrigin("folder", it.toString())) }
    }
    DisposableEffect(prepared) {
        val captured = prepared
        onDispose { if (captured != null) container.editorScope.launch(Dispatchers.IO) { runCatching { manager.discard(captured) } } }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp, containerColor = MaterialTheme.colorScheme.surface) {
            listOf(R.string.pm_installed, R.string.pm_discover, R.string.pm_add).forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title)) })
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (tab != 1) item {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Extension, null, Modifier.size(28.dp))
                        Text(stringResource(R.string.pm_intro), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.pm_intro_hint), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if ((loading && tab == 0) || (busy && prepared == null && !preparingInput && (tab != 1 || preparingOrigin == null))) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val status = progress?.packages
                    if (status != null && status.packages.isNotEmpty())
                        LinearProgressIndicator(progress = { status.completed.size.toFloat() / status.packages.size }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    progress?.let { p ->
                        val label = when (p.stage) {
                            "Downloading" -> R.string.pm_downloading
                            "Checking bundle" -> R.string.pm_checking
                            "Reading archive", "Reading folder" -> R.string.pm_reading
                            "Installing runtime" -> R.string.pm_installing_runtime
                            "Running installer" -> R.string.pm_running_installer
                            else -> R.string.pm_resolving
                        }
                        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                        if (p.detail.isNotBlank()) Text(p.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        status?.currentPackage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (p.bytes > 0) Text("${p.bytes / 1024} KiB${p.total?.let { " / ${it / 1024} KiB" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (operation != null) TextButton(onClick = { operation?.cancel() }) { Text(stringResource(android.R.string.cancel)) }
                }
            }
            error?.let { message -> item { PluginMessage(message, true) } }
            if (registryBroken) item { OutlinedButton(onClick = { resetRegistry = true }, enabled = !busy) { Text(stringResource(R.string.pm_reset)) } }
            notice?.let { message -> item { PluginMessage(message, false) } }
            when (tab) {
                0 -> {
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("all" to R.string.pm_all, "project" to R.string.config_project, "global" to R.string.config_global).forEach { (key, label) ->
                                FilterChip(selected = scopeFilter == key, onClick = { scopeFilter = key }, label = { Text(stringResource(label)) })
                            }
                        }
                    }
                    val projectScope = fs?.let(manager::workspaceScope)
                    val visible = installed.filter { scopeFilter == "all" || if (scopeFilter == "global") it.scope == "global" else it.scope == projectScope }
                    if (!loading && visible.isEmpty()) item {
                        PluginPanel {
                            Text(stringResource(R.string.pm_empty), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.pm_empty_hint), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { tab = 1 }) { Text(stringResource(R.string.pm_discover)) }
                        }
                    }
                    items(visible, key = { it.id }) { record ->
                        PluginPanel {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(record.name, style = MaterialTheme.typography.titleMedium)
                                    Text(listOfNotNull(record.version, record.format.takeIf(String::isNotBlank),
                                        if (record.scope == "global") stringResource(R.string.config_global) else record.scopeLabel).joinToString(" · "),
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val label = stringResource(R.string.pm_enable_named, record.name)
                                Switch(checked = record.enabled, onCheckedChange = { enabled -> scope.launch {
                                    busy = true; error = null; notice = null
                                    try { withContext(Dispatchers.IO) { manager.store.setEnabled(record.id, enabled) } }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message }
                                    finally { busy = false }
                                } }, enabled = !busy, modifier = Modifier.semantics { contentDescription = label })
                            }
                            if (record.description.isNotBlank()) Text(if (record.origin.kind == "builtin") starterDescription(record.name) else record.description,
                                style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            Text(pluginOriginLabel(record.origin), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { scope.launch {
                                    try { details = record to withContext(Dispatchers.IO) { manager.store.contents(record) } }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message }
                                } }, enabled = !busy) { Text(stringResource(R.string.pm_details)) }
                                if (record.origin.kind !in setOf("archive", "folder")) TextButton(onClick = { prepare(record.origin, record) }, enabled = !busy) {
                                    Text(stringResource(R.string.pm_update))
                                }
                                IconButton(onClick = { deleting = record }, enabled = !busy) {
                                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.pm_uninstall_named, record.name))
                                }
                            }
                        }
                    }
                    item { Text(stringResource(R.string.pm_next_run), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                1 -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(query, { query = it.take(160) }, Modifier.fillMaxWidth(), singleLine = true,
                                shape = RoundedCornerShape(28.dp),
                                label = { Text(stringResource(R.string.pm_search_hint)) },
                                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("all" to R.string.pm_all, "skill" to R.string.pm_kind_skills, "plugin" to R.string.pm_kind_plugins, "mcp" to R.string.pm_kind_mcp).forEach { (key, label) ->
                                    FilterChip(selected = kindFilter == key, onClick = { kindFilter = key }, label = { Text(stringResource(label)) })
                                }
                                AssistChip(onClick = { search() }, enabled = !searching, label = { Text(stringResource(R.string.pm_search)) })
                            }
                        }
                    }
                    if (searching || sections == null) item { PluginDiscoverLoading() }
                    else {
                        val catalog = sections.orEmpty()
                        catalog.forEach { section ->
                            val shown = section.listings.filter { kindFilter == "all" || it.kind == kindFilter }
                            if (shown.isEmpty() && section.error == null) return@forEach
                            val open = section.title in expanded
                            item(key = "section-${section.title}") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    if (shown.size > 6) TextButton(onClick = { expanded = if (open) expanded - section.title else expanded + section.title }) {
                                        Text(if (open) stringResource(R.string.pm_show_less) else stringResource(R.string.pm_show_all, shown.size))
                                    }
                                }
                            }
                            section.error?.let { item(key = "error-${section.title}") { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
                            items(if (open) shown else shown.take(6), key = { "${section.title}-${it.name}-${it.origin.target}-${it.origin.subdirectory}" }) { listing ->
                                PluginListingCard(listing, !busy, preparingOrigin == listing.origin, progress,
                                    onCancel = { operation?.cancel() }) { prepare(listing.origin) }
                            }
                        }
                        item {
                            PluginPanel {
                                Text(stringResource(R.string.pm_marketplaces), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.pm_marketplaces_hint), style = MaterialTheme.typography.bodyMedium)
                                marketList.forEach { market ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(market.name.ifBlank { market.repo }, style = MaterialTheme.typography.bodyMedium)
                                            Text(market.repo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton(onClick = { manager.removeMarketplace(market.repo); marketList = manager.marketplaces.list(); sections = null; search() }, enabled = !busy) {
                                            Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.pm_remove_marketplace, market.repo))
                                        }
                                    }
                                }
                                OutlinedTextField(marketInput, { marketInput = it.take(300) }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                                    label = { Text(stringResource(R.string.pm_marketplace_field)) })
                                OutlinedButton(onClick = {
                                    try { manager.addMarketplace(marketInput); marketInput = ""; marketList = manager.marketplaces.list(); sections = null; search() }
                                    catch (e: Exception) { error = e.message }
                                }, enabled = !busy && marketInput.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pm_add_marketplace)) }
                            }
                        }
                    }
                }
                2 -> {
                    item {
                        PluginPanel {
                            Text(stringResource(R.string.pm_add_title), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.pm_paste_hint), style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(input, { input = it.take(4000) }, Modifier.fillMaxWidth(), enabled = !busy, minLines = 2, maxLines = 6,
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                                label = { Text(stringResource(R.string.pm_paste_label)) }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                            PluginInstallButton(enabled = !busy && input.isNotBlank(), preparing = preparingInput, progress = progress,
                                onClick = { submit(input) })
                            if (preparingInput) TextButton(onClick = { operation?.cancel() }) { Text(stringResource(android.R.string.cancel)) }
                            Text(stringResource(R.string.pm_examples), style = MaterialTheme.typography.titleSmall)
                            listOf("npx -y @modelcontextprotocol/server-memory", "npx skills add anthropics/skills --skill pdf",
                                "/plugin marketplace add anthropics/claude-plugins-official", "/plugin install superpowers@superpowers-marketplace",
                                "https://github.com/nextlevelbuilder/ui-ux-pro-max-skill", "npm i @scope/package@1.2.0",
                                "claude mcp add --transport http context7 https://mcp.context7.com/mcp", "uvx mcp-server-fetch").forEach { example ->
                                Text(example, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item {
                        PluginPanel {
                            Text(stringResource(R.string.pm_local), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.pm_local_hint), style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { archivePicker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.pm_choose_archive))
                            }
                            OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pm_choose_folder)) }
                        }
                    }
                }
            }
        }
    }
    prepared?.let { candidate ->
        PluginInstallDialog(candidate, reviewWorkspace, reviewLabel, manager, installed.firstOrNull { it.id == candidate.replacing }, busy, progress,
            onCancel = { operation?.cancel() },
            onDismiss = { if (!busy) prepared = null }, onInstall = { destination, label ->
                if (busy) return@PluginInstallDialog
                busy = true; error = null; progress = PluginProgress("Installing plugin")
                operation = scope.launch {
                    try {
                        val (_, runtimeProblem) = manager.install(candidate, destination, label) { progress = it }
                        prepared = null; tab = 0; input = ""
                        notice = container.appContext.getString(R.string.pm_installed_notice, candidate.contents.name) +
                            runtimeProblem?.let { "\n\n" + container.appContext.getString(R.string.pm_runtime_problem, it) }.orEmpty()
                    } catch (e: CancellationException) { prepared = null; throw e }
                    catch (e: Exception) { error = e.message; prepared = null }
                    finally { busy = false; progress = null; operation = null }
                }
            })
    }
    choices?.let { listings -> AlertDialog(onDismissRequest = { choices = null }, title = { Text(stringResource(R.string.pm_choose_plugin)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pm_choose_plugin_hint, listings.size), style = MaterialTheme.typography.bodyMedium)
            listings.forEach { listing ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(listing.name, style = MaterialTheme.typography.titleSmall)
                    if (listing.description.isNotBlank()) Text(listing.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { choices = null; prepare(listing.origin) }) { Text(stringResource(R.string.settings_action_install)) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { choices = null }) { Text(stringResource(R.string.pm_close)) } }) }
    installer?.let { request ->
        var command by remember(request) { mutableStateOf(request.command) }
        AlertDialog(onDismissRequest = { installer = null }, title = { Text(stringResource(R.string.pm_installer_title)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(request.reason, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(command, { command = it.take(2000) }, Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text(stringResource(R.string.pm_installer_command)) }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                Text(stringResource(R.string.pm_installer_hint), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { installer = null; prepare(PluginOrigin("installer", command.trim(), name = request.packageName.substringAfterLast('/'))) },
            enabled = command.isNotBlank()) { Text(stringResource(R.string.pm_installer_run)) } },
            dismissButton = { TextButton(onClick = { installer = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
    if (resetRegistry) AlertDialog(onDismissRequest = { resetRegistry = false }, title = { Text(stringResource(R.string.pm_reset)) },
        text = { Text(stringResource(R.string.pm_reset_hint)) }, confirmButton = { TextButton(onClick = { scope.launch {
            try { withContext(Dispatchers.IO) { manager.store.resetBrokenRegistry() }; error = null; resetRegistry = false }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message; resetRegistry = false }
        } }) { Text(stringResource(R.string.pm_reset)) } }, dismissButton = { TextButton(onClick = { resetRegistry = false }) { Text(stringResource(android.R.string.cancel)) } })
    deleting?.let { record -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.pm_uninstall_named, record.name)) },
        text = { Text(stringResource(R.string.pm_uninstall_hint)) }, confirmButton = { TextButton(onClick = { scope.launch {
            busy = true; error = null; notice = null
            try { withContext(Dispatchers.IO) { manager.store.uninstall(record.id) }; deleting = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message; deleting = null }
            finally { busy = false }
        } }, enabled = !busy) { Text(stringResource(R.string.pm_uninstall)) } }, dismissButton = { TextButton(onClick = { deleting = null }, enabled = !busy) { Text(stringResource(android.R.string.cancel)) } }) }
    details?.let { (record, contents) -> AlertDialog(onDismissRequest = { details = null }, title = { Text(record.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(record.version, style = MaterialTheme.typography.labelLarge)
            Text(pluginOriginLabel(record.origin), style = MaterialTheme.typography.bodyMedium)
            Text(record.revision, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            PluginContentsView(contents)
        }
    }, confirmButton = { TextButton(onClick = { details = null }) { Text(stringResource(R.string.pm_close)) } }) }
}

@Composable
private fun PluginPanel(content: @Composable ColumnScope.() -> Unit) = Surface(Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
    tonalElevation = 1.dp, shadowElevation = 3.dp) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun PluginMessage(message: String, error: Boolean) {
    Surface(shape = RoundedCornerShape(20.dp), color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Text(message, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun kindLabel(kind: String): String = stringResource(when (kind) {
    "skill" -> R.string.pm_kind_skill; "mcp" -> R.string.pm_kind_mcp; else -> R.string.pm_kind_plugin
})

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginListingCard(listing: PluginListing, enabled: Boolean, preparing: Boolean, progress: PluginProgress?,
    onCancel: () -> Unit, install: () -> Unit) = PluginPanel {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 3.dp) {
            Icon(if (listing.kind == "mcp") Icons.Outlined.Hub else Icons.Outlined.Extension, null, Modifier.padding(12.dp).size(24.dp))
        }
        Text(listing.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (listing.version.isNotBlank()) Text(listing.version, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(if (listing.builtIn) starterDescription(listing.name) else listing.description, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SuggestionChip(onClick = {}, enabled = false, label = { Text(kindLabel(listing.kind)) })
        if (listing.category.isNotBlank()) SuggestionChip(onClick = {}, enabled = false, label = { Text(listing.category) })
    }
    Text(if (listing.builtIn) stringResource(R.string.pm_built_in) else listOf(listing.source, listing.publisher).filter(String::isNotBlank).joinToString(" · "),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val installLabel = stringResource(R.string.pm_install_named, listing.name)
    PluginInstallButton(enabled, preparing, progress, Modifier.semantics { contentDescription = installLabel }, install)
    if (preparing) TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PluginInstallButton(enabled: Boolean, preparing: Boolean, progress: PluginProgress?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Button(onClick = onClick, enabled = enabled && !preparing, modifier = modifier.fillMaxWidth().heightIn(min = 54.dp).animateContentSize(),
        colors = ButtonDefaults.buttonColors(disabledContainerColor = if (preparing) scheme.primaryContainer else scheme.onSurface.copy(alpha = .12f),
            disabledContentColor = if (preparing) scheme.onPrimaryContainer else scheme.onSurface.copy(alpha = .38f))) {
        if (preparing) LoadingIndicator(Modifier.size(26.dp), color = scheme.onPrimaryContainer)
        else Icon(Icons.Outlined.Download, null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(if (preparing) pluginProgressLabel(progress) else stringResource(R.string.settings_action_install),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PluginDiscoverLoading() = Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp),
    color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 3.dp) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LoadingIndicator(Modifier.size(80.dp))
        Text(stringResource(R.string.pm_discover_loading), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(stringResource(R.string.pm_discover_loading_hint), style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun pluginProgressLabel(progress: PluginProgress?): String = stringResource(when (progress?.stage) {
    "Downloading" -> R.string.pm_downloading
    "Checking bundle" -> R.string.pm_checking
    "Reading archive", "Reading folder" -> R.string.pm_reading
    "Installing runtime" -> R.string.pm_installing_runtime
    "Running installer" -> R.string.pm_running_installer
    "Installing plugin" -> R.string.pm_installing
    else -> R.string.pm_resolving
})

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PluginInstallingContent(name: String, progress: PluginProgress?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 6.dp) {
            LoadingIndicator(Modifier.padding(20.dp).size(72.dp))
        }
        Text(name, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(pluginProgressLabel(progress), style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        val packages = progress?.packages
        when {
            packages != null && packages.packages.isNotEmpty() -> LinearProgressIndicator(
                progress = { packages.completed.size.toFloat() / packages.packages.size }, modifier = Modifier.fillMaxWidth())
            progress?.total?.let { it > 0 } == true -> LinearProgressIndicator(
                progress = { (progress.bytes.toFloat() / requireNotNull(progress.total)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else -> LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        val detail = packages?.currentPackage ?: progress?.detail.orEmpty()
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun pluginOriginLabel(origin: PluginOrigin): String = when (origin.kind) {
    "npm" -> "npm · ${origin.target}@${origin.ref.ifBlank { "latest" }}"
    "github" -> "GitHub · ${origin.target}${origin.ref.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}${origin.subdirectory.takeIf(String::isNotBlank)?.let { " / $it" }.orEmpty()}" +
        origin.marketplace.takeIf { it.isNotBlank() && it != origin.target }?.let { " · $it" }.orEmpty()
    "url" -> origin.target
    "mcp" -> stringResource(R.string.pm_mcp_config)
    "installer" -> origin.target
    "builtin" -> stringResource(R.string.pm_built_in)
    "archive" -> stringResource(R.string.pm_local_archive)
    else -> stringResource(R.string.pm_local_folder)
}

@Composable
private fun PluginContentsView(contents: PluginContents) {
    Text(contents.format, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    listOf(R.string.config_instructions to contents.instructions, R.string.config_skills to contents.skills,
        R.string.pm_agents to contents.agents, R.string.config_servers to contents.servers, R.string.pm_hooks to contents.hooks,
        R.string.pm_command_hooks to contents.commandHooks, R.string.pm_runtimes to contents.runtimes).forEach { (label, values) ->
        if (values.isNotEmpty()) {
            Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
            values.take(60).forEach { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            if (values.size > 60) Text("+${values.size - 60}", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (contents.runtimes.isNotEmpty()) Text(stringResource(R.string.pm_runtime_hint), style = MaterialTheme.typography.bodySmall)
    if (contents.warnings.isNotEmpty()) {
        Text(stringResource(R.string.pm_notes), style = MaterialTheme.typography.titleSmall)
        contents.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PluginInstallDialog(candidate: PreparedPlugin, workspace: WorkspaceFs?, workspaceLabel: String, manager: PluginManager,
    previous: InstalledPlugin?, busy: Boolean, progress: PluginProgress?, onCancel: () -> Unit,
    onDismiss: () -> Unit, onInstall: (String, String) -> Unit) {
    var global by rememberSaveable(candidate.token) { mutableStateOf(previous?.let { it.scope == "global" } ?: (workspace == null)) }
    var manifest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(candidate.token) {
        try { manifest = withContext(Dispatchers.IO) { candidate.root.resolve("plugin.json").readText() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { manifest = null }
    }
    var reviewManifest by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) { if (reviewManifest) reviewManifest = false else onDismiss() } },
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(if (busy) R.string.pm_installing else if (reviewManifest) R.string.pm_manifest else R.string.pm_install_title)) }, text = {
        if (busy) PluginInstallingContent(candidate.contents.name, progress)
        else if (reviewManifest) {
            com.androidharness.app.ui.common.SecureDialogEffect()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(manifest.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        } else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(candidate.contents.name, style = MaterialTheme.typography.titleLarge)
            Text(candidate.contents.version, style = MaterialTheme.typography.labelLarge)
            if (candidate.contents.description.isNotBlank()) Text(candidate.contents.description, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
            Text(pluginOriginLabel(candidate.origin), style = MaterialTheme.typography.bodySmall)
            Text(candidate.revision, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            PluginContentsView(candidate.contents)
            Text(stringResource(R.string.pm_scope), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().selectable(selected = !global, onClick = { global = false }, role = Role.RadioButton,
                enabled = workspace != null && candidate.replacing == null && !busy), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !global, onClick = null, enabled = workspace != null && candidate.replacing == null && !busy)
                Text(workspaceLabel.ifBlank { stringResource(R.string.config_project) }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth().selectable(selected = global, onClick = { global = true }, role = Role.RadioButton,
                enabled = candidate.replacing == null && !busy), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = global, onClick = null, enabled = candidate.replacing == null && !busy)
                Text(stringResource(R.string.pm_global_scope), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.pm_review_hint), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { reviewManifest = true }, enabled = manifest != null && !busy) { Text(stringResource(R.string.pm_manifest)) }
        }
    }, confirmButton = {
        if (!busy && reviewManifest) TextButton(onClick = { reviewManifest = false }) { Text(stringResource(R.string.pm_close)) }
        else if (!busy) Button(onClick = {
        val destination = previous?.scope ?: if (global) "global" else manager.workspaceScope(requireNotNull(workspace))
        onInstall(destination, previous?.scopeLabel ?: if (global) "Global" else workspaceLabel)
    }, enabled = !busy && (candidate.replacing == null || previous != null) && (global || workspace != null || previous != null)) { Text(stringResource(if (candidate.replacing == null) R.string.pm_install else R.string.pm_apply_update)) } },
        dismissButton = { if (!reviewManifest || busy) TextButton(onClick = if (busy) onCancel else onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun starterDescription(name: String): String = stringResource(
    if (name == "review-workflow") R.string.pm_review_description else R.string.pm_test_description)
