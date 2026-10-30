package com.androidharness.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.data.BackupEncryption
import com.androidharness.app.data.SettingsBackup
import com.androidharness.app.data.SettingsBackupFile
import com.androidharness.app.data.settingsBackupExportError
import com.androidharness.app.ui.common.BiometricAuth
import com.androidharness.app.ui.common.SecureDialogEffect
import com.androidharness.app.ui.common.SecureScreenEffect
import com.androidharness.app.ui.common.ThinLinearProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun SettingsBackupSection(container: AppContainer) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val backup = remember(container) { SettingsBackup(container) }
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf<Uri?>(null) }
    var source by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    var includeKeys by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<SettingsBackupFile?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        destination = it
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source = it }
    fun clear() {
        password = ""
        confirmation = ""
        showPassword = false
        showConfirmation = false
        source = null
        destination = null
        preview = null
        includeKeys = false
    }
    SecureScreenEffect(container, source != null || destination != null || preview != null || busy)
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_backup_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_backup_desc),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { message = null; exportPicker.launch("androidharness-settings.hbackup") }, enabled = !busy) { Text(stringResource(R.string.settings_action_export)) }
                OutlinedButton(onClick = { message = null; importPicker.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = !busy) { Text(stringResource(R.string.settings_action_import)) }
            }
            if (busy) ThinLinearProgress(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if ((destination != null || source != null) && preview == null) {
        val exporting = destination != null
        AlertDialog(
            onDismissRequest = { if (!busy) clear() },
            title = { Text(if (exporting) stringResource(R.string.settings_backup_encrypt_title) else stringResource(R.string.settings_backup_unlock_title)) },
            text = {
                SecureDialogEffect()
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (exporting) stringResource(R.string.settings_backup_export_note, BackupEncryption.MIN_PASSWORD_LENGTH.toString())
                        else stringResource(R.string.settings_backup_import_note),
                    )
                    OutlinedTextField(password, { if (it.length <= BackupEncryption.MAX_PASSWORD_LENGTH) password = it }, label = { Text(stringResource(R.string.settings_backup_password)) },
                        singleLine = true, enabled = !busy, visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }, enabled = !busy) {
                                Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (showPassword) stringResource(R.string.settings_backup_hide_password)
                                    else stringResource(R.string.settings_backup_show_password))
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    if (exporting) {
                        OutlinedTextField(confirmation, { if (it.length <= BackupEncryption.MAX_PASSWORD_LENGTH) confirmation = it }, label = { Text(stringResource(R.string.settings_backup_confirm_password)) },
                            singleLine = true, enabled = !busy, visualTransformation = if (showConfirmation) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showConfirmation = !showConfirmation }, enabled = !busy) {
                                    Icon(if (showConfirmation) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (showConfirmation) uiContext.getString(R.string.ui_hide_confirmation_password) else uiContext.getString(R.string.ui_show_confirmation_password))
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                        Row {
                            Checkbox(includeKeys, { includeKeys = it }, enabled = !busy)
                            Text(stringResource(R.string.settings_backup_include_credentials), modifier = Modifier.padding(top = 12.dp))
                        }
                        Text(stringResource(R.string.settings_backup_include_note), style = MaterialTheme.typography.bodySmall)
                    }
                    if (busy) ThinLinearProgress(Modifier.fillMaxWidth())
                    if (failed) message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && password.length >= BackupEncryption.MIN_PASSWORD_LENGTH && (!exporting || password == confirmation), onClick = {
                    val chars = password.toCharArray()
                    password = ""
                    confirmation = ""
                    showPassword = false
                    showConfirmation = false
                    val target = destination
                    val input = source
                    busy = true
                    failed = false
                    scope.launch {
                        try {
                            if (target != null) {
                                backup.exportTo(target, chars, includeKeys)
                                clear()
                                message = context.getString(R.string.settings_backup_exported)
                            } else if (input != null) {
                                preview = backup.read(input, chars)
                                source = null
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            failed = true
                            message = if (exporting) settingsBackupExportError(e) else uiContext.getString(R.string.ui_could_not_unlock_backup_check_the_password)
                        } finally { chars.fill('\u0000'); busy = false }
                    }
                }) { Text(if (exporting) uiContext.getString(R.string.ui_encrypt) else uiContext.getString(R.string.ui_unlock)) }
            },
            dismissButton = { TextButton(onClick = { clear() }, enabled = !busy) { Text(stringResource(R.string.settings_action_cancel)) } },
        )
    }
    preview?.let { file ->
        AlertDialog(
            onDismissRequest = { if (!busy) clear() },
            title = { Text(stringResource(R.string.settings_backup_restore_title)) },
            text = {
                SecureDialogEffect()
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(
                            R.string.settings_backup_contents,
                            file.providers.count { it.config.id != com.androidharness.app.llm.HarnessProvider.ID }.toString(),
                            file.servers.size.toString(),
                            file.memories.size.toString(),
                        ),
                    )
                    Text(stringResource(R.string.settings_backup_restore_note))
                    Text(stringResource(R.string.settings_backup_restore_trust))
                    file.providers.filterNot { it.config.id == com.androidharness.app.llm.HarnessProvider.ID }.forEach { entry ->
                        Text(
                            stringResource(R.string.settings_backup_provider_line, entry.config.name, entry.config.baseUrl),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(stringResource(R.string.settings_backup_restore_keys), style = MaterialTheme.typography.bodySmall)
                    if (busy) ThinLinearProgress(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                val restore: () -> Unit = {
                    busy = true
                    scope.launch {
                        try {
                            backup.restore(file)
                            failed = false
                            message = context.getString(R.string.settings_backup_restored)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            failed = true
                            message = context.getString(R.string.settings_backup_restore_failed)
                        } finally { clear(); busy = false }
                    }
                }
                // A portable settings file must not be a way to skip the
                // device-owner check that guards full access.
                if (file.settings.permissionMode == PermissionMode.FULL_ACCESS) {
                    BiometricAuth.requireFullAccessApproval(
                        context,
                        context.getString(R.string.settings_full_access_auth_title),
                        context.getString(R.string.settings_full_access_auth_subtitle),
                        restore,
                    )
                } else {
                    restore()
                }
            }) { Text(stringResource(R.string.settings_action_restore)) } },
            dismissButton = { TextButton(onClick = { clear() }, enabled = !busy) { Text(stringResource(R.string.settings_action_cancel)) } },
        )
    }
}
