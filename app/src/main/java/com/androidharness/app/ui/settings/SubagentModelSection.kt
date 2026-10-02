package com.androidharness.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.data.AppSettings
import com.androidharness.app.llm.ModelCatalog
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import kotlinx.coroutines.launch

@Composable
internal fun SubagentModelSection(container: AppContainer, settings: AppSettings) {
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val catalogs by container.providers.catalogs.collectAsStateWithLifecycle(initialValue = emptyMap())
    val scope = rememberCoroutineScope()
    var picking by rememberSaveable { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }
    val selected = providers.firstOrNull { it.id == settings.subagentProviderId }
    val pickerProviderId = settings.subagentProviderId ?: settings.activeProviderId ?: providers.firstOrNull()?.id

    SettingsAnchor("Subagent model") {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { picking = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Subagent model", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                settings.subagentProviderId == null -> "Use main agent model"
                                selected == null -> "Selected provider unavailable"
                                else -> "${selected.name} · ${settings.subagentModel ?: selected.model}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Choose subagent model")
                }
                Text(
                    "Choose a provider and model for all subagents. The main agent keeps its current model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.subagentProviderId != null) {
                    TextButton(onClick = { scope.launch { container.settings.setSubagentModel(null, null) } }) {
                        Text("Use main agent model")
                    }
                }
            }
        }
    }

    if (picking) ModelPickerSheet(
        providers = providers,
        activeProviderId = pickerProviderId,
        activeModel = if (settings.subagentProviderId == null) settings.activeModel else settings.subagentModel,
        catalogs = catalogs,
        onDismiss = { picking = false },
        onSelect = { providerId, model ->
            scope.launch { container.settings.setSubagentModel(providerId, model) }
            picking = false
        },
        onRefreshCatalog = { providerId ->
            val provider = providers.firstOrNull { it.id == providerId }
            val key = container.providers.apiKey(providerId)
            when {
                provider == null -> "Unknown provider"
                key.isNullOrBlank() -> "No API key for this provider"
                else -> when (val result = ModelCatalog.listModels(provider, key)) {
                    is ModelCatalog.Result.Models -> {
                        container.providers.saveCatalog(providerId, result.models)
                        null
                    }
                    is ModelCatalog.Result.Failed -> result.message
                }
            }
        },
        onAddCustomModel = { providerId, model, reasoning ->
            scope.launch { container.providers.addCustomModel(providerId, model, reasoning) }
        },
        onDeleteCustomModel = { providerId, model ->
            scope.launch { container.providers.removeCustomModel(providerId, model) }
        },
        onManageProviders = { picking = false; managing = true },
    )

    if (managing) ProviderManagerSheet(
        providers = providers,
        activeProviderId = pickerProviderId,
        apiKey = container.providers::apiKey,
        onDismiss = { managing = false },
        onSetActive = { providerId ->
            scope.launch {
                container.settings.setSubagentModel(providerId, null)
                managing = false
                picking = true
            }
        },
        onDelete = { providerId -> scope.launch { container.providers.delete(providerId) } },
        onSave = { existing, name, type, baseUrl, model, apiKey ->
            scope.launch {
                val provider = if (existing == null) container.providers.add(name, type, baseUrl, model, apiKey)
                else existing.copy(name = name, type = type, baseUrl = baseUrl, model = model).also {
                    container.providers.update(it, apiKey)
                }
                container.settings.setSubagentModel(provider.id, null)
                managing = false
                picking = true
            }
        },
    )
}
