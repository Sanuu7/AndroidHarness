package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassModalBottomSheet as ModalBottomSheet

import androidx.compose.animation.Crossfade
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.androidharness.app.llm.ProviderModelSearch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.data.ModelVisibility
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ModelsDev
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.ui.theme.fastEffectsSpec
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

/**
 * Provider management without leaving the conversation: a fully-expanded
 * bottom sheet with a provider list, shared add/edit form and model visibility editor.
 * Long-press selection and deletion review match the full Providers screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderManagerSheet(
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    apiKeys: (providerId: String) -> com.androidharness.app.llm.ProviderKeyPool,
    onDismiss: () -> Unit,
    onSetActive: (String) -> Unit,
    onDelete: suspend (String) -> Unit,
    onEnabledChange: suspend (String, Boolean) -> Unit,
    onMove: (String, Int, List<String>) -> Unit,
    onSave: (
        existing: ProviderConfig?,
        name: String,
        type: ProviderType,
        baseUrl: String,
        model: String,
        apiKeys: com.androidharness.app.llm.ProviderKeyPool,
        headers: List<com.androidharness.app.llm.ProviderHeader>,
    ) -> Unit,
    catalogs: Map<String, List<ModelEntry>> = emptyMap(),
    onRefreshCatalog: suspend (String) -> String? = { "Open model selection to refresh this provider." },
    addProviderInitially: Boolean = false,
    onControls: ((ProviderConfig) -> Unit)? = null,
    onGlobalProxy: (() -> Unit)? = null,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var showForm by rememberSaveable { mutableStateOf(addProviderInitially) }
    var query by rememberSaveable { mutableStateOf("") }
    var showDisabled by rememberSaveable { mutableStateOf(false) }
    val visibility by ModelVisibility.get(LocalContext.current).state.collectAsStateWithLifecycle()
    val selection = rememberProviderSelection()
    var manageModelsFor by rememberSaveable { mutableStateOf<String?>(null) }
    val visible = providers.filter { (it.enabled || showDisabled || query.isNotBlank() || selection.selecting && it.id in selection.ids) &&
        ProviderModelSearch.providerMatches(it, catalogs[it.id].orEmpty(), query) }
    val visibleIds = visible.map { it.id }

    if (manageModelsFor != null) {
        ModelVisibilitySheet(providers, catalogs, manageModelsFor, onDismiss = { manageModelsFor = null }, onRefreshCatalog = onRefreshCatalog)
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        Crossfade(
            targetState = showForm,
            animationSpec = fastEffectsSpec(),
            label = stringResource(R.string.settings_providers_page_cd),
        ) { inForm ->
            if (!inForm) {
                Column(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * .9f).dp).navigationBarsPadding()) {
                    LazyColumn(
                        Modifier.weight(1f).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                        onGlobalProxy?.let { open -> TextButton(onClick = open) { Text(stringResource(R.string.provider_global_proxy_action)) } }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.settings_providers_title), style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                                TextButton(enabled = !selection.busy, onClick = {
                                    if (selection.selecting) selection.done() else selection.selecting = true
                                }) { Text(if (selection.selecting) uiContext.getString(R.string.settings_action_done) else uiContext.getString(R.string.ui_select_providers)) }
                                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_action_close)) }
                            }
                            Text(stringResource(R.string.settings_providers_disabled_note),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FilledTonalButton(onClick = { editing = null; showForm = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.settings_providers_add))
                            }
                            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.settings_providers_search)) },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            FilterChip(selected = showDisabled, onClick = { showDisabled = !showDisabled }, label = { Text(stringResource(R.string.settings_providers_show_disabled)) })
                        }
                        itemsIndexed(visible, key = { _, p -> p.id }) { index, provider ->
                            ProviderListCard(provider, provider.id == activeProviderId, index > 0, index < visible.lastIndex,
                                onSelect = {
                                    scope.launch {
                                        if (!provider.enabled) onEnabledChange(provider.id, true)
                                        onSetActive(provider.id)
                                        onDismiss()
                                    }
                                },
                                onEnabledChange = { enabled -> scope.launch { onEnabledChange(provider.id, enabled) } },
                                onMove = { onMove(provider.id, it, visibleIds) },
                                onEdit = { editing = provider; showForm = true },
                                onControls = if (com.androidharness.app.local.LocalModelCatalog.isLocal(provider.id)) null else onControls?.let { controls -> { controls(provider) } },
                                onDelete = { selection.requestDelete(listOf(provider.id)) },
                                onBrowseModels = { manageModelsFor = provider.id },
                                catalogSize = ProviderModelSearch.models(provider, catalogs[provider.id].orEmpty()).size,
                                selectedModelCount = ProviderModelSearch.models(provider, catalogs[provider.id].orEmpty())
                                    .count { visibility.includes(provider.id, it.id) },
                                selectionMode = selection.selecting, selected = provider.id in selection.ids,
                                onToggleSelection = { if (!selection.busy) selection.toggle(provider.id) },
                                onLongPress = { if (!selection.busy) selection.start(provider.id) })
                        }
                        if (visible.isEmpty()) item {
                            Text(stringResource(R.string.settings_providers_search_empty),
                                modifier = Modifier.padding(vertical = 20.dp))
                        }
                        item {
                            val context = LocalContext.current
                            var catalogBusy by remember { mutableStateOf(false) }
                            var catalogStatus by remember { mutableStateOf<String?>(null) }
                            Column {
                                TextButton(onClick = {
                                    scope.launch {
                                        catalogBusy = true
                                        try {
                                            catalogStatus = ModelsDev.refresh(context, force = true)
                                                ?: uiContext.getString(R.string.ui_catalog_updated)
                                        } finally { catalogBusy = false }
                                    }
                                }, enabled = !catalogBusy) { Text(if (catalogBusy) uiContext.getString(R.string.ui_updating_catalog) else uiContext.getString(R.string.ui_update_model_catalog)) }
                                catalogStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        ProviderBulkActions(selection, providers,
                            onEnabledChange = { id, enabled -> onEnabledChange(id, enabled) }, onDelete = onDelete)
                    }
                }
            } else {
                // The form owns its step headers + back navigation; closing at
                // step one returns to the list page.
                ProviderSheetContent(
                    existing = editing,
                    apiKeys = apiKeys,
                    onDismiss = { showForm = false },
                    onSave = { name, type, baseUrl, model, key, headers ->
                        onSave(editing, name, type, baseUrl, model, key, headers)
                        showForm = false
                        editing = null
                    },
                )
            }
        }
    }
}
