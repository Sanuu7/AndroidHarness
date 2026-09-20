package com.androidharness.app.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.data.env.TermuxSshConfig
import kotlinx.coroutines.launch

@Composable
internal fun TermuxSshDialog(container: AppContainer, onDismiss: () -> Unit) {
    val saved = remember { container.termuxSsh.config.value }
    var username by remember { mutableStateOf(saved.username) }
    var port by remember { mutableStateOf(saved.port.toString()) }
    var password by remember { mutableStateOf(saved.password) }
    var fingerprint by remember { mutableStateOf(saved.fingerprint) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!testing) onDismiss() },
        title = { Text("Termux SSH") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Runs terminal and agent shell/Git commands in Termux on this phone. Choose a shared device folder as your workspace first.")
                SelectionContainer {
                    Text("Run in Termux:\npkg install openssh git\ntermux-setup-storage\npasswd\nwhoami\nsshd\nssh-keygen -lf \$PREFIX/etc/ssh/ssh_host_ecdsa_key.pub -E sha256")
                }
                Text("Copy the username and SHA256 fingerprint above. Configure Git name, email and GitHub credentials inside Termux. AndroidHarness credentials are not copied.")
                OutlinedTextField(username, { username = it }, label = { Text("Termux username") },
                    enabled = !testing, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it }, label = { Text("Port on 127.0.0.1") },
                    enabled = !testing, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("SSH password") },
                    visualTransformation = PasswordVisualTransformation(), enabled = !testing,
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fingerprint, { fingerprint = it }, label = { Text("Host fingerprint (SHA256:…)") },
                    enabled = !testing, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Connection details are encrypted on this device. Each command uses a fresh shell; cd is remembered in the terminal. Start long-running servers directly in Termux.")
                result?.let { Text(it) }
            }
        },
        confirmButton = {
            Button(enabled = !testing, onClick = {
                testing = true
                result = null
                scope.launch {
                    try {
                        check(!container.terminal.state.value.busy && container.runManager.runningSessionIds.value.isEmpty()) {
                            "Wait for running terminal and agent commands to finish before switching modes"
                        }
                        val candidate = TermuxSshConfig(true, username.trim(), port.toIntOrNull() ?: 0,
                            password, fingerprint.trim())
                        candidate.validate()
                        val root = container.workspace.currentOnce().shellRoot
                            ?: error("Choose a shared device folder as your workspace first")
                        val check = container.termuxSsh.run("pwd && git --version", root, 15_000, 4_000, candidate)
                        check(check.exitCode == 0 && !check.timedOut) {
                            (check.rawOutput + "\n" + check.rawStderr + "\n" + check.note.orEmpty()).trim()
                        }
                        check(!container.terminal.state.value.busy && container.runManager.runningSessionIds.value.isEmpty()) {
                            "A command started during the connection test. Wait for it to finish, then try again."
                        }
                        container.termuxSsh.save(candidate)
                        container.terminal.useWorkspace(root)
                        container.terminal.connectionChanged()
                        onDismiss()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { result = e.message ?: "SSH connection failed" }
                    finally { testing = false }
                }
            }) { Text(if (testing) "Testing…" else "Test & enable") }
        },
        dismissButton = {
            Column {
                if (saved.enabled) TextButton(enabled = !testing, onClick = {
                    if (container.terminal.state.value.busy || container.runManager.runningSessionIds.value.isNotEmpty()) {
                        result = "Wait for running commands to finish before switching modes"
                    } else {
                        container.termuxSsh.save(saved.copy(enabled = false))
                        container.terminal.connectionChanged()
                        onDismiss()
                    }
                }) { Text("Use built-in") }
                TextButton(enabled = !testing, onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
