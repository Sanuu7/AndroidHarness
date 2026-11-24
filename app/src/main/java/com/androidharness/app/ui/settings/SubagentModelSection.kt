package com.androidharness.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.data.AppSettings
import com.androidharness.app.llm.ModelCatalog
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.R
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.TextButton

@Composable
internal fun SubagentModelSection(container: AppContainer, settings: AppSettings) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    ModelOverrideSection(
        container = container,
        settings = settings,
        title = R.string.settings_anchor_subagent_model,
        followLabel = R.string.settings_subagent_use_main,
        description = uiContext.getString(R.string.ui_choose_a_provider_and_model_for_all),
        chooseLabel = R.string.settings_subagent_choose_model,
        providerId = settings.subagentProviderId,
        model = settings.subagentModel,
        onSet = container.settings::setSubagentModel,
    )
}

/**
 * A model picked for one part of the app, or nothing to follow the main model. [onSet] with a
 * null provider goes back to following.
 */
@Composable
internal fun ModelOverrideSection(
    container: AppContainer,
    settings: AppSettings,
    title: Int,
    followLabel: Int,
    description: String,
    chooseLabel: Int,
    providerId: String?,
    model: String?,
    onSet: suspend (providerId: String?, model: String?) -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val catalogs by container.providers.catalogs.collectAsStateWithLifecycle(initialValue = emptyMap())
    val scope = rememberCoroutineScope()
    var picking by rememberSaveable { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }
    val selected = providers.firstOrNull { it.id == providerId }
    val pickerProviderId = providerId ?: settings.activeProviderId ?: providers.firstOrNull()?.id

    SettingsAnchor(title) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { picking = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                providerId == null -> uiContext.getString(followLabel)
                                selected == null -> uiContext.getString(R.string.ui_selected_provider_unavailable)
                                else -> "${selected.name} · ${model ?: selected.model}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = stringResource(chooseLabel))
                }
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (providerId != null) {
                    TextButton(onClick = { scope.launch { onSet(null, null) } }) {
                        Text(stringResource(followLabel))
                    }
                }
            }
        }
    }

    if (picking) ModelPickerSheet(
        providers = providers,
        activeProviderId = pickerProviderId,
        activeModel = if (providerId == null) settings.activeModel else model,
        catalogs = catalogs,
        onDismiss = { picking = false },
        onSelect = { providerId, model ->
            scope.launch { onSet(providerId, model) }
            picking = false
        },
        onRefreshCatalog = { providerId ->
            val provider = providers.firstOrNull { it.id == providerId }
            val key = container.providers.apiKey(providerId)
            when {
                provider == null -> uiContext.getString(R.string.ui_unknown_provider)
                else -> {
                    val usable = provider.effectiveApiKey(key)
                    if (usable.isBlank()) uiContext.getString(R.string.ui_no_api_key_for_this_provider)
                    else when (val result = ModelCatalog.listModels(provider, usable)) {
                        is ModelCatalog.Result.Models -> {
                            container.providers.saveCatalog(providerId, result.models)
                            null
                        }
                        is ModelCatalog.Result.Failed -> result.message
                    }
                }
            }
        },
        onAddCustomModel = { providerId, model, reasoning ->
            scope.launch { container.providers.addCustomModel(providerId, model, reasoning) }
        },
        onDeleteCustomModel = { providerId, model ->
            scope.launch { container.providers.removeCustomModel(providerId, model) }
        },
        onEnableProvider = { id -> scope.launch { container.providers.setEnabled(id, true) } },
        onManageProviders = { picking = false; managing = true },
    )

    if (managing) ProviderManagerSheet(
        providers = providers,
        activeProviderId = pickerProviderId,
        apiKeys = container.providers::apiKeys,
        onDismiss = { managing = false },
        onSetActive = { providerId ->
            scope.launch {
                onSet(providerId, null)
                managing = false
                picking = true
            }
        },
        onDelete = { providerId -> scope.launch { container.providers.delete(providerId) } },
        onEnabledChange = { id, enabled -> scope.launch { container.providers.setEnabled(id, enabled) } },
        onMove = { id, direction, visible -> scope.launch { container.providers.move(id, direction, visible) } },
        onSave = { existing, name, type, baseUrl, model, apiKey, headers ->
            scope.launch {
                val provider = if (existing == null) container.providers.add(name, type, baseUrl, model, apiKey, headers)
                else existing.copy(name = name, type = type, baseUrl = baseUrl, model = model, headers = headers).also {
                    container.providers.update(it, apiKey)
                }
                onSet(provider.id, null)
                managing = false
                picking = true
            }
        },
    )
}
