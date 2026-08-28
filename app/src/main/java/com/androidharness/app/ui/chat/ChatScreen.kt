package com.androidharness.app.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.androidharness.app.ui.common.formatTokenCount
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.core.Role
import com.androidharness.app.core.ChatMessage
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.androidharness.app.ui.chat.components.AgentStatusBar
import com.androidharness.app.ui.chat.components.ApprovalCard
import com.androidharness.app.ui.chat.components.AssistantText
import com.androidharness.app.ui.chat.components.AttachmentChips
import com.androidharness.app.ui.chat.components.EmptyState
import com.androidharness.app.ui.chat.components.EnvSheet
import com.androidharness.app.ui.chat.components.EnvironmentInstallCard
import com.androidharness.app.ui.chat.components.MainHeader
import com.androidharness.app.ui.chat.components.MessageComposer
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import com.androidharness.app.ui.chat.components.PlanApprovalCard
import com.androidharness.app.ui.chat.components.QuestionCard
import com.androidharness.app.ui.chat.components.QueuedMessageChip
import com.androidharness.app.ui.chat.components.RewindButton
import com.androidharness.app.skills.slashInvokedSkillName
import com.androidharness.app.skills.slashSkillInstruction
import com.androidharness.app.ui.chat.components.SkillsPickerSheet
import com.androidharness.app.ui.chat.components.SkillUsedBadge
import com.androidharness.app.ui.chat.components.SlashSuggestions
import com.androidharness.app.ui.chat.components.SubagentCard
import com.androidharness.app.ui.chat.components.SubagentPagerCard
import com.androidharness.app.ui.chat.components.ThinkingBlock
import com.androidharness.app.ui.chat.components.TodoCard
import com.androidharness.app.ui.chat.components.ToolCallCard
import com.androidharness.app.ui.chat.components.ToolGroupCard
import com.androidharness.app.ui.chat.components.UserBubble
import com.androidharness.app.ui.common.formatRelativeTime
import com.androidharness.app.ui.common.formatDuration
import com.androidharness.app.ui.files.DiffStatText
import com.androidharness.app.ui.settings.ProviderManagerSheet
import com.androidharness.app.ui.theme.fastEffectsSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The chat screen. All visual components live in `ui/chat/components/`; this file
 * owns state wiring, dialogs, and the message list assembly.
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onOpenFile: (path: String, line: Int?) -> Unit,
    onNewChat: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenSubagent: (toolCallId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var showContext by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showProviderManager by remember { mutableStateOf(false) }
    var slashExpanded by remember { mutableStateOf(false) }
    var composerText by remember { mutableStateOf("") }
    var attachedSkill by remember { mutableStateOf<String?>(null) }

    val clipboard = LocalClipboardManager.current
    var actionsMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var confirmingEdit by remember { mutableStateOf<Pair<ChatMessage, String>?>(null) }
    var showUndoDialog by remember { mutableStateOf(false) }
    // Chosen checkpoint awaiting confirmation with a per-file preview.
    var confirmRewindTurn by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Bottom-pinning for the message list: true while the newest content
    // should stay in view; only a real user drag away from the bottom clears
    // it, programmatic scrolls (follow, jump) never touch the pin.
    var pinnedToBottom by remember { mutableStateOf(true) }

    // True while a finger drag is driving the list (from interactionSource).
    val gestureActive = remember { mutableStateOf(false) }

    // Typewriter reveal for streaming text: characters shown so far this
    // message. Keyed on the message id so it survives the commit handoff.
    var revealedChars by remember(state.streamingMessageId) { mutableStateOf(0) }

    if (showContext) {
        ContextUsageDialog(state = state, onDismiss = { showContext = false })
    }
    if (showModelPicker) {
        ModelPickerSheet(
            providers = state.providers,
            activeProviderId = state.activeProviderId,
            activeModel = state.activeModel,
            catalogs = state.catalogs,
            onDismiss = { showModelPicker = false },
            onSelect = viewModel::selectModel,
            onRefreshCatalog = viewModel::refreshCatalog,
            // Provider management stays in-conversation: a sheet, not a screen.
            onManageProviders = { showProviderManager = true },
        )
    }
    if (state.showSkillsSheet) {
        SkillsPickerSheet(
            skills = state.skills,
            onDismiss = viewModel::dismissSkillsSheet,
            onPick = { skill ->
                viewModel.dismissSkillsSheet()
                attachedSkill = skill.name
                composerText = ""
                slashExpanded = false
            },
        )
    }
    if (state.showEnvSheet) {
        EnvSheet(
            container = viewModel.container,
            envState = state.envState,
            onDismiss = viewModel::dismissEnvSheet,
        )
    }
    if (showProviderManager) {
        ProviderManagerSheet(
            providers = state.providers,
            activeProviderId = state.activeProviderId,
            apiKey = viewModel::providerApiKey,
            onDismiss = { showProviderManager = false },
            onSetActive = viewModel::setActiveProvider,
            onDelete = viewModel::deleteProvider,
            onSave = viewModel::upsertProvider,
        )
    }
    if (state.showCostDialog) {
        CostDialog(state = state, onDismiss = viewModel::dismissCostDialog)
    }

    // Long-press menu for YOUR messages (agent text is directly selectable,
    // hold and drag; the system toolbar handles copy).
    actionsMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { actionsMessage = null },
            title = { Text("Your message") },
            text = {
                Text(
                    if (msg.text.length > 200) msg.text.take(200) + "…" else msg.text,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(msg.text))
                        actionsMessage = null
                    }) { Text("Copy") }
                    TextButton(onClick = {
                        editingMessage = msg
                        actionsMessage = null
                    }) { Text("Edit") }
                }
            },
            dismissButton = {
                TextButton(onClick = { actionsMessage = null }) { Text("Close") }
            },
        )
    }

    // Prefilled editor for a user message.
    editingMessage?.let { msg ->
        var text by remember(msg) { mutableStateOf(msg.text) }
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = { Text("Edit message") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (text.isNotBlank()) {
                        confirmingEdit = msg to text
                        editingMessage = null
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { editingMessage = null }) { Text("Cancel") } },
        )
    }

    // Editing a message rewinds files and truncates the chat, warn first.
    confirmingEdit?.let { (msg, newText) ->
        AlertDialog(
            onDismissRequest = { confirmingEdit = null },
            title = { Text("Edit this message?") },
            text = {
                Text(
                    "Everything after this message will be deleted, and any file changes made " +
                        "after it will be undone: files are restored to how they were at that " +
                        "point. The edited message is then resent. This cannot be undone.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.editAndResend(msg, newText)
                    confirmingEdit = null
                }) { Text("Edit & resend") }
            },
            dismissButton = { TextButton(onClick = { confirmingEdit = null }) { Text("Cancel") } },
        )
    }

    // Undo file changes: pick a turn to restore the workspace back to.
    if (showUndoDialog) {
        val turns = remember(state.turnsWithCheckpoints, state.messages) {
            state.turnsWithCheckpoints.mapNotNull { tid ->
                val msg = state.messages.lastOrNull { it.turnId == tid && it.role == Role.ASSISTANT }
                    ?: state.messages.firstOrNull { it.turnId == tid }
                msg?.let { tid to it }
            }.sortedByDescending { it.second.createdAt }
        }
        AlertDialog(
            onDismissRequest = { showUndoDialog = false },
            title = { Text("Undo file changes") },
            text = {
                if (turns.isEmpty()) {
                    Text("No file changes to undo.")
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            "Pick a checkpoint. Files return to their state before that turn, and the chat rolls back with them: the agent's messages from that point on are removed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        turns.forEach { (tid, msg) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        msg.text.lineSequence().firstOrNull()?.take(64)?.ifBlank { "(no text)" }
                                            ?: "(no text)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        formatRelativeTime(msg.createdAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = {
                                    showUndoDialog = false
                                    confirmRewindTurn = tid
                                }) { Text("Review…") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUndoDialog = false }) { Text("Close") }
            },
        )
    }

    // Undo confirmation: what exactly this rewind will revert, per file.
    confirmRewindTurn?.let { tid ->
        val preview by produceState<ChatViewModel.RewindPreview?>(initialValue = null, tid) {
            value = runCatching { viewModel.rewindPreview(tid) }.getOrNull()
        }
        AlertDialog(
            onDismissRequest = { confirmRewindTurn = null },
            title = { Text("Undo to this checkpoint?") },
            text = {
                when (val p = preview) {
                    null -> Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    ) { CircularProgressIndicator(Modifier.size(22.dp)) }

                    else -> Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            buildString {
                                append("${p.files.size} file(s) revert to their earlier state")
                                if (p.turns > 1) append(" (this turn + ${p.turns - 1} later)")
                                if (p.messagesDeleted > 0) {
                                    append(" · ${p.messagesDeleted} message(s) roll back")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (p.files.isEmpty()) {
                            Text(
                                "No tracked file changes in this window.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        p.files.take(12).forEach { stat ->
                            val scheme = MaterialTheme.colorScheme
                            val dotColor = when {
                                stat.willBeDeleted -> scheme.error
                                !stat.existsNow -> com.androidharness.app.ui.theme.LocalStatusColors.current.success
                                else -> com.androidharness.app.ui.theme.LocalStatusColors.current.warning
                            }
                            val note = when {
                                stat.willBeDeleted -> "created by the agent · will be deleted"
                                !stat.existsNow -> "missing on disk · will be restored"
                                else -> "reverts to its earlier state"
                            }
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .background(dotColor, CircleShape),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            stat.relPath.substringAfterLast('/'),
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val dir = stat.relPath.substringBeforeLast('/', "")
                                        if (dir.isNotBlank()) {
                                            Text(
                                                dir,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = scheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    if (stat.added > 0 || stat.removed > 0) {
                                        com.androidharness.app.ui.files.DiffStatText(stat.added, stat.removed)
                                    }
                                }
                                Text(
                                    note,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                            HorizontalDivider(
                                color = scheme.outlineVariant.copy(alpha = 0.4f),
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        if (p.files.size > 12) {
                            Text(
                                "+ ${p.files.size - 12} more file(s)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                val ready = preview != null &&
                    (preview!!.files.isNotEmpty() || preview!!.messagesDeleted > 0)
                TextButton(
                    enabled = ready,
                    onClick = {
                        confirmRewindTurn = null
                        scope.launch {
                            val message = viewModel.performRewind(tid)
                            snackbar.showSnackbar(message)
                        }
                    },
                ) { Text("Undo", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRewindTurn = null }) { Text("Cancel") }
            },
        )
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is NavEvent.NewChat -> onNewChat()
            }
        }
    }

    // Open sessions at the latest message: scroll to the true bottom once per
    // session, after its messages are both loaded from the DB and composed.
    var initialScrollDone by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.sessionId) {
        val sid = state.sessionId ?: return@LaunchedEffect
        if (initialScrollDone == sid) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.totalItemsCount to state.messages.size }
            .first { (total, size) -> size > 0 && total > 0 }
        if (initialScrollDone != sid) {
            initialScrollDone = sid
            listState.scrollToEnd()
        }
    }

    // Track real finger drags so programmatic scrolls can be told apart.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> gestureActive.value = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> gestureActive.value = false
            }
        }
    }

    // Unpin the MOMENT a real drag moves away from the bottom: follow-scrolls
    // fire on every layout change during a stream and would fight the gesture.
    LaunchedEffect(listState) {
        snapshotFlow {
            gestureActive.value &&
                !isAtBottom(listState.layoutInfo, listState.canScrollForward, tolerance = PIN_TOLERANCE_PX)
        }.distinctUntilChanged().collect { draggedAway ->
            if (draggedAway) pinnedToBottom = false
        }
    }

    // Re-attach when scrolling settles at the bottom (finger release, fling
    // end, or a programmatic scroll landing), pin, then a cheap exact snap.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (inProgress || gestureActive.value) return@collect
                if (isAtBottom(listState.layoutInfo, listState.canScrollForward, tolerance = PIN_TOLERANCE_PX)) {
                    pinnedToBottom = true
                    listState.snapToEndIfDrifted()
                }
            }
    }

    // Typewriter reveal: text appears at the stream's natural pace up to a
    // cap, so fast models don't dump whole paragraphs in a single frame.
    // Large deltas (tool-storm bursts, restored turns) snap instantly,
    // animating through thousands of pending chars re-renders the bubble
    // every frame for many seconds and saturates the main thread.
    // Committing the message shows the full text instantly.
    val streamingTarget = state.streamingText?.length ?: 0
    LaunchedEffect(streamingTarget) {
        if (revealedChars > streamingTarget) revealedChars = streamingTarget
        if (streamingTarget - revealedChars > TYPEWRITER_SNAP_THRESHOLD) {
            revealedChars = streamingTarget
            return@LaunchedEffect
        }
        while (revealedChars < streamingTarget) {
            revealedChars += minOf(streamingTarget - revealedChars, TYPEWRITER_STEP_CHARS)
            delay(TYPEWRITER_TICK_MS)
        }
    }

    // Once the stream commits, the held content must equal the committed text
    // exactly, so the live bubble swaps to the committed row pixel-identically.
    LaunchedEffect(state.streamingCommitted) {
        if (state.streamingCommitted) revealedChars = state.streamingText?.length ?: 0
    }

    // While pinned, keep the newest content in view with small exact scrollBy
    // deltas, cheap (no scrollToItem remeasure storm) and smooth (coalesced
    // at frame-ish cadence instead of 250ms jumps).
    LaunchedEffect(pinnedToBottom, listState) {
        if (!pinnedToBottom) return@LaunchedEffect
        var lastFollowAt = 0L
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount to (last?.let { it.index to (it.offset + it.size) })
        }
            .distinctUntilChanged()
            .collect {
                if (gestureActive.value || listState.isScrollInProgress) return@collect
                val wait = lastFollowAt + FOLLOW_MIN_INTERVAL_MS - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastFollowAt = System.currentTimeMillis()
                if (!pinnedToBottom || gestureActive.value || listState.isScrollInProgress) return@collect
                val info = listState.layoutInfo
                val total = info.totalItemsCount
                if (total == 0) return@collect
                val last = info.visibleItemsInfo.lastOrNull()
                if (last == null || last.index != total - 1) {
                    // The end isn't composed yet (large burst): snap straight to it.
                    listState.scrollToEnd()
                } else {
                    val delta = last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
                    if (delta > 0) listState.scrollBy(delta.toFloat())
                }
            }
    }

    // After a run that changed files finishes, offer a one-tap undo.
    var prevBusy by remember { mutableStateOf(false) }
    LaunchedEffect(state.busy) {
        val wasBusy = prevBusy
        prevBusy = state.busy
        if (wasBusy && !state.busy && state.turnsWithCheckpoints.isNotEmpty()) {
            val latestTurn = state.messages.lastOrNull {
                it.role == Role.ASSISTANT && it.turnId in state.turnsWithCheckpoints
            }?.turnId
            if (latestTurn != null) {
                val res = snackbar.showSnackbar(
                    "Files changed", actionLabel = "Undo", duration = SnackbarDuration.Short,
                )
                if (res == SnackbarResult.ActionPerformed) confirmRewindTurn = latestTurn
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.attachImage(it) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            val ap = state.activeProvider
            // The FULL global ladder for every model (Hermes-style): picking a
            // rung the model doesn't natively speak resolves down the chain
            // inside setClamped; menus render plain labels without captions.
            val thinkingLevels = remember(state.effectiveModel, ap) {
                com.androidharness.app.agent.ThinkingSpecs.visibleLevels(
                    state.effectiveModel,
                    com.androidharness.app.llm.ModelsDev.providerKeyFor(ap?.baseUrl),
                )
            }
            MainHeader(
                sessionTitle = state.sessionTitle,
                busy = state.busy,
                pickerLabel = when {
                    ap == null -> "Add a provider to get started"
                    else -> "${ap.name} · ${state.effectiveModel ?: ap.model}"
                },
                mode = state.mode,
                thinkingLevel = state.thinkingLevel,
                thinkingLevels = thinkingLevels,
                permissionMode = state.permissionMode,
                canUndo = state.turnsWithCheckpoints.isNotEmpty(),
                onOpenDrawer = onOpenDrawer,
                onPickModel = { showModelPicker = true },
                onOpenTerminal = onOpenTerminal,
                onSetThinking = viewModel::setThinkingLevel,
                onSetPermission = viewModel::setPermissionMode,
                onSetMode = viewModel::setMode,
                onOpenContext = { showContext = true },
                onOpenUndo = { showUndoDialog = true },
                onOpenFiles = onOpenFiles,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
            ) {
                val toolResults = remember(state.messages) {
                    state.messages.filter { it.role == Role.TOOL }
                        .associateBy { it.toolCallId ?: "" }
                }
                val runningIds = remember(state.runningCalls) {
                    state.runningCalls.map { it.id }.toSet()
                }

                // FAB visibility tracks the pin (not the raw at-bottom check),
                // so the brief off-bottom moments between follow scrolls don't
                // flash the button during streaming. The badge pulses when new
                // content landed while detached.
                var unreadWhileAway by remember { mutableStateOf(false) }
                LaunchedEffect(pinnedToBottom) { if (pinnedToBottom) unreadWhileAway = false }
                LaunchedEffect(
                    state.messages.size,
                    state.streamingText?.length,
                    state.streamingThinking?.length,
                ) {
                    if (!pinnedToBottom) unreadWhileAway = true
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.messages.isEmpty() && state.streamingText == null) {
                        item {
                            EmptyState(
                                hasProvider = state.activeProvider != null,
                                onSuggestion = { viewModel.send(it) },
                                onAddProvider = { showProviderManager = true },
                            )
                        }
                    }

                    for ((messageIndex, message) in state.messages.withIndex()) {
                        // Inner subagent turns persist with the parent task's
                        // call id on the assistant row, they render on the
                        // subagent's own page, never in the main list.
                        if (message.role == Role.ASSISTANT && message.toolCallId != null) continue
                        val messageKey = message.id ?: "${message.role.name}-${message.createdAt}-$messageIndex"
                        when (message.role) {
                            Role.USER -> item(key = "message-$messageKey-user") {
                                Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        slashInvokedSkillName(message.text)?.let { skillName ->
                                            SkillUsedBadge(skillName)
                                        }
                                        UserBubble(
                                            slashSkillInstruction(message.text)
                                                ?.ifBlank { "/${slashInvokedSkillName(message.text)}" }
                                                ?: message.text,
                                            message.images,
                                            onLongPress = { actionsMessage = message },
                                        )
                                        if (message.turnId in state.turnsWithCheckpoints) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CopyIconButton(message.text)
                                                UndoIconButton(
                                                    onClick = { message.turnId?.let { confirmRewindTurn = it } },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Role.ASSISTANT -> {
                                val canRewind = message.turnId != null &&
                                    message.turnId in state.turnsWithCheckpoints
                                if (message.thinking.isNotBlank()) {
                                    item(key = "message-$messageKey-thinking") {
                                        Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) { ThinkingBlock(message.thinking, durationMs = message.thinkingMs) }
                                    }
                                }
                                if (message.text.isNotBlank()) {
                                    item(key = "message-$messageKey-text") {
                                        Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                            Column {
                                                val used = message.toolCalls
                                                    .filter { it.name == "skill_view" }
                                                    .mapNotNull { call ->
                                                        runCatching {
                                                            kotlinx.serialization.json.Json.parseToJsonElement(call.argumentsJson)
                                                                .jsonObject["name"]?.jsonPrimitive?.content
                                                        }.getOrNull()
                                                    }
                                                    .distinct()
                                                if (used.isNotEmpty()) {
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                        modifier = Modifier.padding(bottom = 4.dp),
                                                    ) {
                                                        used.forEach { SkillUsedBadge(it) }
                                                    }
                                                }
                                                AssistantText(message.text)
                                                // Turn-final extras: diff chips + how
                                                // long the whole turn took.
                                                val isTurnFinal = state.messages
                                                    .lastOrNull { m ->
                                                        m.role == Role.ASSISTANT && m.turnId == message.turnId
                                                    }?.id == message.id
                                                val edits = state.fileEditsByTurn[message.turnId].orEmpty()
                                                if (isTurnFinal && edits.isNotEmpty()) {
                                                    FileEditsCard(edits, onOpenFile, Modifier.padding(top = 4.dp))
                                                }
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    CopyIconButton(message.text)
                                                    if (canRewind) {
                                                        UndoIconButton(
                                                            onClick = { message.turnId?.let { confirmRewindTurn = it } },
                                                        )
                                                    }
                                                    Spacer(Modifier.weight(1f))
                                                    if (isTurnFinal) {
                                                        val userAt = state.messages.firstOrNull { m ->
                                                            m.role == Role.USER && m.turnId == message.turnId
                                                        }?.createdAt ?: 0L
                                                        val worked = formatDuration((message.createdAt - userAt).coerceAtLeast(0))
                                                        if (worked.isNotEmpty()) {
                                                            Text(
                                                                worked,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                // Subagents get their own treatment: 2+ run
                                // in one bounded pager card, a lone one keeps
                                // its standalone card; other tools group as before.
                                val taskCalls = message.toolCalls.filter { it.name == "task" }
                                val otherCalls = message.toolCalls.filter { it.name != "task" }
                                if (taskCalls.size >= 2) {
                                    item(key = "message-$messageKey-subagents") {
                                        Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                            SubagentPagerCard(
                                                calls = taskCalls,
                                                results = toolResults,
                                                runningIds = runningIds,
                                                subagentSteps = state.subagentSteps,
                                                onOpen = onOpenSubagent,
                                            )
                                        }
                                    }
                                } else if (taskCalls.size == 1) {
                                    val call = taskCalls[0]
                                    item(key = call.id) {
                                        Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                            SubagentCard(
                                                call = call,
                                                steps = state.subagentSteps[call.id].orEmpty(),
                                                result = toolResults[call.id],
                                                running = call.id in runningIds,
                                                onOpenFile = onOpenFile,
                                                onOpenFull = { onOpenSubagent(call.id) },
                                            )
                                        }
                                    }
                                }
                                if (otherCalls.size >= 3) {
                                    item(key = "message-$messageKey-tools") {
                                        Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                            ToolGroupCard(
                                                calls = otherCalls,
                                                results = toolResults,
                                                runningIds = runningIds,
                                                onOpenFile = onOpenFile,
                                                subagentSteps = state.subagentSteps,
                                            )
                                        }
                                    }
                                } else {
                                    for (call in otherCalls) {
                                        item(key = call.id) {
                                            Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                                ToolCallCard(
                                                    call = call,
                                                    result = toolResults[call.id],
                                                    running = call.id in runningIds,
                                                    onOpenFile = onOpenFile,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Role.TOOL -> Unit
                            Role.SYSTEM -> Unit
                        }
                    }

                    // Live streaming items are rendered only while not yet in the committed list.
                    val streamAlreadyCommitted = state.streamingMessageId != null &&
                        state.messages.any { it.id == state.streamingMessageId }
                    if (!streamAlreadyCommitted) {
                        val streamKey = state.streamingMessageId ?: state.currentTurnId ?: "idle"
                        state.streamingThinking?.let { thinking ->
                            if (thinking.isNotBlank()) {
                                item(key = "streaming-$streamKey-thinking") {
                                    Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) { ThinkingBlock(thinking, live = true) }
                                }
                            }
                        }
                        state.streamingText?.let { streaming ->
                            item(key = "streaming-$streamKey-text") {
                                Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                    AssistantText(
                                        streaming.take(revealedChars),
                                        streaming = !state.streamingCommitted,
                                    )
                                }
                            }
                        }
                    }

                    state.pendingApproval?.let { approval ->
                        item(key = "approval") {
                            Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                ApprovalCard(
                                    approval = approval,
                                    onApprove = viewModel::approve,
                                    onDeny = viewModel::deny,
                                )
                            }
                        }
                    }

                    state.pendingEnvironment?.let { request ->
                        item(key = "env-install") {
                            Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                EnvironmentInstallCard(
                                    request = request,
                                    envState = state.envState,
                                    onInstall = viewModel::approveEnvironmentInstall,
                                    onSkip = viewModel::denyEnvironmentInstall,
                                )
                            }
                        }
                    }

                    state.pendingQuestion?.let { question ->
                        item(key = "question") {
                            Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                QuestionCard(
                                    question = question,
                                    onAnswer = viewModel::answerQuestion,
                                )
                            }
                        }
                    }

                    state.pendingPlan?.let { plan ->
                        item(key = "plan") {
                            Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                PlanApprovalCard(
                                    plan = plan,
                                    onApprove = viewModel::executePendingPlan,
                                    onDiscard = viewModel::discardPendingPlan,
                                )
                            }
                        }
                    }
                }

                // Floating jump-to-latest button while detached from the
                // bottom; pulses when new content lands while away.
                SlashSuggestions(
                    state = state,
                    query = composerText,
                    expanded = slashExpanded && attachedSkill == null,
                    onPick = { cmd, kind ->
                        when (val action = SlashCommands.pickAction(cmd, composerText, kind)) {
                            is SlashCommands.Pick.AttachSkill -> {
                                attachedSkill = action.name
                                composerText = action.leftover
                                slashExpanded = false
                            }
                            is SlashCommands.Pick.Insert -> {
                                composerText = action.text
                                slashExpanded = true
                            }
                            is SlashCommands.Pick.Send -> {
                                viewModel.send(action.text)
                                composerText = ""
                                attachedSkill = null
                                slashExpanded = false
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                )
                ScrollToBottomFab(
                    visible = !pinnedToBottom,
                    unread = unreadWhileAway,
                    onJump = {
                        pinnedToBottom = true
                        unreadWhileAway = false
                        scope.launch {
                            val total = listState.layoutInfo.totalItemsCount
                            if (total == 0) return@launch
                            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                            if (lastVisible == null || lastVisible.index < total - 1) {
                                // Far from the end: animated scroll to the last
                                // item, then a short glide to the exact bottom.
                                listState.animateScrollToItem(total - 1)
                            }
                            val info = listState.layoutInfo
                            val last = info.visibleItemsInfo.lastOrNull()
                            if (last != null && last.index == info.totalItemsCount - 1) {
                                val delta = (last.offset + last.size + info.afterContentPadding -
                                    info.viewportEndOffset).toFloat()
                                if (delta > 0f) {
                                    listState.animateScrollBy(delta, tween(280, easing = FastOutSlowInEasing))
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 10.dp),
                )
                }

                if (state.todos.isNotEmpty()) {
                    TodoCard(state.todos)
                }

                state.queuedMessage?.let { queued ->
                    QueuedMessageChip(
                        text = queued,
                        onCancel = viewModel::cancelQueuedMessage,
                        onSteer = viewModel::steerQueuedMessage,
                    )
                }

                if (state.attachments.isNotEmpty()) {
                    AttachmentChips(
                        attachments = state.attachments,
                        onRemove = viewModel::removeAttachment,
                    )
                }

                AgentStatusBar(action = state.currentAction, busy = state.busy)
                MessageComposer(
                    busy = state.busy,
                    text = composerText,
                    attachedSkill = attachedSkill,
                    onTextChange = { input ->
                        composerText = input
                        slashExpanded = attachedSkill == null && input.startsWith("/")
                    },
                    onClearSkill = { attachedSkill = null },
                    onSend = {
                        val payload = SlashCommands.composeSend(attachedSkill, composerText)
                        if (payload.isNotBlank()) {
                            viewModel.send(payload)
                            composerText = ""
                            attachedSkill = null
                            slashExpanded = false
                        }
                    },
                    onStop = viewModel::stop,
                    onAttach = { galleryLauncher.launch("image/*") },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers

/** Small copy icon with a brief check confirmation, ChatGPT-style action rows. */
@Composable
private fun CopyIconButton(text: String) {
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    IconButton(onClick = {
        clipboard.setText(AnnotatedString(text))
        copied = true
    }, modifier = Modifier.size(28.dp)) {
        Icon(
            if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
            contentDescription = "Copy",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
}

@Composable
private fun UndoIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(
            Icons.Outlined.History,
            contentDescription = "Undo file changes from this turn",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Collapsible "Edited files" summary for a finished turn: the header carries
 * the total "+N −M", expanding lists every edited file (merged across all
 * edits in the turn) vertically, each row tapping through to the editor.
 */
@Composable
private fun FileEditsCard(
    edits: List<com.androidharness.app.data.db.FileEditEntity>,
    onOpenFile: (path: String, line: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val merged = remember(edits) {
        edits.groupBy { it.relPath }.map { (path, group) ->
            path to Pair(group.sumOf { it.added }, group.sumOf { it.removed })
        }
    }
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = fastEffectsSpec(),
        label = "file edits chevron",
    )

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    "Edited files",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                DiffStatText(merged.sumOf { it.second.first }, merged.sumOf { it.second.second })
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse edited files" else "Expand edited files",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = chevronRotation },
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(fastEffectsSpec()) + expandVertically(fastEffectsSpec()),
                exit = fadeOut(fastEffectsSpec()) + shrinkVertically(fastEffectsSpec()),
            ) {
                Column {
                    merged.forEachIndexed { index, (path, counts) ->
                        val (added, removed) = counts
                        if (index > 0) {
                            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenFile(path, null) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    path.substringAfterLast('/'),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val parent = path.substringBeforeLast('/')
                                if (parent != path) {
                                    Text(
                                        parent,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            DiffStatText(added, removed)
                        }
                    }
                }
            }
        }
    }
}

/** True when the last item's bottom edge sits within [tolerance]px of the viewport bottom. */
private fun isAtBottom(info: LazyListLayoutInfo, canScrollForward: Boolean, tolerance: Int = 64): Boolean {
    // Not scrolled to the bottom at all, and nowhere left to scroll (short list).
    if (!canScrollForward) return true
    val last = info.visibleItemsInfo.lastOrNull() ?: return false
    if (last.index != info.totalItemsCount - 1) return false
    return last.offset + last.size <= info.viewportEndOffset + tolerance
}

/**
 * Instantly scrolls to the very end of the content, bottom padding included.
 * `scrollToItem(last)` alone only aligns the last item's TOP with the viewport
 * top when the item is taller than the viewport; the extra scrollBy (clamped
 * by the list itself) consumes whatever remains.
 */
private suspend fun LazyListState.scrollToEnd() {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    scrollToItem(last)
    scrollBy(FORWARD_FAR_PX)
}

/** Cheap exact snap to the true bottom; a no-op unless the end is composed. */
private suspend fun LazyListState.snapToEndIfDrifted() {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return
    if (last.index != info.totalItemsCount - 1) return
    val delta = last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
    if (delta > 0) scrollBy(delta.toFloat())
}

/** Floating jump-to-latest button; pulses when content landed while away. */
@Composable
private fun ScrollToBottomFab(
    visible: Boolean,
    unread: Boolean,
    onJump: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(fastEffectsSpec()) + scaleIn(initialScale = 0.85f, animationSpec = fastEffectsSpec()),
        exit = fadeOut(fastEffectsSpec()) + scaleOut(targetScale = 0.85f, animationSpec = fastEffectsSpec()),
        modifier = modifier,
    ) {
        val pulse = if (unread) {
            val transition = rememberInfiniteTransition(label = "unread pulse")
            transition.animateFloat(
                initialValue = 1f,
                targetValue = 1.12f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "unread pulse scale",
            ).value
        } else 1f
        Surface(
            onClick = onJump,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier
                .size(40.dp)
                // graphicsLayer scale: the pulse redraws without re-layout.
                .graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Scroll to latest",
                    tint = if (unread) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Cost dialog

@Composable
private fun CostDialog(
    state: ChatUiState,
    onDismiss: () -> Unit,
) {
    val model = state.effectiveModel
    val providerKey = state.activeProvider?.let { com.androidharness.app.llm.ModelsDev.providerKeyFor(it.baseUrl) }
    val cost: Double? = if (state.sessionModelUsage.isNotEmpty()) {
        state.sessionModelUsage.sumOf { row ->
            val pKey = com.androidharness.app.llm.ModelsDev.providerKeyFor(row.providerName)
            com.androidharness.app.llm.ModelPrices.estimate(
                model = row.model,
                totalInputTokens = row.inputTokens,
                outputTokens = row.outputTokens,
                cachedTokens = row.cachedTokens,
                cacheWriteTokens = row.cacheWriteTokens,
                providerKey = pKey,
            ) ?: 0.0
        }
    } else {
        model?.let {
            com.androidharness.app.llm.ModelPrices.estimate(
                it,
                state.usage.totalInput,
                state.usage.totalOutput,
                state.usage.totalCached,
                state.usage.totalCacheWrite,
                providerKey = providerKey,
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Session cost") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Input: ${formatTokenCount(state.usage.totalInput)}")
                Text("Output: ${formatTokenCount(state.usage.totalOutput)}")
                val hitRateStr = when {
                    state.usage.requests == 0L && state.busy -> "Calculating…"
                    state.usage.totalInput > 0 -> "${"%.1f".format(state.usage.avgCacheHitRate * 100)}%"
                    else -> "0.0%"
                }
                Text("Cache hit rate: $hitRateStr")
                Text(
                    "Hit rate updates after each completed turn",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.usage.totalCacheWrite > 0) {
                    Text(
                        "Cache writes: ${formatTokenCount(state.usage.totalCacheWrite)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (cost != null) {
                    Text("Estimated cost: \$${"%.4f".format(cost)}")
                } else {
                    Text("Cost estimate unknown for this model", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

// Typewriter reveal pacing: at each tick reveal up to this many chars, so
// gentle streams pass through unchanged while fast bursts get smoothed.
private const val TYPEWRITER_TICK_MS = 16L
private const val TYPEWRITER_STEP_CHARS = 7
// Deltas larger than this snap instead of animating (burst catch-up).
private const val TYPEWRITER_SNAP_THRESHOLD = 220
// Follow-scroll coalescing: during a stream the list follows the newest
// content with small scrollBy deltas at most this often (ms between scrolls).
private const val FOLLOW_MIN_INTERVAL_MS = 64L
// How far off the bottom (px) a drag must go to detach the pin, and how close
// a settle must land to re-attach it.
private const val PIN_TOLERANCE_PX = 96
// scrollBy clamping makes a huge forward scroll stop exactly at the end.
private const val FORWARD_FAR_PX = 100_000f
