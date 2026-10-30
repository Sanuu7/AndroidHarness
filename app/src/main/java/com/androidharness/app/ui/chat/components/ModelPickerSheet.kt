package com.androidharness.app.ui.chat.components

import com.androidharness.app.ui.theme.*
import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface
import com.androidharness.app.ui.theme.HarnessGlassAlertDialog as AlertDialog
import com.androidharness.app.ui.theme.HarnessGlassModalBottomSheet as ModalBottomSheet
import androidx.compose.ui.graphics.Color

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.ui.common.CatalogLoadingIndicator
import com.androidharness.app.ui.common.ExpressiveIconButton
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.chatgpt.ChatGptProtocol
import com.androidharness.app.data.ModelShortcut
import com.androidharness.app.data.ModelShortcuts
import com.androidharness.app.data.ModelVisibility
import com.androidharness.app.llm.*
import com.androidharness.app.ui.settings.ModelVisibilitySheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.RadioButton
import com.androidharness.app.ui.common.TextButton

/** Unified discovery with a separate provider chooser; browsing never changes the active model. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelPickerSheet(
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    activeModel: String?,
    catalogs: Map<String, List<ModelEntry>>,
    onDismiss: () -> Unit,
    onSelect: (providerId: String, model: String?) -> Unit,
    onRefreshCatalog: suspend (providerId: String) -> String?,
    onManageProviders: () -> Unit,
    browseProviderId: String? = null,
    onAddCustomModel: (providerId: String, model: String, reasoning: Boolean?) -> Unit = { _, _, _ -> },
    onDeleteCustomModel: (providerId: String, model: String) -> Unit = { _, _ -> },
    sameModelAcrossChats: Boolean? = null,
    onModelScopeChange: (Boolean) -> Unit = {},
    modelScopeEnabled: Boolean = true,
    modelShortcuts: ModelShortcuts? = null,
    searchAllProvidersInitially: Boolean = false,
    onEnableProvider: (String) -> Unit = {},
    fallbackSummary: String? = null,
    fallbackEditEnabled: Boolean = true,
    onEditFallbacks: () -> Unit = {},
    modelVisibility: ModelVisibility? = null,
    manageModelsInitially: Boolean = false,
    onAddProvider: (() -> Unit)? = null,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val compactHeight = LocalConfiguration.current.screenHeightDp < 600 * LocalDensity.current.fontScale
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val shortcuts = remember(context, modelShortcuts) { modelShortcuts ?: ModelShortcuts.get(context) }
    val visibility = remember(context, modelVisibility) { modelVisibility ?: ModelVisibility.get(context) }
    val saved by shortcuts.state.collectAsStateWithLifecycle()
    val visible by visibility.state.collectAsStateWithLifecycle()
    val directoryRevision by ModelsDev.revision.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var providerQuery by rememberSaveable { mutableStateOf("") }
    var listedId by rememberSaveable {
        mutableStateOf(browseProviderId ?: activeProviderId.takeUnless { searchAllProvidersInitially })
    }
    var showAll by rememberSaveable { mutableStateOf(searchAllProvidersInitially) }
    var collection by rememberSaveable { mutableStateOf("All") }
    var thinkOnly by rememberSaveable { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(manageModelsInitially) }
    var showAddCustom by rememberSaveable { mutableStateOf(false) }
    var choosingProvider by rememberSaveable { mutableStateOf(false) }
    var showScope by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var pendingProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingModel by rememberSaveable { mutableStateOf<String?>(null) }
    var enableChoice by remember { mutableStateOf<ModelShortcut?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    val initialShared = remember { sameModelAcrossChats }
    val activeProvider = providers.firstOrNull { it.id == activeProviderId }
    val effective = activeModel?.takeIf(String::isNotBlank) ?: activeProvider?.model
    val listedProvider = providers.firstOrNull { it.id == listedId }
    val chosenProviderId = pendingProvider ?: activeProviderId
    val chosenModelId = pendingModel ?: effective
    val chosenProvider = providers.firstOrNull { it.id == chosenProviderId }
    val chosenEntry = chosenProvider?.let { p -> ProviderModelSearch.models(p, catalogs[p.id].orEmpty()).firstOrNull { it.id == chosenModelId } }
    val searching = query.isNotBlank()
    val scopedProviders = if (listedId == null) providers else providers.filter { it.id == listedId }
    val shortcutProviderIds = scopedProviders.mapTo(mutableSetOf()) { it.id }
    fun hasShortcuts(keys: List<ModelShortcut>) = keys.any {
        it.providerId in shortcutProviderIds && (showAll || visible.includes(it.providerId, it.modelId))
    }
    val collections = buildList {
        add("All" to uiContext.getString(R.string.ui_all_models))
        if (hasShortcuts(saved.recent)) add("Recent" to uiContext.getString(R.string.ui_recent))
        if (hasShortcuts(saved.favourites)) add("Favourites" to uiContext.getString(R.string.ui_favourites))
    }
    val activeCollection = collection.takeIf { key -> collections.any { it.first == key } } ?: "All"
    LaunchedEffect(activeCollection) { collection = activeCollection }
    val rows = ProviderModelSearch.results(
        scopedProviders, catalogs, query, if (searching) "All" else activeCollection,
        saved.favourites, saved.recent,
        includeDisabled = showAll || searching || listedProvider?.enabled == false || activeCollection != "All",
        visibility = if (showAll || searching) null else visible,
    ).filter { !thinkOnly || ModelCapabilities.of(it.model, it.provider).thinking.supported == true }.let { results ->
        if (ChatGptProtocol.isProvider(listedId) && (searching || activeCollection == "All")) results.sortedWith { a, b -> newestChatGptFirst.compare(a.model, b.model) }
        else results
    }
    LaunchedEffect(providers.map { it.id }, listedId) {
        if (listedId != null && providers.none { it.id == listedId }) listedId = null
    }
    fun commit(providerId: String, model: String) {
        if (!modelScopeEnabled) return
        onSelect(providerId, model)
        shortcuts.selected(ModelShortcut(providerId, model))
        onDismiss()
    }
    suspend fun refresh(ids: List<String>) {
        if (isRefreshing || ids.isEmpty()) return
        isRefreshing = true; refreshError = null
        try {
            val limit = Semaphore(3)
            val failures = supervisorScope {
                ids.map { id -> async { limit.withPermit {
                    val error = try { onRefreshCatalog(id) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { uiContext.getString(R.string.ui_could_not_refresh) }
                    error?.let { "${providers.firstOrNull { it.id == id }?.name ?: "Provider"}: $it" }
                } } }.awaitAll().filterNotNull()
            }
            refreshError = failures.firstOrNull()?.let { if (failures.size > 1) uiContext.getString(R.string.ui_providers_could_not_refresh, (failures.size).toString(), (it).toString()) else it }
        } finally { isRefreshing = false }
    }
    LaunchedEffect(listedId, providers.map { it.id }, managing) {
        if (!managing) refresh(scopedProviders.filter { it.enabled && (catalogs[it.id].isNullOrEmpty() ||
            ChatGptProtocol.isProvider(it.id) || it.id == HarnessProvider.ID) }.map { it.id })
    }
    if (managing) {
        ModelVisibilitySheet(providers, catalogs, listedId,
            onDismiss = { if (manageModelsInitially) onDismiss() else managing = false },
            visibility = visibility, onRefreshCatalog = onRefreshCatalog)
        return
    }
    @Composable fun SearchField() {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
        OutlinedTextField(value = if (choosingProvider) providerQuery else query,
                onValueChange = { if (choosingProvider) providerQuery = it else query = it }, singleLine = true,
                placeholder = { Text(if (choosingProvider) uiContext.getString(R.string.settings_providers_search) else uiContext.getString(R.string.ui_search_models_or_providers),
                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(19.dp)) },
                trailingIcon = { if ((if (choosingProvider) providerQuery else query).isNotEmpty()) IconButton(onClick = {
                    if (choosingProvider) providerQuery = "" else query = ""
                }) { Icon(Icons.Filled.Close, uiContext.getString(R.string.settings_search_clear), Modifier.size(18.dp)) } },
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }
    val glass = LocalHarnessGlass.current.mode != HarnessGlassMode.OFF
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = if (glass) sheetShape else BottomSheetDefaults.ExpandedShape,
        dragHandle = if (compactHeight) null else ({ BottomSheetDefaults.DragHandle() }),
        containerColor = if (glass) Color.Transparent else scheme.surface, contentColor = scheme.onSurface) {
        Column(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * if (compactHeight) .96f else .86f).dp)
            .navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (choosingProvider) IconButton(onClick = { choosingProvider = false }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, uiContext.getString(R.string.ui_back_to_models), Modifier.size(20.dp))
                }
                Text(if (choosingProvider) uiContext.getString(R.string.ui_switch_provider) else uiContext.getString(R.string.ui_choose_a_model), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                sameModelAcrossChats?.let { shared ->
                    TextButton(onClick = { showScope = true }, enabled = modelScopeEnabled) {
                        Text(if (shared) uiContext.getString(R.string.ui_all_chats) else uiContext.getString(R.string.ui_this_chat), style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
                    }
                }
                if (compactHeight && !choosingProvider) IconButton(onClick = { details = true }, enabled = chosenEntry != null) { Icon(Icons.Outlined.Info, uiContext.getString(R.string.ui_selected_model_details), Modifier.size(19.dp)) }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, uiContext.getString(R.string.settings_action_close), Modifier.size(20.dp)) }
            }
            if (choosingProvider) {
                LazyColumn(Modifier.weight(1f)) {
                    item { SearchField() }
                    item { ProviderChoiceRow(uiContext.getString(R.string.settings_provider_all_providers), uiContext.getString(R.string.ui_search_across_your_providers), listedId == null,
                        onClick = { focusManager.clearFocus(); listedId = null; query = ""; collection = "All"; choosingProvider = false }) }
                    val matches = providers.filter { ProviderModelSearch.providerMatches(it, catalogs[it.id].orEmpty(), providerQuery) }
                    items(matches, key = { it.id }) { provider ->
                        val count = ProviderModelSearch.models(provider, catalogs[provider.id].orEmpty()).size
                        ProviderChoiceRow(provider.name, uiContext.resources.getQuantityString(R.plurals.ui_model_count, count, count) + " · " + if (provider.enabled) uiContext.getString(R.string.settings_headers_enabled) else uiContext.getString(R.string.settings_provider_disabled),
                            listedId == provider.id, onClick = {
                                focusManager.clearFocus(); listedId = provider.id; query = ""; collection = "All"; choosingProvider = false
                            })
                    }
                    if (matches.isEmpty()) item { Text(uiContext.getString(R.string.ui_no_matching_providers), Modifier.padding(vertical = 24.dp), color = scheme.onSurfaceVariant) }
                }
                HorizontalDivider(color = scheme.outlineVariant)
                if (compactHeight) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { onDismiss(); (onAddProvider ?: onManageProviders)() },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(uiContext.getString(R.string.settings_providers_add))
                    }
                    TextButton(onClick = { onDismiss(); onManageProviders() }, Modifier.weight(1f)) { Text(uiContext.getString(R.string.settings_search_manage_providers_title)) }
                } else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = { onDismiss(); (onAddProvider ?: onManageProviders)() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(uiContext.getString(R.string.settings_providers_add))
                    }
                    TextButton(onClick = { onDismiss(); onManageProviders() }, Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.settings_search_manage_providers_title)) }
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        SearchField()
                        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                            itemVerticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { focusManager.clearFocus(); choosingProvider = true }, shape = RoundedCornerShape(10.dp)) {
                                Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(uiContext.getString(R.string.ui_switch_provider))
                            }
                            Text(listedProvider?.name ?: uiContext.getString(R.string.settings_provider_all_providers), style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            collections.forEach { (key, label) ->
                                val selected = if (searching) key == "All" else activeCollection == key
                                val collectionColor by animateColorAsState(
                                    if (selected) scheme.secondaryContainer else scheme.surface,
                                    animationSpec = expressiveEffectsSpec(), label = "model collection",
                                )
                                Surface(onClick = { collection = key; query = "" }, shape = RoundedCornerShape(9.dp),
                                    color = collectionColor,
                                    modifier = Modifier.semantics { this.selected = selected; role = Role.Tab }) {
                                    Box(Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                                        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(uiContext.resources.getQuantityString(R.plurals.ui_model_count, rows.size, rows.size) + if (showAll) uiContext.getString(R.string.ui_show_all) else "",
                                Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            TextButton(onClick = { managing = true }) { Text(uiContext.getString(R.string.ui_edit_list), style = MaterialTheme.typography.labelMedium) }
                            ExpressiveIconButton(onClick = { scope.launch { refresh(scopedProviders.filter { it.enabled || listedId == it.id }.map { it.id }) } }, enabled = !isRefreshing) {
                                if (isRefreshing) CatalogLoadingIndicator(Modifier.size(20.dp))
                                else Icon(Icons.Outlined.Refresh, uiContext.getString(R.string.settings_chatgpt_refresh_models), Modifier.size(18.dp))
                            }
                        }
                        refreshError?.let { Text(uiContext.getString(R.string.ui_couldn_t_refresh_saved_models_remain_available, (it).toString()), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall, color = scheme.error) }
                    }
                    if (rows.isEmpty()) item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(when {
                                isRefreshing -> uiContext.getString(R.string.ui_loading_provider_catalogs)
                                providers.isEmpty() -> uiContext.getString(R.string.ui_add_a_provider_to_choose_a_model)
                                collection == "Favourites" -> uiContext.getString(R.string.ui_star_a_model_to_keep_it_here)
                                collection == "Recent" -> uiContext.getString(R.string.ui_models_you_use_will_appear_here)
                                else -> uiContext.getString(R.string.ui_no_models_match_try_another_search_or)
                            }, color = scheme.onSurfaceVariant)
                            TextButton(onClick = { collection = "All"; query = ""; showAll = true }) { Text(uiContext.getString(R.string.ui_show_all_models)) }
                            if (providers.isEmpty()) TextButton(onClick = { onDismiss(); (onAddProvider ?: onManageProviders)() }) { Text(uiContext.getString(R.string.settings_providers_add)) }
                        }
                    }
                    items(rows, key = { "${it.provider.id.length}:${it.provider.id}:${it.model.id}" }) { result ->
                        val entry = result.model; val owner = result.provider
                        val capabilities = remember(entry, owner, directoryRevision) { ModelCapabilities.of(entry, owner) }
                        Box(Modifier.padding(horizontal = 12.dp)) { ModelRow(entry, owner, owner.id == chosenProviderId && entry.id == chosenModelId,
                            !visible.includes(owner.id, entry.id),
                            ctxLabel(entry.contextTokens ?: ModelsDev.entry(ModelsDev.providerKeyFor(owner.baseUrl), entry.id)?.contextTokens),
                            if (!result.available) uiContext.getString(R.string.ui_unavailable_refresh_or_choose_another_model) else entry.note,
                            modelScopeEnabled && result.available, result.shortcut in saved.favourites,
                            onFavourite = { shortcuts.toggle(result.shortcut) },
                            onClick = { focusManager.clearFocus(); pendingProvider = owner.id; pendingModel = entry.id },
                            capabilities = capabilities, current = owner.id == activeProviderId && entry.id == effective,
                            onDelete = if (entry.custom) ({ onDeleteCustomModel(owner.id, entry.id) }) else null) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = thinkOnly, onClick = { thinkOnly = !thinkOnly }, label = { Text(uiContext.getString(R.string.ui_thinking_only)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_brain), null, Modifier.size(15.dp)) })
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreHoriz, uiContext.getString(R.string.ui_model_options)) }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text(if (showAll) uiContext.getString(R.string.ui_use_customized_list) else uiContext.getString(R.string.ui_show_all_213)) }, onClick = { showAll = !showAll; more = false })
                            DropdownMenuItem(text = { Text(uiContext.getString(R.string.settings_provider_manage_models)) }, onClick = { more = false; managing = true })
                            if (listedProvider != null && !ChatGptProtocol.isProvider(listedId) && !com.androidharness.app.local.LocalModelCatalog.isLocal(listedProvider.id))
                                DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_custom_model)) }, onClick = { more = false; showAddCustom = true })
                            if (collection == "Recent") DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_clear_recent)) }, onClick = {
                                if (listedId != null) shortcuts.clearRecent(listedId!!) else providers.forEach { shortcuts.clearRecent(it.id) }; more = false
                            })
                            fallbackSummary?.let { summary -> DropdownMenuItem(text = { Column { Text(uiContext.getString(R.string.settings_search_fallbacks_title)); Text(summary, style = MaterialTheme.typography.bodySmall) } },
                                enabled = modelScopeEnabled && fallbackEditEnabled, onClick = { more = false; onEditFallbacks() }) }
                        }
                    }
                }
                if (!compactHeight && ChatGptProtocol.isProvider(chosenProviderId)) com.androidharness.app.ui.common.ChatGptUsageLimits(compact = true)
                HorizontalDivider(color = scheme.outlineVariant)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (!compactHeight) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(when (sameModelAcrossChats) { true -> uiContext.getString(R.string.ui_for_all_chats); false -> uiContext.getString(R.string.ui_for_this_chat); null -> uiContext.getString(R.string.ui_for_this_selection) }, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            Text(chosenEntry?.displayName ?: chosenModelId ?: uiContext.getString(R.string.ui_choose_a_model), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            chosenProvider?.let { Text(it.name, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
                        }
                        IconButton(onClick = { details = true }, enabled = chosenEntry != null) { Icon(Icons.Outlined.Info, uiContext.getString(R.string.ui_selected_model_details), Modifier.size(19.dp)) }
                    }
                    val unchanged = pendingModel == null && initialShared == sameModelAcrossChats && chosenEntry != null
                    Button(onClick = {
                        if (chosenProvider != null && chosenModelId != null) {
                            if (chosenProvider.enabled) commit(chosenProvider.id, chosenModelId)
                            else enableChoice = ModelShortcut(chosenProvider.id, chosenModelId)
                        }
                    }, enabled = modelScopeEnabled && chosenEntry != null && !unchanged,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                        Text(when { unchanged -> uiContext.getString(R.string.ui_current_model); chosenProvider?.enabled == false -> uiContext.getString(R.string.ui_enable_and_use); sameModelAcrossChats == true -> uiContext.getString(R.string.ui_use_in_all_chats); else -> uiContext.getString(R.string.ui_use_model) })
                        Spacer(Modifier.width(8.dp)); Icon(if (unchanged) Icons.Filled.Check else Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    enableChoice?.let { choice ->
        val provider = providers.firstOrNull { it.id == choice.providerId }
        AlertDialog(onDismissRequest = { enableChoice = null }, title = { Text(uiContext.getString(R.string.ui_enable, (provider?.name ?: "provider").toString())) },
            text = { Text(uiContext.getString(R.string.ui_enable_this_provider_and_use, (choice.modelId).toString(), (if (sameModelAcrossChats == true) " in all chats" else " for this selection").toString())) },
            confirmButton = { TextButton(enabled = provider != null && modelScopeEnabled, onClick = { onEnableProvider(choice.providerId); commit(choice.providerId, choice.modelId) }) { Text(uiContext.getString(R.string.ui_enable_and_use)) } },
            dismissButton = { TextButton(onClick = { enableChoice = null }) { Text(uiContext.getString(R.string.settings_action_cancel)) } })
    }
    if (showScope && sameModelAcrossChats != null) AlertDialog(onDismissRequest = { showScope = false }, title = { Text(uiContext.getString(R.string.ui_use_model_for)) },
        text = { Column { listOf(false to uiContext.getString(R.string.ui_this_chat), true to uiContext.getString(R.string.ui_all_chats)).forEach { (shared, label) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(sameModelAcrossChats == shared, enabled = modelScopeEnabled, role = Role.RadioButton,
                onClick = { onModelScopeChange(shared); showScope = false }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(sameModelAcrossChats == shared, null); Spacer(Modifier.width(8.dp)); Text(label)
            }
        } } }, confirmButton = { TextButton(onClick = { showScope = false }) { Text(uiContext.getString(R.string.settings_action_close)) } })
    if (details && chosenEntry != null && chosenProvider != null) ModelDetailsDialog(chosenEntry, chosenProvider, onDismiss = { details = false })
    if (showAddCustom && listedProvider != null) AddCustomModelDialog(listedProvider.name,
        onDismiss = { showAddCustom = false }, onAdd = { model, reasoning ->
            onAddCustomModel(listedProvider.id, model, reasoning)
            if (visible.selected.containsKey(listedProvider.id)) visibility.save(listedProvider.id, visible.selected.getValue(listedProvider.id) + model)
            commit(listedProvider.id, model)
        })
}

@Composable
private fun ProviderChoiceRow(name: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val glass = LocalHarnessGlass.current.mode != HarnessGlassMode.OFF
    Surface(modifier = Modifier.fillMaxWidth().then(if (glass) Modifier.padding(horizontal = 12.dp, vertical = 3.dp) else Modifier),
        shape = RoundedCornerShape(if (glass) 20.dp else 0.dp),
        color = if (glass && selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ModelProviderMark(name, 30.dp)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Filled.Check, uiContext.getString(R.string.ui_selected_provider), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
    }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ModelRow(
    entry: ModelEntry, provider: ProviderConfig, selected: Boolean, hidden: Boolean, context: String?,
    note: String?, enabled: Boolean, favourite: Boolean, onFavourite: () -> Unit, onClick: () -> Unit,
    capabilities: ModelCapabilities, current: Boolean, onDelete: (() -> Unit)?,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        if (selected) scheme.secondaryContainer else scheme.surface,
        animationSpec = expressiveEffectsSpec(), label = "model selection",
    )
    Surface(color = containerColor,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
        shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, if (selected) scheme.primary.copy(alpha = .65f) else scheme.surface),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 72.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = uiContext.getString(R.string.ui_choose_from, (entry.displayName ?: entry.id).toString(), (provider.name).toString()) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                ModelProviderMark(provider.name, 28.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(entry.displayName ?: entry.id, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(provider.name, if (current) uiContext.getString(R.string.ui_current) else null, if (hidden) uiContext.getString(R.string.ui_hidden) else null, if (entry.custom) uiContext.getString(R.string.ui_custom) else null, context).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    if (!provider.enabled) Text(uiContext.getString(R.string.ui_disabled_enable_and_use), style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                    ModelCapabilityLabels(capabilities)
                    note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
                }
                Box(Modifier.size(17.dp), contentAlignment = Alignment.Center) {
                    if (selected) Icon(Icons.Outlined.CheckCircle, uiContext.getString(R.string.settings_selected), Modifier.size(17.dp), tint = scheme.onSecondaryContainer)
                }
            }
            ExpressiveIconButton(onClick = onFavourite) { Icon(if (favourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                if (favourite) uiContext.getString(R.string.ui_remove_from_favourites, (entry.displayName ?: entry.id).toString()) else uiContext.getString(R.string.ui_favourite, (entry.displayName ?: entry.id).toString()),
                Modifier.size(18.dp), tint = if (favourite) scheme.primary else scheme.onSurfaceVariant) }
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Outlined.Close, uiContext.getString(R.string.ui_remove_custom_model), Modifier.size(17.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCapabilityLabels(capabilities: ModelCapabilities) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        @Composable fun Label(text: String, icon: Painter, value: ModelCapabilities.Capability) {
            if (value.supported == true) Row(Modifier.semantics { contentDescription = "$text: ${value.description}" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(icon, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val inferred = capabilities.thinking.source == ModelCapabilities.Source.FAMILY
        Label(if (inferred) uiContext.getString(R.string.ui_thinking_unconfirmed) else uiContext.getString(R.string.ui_thinking_2), painterResource(R.drawable.ic_brain), capabilities.thinking)
        Label(uiContext.getString(R.string.ui_tools), rememberVectorPainter(Icons.Outlined.Build), capabilities.tools)
        Label(uiContext.getString(R.string.ui_images), rememberVectorPainter(Icons.Outlined.Image), capabilities.images)
    }
}

@Composable
private fun ModelProviderMark(name: String, size: Dp) {
    Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Text(name.firstOrNull()?.uppercase().orEmpty(), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ModelDetailsDialog(entry: ModelEntry, provider: ProviderConfig, onDismiss: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val revision by ModelsDev.revision.collectAsStateWithLifecycle()
    val capabilities = remember(entry, provider, revision) { ModelCapabilities.of(entry, provider) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(entry.displayName ?: entry.id) }, text = {
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(provider.name, style = MaterialTheme.typography.bodyMedium)
            listOf(uiContext.getString(R.string.ui_thinking_2) to capabilities.thinking, uiContext.getString(R.string.ui_tools) to capabilities.tools, uiContext.getString(R.string.ui_images) to capabilities.images).forEach { (label, value) ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(value.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(uiContext.getString(R.string.ui_thinking_support_does_not_guarantee_visible_thinking), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(uiContext.getString(R.string.settings_action_got_it)) } })
}

@Composable
private fun AddCustomModelDialog(
    providerName: String,
    onDismiss: () -> Unit,
    onAdd: (modelId: String, reasoning: Boolean?) -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var modelId by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    val clean = modelId.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiContext.getString(R.string.ui_add_custom_model)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    uiContext.getString(R.string.ui_add_any_model_id_supported_by, (providerName).toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text(uiContext.getString(R.string.ui_model_id)) },
                    placeholder = { Text(uiContext.getString(R.string.ui_e_g_meta_llama_llama_3_3)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { thinking = !thinking }
                        .padding(vertical = 4.dp),
                ) {
                    Checkbox(
                        checked = thinking,
                        onCheckedChange = { thinking = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(uiContext.getString(R.string.ui_reasoning_model), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            uiContext.getString(R.string.ui_enables_thinking_level_ladder_for_this_model),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = clean.isNotBlank(),
                onClick = {
                    onAdd(clean, if (thinking) true else null)
                    onDismiss()
                },
            ) {
                Text(uiContext.getString(R.string.ui_add_select))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(uiContext.getString(R.string.settings_action_cancel))
            }
        },
    )
}

private val newestChatGptFirst = Comparator<ModelEntry> { left, right ->
    fun version(entry: ModelEntry) = entry.id.lowercase().removePrefix("gpt-")
        .substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val leftVersion = version(left)
    val rightVersion = version(right)
    for (index in 0 until maxOf(leftVersion.size, rightVersion.size)) {
        val order = rightVersion.getOrElse(index) { 0 }.compareTo(leftVersion.getOrElse(index) { 0 })
        if (order != 0) return@Comparator order
    }
    0
}

/** Compact context-window label for picker rows ("200K ctx", "1M ctx", "1B ctx"). */
private fun ctxLabel(tokens: Long?): String? = when {
    tokens == null || tokens <= 0 -> null
    tokens >= 1_000_000_000 -> "${tokens / 1_000_000_000}B ctx"
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M ctx"
    tokens >= 1_000 -> "${tokens / 1_000}K ctx"
    else -> "$tokens ctx"
}
