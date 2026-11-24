package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.tools.mcp.TrustItemKind
import com.androidharness.app.tools.mcp.TrustItemState
import com.androidharness.app.tools.mcp.TrustPrompt
import com.androidharness.app.tools.mcp.WorkspaceTrustMode
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

/** "MCP server · New" style line for one trust item. */
@Composable
internal fun trustItemSummary(prompt: TrustPrompt): String {
    val kind = stringResource(when (prompt.item.kind) {
        TrustItemKind.SERVER -> R.string.trust_kind_server
        TrustItemKind.PLUGIN -> R.string.trust_kind_plugin
        TrustItemKind.LANGUAGE_SERVER -> R.string.trust_kind_language_server
        TrustItemKind.PLUGIN_BIN -> R.string.trust_kind_bin
    })
    val state = stringResource(when (prompt.state) {
        TrustItemState.APPROVED -> R.string.trust_state_approved
        TrustItemState.NEW -> R.string.trust_state_new
        TrustItemState.CHANGED -> R.string.trust_state_changed
        TrustItemState.DECLINED -> R.string.trust_state_declined
        TrustItemState.BLOCKED -> R.string.trust_state_blocked
    })
    return "$kind · $state"
}

@Composable
internal fun WorkspaceTrustPanel(
    mode: WorkspaceTrustMode,
    items: List<TrustPrompt>,
    enabled: Boolean,
    onMode: (WorkspaceTrustMode) -> Unit,
    onItem: (key: String, allow: Boolean) -> Unit,
    onAllowAll: () -> Unit,
    onForget: () -> Unit,
    itemExtra: @Composable ColumnScope.(TrustPrompt) -> Unit = {},
) {
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.trust_panel_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.trust_panel_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text(stringResource(R.string.trust_mode_title), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 6.dp))
            Column(Modifier.selectableGroup()) {
                listOf(
                    Triple(WorkspaceTrustMode.ASK_CHANGES, R.string.trust_mode_ask_changes, R.string.trust_mode_ask_changes_hint),
                    Triple(WorkspaceTrustMode.ASK_NEW, R.string.trust_mode_ask_new, R.string.trust_mode_ask_new_hint),
                    Triple(WorkspaceTrustMode.TRUST_ALL, R.string.trust_mode_trust_all, R.string.trust_mode_trust_all_hint),
                    Triple(WorkspaceTrustMode.BLOCK_ALL, R.string.trust_mode_block_all, R.string.trust_mode_block_all_hint),
                ).forEach { (value, label, hint) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = mode == value, enabled = enabled, role = Role.RadioButton) { onMode(value) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = mode == value, onClick = null, enabled = enabled)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.trust_items_title), style = MaterialTheme.typography.titleSmall)
            val asking = mode == WorkspaceTrustMode.ASK_CHANGES || mode == WorkspaceTrustMode.ASK_NEW
            Text(stringResource(if (asking) R.string.trust_items_hint else R.string.trust_items_mode_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            items.forEach { prompt ->
                val allowed = prompt.state == TrustItemState.APPROVED
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(value = allowed, enabled = enabled && asking, role = Role.Switch) { onItem(prompt.item.key, it) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(prompt.item.name, style = MaterialTheme.typography.titleSmall)
                            Text(trustItemSummary(prompt), style = MaterialTheme.typography.labelMedium,
                                color = if (prompt.state == TrustItemState.NEW || prompt.state == TrustItemState.CHANGED)
                                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (prompt.item.detail.isNotBlank()) Text(prompt.item.detail, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        Switch(checked = allowed, onCheckedChange = null, enabled = enabled && asking)
                    }
                    itemExtra(prompt)
                }
            }
            if (asking) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onForget, enabled = enabled) { Text(stringResource(R.string.trust_forget)) }
                OutlinedButton(onClick = onAllowAll, enabled = enabled && items.any { it.state != TrustItemState.APPROVED }) {
                    Text(stringResource(R.string.trust_allow_all))
                }
            }
        }
    }
}
