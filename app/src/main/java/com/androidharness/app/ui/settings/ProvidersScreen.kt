package com.androidharness.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.data.AppSettings
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import com.androidharness.app.ui.common.AppHeader
import kotlinx.coroutines.launch

@Composable
fun ProvidersScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val catalogs by container.providers.catalogs.collectAsStateWithLifecycle(initialValue = emptyMap())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showModelsFor by remember { mutableStateOf<String?>(null) }
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        containerColor = scheme.surface,
        topBar = {
            AppHeader(
                title = "Providers",
                subtitle = "Tap a row to make it active",
                onBack = onBack,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editing = null
                    showDialog = true
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add provider") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(providers, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    active = provider.id == settings.activeProviderId,
                    catalogSize = catalogs[provider.id]?.size,
                    onSetActive = {
                        scope.launch {
                            container.settings.setActiveProvider(provider.id)
                            // Keep a picked model only if it belongs here.
                            if (!catalogs[provider.id].orEmpty().any { it.id == settings.activeModel }) {
                                container.settings.setActiveModel(null)
                            }
                            // Adapt the stored thinking tier to the new model.
                            com.androidharness.app.agent.ThinkingSpecs.clampStoredLevel(
                                container.settings,
                                provider.model,
                                com.androidharness.app.llm.ModelsDev.providerKeyFor(provider.baseUrl),
                            )
                        }
                    },
                    onEdit = {
                        editing = provider
                        showDialog = true
                    },
                    onDelete = {
                        scope.launch { container.providers.delete(provider.id) }
                    },
                    onBrowseModels = { showModelsFor = provider.id },
                )
            }
            if (providers.isEmpty()) {
                item {
                    Text(
                        "No providers yet. Add one — an OpenAI-compatible endpoint " +
                            "(OpenAI, OpenRouter, Groq, Ollama…), Anthropic, or Gemini — " +
                            "with its API key and a model name.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
        }
    }

    if (showDialog) {
        ProviderSheet(
            existing = editing,
            existingKey = editing?.let { container.providers.apiKey(it.id) },
            onDismiss = { showDialog = false },
            onSave = { name, type, baseUrl, model, apiKey ->
                scope.launch {
                    val existingConfig = editing
                    if (existingConfig == null) {
                        val created = container.providers.add(name, type, baseUrl, model, apiKey)
                        if (settings.activeProviderId == null) {
                            container.settings.setActiveProvider(created.id)
                        }
                    } else {
                        container.providers.update(
                            existingConfig.copy(
                                name = name,
                                type = type,
                                baseUrl = baseUrl,
                                model = model,
                            ),
                            apiKey,
                        )
                    }
                    showDialog = false
                }
            },
        )
    }

    if (showModelsFor != null) {
        ModelPickerSheet(
            providers = providers,
            activeProviderId = settings.activeProviderId,
            activeModel = settings.activeModel,
            catalogs = catalogs,
            thinkingLevel = settings.thinkingLevel,
            browseProviderId = showModelsFor,
            onDismiss = { showModelsFor = null },
            onSelect = { providerId, model ->
                scope.launch {
                    container.settings.setActiveProvider(providerId)
                    container.settings.setActiveModel(model)
                    val provider = providers.firstOrNull { it.id == providerId }
                    if (provider != null) {
                        com.androidharness.app.agent.ThinkingSpecs.clampStoredLevel(
                            container.settings,
                            model?.takeIf { it.isNotBlank() } ?: provider.model,
                            com.androidharness.app.llm.ModelsDev.providerKeyFor(provider.baseUrl),
                        )
                    }
                }
            },
            onRefreshCatalog = { providerId ->
                val provider = providers.firstOrNull { it.id == providerId }
                when {
                    provider == null -> "Unknown provider"
                    else -> when (
                        val result = com.androidharness.app.llm.ModelCatalog.listModels(
                            provider, container.providers.apiKey(providerId).orEmpty(),
                        )
                    ) {
                        is com.androidharness.app.llm.ModelCatalog.Result.Models -> {
                            container.providers.saveCatalog(providerId, result.models)
                            null
                        }
                        is com.androidharness.app.llm.ModelCatalog.Result.Failed -> result.message
                    }
                }
            },
            onSetThinking = { level ->
                scope.launch { container.settings.setThinkingLevel(level) }
            },
            onManageProviders = { /* already here */ },
        )
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderConfig,
    active: Boolean,
    catalogSize: Int?,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBrowseModels: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onSetActive,
        color = scheme.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(
            1.dp,
            if (active) scheme.primary.copy(alpha = 0.5f) else scheme.outlineVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(provider.name, style = MaterialTheme.typography.titleSmallEmphasized)
                Text(
                    "${provider.type.label} · ${provider.model}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    provider.baseUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (active) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Active",
                    tint = scheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = onBrowseModels) {
                Icon(
                    Icons.Outlined.Layers,
                    contentDescription = "Browse models",
                    tint = scheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = scheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = scheme.error)
            }
        }
    }
}

