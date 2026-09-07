package com.androidharness.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.common.ProviderMark
import com.androidharness.app.ui.common.SecureDialogEffect
import com.androidharness.app.llm.ModelCatalog
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ModelsDev
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.endpointPath
import com.androidharness.app.llm.reasoningCapable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
)

internal val ProviderBrands: List<ProviderBrand?> = listOf(
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
    existingKey: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, type: ProviderType, baseUrl: String, model: String, apiKey: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // The API key field lives in this sheet's own window, which the
        // activity-level FLAG_SECURE never covered.
        SecureDialogEffect()
        ProviderSheetContent(
            existing = existing,
            existingKey = existingKey,
            onDismiss = onDismiss,
            onSave = onSave,
        )
    }
}

private enum class AddStep { PROVIDER, KEY, MODEL }

@Composable
internal fun ProviderSheetContent(
    existing: ProviderConfig?,
    existingKey: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, type: ProviderType, baseUrl: String, model: String, apiKey: String) -> Unit,
) {
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
    var apiKey by remember { mutableStateOf(existingKey ?: "") }

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
    val selectedLabel = brand?.label ?: devChoice?.name ?: "Custom endpoint"

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

    fun continueToModels() {
        scope.launch {
            fetching = true
            fetchError = null
            val result = withContext(Dispatchers.IO) {
                ModelCatalog.listModels(
                    ProviderConfig("", "", effectiveType, effectiveBaseUrl, ""),
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

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
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
                        .padding(horizontal = 16.dp),
                ) {
                    if (catalogSyncing) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        ) {
                            Text(
                                "Refreshing provider catalog…",
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
                            placeholder = { Text("Search ${devProviders.size} providers") },
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
                        .weight(1f, fill = false)
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
                                label = { Text("Protocol") },
                                trailingIcon = {
                                    IconButton(onClick = { typeMenu = true }) {
                                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose protocol")
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
                            label = { Text("Server address") },
                            placeholder = { Text("https://your-server.example.com/v1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (requiresKey) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it; fetchError = null },
                            label = { Text("API key") },
                            placeholder = {
                                Text("Get one from ${effectiveBaseUrl.substringAfter("://").substringBefore('/')}")
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Stored securely on this device and used only for this provider.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "$selectedLabel runs locally, so no API key is needed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }

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
                            enabled = !fetching &&
                                (!requiresKey || apiKey.isNotBlank()) &&
                                (!isCustom || baseUrl.isNotBlank()),
                        ) { Text(if (fetching) "Connecting…" else "Continue") }
                    },
                    secondary = if (fetchError != null) {
                        { TextButton(onClick = { step = AddStep.MODEL }) { Text("Continue anyway") } }
                    } else null,
                    onCancel = onDismiss,
                )
            }

            // ================================================================
            AddStep.MODEL -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    SelectedProviderSummary(
                        label = selectedLabel,
                        subtitle = "Choose the model this provider should use",
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = modelQuery,
                        onValueChange = { modelQuery = it },
                        placeholder = { Text("Search models or type an ID") },
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
                            .heightIn(max = 360.dp),
                    ) {
                        // Free-typed ID is a first-class row, not a fallback hack.
                        if (q.isNotBlank() && filtered.none { it.id == q }) {
                            item(key = "typed-$q") {
                                ModelPickRow(
                                    id = q,
                                    thinking = reasoningCapable(q),
                                    selected = model == q,
                                    hint = "Use custom model ID",
                                    onClick = { model = q },
                                )
                            }
                        }
                        items(filtered, key = { it.id }) { entry ->
                            ModelPickRow(
                                id = entry.id,
                                thinking = entry.reasoning ?: reasoningCapable(entry.id),
                                selected = model == entry.id,
                                hint = null,
                                onClick = { model = entry.id },
                            )
                        }
                        if (displayModels == null && q.isBlank()) {
                            item {
                                Text(
                                    "No catalog available yet. Type a model ID above.",
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
                        label = { Text("Profile name (optional)") },
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
                                onSave(finalName, effectiveType, effectiveBaseUrl.trim(), model.trim(), apiKey.trim())
                            },
                            enabled = model.isNotBlank(),
                        ) { Text("Save") }
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
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (step == AddStep.PROVIDER) 16.dp else 4.dp, end = 4.dp, bottom = 12.dp),
    ) {
        if (step != AddStep.PROVIDER) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (step) {
                        AddStep.PROVIDER -> if (editing) "Edit provider" else "Add provider"
                        AddStep.KEY -> "Connect $providerLabel"
                        AddStep.MODEL -> "Choose model"
                    },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Text(
                when (step) {
                    AddStep.PROVIDER -> "Pick a service or connect your own endpoint"
                    AddStep.KEY -> "Add the credentials needed to reach this provider"
                    AddStep.MODEL -> "Search the catalog or enter a model ID"
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Close")
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
        TextButton(onClick = onCancel) { Text("Cancel") }
        secondary?.invoke()
        primary()
    }
}

@Composable
private fun ModelPickRow(
    id: String,
    thinking: Boolean,
    selected: Boolean,
    hint: String?,
    onClick: () -> Unit,
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
                    id,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = listOfNotNull(if (thinking) "thinking" else null, hint)
                    .joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (thinking) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = scheme.primary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

/**
 * The searchable provider directory: "Custom endpoint" first, then curated
 * brands under "Popular", then every models.dev provider the app can speak
 * to under "All providers". Divider-separated tap targets, selection shown
 * with a trailing check.
 */
@Composable
private fun ProviderDirectory(
    query: String,
    devProviders: List<ModelsDev.ProviderInfo>,
    selectedBrand: ProviderBrand?,
    selectedDev: ModelsDev.ProviderInfo?,
    customSelected: Boolean,
    onSelectBrand: (ProviderBrand) -> Unit,
    onSelectDev: (ModelsDev.ProviderInfo) -> Unit,
    onSelectCustom: () -> Unit,
) {
    val q = remember(query) { query.trim().lowercase() }
    val curated = remember(q) {
        ProviderBrands.filterNotNull()
            .filter { q.isBlank() || it.label.lowercase().contains(q) }
    }
    val dev = remember(q, devProviders) {
        devProviders.filter {
            q.isBlank() || it.name.lowercase().contains(q) || it.id.lowercase().contains(q)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (q.isBlank() || "custom".contains(q)) {
            item(key = "custom") {
                ProviderRow(
                    title = "Custom endpoint",
                    subtitle = "Connect any compatible API or local server",
                    selected = customSelected,
                    custom = true,
                    onClick = onSelectCustom,
                )
            }
        }
        if (curated.isNotEmpty()) {
            item(key = "popular-label") {
                DirectoryLabel(if (q.isBlank()) "Popular" else "Providers")
            }
            items(curated, key = { "brand-${it.label}" }) { b ->
                ProviderRow(
                    title = b.label,
                    subtitle = if (b.needsKey) "API key required" else "Runs locally without a key",
                    selected = b.label == selectedBrand?.label,
                    onClick = { onSelectBrand(b) },
                )
            }
        }
        if (dev.isNotEmpty()) {
            if (q.isBlank()) {
                item(key = "all-label") { DirectoryLabel("All providers") }
            }
            items(dev, key = { "dev-${it.id}" }) { info ->
                ProviderRow(
                    title = info.name,
                    subtitle = "${info.modelCount} models",
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
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                    contentDescription = "Selected",
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
