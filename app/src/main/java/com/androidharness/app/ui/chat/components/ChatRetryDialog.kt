package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.core.ChatExperience
import com.androidharness.app.data.ModelShortcut
import com.androidharness.app.data.ModelShortcuts
import com.androidharness.app.data.ModelVisibility
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.ui.theme.HarnessGlassAlertDialog
import com.androidharness.app.ui.common.TextButton

/**
 * Models offered for a one-off retry: the user's favourites, then the ones they picked
 * most recently, minus the model already in use and anything hidden or no longer configured.
 */
internal fun retryModelChoices(
    favourites: List<ModelShortcut>,
    recent: List<ModelShortcut>,
    providers: List<ProviderConfig>,
    current: ModelShortcut?,
    isVisible: (providerId: String, modelId: String) -> Boolean,
): List<ModelShortcut> = (favourites + recent).distinct().filter { key ->
    key != current && providers.any { it.id == key.providerId } && isVisible(key.providerId, key.modelId)
}

/**
 * Retry starts with a plain choice: run it again as is, or pick another model from the
 * ones already used or starred. [onRetryWith] never changes the chat's own model.
 */
@Composable
internal fun ChatRetryDialog(
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    activeModel: String?,
    onRetry: () -> Unit,
    onRetryWith: (ModelShortcut) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val shortcuts = remember(context) { ModelShortcuts.get(context) }
    val visibility = remember(context) { ModelVisibility.get(context) }
    val saved by shortcuts.state.collectAsStateWithLifecycle()
    val visible by visibility.state.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    val current = remember(providers, activeProviderId, activeModel) {
        val provider = providers.firstOrNull { it.id == activeProviderId } ?: return@remember null
        ModelShortcut(provider.id, activeModel?.takeIf(String::isNotBlank) ?: provider.model)
    }
    val choices = remember(saved, visible, providers, current) {
        retryModelChoices(saved.favourites, saved.recent, providers, current, visible::includes)
    }
    HarnessGlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (choosing) R.string.chat_retry_with_model else R.string.chat_retry_reply)) },
        text = {
            if (!choosing) {
                Column {
                    RetryOption(Icons.Outlined.Refresh, stringResource(R.string.ui_retry), stringResource(R.string.chat_retry_same_desc), onRetry)
                    RetryOption(Icons.Outlined.SwapHoriz, stringResource(R.string.chat_retry_with_model), null) { choosing = true }
                }
            } else if (choices.isEmpty()) {
                Text(stringResource(R.string.chat_retry_no_models), style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.chat_retry_model_hint), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(choices, key = { it.providerId + "\u0000" + it.modelId }) { choice ->
                            val provider = providers.firstOrNull { it.id == choice.providerId }
                            RetryModelRow(
                                label = ChatExperience.modelLabel(choice.modelId) ?: choice.modelId,
                                provider = provider?.name.orEmpty(),
                                favourite = choice in saved.favourites,
                                onClick = { onRetryWith(choice) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (choosing) TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.settings_action_back)) }
            else TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) }
        },
    )
}

@Composable
private fun RetryOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RetryModelRow(label: String, provider: String, favourite: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (provider.isNotBlank()) Text(provider, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (favourite) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
    }
}
