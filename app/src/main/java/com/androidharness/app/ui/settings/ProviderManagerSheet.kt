package com.androidharness.app.ui.settings

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import com.androidharness.app.ui.common.ProviderMark
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
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Providers",
                                style = MaterialTheme.typography.titleMediumEmphasized,
                            )
                            Text(
                                "Choose the service that powers this chat",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Close")
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            editing = null
                            showForm = true
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 12.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add provider")
                    }
                    // Bounded height: a wrap-content LazyColumn inside a bottom
                    // sheet collapses and its drags fight the dismiss gesture.
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(providers, key = { _, p -> p.id }) { index, provider ->
                            val active = provider.id == activeProviderId
                            Surface(
                                onClick = {
                                    onSetActive(provider.id)
                                    onDismiss()
                                },
                                color = if (active) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                                ) {
                                    ProviderMark(size = 40.dp)
                                    Spacer(Modifier.width(11.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                provider.name,
                                                style = MaterialTheme.typography.titleSmallEmphasized,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            if (active) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                    shape = RoundedCornerShape(50),
                                                ) {
                                                    Text(
                                                        "Active",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            if (provider.id == com.androidharness.app.llm.HarnessProvider.ID) provider.model
                                            else "${provider.type.endpointPath} · ${provider.model}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (active) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.76f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (active) {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = "Active",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(19.dp),
                                        )
                                    }
                                    if (provider.id != com.androidharness.app.llm.HarnessProvider.ID) {
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
                                }
                            }
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
