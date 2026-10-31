package com.androidharness.app.ui.settings

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.local.*
import com.androidharness.app.ui.common.AppLinearProgressIndicator
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Reads the chip and the fastest core once. Runs on a worker thread because it opens a graphics context. */
internal fun readDeviceCapability(context: Context, profile: LocalDeviceProfile): LocalDeviceCapability {
    val telemetry = DeviceTelemetry(context)
    val hardware = runCatching { telemetry.hardware() }.getOrNull()
    val maxKhz = runCatching { telemetry.sample().cores.mapNotNull { it.maxKhz }.maxOrNull() }.getOrNull()
    val chip = ChipClassifier.classify(hardware?.manufacturer.orEmpty(), hardware?.chipset ?: Build.HARDWARE,
        Build.HARDWARE, hardware?.board ?: Build.BOARD, maxKhz, profile.cores)
    return LocalDeviceCapability(profile, chip)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalStorageCard(report: LocalStorageReport, freeBytes: Long, canClearPaused: Boolean, enabled: Boolean,
    onClearPaused: () -> Unit, onClearLeftovers: () -> Unit) {
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.local_storage_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.local_storage_summary, size(report.totalBytes), size(freeBytes)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val slices = listOf(
                Triple(R.string.local_storage_models, report.installedBytes, MaterialTheme.colorScheme.primary),
                Triple(R.string.local_storage_unfinished, report.pausedBytes, MaterialTheme.colorScheme.tertiary),
                Triple(R.string.local_storage_leftovers, report.leftoverBytes, MaterialTheme.colorScheme.error),
                Triple(R.string.local_storage_other, report.otherBytes, MaterialTheme.colorScheme.outline),
            ).filter { it.second > 0 }
            if (slices.isEmpty()) Text(stringResource(R.string.local_storage_empty), style = MaterialTheme.typography.bodySmall)
            else {
                Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    slices.forEach { (_, bytes, color) -> Box(Modifier.weight(bytes.toFloat()).fillMaxHeight().background(color)) }
                }
                slices.forEach { (label, bytes, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
                        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(size(bytes), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (report.models.isNotEmpty()) {
                HorizontalDivider()
                report.models.forEach { usage ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(usage.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(size(usage.installedBytes + usage.pausedBytes), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (canClearPaused || report.leftoverBytes > 0) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canClearPaused) OutlinedButton(onClick = onClearPaused, enabled = enabled) { Text(stringResource(R.string.local_storage_clear_unfinished)) }
                if (report.leftoverBytes > 0) OutlinedButton(onClick = onClearLeftovers, enabled = enabled) { Text(stringResource(R.string.local_storage_clean_leftovers)) }
            }
        }
    }
}

@Composable
internal fun suggestionKindLabel(kind: SuggestionKind) = stringResource(when (kind) {
    SuggestionKind.BEST -> R.string.local_suggest_best
    SuggestionKind.CODING -> R.string.local_suggest_coding
    SuggestionKind.FAST -> R.string.local_suggest_fast
    SuggestionKind.LIGHT -> R.string.local_suggest_light
})

@Composable
internal fun suggestionReason(kind: SuggestionKind) = stringResource(when (kind) {
    SuggestionKind.BEST -> R.string.local_suggest_reason_best
    SuggestionKind.CODING -> R.string.local_suggest_reason_coding
    SuggestionKind.FAST -> R.string.local_suggest_reason_fast
    SuggestionKind.LIGHT -> R.string.local_suggest_reason_light
})

@Composable
internal fun SuggestionsHeader(capability: LocalDeviceCapability?, hasSuggestions: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.local_suggested_title), style = MaterialTheme.typography.titleSmall)
        if (capability == null) AppLinearProgressIndicator(Modifier.fillMaxWidth())
        else {
            Text(stringResource(R.string.local_suggested_device, capability.chip.label.ifBlank { Build.MODEL },
                size(capability.profile.totalRam), stringResource(when (capability.chip.tier) {
                    ChipTier.FLAGSHIP -> R.string.local_chip_flagship
                    ChipTier.UPPER_MID -> R.string.local_chip_upper_mid
                    ChipTier.MID -> R.string.local_chip_mid
                    ChipTier.ENTRY -> R.string.local_chip_entry
                    ChipTier.UNKNOWN -> R.string.local_chip_unknown
                })), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!hasSuggestions) Text(stringResource(R.string.local_suggest_none), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Search box results from Hugging Face for one weight format. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HubSearchPanel(manager: LocalModelManager, format: LocalModelFormat, device: LocalDeviceProfile,
    query: String, onOpen: (HubModel) -> Unit) {
    var sort by rememberSaveable { mutableStateOf(HubSort.POPULAR) }
    var onlyFit by rememberSaveable { mutableStateOf(true) }
    var limit by rememberSaveable(format, query, sort) { mutableIntStateOf(PAGE) }
    var results by remember(format) { mutableStateOf<List<HubModel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val term = query.trim()
    LaunchedEffect(format, term, sort, limit) {
        // Typing restarts this effect, so the request only goes out once the text has settled.
        if (term.isNotEmpty()) delay(450)
        loading = true; error = null
        try { results = manager.searchHub(term, format, sort, limit) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message.orEmpty() }
        finally { loading = false }
    }
    val shown = results.filter { !onlyFit || it.likelyFits(device) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HubSort.entries.forEach { option ->
                FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(stringResource(when (option) {
                    HubSort.POPULAR -> R.string.local_hub_sort_popular
                    HubSort.TRENDING -> R.string.local_hub_sort_trending
                    HubSort.NEWEST -> R.string.local_hub_sort_newest
                    HubSort.LIKED -> R.string.local_hub_sort_liked
                })) })
            }
            FilterChip(selected = onlyFit, onClick = { onlyFit = !onlyFit }, label = { Text(stringResource(R.string.local_hub_only_fit)) })
        }
        if (format == LocalModelFormat.SAFETENSORS) Text(stringResource(R.string.local_hub_note_safetensors),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(stringResource(R.string.local_hub_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (loading) AppLinearProgressIndicator(Modifier.fillMaxWidth())
        else if (error == null && shown.isEmpty()) Text(stringResource(R.string.local_hub_empty), style = MaterialTheme.typography.bodySmall)
        shown.forEach { HubResultCard(it, device) { onOpen(it) } }
        if (!loading && results.size >= limit) TextButton(onClick = { limit += PAGE }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.local_hub_load_more))
        }
    }
}

private const val PAGE = 25

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HubResultCard(model: HubModel, device: LocalDeviceProfile, onOpen: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(model.name, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.local_hub_by, model.author), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val facts = buildList {
                add(stringResource(R.string.local_hub_downloads, compactCount(model.downloads)))
                add(stringResource(R.string.local_hub_likes, compactCount(model.likes)))
                model.parameters?.let { add(stringResource(R.string.local_hub_params, compactCount(it))) }
                model.downloadBytes?.let { add(stringResource(R.string.local_hub_download_estimate, size(it))) }
            }
            Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (model.estimatedQ4Bytes != null) {
                    val fits = model.likelyFits(device)
                    Surface(shape = MaterialTheme.shapes.small,
                        color = if (fits) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer) {
                        Text(stringResource(if (fits) R.string.local_hub_fit else R.string.local_hub_too_large),
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                            color = if (fits) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
            FilledTonalButton(onClick = onOpen) { Text(stringResource(R.string.local_hub_view_files)) }
        }
    }
}
