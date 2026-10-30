package com.androidharness.app.ui.settings

import com.androidharness.app.R
import com.androidharness.app.ui.common.AppLinearProgressIndicator
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.chatgpt.ChatGptAccount
import com.androidharness.app.chatgpt.ChatGptAccountState
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun ChatGptAccountsPanel(
    state: ChatGptAccountState, selectedId: String?, selectedModel: String?, selecting: String?, error: String?,
    onAdd: () -> Unit, onCancelSignIn: () -> Unit,
    onSelect: (ChatGptAccount) -> Unit,
    onRefresh: (ChatGptAccount) -> Unit, onCheckModels: (ChatGptAccount) -> Unit,
    onReconnect: (ChatGptAccount) -> Unit, onSignOut: (ChatGptAccount) -> Unit, onUsage: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val colors = MaterialTheme.colorScheme
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_chatgpt_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_chatgpt_subtitle), style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
                SettingsAnchor(R.string.settings_anchor_continue_chatgpt) {
                    Button(onClick = onAdd, enabled = !state.signingIn) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.accounts.isEmpty()) uiContext.getString(R.string.settings_search_continue_chatgpt_title) else uiContext.getString(R.string.ui_add_account))
                    }
                }
                if (state.signingIn) {
                    AppLinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.settings_chatgpt_finish_signin), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCancelSignIn) { Text(stringResource(R.string.settings_chatgpt_cancel_signin)) }
                }
                error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (state.accounts.isNotEmpty()) HorizontalDivider(color = colors.outlineVariant)
            state.accounts.forEach { account ->
                key(account.providerId) {
                    HorizontalDivider(color = colors.outlineVariant)
                    var menu by remember { mutableStateOf(false) }
                    val selected = selectedId == account.providerId
                    val subtitle = when {
                        selecting == account.providerId -> stringResource(R.string.settings_chatgpt_checking_available)
                        !account.connected -> stringResource(R.string.settings_chatgpt_signed_out)
                        selected -> account.models.firstOrNull { it.id == selectedModel }?.displayName ?: selectedModel.orEmpty()
                        else -> pluralStringResource(R.plurals.settings_accounts_models_available, account.models.size, account.models.size)
                    }
                    Surface(onClick = { if (account.connected) onSelect(account) else onReconnect(account) },
                        enabled = selecting == null && !state.signingIn, color = colors.surface,
                        modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(shape = CircleShape, color = if (selected) colors.primaryContainer else colors.surfaceContainerHigh) {
                                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                    Text(account.label.take(1).uppercase(), style = MaterialTheme.typography.titleSmall)
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(account.label, style = MaterialTheme.typography.titleSmall)
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                if (selected) Text(stringResource(R.string.settings_chatgpt_selected), style = MaterialTheme.typography.labelSmall, color = colors.primary)
                            }
                            if (selecting == account.providerId) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else if (selected) Icon(Icons.Outlined.CheckCircle, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.settings_accounts_manage_cd, account.label)) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (account.connected) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.settings_chatgpt_refresh_models)) }, onClick = { menu = false; onRefresh(account) })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.settings_chatgpt_check_new_models)) }, enabled = state.checkingModelsFor == null,
                                            onClick = { menu = false; onCheckModels(account) })
                                    }
                                    DropdownMenuItem(text = { Text(if (account.connected) uiContext.getString(R.string.ui_reconnect) else uiContext.getString(R.string.ui_sign_in_again)) },
                                        enabled = !state.signingIn, onClick = { menu = false; onReconnect(account) })
                                    if (account.connected) DropdownMenuItem(text = { Text(stringResource(R.string.settings_chatgpt_sign_out), color = colors.error) },
                                        onClick = { menu = false; onSignOut(account) })
                                }
                            }
                        }
                    }
                    if (state.checkingModelsFor == account.providerId) {
                        AppLinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                        Text(stringResource(R.string.settings_chatgpt_checking_models),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp), color = colors.onSurfaceVariant)
                    }
                    state.modelCheckResults[account.providerId]?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            TextButton(onClick = onUsage, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_chatgpt_limits_usage))
            }
        }
    }
}

