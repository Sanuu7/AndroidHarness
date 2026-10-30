package com.androidharness.app.ui.transfer

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.data.transfer.*
import com.androidharness.app.ui.common.ExpressiveDropdownMenu
import com.androidharness.app.ui.common.ExpressiveDropdownMenuItem
import com.androidharness.app.ui.common.SecureScreenEffect
import com.androidharness.app.ui.settings.SettingsPanel
import kotlinx.coroutines.*
import java.security.SecureRandom
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun SyncSettingsSection(c: AppContainer) {
    val manager = c.sync
    val state by manager.state.collectAsStateWithLifecycle()
    val projects by c.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedProject by remember { mutableStateOf<String?>(null) }
    var profiles by remember { mutableStateOf<List<SyncProfile>>(emptyList()) }
    var selectedProfile by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SyncProfile?>(null) }
    var deleting by remember { mutableStateOf<SyncProfile?>(null) }
    var replace by remember(state.plan) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val locked = state.working || state.plan != null
    LaunchedEffect(Unit) {
        try { profiles = withContext(Dispatchers.IO) { manager.profiles.all() }; loaded = true }
        catch (_: Exception) { failure = "Saved sync connections could not be read." }
    }
    val project = projects.firstOrNull { it.id == selectedProject } ?: projects.firstOrNull()
    val profile = profiles.firstOrNull { it.id == selectedProfile } ?: profiles.firstOrNull()
    val fs = remember(project) { project?.let { runCatching { c.workspace.fsFor(it) }.getOrNull() } }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkspaceChoice(projects, project, !locked) { selectedProject = it }
            Text(stringResource(R.string.sync_local_hint), style = MaterialTheme.typography.bodySmall)
            if (fs?.shellRoot == null) Text(stringResource(R.string.transfer_local_required), color = MaterialTheme.colorScheme.error)
            var choosing by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(enabled = !locked && profiles.isNotEmpty(), onClick = { choosing = true }) { Text(profile?.name?.ifBlank { profile.host } ?: stringResource(R.string.sync_choose_connection)) }
                ExpressiveDropdownMenu(choosing, { choosing = false }) {
                    profiles.forEach { p -> ExpressiveDropdownMenuItem(text = { Text(p.name.ifBlank { p.host }) }, onClick = { selectedProfile = p.id; choosing = false }) }
                }
            }
            profile?.let { Text("${it.protocol} · ${it.host}:${it.port}${it.remoteFolder}", style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = loaded && !locked, onClick = { editing = SyncProfile() }) { Text(stringResource(R.string.sync_add_connection)) }
                profile?.let { p ->
                    TextButton(enabled = !locked, onClick = { editing = p }) { Text(stringResource(R.string.settings_action_edit)) }
                    TextButton(enabled = !locked, onClick = { deleting = p }) { Text(stringResource(R.string.settings_action_delete)) }
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.sync_preview_hint), style = MaterialTheme.typography.bodySmall)
            if (!locked) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = fs?.shellRoot != null && profile != null, onClick = { manager.preview(fs!!, profile!!, SyncDirection.UPLOAD) }) { Text(stringResource(R.string.sync_preview_upload)) }
                OutlinedButton(enabled = fs?.shellRoot != null && profile != null, onClick = { manager.preview(fs!!, profile!!, SyncDirection.DOWNLOAD) }) { Text(stringResource(R.string.sync_preview_download)) }
            }
            if (state.working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.phase, style = MaterialTheme.typography.titleSmall)
                Text(state.path, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = manager::cancel) { Text(stringResource(R.string.transfer_cancel)) }
            }
            state.plan?.let { plan ->
                Text(stringResource(if (plan.direction == SyncDirection.UPLOAD) R.string.sync_upload_preview else R.string.sync_download_preview), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.sync_preview_counts, plan.changes.size - plan.replacements, plan.replacements, plan.unchanged))
                plan.changes.take(30).forEach { Text(it.path, style = MaterialTheme.typography.bodySmall) }
                if (plan.changes.size > 30) Text(stringResource(R.string.sync_more_files, plan.changes.size - 30), style = MaterialTheme.typography.bodySmall)
                if (plan.replacements > 0) TransferToggle(stringResource(R.string.sync_replace_files, plan.replacements), replace, { replace = it })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { manager.apply(replace) }) { Text(stringResource(R.string.sync_apply)) }
                    TextButton(onClick = manager::cancel) { Text(stringResource(R.string.sync_discard_preview)) }
                }
            }
            (failure ?: state.message)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    editing?.let { initial -> SyncConnectionDialog(c, initial, onDismiss = { editing = null }) { saved ->
        manager.profiles.save(saved); profiles = manager.profiles.all(); selectedProfile = saved.id; editing = null
    } }
    deleting?.let { p -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.sync_remove_connection)) },
        text = { Text(p.name.ifBlank { p.host }) }, confirmButton = { TextButton(onClick = {
            scope.launch {
                try { withContext(Dispatchers.IO) { manager.profiles.remove(p.id) }; profiles = withContext(Dispatchers.IO) { manager.profiles.all() }; deleting = null }
                catch (_: Exception) { failure = "Could not remove this connection."; deleting = null }
            }
        }) { Text(stringResource(R.string.settings_action_delete)) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.settings_action_cancel)) } }) }
}

@Composable
private fun SyncConnectionDialog(c: AppContainer, initial: SyncProfile, onDismiss: () -> Unit, onSave: (SyncProfile) -> Unit) {
    SecureScreenEffect(c)
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var keyMode by remember { mutableStateOf(initial.privateKey.isNotBlank()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var observed by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val candidate = draft.copy(port = port.toIntOrNull() ?: 0, privateKey = if (keyMode) draft.privateKey else "", passphrase = if (keyMode) draft.passphrase else "")
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.sync_connection)) }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncProtocol.entries.forEach { protocol -> FilterChip(draft.protocol == protocol, enabled = !busy, onClick = {
                        draft = draft.copy(protocol = protocol, fingerprint = ""); port = if (protocol == SyncProtocol.FTP) "21" else "22"; observed = null
                    }, label = { Text(protocol.name) }) }
                }
                Text(stringResource(if (draft.protocol == SyncProtocol.FTP) R.string.transfer_ftp_plaintext else R.string.sync_sftp_hint), style = MaterialTheme.typography.bodySmall)
                TransferField(draft.name, { draft = draft.copy(name = it.take(100)) }, R.string.transfer_connection_name, busy)
                TransferField(draft.host, { draft = draft.copy(host = it.trim(), fingerprint = ""); observed = null }, R.string.transfer_host, busy)
                TransferField(port, { port = it.filter(Char::isDigit).take(5); draft = draft.copy(fingerprint = ""); observed = null }, R.string.transfer_port, busy, number = true)
                TransferField(draft.username, { draft = draft.copy(username = it) }, R.string.transfer_username, busy)
                if (draft.protocol == SyncProtocol.SFTP) TransferToggle(stringResource(R.string.sync_use_private_key), keyMode, { keyMode = it }, !busy)
                if (keyMode && draft.protocol == SyncProtocol.SFTP) {
                    OutlinedTextField(draft.privateKey, { draft = draft.copy(privateKey = it) }, label = { Text(stringResource(R.string.sync_private_key)) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 200.dp), visualTransformation = PasswordVisualTransformation())
                    TransferField(draft.passphrase, { draft = draft.copy(passphrase = it) }, R.string.sync_key_passphrase, busy, secret = true)
                } else TransferField(draft.password, { draft = draft.copy(password = it) }, R.string.transfer_password, busy, secret = true)
                TransferField(draft.remoteFolder, { draft = draft.copy(remoteFolder = it) }, R.string.sync_remote_folder, busy)
                if (draft.protocol == SyncProtocol.SFTP) {
                    if (draft.fingerprint.isNotBlank()) SelectionContainer { Text(draft.fingerprint, style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(enabled = !busy, onClick = {
                        try {
                            candidate.validate(pin = false); busy = true; error = null
                            scope.launch {
                                try { observed = withContext(Dispatchers.IO) { c.sshConnections.probe(candidate.ssh()) } }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { error = "Could not read the SSH host key. Check the address and port." }
                                finally { busy = false }
                            }
                        } catch (e: IllegalArgumentException) { error = e.message }
                    }) { Text(stringResource(R.string.sync_read_fingerprint)) }
                    observed?.let { value ->
                        SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall) }
                        Text(stringResource(R.string.sync_verify_fingerprint_hint), style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !busy, onClick = { draft = draft.copy(fingerprint = value); observed = null }) { Text(stringResource(R.string.sync_trust_fingerprint)) }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = {
            try {
                candidate.validate(); busy = true; error = null
                scope.launch {
                    try { withContext(Dispatchers.IO) { onSave(candidate) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Could not securely save this connection." }
                    finally { busy = false }
                }
            } catch (e: IllegalArgumentException) { error = e.message }
        }) { Text(stringResource(R.string.settings_action_save)) } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } })
}

@Composable
internal fun FtpServerSection(c: AppContainer) {
    val state by c.ftpShare.state.collectAsStateWithLifecycle()
    val projects by c.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    var projectId by remember { mutableStateOf<String?>(null) }
    val project = projects.firstOrNull { it.id == projectId } ?: projects.firstOrNull()
    val fs = remember(project) { project?.let { runCatching { c.workspace.fsFor(it) }.getOrNull() } }
    var port by remember { mutableStateOf("2121") }
    var username by remember { mutableStateOf("androidharness") }
    var password by remember { mutableStateOf(ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }) }
    var reveal by remember { mutableStateOf(false) }
    var lan by remember { mutableStateOf(false) }
    var readOnly by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val running = state.running || state.starting
    SecureScreenEffect(c)
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.transfer_ftp_plaintext), style = MaterialTheme.typography.bodySmall)
            if (running) {
                Text(state.project, style = MaterialTheme.typography.titleMedium)
                state.addresses.forEach { SelectionContainer { Text(it) } }
                Text(stringResource(R.string.ftp_server_login_status, state.username, state.clients))
                OutlinedTextField(c.ftpShare.passwordForActiveShare().orEmpty(), {}, readOnly = true, singleLine = true,
                    label = { Text(stringResource(R.string.transfer_password)) }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation())
                TextButton(onClick = { reveal = !reveal }) { Text(stringResource(if (reveal) R.string.transfer_hide_password else R.string.transfer_show_password)) }
                Text(stringResource(if (state.readOnly) R.string.ftp_server_read_only else R.string.ftp_server_writable))
                Text(stringResource(R.string.ftp_server_background_hint), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { c.ftpShare.stop() }) { Text(stringResource(R.string.ftp_server_stop)) }
            } else {
                WorkspaceChoice(projects, project, true) { projectId = it }
                if (fs?.shellRoot == null) Text(stringResource(R.string.transfer_local_required), color = MaterialTheme.colorScheme.error)
                TransferField(port, { port = it.filter(Char::isDigit).take(5) }, R.string.transfer_port, false, number = true)
                TransferField(username, { username = it.take(100) }, R.string.transfer_username, false)
                OutlinedTextField(password, { password = it.take(256) }, singleLine = true, label = { Text(stringResource(R.string.transfer_password)) },
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { reveal = !reveal }) { Text(stringResource(if (reveal) R.string.transfer_hide_password else R.string.transfer_show_password)) }
                TransferToggle(stringResource(R.string.ftp_server_allow_lan), lan, { lan = it })
                TransferToggle(stringResource(R.string.ftp_server_read_only), readOnly, { readOnly = it })
                Text(stringResource(R.string.ftp_server_scope_hint), style = MaterialTheme.typography.bodySmall)
                Button(enabled = fs?.shellRoot != null && password.length >= 8 && username.isNotBlank() && port.toIntOrNull() in 1024..65535, onClick = {
                    error = null
                    scope.launch {
                        try { withContext(Dispatchers.IO) { c.ftpShare.start(fs!!, project!!.name, port.toInt(), username, password, lan, readOnly) } }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "Could not start FTP sharing. Check folder access, username, password and port." }
                    }
                }) { Text(stringResource(R.string.ftp_server_start)) }
            }
            (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun WorkspaceChoice(projects: List<ProjectEntity>, selected: ProjectEntity?, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(stringResource(R.string.transfer_workspace), style = MaterialTheme.typography.labelMedium)
    Box {
        OutlinedButton(enabled = enabled && projects.isNotEmpty(), onClick = { open = true }) { Text(selected?.name ?: stringResource(R.string.transfer_choose_workspace)) }
        ExpressiveDropdownMenu(open, { open = false }) {
            projects.forEach { project -> ExpressiveDropdownMenuItem(text = { Text(project.name) }, onClick = { open = false; onSelect(project.id) }) }
        }
    }
}
@Composable
private fun TransferField(value: String, onChange: (String) -> Unit, label: Int, busy: Boolean, secret: Boolean = false, number: Boolean = false) {
    OutlinedTextField(value, onChange, label = { Text(stringResource(label)) }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else if (number) KeyboardType.Number else KeyboardType.Text),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}
@Composable
private fun TransferToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
