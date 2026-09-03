package com.androidharness.app.ui.settings

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.llm.ModelsDev
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.endpointPath
import com.androidharness.app.ui.theme.fastEffectsSpec
import kotlinx.coroutines.launch

/**
 * Provider management without leaving the conversation: a fully-expanded
 * bottom sheet with two pages, a list (tap = activate + close; edit/delete
 * icons mirror the Providers screen) and the shared add/edit form
 * ([ProviderSheetContent]) reached with a forward/back slide. The full-screen
 * Providers destination still exists for bulk management from the drawer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderManagerSheet(
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    apiKey: (providerId: String) -> String?,
    onDismiss: () -> Unit,
    onSetActive: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSave: (
        existing: ProviderConfig?,
        name: String,
        type: ProviderType,
        baseUrl: String,
        model: String,
        apiKey: String,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var showForm by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Crossfade(
            targetState = showForm,
            animationSpec = fastEffectsSpec(),
            label = "provider manager page",
        ) { inForm ->
            if (!inForm) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .navigationBarsPadding(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Providers",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Close")
                        }
                    }
                    Text(
                        "Tap a row to make it active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    // Bounded height: a wrap-content LazyColumn inside a bottom
                    // sheet collapses and its drags fight the dismiss gesture.
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                    ) {
                        itemsIndexed(providers, key = { _, p -> p.id }) { index, provider ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSetActive(provider.id)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        provider.name,
                                        style = MaterialTheme.typography.titleSmallEmphasized,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${provider.type.endpointPath} · ${provider.model}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (provider.id == activeProviderId) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = "Active",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(onClick = {
                                    editing = provider
                                    showForm = true
                                }) {
                                    Icon(
                                        Icons.Outlined.Edit,
                                        contentDescription = "Edit",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { onDelete(provider.id) }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            if (index < providers.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                        item {
                            TextButton(onClick = {
                                editing = null
                                showForm = true
                            }) { Text("Add provider") }
                        }
                        item {
                            // The local model/thinking catalog (models.dev)
                            // drives which models and tiers the pickers show,
                            // this forces a fresh download instead of waiting
                            // for the weekly auto-refresh.
                            val context = LocalContext.current
                            var catalogBusy by remember { mutableStateOf(false) }
                            var catalogStatus by remember { mutableStateOf<String?>(null) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            catalogBusy = true
                                            val err = ModelsDev.refresh(context, force = true)
                                            catalogStatus = err
                                                ?: "Catalog updated: ${ModelsDev.speakableProviders().size} providers"
                                            catalogBusy = false
                                        }
                                    },
                                    enabled = !catalogBusy,
                                ) { Text(if (catalogBusy) "Updating catalog…" else "Update model catalog") }
                                catalogStatus?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (catalogStatus?.startsWith("Catalog updated") == true)
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.error,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // The form owns its step headers + back navigation; closing at
                // step one returns to the list page.
                ProviderSheetContent(
                    existing = editing,
                    existingKey = editing?.let { apiKey(it.id) },
                    onDismiss = { showForm = false },
                    onSave = { name, type, baseUrl, model, key ->
                        onSave(editing, name, type, baseUrl, model, key)
                        showForm = false
                        editing = null
                    },
                )
            }
        }
    }
}
