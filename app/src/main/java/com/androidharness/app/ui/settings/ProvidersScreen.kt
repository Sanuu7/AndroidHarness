package com.androidharness.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.data.AppSettings
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.ModelCatalog
import com.androidharness.app.ui.common.AppHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProvidersScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var showDialog by remember { mutableStateOf(false) }
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
                    onSetActive = {
                        scope.launch { container.settings.setActiveProvider(provider.id) }
                    },
                    onEdit = {
                        editing = provider
                        showDialog = true
                    },
                    onDelete = {
                        scope.launch { container.providers.delete(provider.id) }
                    },
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
        ProviderDialog(
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
}

@Composable
private fun ProviderCard(
    provider: ProviderConfig,
    active: Boolean,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
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
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = scheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = scheme.error)
            }
        }
    }
}

@Composable
private fun ProviderDialog(
    existing: ProviderConfig?,
    existingKey: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, type: ProviderType, baseUrl: String, model: String, apiKey: String) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: ProviderType.OPENAI_COMPAT) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: ProviderType.OPENAI_COMPAT.defaultBaseUrl) }
    var model by remember { mutableStateOf(existing?.model ?: "") }
    var apiKey by remember { mutableStateOf(existingKey ?: "") }
    var typeMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add provider" else "Edit provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. OpenRouter") },
                    singleLine = true,
                )
                Box {
                    OutlinedTextField(
                        value = type.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("API type") },
                        trailingIcon = {
                            IconButton(onClick = { typeMenu = true }) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose type")
                            }
                        },
                    )
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        ProviderType.entries.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text(entry.label) },
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
                    label = { Text("Base URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    placeholder = {
                        Text(
                            when (type) {
                                ProviderType.OPENAI_COMPAT -> "e.g. gpt-4o, deepseek/deepseek-chat"
                                ProviderType.ANTHROPIC -> "e.g. claude-sonnet-4-5"
                                ProviderType.GEMINI -> "e.g. gemini-2.5-pro"
                            }
                        )
                    },
                    singleLine = true,
                )

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProviderPresets.forEach { (label, t, base) ->
                        SuggestionChip(
                            onClick = {
                                type = t
                                baseUrl = base
                                when (t) {
                                    ProviderType.OPENAI_COMPAT -> model = ""
                                    ProviderType.ANTHROPIC -> model = "claude-sonnet-4-5-20250929"
                                    ProviderType.GEMINI -> model = "gemini-2.5-pro"
                                }
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                var modelList by remember { mutableStateOf<List<String>?>(null) }
                var modelError by remember { mutableStateOf<String?>(null) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (apiKey.isBlank()) {
                                modelError = "Need an API key first"
                                return@OutlinedButton
                            }
                            modelList = null; modelError = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    ModelCatalog.listModels(
                                        ProviderConfig("", "", type, baseUrl, ""), apiKey,
                                    )
                                }
                                when (result) {
                                    is ModelCatalog.Result.Models -> modelList = result.models
                                    is ModelCatalog.Result.Failed -> modelError = result.message
                                }
                            }
                        },
                    ) { Text("Fetch models") }
                    OutlinedButton(onClick = {
                        if (apiKey.isBlank()) {
                            modelError = "Need an API key first"
                            return@OutlinedButton
                        }
                        modelError = "Testing…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                ModelCatalog.listModels(
                                    ProviderConfig("", "", type, baseUrl, ""), apiKey,
                                )
                            }
                            modelError = when (result) {
                                is ModelCatalog.Result.Models -> "✓ Connected — ${result.models.size} models in ${result.latencyMs}ms"
                                is ModelCatalog.Result.Failed -> result.message
                            }
                        }
                    }) { Text("Test connection") }
                }
                modelError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                modelList?.let { models ->
                    var modelDropdown by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { modelDropdown = true }) {
                            Text("Pick from ${models.size} models")
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = modelDropdown, onDismissRequest = { modelDropdown = false }) {
                            models.take(80).forEach { id ->
                                DropdownMenuItem(
                                    text = { Text(id, style = MaterialTheme.typography.labelSmall) },
                                    onClick = { model = id; modelDropdown = false; modelList = null },
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = name.ifBlank { type.label }
                    onSave(finalName, type, baseUrl.trim(), model.trim(), apiKey.trim())
                },
                enabled = model.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private val ProviderPresets = listOf(
    Triple("OpenRouter", ProviderType.OPENAI_COMPAT, "https://openrouter.ai/api/v1"),
    Triple("Groq", ProviderType.OPENAI_COMPAT, "https://api.groq.com/openai/v1"),
    Triple("Together", ProviderType.OPENAI_COMPAT, "https://api.together.xyz/v1"),
    Triple("DeepSeek", ProviderType.OPENAI_COMPAT, "https://api.deepseek.com/v1"),
    Triple("Mistral", ProviderType.OPENAI_COMPAT, "https://api.mistral.ai/v1"),
    Triple("Ollama", ProviderType.OPENAI_COMPAT, "http://127.0.0.1:11434/v1"),
    Triple("LM Studio", ProviderType.OPENAI_COMPAT, "http://127.0.0.1:1234/v1"),
)
