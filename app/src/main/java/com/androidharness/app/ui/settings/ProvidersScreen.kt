package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.androidharness.app.llm.ProviderModelSearch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.common.ProviderMark
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.TextButton

@Composable
fun ProvidersScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    // The provider list is where API keys are entered and revealed.
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val catalogs by container.providers.catalogs.collectAsStateWithLifecycle(initialValue = emptyMap())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showModelsFor by remember { mutableStateOf<String?>(null) }
    val scheme = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var showDisabled by rememberSaveable { mutableStateOf(false) }
    var controlsFor by remember { mutableStateOf<ProviderConfig?>(null) }
    var showControls by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var showAllModels by remember { mutableStateOf(false) }
    val visibility by com.androidharness.app.data.ModelVisibility.get(LocalContext.current).state.collectAsStateWithLifecycle()
    val selection = rememberProviderSelection()
    val visibleProviders = providers.filter { (it.enabled || showDisabled || query.isNotBlank() || selection.selecting && it.id in selection.ids) &&
        ProviderModelSearch.providerMatches(it, catalogs[it.id].orEmpty(), query) }
    val visibleIds = visibleProviders.map { it.id }

    Scaffold(
        containerColor = scheme.surface,
        topBar = {
            AppHeader(
                title = stringResource(R.string.settings_providers_title),
                subtitle = stringResource(R.string.settings_providers_intro),
                onBack = onBack,
            )
        },
        bottomBar = {
            if (selection.selecting || selection.deleting != null || selection.error != null) Surface {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    ProviderBulkActions(selection, providers, container.providers::setEnabled, container.providers::delete)
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.fillMaxHeight().widthIn(max = 960.dp).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { controlsFor = null; showControls = true }) { Text(stringResource(R.string.provider_global_proxy_action)) }
                        TextButton(enabled = !selection.busy, onClick = {
                            if (selection.selecting) selection.done() else selection.selecting = true
                        }) { Text(if (selection.selecting) uiContext.getString(R.string.settings_action_done) else uiContext.getString(R.string.ui_select_providers)) }
                    }
                    FilledTonalButton(
                        onClick = {
                            editing = null
                            showDialog = true
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp, bottom = 4.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_providers_add))
                    }
                }
                item {
                    OutlinedTextField(value = query, onValueChange = { query = it },
                        label = { Text(stringResource(R.string.settings_providers_search_placeholder)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = showDisabled, onClick = { showDisabled = !showDisabled }, label = { Text(stringResource(R.string.settings_providers_show_disabled)) })
                        TextButton(onClick = { showAllModels = true }) { Text(stringResource(R.string.settings_providers_search_all_models)) }
                    }
                    Text(stringResource(R.string.settings_providers_disabled_note),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                items(visibleProviders, key = { it.id }) { provider ->
                    ProviderListCard(
                        provider = provider,
                        active = provider.id == settings.activeProviderId,
                        canMoveUp = visibleIds.indexOf(provider.id) > 0,
                        canMoveDown = visibleIds.indexOf(provider.id) < visibleIds.lastIndex,
                        onEnabledChange = { enabled -> scope.launch { container.providers.setEnabled(provider.id, enabled) } },
                        onMove = { direction -> scope.launch { container.providers.move(provider.id, direction, visibleIds) } },
                        onSelect = {
                            scope.launch {
                                container.providers.setEnabled(provider.id, true)
                                val selectedModel = settings.activeModel.takeIf { model ->
                                    catalogs[provider.id].orEmpty().any { it.id == model }
                                }
                                container.settings.setActiveSelection(provider.id, selectedModel)
                                // Adapt the stored thinking tier to the new model.
                                com.androidharness.app.agent.ThinkingSpecs.clampStoredLevel(
                                    container.settings,
                                    selectedModel ?: provider.model,
                                    com.androidharness.app.llm.ModelsDev.providerKeyFor(provider.baseUrl),
                                )
                            }
                        },
                        onControls = if (com.androidharness.app.local.LocalModelCatalog.isLocal(provider.id)) null else {
                            { controlsFor = provider; showControls = true }
                        },
                        onEdit = {
                            editing = provider
                            showDialog = true
                        },
                        onDelete = {
                            selection.requestDelete(listOf(provider.id))
                        },
                        onBrowseModels = { showModelsFor = provider.id },
                        catalogSize = ProviderModelSearch.models(provider, catalogs[provider.id].orEmpty()).size,
                        selectedModelCount = ProviderModelSearch.models(provider, catalogs[provider.id].orEmpty())
                            .count { visibility.includes(provider.id, it.id) },
                        selectionMode = selection.selecting,
                        selected = provider.id in selection.ids,
                        onToggleSelection = { if (!selection.busy) selection.toggle(provider.id) },
                        onLongPress = { if (!selection.busy) selection.start(provider.id) },
                    )
                }
                if (visibleProviders.isEmpty()) {
                    item {
                        Surface(
                            color = scheme.surfaceContainerLow,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                            ) {
                                ProviderMark(size = 48.dp)
                                Text(
                                    if (providers.isEmpty()) uiContext.getString(R.string.ui_no_providers_yet) else uiContext.getString(R.string.ui_no_matching_providers_1067),
                                    style = MaterialTheme.typography.titleSmallEmphasized,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                                Text(
                                    if (providers.isEmpty()) uiContext.getString(R.string.ui_add_a_provider_then_choose_its_model) else uiContext.getString(R.string.ui_try_a_different_search_or_show_disabled),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showControls) ProviderControlsSheet(container, controlsFor) { showControls = false; controlsFor = null }

    if (showDialog) {
        ProviderSheet(
            existing = editing,
            apiKeys = container.providers::apiKeys,
            onDismiss = { showDialog = false },
            onSave = { name, type, baseUrl, model, apiKey, headers ->
                scope.launch {
                    try {
                        val existingConfig = editing
                        if (existingConfig == null) {
                            val created = container.providers.add(name, type, baseUrl, model, apiKey, headers)
                            // Adding a provider switches to it on its own default
                            // model; a stale model override must not survive.
                            container.settings.setActiveSelection(created.id, null)
                        } else {
                            container.providers.update(
                                existingConfig.copy(
                                    name = name,
                                    type = type,
                                    baseUrl = baseUrl,
                                    model = model,
                                    headers = headers,
                                ),
                                apiKey,
                            )
                        }
                        showDialog = false
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { saveFailed = true }
                }
            },
        )
    }

    if (saveFailed) AlertDialog(
        onDismissRequest = { saveFailed = false },
        text = { Text(stringResource(R.string.provider_save_failed)) },
        confirmButton = { TextButton(onClick = { saveFailed = false }) { Text(stringResource(android.R.string.ok)) } },
    )

    if (showModelsFor != null || showAllModels) {
        ModelPickerSheet(
            providers = providers,
            activeProviderId = settings.activeProviderId,
            activeModel = settings.activeModel,
            catalogs = catalogs,
            browseProviderId = showModelsFor,
            manageModelsInitially = showModelsFor != null,
            searchAllProvidersInitially = showAllModels,
            onEnableProvider = { id -> scope.launch { container.providers.setEnabled(id, true) } },
            onDismiss = { showModelsFor = null; showAllModels = false },
            onSelect = { providerId, model ->
                scope.launch {
                    container.settings.setActiveSelection(providerId, model)
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
                    provider == null -> uiContext.getString(R.string.ui_unknown_provider)
                    else -> when (
                        val result = com.androidharness.app.llm.ModelCatalog.listModels(
                            provider,
                            provider.effectiveApiKey(container.providers.apiKey(providerId)),
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
            onAddCustomModel = { providerId, model, reasoning ->
                scope.launch { container.providers.addCustomModel(providerId, model, reasoning) }
            },
            onDeleteCustomModel = { providerId, model ->
                scope.launch { container.providers.removeCustomModel(providerId, model) }
            },
            onManageProviders = { /* already here */ },
        )
    }
}
