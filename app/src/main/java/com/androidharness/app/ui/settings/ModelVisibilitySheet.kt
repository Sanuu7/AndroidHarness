package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassModalBottomSheet as ModalBottomSheet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.data.ModelVisibility
import com.androidharness.app.llm.*
import com.androidharness.app.ui.common.ModelReasoningIcon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton

/** Editing is a draft. Search and provider switches never discard checks; Save commits every edited provider. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelVisibilitySheet(
    providers: List<ProviderConfig>, catalogs: Map<String, List<ModelEntry>>, initialProviderId: String?,
    onDismiss: () -> Unit, visibility: ModelVisibility = ModelVisibility.get(LocalContext.current),
    onRefreshCatalog: suspend (String) -> String? = { null },
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val saved by visibility.state.collectAsStateWithLifecycle()
    var providerId by rememberSaveable { mutableStateOf(initialProviderId) }
    var query by rememberSaveable { mutableStateOf("") }
    // Lists are saveable without a custom Android Parcelable serializer.
    var draftIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var draftValues by rememberSaveable { mutableStateOf(listOf<List<String>>()) }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val provider = providers.firstOrNull { it.id == providerId } ?: providers.firstOrNull()
    val available = provider?.let { ProviderModelSearch.models(it, catalogs[it.id].orEmpty()) }.orEmpty()
    val selected = provider?.let { p ->
        draftIds.indexOf(p.id).takeIf { it >= 0 }?.let { draftValues[it].toSet() }
            ?: saved.selection(p.id, available.map { it.id })
    }.orEmpty()
    val results = available.filter { provider != null && ProviderModelSearch.matches(provider, it, query) }
    fun update(ids: Set<String>) {
        val id = provider?.id ?: return
        val index = draftIds.indexOf(id)
        if (index < 0) { draftIds = draftIds + id; draftValues = draftValues + listOf(ids.toList()) }
        else draftValues = draftValues.mapIndexed { i, old -> if (i == index) ids.toList() else old }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * .9f).dp)
            .navigationBarsPadding().imePadding()) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_provider_manage_models), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, uiContext.getString(R.string.ui_close_model_management)) }
                    }
                    Text(stringResource(R.string.settings_models_visibility_subtitle), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(providers, key = { it.id }) { p ->
                            FilterChip(selected = p.id == provider?.id, onClick = { providerId = p.id; query = "" }, label = { Text(p.name) })
                        }
                    }
                    OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.settings_models_visibility_search)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_models_visibility_selected, available.count { it.id in selected }.toString(), available.size.toString()), style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f))
                        val checked = results.isNotEmpty() && results.all { it.id in selected }
                        TextButton(enabled = results.isNotEmpty(), onClick = {
                            val ids = results.map { it.id }.toSet()
                            update(if (checked) selected - ids else selected + ids)
                        }) { Text(uiContext.getString(R.string.ui_results, (if (checked) uiContext.getString(R.string.ui_clear) else uiContext.getString(R.string.settings_provider_select)).toString(), (results.size).toString())) }
                    }
                    if (provider != null) TextButton(enabled = !refreshing, onClick = {
                        scope.launch {
                            refreshing = true
                            try { error = onRefreshCatalog(provider.id) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (_: Exception) { error = uiContext.getString(R.string.ui_could_not_refresh_models_try_again) }
                            finally { refreshing = false }
                        }
                    }) { Text(if (refreshing) uiContext.getString(R.string.ui_refreshing) else uiContext.getString(R.string.settings_chatgpt_refresh_models)) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                items(results, key = { "${provider?.id}:${it.id}" }) { entry ->
                    val showCd = stringResource(R.string.settings_models_visibility_show_cd, entry.displayName ?: entry.id)
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .toggleable(entry.id in selected, role = Role.Checkbox, onValueChange = { checked ->
                            update(if (checked) selected + entry.id else selected - entry.id)
                        }).semantics { contentDescription = showCd }
                        .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(entry.id in selected, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.displayName ?: entry.id, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (entry.displayName != null) Text(entry.id, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        ModelReasoningIcon(entry, provider?.type)
                    }
                }
                if (results.isEmpty()) item { Text(stringResource(R.string.settings_models_visibility_empty), modifier = Modifier.padding(vertical = 24.dp)) }
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.settings_models_visibility_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = {
                    provider?.takeIf { it.id !in draftIds }?.let { visibility.save(it.id, selected) }
                    draftIds.forEachIndexed { i, id ->
                        if (providers.any { it.id == id }) visibility.save(id, draftValues[i].toSet())
                    }
                    onDismiss()
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_models_visibility_save)) }
            }
        }
    }
}
