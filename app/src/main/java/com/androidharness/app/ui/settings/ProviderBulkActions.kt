package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.llm.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

internal fun deletableProvider(provider: ProviderConfig) = provider.id != com.androidharness.app.llm.HarnessProvider.ID &&
    !com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(provider.id) &&
    !com.androidharness.app.local.LocalModelCatalog.isLocal(provider.id)

@Stable
internal class ProviderSelectionState(selecting: Boolean = false, ids: Set<String> = emptySet()) {
    var selecting by mutableStateOf(selecting)
    var ids by mutableStateOf(ids)
    var deleting by mutableStateOf<List<String>?>(null)
    var reviewing by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    fun toggle(id: String) { ids = if (id in ids) ids - id else ids + id }
    fun start(id: String) { selecting = true; ids = ids + id }
    fun done() { selecting = false; ids = emptySet(); error = null }
    fun requestDelete(ids: List<String>) { deleting = ids; reviewing = false; error = null }
}

@Composable
internal fun rememberProviderSelection(): ProviderSelectionState = rememberSaveable(saver = listSaver(
    save = { listOf(if (it.selecting) "yes" else "no") + it.ids },
    restore = { ProviderSelectionState(it.first() == "yes", it.drop(1).toSet()) },
)) { ProviderSelectionState() }

/** Confirmation is shared by the full settings screen and every in-chat provider manager. */
@Composable
internal fun ProviderBulkActions(
    state: ProviderSelectionState, providers: List<ProviderConfig>,
    onEnabledChange: suspend (String, Boolean) -> Unit,
    onDelete: suspend (String) -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val selected = providers.filter { it.id in state.ids }
    if (state.selecting) {
        Text(stringResource(R.string.settings_bulk_selected_count, selected.size.toString()), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true to uiContext.getString(R.string.ui_enable_1042), false to uiContext.getString(R.string.settings_action_disable)).forEach { (enabled, label) ->
                OutlinedButton(enabled = selected.isNotEmpty() && !state.busy, onClick = {
                    scope.launch {
                        state.busy = true; state.error = null
                        try { selected.forEach { onEnabledChange(it.id, enabled) } }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { state.error = uiContext.getString(R.string.ui_some_providers_could_not_be_updated_review) }
                        finally { state.busy = false }
                    }
                }) { Text(label) }
            }
            OutlinedButton(enabled = selected.isNotEmpty() && selected.all(::deletableProvider) && !state.busy,
                onClick = { state.requestDelete(selected.map { it.id }) }) { Text(stringResource(R.string.settings_action_delete), color = MaterialTheme.colorScheme.error) }
        }
        if (selected.any { !deletableProvider(it) }) Text(stringResource(R.string.settings_bulk_disable_note),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    state.deleting?.let { requested ->
        val targets = providers.filter { it.id in requested }
        val valid = targets.size == requested.size && targets.all(::deletableProvider)
        val multiple = requested.size > 1
        AlertDialog(onDismissRequest = { if (!state.busy) state.deleting = null },
            title = { Text(
                when {
                    state.reviewing -> stringResource(R.string.settings_bulk_delete_all_title, requested.size.toString())
                    multiple -> stringResource(R.string.settings_bulk_delete_count_title, requested.size.toString())
                    else -> stringResource(
                        R.string.settings_bulk_delete_one_title,
                        targets.firstOrNull()?.name ?: stringResource(R.string.settings_bulk_delete_fallback_name),
                    )
                },
            ) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.settings_bulk_delete_note))
                    if (state.reviewing || !multiple) targets.forEach { Text(it.name, style = MaterialTheme.typography.bodyMedium) }
                    if (!valid) Text(stringResource(R.string.settings_bulk_list_changed), color = MaterialTheme.colorScheme.error)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = valid && !state.busy, onClick = {
                    if (multiple && !state.reviewing) state.reviewing = true
                    else scope.launch {
                        state.busy = true; state.error = null
                        val remaining = targets.map { it.id }.toMutableSet()
                        try {
                            targets.forEach { provider ->
                                onDelete(provider.id)
                                remaining.remove(provider.id)
                                state.ids = state.ids - provider.id
                            }
                            state.deleting = null
                            if (state.ids.isEmpty()) state.done()
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            state.deleting = null
                            state.selecting = true
                            state.ids = state.ids + remaining
                            state.error = uiContext.getString(R.string.ui_some_providers_could_not_be_deleted_remaining)
                        } finally { state.busy = false }
                    }
                }) { Text(if (state.busy) uiContext.getString(R.string.ui_deleting) else if (multiple && !state.reviewing) uiContext.getString(R.string.settings_action_continue) else if (multiple) uiContext.getString(R.string.ui_delete_all) else uiContext.getString(R.string.ui_delete_provider)) }
            }, dismissButton = { TextButton(enabled = !state.busy, onClick = { state.deleting = null }) {
                Text(if (state.reviewing) uiContext.getString(R.string.ui_edit_selection) else uiContext.getString(R.string.settings_action_cancel))
            } })
    }
}
