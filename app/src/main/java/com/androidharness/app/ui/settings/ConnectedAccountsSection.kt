package com.androidharness.app.ui.settings

import android.net.Uri
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.chatgpt.ChatGptProtocol
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.ui.common.openOAuthBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun ConnectedAccountsSection(container: AppContainer) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
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
                e.message in setOf(uiContext.getString(R.string.ui_this_account_did_not_offer_any_models), uiContext.getString(R.string.ui_reconnect_chatgpt_to_use_this_account),
                    uiContext.getString(R.string.ui_choose_a_model_offered_by_this_account)) -> e.message
                else -> uiContext.getString(R.string.ui_could_not_update_this_account_check_your)
            }
        }
    } }
    ChatGptAccountsPanel(state, settings.activeProviderId, settings.activeModel, selecting,
        error = selectionError ?: state.error,
        onAdd = { container.chatGpt.startSignIn { openOAuthBrowser(context, Uri.parse(it)) } },
        onCancelSignIn = container.chatGpt::cancelSignIn,
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
        onRefresh = { container.chatGpt.refresh(it.providerId) },
        onCheckModels = { container.chatGpt.checkNewerModels(it.providerId) },
        onReconnect = { account -> container.chatGpt.startSignIn(account.providerId) { openOAuthBrowser(context, Uri.parse(it)) } },
        onSignOut = { pendingSignOut = it.providerId },
        onUsage = { openOAuthBrowser(context, Uri.parse(ChatGptProtocol.USAGE_URL)) },
    )
    if (state.showWelcome) AlertDialog(
        onDismissRequest = { update { container.chatGpt.dismissWelcome() } },
        title = { Text(stringResource(R.string.settings_accounts_using_plan)) },
        text = { Text(stringResource(R.string.settings_accounts_using_plan_desc)) },
        confirmButton = { TextButton(onClick = { update { container.chatGpt.dismissWelcome() } }) { Text(stringResource(R.string.settings_action_got_it)) } },
    )
    pendingSignOut?.let { id -> AlertDialog(
        onDismissRequest = { pendingSignOut = null },
        title = { Text(stringResource(R.string.settings_accounts_signout_title)) },
        text = { Text(stringResource(R.string.settings_accounts_signout_message)) },
        confirmButton = { TextButton(onClick = { container.chatGpt.signOut(id); pendingSignOut = null }) { Text(stringResource(R.string.settings_chatgpt_sign_out)) } },
        dismissButton = { TextButton(onClick = { pendingSignOut = null }) { Text(stringResource(R.string.settings_action_cancel)) } },
    ) }
}
