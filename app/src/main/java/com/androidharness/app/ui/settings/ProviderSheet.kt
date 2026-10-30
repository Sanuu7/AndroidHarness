package com.androidharness.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.material3.OutlinedTextField
import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface
import com.androidharness.app.ui.theme.HarnessGlassModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.common.ProviderMark
import com.androidharness.app.ui.common.SecureDialogEffect
import com.androidharness.app.R
import com.androidharness.app.llm.ModelCatalog
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ModelsDev
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderApiKey
import com.androidharness.app.llm.ProviderKeyPool
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.endpointPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

/**
 * One tappable provider brand. Everything except [label] is plumbing the UI
 * hides, users pick "OpenRouter", never "OpenAI-compatible + base URL".
 */
data class ProviderBrand(
    val label: String,
    val type: ProviderType,
    val baseUrl: String,
    /** Local servers need no key; the key step shows a note instead. */
    val needsKey: Boolean,
    /** Pre-filled model, when a brand has one obvious default. */
    val suggestedModel: String? = null,
    /** Short pill next to the name, used to introduce a newly added brand. */
    @StringRes val badge: Int? = null,
    /** Rendered above every other entry, outside the Popular group. */
    val pinned: Boolean = false,
)

internal val ProviderBrands: List<ProviderBrand?> = listOf(
    // Curated brands are compiled in, so a brand keeps its position here even
    // after the fetched models.dev directory refreshes.
    ProviderBrand("A2Agent", ProviderType.OPENAI_COMPAT, "https://api.a2agent.me/v1", true,
        badge = R.string.settings_provider_badge_new, pinned = true),
    ProviderBrand("OpenRouter", ProviderType.OPENAI_COMPAT, "https://openrouter.ai/api/v1", true),
    ProviderBrand("Anthropic", ProviderType.ANTHROPIC, "https://api.anthropic.com", true, "claude-sonnet-4-5"),
    ProviderBrand("Gemini", ProviderType.GEMINI, "https://generativelanguage.googleapis.com/v1beta", true, "gemini-2.5-flash"),
    ProviderBrand("OpenAI", ProviderType.OPENAI_COMPAT, "https://api.openai.com/v1", true),
    // The newer Responses API (gpt-5/o-series first-class reasoning; some
    // latest models exist only there), same key as plain OpenAI.
    ProviderBrand("OpenAI (Responses)", ProviderType.OPENAI_RESPONSES, "https://api.openai.com/v1", true),
    ProviderBrand("Groq", ProviderType.OPENAI_COMPAT, "https://api.groq.com/openai/v1", true),
    ProviderBrand("DeepSeek", ProviderType.OPENAI_COMPAT, "https://api.deepseek.com/v1", true),
    ProviderBrand("Together", ProviderType.OPENAI_COMPAT, "https://api.together.xyz/v1", true),
    ProviderBrand("Mistral", ProviderType.OPENAI_COMPAT, "https://api.mistral.ai/v1", true),
    ProviderBrand("Ollama", ProviderType.OPENAI_COMPAT, "http://127.0.0.1:11434/v1", false),
    ProviderBrand("LM Studio", ProviderType.OPENAI_COMPAT, "http://127.0.0.1:1234/v1", false),
    // Custom endpoints get their own tile that reveals the advanced fields.
    null,
)

/**
 * Add/edit a provider as a guided 3-step flow inside one sheet:
 * pick a provider → paste the key → pick a model. One decision per screen;
 * models load automatically between steps 2 and 3 (no "Load models" ritual),
 * with the offline models.dev list as instant fallback. Used by the Providers
 * screen and first-run setup; chat goes through [ProviderManagerSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSheet(
    existing: ProviderConfig?,
    apiKeys: (String) -> ProviderKeyPool,
    onDismiss: () -> Unit,
    onSave: (name: String, type: ProviderType, baseUrl: String, model: String, apiKeys: ProviderKeyPool, headers: List<com.androidharness.app.llm.ProviderHeader>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        // The API key field lives in this sheet's own window, which the
        // activity-level FLAG_SECURE never covered.
        ProviderSheetContent(
            existing = existing,
            apiKeys = apiKeys,
            onDismiss = onDismiss,
            onSave = onSave,
        )
    }
}

private enum class AddStep { PROVIDER, KEY, MODEL }

@Composable
internal fun ProviderSheetContent(
    existing: ProviderConfig?,
    apiKeys: (String) -> ProviderKeyPool,
    onDismiss: () -> Unit,
    onSave: (name: String, type: ProviderType, baseUrl: String, model: String, apiKeys: ProviderKeyPool, headers: List<com.androidharness.app.llm.ProviderHeader>) -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    SecureDialogEffect()
    val scope = rememberCoroutineScope()

    // ---- Provider identity --------------------------------------------------
    fun matchBrand(config: ProviderConfig): ProviderBrand? =
        ProviderBrands.filterNotNull().firstOrNull {
            it.type == config.type && it.baseUrl.equals(config.baseUrl, ignoreCase = true)
        }

    var brand by remember { mutableStateOf(existing?.let(::matchBrand)) }
    var devChoice by remember { mutableStateOf<ModelsDev.ProviderInfo?>(null) }
    var isCustom by remember { mutableStateOf(existing != null && brand == null) }
    var type by remember { mutableStateOf(existing?.type ?: ProviderType.OPENAI_COMPAT) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: ProviderType.OPENAI_COMPAT.defaultBaseUrl) }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var model by remember { mutableStateOf(existing?.model ?: "") }
    var keyPool by remember(existing?.id) {
        mutableStateOf(if (existing == null) ProviderKeyPool(listOf(ProviderApiKey(value = ""))) else ProviderKeyPool())
    }
    var keysLoaded by remember(existing?.id) { mutableStateOf(existing == null) }
    var keysLoadFailed by remember(existing?.id) { mutableStateOf(false) }
    LaunchedEffect(existing?.id) {
        if (existing != null) try {
            keyPool = withContext(Dispatchers.IO) { apiKeys(existing.id) }
            keysLoaded = true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { keysLoadFailed = true }
    }
    val apiKey = keyPool.keys.firstOrNull { it.enabled }?.value.orEmpty()
    val keysValid = keysLoaded && keyPool.error() == null
    var headerRows by remember { mutableStateOf(existing?.headers.orEmpty().map { ProviderHeaderDraft(it) }) }
    val headers = headerRows.map { it.header }
    val headersValid = com.androidharness.app.llm.ProviderHeaders.error(headers) == null

    var typeMenu by remember { mutableStateOf(false) }
    var providerQuery by remember { mutableStateOf("") }
    var modelQuery by remember { mutableStateOf("") }

    // Editing skips the provider pick; adding starts there.
    var step by remember { mutableStateOf(if (existing == null) AddStep.PROVIDER else AddStep.KEY) }

    // ---- Catalog ------------------------------------------------------------
    var entries by remember { mutableStateOf<List<ModelEntry>?>(null) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var catalogSyncing by remember { mutableStateOf(false) }

    // Reactive directory: updates when a background catalog refresh lands,
    // so the count here always matches the provider-manager toast.
    val catalogProviders by ModelsDev.providersFlow.collectAsStateWithLifecycle(initialValue = ModelsDev.providers())
    val devProviders = remember(catalogProviders) {
        ModelsDev.speakableProviders(catalogProviders)
    }
    // Auto-sync on open when adding: the user always browses a fresh list.
    val context = LocalContext.current
    LaunchedEffect(existing) {
        if (existing == null && !catalogSyncing) {
            catalogSyncing = true
            ModelsDev.refresh(context, force = true)
            catalogSyncing = false
        }
    }

    val devType = devChoice?.let { ModelsDev.protocolFor(it.npm) }
    val effectiveType = when {
        isCustom -> type
        devType != null -> devType
        else -> brand?.type ?: type
    }
    val effectiveBaseUrl = when {
        isCustom -> baseUrl
        devChoice != null -> devChoice!!.api ?: effectiveType.defaultBaseUrl
        else -> brand?.baseUrl ?: baseUrl
    }
    val requiresKey = if (devChoice != null) true else brand?.needsKey ?: true
    val selectedLabel = brand?.label ?: devChoice?.name ?: uiContext.getString(R.string.settings_provider_custom_endpoint)

    // Offline model list from the catalog: shown instantly on step 3 when the
    // live fetch hasn't produced a list (or failed).
    val devModelEntries = remember(devChoice) {
        devChoice?.let { info ->
            ModelsDev.modelsFor(info.id).map { (id, e) ->
                ModelEntry(
                    id,
                    reasoning = e.reasoning ?: (e.effortValues != null || e.toggle || e.budgetTokens),
                    contextTokens = e.contextTokens,
                )
            }.sortedBy { it.id }
        }.orEmpty()
    }
    val displayModels = entries ?: devModelEntries.takeIf { devChoice != null && it.isNotEmpty() }

    // The key alone is enough to add a provider: the first catalog model is
    // preselected so Save always has a model to fall back to.
    LaunchedEffect(displayModels) {
        if (model.isBlank()) model = displayModels?.firstOrNull()?.id ?: ""
    }

    fun continueToModels() {
        // A disabled key must never be used just to load the model list.
        if (requiresKey && apiKey.isBlank()) { step = AddStep.MODEL; return }
        scope.launch {
            fetching = true
            fetchError = null
            val result = withContext(Dispatchers.IO) {
                ModelCatalog.listModels(
                    ProviderConfig(existing?.id.orEmpty(), "", effectiveType, effectiveBaseUrl, "", headers = headers),
                    apiKey,
                )
            }
            fetching = false
            when (result) {
                is ModelCatalog.Result.Models -> {
                    entries = result.models
                    step = AddStep.MODEL
                }
                is ModelCatalog.Result.Failed -> fetchError = result.message
            }
        }
    }

    // A fixed sheet height, not wrap-content. The model step used to grow past
    // the viewport, so Save sat under the gesture bar and a downward drag
    // dismissed the sheet instead of scrolling the form.
    val sheetHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    Column(
        Modifier
            .fillMaxWidth()
            .height(sheetHeight)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        ProviderFlowHeader(
            step = step,
            editing = existing != null,
            providerLabel = selectedLabel,
            onBack = {
                step = if (step == AddStep.MODEL) AddStep.KEY else AddStep.PROVIDER
            },
            onClose = onDismiss,
        )

        when (step) {
            // ================================================================
            AddStep.PROVIDER -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                ) {
                    if (catalogSyncing) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        ) {
                            Text(
                                uiContext.getString(R.string.ui_refreshing_provider_catalog),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (devProviders.isNotEmpty()) {
                        OutlinedTextField(
                            value = providerQuery,
                            onValueChange = { providerQuery = it },
                            placeholder = { Text(stringResource(R.string.settings_provider_search_count, devProviders.size.toString())) },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    ProviderDirectory(
                        query = providerQuery,
                        devProviders = devProviders,
                        selectedBrand = brand,
                        selectedDev = devChoice,
                        customSelected = isCustom,
                        onSelectBrand = { selected ->
                            if (!selected.needsKey && keyPool.keys.all { it.value.isBlank() }) keyPool = keyPool.copy(keys = emptyList())
                            devChoice = null
                            isCustom = false
                            brand = selected
                            type = selected.type
                            baseUrl = selected.baseUrl
                            if (model.isBlank()) model = selected.suggestedModel ?: ""
                            entries = null
                            fetchError = null
                            step = AddStep.KEY
                        },
                        onSelectDev = { info ->
                            brand = null
                            isCustom = false
                            devChoice = info
                            type = ModelsDev.protocolFor(info.npm) ?: type
                            baseUrl = info.api ?: type.defaultBaseUrl
                            entries = null
                            fetchError = null
                            step = AddStep.KEY
                        },
                        onSelectCustom = {
                            brand = null
                            devChoice = null
                            isCustom = true
                            entries = null
                            fetchError = null
                            step = AddStep.KEY
                        },
                    )
                }
            }

            // ================================================================
            AddStep.KEY -> {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    SelectedProviderSummary(
                        label = selectedLabel,
                        subtitle = if (isCustom) effectiveBaseUrl else effectiveType.endpointPath,
                    )
                    if (isCustom) {
                        Box {
                            OutlinedTextField(
                                value = type.endpointPath,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.settings_provider_protocol)) },
                                trailingIcon = {
                                    IconButton(onClick = { typeMenu = true }) {
                                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.settings_provider_choose_protocol))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                                ProviderType.entries.forEach { entry ->
                                    DropdownMenuItem(
                                        text = { Text(entry.endpointPath) },
                                        onClick = {
                                            type = entry
                                            if (existing == null || baseUrl == existing.type.defaultBaseUrl) {
                                                baseUrl = entry.defaultBaseUrl
                                            }
                                            typeMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text(stringResource(R.string.settings_provider_server_address)) },
                            placeholder = { Text("https://your-server.example.com/v1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (requiresKey || keyPool.keys.any { it.value.isNotEmpty() }) {
                        if (keysLoaded) ProviderKeysEditor(keyPool, onChange = { keyPool = it; fetchError = null }, enabled = !fetching)
                        else if (!keysLoadFailed) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                        keyPool.error()?.takeUnless { keyPool.keys.any { key -> key.value.isBlank() } }?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.settings_provider_local_no_key, selectedLabel),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }

                    if (keysLoadFailed) Text(stringResource(R.string.provider_keys_load_failed), color = MaterialTheme.colorScheme.error)
                    ProviderHeadersEditor(headerRows, onChange = { headerRows = it; fetchError = null })

                    fetchError?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                StepButtons(
                    primary = {
                        Button(
                            onClick = { continueToModels() },
                            enabled = !fetching && headersValid && keysValid &&
                                (!isCustom || baseUrl.isNotBlank()),
                        ) { Text(if (fetching) uiContext.getString(R.string.ui_connecting) else uiContext.getString(R.string.settings_action_continue)) }
                    },
                    secondary = if (fetchError != null && headersValid && keysValid) {
                        { TextButton(onClick = { step = AddStep.MODEL }) { Text(stringResource(R.string.settings_action_continue_anyway)) } }
                    } else null,
                    onCancel = onDismiss,
                )
            }

            // ================================================================
            AddStep.MODEL -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                ) {
                    SelectedProviderSummary(
                        label = selectedLabel,
                        subtitle = stringResource(R.string.settings_provider_choose_model),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = modelQuery,
                        onValueChange = { modelQuery = it },
                        placeholder = { Text(stringResource(R.string.settings_provider_search_model)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    val q = modelQuery.trim()
                    val filtered = remember(displayModels, q) {
                        val lower = q.lowercase()
                        displayModels.orEmpty()
                            .filter { lower.isBlank() || it.id.lowercase().contains(lower) }
                    }
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        // Free-typed ID is a first-class row, not a fallback hack.
                        if (q.isNotBlank() && filtered.none { it.id == q }) {
                            item(key = "typed-$q") {
                                ModelPickRow(
                                    entry = ModelEntry(q),
                                    providerType = effectiveType,
                                    selected = model == q,
                                    hint = stringResource(R.string.settings_provider_custom_model),
                                    onClick = { model = q },
                                )
                            }
                        }
                        items(filtered, key = { it.id }) { entry ->
                            ModelPickRow(
                                entry = entry,
                                providerType = effectiveType,
                                directory = entries == null,
                                selected = model == entry.id,
                                hint = null,
                                onClick = { model = entry.id },
                            )
                        }
                        if (displayModels == null && q.isBlank()) {
                            item {
                                Text(
                                    uiContext.getString(R.string.ui_no_catalog_available_yet_type_a_model),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 18.dp),
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.settings_provider_profile_name)) },
                        placeholder = { Text(selectedLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }

                StepButtons(
                    primary = {
                        Button(
                            onClick = {
                                val finalName = name.ifBlank { selectedLabel }
                                onSave(finalName, effectiveType, effectiveBaseUrl.trim(), model.trim(), keyPool,
                                    com.androidharness.app.llm.ProviderHeaders.normalized(headers))
                            },
                            enabled = model.isNotBlank() && headersValid && keysValid,
                        ) { Text(stringResource(R.string.settings_action_save)) }
                    },
                    secondary = null,
                    onCancel = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun ProviderFlowHeader(
    step: AddStep,
    editing: Boolean,
    providerLabel: String,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (step == AddStep.PROVIDER) 16.dp else 4.dp, end = 4.dp, bottom = 12.dp),
    ) {
        if (step != AddStep.PROVIDER) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_action_back))
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (step) {
                        AddStep.PROVIDER -> if (editing) stringResource(R.string.settings_provider_edit_title)
                        else stringResource(R.string.settings_provider_add_title)
                        AddStep.KEY -> stringResource(R.string.settings_provider_connect_title, providerLabel)
                        AddStep.MODEL -> stringResource(R.string.settings_provider_choose_model_title)
                    },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = scheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        when (step) {
                            AddStep.PROVIDER -> "1 of 3"
                            AddStep.KEY -> "2 of 3"
                            AddStep.MODEL -> "3 of 3"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Text(
                when (step) {
                    AddStep.PROVIDER -> uiContext.getString(R.string.ui_pick_a_service_or_connect_your_own)
                    AddStep.KEY -> uiContext.getString(R.string.ui_add_the_credentials_needed_to_reach_this)
                    AddStep.MODEL -> uiContext.getString(R.string.ui_search_the_catalog_or_enter_a_model)
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_action_close))
        }
    }
}

@Composable
private fun SelectedProviderSummary(
    label: String,
    subtitle: String,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            ProviderMark(size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Bottom action row: primary right, optional secondary, Cancel left-most. */
@Composable
private fun StepButtons(
    primary: @Composable () -> Unit,
    secondary: (@Composable () -> Unit)?,
    onCancel: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_action_cancel)) }
        secondary?.invoke()
        primary()
    }
}

@Composable
private fun ModelPickRow(
    entry: ModelEntry,
    providerType: ProviderType,
    selected: Boolean,
    hint: String?,
    onClick: () -> Unit,
    directory: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        color = if (selected) scheme.primaryContainer.copy(alpha = 0.62f) else scheme.surface,
        shape = RoundedCornerShape(13.dp),
        border = if (selected) BorderStroke(1.dp, scheme.primary.copy(alpha = 0.35f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.id,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = hint.orEmpty()
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            com.androidharness.app.ui.common.ModelReasoningIcon(entry, providerType, directory)
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.settings_chatgpt_selected),
                    tint = scheme.primary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

/**
 * The searchable provider directory: pinned brands at the very top, then
 * "Custom endpoint", then curated brands under "Popular", then every
 * models.dev provider the app can speak to under "All providers".
 * Divider-separated tap targets, selection shown with a trailing check.
 */
@Composable
private fun ColumnScope.ProviderDirectory(
    query: String,
    devProviders: List<ModelsDev.ProviderInfo>,
    selectedBrand: ProviderBrand?,
    selectedDev: ModelsDev.ProviderInfo?,
    customSelected: Boolean,
    onSelectBrand: (ProviderBrand) -> Unit,
    onSelectDev: (ModelsDev.ProviderInfo) -> Unit,
    onSelectCustom: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val q = remember(query) { query.trim().lowercase() }
    val curated = remember(q) {
        ProviderBrands.filterNotNull()
            .filter { q.isBlank() || it.label.lowercase().contains(q) }
    }
    val pinned = curated.filter { it.pinned }
    val popular = curated.filterNot { it.pinned }
    val dev = remember(q, devProviders) {
        devProviders.filter {
            q.isBlank() || it.name.lowercase().contains(q) || it.id.lowercase().contains(q)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(pinned, key = { "pinned-${it.label}" }) { b ->
            ProviderRow(
                title = b.label,
                subtitle = if (b.needsKey) uiContext.getString(R.string.ui_api_key_required) else uiContext.getString(R.string.ui_runs_locally_without_a_key),
                selected = b.label == selectedBrand?.label,
                badge = b.badge?.let(uiContext::getString),
                onClick = { onSelectBrand(b) },
            )
        }
        if (q.isBlank() || "custom".contains(q)) {
            item(key = "custom") {
                ProviderRow(
                    title = stringResource(R.string.settings_provider_custom_endpoint),
                    subtitle = stringResource(R.string.settings_provider_custom_endpoint_desc),
                    selected = customSelected,
                    custom = true,
                    onClick = onSelectCustom,
                )
            }
        }
        if (popular.isNotEmpty()) {
            item(key = "popular-label") {
                DirectoryLabel(if (q.isBlank()) uiContext.getString(R.string.ui_popular) else uiContext.getString(R.string.settings_providers_title))
            }
            items(popular, key = { "brand-${it.label}" }) { b ->
                ProviderRow(
                    title = b.label,
                    subtitle = if (b.needsKey) uiContext.getString(R.string.ui_api_key_required) else uiContext.getString(R.string.ui_runs_locally_without_a_key),
                    selected = b.label == selectedBrand?.label,
                    badge = b.badge?.let(uiContext::getString),
                    onClick = { onSelectBrand(b) },
                )
            }
        }
        if (dev.isNotEmpty()) {
            if (q.isBlank()) {
                item(key = "all-label") { DirectoryLabel(stringResource(R.string.settings_provider_all_providers)) }
            }
            items(dev, key = { "dev-${it.id}" }) { info ->
                ProviderRow(
                    title = info.name,
                    subtitle = stringResource(R.string.settings_provider_model_count, info.modelCount.toString()),
                    selected = info.id == selectedDev?.id,
                    onClick = { onSelectDev(info) },
                )
            }
        }
    }
}

@Composable
private fun DirectoryLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun ProviderRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    custom: Boolean = false,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        color = if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (selected) scheme.primary.copy(alpha = 0.28f)
            else scheme.outlineVariant.copy(alpha = 0.32f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            if (custom) {
                ProviderMark(size = 38.dp)
            } else {
                ProviderInitial(label = title, selected = selected)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (badge != null) Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) scheme.onSecondaryContainer else scheme.onTertiaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) scheme.primary.copy(alpha = 0.16f) else scheme.tertiaryContainer)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) scheme.onSecondaryContainer.copy(alpha = 0.72f)
                    else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.settings_chatgpt_selected),
                    tint = scheme.primary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun ProviderInitial(
    label: String,
    selected: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val initials = remember(label) {
        label
            .replace("(Responses)", "")
            .split(' ', '-', '_')
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.take(1).uppercase() }
            .take(2)
    }
    Surface(
        color = if (selected) scheme.primary.copy(alpha = 0.14f) else scheme.surfaceContainerHighest,
        contentColor = if (selected) scheme.primary else scheme.onSurfaceVariant,
        shape = RoundedCornerShape(11.dp),
        modifier = Modifier.size(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(initials.ifBlank { "AI" }, style = MaterialTheme.typography.labelMedium)
        }
    }
}
