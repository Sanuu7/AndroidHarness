package com.androidharness.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.tools.mcp.TrustPrompt
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.settings.trustItemSummary

/**
 * Asks about the workspace servers and plugins that are new or changed. Ticked
 * ones are allowed, unticked ones are remembered as not allowed, and "Trust
 * everything" switches the whole workspace to never asking.
 */
@Composable
internal fun WorkspaceTrustDialog(
    prompts: List<TrustPrompt>,
    onContinue: (allowed: Set<String>, trustAll: Boolean) -> Unit,
    onNotNow: () -> Unit,
) {
    var allowed by remember(prompts) { mutableStateOf(prompts.map { it.item.key }.toSet()) }
    var trustAll by remember(prompts) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text(stringResource(R.string.trust_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.trust_dialog_body), style = MaterialTheme.typography.bodyMedium)
                prompts.forEach { prompt ->
                    val key = prompt.item.key
                    val checked = trustAll || key in allowed
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = checked, enabled = !trustAll, role = Role.Checkbox) {
                            allowed = if (it) allowed + key else allowed - key
                        },
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = !trustAll, modifier = Modifier.padding(12.dp))
                        Column(Modifier.weight(1f).padding(top = 10.dp, bottom = 6.dp)) {
                            Text(prompt.item.name, style = MaterialTheme.typography.titleSmall)
                            Text(trustItemSummary(prompt), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            if (prompt.item.detail.isNotBlank()) Text(prompt.item.detail, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (!trustAll && allowed.size < prompts.size) Text(stringResource(R.string.trust_dialog_unticked),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(
                    Modifier.fillMaxWidth().toggleable(value = trustAll, role = Role.Checkbox) { trustAll = it },
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(checked = trustAll, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Column(Modifier.weight(1f).padding(top = 10.dp, bottom = 6.dp)) {
                        Text(stringResource(R.string.trust_dialog_trust_all), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.trust_dialog_trust_all_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onContinue(allowed, trustAll) }) { Text(stringResource(R.string.trust_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) { Text(stringResource(R.string.trust_not_now)) }
        },
    )
}
