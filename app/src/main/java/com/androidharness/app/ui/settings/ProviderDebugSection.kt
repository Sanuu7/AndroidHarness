package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.llm.debug.DebugCapture
import com.androidharness.app.llm.debug.DebugLogRepository
import com.androidharness.app.llm.debug.DebugTrace
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
 * Tapping the summary opens the console; the switch decides whether anything is
 * recorded at all. Everything here is off after a fresh install.
 */
@Composable
internal fun ProviderDebugCard(container: AppContainer, onOpen: (SettingsPage) -> Unit) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val traces by DebugCapture.store.traces.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    SettingsPanel(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp).clickable { onOpen(SettingsPage.PROVIDER_DEBUG) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_debug_title), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.settings_debug_summary), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (settings.debugConsoleEnabled && traces.isNotEmpty()) {
                    Text(stringResource(R.string.settings_debug_count, traces.size),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Switch(checked = settings.debugConsoleEnabled, onCheckedChange = { on ->
                applyDebugSettings(container, scope, context, on, settings.debugConsoleBodies,
                    settings.debugConsolePersist)
            })
        }
    }
}

/** The console itself: switches, chat filter, export, and the recorded exchanges. */
@Composable
internal fun ProviderDebugSection(container: AppContainer) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val live by DebugCapture.store.traces.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val persist = settings.debugConsoleEnabled && settings.debugConsolePersist

    var rows by remember { mutableStateOf<List<DebugTrace>>(emptyList()) }
    var chats by remember { mutableStateOf<List<DebugLogRepository.Chat>>(emptyList()) }
    var chatId by remember { mutableStateOf<String?>(null) }
    var failedOnly by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf<Long?>(null) }
    var pendingChange by remember { mutableStateOf<Triple<Boolean, Boolean, Boolean>?>(null) }

    fun request(enabled: Boolean, bodies: Boolean, keep: Boolean) {
        if (!settings.debugConsoleConsent && (bodies || keep)) {
            pendingChange = Triple(enabled, bodies, keep)
        } else {
            applyDebugSettings(container, scope, context, enabled, bodies, keep)
        }
    }
    var pendingSave by remember { mutableStateOf<String?>(null) }

    // SAF write: the user picks the directory, and no storage permission is needed.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = pendingSave
        pendingSave = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
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

    LaunchedEffect(persist, chatId, failedOnly, live.size) {
        if (persist) {
            val since = System.currentTimeMillis() - DebugLogRepository.RETENTION_MS
            rows = container.debugLog.load(since = since, sessionId = chatId, failedOnly = failedOnly)
            chats = container.debugLog.chats(since)
        } else {
            rows = live
        }
    }

    val visible = remember(rows, query, chatId, failedOnly, persist) {
        rows.filter { trace ->
            (!failedOnly || trace.failed) &&
                (chatId == null || DebugLogRepository.normalizeSession(trace.sessionId) == chatId) &&
                (query.isBlank() || listOf(trace.providerName, trace.model, trace.url, trace.error.orEmpty(),
                    trace.statusCode?.toString().orEmpty()).any { it.contains(query, ignoreCase = true) })
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_debug_title), style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.settings_debug_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.debugConsoleEnabled, onCheckedChange = { on ->
                        request(on, settings.debugConsoleBodies, settings.debugConsolePersist)
                    })
                }
                if (settings.debugConsoleEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_debug_bodies), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f))
                        Switch(checked = settings.debugConsoleBodies, onCheckedChange = { on ->
                            request(true, on, settings.debugConsolePersist)
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_debug_persist),
                                style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.settings_debug_persist_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = settings.debugConsolePersist, onCheckedChange = { on ->
                            request(true, settings.debugConsoleBodies, on)
                        })
                    }
                    Text(stringResource(R.string.settings_debug_local_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (persist && chats.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = chatId == null, onClick = { chatId = null },
                    label = { Text(stringResource(R.string.ui_all_chats)) })
                chats.take(8).forEach { chat ->
                    FilterChip(selected = chatId == chat.sessionId, onClick = { chatId = chat.sessionId },
                        label = { Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = failedOnly, onClick = { failedOnly = !failedOnly },
                label = { Text(stringResource(R.string.rich_failed)) })
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text(stringResource(R.string.settings_debug_search)) },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = visible.isNotEmpty(), onClick = {
                val text = visible.joinToString("\n\n----------\n\n") { it.asText() }
                scope.launch {
                    val outcome = runCatching {
                        withContext(Dispatchers.IO) { writeExportFile(context, text) }
                    }
                    outcome.onSuccess { file -> shareExportFile(context, file, text) }
                        .onFailure { error ->
                            Toast.makeText(context, context.getString(R.string.ui_failed,
                                error.localizedMessage ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
                        }
                }
            }) { Text(stringResource(R.string.settings_debug_export)) }
            TextButton(enabled = visible.isNotEmpty(), onClick = {
                pendingSave = visible.joinToString("\n\n----------\n\n") { it.asText() }
                saveLauncher.launch(exportName())
            }) { Text(stringResource(R.string.settings_debug_save)) }
            TextButton(enabled = rows.isNotEmpty(), onClick = {
                DebugCapture.store.clear()
                scope.launch {
                    container.debugLog.clear()
                    rows = emptyList()
                    chats = emptyList()
                }
            }) { Text(stringResource(R.string.ui_clear)) }
        }

        if (visible.isEmpty()) {
            Text(stringResource(R.string.settings_debug_empty), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        } else {
            visible.forEach { trace ->
                TraceRow(trace, chatTitle = chats.firstOrNull {
                    it.sessionId == DebugLogRepository.normalizeSession(trace.sessionId)
                }?.title) { openId = trace.id }
            }
        }
    }

    // Re-read from the live list, so a streaming trace keeps updating while open.
    val open = openId?.let { id -> rows.firstOrNull { it.id == id } ?: live.firstOrNull { it.id == id } }
    if (open != null) TraceDetail(open) { openId = null }

    pendingChange?.let { change ->
        AlertDialog(
            onDismissRequest = { pendingChange = null },
            title = { Text(stringResource(R.string.settings_debug_title)) },
            text = { Text(stringResource(R.string.settings_debug_consent)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.settings.setDebugConsent() }
                    applyDebugSettings(container, scope, context, change.first, change.second, change.third)
                    pendingChange = null
                }) { Text(stringResource(R.string.ui_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingChange = null }) { Text(stringResource(R.string.ui_cancel)) }
            },
        )
    }
}

private fun applyDebugSettings(
    container: AppContainer,
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    enabled: Boolean,
    bodies: Boolean,
    keep: Boolean,
) {
    DebugCapture.enabled = enabled
    DebugCapture.captureBodies = bodies && enabled
    if (!enabled || !keep) {
        scope.launch { container.debugLog.clear() }
    }
    scope.launch { container.settings.setDebugConsole(enabled, bodies, keep) }
}

@Composable
private fun TraceRow(trace: DebugTrace, chatTitle: String?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    trace.providerName.ifBlank { trace.url },
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    "${trace.method} ${trace.url.substringAfter("//")}",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                chatTitle?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.primary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            Text(
                when {
                    trace.error != null -> "ERR"
                    trace.statusCode != null -> trace.statusCode.toString()
                    else -> "—"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (trace.failed) scheme.error else scheme.primary,
            )
            Text("${trace.durationMs} ms", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            if (trace.stream && trace.streamEvents > 0) {
                Text(stringResource(R.string.settings_debug_events, trace.streamEvents),
                    style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            if (trace.attempt > 1) {
                Text("${trace.attempt}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TraceDetail(trace: DebugTrace, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                copyToClipboard(context, trace.asText())
                Toast.makeText(context, context.getString(R.string.ui_copied, trace.method), Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.ui_copy)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
        title = { Text(trace.providerName.ifBlank { trace.url }, maxLines = 2) },
        text = {
            SelectionContainer {
                Text(trace.asText(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
            }
        },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Provider request", text))
}

/**
 * Writes the log to a file under the FileProvider's shared cache directory.
 *
 * A text extra goes through Binder, which throws once a bundle passes ~1 MB; a
 * full log of 50 exchanges with bodies exceeds that easily. Only the newest
 * export is kept, so repeated taps do not grow the cache.
 */
private fun writeExportFile(context: Context, text: String): File {
    val directory = File(context.cacheDir, "shared").apply { mkdirs() }
    directory.listFiles()?.forEach { it.delete() }
    return File(directory, exportName()).apply { writeText(text) }
}

private fun exportName(): String =
    "provider-request-log-" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"

private fun shareExportFile(context: Context, file: File, text: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.update", file)
    val title = context.getString(R.string.settings_debug_title)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        // Receivers that only read EXTRA_TEXT still get a short preview.
        putExtra(Intent.EXTRA_TEXT, text.take(2_000))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title))
}
