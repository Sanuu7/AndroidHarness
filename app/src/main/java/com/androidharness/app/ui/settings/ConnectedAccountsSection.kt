package com.androidharness.app.ui.settings

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.chatgpt.ChatGptProtocol
import com.androidharness.app.data.AppSettings
import com.androidharness.app.ui.common.openOAuthBrowser
import kotlinx.coroutines.launch

@Composable
internal fun ConnectedAccountsSection(container: AppContainer) {
    val state by container.chatGpt.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSignOut by remember { mutableStateOf<String?>(null) }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ChatGPT", style = MaterialTheme.typography.titleMedium)
            Text("Use your ChatGPT Plus or Pro plan for coding, editing files and running tools. Available models and usage limits depend on your account.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsAnchor("Continue with ChatGPT") {
                Button(
                    onClick = { container.chatGpt.startSignIn { openOAuthBrowser(context, Uri.parse(it)) } },
                    enabled = !state.signingIn,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continue with ChatGPT") }
            }
            if (state.signingIn) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Finish signing in in your browser, then return here.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = container.chatGpt::cancelSignIn) { Text("Cancel sign-in") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            state.accounts.forEach { account ->
                HorizontalDivider()
                Text(account.label, style = MaterialTheme.typography.titleSmall)
                Text(if (account.connected) "Using ChatGPT plan · ${account.models.size} models" else "Signed out",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (account.connected) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { scope.launch {
                            container.settings.setActiveProvider(account.providerId)
                            container.settings.setActiveModel(account.models.first().id)
                        } }, enabled = account.models.isNotEmpty()) {
                            Text(if (settings.activeProviderId == account.providerId) "Selected" else "Use in chat")
                        }
                        TextButton(onClick = { container.chatGpt.refresh(account.providerId) }) { Text("Refresh models") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        container.chatGpt.startSignIn(account.providerId) { openOAuthBrowser(context, Uri.parse(it)) }
                    }, enabled = !state.signingIn) { Text(if (account.connected) "Reconnect" else "Sign in again") }
                    if (account.connected) TextButton(onClick = { pendingSignOut = account.providerId }) { Text("Sign out") }
                }
            }
            TextButton(onClick = { openOAuthBrowser(context, Uri.parse(ChatGptProtocol.USAGE_URL)) }) { Text("Manage usage") }
        }
    }
    if (state.showWelcome) AlertDialog(
        onDismissRequest = { scope.launch { container.chatGpt.dismissWelcome() } },
        title = { Text("You're using your ChatGPT plan") },
        text = { Text("Eligible requests in AndroidHarness use your ChatGPT plan or credits. You can review usage and manage this app's access in ChatGPT settings.") },
        confirmButton = { TextButton(onClick = { scope.launch { container.chatGpt.dismissWelcome() } }) { Text("Got it") } },
    )
    pendingSignOut?.let { id -> AlertDialog(
        onDismissRequest = { pendingSignOut = null },
        title = { Text("Sign out of ChatGPT?") },
        text = { Text("This ends the app's connection to this account. You can sign in again later.") },
        confirmButton = { TextButton(onClick = { container.chatGpt.signOut(id); pendingSignOut = null }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { pendingSignOut = null }) { Text("Cancel") } },
    ) }
}
