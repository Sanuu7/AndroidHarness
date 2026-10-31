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
import androidx.compose.ui.text.font.FontFamily
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
internal fun PluginManagerSection(container: AppContainer, managerOverride: PluginManager? = null, workspaceOverride: WorkspaceFs? = null) {
    val manager = managerOverride ?: container.pluginManager
    val current by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)
    val project by container.workspace.currentProject.collectAsStateWithLifecycle(initialValue = null)
    val fs = workspaceOverride ?: current
    val scope = rememberCoroutineScope()
    val revision by manager.revision.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(tab) { listState.scrollToItem(0) }
    var source by rememberSaveable { mutableStateOf("npm") }
    var target by rememberSaveable { mutableStateOf("") }
    var ref by rememberSaveable { mutableStateOf("") }
    var subfolder by rememberSaveable { mutableStateOf("") }
    var scopeFilter by rememberSaveable { mutableStateOf("all") }
    var installed by remember { mutableStateOf<List<InstalledPlugin>>(emptyList()) }
    var listings by remember { mutableStateOf<List<PluginListing>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var registryBroken by remember { mutableStateOf(false) }
    var resetRegistry by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<PluginProgress?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var prepared by remember { mutableStateOf<PreparedPlugin?>(null) }
    var reviewWorkspace by remember { mutableStateOf<WorkspaceFs?>(null) }
    var reviewLabel by remember { mutableStateOf("") }
    var update by remember { mutableStateOf<InstalledPlugin?>(null) }
    var deleting by remember { mutableStateOf<InstalledPlugin?>(null) }
    var details by remember { mutableStateOf<Pair<InstalledPlugin, PluginContents>?>(null) }

    LaunchedEffect(manager, revision) {
        loading = true
        try { installed = withContext(Dispatchers.IO) { manager.store.list() }; registryBroken = false }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message; registryBroken = true }
        finally { loading = false }
    }
    fun prepare(origin: PluginOrigin, replacing: InstalledPlugin? = update) {
        if (busy) return
        val captured = fs
        reviewWorkspace = captured
        reviewLabel = replacing?.scopeLabel ?: project?.name ?: captured?.displayPath.orEmpty()
        operation = scope.launch {
            busy = true; error = null; notice = null
            try { prepared = manager.prepare(origin, replacing) { progress = it } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: container.appContext.getString(R.string.pm_failed) }
            finally { busy = false; progress = null; operation = null }
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
        onDispose { if (captured != null) container.editorScope.launch(Dispatchers.IO) { manager.discard(captured) } }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp, containerColor = MaterialTheme.colorScheme.surface) {
            listOf(R.string.pm_installed, R.string.pm_discover, R.string.pm_add).forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title)) })
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Extension, null, Modifier.size(28.dp))
                        Text(stringResource(R.string.pm_intro), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.pm_intro_hint), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (loading || busy) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    progress?.let { p ->
                        val label = when (p.stage) {
                            "Downloading" -> R.string.pm_downloading
                            "Checking bundle" -> R.string.pm_checking
                            "Reading archive", "Reading folder" -> R.string.pm_reading
                            else -> R.string.pm_resolving
                        }
                        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
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
                                    Text(record.version + " · " + if (record.scope == "global") stringResource(R.string.config_global) else record.scopeLabel,
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
                            if (record.description.isNotBlank()) Text(if (record.origin.kind == "builtin") starterDescription(record.name) else record.description, style = MaterialTheme.typography.bodyMedium)
                            Text(pluginOriginLabel(record.origin), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { scope.launch {
                                    try { details = record to withContext(Dispatchers.IO) { manager.store.contents(record) } }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message }
                                } }, enabled = !busy) { Text(stringResource(R.string.pm_details)) }
                                TextButton(onClick = {
                                    update = record
                                    source = record.origin.kind.takeIf { it in setOf("npm", "github") } ?: "local"
                                    target = if (source == "npm") record.origin.target + "@" + record.origin.ref.ifBlank { "latest" } else record.origin.target
                                    ref = record.origin.ref; subfolder = record.origin.subdirectory
                                    if (record.origin.kind == "builtin") prepare(record.origin, record) else tab = 2
                                }, enabled = !busy) { Text(stringResource(R.string.pm_update)) }
                                IconButton(onClick = { deleting = record }, enabled = !busy) {
                                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.pm_uninstall_named, record.name))
                                }
                            }
                        }
                    }
                    item { Text(stringResource(R.string.pm_next_run), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                1 -> {
                    item { Text(stringResource(R.string.pm_starters), style = MaterialTheme.typography.titleMedium) }
                    items(PluginStarters.listings, key = { "starter-${it.name}" }) { listing ->
                        PluginListingCard(listing, !busy) { update = null; prepare(listing.origin, null) }
                    }
                    item {
                        PluginPanel {
                            Text(stringResource(R.string.pm_community), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.pm_community_hint), style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(query, { query = it.take(160) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pm_search_hint)) }, singleLine = true, enabled = !busy)
                            OutlinedButton(onClick = { operation = scope.launch {
                                busy = true; error = null
                                try { listings = manager.search(query); searched = true }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = e.message }
                                finally { busy = false; operation = null }
                            } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pm_search)) }
                        }
                    }
                    if (searched && listings.isEmpty()) item { Text(stringResource(R.string.pm_no_results), style = MaterialTheme.typography.bodyMedium) }
                    items(listings, key = { "npm-${it.name}" }) { listing -> PluginListingCard(listing, !busy) { update = null; prepare(listing.origin, null) } }
                }
                2 -> {
                    item {
                        PluginPanel {
                            Text(stringResource(if (update == null) R.string.pm_add_title else R.string.pm_update_title), style = MaterialTheme.typography.titleLarge)
                            update?.let { Text(it.name, style = MaterialTheme.typography.labelLarge) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("npm", "github", "local").forEach { kind ->
                                    FilterChip(selected = source == kind, onClick = { source = kind; target = ""; ref = ""; subfolder = "" }, enabled = !busy,
                                        label = { Text(when (kind) { "npm" -> "npm"; "github" -> "GitHub"; else -> stringResource(R.string.pm_local) }) })
                                }
                            }
                            Text(stringResource(when (source) { "npm" -> R.string.pm_npm_hint; "github" -> R.string.pm_github_hint; else -> R.string.pm_local_hint }), style = MaterialTheme.typography.bodyMedium)
                            if (source != "local") {
                                OutlinedTextField(target, { target = it.take(256) }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                                    label = { Text(stringResource(if (source == "npm") R.string.pm_package else R.string.pm_repository)) })
                                if (source == "github") {
                                    OutlinedTextField(ref, { ref = it.take(200) }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.pm_ref)) })
                                    OutlinedTextField(subfolder, { subfolder = it.take(256) }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.pm_subfolder)) })
                                }
                                Button(onClick = { prepare(PluginOrigin(source, target.trim(), if (source == "github") ref.trim() else "", subfolder.trim())) },
                                    enabled = !busy && target.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pm_review)) }
                            } else {
                                OutlinedButton(onClick = { archivePicker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.pm_choose_archive))
                                }
                                OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pm_choose_folder)) }
                            }
                            if (update != null) TextButton(onClick = { update = null; target = ""; ref = ""; subfolder = "" }, enabled = !busy) { Text(stringResource(R.string.pm_cancel_update)) }
                        }
                    }
                }
            }
        }
    }
    prepared?.let { candidate ->
        PluginInstallDialog(candidate, reviewWorkspace, reviewLabel, manager, installed.firstOrNull { it.id == candidate.replacing }, busy,
            onDismiss = { if (!busy) prepared = null }, onInstall = { destination, label -> scope.launch {
                busy = true; error = null
                try {
                    manager.install(candidate, destination, label)
                    prepared = null; update = null; tab = 0
                    notice = container.appContext.getString(R.string.pm_installed_notice, candidate.contents.name)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message; prepared = null }
                finally { busy = false }
            } })
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
private fun PluginPanel(content: @Composable ColumnScope.() -> Unit) = SettingsPanel(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun PluginMessage(message: String, error: Boolean) {
    Surface(shape = RoundedCornerShape(20.dp), color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Text(message, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PluginListingCard(listing: PluginListing, enabled: Boolean, review: () -> Unit) = PluginPanel {
    Text(listing.name, style = MaterialTheme.typography.titleMedium)
    Text(if (listing.builtIn) starterDescription(listing.name) else listing.description, style = MaterialTheme.typography.bodyMedium)
    Text(if (listing.builtIn) stringResource(R.string.pm_built_in) else "npm · ${listing.publisher}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = review, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pm_review)) }
}

@Composable
private fun pluginOriginLabel(origin: PluginOrigin): String = when (origin.kind) {
    "npm" -> "npm · ${origin.target}@${origin.ref.ifBlank { "latest" }}"
    "github" -> "GitHub · ${origin.target} · ${origin.ref}${origin.subdirectory.takeIf(String::isNotBlank)?.let { " / $it" }.orEmpty()}"
    "builtin" -> stringResource(R.string.pm_built_in)
    "archive" -> stringResource(R.string.pm_local_archive)
    else -> stringResource(R.string.pm_local_folder)
}

@Composable
private fun PluginContentsView(contents: PluginContents) {
    listOf(R.string.config_instructions to contents.instructions, R.string.config_skills to contents.skills,
        R.string.config_servers to contents.servers, R.string.pm_hooks to contents.hooks).forEach { (label, values) ->
        if (values.isNotEmpty()) {
            Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
            values.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (contents.needsRuntime) Text(stringResource(R.string.pm_runtime_hint), style = MaterialTheme.typography.bodySmall)
    if (contents.warnings.isNotEmpty()) Text(stringResource(R.string.pm_scripts_hint), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PluginInstallDialog(candidate: PreparedPlugin, workspace: WorkspaceFs?, workspaceLabel: String, manager: PluginManager,
    previous: InstalledPlugin?, busy: Boolean, onDismiss: () -> Unit, onInstall: (String, String) -> Unit) {
    var global by rememberSaveable(candidate.token) { mutableStateOf(previous?.let { it.scope == "global" } ?: (workspace == null)) }
    var manifest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(candidate.token) {
        try { manifest = withContext(Dispatchers.IO) { candidate.root.resolve("plugin.json").readText() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { manifest = null }
    }
    var reviewManifest by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (reviewManifest) reviewManifest = false else if (!busy) onDismiss() },
        title = { Text(stringResource(if (reviewManifest) R.string.pm_manifest else R.string.pm_review_title)) }, text = {
        if (reviewManifest) {
            com.androidharness.app.ui.common.SecureDialogEffect()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(manifest.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        } else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(candidate.contents.name, style = MaterialTheme.typography.titleLarge)
            Text(candidate.contents.version, style = MaterialTheme.typography.labelLarge)
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
        if (reviewManifest) TextButton(onClick = { reviewManifest = false }) { Text(stringResource(R.string.pm_close)) }
        else TextButton(onClick = {
        val destination = previous?.scope ?: if (global) "global" else manager.workspaceScope(requireNotNull(workspace))
        onInstall(destination, previous?.scopeLabel ?: if (global) "Global" else workspaceLabel)
    }, enabled = !busy && (candidate.replacing == null || previous != null) && (global || workspace != null || previous != null)) { Text(stringResource(if (candidate.replacing == null) R.string.pm_install else R.string.pm_apply_update)) } },
        dismissButton = { if (!reviewManifest) TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun starterDescription(name: String): String = stringResource(
    if (name == "review-workflow") R.string.pm_review_description else R.string.pm_test_description)
