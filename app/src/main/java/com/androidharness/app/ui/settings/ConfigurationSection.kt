package com.androidharness.app.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.config.*
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.*
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigurationSection(container: AppContainer, workspaceOverride: WorkspaceFs? = null) {
    val workspace by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)
    val project by container.workspace.currentProject.collectAsStateWithLifecycle(initialValue = null)
    val fs = workspaceOverride ?: workspace
    val scope = rememberCoroutineScope()
    var global by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<HarnessConfig?>(null) }
    var document by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var approved by remember { mutableStateOf(false) }
    var approvalFingerprint by remember { mutableStateOf("") }
    var editor by rememberSaveable { mutableStateOf(false) }
    var editWorkspacePath by rememberSaveable { mutableStateOf("") }
    var editorWorkspace by remember { mutableStateOf<WorkspaceFs?>(null) }
    var editorDocument by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var folders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var variables by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var secrets by remember { mutableStateOf<List<String>>(emptyList()) }
    var addFolder by remember { mutableStateOf(false) }
    var addValue by remember { mutableStateOf(false) }
    var folderName by rememberSaveable { mutableStateOf("") }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try { withContext(Dispatchers.IO) { container.configuration.addFolder(folderName, uri) }; refresh++ }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
        }
    }
    val configRevision by container.configuration.revision.collectAsStateWithLifecycle()
    LaunchedEffect(fs?.displayPath, global, refresh, configRevision) {
        loading = true; error = null; snapshot = null; document = null; approved = false
        if (fs != null) {
            try {
                document = withContext(Dispatchers.IO) { container.configuration.document(fs, global) }
                folders = withContext(Dispatchers.IO) { container.configuration.folders() }
                variables = withContext(Dispatchers.IO) { container.configuration.variables() }
                secrets = withContext(Dispatchers.IO) { container.configuration.secretNames() }
                val resolved = withContext(Dispatchers.IO) { container.configuration.load(fs).also { validateAutoSkills(container, fs, it) } }
                snapshot = resolved
                approved = withContext(Dispatchers.IO) { container.mcp.isWorkspaceApproved(fs, resolved) }
                approvalFingerprint = withContext(Dispatchers.IO) { container.mcp.approvalFingerprint(fs, resolved) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Configuration could not be read." }
        }
        loading = false
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryTabRow(selectedTabIndex = if (global) 1 else 0, containerColor = MaterialTheme.colorScheme.surface) {
            Tab(selected = !global, onClick = { global = false }, text = { Text(stringResource(R.string.config_project)) })
            Tab(selected = global, onClick = { global = true }, text = { Text(stringResource(R.string.config_global)) })
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.DataObject, null, Modifier.size(32.dp))
                        Text(stringResource(if (global) R.string.config_global_title else R.string.config_project_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(if (global) R.string.config_global_hint else R.string.config_project_hint), style = MaterialTheme.typography.bodyMedium)
                        if (!global) Text(if (workspaceOverride == null) project?.name ?: stringResource(R.string.config_no_workspace)
                            else fs?.displayPath?.substringAfterLast('/') ?: stringResource(R.string.config_no_workspace),
                            style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                SettingsPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(document?.first ?: "harness.jsonc", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                        Text(stringResource(if (document?.second == null) R.string.config_not_created else R.string.config_file_ready),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                editorWorkspace = fs; editorDocument = document; editWorkspacePath = fs?.displayPath.orEmpty(); editor = true
                            }, enabled = document != null && !loading, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                                Text(stringResource(if (document?.second == null) R.string.config_create else R.string.config_edit))
                            }
                            IconButton(onClick = { refresh++ }, enabled = !loading) {
                                Icon(Icons.Outlined.Refresh, stringResource(R.string.config_reload))
                            }
                        }
                    }
                }
            }
            if (!global && fs != null) item {
                ConfigDetails(stringResource(R.string.settings_page_workspace_title), listOf(fs.displayPath))
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.config_needs_attention), style = MaterialTheme.typography.titleMedium)
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.config_error_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            } }
            snapshot?.takeIf { it.present }?.let { config ->
                item {
                    ConfigDetails(stringResource(R.string.config_effective),
                        listOf(stringResource(R.string.config_effective_hint)), initiallyExpanded = true) {
                        ConfigCount(R.string.config_instructions, config.instructions.size)
                        ConfigCount(R.string.config_references, config.references.size)
                        ConfigCount(R.string.config_skills, config.skills.size)
                        ConfigCount(R.string.config_agents, config.agents.size)
                        ConfigCount(R.string.config_servers, config.servers.count { it.enabled })
                        ConfigCount(R.string.config_plugins, config.plugins.size)
                    }
                }
                if (config.sources.isNotEmpty()) item { ConfigDetails(stringResource(R.string.config_sources), config.sources.map { it.id }) }
                if (config.instructions.isNotEmpty()) item { ConfigDetails(stringResource(R.string.config_instructions), config.instructions.map { "${it.path}\n${it.source}" }) }
                if (config.references.isNotEmpty()) item { ConfigDetails(stringResource(R.string.config_references), config.references.map { "${it.title}\n${it.path}" }) }
                if (config.skills.isNotEmpty()) item { ConfigDetails(stringResource(R.string.config_skills), config.skills.keys.sorted()) }
                if (config.permissions.isNotEmpty()) item { ConfigDetails(stringResource(R.string.config_permissions), config.permissions.map { "${it.key}: ${it.value}" }) }
                if (config.agents.isNotEmpty()) item { ConfigDetails(stringResource(R.string.config_agents), config.agents.values.map { "${it.name}${if (it.name == config.defaultAgent) " · ${container.appContext.getString(R.string.config_default)}" else ""}\n${it.description}" }) }
                if (config.warnings.isNotEmpty()) item { ConfigDetails(stringResource(R.string.config_warnings), config.warnings, initiallyExpanded = true) }
                if (config.servers.any { it.enabled } || config.plugins.isNotEmpty()) item {
                    SettingsPanel(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.config_project_tools), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.config_trust_hint), style = MaterialTheme.typography.bodyMedium)
                            config.servers.filter { it.enabled }.forEach { server ->
                                Text(server.name, style = MaterialTheme.typography.titleSmall)
                                Text(if (server.isRemote) config.redact(server.url.orEmpty())
                                    else config.redact((listOf(server.command) + server.args).joinToString(" ")),
                                    style = MaterialTheme.typography.bodySmall)
                                if (server.env.isNotEmpty() || server.headers.isNotEmpty()) Text(
                                    (server.env.keys + server.headers.keys).joinToString(", "), style = MaterialTheme.typography.labelSmall)
                            }
                            config.plugins.forEach { plugin ->
                                Text(plugin.name, style = MaterialTheme.typography.titleSmall)
                                Text(plugin.hooks.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                                ConfigDetails(stringResource(R.string.config_plugin_code, plugin.name), listOf(plugin.script))
                            }
                            OutlinedButton(onClick = { scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val current = container.configuration.load(requireNotNull(fs))
                                        check(current.fingerprint == config.fingerprint && container.mcp.approvalFingerprint(fs, current) == approvalFingerprint) { "Configuration changed. Reload and review it before approving." }
                                        container.mcp.approveWorkspace(fs, config)
                                    }
                                    refresh++
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = e.message }
                            } }, enabled = !approved && !loading, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(if (approved) R.string.config_approved else R.string.config_approve))
                            }
                        }
                    }
                }
            }
            if (global) {
                item {
                    ConfigDetails(stringResource(R.string.config_folders), listOf(stringResource(R.string.config_folders_hint)), initiallyExpanded = true) {
                        folders.keys.forEach { name -> ConfigRemoveRow("@$name", { scope.launch {
                            try { withContext(Dispatchers.IO) { container.configuration.removeFolder(name) }; refresh++ }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message }
                        } }) }
                        TextButton(onClick = { folderName = ""; addFolder = true }) { Text(stringResource(R.string.config_add_folder)) }
                    }
                }
                item {
                    ConfigDetails(stringResource(R.string.config_values), listOf(stringResource(R.string.config_values_hint)), initiallyExpanded = true) {
                        variables.keys.forEach { name -> ConfigRemoveRow("{env:$name}", { scope.launch {
                            try { withContext(Dispatchers.IO) { container.configuration.removeVariable(name, false) }; refresh++ }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message }
                        } }) }
                        secrets.forEach { name -> ConfigRemoveRow("{secret:$name}", { scope.launch {
                            try { withContext(Dispatchers.IO) { container.configuration.removeVariable(name, true) }; refresh++ }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message }
                        } }) }
                        TextButton(onClick = { addValue = true }) { Text(stringResource(R.string.config_add_value)) }
                    }
                }
            }
            item { Text(stringResource(R.string.config_access_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    val editingFs = editorWorkspace ?: fs?.takeIf { it.displayPath == editWorkspacePath }
    val editingDocument = editorDocument ?: document?.takeIf { fs?.displayPath == editWorkspacePath }
    if (editor && editingFs != null && editingDocument != null) key(editWorkspacePath, global) {
        ConfigEditor(container, editingFs, global, editingDocument,
            onClose = { editor = false; editorWorkspace = null; editorDocument = null },
            onSaved = { editor = false; editorWorkspace = null; editorDocument = null; refresh++ })
    }
    if (addFolder) AlertDialog(onDismissRequest = { addFolder = false }, title = { Text(stringResource(R.string.config_add_folder)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.config_folder_name_hint))
            OutlinedTextField(folderName, { folderName = it }, singleLine = true, label = { Text(stringResource(R.string.config_name)) })
        } }, confirmButton = { TextButton(onClick = { addFolder = false; folderPicker.launch(null) },
            enabled = Regex("[a-zA-Z0-9_-]{1,32}").matches(folderName) && folderName !in folders) { Text(stringResource(R.string.config_choose_folder)) } },
        dismissButton = { TextButton(onClick = { addFolder = false }) { Text(stringResource(android.R.string.cancel)) } })
    if (addValue) ConfigValueDialog(onDismiss = { addValue = false }, onSave = { name, value, secret ->
        withContext(Dispatchers.IO) { container.configuration.putVariable(name, value, secret) }; refresh++
    })
}

@Composable
private fun ConfigCount(label: Int, count: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(count.toString(), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ConfigDetails(title: String, entries: List<String>, initiallyExpanded: Boolean = false, extra: @Composable ColumnScope.() -> Unit = {}) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    stringResource(if (expanded) R.string.config_collapse else R.string.config_expand))
            }
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            entries.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            extra()
        }
    }
}

@Composable
private fun ConfigRemoveRow(name: String, remove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = remove) { Icon(Icons.Outlined.Close, stringResource(R.string.config_remove_named, name)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigEditor(container: AppContainer, fs: WorkspaceFs, global: Boolean, document: Pair<String, String?>, onClose: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val initial = document.second ?: HarnessConfigRepository.TEMPLATE
    var text by rememberSaveable(fs.displayPath, global, document.first) { mutableStateOf(initial) }
    val originalFingerprint by rememberSaveable(fs.displayPath, global, document.first) {
        mutableStateOf(HarnessConfig.digest(if (document.second == null) "absent" else "present:${document.second}"))
    }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    fun close() { if (!busy) { if (text != initial) discard = true else onClose() } }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        com.androidharness.app.ui.common.SecureDialogEffect()
        BackHandler { close() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(stringResource(R.string.config_edit), style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = { IconButton(onClick = ::close, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.config_back)) } },
                    actions = { TextButton(onClick = { scope.launch {
                        busy = true; message = null
                        try {
                            withContext(Dispatchers.IO) {
                                container.configuration.save(fs, global, document.first, text, document.second, originalFingerprint)
                            }
                            onSaved()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failed = true; message = e.message }
                        finally { busy = false }
                    } }, enabled = !busy) { Text(stringResource(R.string.config_save)) } })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(document.first, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedTextField(value = text, onValueChange = { text = it; message = null }, modifier = Modifier.fillMaxWidth().weight(1f),
                        enabled = !busy, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), label = { Text(stringResource(R.string.config_document)) })
                    message?.let { Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 130.dp)) }
                    OutlinedButton(onClick = { scope.launch {
                        busy = true; message = null
                        try {
                            withContext(Dispatchers.IO) {
                                validateAutoSkills(container, fs, container.configuration.load(fs, if (global) null else document.first to text, if (global) document.first to text else null))
                            }
                            failed = false; message = container.appContext.getString(R.string.config_valid)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failed = true; message = e.message }
                        finally { busy = false }
                    } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.config_validate)) }
                }
            }
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.config_discard_title)) },
            text = { Text(stringResource(R.string.config_discard_hint)) },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.config_discard)) } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.config_keep_editing)) } })
    }
}

@Composable
private fun ConfigValueDialog(onDismiss: () -> Unit, onSave: suspend (String, String, Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.config_add_value)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.config_name)) }, singleLine = true, enabled = !busy)
            OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.config_value)) }, enabled = !busy, maxLines = 4,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(secret, { secret = it }, enabled = !busy)
                Text(stringResource(R.string.config_store_secret), style = MaterialTheme.typography.bodyMedium)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        com.androidharness.app.ui.common.SecureDialogEffect()
    }, confirmButton = { TextButton(onClick = { scope.launch {
        busy = true
        try { onSave(name, value, secret); onDismiss() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        finally { busy = false }
    } }, enabled = !busy && value.isNotBlank() && Regex("[A-Za-z_][A-Za-z0-9_]{0,63}").matches(name)) { Text(stringResource(R.string.config_save)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(android.R.string.cancel)) } })
}

private fun validateAutoSkills(container: AppContainer, fs: WorkspaceFs, config: HarnessConfig) {
    if (config.autoLoad.isEmpty()) return
    val store = container.skills.forWorkspace(fs).configured(config).freeze()
    val loaded = mutableSetOf<String>()
    var chars = 0
    config.autoLoad.forEach { pattern ->
        val matches = store.enabled().filter { ConfigGlob.matches(pattern, it.name) }
        require(matches.isNotEmpty()) { "Auto-loaded skill '$pattern' is missing or disabled." }
        matches.filter { loaded.add(it.name) }.forEach { chars += store.view(it.name).getOrThrow().content.length }
    }
    require(chars <= 32_000) { "Auto-loaded skill instructions exceed 32,000 characters." }
}
