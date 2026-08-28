package com.androidharness.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
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

    val devProviders = remember {
        val curatedHosts = ProviderBrands.filterNotNull().map {
            it.baseUrl.substringAfter("://").substringBefore('/').lowercase()
        }
        val curatedIds = setOf(
            "openrouter", "anthropic", "google", "openai", "groq",
            "deepseek", "togetherai", "mistral",
        )
        ModelsDev.providers().filter { info ->
            ModelsDev.protocolFor(info.npm) != null &&
                info.id !in curatedIds &&
                (info.api == null ||
                    curatedHosts.none { it in info.api.substringAfter("://").lowercase() })
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
        // ---- Step header -----------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (step == AddStep.PROVIDER) {
                Text(
                    if (existing == null) "Add provider" else "Edit provider",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            } else {
                IconButton(onClick = {
                    step = if (step == AddStep.MODEL) AddStep.KEY else AddStep.PROVIDER
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    if (step == AddStep.KEY) {
                        ("API key · ").plus(selectedLabel)
                    } else {
                        "Pick a model"
                    },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
            }
        }

        when (step) {
            // ================================================================
            AddStep.PROVIDER -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    if (devProviders.isNotEmpty()) {
                        OutlinedTextField(
                            value = providerQuery,
                            onValueChange = { providerQuery = it },
                            placeholder = { Text("Search ${devProviders.size} providers") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
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
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            "$selectedLabel runs locally, no API key needed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                    OutlinedTextField(
                        value = modelQuery,
                        onValueChange = { modelQuery = it },
                        placeholder = { Text("Search models or type an ID") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    val q = modelQuery.trim()
                    val filtered = displayModels.orEmpty()
                        .filter { q.isBlank() || it.id.lowercase().contains(q.lowercase()) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // Free-typed ID is a first-class row, not a fallback hack.
                        if (q.isNotBlank() && filtered.none { it.id == q }) {
                            ModelPickRow(
                                id = q,
                                thinking = reasoningCapable(q),
                                selected = model == q,
                                hint = "use as typed",
                                onClick = { model = q },
                            )
                        }
                        filtered.forEach { entry ->
                            ModelPickRow(
                                id = entry.id,
                                thinking = entry.reasoning ?: reasoningCapable(entry.id),
                                selected = model == entry.id,
                                hint = null,
                                onClick = { model = entry.id },
                            )
                        }
                        if (displayModels == null && q.isBlank()) {
                            Text(
                                "Type a model ID above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                id,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(if (thinking) "thinking" else null, hint)
                .joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (thinking) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(18.dp),
            )
        }
    }
}

/**
 * The searchable provider directory: curated brands under "Popular", every
 * models.dev provider the app can speak to under "All providers", then
 * "Custom endpoint". Divider-separated tap targets, selection shown with a
 * trailing check.
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
    val scheme = MaterialTheme.colorScheme
    val q = query.trim().lowercase()
    val curated = ProviderBrands.filterNotNull()
        .filter { q.isBlank() || it.label.lowercase().contains(q) }
    val dev = devProviders
        .filter { q.isBlank() || it.name.lowercase().contains(q) || it.id.contains(q) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        if (curated.isNotEmpty()) {
            DirectoryLabel(if (q.isBlank()) "Popular" else "Brands")
            curated.forEach { b ->
                ProviderRow(
                    title = b.label,
                    subtitle = if (b.needsKey) "API key required" else "Local (no API key)",
                    selected = b.label == selectedBrand?.label,
                    onClick = { onSelectBrand(b) },
                )
            }
        }
        if (dev.isNotEmpty()) {
            if (q.isBlank()) DirectoryLabel("All providers")
            dev.forEach { info ->
                ProviderRow(
                    title = info.name,
                    subtitle = "${info.modelCount} models",
                    selected = info.id == selectedDev?.id,
                    onClick = { onSelectDev(info) },
                )
            }
        }
        if (q.isBlank() || "custom".contains(q)) {
            if (curated.isNotEmpty() || dev.isNotEmpty()) {
                HorizontalDivider(
                    color = scheme.outlineVariant.copy(alpha = 0.35f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            ProviderRow(
                title = "Custom endpoint",
                subtitle = "Bring your own server URL",
                selected = customSelected,
                onClick = onSelectCustom,
            )
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
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) scheme.primary else scheme.onSurface,
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
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = scheme.primary,
                modifier = Modifier.height(18.dp),
            )
        }
    }
}
