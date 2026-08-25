package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.launch

/**
 * Model picker for ONE provider (the active one, or [browseProviderId] when
 * browsing from the Providers screen): current selection + thinking tiers +
 * searchable model list with thinking/context badges. Switching providers is
 * a deliberate hop ("Switch provider" → manager sheet), never an accidental
 * scroll into another provider's models.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelPickerSheet(
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    activeModel: String?,
    catalogs: Map<String, List<ModelEntry>>,
    thinkingLevel: ThinkingLevel,
    onDismiss: () -> Unit,
    onSelect: (providerId: String, model: String?) -> Unit,
    onRefreshCatalog: suspend (providerId: String) -> String?,
    onSetThinking: (ThinkingLevel) -> Unit,
    onManageProviders: () -> Unit,
    /** When set (Providers screen "browse"), list this provider instead of the active one. */
    browseProviderId: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var thinkOnly by remember { mutableStateOf(false) }

    val listedId = browseProviderId ?: activeProviderId
    val listedProvider = providers.firstOrNull { it.id == listedId }
    val activeProvider = providers.firstOrNull { it.id == activeProviderId }
    val effective = activeModel?.takeIf { it.isNotBlank() } ?: activeProvider?.model
    val listedModel = if (browseProviderId == null) effective else listedProvider?.model
    val selectedEntry = catalogs[listedId]?.find { it.id == listedModel }

    // models.dev (fresh, per-model vocabulary) first; provider-reported
    // "not a reasoner" still wins over everything.
    val devKey = ModelsDev.providerKeyFor(listedProvider?.baseUrl)
    var spec = com.androidharness.app.agent.ThinkingSpecs.forModel(
        if (browseProviderId == null) effective else listedModel,
        devKey,
    )
    if (selectedEntry?.reasoning == false) {
        spec = com.androidharness.app.agent.ThinkingSpecs.Spec(
            com.androidharness.app.agent.ThinkingSpecs.Style.NONE,
            listOf(ThinkingLevel.OFF),
        )
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
                Text("Model", style = MaterialTheme.typography.titleMediumEmphasized)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }

            // Current selection + prominent jump to provider management.
            if (activeProvider != null && browseProviderId == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Current",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                        Text(
                            "${activeProvider.name} · $effective",
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(onClick = { onDismiss(); onManageProviders() }) {
                        Text("Switch provider")
                    }
                }
            }

            // Only the model's native tiers render — unsupported levels are
            // hidden outright (per user decision, reversing the old dim-all).
            if (listedProvider != null) {
                val visible = com.androidharness.app.agent.ThinkingSpecs.visibleLevels(
                    if (browseProviderId == null) effective else listedModel,
                    devKey,
                )
                Text(
                    when {
                        spec.style == com.androidharness.app.agent.ThinkingSpecs.Style.NONE &&
                            spec.levels.size == 1 -> "This model doesn't support thinking"
                        spec.style == com.androidharness.app.agent.ThinkingSpecs.Style.NONE ->
                            "Thinking — this model reasons on its own; no dial to send"
                        else -> "Thinking"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    visible.forEach { level ->
                        FilterChip(
                            selected = thinkingLevel == level,
                            onClick = { onSetThinking(level) },
                            label = {
                                Text(level.label, style = MaterialTheme.typography.labelSmall)
                            },
                        )
                    }
                }
                if (thinkingLevel != ThinkingLevel.OFF && thinkingLevel !in visible) {
                    Text(
                        "“${thinkingLevel.label}” isn't native to this model. " +
                            "Requests clamp to the nearest supported tier",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                if (spec.style == com.androidharness.app.agent.ThinkingSpecs.Style.EFFORT) {
                    val wireValues = visible
                        .filter { it != ThinkingLevel.OFF }
                        .mapNotNull {
                            com.androidharness.app.agent.ThinkingSpecs.effortWire(
                                if (browseProviderId == null) effective else listedModel,
                                it,
                                devKey,
                            )
                        }
                    if (wireValues.isNotEmpty()) {
                        Text(
                            "Sends reasoning_effort: ${wireValues.joinToString(" · ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = scheme.outlineVariant.copy(alpha = 0.5f))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search models") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
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
                Spacer(Modifier.weight(1f))
                listedProvider?.let { p ->
                    val catalog = catalogs[p.id].orEmpty()
                    Text(
                        if (catalog.isEmpty()) "models" else "${catalog.size} models",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    var refreshError by remember { mutableStateOf<String?>(null) }
                    IconButton(onClick = {
                        scope.launch {
                            refreshError = onRefreshCatalog(p.id)
                        }
                    }) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "Refresh models",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
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
                            "Add a provider first — the model list follows from it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                } else {
                    val catalog = catalogs[provider.id].orEmpty()
                    item(key = "autofetch") {
                        // Fetch once per sheet open when the catalog is empty.
                        var error by remember { mutableStateOf<String?>(null) }
                        LaunchedEffect(provider.id) {
                            if (catalog.isEmpty()) {
                                error = onRefreshCatalog(provider.id)
                            }
                        }
                        error?.let {
                            Text(
                                it.take(80),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    val q = query.trim().lowercase()
                    val rows = buildList {
                        add(ModelEntry(provider.model, reasoning = null, contextTokens = null))
                        addAll(catalog.filter { it.id != provider.model })
                    }.distinctBy { it.id }
                        .filter { q.isBlank() || it.id.lowercase().contains(q) }
                        .filter { !thinkOnly || (it.reasoning ?: reasoningCapable(it.id)) }

                    items(rows.size, key = { "${provider.id}-${rows[it].id}" }) { index ->
                        val entry = rows[index]
                        val isSelected = provider.id == (browseProviderId ?: activeProviderId) &&
                            entry.id == (if (browseProviderId == null) effective else listedModel)
                        ModelRow(
                            id = entry.id,
                            default = entry.id == provider.model,
                            thinking = entry.reasoning ?: reasoningCapable(entry.id),
                            known = entry.reasoning != null,
                            selected = isSelected,
                            ctx = ctxLabel(
                                entry.contextTokens
                                    ?: ModelsDev.entry(devKey, entry.id)?.contextTokens,
                            ),
                            onClick = {
                                onSelect(provider.id, entry.id)
                                onDismiss()
                            },
                        )
                        // Clear separation between tap targets.
                        if (index < rows.lastIndex) {
                            HorizontalDivider(
                                color = scheme.outlineVariant.copy(alpha = 0.35f),
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
                item {
                    TextButton(onClick = { onDismiss(); onManageProviders() }) {
                        Text("Manage providers…")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    id: String,
    default: Boolean,
    thinking: Boolean,
    known: Boolean,
    selected: Boolean,
    ctx: String?,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                id,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                if (default) "saved default" else null,
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
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = scheme.primary)
        }
    }
}

/** Compact context-window label for picker rows ("200K ctx", "1M ctx"). */
private fun ctxLabel(tokens: Long?): String? = when {
    tokens == null -> null
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M ctx"
    tokens >= 1_000 -> "${tokens / 1_000}K ctx"
    else -> "$tokens ctx"
}
