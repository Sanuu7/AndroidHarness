package com.androidharness.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.llm.ProviderApiKey
import com.androidharness.app.llm.ProviderKeyCheck
import com.androidharness.app.llm.ProviderKeyPool
import com.androidharness.app.tools.SearchKeyCheck
import com.androidharness.app.tools.SearchProviders
import com.androidharness.app.ui.common.SecureScreenEffect
import com.androidharness.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun descriptionFor(id: String): Int = when (id) {
    "marginalia" -> R.string.ws_desc_marginalia
    "brave" -> R.string.ws_desc_brave
    "serper" -> R.string.ws_desc_serper
    "serpapi" -> R.string.ws_desc_serpapi
    "tavily" -> R.string.ws_desc_tavily
    "linkup" -> R.string.ws_desc_linkup
    "langsearch" -> R.string.ws_desc_langsearch
    "jina" -> R.string.ws_desc_jina
    "firecrawl" -> R.string.ws_desc_firecrawl
    else -> R.string.ws_desc_keyless
}

/** Where one provider stands, which decides its pill and whether search can use it. */
private enum class Readiness { OFF, FREE, KEYS, SHARED, NEEDS_KEY }

private fun readiness(id: String, on: Boolean, pool: ProviderKeyPool?): Readiness {
    val info = SearchProviders.info(id)
    return when {
        !on -> Readiness.OFF
        info == null -> Readiness.FREE
        (pool?.keys?.count { it.enabled } ?: 0) > 0 -> Readiness.KEYS
        info.sharedKey != null -> Readiness.SHARED
        else -> Readiness.NEEDS_KEY
    }
}

private fun Readiness.usable() = this == Readiness.FREE || this == Readiness.KEYS || this == Readiness.SHARED

/**
 * Web search settings. Every provider that is on and ready takes part in
 * search, and the agent picks between them per call. A card on top says which
 * ones that is; each row below has its own switch and opens its keys in place.
 */
@Composable
internal fun WebSearchSettingsSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val disabled = settings.disabledSearchProviders
    var keyEpoch by remember { mutableIntStateOf(0) }
    // Every provider's saved pool, so each row can show its own key count.
    val pools = remember(keyEpoch) {
        SearchProviders.apiIds.associateWith { id -> runCatching { container.keys.searchKeyPool(id) }.getOrDefault(ProviderKeyPool()) }
    }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val states = SearchProviders.ids.associateWith { readiness(it, it !in disabled, pools[it]) }

    var order = 0
    SearchOverviewCard(
        ready = SearchProviders.ids.filter { states[it]?.usable() == true },
        modifier = Modifier.settingsAnchor(R.string.settings_search_web_backend_label).settingsEntrance(order++),
    )

    val groups = listOf(
        R.string.ws_group_free to listOf(SearchProviders.KEYLESS) + SearchProviders.apis.filter { it.kind == SearchProviders.Kind.FREE }.map { it.id },
        R.string.ws_group_engines to SearchProviders.apis.filter { it.kind == SearchProviders.Kind.ENGINE }.map { it.id },
        R.string.ws_group_ai to SearchProviders.apis.filter { it.kind == SearchProviders.Kind.AI }.map { it.id },
    )
    groups.forEach { (label, ids) ->
        SettingsSectionLabel(stringResource(label), Modifier.settingsEntrance(order++))
        SettingsGroup(Modifier.settingsEntrance(order++)) {
            ids.forEach { id ->
                val on = id !in disabled
                pressItem(selected = open == id, onClick = { open = if (open == id) null else id }) { pressed ->
                    ProviderRow(
                        container, id, on, states.getValue(id), pressed, open == id, pools[id] ?: ProviderKeyPool(),
                        onToggle = { scope.launch { container.settings.setSearchProviderEnabled(id, it) } },
                        onKeysChanged = { keyEpoch++ },
                    )
                }
            }
        }
    }
}

/** How many providers web search can use right now, and their names. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchOverviewCard(ready: List<String>, modifier: Modifier) {
    val motion = MaterialTheme.motionScheme
    val enterMove = motion.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val exitMove = motion.fastSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val enterFade = motion.defaultEffectsSpec<Float>()
    val exitFade = motion.fastEffectsSpec<Float>()
    val chipIn = motion.fastSpatialSpec<Float>()
    SettingsPanel(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp).animateContentSize(motion.defaultSpatialSpec()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SettingsMorphBadge(seed = ready.size * 3 + 1, pressed = false, size = 52.dp,
                    tone = if (ready.isEmpty()) SettingsTone.NEUTRAL else SettingsTone.PRIMARY) { tint ->
                    Icon(Icons.Rounded.TravelExplore, null, Modifier.size(26.dp), tint = tint)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ws_overview_label), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AnimatedContent(
                        ready.size,
                        transitionSpec = {
                            val up = targetState > initialState
                            (slideInVertically(enterMove) { if (up) it / 2 else -it / 2 } + fadeIn(enterFade)) togetherWith
                                (slideOutVertically(exitMove) { if (up) -it / 2 else it / 2 } + fadeOut(exitFade)) using
                                SizeTransform(clip = false)
                        },
                        label = "searchProviderCount",
                    ) { count ->
                        Text(
                            if (count == 0) stringResource(R.string.ws_overview_none)
                            else pluralStringResource(R.plurals.ws_overview_count, count, count),
                            style = MaterialTheme.typography.headlineSmallEmphasized,
                        )
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SearchProviders.ids.forEach { id ->
                    AnimatedVisibility(id in ready, enter = scaleIn(chipIn) + fadeIn(enterFade), exit = scaleOut(chipIn) + fadeOut(exitFade)) {
                        SettingsStatusPill(SearchProviders.name(id) ?: stringResource(R.string.ws_keyless_name), ok = true)
                    }
                }
            }
            Text(
                stringResource(if (ready.isEmpty()) R.string.ws_overview_off else R.string.ws_overview_auto),
                style = MaterialTheme.typography.bodyMedium,
                color = if (ready.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One provider: its switch and state, and its keys once the row is opened. */
@Composable
private fun ProviderRow(
    container: AppContainer,
    id: String,
    on: Boolean,
    state: Readiness,
    pressed: Boolean,
    open: Boolean,
    pool: ProviderKeyPool,
    onToggle: (Boolean) -> Unit,
    onKeysChanged: () -> Unit,
) {
    val info = SearchProviders.info(id)
    val name = info?.name ?: stringResource(R.string.ws_keyless_name)
    val keys = pool.keys.count { it.enabled }
    val motion = MaterialTheme.motionScheme
    val dim by animateFloatAsState(if (on) 1f else 0.62f, motion.defaultEffectsSpec(), label = "searchRowDim")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsMorphBadge(
                seed = SearchProviders.ids.indexOf(id) * 3 + 1,
                pressed = pressed || open,
                tone = if (state.usable()) SettingsTone.PRIMARY else SettingsTone.NEUTRAL,
                modifier = Modifier.alpha(dim),
            ) { tint ->
                if (info == null) Icon(Icons.Rounded.Public, null, Modifier.size(22.dp), tint = tint)
                else Text(name.take(1), style = MaterialTheme.typography.titleMediumEmphasized, color = tint)
            }
            Column(Modifier.weight(1f).alpha(dim), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    AnimatedContent(
                        state to keys,
                        transitionSpec = { (scaleIn(motion.fastSpatialSpec()) + fadeIn()) togetherWith (scaleOut(motion.fastSpatialSpec()) + fadeOut()) },
                        label = "searchRowState",
                    ) { (s, n) ->
                        when (s) {
                            Readiness.OFF -> SettingsStatusPill(stringResource(R.string.ws_status_off), ok = false)
                            Readiness.FREE -> SettingsStatusPill(stringResource(R.string.ws_status_free), ok = true)
                            Readiness.KEYS -> SettingsStatusPill(pluralStringResource(R.plurals.ws_row_keys, n, n), ok = true)
                            Readiness.SHARED -> SettingsStatusPill(stringResource(R.string.ws_status_shared_key), ok = true)
                            Readiness.NEEDS_KEY -> SettingsStatusPill(stringResource(R.string.ws_status_no_key), ok = false)
                        }
                    }
                }
                Text(stringResource(descriptionFor(id)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val switchLabel = stringResource(R.string.ws_switch_description, name)
            Switch(on, onCheckedChange = onToggle, modifier = Modifier.semantics { contentDescription = switchLabel })
        }
        AnimatedVisibility(
            open,
            enter = expandVertically(motion.defaultSpatialSpec(), expandFrom = Alignment.Top) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec(), shrinkTowards = Alignment.Top) + fadeOut(motion.fastEffectsSpec()),
        ) {
            ProviderPanel(container, id, state, pool, onKeysChanged)
        }
    }
}

/** What a provider needs, and its key editor. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ProviderPanel(
    container: AppContainer,
    provider: String,
    state: Readiness,
    saved: ProviderKeyPool,
    onKeysChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val info = SearchProviders.info(provider)
    val name = info?.name ?: stringResource(R.string.ws_keyless_name)
    // Null while the key list is closed; the pasted keys are only on screen while it is open.
    var draft by remember(provider) { mutableStateOf<ProviderKeyPool?>(null) }
    var saving by remember(provider) { mutableStateOf(false) }
    var status by remember(provider) { mutableStateOf<String?>(null) }
    var confirmRemove by remember(provider) { mutableStateOf(false) }
    val enabled = saved.keys.count { it.enabled }
    SecureScreenEffect(container, draft != null)
    val motion = MaterialTheme.motionScheme
    val enterFade = motion.defaultEffectsSpec<Float>()
    val exitFade = motion.fastEffectsSpec<Float>()

    fun openKeyPage() {
        info?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.keyUrl))) } }
    }

    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp).animateContentSize(motion.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            when {
                state == Readiness.OFF -> stringResource(R.string.ws_panel_off, name)
                info == null -> stringResource(R.string.ws_panel_keyless)
                enabled > 0 -> if (saved.rotate) pluralStringResource(R.plurals.ws_summary_keys, enabled, enabled)
                    else pluralStringResource(R.plurals.ws_summary_keys_ordered, enabled, enabled)
                info.sharedKey != null -> stringResource(R.string.ws_panel_shared_key)
                else -> stringResource(R.string.ws_panel_no_key, name)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (info != null) AnimatedContent(
            draft != null,
            transitionSpec = { fadeIn(enterFade) togetherWith fadeOut(exitFade) },
            label = "searchKeyMode",
        ) { editing ->
            if (editing) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.search_keys_rotation_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                draft?.let { pool ->
                    ProviderKeysEditor(pool, onChange = { draft = it; status = null }, enabled = !saving,
                        onTest = { key -> SearchKeyCheck.check(provider, key) },
                        testHint = stringResource(R.string.ws_test_hint))
                }
                Text(stringResource(R.string.ws_key_storage, info.name), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !saving, onClick = {
                        val pool = draft ?: return@Button
                        pool.error()?.let { status = it; return@Button }
                        saving = true
                        status = null
                        scope.launch {
                            // Only keys that are new or changed cost a check request.
                            val error = pool.keys.withIndex().firstNotNullOfOrNull { (index, key) ->
                                if (!key.enabled || saved.keys.any { it.value == key.value }) null
                                else (SearchKeyCheck.check(provider, key.value) as? ProviderKeyCheck.Result.Failed)
                                    ?.let { context.getString(R.string.search_key_check_failed, index + 1, it.message) }
                            }
                            if (error == null) {
                                val stored = runCatching { withContext(Dispatchers.IO) { container.keys.putSearchKeyPool(provider, pool) } }
                                if (stored.isSuccess) {
                                    draft = null
                                    status = context.getString(R.string.search_keys_verified_and_saved)
                                    onKeysChanged()
                                } else status = stored.exceptionOrNull()?.message
                            } else status = error
                            saving = false
                        }
                    }) {
                        if (saving) {
                            LoadingIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                            Text(stringResource(R.string.ws_saving), Modifier.padding(start = 8.dp))
                        } else Text(stringResource(R.string.ui_save_and_check))
                    }
                    TextButton(enabled = !saving, onClick = { draft = null; status = null }) {
                        Text(stringResource(R.string.settings_action_cancel))
                    }
                }
            } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (saved.keys.isNotEmpty()) {
                    FilledTonalButton(onClick = { draft = saved; status = null }) {
                        Icon(Icons.Rounded.Key, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.search_manage_keys), Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = ::openKeyPage) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                        Text(stringResource(R.string.ws_get_key), Modifier.padding(start = 8.dp))
                    }
                    TextButton(onClick = { confirmRemove = true }) {
                        Text(stringResource(R.string.ws_remove_all), color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Button(onClick = ::openKeyPage) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                        Text(stringResource(R.string.ws_get_key), Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = { draft = ProviderKeyPool(listOf(ProviderApiKey(value = ""))); status = null }) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Text(stringResource(if (info.sharedKey != null) R.string.ws_add_own_key else R.string.ws_add_key),
                            Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        AnimatedVisibility(status != null, enter = fadeIn() + slideInVertically { -it / 3 }, exit = fadeOut()) {
            Text(status.orEmpty(), style = MaterialTheme.typography.bodySmall,
                color = if (status == context.getString(R.string.search_keys_verified_and_saved)) LocalStatusColors.current.success
                    else MaterialTheme.colorScheme.error)
        }
    }

    if (confirmRemove && info != null) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text(stringResource(R.string.ws_remove_title, info.name)) },
        text = { Text(stringResource(R.string.ws_remove_body, info.name)) },
        confirmButton = {
            TextButton(onClick = {
                confirmRemove = false
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { container.keys.removeSearchApiKey(provider) } }
                    onKeysChanged()
                }
            }) { Text(stringResource(R.string.settings_action_remove), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}
