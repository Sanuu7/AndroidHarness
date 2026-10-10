package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.chatgpt.ChatGptAccount
import com.androidharness.app.chatgpt.ChatGptAccountState

@Composable
internal fun ChatGptAccountsPanel(
    state: ChatGptAccountState, selectedId: String?, selectedModel: String?, selecting: String?, error: String?,
    onAdd: () -> Unit, onCancelSignIn: () -> Unit, onAutoSwitch: (Boolean) -> Unit,
    onSelect: (ChatGptAccount) -> Unit, onFallback: (ChatGptAccount, Boolean, String?) -> Unit,
    onRefresh: (ChatGptAccount) -> Unit, onCheckModels: (ChatGptAccount) -> Unit,
    onReconnect: (ChatGptAccount) -> Unit, onSignOut: (ChatGptAccount) -> Unit, onUsage: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ChatGPT", style = MaterialTheme.typography.titleMedium)
                Text("Connect your accounts and choose which one to use.", style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
                SettingsAnchor("Continue with ChatGPT") {
                    Button(onClick = onAdd, enabled = !state.signingIn) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.accounts.isEmpty()) "Continue with ChatGPT" else "Add account")
                    }
                }
                if (state.signingIn) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Finish signing in in your browser, then return here.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCancelSignIn) { Text("Cancel sign-in") }
                }
                error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (state.accounts.isNotEmpty()) {
                HorizontalDivider(color = colors.outlineVariant)
                AccountSwitchRow("Auto-switch at usage limit",
                    "Continue with another account. Keep the same model when available, otherwise use its fallback.", state.autoSwitch, onAutoSwitch)
                if (state.autoSwitch && state.accounts.count { it.connected && it.useForAutoSwitch } < 2) {
                    Text("Add or enable another account to switch automatically.", style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                }
            }
            state.accounts.forEach { account ->
                key(account.providerId) {
                    HorizontalDivider(color = colors.outlineVariant)
                    var menu by remember { mutableStateOf(false) }
                    val selected = selectedId == account.providerId
                    val subtitle = when {
                        selecting == account.providerId -> "Checking available models…"
                        !account.connected -> "Signed out"
                        account.usageLimited -> if (selected) "Usage limit reached · try again in chat" else "Usage limit reached"
                        selected -> account.models.firstOrNull { it.id == selectedModel }?.displayName ?: selectedModel.orEmpty()
                        else -> "${account.models.size} available ${if (account.models.size == 1) "model" else "models"}"
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
                                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                                    color = if (account.usageLimited) colors.error else colors.onSurfaceVariant)
                                if (selected) Text("Selected", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                            }
                            if (selecting == account.providerId) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else if (selected) Icon(Icons.Outlined.CheckCircle, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Manage ${account.label}") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (account.connected) {
                                        DropdownMenuItem(text = { Text("Refresh models") }, onClick = { menu = false; onRefresh(account) })
                                        DropdownMenuItem(text = { Text("Check newer models") }, enabled = state.checkingModelsFor == null,
                                            onClick = { menu = false; onCheckModels(account) })
                                    }
                                    DropdownMenuItem(text = { Text(if (account.connected) "Reconnect" else "Sign in again") },
                                        enabled = !state.signingIn, onClick = { menu = false; onReconnect(account) })
                                    if (account.connected) DropdownMenuItem(text = { Text("Sign out", color = colors.error) },
                                        onClick = { menu = false; onSignOut(account) })
                                }
                            }
                        }
                    }
                    if (state.autoSwitch && account.connected) {
                        AccountSwitchRow("Include in auto-switch", null, account.useForAutoSwitch,
                            { onFallback(account, it, account.fallbackModel) }, inset = true)
                        if (account.useForAutoSwitch) FallbackModelRow(account) { onFallback(account, true, it) }
                    }
                    if (state.checkingModelsFor == account.providerId) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                        Text("Checking newer models. Small test requests use this account's plan.",
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
                Text("Limits & usage in ChatGPT")
            }
        }
    }
}

@Composable
private fun AccountSwitchRow(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit, inset: Boolean = false) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .padding(start = if (inset) 64.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun FallbackModelRow(account: ChatGptAccount, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choice = account.models.firstOrNull { it.id == account.fallbackModel }
    Box(Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, bottom = 12.dp)) {
        Surface(onClick = { expanded = true }, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Fallback model for ${account.label}"
            }) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Fallback model", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(choice?.displayName ?: choice?.id ?: "Automatic", style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Automatic") }, onClick = { expanded = false; onSelect(null) })
            account.models.forEach { model -> DropdownMenuItem(text = { Text(model.displayName ?: model.id) },
                onClick = { expanded = false; onSelect(model.id) }) }
        }
    }
}
