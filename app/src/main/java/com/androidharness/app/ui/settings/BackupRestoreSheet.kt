@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.androidharness.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Unarchive
import com.androidharness.app.ui.common.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.BackupChatInfo
import com.androidharness.app.data.BackupProgress
import com.androidharness.app.data.BackupWorkspace
import com.androidharness.app.data.ChatBackupArchive
import com.androidharness.app.data.ChatBackupManager
import com.androidharness.app.data.ChatImportPreview
import com.androidharness.app.data.DuplicatePolicy
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.theme.HarnessGlassModalBottomSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal sealed interface BackupSheetMode {
    data object Export : BackupSheetMode
    data class Restore(val uri: Uri) : BackupSheetMode
}

private enum class Phase { LOADING, PICK, WORKING, DONE, FAILED }

/** A stat pill on the result card, e.g. "23 chats". */
private data class ResultChip(val text: String, val icon: ImageVector? = null)

@Composable
internal fun BackupRestoreSheet(
    container: AppContainer,
    mode: BackupSheetMode,
    onDismiss: () -> Unit,
    onRestored: (ChatBackupManager.ImportResult) -> Unit = {},
) {
    var working by remember { mutableStateOf(false) }
    val workingNow by rememberUpdatedState(working)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // Swiping away mid-write would orphan the job; Cancel is the way out while it runs.
        confirmValueChange = { it != SheetValue.Hidden || !workingNow },
    )
    HarnessGlassModalBottomSheet(onDismissRequest = { if (!workingNow) onDismiss() }, sheetState = sheetState) {
        MaterialTheme(motionScheme = MotionScheme.expressive()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            ) {
                when (mode) {
                    BackupSheetMode.Export -> ExportFlow(container, onWorking = { working = it }, onDismiss = onDismiss)
                    is BackupSheetMode.Restore -> RestoreFlow(container, mode.uri, onWorking = { working = it }, onDismiss = onDismiss, onRestored = onRestored)
                }
            }
        }
    }
}

@Composable
private fun ExportFlow(container: AppContainer, onWorking: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.LOADING) }
    var chats by remember { mutableStateOf(emptyList<BackupChatInfo>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var images by remember { mutableStateOf(true) }
    var usage by remember { mutableStateOf(true) }
    var memories by remember { mutableStateOf(true) }
    var snippets by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf(BackupProgress(0, 0)) }
    var result by remember { mutableStateOf<ChatBackupManager.ExportResult?>(null) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        chats = runCatching { container.chatBackup.chatsForExport() }.getOrElse { e ->
            error = e.message ?: context.getString(R.string.backup_unknown_error); phase = Phase.FAILED; return@LaunchedEffect
        }
        selected = chats.map { it.id }.toSet()
        phase = Phase.PICK
    }

    val suggestedName = remember {
        "androidharness-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) +
            "." + ChatBackupArchive.EXTENSION
    }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val options = ChatBackupManager.ExportOptions(selected, memories, snippets, images, usage)
        progress = BackupProgress(0, selected.size)
        notice = null
        phase = Phase.WORKING
        onWorking(true)
        job = scope.launch {
            try {
                result = container.chatBackup.exportTo(uri, options) { progress = it }
                savedUri = uri
                phase = Phase.DONE
            } catch (e: CancellationException) {
                notice = context.getString(R.string.backup_cancelled)
                phase = Phase.PICK
            } catch (e: Exception) {
                error = e.message ?: context.getString(R.string.backup_unknown_error)
                phase = Phase.FAILED
            } finally {
                onWorking(false)
                job = null
            }
        }
    }

    AnimatedContent(phase, transitionSpec = { phaseTransition() }, label = "backup phase") { current ->
        when (current) {
            Phase.LOADING -> CenteredLoading(stringResource(R.string.backup_loading_chats))
            Phase.PICK -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetHeader(Icons.Rounded.Archive, stringResource(R.string.backup_pick_title), stringResource(R.string.backup_pick_subtitle))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OptionChip(stringResource(R.string.backup_option_images), images) { images = it }
                    OptionChip(stringResource(R.string.backup_option_usage), usage) { usage = it }
                    OptionChip(stringResource(R.string.backup_option_memories), memories) { memories = it }
                    OptionChip(stringResource(R.string.backup_option_snippets), snippets) { snippets = it }
                }
                if (chats.size > 6) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(R.string.backup_search_hint)) },
                        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
                    )
                }
                val visible = chats.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
                if (chats.isNotEmpty()) SelectionBar(selected.size, chats.size,
                    onAll = { selected = selected + visible.map { it.id } },
                    onNone = { selected = selected - visible.map { it.id }.toSet() })
                when {
                    chats.isEmpty() -> EmptyNote(stringResource(R.string.backup_no_chats))
                    visible.isEmpty() -> EmptyNote(stringResource(R.string.backup_no_matches))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(visible, key = { _, chat -> chat.id }) { index, chat ->
                            ChatRow(chat, checked = chat.id in selected, index = index, badge = null, dimmed = false,
                                modifier = Modifier.animateItem()) {
                                selected = if (chat.id in selected) selected - chat.id else selected + chat.id
                            }
                        }
                    }
                }
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val extrasOnly = selected.isEmpty() && (memories || snippets)
                Button(
                    onClick = { createFile.launch(suggestedName) },
                    enabled = selected.isNotEmpty() || extrasOnly,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(if (extrasOnly) stringResource(R.string.backup_extras_only)
                        else pluralStringResource(R.plurals.backup_start_export, selected.size, selected.size))
                }
            }
            Phase.WORKING -> WorkingPanel(
                progress = progress,
                label = stringResource(if (progress.total > 0 && progress.done >= progress.total) R.string.backup_packing_extras else R.string.backup_packing),
                note = stringResource(R.string.backup_keep_open),
                onCancel = { job?.cancel() },
            )
            Phase.DONE -> {
                val r = result
                SuccessPanel(
                    title = stringResource(R.string.backup_done_title),
                    body = stringResource(R.string.backup_done_body),
                    chips = buildList {
                        if (r != null) {
                            add(ResultChip(pluralStringResource(R.plurals.backup_chip_chats, r.sessions, r.sessions)))
                            add(ResultChip(pluralStringResource(R.plurals.backup_chip_messages, r.messages, r.messages)))
                            if (r.images > 0) add(ResultChip(pluralStringResource(R.plurals.backup_chip_images, r.images, r.images)))
                            if (r.memories > 0) add(ResultChip(pluralStringResource(R.plurals.backup_chip_memories, r.memories, r.memories)))
                            if (r.snippets > 0) add(ResultChip(pluralStringResource(R.plurals.backup_chip_snippets, r.snippets, r.snippets)))
                        }
                    },
                    primary = stringResource(R.string.backup_done) to onDismiss,
                    secondary = savedUri?.let { uri ->
                        stringResource(R.string.backup_share) to {
                            val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.backup_share_chooser))) }
                            Unit
                        }
                    },
                )
            }
            Phase.FAILED -> FailurePanel(
                title = stringResource(R.string.backup_failed_title),
                message = error,
                onRetry = if (chats.isNotEmpty() || memories || snippets) ({ phase = Phase.PICK }) else null,
                onClose = onDismiss,
            )
        }
    }
}

@Composable
private fun RestoreFlow(
    container: AppContainer,
    uri: Uri,
    onWorking: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onRestored: (ChatBackupManager.ImportResult) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.LOADING) }
    var preview by remember { mutableStateOf<ChatImportPreview?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var policy by remember { mutableStateOf(DuplicatePolicy.SKIP) }
    var extras by remember { mutableStateOf(true) }
    var missing by remember { mutableStateOf<List<BackupWorkspace>?>(null) }
    var progress by remember { mutableStateOf(BackupProgress(0, 0)) }
    var result by remember { mutableStateOf<ChatBackupManager.ImportResult?>(null) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(uri) {
        try {
            val loaded = container.chatBackup.prepareImport(uri)
            preview = loaded
            selected = loaded.chats.map { it.info.id }.toSet()
            phase = Phase.PICK
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            error = e.message ?: context.getString(R.string.backup_unknown_error)
            phase = Phase.FAILED
        }
    }

    fun start(source: ChatImportPreview, mapping: Map<String, String>) {
        progress = BackupProgress(0, source.chats.count { it.info.id in selected })
        notice = null
        phase = Phase.WORKING
        onWorking(true)
        job = scope.launch {
            try {
                val done = container.chatBackup.importPrepared(source, mapping, selected, policy, extras) { progress = it }
                result = done
                onRestored(done)
                phase = Phase.DONE
            } catch (e: CancellationException) {
                notice = context.getString(R.string.restore_cancelled)
                phase = Phase.PICK
            } catch (e: Exception) {
                error = e.message ?: context.getString(R.string.backup_unknown_error)
                phase = Phase.FAILED
            } finally {
                onWorking(false)
                job = null
            }
        }
    }

    AnimatedContent(phase, transitionSpec = { phaseTransition() }, label = "restore phase") { current ->
        val source = preview
        when (current) {
            Phase.LOADING -> CenteredLoading(stringResource(R.string.restore_reading))
            Phase.PICK -> if (source != null) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val date = remember(source.exportedAt) {
                    if (source.exportedAt > 0) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                        .format(java.util.Date(source.exportedAt)) else null
                }
                SheetHeader(Icons.Rounded.Unarchive, stringResource(R.string.restore_review_title),
                    date?.let { stringResource(R.string.restore_from, it) })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val messages = source.chats.sumOf { it.info.messages }
                    StatPill(pluralStringResource(R.plurals.backup_chip_chats, source.chats.size, source.chats.size), 0)
                    StatPill(pluralStringResource(R.plurals.backup_chip_messages, messages, messages), 1)
                    if (source.memories > 0) StatPill(pluralStringResource(R.plurals.backup_chip_memories, source.memories, source.memories), 2)
                    if (source.snippets > 0) StatPill(pluralStringResource(R.plurals.backup_chip_snippets, source.snippets, source.snippets), 3)
                }
                if (source.legacy) Text(stringResource(R.string.restore_legacy_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (source.chats.any { it.exists }) {
                    Text(stringResource(R.string.restore_duplicates_label), style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(DuplicatePolicy.SKIP to R.string.restore_skip, DuplicatePolicy.KEEP_BOTH to R.string.restore_keep_both)
                            .forEachIndexed { index, (value, label) ->
                                SegmentedButton(
                                    selected = policy == value, onClick = { policy = value },
                                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                                ) { Text(stringResource(label)) }
                            }
                    }
                }
                if (source.memories + source.snippets > 0) {
                    Row(
                        Modifier.fillMaxWidth().clickable { extras = !extras }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = extras, onCheckedChange = { extras = it })
                        Text(stringResource(R.string.restore_extras), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (source.chats.isEmpty() && source.memories + source.snippets == 0) {
                    EmptyNote(stringResource(R.string.restore_empty))
                } else if (source.chats.isNotEmpty()) {
                    SelectionBar(selected.size, source.chats.size,
                        onAll = { selected = source.chats.map { it.info.id }.toSet() }, onNone = { selected = emptySet() })
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(source.chats, key = { _, chat -> chat.info.id }) { index, chat ->
                            ChatRow(
                                chat.info, checked = chat.info.id in selected, index = index,
                                badge = if (chat.exists) stringResource(R.string.restore_on_phone) else null,
                                dimmed = chat.exists && policy == DuplicatePolicy.SKIP,
                            ) {
                                selected = if (chat.info.id in selected) selected - chat.info.id else selected + chat.info.id
                            }
                        }
                    }
                }
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val count = source.chats.count { it.info.id in selected && (!it.exists || policy == DuplicatePolicy.KEEP_BOTH) }
                val extrasOnly = count == 0 && extras && source.memories + source.snippets > 0
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val needed = container.chatBackup.missingWorkspaces(source, selected, policy, extras)
                                if (needed.isEmpty()) start(source, emptyMap()) else missing = needed
                            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                                error = e.message ?: context.getString(R.string.backup_unknown_error)
                                phase = Phase.FAILED
                            }
                        }
                    },
                    enabled = count > 0 || extrasOnly,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(if (extrasOnly) stringResource(R.string.restore_extras_only)
                        else pluralStringResource(R.plurals.restore_start, count, count))
                }
            }
            Phase.WORKING -> WorkingPanel(
                progress = progress,
                label = stringResource(if (progress.total > 0 && progress.done >= progress.total) R.string.restore_working_extras else R.string.restore_working),
                note = stringResource(R.string.restore_cancel_note),
                onCancel = { job?.cancel() },
            )
            Phase.DONE -> {
                val r = result
                SuccessPanel(
                    title = stringResource(R.string.restore_done_title),
                    body = stringResource(R.string.restore_done_body),
                    chips = buildList {
                        if (r != null) {
                            add(ResultChip(pluralStringResource(R.plurals.backup_chip_chats, r.sessions, r.sessions)))
                            add(ResultChip(pluralStringResource(R.plurals.backup_chip_messages, r.messages, r.messages)))
                            if (r.images > 0) add(ResultChip(pluralStringResource(R.plurals.backup_chip_images, r.images, r.images)))
                            if (r.memories > 0) add(ResultChip(pluralStringResource(R.plurals.backup_chip_memories, r.memories, r.memories)))
                            if (r.snippets > 0) add(ResultChip(pluralStringResource(R.plurals.backup_chip_snippets, r.snippets, r.snippets)))
                            if (r.copies > 0) add(ResultChip(pluralStringResource(R.plurals.restore_chip_copies, r.copies, r.copies)))
                            if (r.skipped > 0) add(ResultChip(pluralStringResource(R.plurals.restore_chip_skipped, r.skipped, r.skipped)))
                        }
                    },
                    primary = stringResource(R.string.backup_done) to onDismiss,
                    secondary = null,
                )
            }
            Phase.FAILED -> FailurePanel(
                title = stringResource(R.string.restore_failed_title),
                message = error,
                onRetry = if (source != null) ({ phase = Phase.PICK }) else null,
                onClose = onDismiss,
            )
        }
    }

    val pending = missing
    val source = preview
    if (pending != null && source != null) {
        ChatRestoreDialog(container, pending, source.chats.count { it.info.id in selected },
            onDismiss = { missing = null },
            onConfirm = { mapping -> missing = null; start(source, mapping) })
    }
}

private fun androidx.compose.animation.AnimatedContentTransitionScope<Phase>.phaseTransition() =
    (fadeIn(tween(220, delayMillis = 60)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.94f))
        .togetherWith(fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.98f))

@Composable
private fun animationsOff(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}

@Composable
private fun SheetHeader(icon: ImageVector, title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = selected, onClick = { onChange(!selected) }, label = { Text(label) },
        leadingIcon = if (selected) ({ Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }) else null,
    )
}

@Composable
private fun SelectionBar(selected: Int, total: Int, onAll: () -> Unit, onNone: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(selected, transitionSpec = { countTransition(targetState > initialState) }, label = "selected count") { count ->
            Text(stringResource(R.string.backup_progress_count, count, total), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        if (selected < total) TextButton(onClick = onAll) { Text(stringResource(R.string.backup_select_all)) }
        else TextButton(onClick = onNone) { Text(stringResource(R.string.backup_select_none)) }
    }
}

private fun countTransition(up: Boolean) =
    (slideInVertically(spring(stiffness = Spring.StiffnessMedium)) { if (up) it else -it } + fadeIn())
        .togetherWith(slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { if (up) -it else it } + fadeOut())

/** Rows rise into place one after another the first time the list appears. */
@Composable
private fun ChatRow(
    chat: BackupChatInfo,
    checked: Boolean,
    index: Int,
    badge: String?,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val still = animationsOff()
    val entrance = remember(chat.id) { Animatable(if (still || index > 10) 1f else 0f) }
    LaunchedEffect(chat.id) {
        if (entrance.value < 1f) {
            delay(index * 35L)
            entrance.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    val alpha by animateFloatAsState(if (dimmed) 0.5f else 1f, label = "row dim")
    val press by animateFloatAsState(if (checked) 1f else 0.97f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "row check")
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(18.dp),
        color = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().graphicsLayer {
            this.alpha = entrance.value.coerceIn(0f, 1f) * alpha
            translationY = (1f - entrance.value) * 28.dp.toPx()
            scaleX = press; scaleY = press
        },
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(chat.title.ifBlank { stringResource(R.string.backup_untitled_chat) }, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val date = remember(chat.updatedAt) {
                    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(chat.updatedAt))
                }
                Text("$date · " + pluralStringResource(R.plurals.backup_chip_messages, chat.messages, chat.messages),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            badge?.let {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.padding(end = 8.dp)) {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun StatPill(text: String, index: Int, icon: ImageVector? = null) {
    val still = animationsOff()
    val pop = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (pop.value < 1f) { delay(120L + index * 70L); pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)) }
    }
    Surface(
        shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceIn(0f, 1f) },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
}

@Composable
private fun CenteredLoading(label: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LoadingIndicator(Modifier.size(64.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/** The ring fills as chats are packed; the count ticks up and the current chat title slides past. */
@Composable
private fun WorkingPanel(progress: BackupProgress, label: String, note: String, onCancel: () -> Unit) {
    val fraction = if (progress.total <= 0) 0f else progress.done.toFloat() / progress.total
    val ring by animateFloatAsState(fraction, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow), label = "backup ring")
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(156.dp), contentAlignment = Alignment.Center) {
            com.androidharness.app.ui.common.AppCircularProgressIndicator(
                progress = { ring }, modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            AnimatedContent(progress.done, transitionSpec = { countTransition(true) }, label = "packed count") { done ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$done", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    Text("/ ${progress.total}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        AnimatedContent(progress.title, transitionSpec = { countTransition(true) }, label = "current chat") { title ->
            Text(title.ifBlank { " " }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.backup_cancel)) }
    }
}

/** A check badge springs in with a little overshoot and a confirm tap, then the stats pop in. */
@Composable
private fun SuccessPanel(
    title: String,
    body: String,
    chips: List<ResultChip>,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>?,
) {
    val haptics = LocalHapticFeedback.current
    val still = animationsOff()
    val badge = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        if (badge.value < 1f) badge.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow))
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = CircleShape, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(96.dp).graphicsLayer {
                scaleX = badge.value; scaleY = badge.value
                rotationZ = (1f - badge.value) * -60f
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(52.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { chips.forEachIndexed { index, chip -> StatPill(chip.text, index + 2, chip.icon) } }
        Spacer(Modifier.size(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            secondary?.let { (label, action) ->
                OutlinedButton(onClick = action, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(label) }
            }
            Button(onClick = primary.second, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(primary.first) }
        }
    }
}

/** A short head shake and a reject tap say "this did not work" before the words do. */
@Composable
private fun FailurePanel(title: String, message: String, onRetry: (() -> Unit)?, onClose: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val still = animationsOff()
    val shake = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        if (!still) for (offset in listOf(-14f, 12f, -8f, 5f, 0f)) shake.animateTo(offset, tween(55))
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.size(88.dp).graphicsLayer { translationX = shake.value.dp.toPx() },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(46.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(stringResource(R.string.backup_close)) }
            onRetry?.let { retry ->
                Button(onClick = retry, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(stringResource(R.string.backup_try_again)) }
            }
        }
    }
}
