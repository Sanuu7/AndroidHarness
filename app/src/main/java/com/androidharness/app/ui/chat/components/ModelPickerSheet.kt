package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ModelsDev
import com.androidharness.app.llm.reasoningCapable
import com.androidharness.app.ui.common.ProviderMark
import kotlinx.coroutines.launch

/**
 * Model picker for ONE provider (the active one, or [browseProviderId] when
 * browsing from the Providers screen): current selection + thinking tiers +
 * searchable model list with thinking/context badges. Switching providers is
 * a deliberate hop ("Switch provider" → manager sheet), never an accidental
 * scroll into another provider's models.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    /** When set (Providers screen "browse"), list this provider instead of the active one. */
    browseProviderId: String? = null,
    onAddCustomModel: (providerId: String, model: String, reasoning: Boolean?) -> Unit = { _, _, _ -> },
    onDeleteCustomModel: (providerId: String, model: String) -> Unit = { _, _ -> },
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var thinkOnly by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var showAddCustomDialog by remember { mutableStateOf(false) }

    val listedId = browseProviderId ?: activeProviderId
    val listedProvider = providers.firstOrNull { it.id == listedId }
    val activeProvider = providers.firstOrNull { it.id == activeProviderId }
    val effective = activeModel?.takeIf { it.isNotBlank() } ?: activeProvider?.model
    val listedModel = if (browseProviderId == null) effective else listedProvider?.model
    val devKey = ModelsDev.providerKeyFor(listedProvider?.baseUrl)
    val listedCatalog = remember(listedProvider?.id, catalogs) {
        listedProvider?.let { catalogs[it.id].orEmpty() }.orEmpty()
    }
    val normalizedQuery = remember(query) { query.trim().lowercase() }
    val visibleRows = remember(listedProvider, listedCatalog, normalizedQuery, thinkOnly) {
        val provider = listedProvider ?: return@remember emptyList()
        buildList {
            add(ModelEntry(provider.model, reasoning = null, contextTokens = null))
            addAll(listedCatalog.filter { it.id != provider.model })
        }.distinctBy { it.id }
            .filter { normalizedQuery.isBlank() || it.id.lowercase().contains(normalizedQuery) }
            .filter { !thinkOnly || (it.reasoning ?: reasoningCapable(it.id)) }
    }

    // Opens fully expanded: half-expanded sheets trap bottom rows behind the
    // drag-to-dismiss gesture, which read as "touch not responding".
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Choose model", style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        "Search, filter, or enter any model ID",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }

            // Current selection + prominent jump to provider management.
            if (activeProvider != null && browseProviderId == null) {
                Surface(
                    color = scheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        ProviderMark(size = 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                activeProvider.name,
                                style = MaterialTheme.typography.titleSmallEmphasized,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                effective.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(onClick = { onDismiss(); onManageProviders() }) {
                            Text("Switch")
                        }
                    }
                }
            }

            // Thinking tiers live in the header badge, not here: the picker is
            // about WHICH model runs; the global ladder adapts to it via
            // ThinkingSpecs.clampStoredLevel on every switch.

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search models") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilterChip(
                    selected = thinkOnly,
                    onClick = { thinkOnly = !thinkOnly },
                    label = { Text("Thinking only", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.width(8.dp))
                AssistChip(
                    onClick = { showAddCustomDialog = true },
                    label = { Text("Custom model", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                listedProvider?.let { p ->
                    Text(
                        if (isRefreshing) "reloading…" else if (listedCatalog.isEmpty()) "models" else "${listedCatalog.size} models",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    IconButton(
                        onClick = {
                            if (!isRefreshing) {
                                scope.launch {
                                    isRefreshing = true
                                    refreshError = onRefreshCatalog(p.id)
                                    isRefreshing = false
                                }
                            }
                        },
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = "Refresh models",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            if (refreshError != null) {
                Text(
                    refreshError!!.take(80),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // Bounded height: a wrap-content LazyColumn inside a bottom sheet
            // collapses and its drags fight the sheet's dismiss gesture.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(430.dp)
                    .padding(top = 6.dp),
            ) {
                val provider = listedProvider
                if (provider == null) {
                    item {
                        Text(
                            "Add a provider first. The model list follows from it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                } else {
                    item(key = "autofetch") {
                        // Fetch once per sheet open when the catalog is empty.
                        LaunchedEffect(provider.id) {
                            if (listedCatalog.isEmpty() && !isRefreshing) {
                                isRefreshing = true
                                refreshError = onRefreshCatalog(provider.id)
                                isRefreshing = false
                            }
                        }
                    }
                    if (isRefreshing && listedCatalog.isEmpty()) {
                        item(key = "reloading") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "Reloading models…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (query.isNotBlank() && visibleRows.none { it.id.equals(query.trim(), ignoreCase = true) }) {
                        item(key = "inline-custom-${query.trim()}") {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = scheme.primaryContainer.copy(alpha = 0.6f),
                                border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        val customName = query.trim()
                                        onAddCustomModel(provider.id, customName, null)
                                        onSelect(provider.id, customName)
                                        onDismiss()
                                    },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Add,
                                        contentDescription = null,
                                        tint = scheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Use custom model",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.primary,
                                        )
                                        Text(
                                            query.trim(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    items(visibleRows.size, key = { "${provider.id}-${visibleRows[it].id}" }) { index ->
                        val entry = visibleRows[index]
                        val isSelected = provider.id == (browseProviderId ?: activeProviderId) &&
                            entry.id == (if (browseProviderId == null) effective else listedModel)
                        ModelRow(
                            id = entry.id,
                            thinking = entry.reasoning ?: reasoningCapable(entry.id),
                            known = entry.reasoning != null,
                            selected = isSelected,
                            isCustom = entry.custom,
                            ctx = ctxLabel(
                                entry.contextTokens
                                    ?: ModelsDev.entry(devKey, entry.id)?.contextTokens,
                            ),
                            onClick = {
                                onSelect(provider.id, entry.id)
                                onDismiss()
                            },
                            onDelete = if (entry.custom) {
                                { onDeleteCustomModel(provider.id, entry.id) }
                            } else null,
                        )
                    }
                }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = { showAddCustomDialog = true }) {
                            Text("+ Custom model…")
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onDismiss(); onManageProviders() }) {
                            Text("Manage providers…")
                        }
                    }
                }
            }
        }
    }

    if (showAddCustomDialog && listedProvider != null) {
        AddCustomModelDialog(
            providerName = listedProvider.name,
            onDismiss = { showAddCustomDialog = false },
            onAdd = { modelId, reasoning ->
                onAddCustomModel(listedProvider.id, modelId, reasoning)
                onSelect(listedProvider.id, modelId)
                onDismiss()
            },
        )
    }
}

@Composable
private fun ModelRow(
    id: String,
    thinking: Boolean,
    known: Boolean,
    selected: Boolean,
    ctx: String?,
    isCustom: Boolean = false,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        color = if (selected) scheme.secondaryContainer else scheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = if (selected) BorderStroke(1.dp, scheme.primary.copy(alpha = 0.28f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        id,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isCustom) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = scheme.tertiaryContainer,
                        ) {
                            Text(
                                "custom",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                val sub = listOfNotNull(
                    if (thinking) "thinking" else null,
                    ctx,
                ).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (thinking) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
            }
            if (onDelete != null) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Remove custom model",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = scheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun AddCustomModelDialog(
    providerName: String,
    onDismiss: () -> Unit,
    onAdd: (modelId: String, reasoning: Boolean?) -> Unit,
) {
    var modelId by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    val clean = modelId.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Add any model ID supported by $providerName.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Model ID") },
                    placeholder = { Text("e.g. meta-llama/llama-3.3-70b") },
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
                        Text("Reasoning model", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Enables thinking level ladder for this model",
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
                Text("Add & Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/** Compact context-window label for picker rows ("200K ctx", "1M ctx", "1B ctx"). */
private fun ctxLabel(tokens: Long?): String? = when {
    tokens == null || tokens <= 0 -> null
    tokens >= 1_000_000_000 -> "${tokens / 1_000_000_000}B ctx"
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M ctx"
    tokens >= 1_000 -> "${tokens / 1_000}K ctx"
    else -> "$tokens ctx"
}
