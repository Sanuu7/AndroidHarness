package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassAlertDialog as AlertDialog
import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.llm.debug.DebugCapture
import com.androidharness.app.llm.debug.DebugJson
import com.androidharness.app.llm.debug.DebugLogRepository
import com.androidharness.app.llm.debug.DebugTrace
import com.androidharness.app.llm.debug.DebugTurns
import com.androidharness.app.ui.common.ExpressiveDropdownMenu
import com.androidharness.app.ui.common.ExpressiveDropdownMenuItem
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.FilledTonalIconButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.theme.HarnessMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Entry point on the Troubleshooting page: the switch and the way in.
 *
 * Tapping the row opens the console; the switch decides whether anything is
 * recorded at all. Everything here is off after a fresh install.
 */
@Composable
internal fun ProviderDebugCard(container: AppContainer, onOpen: (SettingsPage) -> Unit) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val traces by DebugCapture.store.traces.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    SettingsPanel(Modifier.fillMaxWidth()) {
        Surface(onClick = { onOpen(SettingsPage.PROVIDER_DEBUG) }, color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ConsoleBadge(40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.settings_debug_title), style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium)
                    Text(
                        if (settings.debugConsoleEnabled && traces.isNotEmpty()) stringResource(R.string.settings_debug_count, traces.size)
                        else stringResource(R.string.settings_page_provider_debug_desc),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.debugConsoleEnabled, onCheckedChange = { on ->
                    applyDebugSettings(container, scope, on, settings.debugConsoleBodies, settings.debugConsolePersist)
                })
            }
        }
    }
}

private enum class OutcomeFilter(val label: Int) {
    ALL(R.string.debug_console_filter_all),
    FAILED(R.string.debug_console_filter_failed),
    RETRIED(R.string.debug_console_filter_retried),
    STREAMED(R.string.debug_console_filter_streamed),
}

private enum class ExportFormat(val extension: String, val mime: String) {
    TEXT("txt", "text/plain"),
    JSON("json", "application/json"),
}

/** The console itself: capture switches, filters, export, and the recorded turns. */
@Composable
internal fun ProviderDebugSection(container: AppContainer) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val live by DebugCapture.store.traces.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val persist = settings.debugConsoleEnabled && settings.debugConsolePersist

    var stored by remember { mutableStateOf<List<DebugTrace>>(emptyList()) }
    var chats by remember { mutableStateOf<List<DebugLogRepository.Chat>>(emptyList()) }
    var chatId by rememberSaveable { mutableStateOf<String?>(null) }
    var providerId by rememberSaveable { mutableStateOf<String?>(null) }
    var outcome by rememberSaveable { mutableStateOf(OutcomeFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var paused by remember { mutableStateOf(DebugCapture.paused) }
    var pendingChange by remember { mutableStateOf<Triple<Boolean, Boolean, Boolean>?>(null) }
    var pendingSave by remember { mutableStateOf<String?>(null) }
    var exportMenu by remember { mutableStateOf(false) }

    fun request(enabled: Boolean, bodies: Boolean, keep: Boolean) {
        if (!settings.debugConsoleConsent && (bodies || keep)) pendingChange = Triple(enabled, bodies, keep)
        else applyDebugSettings(container, scope, enabled, bodies, keep)
    }

    // The saved log only changes when a request starts or ends, so the row count is
    // enough of a trigger. A stream that is still running is read from memory below.
    LaunchedEffect(persist, live.size) {
        if (persist) {
            val since = System.currentTimeMillis() - DebugLogRepository.RETENTION_MS
            stored = container.debugLog.load(since = since)
            chats = container.debugLog.chats(since)
        } else {
            stored = emptyList()
            chats = emptyList()
        }
    }

    // Memory wins over the database: it holds the stream as it grows.
    val rows = remember(live, stored) {
        (live + stored).distinctBy { it.id }.sortedByDescending { it.startedAt }
    }
    val turns = remember(rows) { DebugTurns.group(rows) }
    val providers = remember(rows) {
        rows.filter { it.providerId.isNotBlank() }.distinctBy { it.providerId }
            .map { it.providerId to it.providerName.ifBlank { it.providerId } }
    }
    val visible = remember(turns, query, chatId, providerId, outcome) {
        turns.filter { turn ->
            val traces = turn.attempts.map { it.trace }
            val outcomeMatch = when (outcome) {
                OutcomeFilter.ALL -> true
                OutcomeFilter.FAILED -> turn.last.failed
                OutcomeFilter.RETRIED -> turn.retried
                OutcomeFilter.STREAMED -> traces.any { it.stream }
            }
            outcomeMatch &&
                (chatId == null || traces.any { DebugLogRepository.normalizeSession(it.sessionId) == chatId }) &&
                (providerId == null || traces.any { it.providerId == providerId }) &&
                (query.isBlank() || traces.any { it.matches(query.trim()) })
        }
    }
    val visibleTraces = remember(visible) { visible.flatMap { turn -> turn.attempts.map { it.trace } } }

    fun exportText(format: ExportFormat): String = when (format) {
        ExportFormat.TEXT -> visibleTraces.joinToString("\n\n----------\n\n") { it.asText() }
        ExportFormat.JSON -> DebugJson.export(visibleTraces)
    }

    // SAF write: the user picks where the file goes, and no storage permission is needed.
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        writePicked(context, scope, uri, pendingSave)
        pendingSave = null
    }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        writePicked(context, scope, uri, pendingSave)
        pendingSave = null
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CapturePanel(
            settings = settings,
            paused = paused,
            onEnabled = { request(it, settings.debugConsoleBodies, settings.debugConsolePersist) },
            onBodies = { request(true, it, settings.debugConsolePersist) },
            onPersist = { request(true, settings.debugConsoleBodies, it) },
            onPaused = {
                DebugCapture.paused = it
                paused = it
            },
        )

        if (rows.isEmpty()) {
            EmptyState(
                title = stringResource(if (settings.debugConsoleEnabled) R.string.debug_console_waiting_title else R.string.debug_console_off_title),
                body = stringResource(if (settings.debugConsoleEnabled) R.string.debug_console_waiting_body else R.string.debug_console_off_body),
            )
        } else {
            StatTiles(
                requests = rows.size,
                failed = rows.count { it.failed },
                retried = turns.count { it.retried },
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = {
                        Text(stringResource(R.string.debug_console_search), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = if (query.isEmpty()) null else {
                        { IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.debug_console_clear_search)) } }
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f),
                )
                Box {
                    FilledTonalIconButton(onClick = { exportMenu = true }, enabled = visibleTraces.isNotEmpty()) {
                        Icon(Icons.Rounded.IosShare, stringResource(R.string.debug_console_export))
                    }
                    ExpressiveDropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                        ExpressiveDropdownMenuItem(text = { Text(stringResource(R.string.debug_console_share_text)) }, onClick = {
                            exportMenu = false
                            shareExport(context, scope, exportText(ExportFormat.TEXT), ExportFormat.TEXT)
                        })
                        ExpressiveDropdownMenuItem(text = { Text(stringResource(R.string.debug_console_share_json)) }, onClick = {
                            exportMenu = false
                            shareExport(context, scope, exportText(ExportFormat.JSON), ExportFormat.JSON)
                        })
                        ExpressiveDropdownMenuItem(text = { Text(stringResource(R.string.debug_console_save_text)) }, onClick = {
                            exportMenu = false
                            pendingSave = exportText(ExportFormat.TEXT)
                            saveText.launch(exportName(ExportFormat.TEXT))
                        })
                        ExpressiveDropdownMenuItem(text = { Text(stringResource(R.string.debug_console_save_json)) }, onClick = {
                            exportMenu = false
                            pendingSave = exportText(ExportFormat.JSON)
                            saveJson.launch(exportName(ExportFormat.JSON))
                        })
                    }
                }
                IconButton(onClick = {
                    DebugCapture.store.clear()
                    scope.launch {
                        container.debugLog.clear()
                        stored = emptyList()
                        chats = emptyList()
                    }
                }) { Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.debug_console_clear)) }
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutcomeFilter.entries.forEach { filter ->
                    FilterChip(selected = outcome == filter, onClick = { outcome = filter },
                        label = { Text(stringResource(filter.label)) })
                }
                if (providers.size > 1) providers.forEach { (id, name) ->
                    FilterChip(selected = providerId == id, onClick = { providerId = if (providerId == id) null else id },
                        label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
                chats.take(8).forEach { chat ->
                    FilterChip(selected = chatId == chat.sessionId,
                        onClick = { chatId = if (chatId == chat.sessionId) null else chat.sessionId },
                        label = { Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }

            if (visible.isEmpty()) {
                Text(stringResource(R.string.debug_console_empty_filtered), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
            } else {
                visible.forEach { turn ->
                    key(turn.key) {
                        TurnGroup(
                            turn = turn,
                            chatTitle = chats.firstOrNull {
                                it.sessionId == DebugLogRepository.normalizeSession(turn.last.sessionId)
                            }?.title,
                            onOpen = { openId = it },
                        )
                    }
                }
            }
        }
    }

    val openTurn = openId?.let { id -> turns.firstOrNull { turn -> turn.attempts.any { it.trace.id == id } } }
    val openAttempt = openTurn?.attempts?.firstOrNull { it.trace.id == openId }
    if (openTurn != null && openAttempt != null) {
        ProviderDebugDetailSheet(
            turn = openTurn,
            attempt = openAttempt,
            chatTitle = chats.firstOrNull {
                it.sessionId == DebugLogRepository.normalizeSession(openAttempt.trace.sessionId)
            }?.title,
            onOpen = { openId = it },
            onDismiss = { openId = null },
        )
    }

    pendingChange?.let { change ->
        AlertDialog(
            onDismissRequest = { pendingChange = null },
            title = { Text(stringResource(R.string.settings_debug_title)) },
            text = { Text(stringResource(R.string.settings_debug_consent)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.settings.setDebugConsent() }
                    applyDebugSettings(container, scope, change.first, change.second, change.third)
                    pendingChange = null
                }) { Text(stringResource(R.string.ui_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingChange = null }) { Text(stringResource(R.string.custom_sections_cancel)) }
            },
        )
    }
}

private fun DebugTrace.matches(query: String): Boolean =
    listOf(providerName, model, url, error.orEmpty(), statusCode?.toString().orEmpty(), providerTraceId.orEmpty())
        .any { it.contains(query, ignoreCase = true) }

private fun applyDebugSettings(
    container: AppContainer,
    scope: kotlinx.coroutines.CoroutineScope,
    enabled: Boolean,
    bodies: Boolean,
    keep: Boolean,
) {
    DebugCapture.enabled = enabled
    DebugCapture.captureBodies = bodies && enabled
    if (!enabled || !keep) scope.launch { container.debugLog.clear() }
    scope.launch { container.settings.setDebugConsole(enabled, bodies, keep) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConsoleBadge(size: Dp) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(size).background(scheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.BugReport, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(size / 2))
    }
}

@Composable
private fun CapturePanel(
    settings: AppSettings,
    paused: Boolean,
    onEnabled: (Boolean) -> Unit,
    onBodies: (Boolean) -> Unit,
    onPersist: (Boolean) -> Unit,
    onPaused: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    SettingsPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ConsoleBadge(44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.settings_debug_title), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.settings_debug_summary), style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant)
            }
            Switch(checked = settings.debugConsoleEnabled, onCheckedChange = onEnabled)
        }
        AnimatedVisibility(settings.debugConsoleEnabled, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                PanelDivider()
                CaptureToggle(stringResource(R.string.settings_debug_bodies), stringResource(R.string.debug_console_bodies_note),
                    settings.debugConsoleBodies, onBodies)
                PanelDivider()
                CaptureToggle(stringResource(R.string.settings_debug_persist), stringResource(R.string.settings_debug_persist_note),
                    settings.debugConsolePersist, onPersist)
                PanelDivider()
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RecordingDot(active = !paused)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(if (paused) R.string.debug_console_paused else R.string.debug_console_recording),
                            style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.settings_debug_local_note), style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = { onPaused(!paused) }, contentPadding = PaddingValues(start = 12.dp, end = 16.dp)) {
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (paused) R.string.debug_console_resume else R.string.debug_console_pause))
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun CaptureToggle(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A record light: it breathes while requests are being captured and goes flat when paused. */
@Composable
private fun RecordingDot(active: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val alpha = if (active) {
        val transition = rememberInfiniteTransition(label = "recording")
        transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "recording-alpha").value
    } else 1f
    Box(Modifier.size(10.dp).alpha(alpha).background(if (active) scheme.error else scheme.outline, CircleShape))
}

/** Three joined tiles: wide corners on the outside, tight ones where they meet. */
@Composable
private fun StatTiles(requests: Int, failed: Int, retried: Int) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        StatTile(requests, stringResource(R.string.debug_console_stat_requests), scheme.surfaceContainerLow, scheme.onSurface,
            RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp, topEnd = 6.dp, bottomEnd = 6.dp))
        StatTile(failed, stringResource(R.string.debug_console_stat_failed),
            if (failed > 0) scheme.errorContainer else scheme.surfaceContainerLow,
            if (failed > 0) scheme.onErrorContainer else scheme.onSurface, RoundedCornerShape(6.dp))
        StatTile(retried, stringResource(R.string.debug_console_stat_retried),
            if (retried > 0) scheme.tertiaryContainer else scheme.surfaceContainerLow,
            if (retried > 0) scheme.onTertiaryContainer else scheme.onSurface,
            RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 22.dp, bottomEnd = 22.dp))
    }
}

@Composable
private fun RowScope.StatTile(value: Int, label: String, color: Color, content: Color, shape: Shape) {
    Surface(Modifier.weight(1f).fillMaxHeight(), shape = shape, color = color, contentColor = content) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.75f))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyState(title: String, body: String) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(88.dp).background(scheme.surfaceContainerHigh, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.BugReport, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Rows of one turn share a card look: wide corners on the outside, tight ones where rows meet. */
private fun turnRowShape(first: Boolean, last: Boolean): Shape {
    val top = if (first) 22.dp else 6.dp
    val bottom = if (last) 22.dp else 6.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun TurnGroup(turn: DebugTurns.Turn, chatTitle: String?, onOpen: (Long) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (turn.retried || chatTitle != null) {
            Row(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chatTitle?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                if (turn.retried) {
                    Text(stringResource(R.string.debug_console_attempts, turn.attempts.size),
                        style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                }
                if (turn.recovered) Pill(stringResource(R.string.debug_console_recovered), scheme.tertiaryContainer, scheme.onTertiaryContainer)
            }
        }
        turn.attempts.forEachIndexed { index, attempt ->
            AttemptRow(attempt, turnRowShape(index == 0, index == turn.attempts.lastIndex)) { onOpen(attempt.trace.id) }
        }
    }
}

@Composable
private fun AttemptRow(attempt: DebugTurns.Attempt, shape: Shape, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val trace = attempt.trace
    Surface(onClick = onClick, shape = shape, color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusBadge(trace, 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (trace.probe) stringResource(R.string.debug_console_probe)
                    else trace.providerName.ifBlank { trace.url.substringAfter("//").substringBefore('/') },
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    trace.model.ifBlank { "${trace.method} ${trace.url.substringAfter("//")}" },
                    style = MaterialTheme.typography.bodySmall, fontFamily = HarnessMono, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                attemptReason(attempt)?.let { Pill(it, scheme.secondaryContainer, scheme.onSecondaryContainer) }
                Text(traceMeta(trace), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun Pill(text: String, color: Color, content: Color) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.background(color, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp))
}

/**
 * The status at a glance. The shape carries the meaning as well as the colour:
 * a calm circle for an answer, a cookie for a request the provider refused, and
 * a spiked sun when the provider or the network broke.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StatusBadge(trace: DebugTrace, size: Dp) {
    val scheme = MaterialTheme.colorScheme
    val status = trace.statusCode
    val broken = trace.error != null || (status != null && status >= 500)
    val refused = !broken && trace.failed
    val shape = when {
        broken -> MaterialShapes.Sunny.toShape()
        refused -> MaterialShapes.Cookie9Sided.toShape()
        else -> CircleShape
    }
    val (color, content) = when {
        broken -> scheme.error to scheme.onError
        refused -> scheme.errorContainer to scheme.onErrorContainer
        else -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    Box(Modifier.size(size).background(color, shape), contentAlignment = Alignment.Center) {
        Text(status?.toString() ?: if (trace.error != null) "ERR" else "...",
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = content, maxLines = 1)
    }
}

/** Why this send happened, in words: "Retry after HTTP 429". Null for the first send of a turn. */
@Composable
internal fun attemptReason(attempt: DebugTurns.Attempt, detailed: Boolean = false): String? {
    val previous = attempt.previous ?: return null
    val cause = when {
        previous.statusCode != null && previous.failed -> stringResource(R.string.debug_console_cause_http, previous.statusCode)
        previous.error != null -> stringResource(R.string.debug_console_cause_network)
        else -> stringResource(R.string.debug_console_cause_unknown)
    }
    return when (attempt.kind) {
        DebugTurns.Kind.FIRST -> null
        DebugTurns.Kind.RETRY -> stringResource(R.string.debug_console_retry_after, cause)
        DebugTurns.Kind.KEY_ROTATION -> stringResource(R.string.debug_console_key_after, attempt.trace.keySlot + 1, cause)
        DebugTurns.Kind.FALLBACK ->
            if (detailed && previous.providerName.isNotBlank()) {
                stringResource(R.string.debug_console_fallback_from, previous.providerName, cause)
            } else stringResource(R.string.debug_console_fallback_after, cause)
    }
}

@Composable
private fun traceMeta(trace: DebugTrace): String {
    val parts = mutableListOf(clockTime(trace.startedAt), shortDuration(trace.durationMs))
    if (trace.stream && trace.streamEvents > 0) parts += stringResource(R.string.debug_console_events_short, trace.streamEvents)
    trace.streamUsage?.let { parts += it }
    return parts.joinToString("  ·  ")
}

internal fun clockTime(epochMillis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(epochMillis))

/** Milliseconds stay exact below a second, where they tell a cache hit from a slow start. */
internal fun shortDuration(ms: Long): String =
    if (ms < 1_000) "$ms ms" else String.format(Locale.US, "%.1f s", ms / 1000.0)

private fun writePicked(context: Context, scope: kotlinx.coroutines.CoroutineScope, uri: android.net.Uri?, text: String?) {
    if (uri == null || text == null) return
    scope.launch {
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    ?: error("Could not open the chosen file")
            }
        }
        outcome.onSuccess { Toast.makeText(context, R.string.ui_saved, Toast.LENGTH_SHORT).show() }
            .onFailure { error ->
                Toast.makeText(context, context.getString(R.string.ui_failed,
                    error.localizedMessage ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
    }
}

private fun exportName(format: ExportFormat): String =
    "provider-request-log-" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "." + format.extension

/**
 * Shares the log as a file from the FileProvider's shared cache directory.
 *
 * A text extra goes through Binder, which throws once a bundle passes about
 * 1 MB, and a log with bodies passes that easily. Only the newest export is
 * kept, so repeated taps do not grow the cache.
 */
private fun shareExport(context: Context, scope: kotlinx.coroutines.CoroutineScope, text: String, format: ExportFormat) {
    scope.launch {
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "shared").apply { mkdirs() }
                directory.listFiles()?.filter { it.name.startsWith("provider-request-log-") }?.forEach { it.delete() }
                File(directory, exportName(format)).apply { writeText(text) }
            }
        }
        outcome.onSuccess { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.update", file)
            val title = context.getString(R.string.settings_debug_title)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = format.mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, title))
        }.onFailure { error ->
            Toast.makeText(context, context.getString(R.string.ui_failed,
                error.localizedMessage ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }
}

/** Shares one trace as text; the detail sheet uses it. */
internal fun shareTraceText(context: Context, scope: kotlinx.coroutines.CoroutineScope, text: String) =
    shareExport(context, scope, text, ExportFormat.TEXT)
