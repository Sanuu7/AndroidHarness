package com.androidharness.app.ui.settings

import com.androidharness.app.ui.common.AppLinearProgressIndicator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.AppContainer
import com.androidharness.app.ui.github.*
import com.androidharness.app.ui.common.SecureScreenEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun GitHubIntegrationSection(container: AppContainer) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val state by container.github.state.collectAsStateWithLifecycle()
    val project by container.workspace.currentProject.collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var patForm by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var workflow by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var logout by remember { mutableStateOf(false) }
    SecureScreenEffect(container, patForm)

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { message = context.getString(R.string.settings_github_open_browser_failed, url) }
    }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.login?.takeIf { it.isNotEmpty() }?.let { uiContext.getString(R.string.ui_connected_as, (it).toString()) } ?: uiContext.getString(R.string.ui_connect_github),
                style = MaterialTheme.typography.titleMedium)
            Text(if (state.login != null) {
                if (state.oauth) uiContext.getString(R.string.ui_github_sign_in_credentials_stored_securely_on)
                else uiContext.getString(R.string.ui_personal_access_token_credentials_stored_securely_on)
            } else uiContext.getString(R.string.ui_sign_in_to_import_private_repositories_and), style = MaterialTheme.typography.bodySmall)

            if (state.code != null) {
                val code = requireNotNull(state.code)
                Text(code.userCode, style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.settings_github_copy_code_note))
                Button(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText(uiContext.getString(R.string.ui_github_sign_in_code), code.userCode))
                    open(code.url)
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_github_copy_open)) }
                TextButton(onClick = { container.github.cancelSignIn() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_chatgpt_cancel_signin)) }
            } else if (!patForm && !state.busy) {
                Button(onClick = { message = null; container.github.startSignIn(workflow) },
                    enabled = !working && container.github.clientId.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.login == null) uiContext.getString(R.string.ui_sign_in_with_github) else uiContext.getString(R.string.ui_sign_in_with_another_account))
                }
                if (container.github.clientId.isBlank()) Text(stringResource(R.string.settings_github_signin_unavailable),
                    style = MaterialTheme.typography.bodySmall)
                Row {
                    Checkbox(workflow, onCheckedChange = { workflow = it })
                    Text(stringResource(R.string.settings_github_allow_workflows), modifier = Modifier.weight(1f).padding(top = 12.dp))
                }
                OutlinedButton(onClick = { patForm = true; token = ""; message = null },
                    enabled = !working, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_github_use_pat)) }
            }
            if (state.busy || working) AppLinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (patForm) {
                Text(stringResource(R.string.settings_github_pat_scope_note),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { open("https://github.com/settings/tokens?type=beta") },
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_github_create_token)) }
                OutlinedTextField(token, onValueChange = { token = it }, label = { Text(stringResource(R.string.settings_github_pat)) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !working,
                    modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    working = true
                    scope.launch {
                        try { container.github.connectPat(token); token = ""; patForm = false; message = null }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = container.github.safeMessage(e) }
                        finally { working = false }
                    }
                }, enabled = !working && token.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_github_verify_connect)) }
                TextButton(onClick = { token = ""; patForm = false }, enabled = !working,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_action_cancel)) }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (state.login != null) TextButton(onClick = { logout = true }, enabled = !working && !state.busy,
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_github_disconnect)) }
        }
    }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.settings_github_repositories), style = MaterialTheme.typography.titleMedium)
            Button(onClick = { importing = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_github_import)) }
            Text(project?.let { uiContext.getString(R.string.ui_current_workspace_1002, (it.name).toString()) } ?: uiContext.getString(R.string.ui_choose_a_workspace_to_publish_changes), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { project?.let { publishing = it.id to it.name } },
                enabled = project != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_github_commit_push)) }
            Text(stringResource(R.string.settings_github_commit_note),
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (importing) GitHubImportDialog(container, onDismiss = { importing = false })
    publishing?.let { (id, name) -> GitHubPublishDialog(container, id, name, onDismiss = { publishing = null }) }
    if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text(stringResource(R.string.settings_github_disconnect_title)) },
        text = { Text(stringResource(R.string.settings_github_disconnect_message)) },
        confirmButton = { TextButton(onClick = {
            logout = false; working = true
            scope.launch {
                try { container.github.disconnect() }
                catch (e: Exception) { message = container.github.safeMessage(e) }
                finally { working = false }
            }
        }) { Text(stringResource(R.string.settings_action_disconnect)) } }, dismissButton = { TextButton(onClick = { logout = false }) { Text(stringResource(R.string.settings_action_cancel)) } })
}
