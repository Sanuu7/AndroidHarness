package com.androidharness.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.ui.github.*
import com.androidharness.app.ui.common.SecureScreenEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GitHubIntegrationSection(container: AppContainer) {
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
            .onFailure { message = "Could not open the browser. Open $url in your browser." }
    }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.login?.takeIf { it.isNotEmpty() }?.let { "Connected as $it" } ?: "Connect GitHub",
                style = MaterialTheme.typography.titleMedium)
            Text(if (state.login != null) {
                if (state.oauth) "GitHub sign-in · credentials stored securely on this device"
                else "Personal access token · credentials stored securely on this device"
            } else "Sign in to import private repositories and publish your work.", style = MaterialTheme.typography.bodySmall)

            if (state.code != null) {
                val code = requireNotNull(state.code)
                Text(code.userCode, style = MaterialTheme.typography.headlineSmall)
                Text("Copy this code and enter it on GitHub. Return here after approving access.")
                Button(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("GitHub sign-in code", code.userCode))
                    open(code.url)
                }, modifier = Modifier.fillMaxWidth()) { Text("Copy code & open GitHub") }
                TextButton(onClick = { container.github.cancelSignIn() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel sign-in") }
            } else if (!patForm && !state.busy) {
                Button(onClick = { message = null; container.github.startSignIn(workflow) },
                    enabled = !working && container.github.clientId.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.login == null) "Sign in with GitHub" else "Sign in with another account")
                }
                if (container.github.clientId.isBlank()) Text("GitHub sign-in is unavailable in this build. You can use a personal access token.",
                    style = MaterialTheme.typography.bodySmall)
                Row {
                    Checkbox(workflow, onCheckedChange = { workflow = it })
                    Text("Allow editing GitHub Actions workflows", modifier = Modifier.weight(1f).padding(top = 12.dp))
                }
                OutlinedButton(onClick = { patForm = true; token = ""; message = null },
                    enabled = !working, modifier = Modifier.fillMaxWidth()) { Text("Use a personal access token") }
            }
            if (state.busy || working) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (patForm) {
                Text("For fine-grained tokens, include the target repositories and Contents: read and write. Workflow changes also need Workflows access. Organization approval or SSO may be required.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { open("https://github.com/settings/tokens?type=beta") },
                    modifier = Modifier.fillMaxWidth()) { Text("Create a token on GitHub") }
                OutlinedTextField(token, onValueChange = { token = it }, label = { Text("Personal access token") },
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
                }, enabled = !working && token.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Verify & connect") }
                TextButton(onClick = { token = ""; patForm = false }, enabled = !working,
                    modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (state.login != null) TextButton(onClick = { logout = true }, enabled = !working && !state.busy,
                modifier = Modifier.fillMaxWidth()) { Text("Disconnect GitHub") }
        }
    }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Repositories", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { importing = true }, modifier = Modifier.fillMaxWidth()) { Text("Import from GitHub") }
            Text(project?.let { "Current workspace: ${it.name}" } ?: "Choose a workspace to publish changes.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { project?.let { publishing = it.id to it.name } },
                enabled = project != null, modifier = Modifier.fillMaxWidth()) { Text("Commit & push") }
            Text("Review the repository, branch and files before publishing. Save a push preset from these controls to reuse it in Automation.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (importing) GitHubImportDialog(container, onDismiss = { importing = false })
    publishing?.let { (id, name) -> GitHubPublishDialog(container, id, name, onDismiss = { publishing = null }) }
    if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text("Disconnect GitHub?") },
        text = { Text("Remove the saved connection and repository credentials from this device. Your repositories and local commits are kept.") },
        confirmButton = { TextButton(onClick = {
            logout = false; working = true
            scope.launch {
                try { container.github.disconnect() }
                catch (e: Exception) { message = container.github.safeMessage(e) }
                finally { working = false }
            }
        }) { Text("Disconnect") } }, dismissButton = { TextButton(onClick = { logout = false }) { Text("Cancel") } })
}
