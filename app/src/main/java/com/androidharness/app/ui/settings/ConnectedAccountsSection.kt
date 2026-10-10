package com.androidharness.app.ui.settings

import android.net.Uri
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.chatgpt.ChatGptProtocol
import com.androidharness.app.data.AppSettings
import com.androidharness.app.ui.common.openOAuthBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ConnectedAccountsSection(container: AppContainer) {
    val state by container.chatGpt.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSignOut by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf<String?>(null) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    fun update(action: suspend () -> Unit) { scope.launch {
        try { action(); selectionError = null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            selectionError = when {
                e is java.io.IOException && e.message?.startsWith("ChatGPT") == true -> e.message
                e.message in setOf("This account did not offer any models.", "Reconnect ChatGPT to use this account.",
                    "Choose a model offered by this account.") -> e.message
                else -> "Could not update this account. Check your connection and try again."
            }
        }
    } }
    ChatGptAccountsPanel(state, settings.activeProviderId, settings.activeModel, selecting,
        error = selectionError ?: state.error,
        onAdd = { container.chatGpt.startSignIn { openOAuthBrowser(context, Uri.parse(it)) } },
        onCancelSignIn = container.chatGpt::cancelSignIn,
        onAutoSwitch = { update { container.chatGpt.setAutoSwitch(it) } },
        onSelect = { account ->
            if (selecting == null) {
                selecting = account.providerId
                update {
                    try {
                        val config = container.chatGpt.selectAccount(account.providerId, settings.activeModel.orEmpty())
                        container.settings.setActiveSelection(config.id, config.model)
                    } finally { selecting = null }
                }
            }
        },
        onFallback = { account, enabled, model -> update { container.chatGpt.setFallback(account.providerId, enabled, model) } },
        onRefresh = { container.chatGpt.refresh(it.providerId) },
        onCheckModels = { container.chatGpt.checkNewerModels(it.providerId) },
        onReconnect = { account -> container.chatGpt.startSignIn(account.providerId) { openOAuthBrowser(context, Uri.parse(it)) } },
        onSignOut = { pendingSignOut = it.providerId },
        onUsage = { openOAuthBrowser(context, Uri.parse(ChatGptProtocol.USAGE_URL)) },
    )
    if (state.showWelcome) AlertDialog(
        onDismissRequest = { update { container.chatGpt.dismissWelcome() } },
        title = { Text("You're using your ChatGPT plan") },
        text = { Text("Eligible requests in AndroidHarness use your ChatGPT plan or credits. Review usage and manage this app's access in ChatGPT settings.") },
        confirmButton = { TextButton(onClick = { update { container.chatGpt.dismissWelcome() } }) { Text("Got it") } },
    )
    pendingSignOut?.let { id -> AlertDialog(
        onDismissRequest = { pendingSignOut = null },
        title = { Text("Sign out of this account?") },
        text = { Text("This disconnects the account from AndroidHarness and removes it from automatic switching. You can sign in again later.") },
        confirmButton = { TextButton(onClick = { container.chatGpt.signOut(id); pendingSignOut = null }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { pendingSignOut = null }) { Text("Cancel") } },
    ) }
}
