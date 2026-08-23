package com.androidharness.app.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.core.Role
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.ui.chat.components.AgentStatusBar
import com.androidharness.app.ui.chat.components.ApprovalCard
import com.androidharness.app.ui.chat.components.AssistantText
import com.androidharness.app.ui.chat.components.AttachmentChips
import com.androidharness.app.ui.chat.components.EmptyState
import com.androidharness.app.ui.chat.components.EnvironmentInstallCard
import com.androidharness.app.ui.chat.components.MainHeader
import com.androidharness.app.ui.chat.components.MessageComposer
import com.androidharness.app.ui.chat.components.PlanApprovalCard
import com.androidharness.app.ui.chat.components.QuestionCard
import com.androidharness.app.ui.chat.components.QueuedMessageChip
import com.androidharness.app.ui.chat.components.RewindButton
import com.androidharness.app.ui.chat.components.SlashSuggestions
import com.androidharness.app.ui.chat.components.SubagentCard
import com.androidharness.app.ui.chat.components.ThinkingBlock
import com.androidharness.app.ui.chat.components.TodoCard
import com.androidharness.app.ui.chat.components.ToolCallCard
import com.androidharness.app.ui.chat.components.ToolGroupCard
import com.androidharness.app.ui.chat.components.UserBubble
import com.androidharness.app.ui.common.formatRelativeTime
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
    onManageProviders: () -> Unit,
    onOpenFile: (path: String, line: Int?) -> Unit,
    onNewChat: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var showContext by remember { mutableStateOf(false) }
    var slashExpanded by remember { mutableStateOf(false) }

    val clipboard = LocalClipboardManager.current
    var actionsMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var confirmingEdit by remember { mutableStateOf<Pair<ChatMessage, String>?>(null) }
    var showUndoDialog by remember { mutableStateOf(false) }
    var selectingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    val scope = rememberCoroutineScope()

    // Bottom-pinning for the message list: true while the newest content
    // should stay in view; only a real user drag away from the bottom clears
    // it — programmatic scrolls (follow, jump) never touch the pin.
    var pinnedToBottom by remember { mutableStateOf(true) }

    // True while a finger drag is driving the list (from interactionSource).
    val gestureActive = remember { mutableStateOf(false) }

    // Typewriter reveal for streaming text: characters shown so far this
    // message. Keyed on the message id so it survives the commit handoff.
    var revealedChars by remember(state.streamingMessageId) { mutableStateOf(0) }

    if (showContext) {
        ContextUsageDialog(state = state, onDismiss = { showContext = false })
    }
    if (state.showCostDialog) {
        CostDialog(state = state, onDismiss = viewModel::dismissCostDialog)
    }

    // Long-press message actions (copy; edit for user messages).
    actionsMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { actionsMessage = null },
            title = { Text(if (msg.role == Role.USER) "Your message" else "Agent message") },
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
                        selectingMessage = msg
                        actionsMessage = null
                    }) { Text("Select text") }
                }
            },
            dismissButton = {
                Row {
                    if (msg.role == Role.USER) {
                        TextButton(onClick = {
                            editingMessage = msg
                            actionsMessage = null
                        }) { Text("Edit") }
                    }
                    TextButton(onClick = { actionsMessage = null }) { Text("Close") }
                }
            },
        )
    }

    // Word-by-word selection for any message, via the system copy affordance.
    selectingMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { selectingMessage = null },
            confirmButton = {
                TextButton(onClick = { selectingMessage = null }) { Text("Close") }
            },
            title = { Text("Select text") },
            text = {
                SelectionContainer {
                    Text(
                        msg.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
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

    // Editing a message rewinds files and truncates the chat — warn first.
    confirmingEdit?.let { (msg, newText) ->
        AlertDialog(
            onDismissRequest = { confirmingEdit = null },
            title = { Text("Edit this message?") },
            text = {
                Text(
                    "Everything after this message will be deleted, and any file changes made " +
                        "after it will be undone — files are restored to how they were at that " +
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
                            "Restore the workspace to before a turn. Only files are restored; messages stay.",
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
                                    viewModel.rewindToTurn(tid)
                                    showUndoDialog = false
                                }) { Text("Restore") }
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
    // end, or a programmatic scroll landing) — pin, then a cheap exact snap.
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
    // Large deltas (tool-storm bursts, restored turns) snap instantly —
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
    // deltas — cheap (no scrollToItem remeasure storm) and smooth (coalesced
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
                if (res == SnackbarResult.ActionPerformed) viewModel.rewindToTurn(latestTurn)
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.attachImage(it) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            MainHeader(
                sessionTitle = state.sessionTitle,
                busy = state.busy,
                statusText = when {
                    state.busy -> state.currentAction ?: "Working in your workspace…"
                    state.activeProvider == null -> "Add a provider to get started"
                    else -> state.activeProvider!!.let { "${it.name} · ${it.model}" }
                },
                providers = state.providers,
                activeProviderId = state.activeProviderId,
                mode = state.mode,
                thinkingLevel = state.thinkingLevel,
                permissionMode = state.permissionMode,
                canUndo = state.turnsWithCheckpoints.isNotEmpty(),
                onOpenDrawer = onOpenDrawer,
                onManageProviders = onManageProviders,
                onSelectProvider = viewModel::setActiveProvider,
                onOpenTerminal = onOpenTerminal,
                onSetThinking = viewModel::setThinkingLevel,
                onSetPermission = viewModel::setPermissionMode,
                onSetMode = viewModel::setMode,
                onOpenContext = { showContext = true },
                onOpenUndo = { showUndoDialog = true },
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
                                onAddProvider = onManageProviders,
                            )
                        }
                    }

                    for ((messageIndex, message) in state.messages.withIndex()) {
                        val messageKey = message.id ?: "${message.role.name}-${message.createdAt}-$messageIndex"
                        when (message.role) {
                            Role.USER -> item(key = "message-$messageKey-user") {
                                Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                    UserBubble(
                                        message.text,
                                        message.images,
                                        onLongPress = { actionsMessage = message },
                                    )
                                }
                            }
                            Role.ASSISTANT -> {
                                val canRewind = message.turnId != null &&
                                    message.turnId in state.turnsWithCheckpoints
                                if (message.thinking.isNotBlank()) {
                                    item(key = "message-$messageKey-thinking") {
                                        Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) { ThinkingBlock(message.thinking) }
                                    }
                                }
                                if (message.text.isNotBlank()) {
                                    item(key = "message-$messageKey-text") {
                                        Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                AssistantText(
                                                    message.text,
                                                    modifier = Modifier.weight(1f),
                                                    onLongPress = { actionsMessage = message },
                                                )
                                                if (canRewind) {
                                                    RewindButton(
                                                        hasCheckpoints = true,
                                                        onRewind = { message.turnId?.let { viewModel.rewindToTurn(it) } },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                if (message.toolCalls.size >= 3) {
                                    item(key = "message-$messageKey-tools") {
                                        Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                            ToolGroupCard(
                                                calls = message.toolCalls,
                                                results = toolResults,
                                                runningIds = runningIds,
                                                onOpenFile = onOpenFile,
                                                subagentSteps = state.subagentSteps,
                                            )
                                        }
                                    }
                                } else {
                                    for (call in message.toolCalls) {
                                        item(key = call.id) {
                                            Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                                if (call.name == "task") {
                                                    SubagentCard(
                                                        call = call,
                                                        steps = state.subagentSteps[call.id].orEmpty(),
                                                        result = toolResults[call.id],
                                                        running = call.id in runningIds,
                                                        onOpenFile = onOpenFile,
                                                    )
                                                } else {
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
                            }
                            Role.TOOL -> Unit
                            Role.SYSTEM -> Unit
                        }
                    }

                    // Live streaming items are keyed by the MESSAGE id, which
                    // the committed row reuses — the swap is an in-place
                    // content change, never a remove + re-insert.
                    val streamKey = state.streamingMessageId ?: state.currentTurnId ?: "idle"
                    state.streamingThinking?.let { thinking ->
                        if (thinking.isNotBlank()) {
                            item(key = "message-$streamKey-thinking") {
                                Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) { ThinkingBlock(thinking, live = true) }
                            }
                        }
                    }
                    state.streamingText?.let { streaming ->
                        item(key = "message-$streamKey-text") {
                            Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                AssistantText(
                                    streaming.take(revealedChars),
                                    streaming = !state.streamingCommitted,
                                )
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

                SlashSuggestions(
                    state = state,
                    expanded = slashExpanded,
                    onPick = { cmd -> viewModel.send(cmd) },
                )

                state.queuedMessage?.let { queued ->
                    QueuedMessageChip(text = queued, onCancel = viewModel::cancelQueuedMessage)
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
                    onSend = { text -> viewModel.send(text); slashExpanded = false },
                    onStop = viewModel::stop,
                    onAttach = { galleryLauncher.launch("image/*") },
                    onTextChange = { input ->
                        slashExpanded = input.startsWith("/")
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers

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
    val cost = state.activeProvider?.let {
        com.androidharness.app.llm.ModelPrices.estimate(
            it.model,
            state.usage.totalInput,
            state.usage.totalOutput,
            state.usage.totalCached,
            state.usage.totalCacheWrite,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Session cost") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Input: ${formatTokenCount(state.usage.totalInput)}")
                Text("Output: ${formatTokenCount(state.usage.totalOutput)}")
                Text("Requests: ${state.usage.requests}")
                Text("Cache hit rate: ${"%.1f".format(state.usage.avgCacheHitRate * 100)}%")
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
