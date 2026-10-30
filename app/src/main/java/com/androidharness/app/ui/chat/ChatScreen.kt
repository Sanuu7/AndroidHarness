package com.androidharness.app.ui.chat

import com.androidharness.app.ui.theme.*
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import com.androidharness.app.artifacts.ArtifactAttachment
import com.androidharness.app.core.response.ResponseAttachment
import com.androidharness.app.core.response.UserMessageContent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.androidharness.app.R
import com.androidharness.app.ui.chat.components.ChatAppearance

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.audio.TtsError
import com.androidharness.app.data.audio.TtsPhase
import com.androidharness.app.ui.common.TtsTermsDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.ForkRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.androidharness.app.ui.common.formatTokenCount
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import android.widget.Toast
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.androidharness.app.agent.AgentMode
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import com.androidharness.app.skills.slashInvokedSkillName
import com.androidharness.app.skills.slashSkillInstruction
import com.androidharness.app.ui.chat.components.CompactionBanner
import com.androidharness.app.ui.chat.components.CompactionResultCard
import com.androidharness.app.ui.chat.components.CompactionNoticeLine
import com.androidharness.app.ui.chat.components.FileAttachmentChips
import com.androidharness.app.ui.chat.components.MentionSuggestions
import com.androidharness.app.ui.chat.components.SkillsPickerSheet
import com.androidharness.app.ui.chat.components.SkillUsedBadge
import com.androidharness.app.ui.chat.components.SlashSuggestions
import com.androidharness.app.ui.chat.components.SubagentCard
import com.androidharness.app.ui.chat.components.SubagentPagerCard
import com.androidharness.app.ui.chat.components.ThinkingBlock
import com.androidharness.app.ui.chat.components.TodoCard
import com.androidharness.app.ui.chat.components.ToolCallCard
import com.androidharness.app.ui.chat.components.ToolGroupCard
import com.androidharness.app.ui.chat.components.TurnActivityCard
import com.androidharness.app.ui.chat.components.UserBubble
import com.androidharness.app.ui.chat.components.WebPreviewSheet
import com.androidharness.app.browser.BrowserToolActivity
import com.androidharness.app.ui.common.formatRelativeTime
import com.androidharness.app.ui.common.formatDuration
import com.androidharness.app.ui.files.DiffStatText
import com.androidharness.app.ui.github.GitHubWorkspaceDialog
import com.androidharness.app.ui.settings.ProviderManagerSheet
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.fastEffectsSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The chat screen. All visual components live in `ui/chat/components/`; this file
 * owns state wiring, dialogs, and the message list assembly.
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onOpenFile: (path: String, line: Int?) -> Unit,
    onNewChat: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenSubagent: (toolCallId: String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onNavigateToSession: (sessionId: String) -> Unit = {},
    searchMessageId: String? = null,
    onSelectSection: (com.androidharness.app.data.ConversationKind) -> Unit = {},
) {
    ChatAppearance {
        val appearance by viewModel.container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
        val conversation by viewModel.state.collectAsStateWithLifecycle()
        com.androidharness.app.ui.theme.HarnessCanvasBackdrop(appearance.harnessCanvas, conversation.conversation.isChat, Modifier.fillMaxSize()) {
        ChatScreenContent(viewModel, onOpenDrawer, onOpenFile, onNewChat, onOpenTerminal,
            onOpenFiles, onOpenSubagent, onOpenSettings, onNavigateToSession, searchMessageId, onSelectSection)
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun ChatScreenContent(
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onOpenFile: (path: String, line: Int?) -> Unit,
    onNewChat: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenSubagent: (toolCallId: String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onNavigateToSession: (sessionId: String) -> Unit = {},
    searchMessageId: String? = null,
    onSelectSection: (com.androidharness.app.data.ConversationKind) -> Unit = {},
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    // Lazy list content and its derived lookup maps must use the same snapshot.
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val subagentResults by remember(state.sessionId) {
        state.sessionId?.let { sid -> viewModel.container.runManager.subagents.forSession(sid)
            .map { list -> list.associate { it.id to (it.status to it.output) } }.distinctUntilChanged() }
            ?: kotlinx.coroutines.flow.flowOf(emptyMap())
    }.collectAsStateWithLifecycle(initialValue = emptyMap())
    val globalSettings by viewModel.container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val showChatStartControls = state.conversation.isChat && state.conversationReady && !state.isLoadingMessages &&
        state.messages.isEmpty() && !state.busy && state.streamingText == null
    val effectiveImageFrequency = com.androidharness.app.core.ChatExperience
        .effectiveImageFrequency(state.conversation.imageFrequency, globalSettings.chatImageFrequency)
    var showCodeHandoff by remember(state.sessionId) { mutableStateOf(false) }
    val codeHandoff = remember(state.conversation.isChat, state.messages) {
        if (state.conversation.isChat) com.androidharness.app.core.ChatExperience.codeHandoffOffer(state.messages) else null
    }
    if (showCodeHandoff) {
        val projects by viewModel.container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
        com.androidharness.app.ui.chat.components.ChatCodeHandoffSheet(
            projects = projects, initialProjectId = viewModel.activeWorkspace.value?.id,
            enabled = !state.busy && !state.conversationSaving,
            onDismiss = { showCodeHandoff = false },
            onContinue = { projectId -> viewModel.continueInCode(projectId); showCodeHandoff = false })
    }
    val ttsState by viewModel.container.tts.state.collectAsStateWithLifecycle()
    var ttsTermsError by remember { mutableStateOf<TtsError?>(null) }
    LaunchedEffect(Unit) {
        viewModel.container.tts.errors.collect { error ->
            if (error.termsRequired) ttsTermsError = error
            else Toast.makeText(uiContext, error.message, Toast.LENGTH_LONG).show()
        }
    }
    ttsTermsError?.let { TtsTermsDialog(it) { ttsTermsError = null } }
    val webPreviewAvailable by viewModel.webPreviewAvailable.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) {
        viewModel.refreshWebPreviewSources()
    }
    val desktopLayout = com.androidharness.app.ui.LocalDesktopLayout.current
    val currentProject by viewModel.activeWorkspace.collectAsStateWithLifecycle(initialValue = null)
    var githubProject by remember { mutableStateOf<Pair<String, String>?>(null) }
    val listState = rememberChatListState()
    val snackbar = remember { SnackbarHostState() }
    var showContext by remember { mutableStateOf(false) }

    githubProject?.let { (id, name) ->
        GitHubWorkspaceDialog(viewModel.container, id, name,
            onOpenSettings = {
                viewModel.container.pendingSettingsScroll.value = "github"
                onOpenSettings()
            }, onDismiss = { githubProject = null }, sessionId = state.sessionId)
    }
    var activeModelPickerTarget by remember { mutableStateOf<ModelSelectionTarget?>(null) }
    var activeProviderManagerTarget by remember { mutableStateOf<ModelSelectionTarget?>(null) }
    var providerControlRoute by remember { mutableStateOf<Pair<com.androidharness.app.llm.ProviderConfig?, ModelSelectionTarget>?>(null) }
    var addProviderInitially by remember { mutableStateOf(false) }
    var showFallbacks by rememberSaveable { mutableStateOf(false) }
    var fallbackProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    val showModelPicker = activeModelPickerTarget != null
    val showProviderManager = activeProviderManagerTarget != null || providerControlRoute != null
    var showWebPreview by remember { mutableStateOf(false) }
    var webPreviewUrl by remember { mutableStateOf<String?>(null) }
    val imageSources = remember(state.messages) {
        com.androidharness.app.core.ChatActivityPresentation.imageSources(state.messages)
    }
    val responseImages = remember(state.messages, state.conversation.isChat) {
        if (state.conversation.isChat) com.androidharness.app.core.ChatResponseImages.available(state.messages) else null
    }
    var viewerImageUrl by remember { mutableStateOf<String?>(null) }
    viewerImageUrl?.let { imageUrl ->
        com.androidharness.app.ui.chat.components.ImageViewerDialog(
            imageUrl = imageUrl,
            sourceUrl = imageSources[imageUrl],
            onDismiss = { viewerImageUrl = null },
            onOpenSource = { source -> viewerImageUrl = null; webPreviewUrl = source; showWebPreview = true },
        )
    }
    fun watchBrowser(target: String?) {
        webPreviewUrl = target ?: viewModel.container.browser.getActiveUrl()?.takeUnless { it == "about:blank" }
        showWebPreview = true
    }
    val browserTargets = remember(state.messages) { BrowserToolActivity.targets(state.messages) }
    val latestBrowserActivity = remember(state.messages, state.runningCalls, state.pendingApproval, browserTargets) {
        val call = state.runningCalls.lastOrNull { BrowserToolActivity.isBrowserTool(it.name) }
            ?: state.messages.flatMap { it.toolCalls }.lastOrNull { BrowserToolActivity.isBrowserTool(it.name) }
        call?.let {
            BrowserToolActivity.from(it, state.messages.lastOrNull { m -> m.role == Role.TOOL && m.toolCallId == it.id },
                state.runningCalls.any { running -> running.id == it.id }, browserTargets[it.id], state.pendingApproval?.call?.id == it.id)
        }
    }
    var slashExpanded by remember { mutableStateOf(false) }
    // The composer carries its own selection so programmatic edits (mention
    // picks, share prefills) move the cursor instead of leaving it where the
    // user last typed. composerText mirrors value.text for the readers.
    var composerValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val composerText = composerValue.text
    var draftRestored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.conversationReady) {
        if (state.conversationReady && !draftRestored) {
            if (composerValue.text.isBlank()) composerValue = TextFieldValue(state.conversation.draftText)
            draftRestored = true
        }
    }
    LaunchedEffect(composerText, state.conversationReady, draftRestored, state.sessionId) {
        if (!state.conversationReady || !draftRestored) return@LaunchedEffect
        viewModel.saveDraft(composerText)
    }
    var attachedConceptJson by rememberSaveable(state.sessionId) { mutableStateOf<String?>(null) }
    val attachedConcept = remember(attachedConceptJson) { ArtifactAttachment.decode(attachedConceptJson) }
    var attachedResponseJson by rememberSaveable(state.sessionId) { mutableStateOf<String?>(null) }
    val attachedResponse = remember(attachedResponseJson) { ResponseAttachment.decode(attachedResponseJson) }
    val scheduleKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val sectionFocus = androidx.compose.ui.platform.LocalFocusManager.current
    var scheduleDraft by remember { mutableStateOf<com.androidharness.app.automation.AutomationTask?>(null) }
    var scheduledComposerText by remember { mutableStateOf<String?>(null) }
    var showScheduledList by remember { mutableStateOf(false) }
    var createdScheduleIds by remember { mutableStateOf(setOf<String>()) }
    val automations by viewModel.container.automation.repository.tasks.collectAsStateWithLifecycle()
    val scheduleConnection by viewModel.container.automation.connection.collectAsStateWithLifecycle()
    val scheduleNow by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(60_000) }
    }
    val chatSchedules = automations.filter { task ->
        (state.sessionId != null && task.targetSessionId == state.sessionId || task.id in createdScheduleIds) &&
            (task.enabled || task.activeRun != null) && task.lastStatus != com.androidharness.app.automation.AutomationStatus.CANCELLED
    }
    fun setComposerText(new: String, cursor: Int = new.length) {
        composerValue = TextFieldValue(new, TextRange(cursor.coerceIn(0, new.length)))
    }
    var attachedSkill by remember { mutableStateOf<String?>(null) }
    scheduleDraft?.let { task ->
        com.androidharness.app.ui.automation.ScheduleMessageSheet(viewModel.container, task,
            onDismiss = { scheduleDraft = null; scheduledComposerText = null },
            onSaved = { saved ->
                if (scheduledComposerText != null && composerText == scheduledComposerText) setComposerText("")
                if (saved.targetSessionId == null) createdScheduleIds = createdScheduleIds + saved.id
            })
    }
    if (showScheduledList) androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { showScheduledList = false },
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(uiContext.getString(R.string.ui_scheduled_messages), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            items(chatSchedules.size) { index ->
                val task = chatSchedules[index]
                com.androidharness.app.ui.automation.ScheduledMessageRow(listOf(task), scheduleConnection, scheduleNow) {
                    showScheduledList = false; scheduleDraft = task
                }
            }
        }
    }

    LaunchedEffect(state.sessionId) {
        val sid = state.sessionId ?: return@LaunchedEffect
        viewModel.container.pendingCodeDraft.filterNotNull().collect { draft ->
            if (draft.sessionId == sid && viewModel.container.pendingCodeDraft.compareAndSet(draft, null)) {
                setComposerText(if (composerValue.text.isBlank()) draft.prompt else "${composerValue.text}\n\n${draft.prompt}")
            }
        }
    }

    // Only the chosen, fully loaded regular Chat may claim a share. Outgoing Code
    // screens can remain composed during navigation, so section gating is essential.
    LaunchedEffect(state.sessionId, state.conversationReady, state.conversation.isChat, draftRestored) {
        if (!state.conversationReady || !state.conversation.isChat || !draftRestored) return@LaunchedEffect
        viewModel.container.pendingShare.filterNotNull().collect { share ->
            if (share.isFor(state.sessionId, state.conversation) &&
                viewModel.container.pendingShare.compareAndSet(share, null)) {
                if (!share.text.isNullOrBlank()) {
                    val current = composerValue.text
                    setComposerText(if (current.isBlank()) share.text else "$current\n\n${share.text}")
                }
                share.streams.forEach { viewModel.attachSharedFile(it, share.mime) }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.container.pendingAgentPrompt.filterNotNull().collect { prompt ->
            viewModel.state.first { it.providers.isNotEmpty() && it.activeProvider != null }
            viewModel.container.pendingAgentPrompt.value = null
            viewModel.send(prompt)
        }
    }

    // Mode switches announce the model they will use when separate
    // planning/execution models are enabled (see Settings, Planning model).
    val toastContext = LocalContext.current
    LaunchedEffect(state.modeToast) {
        state.modeToast?.let {
            Toast.makeText(toastContext, it, Toast.LENGTH_SHORT).show()
            viewModel.clearModeToast()
        }
    }

    if (state.pendingWorkspaceMcp != null) {
        AlertDialog(
            onDismissRequest = { viewModel.denyWorkspaceMcp() },
            title = { Text(uiContext.getString(R.string.ui_allow_workspace_mcp_servers)) },
            text = {
                val names = state.pendingWorkspaceMcp?.joinToString(", ").orEmpty()
                Text(
                    uiContext.getString(R.string.ui_this_workspace_ships_a_harness_mcp_json, (names).toString()) +
                        uiContext.getString(R.string.ui_its_servers_can_run_local_commands_and) +
                        uiContext.getString(R.string.ui_only_approve_configs_you_trust),
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.approveWorkspaceMcp() }) { Text(uiContext.getString(R.string.ui_approve)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.denyWorkspaceMcp() }) { Text(uiContext.getString(R.string.ui_run_without_them)) }
            },
        )
    }

    if (state.showPlanningPromo) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPlanningPromo() },
            title = { Text(uiContext.getString(R.string.ui_two_models_one_for_planning_one_for)) },
            text = {
                Text(
                    uiContext.getString(R.string.ui_you_can_now_configure_two_models_separately) +
                        uiContext.getString(R.string.ui_and_one_that_takes_over_once_you),
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.dismissPlanningPromo()
                    viewModel.container.pendingSettingsScroll.value = "planning"
                    onOpenSettings()
                }) { Text(uiContext.getString(R.string.ui_configure)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPlanningPromo() }) { Text(uiContext.getString(R.string.settings_action_close)) }
            },
        )
    }

    if (state.showForkPromo) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissForkPromo() },
            title = { Text(uiContext.getString(R.string.ui_fork_conversation_from_here)) },
            text = {
                Text(
                    uiContext.getString(R.string.ui_forking_branches_this_chat_into_a_new) +
                        uiContext.getString(R.string.ui_the_active_agent_still_retains_all_background),
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.confirmForkFromPromo { newSid -> onNavigateToSession(newSid) }
                }) { Text(uiContext.getString(R.string.ui_fork_chat)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissForkPromo() }) { Text(uiContext.getString(R.string.settings_action_cancel)) }
            },
        )
    }

    if (state.showVoicePromo) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissVoicePromo() },
            title = { Text(uiContext.getString(R.string.ui_voice_speech_input)) },
            text = {
                Text(
                    uiContext.getString(R.string.ui_you_can_transcribe_speech_with_groq_whisper) +
                        uiContext.getString(R.string.ui_configure_groq_whisper_in_settings_with_your),
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.selectVoiceEngineFromPromo(AppSettings.VOICE_ENGINE_GROQ)
                    viewModel.container.pendingSettingsScroll.value = "voice"
                    onOpenSettings()
                }) { Text(uiContext.getString(R.string.ui_configure_groq_whisper)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.selectVoiceEngineFromPromo(AppSettings.VOICE_ENGINE_INBUILT)
                }) { Text(uiContext.getString(R.string.ui_use_inbuilt_speech)) }
            },
        )
    }

    val quoteComposerFocus = com.androidharness.app.ui.LocalComposerFocus.current
    val clipboard = LocalClipboardManager.current
    var actionsMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    // Set while the retry choice (same model or another) is open; runs the chosen retry.
    var retryRequest by remember { mutableStateOf<((com.androidharness.app.data.ModelShortcut?) -> Unit)?>(null) }
    var confirmingEdit by remember { mutableStateOf<Pair<ChatMessage, String>?>(null) }
    var showUndoDialog by remember { mutableStateOf(false) }
    // Chosen checkpoint awaiting confirmation with a per-file preview.
    var confirmRewindTurn by remember { mutableStateOf<String?>(null) }
    var showChatSettings by rememberSaveable(viewModel) { mutableStateOf(false) }
    var showChatAgents by rememberSaveable(viewModel) { mutableStateOf(false) }
    var newAgentId by rememberSaveable(viewModel) { mutableStateOf<String?>(null) }
    var showChatSources by remember(state.sessionId) { mutableStateOf(false) }
    var chatSources by remember(state.sessionId) {
        mutableStateOf<List<com.androidharness.app.core.ChatActivityPresentation.Source>?>(null)
    }
    val scope = rememberCoroutineScope()

    fun chooseChatAgent(agent: com.androidharness.app.data.ChatAgentProfile?) {
        if (agent != null) viewModel.startAgentChat(agent, composerValue.text)
        else viewModel.prepareSectionSwitch(composerValue.text, onNewChat)
    }
    newAgentId?.let { id ->
        val initial = remember(id) { com.androidharness.app.data.ChatAgentProfile(id = id) }
        com.androidharness.app.ui.chat.components.ChatAgentEditor(viewModel.container, initial,
            onDismiss = { newAgentId = null }, onSaved = {
                newAgentId = null
                scope.launch {
                    viewModel.container.settings.settings.first().chatAgents.firstOrNull { it.id == id }?.let(::chooseChatAgent)
                }
            })
    }

    if (showChatAgents) com.androidharness.app.ui.chat.components.ChatAgentsSheet(viewModel.container,
        onDismiss = { showChatAgents = false },
        onStart = { agent -> viewModel.startAgentChat(agent, composerValue.text) { showChatAgents = false } },
        onRegularChat = { viewModel.prepareSectionSwitch(composerValue.text) { showChatAgents = false; onNewChat() } })

    val latestUserTurnId = state.messages.lastOrNull { it.role == Role.USER }?.turnId
    // Follow new content until the reader scrolls away.
    var showJumpToLatest by remember(state.sessionId) { mutableStateOf(false) }
    var unreadWhileAway by remember(state.sessionId) { mutableStateOf(false) }
    var lastAnchoredTurnId by remember(state.sessionId) { mutableStateOf<String?>(null) }
    var searchRevealDone by remember(state.sessionId, searchMessageId) { mutableStateOf(false) }
    var findOpen by remember(state.sessionId) { mutableStateOf(false) }
    var findQuery by remember(state.sessionId) { mutableStateOf("") }
    var findHits by remember(state.sessionId) { mutableStateOf<List<com.androidharness.app.data.MessageHit>>(emptyList()) }
    var findPosition by remember(state.sessionId) { mutableStateOf(0) }
    var findTargetId by remember(state.sessionId) { mutableStateOf<String?>(null) }
    val activeFindId = if (findOpen) findTargetId else searchMessageId
    // A real drag pauses following until the reader returns to the bottom.
    var pinnedToBottom by remember(state.sessionId) { mutableStateOf(false) }
    var returnToQuestion by remember(state.sessionId) { mutableStateOf(true) }
    var chatWasBusy by remember(state.sessionId) { mutableStateOf(false) }
    val gestureActive = remember { mutableStateOf(false) }
    var mainListDragged by remember(state.sessionId) { mutableStateOf(false) }
    var lastDragTowardBottom by remember(state.sessionId) { mutableStateOf(false) }
    var userReachedBottom by remember(state.sessionId) { mutableStateOf(false) }
    val detailScroll = remember(state.sessionId) { ChatDetailScroll {
        returnToQuestion = false
        pinnedToBottom = false
        mainListDragged = false
        userReachedBottom = false
    } }
    val density = LocalDensity.current
    // Only the unused part of the latest turn is filled, so its question
    // stays anchored during streaming and after a short answer finishes.
    var chatViewportHeight by remember(state.sessionId) { mutableStateOf(0) }
    val chatItemHeights = remember(state.sessionId, latestUserTurnId) { androidx.compose.runtime.mutableStateMapOf<Any, Int>() }
    var chatTrailingSpace by remember(state.sessionId, latestUserTurnId) { mutableStateOf(0) }
    val liveTailPx = if (state.conversation.isChat) chatTrailingSpace else 0
    val chatGapPx = with(density) { 12.dp.roundToPx() }
    val chatPaddingPx = with(density) { 48.dp.roundToPx() }
    fun remainingTurnSpace(keys: List<Any>): Int =
        (chatViewportHeight - chatPaddingPx - keys.sumOf { chatItemHeights[it] ?: 0 } - chatGapPx * keys.size).coerceAtLeast(0)
    val previewOffset = with(density) { 64.dp.roundToPx() }
    val hideJumpAt = with(density) { 96.dp.toPx() }
    val currentTail = androidx.compose.runtime.rememberUpdatedState(liveTailPx)
    val userScrollObserver = remember(state.sessionId) {
        UserScrollObserver { deltaY ->
            if (detailScroll.reading) return@UserScrollObserver
            returnToQuestion = false
            pinnedToBottom = false
            // DragInteraction.Start arrives asynchronously; it must not erase
            // the direction already observed on the first pointer movement.
            if (!mainListDragged || deltaY > 0f) userReachedBottom = false
            lastDragTowardBottom = deltaY < 0f
        }.apply {
            onUserScrollApplied = {
                if (!detailScroll.reading && lastDragTowardBottom &&
                    isAtBottom(listState.layoutInfo, listState.canScrollForward, tolerance = 1, tail = currentTail.value)) {
                    userReachedBottom = true
                }
            }
        }
    }

    // Filled while the LazyColumn registers its items, so the newest question
    // can be anchored before it has ever been on screen.
    val userItemIndexes = remember(state.sessionId) { HashMap<String, Int>() }
    val searchItemIndex = remember(activeFindId) { mutableStateOf<Int?>(null) }
    val searchTurnId = state.messages.firstOrNull { it.id == activeFindId }?.turnId
    var diffFile by remember { mutableStateOf<Pair<String, String>?>(null) }
    var imagePreview by remember { mutableStateOf<com.androidharness.app.core.ImageRef?>(null) }
    diffFile?.let { (turnId, path) ->
        ChatFileDiffSheet(
            path = path,
            loadDiff = { viewModel.fileDiff(turnId, path) },
            onOpenFile = { diffFile = null; onOpenFile(path, null) },
            onDismiss = { diffFile = null },
        )
    }
    imagePreview?.let { ref ->
        com.androidharness.app.ui.chat.components.ImagePreviewDialog(
            ref = ref,
            dir = viewModel.container.images.imagesDir,
            onDismiss = { imagePreview = null },
        )
    }
    LaunchedEffect(showChatSources) {
        if (!showChatSources) return@LaunchedEffect
        chatSources = null
        chatSources = runCatching { viewModel.loadChatSources() }.getOrDefault(emptyList())
    }
    if (showChatSettings) {
        com.androidharness.app.ui.chat.components.ChatSettingsSheet(
            instructions = state.conversation.instructions,
            useGlobalInstructions = state.conversation.useGlobalInstructions,
            globalInstructions = globalSettings.chatInstructions,
            personalization = state.conversation.personalizationEnabled,
            imageFrequency = state.conversation.imageFrequency,
            globalImageFrequency = globalSettings.chatImageFrequency,
            saving = state.conversationSaving,
            saveError = state.chatSettingsError,
            onSave = { text, enabled, frequency, inherit ->
                viewModel.setChatSettings(text, enabled, frequency, inherit, onSaved = { showChatSettings = false })
            },
            onDismiss = { showChatSettings = false },
            onOpenAgents = { showChatSettings = false; showChatAgents = true },
        )
    }
    if (showChatSources) {
        com.androidharness.app.ui.chat.components.ChatSourceLibrarySheet(
            sources = chatSources.orEmpty(),
            loading = chatSources == null,
            onOpen = { url ->
                showChatSources = false
                webPreviewUrl = url
                showWebPreview = true
            },
            onDismiss = { showChatSources = false },
        )
    }
    LaunchedEffect(searchMessageId, state.isLoadingMessages) {
        if (searchMessageId == null || state.isLoadingMessages) return@LaunchedEffect
        if (!viewModel.revealMessage(searchMessageId)) {
            searchRevealDone = true
            lastAnchoredTurnId = state.messages.lastOrNull { it.role == Role.USER }?.turnId
            snackbar.showSnackbar(uiContext.getString(R.string.ui_this_message_is_no_longer_in_the))
            return@LaunchedEffect
        }
        val index = snapshotFlow { searchItemIndex.value }.filterNotNull().first()
        pinnedToBottom = false
        listState.scrollToItem(index)
        searchRevealDone = true
        // Keep the search result in place; a new message changes the turn id
        // and anchors normally.
        lastAnchoredTurnId = state.messages.lastOrNull { it.role == Role.USER }?.turnId
    }
    LaunchedEffect(findOpen, findQuery, state.sessionId) {
        if (!findOpen) return@LaunchedEffect
        if (findQuery.isBlank()) {
            findHits = emptyList()
            findPosition = 0
            findTargetId = null
            return@LaunchedEffect
        }
        delay(250)
        val hits = viewModel.findInConversation(findQuery)
        findHits = hits
        findPosition = 0
        findTargetId = hits.firstOrNull()?.messageId
    }
    LaunchedEffect(findOpen, findTargetId, state.isLoadingMessages) {
        if (!findOpen) return@LaunchedEffect
        val target = findTargetId ?: return@LaunchedEffect
        if (state.isLoadingMessages) return@LaunchedEffect
        if (!viewModel.revealMessage(target)) return@LaunchedEffect
        val index = snapshotFlow { searchItemIndex.value }.filterNotNull().first()
        if (index in 0 until listState.layoutInfo.totalItemsCount) listState.animateScrollToItem(index)
    }

    if (showContext) {
        TaskSettingsSheet(state = state, onDismiss = { showContext = false },
            onSave = viewModel::saveTaskControls, onRecoveryChange = viewModel::saveRecoverySettings)
    }
    activeModelPickerTarget?.let { target ->
        val currentProviderId = state.selectedProviderIdFor(target) ?: state.activeProviderId
        val currentModel = state.selectedModelFor(target) ?: state.activeModel
        ModelPickerSheet(
            providers = state.providers,
            activeProviderId = currentProviderId,
            activeModel = currentModel,
            catalogs = state.catalogs,
            onDismiss = { activeModelPickerTarget = null },
            onSelect = { providerId, model ->
                viewModel.selectModelForTarget(target, providerId, model)
            },
            onRefreshCatalog = viewModel::refreshCatalog,
            onAddCustomModel = viewModel::addCustomModel,
            onDeleteCustomModel = viewModel::deleteCustomModel,
            sameModelAcrossChats = state.sameModelAcrossChats.takeIf { state.conversation.agent == null },
            onModelScopeChange = viewModel::setSameModelAcrossChats,
            modelScopeEnabled = state.modelSelectionReady && !state.modelSelectionBusy,
            fallbackSummary = when (state.taskControl.fallbackMode) {
                com.androidharness.app.llm.FallbackMode.OFF -> uiContext.getString(R.string.ui_off_for_this_chat)
                com.androidharness.app.llm.FallbackMode.CUSTOM -> uiContext.getString(R.string.ui_custom_chain_models, (state.taskControl.fallbackOverride?.targets?.size ?: 0).toString())
                com.androidharness.app.llm.FallbackMode.DEFAULT -> if (state.fallbackDefaults.enabled)
                    uiContext.getString(R.string.ui_use_default_models, (state.fallbackDefaults.targets.size).toString()) else uiContext.getString(R.string.ui_use_default_no_chain)
            },
            onEditFallbacks = { fallbackProviderId = currentProviderId; activeModelPickerTarget = null; showFallbacks = true },
            fallbackEditEnabled = !state.busy,
            // Provider management stays in-conversation: a sheet, not a screen.
            onEnableProvider = { id -> scope.launch { viewModel.container.providers.setEnabled(id, true) } },
            onManageProviders = {
                addProviderInitially = false
                activeProviderManagerTarget = target
                activeModelPickerTarget = null
            },
            onAddProvider = {
                addProviderInitially = true
                activeProviderManagerTarget = target
                activeModelPickerTarget = null
            },
        )
    }
    if (showFallbacks) com.androidharness.app.ui.settings.FallbackEditorSheet(
        container = viewModel.container, defaultPolicy = state.fallbackDefaults,
        chatMode = state.taskControl.fallbackMode, customPolicy = state.taskControl.fallbackOverride,
        selectedProviderId = fallbackProviderId ?: state.activeProviderId,
        onDismiss = { showFallbacks = false }, onSave = viewModel::saveFallbacks,
    )
    if (state.showSkillsSheet) {
        SkillsPickerSheet(
            skills = state.skills,
            onDismiss = viewModel::dismissSkillsSheet,
            onPick = { skill ->
                viewModel.dismissSkillsSheet()
                attachedSkill = skill.name
                setComposerText("")
                slashExpanded = false
            },
        )
    }
    if (state.showEnvSheet) {
        EnvSheet(
            container = viewModel.container,
            onDismiss = viewModel::dismissEnvSheet,
        )
    }
    providerControlRoute?.let { route ->
        com.androidharness.app.ui.settings.ProviderControlsSheet(viewModel.container, route.first) {
            providerControlRoute = null; activeProviderManagerTarget = route.second
        }
    }
    activeProviderManagerTarget?.let { target ->
        ProviderManagerSheet(
            onControls = { config -> activeProviderManagerTarget = null; providerControlRoute = config to target },
            onGlobalProxy = { activeProviderManagerTarget = null; providerControlRoute = null to target },
            providers = state.providers,
            addProviderInitially = addProviderInitially,
            activeProviderId = state.selectedProviderIdFor(target) ?: state.activeProviderId,
            apiKeys = viewModel::providerApiKeys,
            onDismiss = { activeProviderManagerTarget = null },
            onSetActive = { providerId ->
                viewModel.selectProviderForTarget(target, providerId)
            },
            onDelete = { id -> viewModel.container.providers.delete(id) },
            catalogs = state.catalogs,
            onRefreshCatalog = viewModel::refreshCatalog,
            onEnabledChange = { id, enabled -> viewModel.container.providers.setEnabled(id, enabled) },
            onMove = { id, direction, visible -> scope.launch { viewModel.container.providers.move(id, direction, visible) } },
            onSave = viewModel::upsertProvider,
        )
    }
    if (showWebPreview) {
        val fs by viewModel.workspaceFs.collectAsStateWithLifecycle(initialValue = null)
        WebPreviewSheet(
            initialTarget = webPreviewUrl,
            startAtSources = webPreviewUrl == null,
            workspace = fs,
            messages = state.messages,
            additionalLinks = remember(state.streamingText) {
                com.androidharness.app.core.WebResourceExtractor.extractUrls(state.streamingText.orEmpty())
            },
            browserController = viewModel.container.browser,
            agentActivity = latestBrowserActivity,
            agentRunning = state.busy,
            onStopAgent = viewModel::stop,
            onSendPrompt = { prompt ->
                viewModel.send(prompt)
            },
            onDismiss = {
                showWebPreview = false
                webPreviewUrl = null
            },
        )
    }
    if (state.showCostDialog) {
        CostDialog(state = state, onDismiss = viewModel::dismissCostDialog)
    }

    retryRequest?.let { runRetry ->
        com.androidharness.app.ui.chat.components.ChatRetryDialog(
            providers = state.providers,
            activeProviderId = state.activeProviderId,
            activeModel = state.activeModel,
            onRetry = { retryRequest = null; runRetry(null) },
            onRetryWith = { choice -> retryRequest = null; runRetry(choice) },
            onDismiss = { retryRequest = null },
        )
    }

    // Long-press menu for YOUR messages (agent text is directly selectable,
    // hold and drag; the system toolbar handles copy).
    actionsMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { actionsMessage = null },
            title = { Text(uiContext.getString(R.string.ui_your_message)) },
            text = {
                Text(
                    UserMessageContent.visibleText(msg.text).let { if (it.length > 200) it.take(200) + "…" else it },
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(UserMessageContent.visibleText(msg.text)))
                        actionsMessage = null
                    }) { Text(uiContext.getString(R.string.ui_copy)) }
                    TextButton(onClick = {
                        editingMessage = msg
                        actionsMessage = null
                    }) { Text(uiContext.getString(R.string.settings_action_edit)) }
                    TextButton(
                        onClick = {
                            actionsMessage = null
                            confirmingEdit = msg to msg.text
                        },
                        enabled = !state.busy,
                    ) { Text(uiContext.getString(R.string.ui_retry)) }
                }
            },
            dismissButton = {
                Row {
                    if (msg.turnId in state.turnsWithCheckpoints) TextButton(onClick = {
                        actionsMessage = null
                        confirmRewindTurn = msg.turnId
                    }) { Text(uiContext.getString(R.string.ui_undo_file_changes_165)) }
                    TextButton(onClick = { actionsMessage = null }) { Text(uiContext.getString(R.string.settings_action_close)) }
                }
            },
        )
    }

    // Prefilled editor for a user message.
    editingMessage?.let { msg ->
        var text by remember(msg) { mutableStateOf(UserMessageContent.visibleText(msg.text)) }
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = { Text(uiContext.getString(R.string.ui_edit_message)) },
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
                        confirmingEdit = msg to UserMessageContent.edit(text, msg.text)
                        editingMessage = null
                    }
                }) { Text(uiContext.getString(R.string.settings_action_continue)) }
            },
            dismissButton = { TextButton(onClick = { editingMessage = null }) { Text(uiContext.getString(R.string.settings_action_cancel)) } },
        )
    }

    // Editing a message rewinds files and truncates the chat, warn first.
    confirmingEdit?.let { (msg, newText) ->
        AlertDialog(
            onDismissRequest = { confirmingEdit = null },
            title = { Text(if (newText == msg.text) uiContext.getString(R.string.ui_retry_this_message) else uiContext.getString(R.string.ui_edit_this_message)) },
            text = {
                Text(
                    uiContext.getString(R.string.ui_everything_after_this_message_will_be_deleted) +
                        uiContext.getString(R.string.ui_after_it_will_be_undone_files_are) +
                        uiContext.getString(R.string.ui_point_the_message_is_then_resent_once),
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.editAndResend(msg, newText)
                    confirmingEdit = null
                }) { Text(if (newText == msg.text) uiContext.getString(R.string.ui_retry) else uiContext.getString(R.string.ui_edit_resend)) }
            },
            dismissButton = { TextButton(onClick = { confirmingEdit = null }) { Text(uiContext.getString(R.string.settings_action_cancel)) } },
        )
    }

    // Undo file changes: pick a turn to restore the workspace back to.
    if (showUndoDialog) {
        var loadingTurns by remember { mutableStateOf(true) }
        val turns by produceState<List<Pair<String, ChatMessage>>>(emptyList(), state.turnsWithCheckpoints) {
            loadingTurns = true
            try { value = viewModel.undoTurns() }
            finally { loadingTurns = false }
        }
        AlertDialog(
            onDismissRequest = { showUndoDialog = false },
            title = { Text(uiContext.getString(R.string.ui_undo_file_changes_165)) },
            text = {
                if (loadingTurns) {
                    Text(uiContext.getString(R.string.ui_loading_checkpoints))
                } else if (turns.isEmpty()) {
                    Text(uiContext.getString(R.string.ui_no_file_changes_to_undo))
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            uiContext.getString(R.string.ui_pick_a_checkpoint_files_return_to_their),
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
                                        msg.text.lineSequence().firstOrNull()?.take(64)?.ifBlank { uiContext.getString(R.string.ui_no_text) }
                                            ?: uiContext.getString(R.string.ui_no_text),
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
                                }) { Text(uiContext.getString(R.string.ui_review)) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUndoDialog = false }) { Text(uiContext.getString(R.string.settings_action_close)) }
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
            title = { Text(uiContext.getString(R.string.ui_undo_to_this_checkpoint)) },
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
                                append(uiContext.getString(R.string.ui_file_s_revert_to_their_earlier_state, (p.files.size).toString()))
                                if (p.turns > 1) append(uiContext.getString(R.string.ui_this_turn_later, (p.turns - 1).toString()))
                                if (p.messagesDeleted > 0) {
                                    append(uiContext.getString(R.string.ui_message_s_roll_back, (p.messagesDeleted).toString()))
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (p.files.isEmpty()) {
                            Text(
                                uiContext.getString(R.string.ui_no_tracked_file_changes_in_this_window),
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
                                stat.willBeDeleted -> uiContext.getString(R.string.ui_created_by_the_agent_will_be_deleted)
                                !stat.existsNow -> uiContext.getString(R.string.ui_missing_on_disk_will_be_restored)
                                else -> uiContext.getString(R.string.ui_reverts_to_its_earlier_state)
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
                                uiContext.getString(R.string.ui_more_file_s, (p.files.size - 12).toString()),
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
                ) { Text(uiContext.getString(R.string.ui_undo), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRewindTurn = null }) { Text(uiContext.getString(R.string.settings_action_cancel)) }
            },
        )
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    val selectSection by androidx.compose.runtime.rememberUpdatedState(onSelectSection)
    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is NavEvent.NewChat -> onNewChat()
                is NavEvent.OpenSession -> onNavigateToSession(event.id)
                is NavEvent.SelectSection -> viewModel.prepareSectionSwitch(composerValue.text) {
                    sectionFocus.clearFocus(force = true)
                    scheduleKeyboard?.hide()
                    selectSection(event.kind)
                }
            }
        }
    }

    // Chat snaps the sent question to the top and holds it there while the reply
    // grows below. Code keeps following the bottom. An explicit bottom jump or a
    // reader's own scrolling takes precedence for the rest of that turn.
    LaunchedEffect(state.isLoadingMessages, latestUserTurnId, searchMessageId) {
        if (state.isLoadingMessages || (searchMessageId != null && !searchRevealDone)) return@LaunchedEffect
        val turnId = latestUserTurnId ?: return@LaunchedEffect
        if (turnId == lastAnchoredTurnId) return@LaunchedEffect
        lastAnchoredTurnId = turnId
        returnToQuestion = true
        pinnedToBottom = !state.conversation.isChat
        withFrameNanos { }
        if (pinnedToBottom) listState.scrollToEnd(liveTailPx) { pinnedToBottom && !gestureActive.value }
        else if (returnToQuestion && !gestureActive.value) userItemIndexes[turnId]?.let { listState.scrollToItem(it) }
    }
    // Reapply the question anchor once its measured trailing room is available.
    LaunchedEffect(liveTailPx, latestUserTurnId) {
        if (liveTailPx <= 0 || !returnToQuestion) return@LaunchedEffect
        val turnId = latestUserTurnId ?: return@LaunchedEffect
        withFrameNanos { }
        if (returnToQuestion && !gestureActive.value && !detailScroll.reading && latestUserTurnId == turnId) {
            userItemIndexes[turnId]?.let { listState.scrollToItem(it) }
        }
    }
    LaunchedEffect(state.busy, latestUserTurnId) {
        val finished = chatWasBusy && !state.busy
        chatWasBusy = state.busy
        if (!finished || !state.conversation.isChat || !returnToQuestion || findOpen || searchMessageId != null) return@LaunchedEffect
        val turnId = latestUserTurnId ?: return@LaunchedEffect
        pinnedToBottom = false
        withFrameNanos { }
        if (returnToQuestion && !gestureActive.value && !detailScroll.reading && latestUserTurnId == turnId) {
            userItemIndexes[turnId]?.let { listState.scrollToItem(it) }
        }
    }

    // Track real finger drags so programmatic scrolls can be told apart.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    if (detailScroll.reading) return@collect
                    returnToQuestion = false
                    pinnedToBottom = false
                    gestureActive.value = true
                    mainListDragged = true
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> gestureActive.value = false
            }
        }
    }

    // Only a direct drag settling at the bottom may resume following.
    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.isScrollInProgress, gestureActive.value, mainListDragged to !listState.canScrollForward) }
            .distinctUntilChanged()
            .collect { (inProgress, dragging, position) ->
                val (dragged, atBottom) = position
                // A fling can reach the edge before the next stream update extends it.
                if (dragged && lastDragTowardBottom && atBottom) userReachedBottom = true
                if (inProgress || dragging || !dragged) return@collect
                mainListDragged = false
                if (lastDragTowardBottom && (userReachedBottom || atBottom)) {
                    returnToQuestion = false
                    pinnedToBottom = true
                }
                userReachedBottom = false
            }
    }

    // While pinned, keep the newest content in view with small exact deltas.
    LaunchedEffect(pinnedToBottom, listState, liveTailPx) {
        if (!pinnedToBottom) return@LaunchedEffect
        var lastFollowAt = 0L
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            Triple(info.totalItemsCount, last?.let { it.index to (it.offset + it.size) }, listState.isScrollInProgress)
        }.distinctUntilChanged().collect {
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
                listState.scrollToEnd(liveTailPx) { pinnedToBottom && !gestureActive.value && !listState.isScrollInProgress }
            } else {
                val delta = last.offset + last.size + info.afterContentPadding - liveTailPx - info.viewportEndOffset
                if (delta > 0) listState.scrollBy(delta.toFloat())
            }
        }
    }

    // The down button always refers to the bottom, regardless of which bubble is visible.
    LaunchedEffect(listState, liveTailPx) {
        snapshotFlow {
            !pinnedToBottom && !isAtBottom(listState.layoutInfo, listState.canScrollForward,
                tolerance = hideJumpAt.toInt(), tail = liveTailPx)
        }.distinctUntilChanged().collect { away ->
            showJumpToLatest = away
            if (!away) unreadWhileAway = false
        }
    }

    // New content landing while the question is off-screen pulses the button.
    LaunchedEffect(state.messages.lastOrNull()?.id, state.streamingText?.length, state.streamingThinking?.length) {
        if (showJumpToLatest) unreadWhileAway = true
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
                    uiContext.getString(R.string.ui_files_changed), actionLabel = uiContext.getString(R.string.ui_undo), duration = SnackbarDuration.Short,
                )
                if (res == SnackbarResult.ActionPerformed) confirmRewindTurn = latestTurn
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.attachImage(it) } }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.attachFile(it) } }

    val voiceController = rememberVoiceInputController(viewModel.container)
    var pendingLockedRecord by remember { mutableStateOf(false) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (state.voiceEngine == AppSettings.VOICE_ENGINE_GROQ) {
                voiceController.startGroqRecording(locked = pendingLockedRecord)
            } else {
                val base = composerText
                voiceController.startNativeListening { transcribed, isFinal ->
                    val newText = if (base.isBlank()) transcribed else "$base $transcribed"
                    setComposerText(newText)
                }
            }
        }
    }

    LaunchedEffect(voiceController.errorMessage) {
        voiceController.errorMessage?.let {
            Toast.makeText(toastContext, it, Toast.LENGTH_SHORT).show()
        }
    }

    val glassScene = rememberHarnessGlassScene()
    val glassAmbient = LocalHarnessGlassBackdrop.current
    val glassChrome = rememberGlassContentBackdrop(glassAmbient, glassScene,
        if (LocalHarnessGlass.current.mode == HarnessGlassMode.LIQUID) .26f else 1f)
    val floatingGlass = glassScene != null && !desktopLayout
    var glassComposerHeight by remember { mutableIntStateOf(0) }
    CompositionLocalProvider(
        LocalHarnessGlassChromeBackdrop provides glassChrome,
        LocalChatDetailScroll provides detailScroll,
        com.androidharness.app.ui.chat.rich.LocalResponseFileOpen provides (if (state.conversation.isChat) null else onOpenFile),
        com.androidharness.app.ui.chat.rich.LocalResponseContext provides { title, context ->
            attachedConceptJson = null
            attachedResponseJson = ResponseAttachment(title.take(100), context.take(1500)).encode()
        },
    ) {
    Scaffold(
        containerColor = if (com.androidharness.app.ui.theme.LocalHarnessCanvasVisible.current) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            val currentProvider = state.activeProvider
            val currentModel = state.effectiveModel ?: currentProvider?.model
            val headerModel = com.androidharness.app.core.ChatExperience.modelLabel(currentModel)
            val modelEntry = state.catalogs[currentProvider?.id].orEmpty().firstOrNull { it.id == currentModel }
                ?: com.androidharness.app.llm.ModelEntry(currentModel.orEmpty())
            val reasoningRevision by com.androidharness.app.llm.ModelsDev.revision.collectAsState()
            val thinking = remember(currentModel, currentProvider, modelEntry, state.thinkingLevel, state.maxOutputTokens, reasoningRevision, state.reasoningConfigs, state.customThinkingLevels) {
                currentProvider?.let { com.androidharness.app.llm.RequestOptions(thinking = state.thinkingLevel, maxOutputTokens = state.maxOutputTokens,
                    customReasoning = state.reasoningConfigs, customThinkingLevels = state.customThinkingLevels)
                    .resolveThinking(it.copy(model = currentModel.orEmpty()), modelEntry) }
            }
            val thinkingLevels = thinking?.spec?.levels.orEmpty()
            var editingReasoning by remember { mutableStateOf<com.androidharness.app.llm.ProviderConfig?>(null) }
            var addingReasoning by remember { mutableStateOf(false) }
            editingReasoning?.let { config ->
                val key = com.androidharness.app.agent.CustomReasoning.key(config)
                com.androidharness.app.ui.chat.components.CustomReasoningSheet(
                    config = config, saved = state.reasoningConfigs[key], selectedLevel = state.customThinkingLevels[key],
                    spec = com.androidharness.app.agent.ThinkingSpecs.forProvider(config,
                        state.catalogs[config.id].orEmpty().firstOrNull { it.id == config.model }),
                    onDismiss = { editingReasoning = null },
                    onSave = { viewModel.saveReasoningConfig(config, it); editingReasoning = null },
                    onDetectLevels = { viewModel.detectReasoningLevels(config) },
                    startAdding = addingReasoning,
                )
            }

            Column {
            MainHeader(
                conversation = state.conversation,
                sessionTitle = state.sessionTitle,
                workspaceName = currentProject?.name,
                busy = state.busy,
                pickerLabel = when {
                    !state.modelSelectionReady -> uiContext.getString(R.string.ui_loading_model)
                    currentProvider == null && !state.sameModelAcrossChats -> uiContext.getString(R.string.ui_choose_a_model_for_this_chat)
                    currentProvider == null -> uiContext.getString(R.string.ui_add_a_provider_to_get_started)
                    state.dualPlanning && state.mode == AgentMode.PLAN ->
                        "$headerModel (Plan)"
                    state.dualPlanning && state.mode == AgentMode.ACT ->
                        "$headerModel (Exec)"
                    else -> headerModel ?: currentProvider.name
                },
                modelScopeLabel = if (state.sameModelAcrossChats) null else uiContext.getString(R.string.ui_this_chat),
                searchDepth = state.searchDepth,
                onSetSearchDepth = viewModel::setSearchDepth,
                mode = state.mode,
                dualPlanning = state.dualPlanning,
                thinkingLevel = thinking?.level ?: state.thinkingLevel,
                thinkingResolved = thinking,
                onCustomizeThinking = currentProvider?.let { provider -> {
                    addingReasoning = false; editingReasoning = provider.copy(model = currentModel.orEmpty())
                } },
                onAddThinkingLevel = currentProvider?.let { provider -> {
                    addingReasoning = true; editingReasoning = provider.copy(model = currentModel.orEmpty())
                } },
                onSetCustomThinking = { name -> currentProvider?.let { viewModel.setCustomThinking(it.copy(model = currentModel.orEmpty()), name) } },
                thinkingLevels = thinkingLevels,
                permissionMode = state.permissionMode,
                canUndo = state.turnsWithCheckpoints.isNotEmpty(),
                onOpenDrawer = onOpenDrawer,
                onPickModel = {
                    activeModelPickerTarget = state.modelSelectionTarget
                },
                onOpenTerminal = onOpenTerminal,
                onSetThinking = viewModel::setThinkingLevel,
                onSetPermission = viewModel::setPermissionMode,
                onSetMode = viewModel::setMode,
                onToggleDualPlanning = viewModel::toggleDualPlanning,
                onOpenContext = { showContext = true },
                onOpenChatSettings = { showChatSettings = true },
                onOpenSources = { chatSources = null; showChatSources = true },
                chatPinned = state.sessionId in globalSettings.pinnedSessions,
                chatArchived = state.sessionId in globalSettings.archivedSessions,
                onToggleChatPin = state.sessionId?.takeIf { state.conversation.isChat }?.let { sid -> {
                    scope.launch { viewModel.container.settings.setPinned(sid, sid !in globalSettings.pinnedSessions) }
                    Unit
                } },
                onToggleChatArchive = state.sessionId?.takeIf { state.conversation.isChat }?.let { sid -> {
                    scope.launch {
                        val wasArchived = sid in globalSettings.archivedSessions
                        viewModel.container.settings.setArchived(sid, !wasArchived)
                        val result = snackbar.showSnackbar(
                            uiContext.getString(if (wasArchived) R.string.ui_chat_restored else R.string.ui_chat_archived),
                            actionLabel = uiContext.getString(R.string.ui_undo), duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.container.settings.setArchived(sid, wasArchived)
                    }
                    Unit
                } },
                onOpenUndo = { showUndoDialog = true },
                onOpenFiles = onOpenFiles,
                canOpenGitHub = currentProject != null,
                onOpenGitHub = {
                    // Keep the review tied to the workspace selected when opened.
                    currentProject?.let { githubProject = it.id to it.name }
                },
                showWebPreviewShortcut = webPreviewAvailable,
                onOpenWebPreview = { webPreviewUrl = null; showWebPreview = true },
                onFind = {
                    findOpen = true
                    pinnedToBottom = false
                },
            )
            if (showChatStartControls) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    com.androidharness.app.ui.chat.components.ChatPersonalizationDropdown(
                        personalized = state.conversation.personalizationEnabled,
                        enabled = !state.conversationSaving,
                        modifier = if (state.conversation.agent != null) Modifier.weight(1f) else Modifier.fillMaxWidth(),
                        onSelect = { enabled ->
                            viewModel.setChatSettings(state.conversation.instructions, enabled, state.conversation.imageFrequency)
                        },
                    )
                    state.conversation.agent?.let { agent ->
                        com.androidharness.app.ui.chat.components.ChatAgentDropdown(
                            selected = agent, agents = globalSettings.chatAgents,
                            enabled = !state.busy && !state.conversationSaving,
                            onSelect = ::chooseChatAgent,
                            onCreate = { newAgentId = java.util.UUID.randomUUID().toString() },
                            onManage = { showChatAgents = true }, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (findOpen) {
                com.androidharness.app.ui.chat.components.FindInChatBar(
                    query = findQuery,
                    onQueryChange = { findQuery = it },
                    position = findPosition,
                    total = findHits.size,
                    onPrevious = {
                        if (findHits.isNotEmpty()) {
                            findPosition = (findPosition - 1 + findHits.size) % findHits.size
                            findTargetId = findHits[findPosition].messageId
                        }
                    },
                    onNext = {
                        if (findHits.isNotEmpty()) {
                            findPosition = (findPosition + 1) % findHits.size
                            findTargetId = findHits[findPosition].messageId
                        }
                    },
                    onClose = {
                        findOpen = false
                        findTargetId = null
                    },
                )
            }
            }
        },
        snackbarHost = {},
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (floatingGlass) PaddingValues(bottom = padding.calculateBottomPadding()) else padding)
                .background(if (com.androidharness.app.ui.theme.LocalHarnessCanvasVisible.current) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .then(if (desktopLayout) Modifier.widthIn(max = 880.dp) else Modifier)
                    .fillMaxSize()
                    .imePadding(),
            ) {
                val toolResults = remember(state.messages, subagentResults) {
                    val map = HashMap<String, ChatMessage>(state.messages.size)
                    for (m in state.messages) {
                        if (m.role == Role.TOOL && !m.toolCallId.isNullOrEmpty()) {
                            map[m.toolCallId] = m
                        }
                    }
                    subagentResults.forEach { (id, outcome) ->
                        if (outcome.first.active) map.remove(id)
                        else map[id] = ChatMessage(role = Role.TOOL, toolCallId = id, toolName = "task",
                            text = outcome.second, isError = outcome.first != com.androidharness.app.agent.SubagentStatus.COMPLETED)
                    }
                    map
                }
                val runningIds = remember(state.runningCalls, subagentResults) {
                    state.runningCalls.map { it.id }.toSet() + subagentResults.filterValues { it.first.active }.keys
                }
                val committedCallIds = remember(state.messages, state.currentTurnId) {
                    state.messages.filter { it.turnId == state.currentTurnId }.flatMap { it.toolCalls }.map { it.id }.toSet()
                }

                // Precompute message lookup maps to eliminate O(N^2) scans in LazyColumn items
                val turnFinalAssistantIds = remember(state.messages) {
                    val map = HashMap<String, String>()
                    for (m in state.messages) {
                        if (m.role == Role.ASSISTANT && m.toolCallId == null && m.turnId != null && m.id != null) {
                            map[m.turnId] = m.id
                        }
                    }
                    map
                }
                val turnFirstUserTimes = remember(state.messages) {
                    val map = HashMap<String, Long>()
                    for (m in state.messages) {
                        if (m.role == Role.USER && m.turnId != null && !map.containsKey(m.turnId)) {
                            map[m.turnId] = m.createdAt
                        }
                    }
                    map
                }
                val turnSpeeds = remember(state.messages) {
                    state.messages.filter { it.turnId != null }
                        .groupBy { it.turnId!! }
                        .mapValues { (_, messages) -> turnTokensPerSecond(messages) }
                }
                val turnActivities = remember(state.messages) {
                    completedTurnActivities(state.messages)
                }
                val chatTurns = remember(state.messages, state.conversation.isChat) {
                    if (state.conversation.isChat) state.messages.groupBy { it.turnId } else emptyMap()
                }
                val turnVerification = remember(state.messages) {
                    state.messages.filter { it.turnId != null }.groupBy { it.turnId }
                        .mapValues { (_, messages) -> com.androidharness.app.agent.VerificationEvidence.collect(messages) }
                }
                val skillUsedByMessage = remember(state.messages) {
                    val map = HashMap<String, List<String>>()
                    for (m in state.messages) {
                        if (m.role == Role.ASSISTANT && m.id != null) {
                            val skills = m.toolCalls
                                .filter { it.name == "skill_view" }
                                .mapNotNull { call ->
                                    runCatching {
                                        kotlinx.serialization.json.Json.parseToJsonElement(call.argumentsJson)
                                            .jsonObject["name"]?.jsonPrimitive?.content
                                    }.getOrNull()
                                }
                                .distinct()
                            if (skills.isNotEmpty()) {
                                map[m.id] = skills
                            }
                        }
                    }
                    map
                }

                // "Speak every reply" reads each assistant block as it lands,
                // including progress commentary between tool calls, not just
                // the final answer. Blocks queue so they never cut each other off.
                val appSettings by viewModel.container.settings.settings
                    .collectAsStateWithLifecycle(initialValue = AppSettings())
                var autoSpokenIds by remember { mutableStateOf<Set<String>>(emptySet()) }
                LaunchedEffect(appSettings.ttsAlwaysSpeak, state.sessionId) {
                    autoSpokenIds = if (appSettings.ttsAlwaysSpeak) {
                        state.messages.mapNotNull { it.id }.toSet()
                    } else {
                        emptySet()
                    }
                }
                LaunchedEffect(state.messages, appSettings.ttsAlwaysSpeak) {
                    if (!appSettings.ttsAlwaysSpeak) return@LaunchedEffect
                    val known = autoSpokenIds
                    if (known.isEmpty() && state.messages.isNotEmpty()) {
                        // Turned on with a chat already open: don't read history.
                        autoSpokenIds = state.messages.mapNotNull { it.id }.toSet()
                        return@LaunchedEffect
                    }
                    val fresh = state.messages.filter { it.id != null && it.id !in known }
                    if (fresh.isEmpty()) return@LaunchedEffect
                    autoSpokenIds = known + fresh.mapNotNull { it.id }
                    fresh.forEach { message ->
                        if (message.role == Role.ASSISTANT && message.toolCallId == null &&
                            message.text.isNotBlank()
                        ) {
                            message.id?.let { viewModel.container.tts.enqueueAuto(it, message.text) }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { chatViewportHeight = it.height },
                ) {
                    if (state.isLoadingMessages) {
                        ChatLoadingSkeleton(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp, vertical = 16.dp),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().glassViewportExtra(if (floatingGlass) glassComposerHeight else 0)
                                .recordHarnessGlassScene(glassScene,
                                    fadeTop = if (floatingGlass) with(density) { padding.calculateTopPadding().toPx() } else 0f,
                                    fadeBottom = if (floatingGlass) glassComposerHeight.toFloat() else 0f)
                                .nestedScroll(userScrollObserver),
                            contentPadding = PaddingValues(
                                start = 18.dp, top = 24.dp + (if (floatingGlass) padding.calculateTopPadding() else 0.dp), end = 18.dp,
                                bottom = 24.dp + (if (floatingGlass) with(density) { glassComposerHeight.toDp() } else 0.dp),
                            ),
                            // Top-anchored: a sent question stays put while the
                            // answer grows below; short chats no longer sit at the bottom.
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Indices are recorded while the item scope is built,
                            // so a brand-new question can be anchored before it
                            // has ever been composed on screen.
                            userItemIndexes.clear()
                            var nextItemIndex = 0
                            var latestTurnStarted = false
                            val latestTurnKeys = mutableListOf<Any>()
                            fun indexedItem(
                                key: Any,
                                contentType: ChatListContentType,
                                content: @Composable LazyItemScope.() -> Unit,
                            ) {
                                if (key == "search-target") searchItemIndex.value = nextItemIndex
                                nextItemIndex++
                                val measureTurn = state.conversation.isChat && latestTurnStarted
                                if (measureTurn) latestTurnKeys += key
                                item(key = key, contentType = contentType) {
                                    if (state.conversation.isChat) {
                                        val itemScope = this
                                        Box(Modifier.onSizeChanged {
                                            if (measureTurn) {
                                                chatItemHeights[key] = it.height
                                                chatTrailingSpace = remainingTurnSpace(latestTurnKeys) + chatGapPx
                                            }
                                        }) { content(itemScope) }
                                    } else content()
                                }
                            }
                            // One composition path keeps the disclosure and its inner scroll
                            // position intact when live activity becomes a saved message.
                            fun chatActivityItem(activityKey: String?, messages: List<ChatMessage>, running: Boolean, committed: Boolean) {
                                indexedItem(key = "chat-turn-$activityKey-activity", contentType = ChatListContentType.Thinking) {
                                    com.androidharness.app.ui.chat.components.ChatActivity(messages, live = running,
                                        showThinkingMetrics = true,
                                        thinkingTiming = state.thinkingTiming.takeIf { running && !committed },
                                        reasoningTokens = state.reasoningTokens.takeIf { running && !committed },
                                        turnStartedAt = state.turnStartedAt.takeIf { running },
                                        autoExpand = !appSettings.chatKeepThinkingCollapsed,
                                        runningCalls = if (running) state.runningCalls else emptyList(),
                                        streamingThinking = if (running && !committed) state.streamingThinking.orEmpty() else "",
                                        writing = running && !state.streamingText.isNullOrBlank(),
                                        streamId = state.streamingMessageId ?: "live",
                                        onOpenUrl = { url -> webPreviewUrl = url; showWebPreview = true })
                                }
                            }
                            if (state.hasOlderMessages) {
                                indexedItem(key = "older-messages", contentType = ChatListContentType.History) {
                                    TextButton(onClick = {
                                        viewModel.loadOlderMessages()
                                    }, enabled = !state.isLoadingOlderMessages, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (state.isLoadingOlderMessages) uiContext.getString(R.string.ui_loading_older_messages) else uiContext.getString(R.string.ui_load_older_messages))
                                    }
                                }
                            }
                            if (state.messages.isEmpty() && state.streamingText == null) {
                                indexedItem(key = "empty-state", contentType = ChatListContentType.Empty) {
                                    EmptyState(
                                        modifier = Modifier.fillParentMaxHeight(),
                                        hasProvider = state.activeProvider != null,
                                        chatOnly = state.conversation.isChat,
                                        agent = state.conversation.agent,
                                        agentSetup = {
                                            com.androidharness.app.ui.chat.components.ChatAgentDropdown(
                                                selected = null, agents = globalSettings.chatAgents,
                                                enabled = !state.busy && !state.conversationSaving,
                                                onSelect = ::chooseChatAgent,
                                                onCreate = { newAgentId = java.util.UUID.randomUUID().toString() },
                                                onManage = { showChatAgents = true },
                                                modifier = Modifier.fillMaxWidth(), setupCard = true,
                                            )
                                        },
                                        onSuggestion = { viewModel.send(it) },
                                        onAddProvider = { addProviderInitially = true; activeProviderManagerTarget = ModelSelectionTarget.ACTIVE },
                                    )
                                }
                            }

                    val renderedChatActivities = HashSet<String>()
                    val chatStreamCommitted = state.streamingMessageId != null && state.messages.any { it.id == state.streamingMessageId }
                    for ((messageIndex, message) in state.messages.withIndex()) {
                        // Inner subagent turns persist with the parent task's
                        // call id on the assistant row, they render on the
                        // subagent's own page, never in the main list.
                        if (message.role == Role.ASSISTANT && message.toolCallId != null) continue
                        val messageKey = message.id ?: "${message.role.name}-${message.createdAt}-$messageIndex"
                        if (activeFindId != null && message.id == activeFindId) {
                            indexedItem(key = "search-target", contentType = ChatListContentType.SearchTarget) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(uiContext.getString(R.string.ui_search_result), style = MaterialTheme.typography.labelMedium)
                                        if (!state.conversation.isChat && (message.role == Role.TOOL || message.role == Role.SYSTEM)) {
                                            Spacer(Modifier.height(8.dp))
                                            MarkdownText(message.text)
                                        }
                                    }
                                }
                            }
                        }
                        message.artifact?.takeIf { message.role != Role.SYSTEM }?.let { artifact ->
                            indexedItem(key = "message-$messageKey-artifact", contentType = ChatListContentType.Artifact) {
                                com.androidharness.app.ui.chat.components.ArtifactCard(
                                    artifact = artifact,
                                    revisions = state.messages.mapNotNull { it.artifact }.filter { it.id == artifact.id },
                                    loadRevisions = { state.sessionId?.let { viewModel.container.sessions.artifacts(it) }.orEmpty() },
                                    onRevise = { selected ->
                                        attachedResponseJson = null
                                        attachedConceptJson = ArtifactAttachment.from(selected).encode()
                                        if (composerText.isBlank()) setComposerText("Revise this concept: ")
                                    },
                                )
                            }
                        }
                        when (message.role) {
                            Role.USER -> {
                                if (message.turnId != null && message.turnId == latestUserTurnId) latestTurnStarted = true
                                val userIndex = nextItemIndex
                                indexedItem(key = "message-$messageKey-user", contentType = ChatListContentType.User) {
                                    Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                        Column(horizontalAlignment = Alignment.End) {
                                            val (visibleText, fileChips) = FileAttachments.splitForDisplay(
                                                slashSkillInstruction(UserMessageContent.visibleText(message.text))
                                                    ?.ifBlank { "/${slashInvokedSkillName(UserMessageContent.visibleText(message.text))}" }
                                                    ?: UserMessageContent.visibleText(message.text),
                                            )
                                            slashInvokedSkillName(UserMessageContent.visibleText(message.text))?.let { skillName ->
                                                SkillUsedBadge(skillName)
                                            }
                                            ArtifactAttachment.fromPrompt(message.text)?.let { SkillUsedBadge(uiContext.getString(R.string.ui_concept, (it.title).toString())) }
                                            ResponseAttachment.fromPrompt(message.text)?.let { SkillUsedBadge(uiContext.getString(R.string.ui_response, (it.title).toString())) }
                                            UserBubble(
                                                visibleText,
                                                message.images,
                                                fileChips,
                                                imageDir = viewModel.container.images.imagesDir,
                                                onOpenImage = { imagePreview = it },
                                                onLongPress = { actionsMessage = message },
                                            )

                                        }
                                    }
                                }
                                message.turnId?.let { userItemIndexes[it] = userIndex }
                            }
                            Role.ASSISTANT -> {
                                val canRewind = message.turnId != null &&
                                    message.turnId in state.turnsWithCheckpoints
                                val isTurnFinal = message.id == turnFinalAssistantIds[message.turnId]
                                val isTurnRunning = state.busy && (message.turnId == state.currentTurnId ||
                                    (state.currentTurnId == null && message.turnId == state.messages.lastOrNull { it.turnId != null }?.turnId))
                                if (state.conversation.isChat) {
                                    val conversationTurn = message.turnId?.let { chatTurns[it] }.orEmpty().ifEmpty { listOf(message) }
                                    val activityKey = message.turnId ?: messageKey
                                    if (renderedChatActivities.add(activityKey)) {
                                        chatActivityItem(activityKey, conversationTurn, isTurnRunning, chatStreamCommitted)
                                    }
                                    // Progress prose stays in the chronological disclosure; only the answer renders here.
                                    if (message.text.isNotBlank() && message.toolCalls.isEmpty() && (isTurnFinal || message.turnId == null)) indexedItem(key = "chat-$messageKey-answer", contentType = ChatListContentType.AssistantText) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            val replyText = remember(message.text) { com.androidharness.app.core.response.ChatRichResponse.normalize(message.text) }
                                            com.androidharness.app.ui.chat.components.QuoteReplySelection(onQuote = { selected ->
                                                attachedConceptJson = null
                                                attachedResponseJson = ResponseAttachment(uiContext.getString(R.string.chat_quoted_text), selected.take(1500)).encode()
                                                if (selected.length > 1500) scope.launch { snackbar.showSnackbar(uiContext.getString(R.string.chat_quote_shortened)) }
                                                quoteComposerFocus?.requestFocus()
                                                scheduleKeyboard?.show()
                                                pinnedToBottom = false
                                            }) {
                                                CompositionLocalProvider(
                                                    com.androidharness.app.ui.chat.components.LocalChatImageSources provides imageSources,
                                                    com.androidharness.app.ui.chat.components.LocalChatImageOpen provides { url -> viewerImageUrl = url },
                                                    LocalChatImagesEnabled provides (effectiveImageFrequency != com.androidharness.app.data.ImageFrequency.NONE),
                                                ) {
                                                    AssistantText(replyText, showPreviewChip = false, chatImages = responseImages,
                                                        onFollowUp = { action ->
                                                            attachedConceptJson = null
                                                            attachedResponseJson = ResponseAttachment(action.title, action.context).encode()
                                                            setComposerText(listOf(composerText.trim(), action.prompt).filter(String::isNotBlank).joinToString("\n\n"))
                                                        }, onOpenUrl = { url -> webPreviewUrl = url; showWebPreview = true })
                                                }
                                            }
                                            if (isTurnFinal && message.toolCalls.isEmpty() && !isTurnRunning) {
                                                if (message.turnId == latestUserTurnId) {
                                                    val followUps = message.generation.followUps
                                                    if (followUps.isNotEmpty()) {
                                                        com.androidharness.app.ui.chat.components.FollowUpChips(followUps) { suggestion ->
                                                            attachedConceptJson = null
                                                            attachedResponseJson = null
                                                            setComposerText(suggestion)
                                                            quoteComposerFocus?.requestFocus()
                                                            scheduleKeyboard?.show()
                                                        }
                                                    }
                                                }
                                                com.androidharness.app.ui.chat.components.ChatSources(conversationTurn) { url -> webPreviewUrl = url; showWebPreview = true }
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    CopyIconButton(replyText)
                                                    message.id?.let { id -> SpeakIconButton(
                                                        active = ttsState.messageId == id,
                                                        preparing = ttsState.messageId == id && ttsState.phase == TtsPhase.PREPARING,
                                                        onClick = { viewModel.container.tts.toggle(id, replyText) }) }
                                                    if (message.turnId != null && message.turnId == latestUserTurnId) {
                                                        IconButton(
                                                            onClick = { retryRequest = { model -> viewModel.regenerate(message, model) } },
                                                            enabled = !state.busy && !state.conversationSaving,
                                                        ) {
                                                            Icon(Icons.Outlined.Refresh, uiContext.getString(R.string.ui_regenerate),
                                                                Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        }
                                                    }
                                                    ResponseActions(
                                                        onFork = { viewModel.requestFork(message) { onNavigateToSession(it) } },
                                                        onUndo = null,
                                                        details = {
                                                            message.generation.model?.let { model ->
                                                                Text(
                                                                    uiContext.getString(
                                                                        R.string.chat_answered_by,
                                                                        com.androidharness.app.core.ChatExperience.modelLabel(model) ?: model,
                                                                    ),
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                                                )
                                                            }
                                                        },
                                                    )
                                                }
                                                state.sessionId?.let { sid ->
                                                    com.androidharness.app.ui.chat.components.ChatAnswerVersions(
                                                        sid, state.messages, viewModel.container.sessions,
                                                        turnId = message.turnId, readOnly = message.turnId != latestUserTurnId,
                                                        onOpenUrl = { url -> webPreviewUrl = url; showWebPreview = true },
                                                        enabled = message.turnId != latestUserTurnId || (!state.busy && !state.conversationSaving),
                                                        onSelect = viewModel::selectAnswerVersion)
                                                }
                                                if (com.androidharness.app.core.ChatActivityPresentation.savedMemory(conversationTurn)) {
                                                    com.androidharness.app.ui.chat.components.ChatMemoryReceipt(conversationTurn, viewModel.container, state.busy)
                                                }
                                            }
                                        }
                                    }
                                    continue
                                }
                                val activity = turnActivities[message.turnId].orEmpty()
                                val isSearchTurn = activeFindId != null && message.turnId == searchTurnId
                                val hasFinishedActivity = !isTurnRunning && !isSearchTurn && activity.isNotEmpty()
                                if (hasFinishedActivity && !isTurnFinal) continue
                                if (!hasFinishedActivity && (message.thinking.isNotBlank() || (message.generation.reasoningTokens ?: 0) > 0)) {
                                    indexedItem(key = "message-$messageKey-thinking", contentType = ChatListContentType.Thinking) {
                                        Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                            ThinkingBlock(message.thinking, thinkingMs = message.thinkingMs, reasoningTokens = message.generation.reasoningTokens)
                                        }
                                    }
                                }
                                if (message.text.isNotBlank() || hasFinishedActivity) {
                                    indexedItem(key = "message-$messageKey-text", contentType = ChatListContentType.AssistantText) {
                                        Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                            Column {
                                                val used = skillUsedByMessage[message.id].orEmpty()
                                                if (used.isNotEmpty()) {
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                        modifier = Modifier.padding(bottom = 4.dp),
                                                    ) {
                                                        used.forEach { SkillUsedBadge(it) }
                                                    }
                                                }
                                                if (message.text.isNotBlank()) {
                                                    Text("ANDROIDHARNESS", style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                                                }
                                                AssistantText(
                                                    message.text,
                                                    showPreviewChip = isTurnFinal && !isTurnRunning,
                                                    onFollowUp = { action ->
                                                        attachedConceptJson = null
                                                        attachedResponseJson = ResponseAttachment(action.title, action.context).encode()
                                                        setComposerText(listOf(composerText.trim(), action.prompt).filter { it.isNotBlank() }.joinToString("\n\n"))
                                                    },
                                                    onOpenUrl = { url ->
                                                        webPreviewUrl = url
                                                        showWebPreview = true
                                                    },
                                                )
                                                if (state.conversation.sourceChatId != null &&
                                                    com.androidharness.app.data.isCodeHandoffGreeting(message.text)) {
                                                    com.androidharness.app.ui.chat.components.ChatFromChatBadge(
                                                        onClick = { state.conversation.sourceChatId?.let(onNavigateToSession) },
                                                        modifier = Modifier.padding(top = 8.dp),
                                                    )
                                                }
                                                val edits = state.fileEditsByTurn[message.turnId].orEmpty()
                                                if (isTurnFinal && !isTurnRunning && edits.isNotEmpty()) {
                                                    FileEditsCard(
                                                        edits = edits,
                                                        onOpenFile = onOpenFile,
                                                        onReviewFile = { path -> message.turnId?.let { diffFile = it to path } },
                                                        modifier = Modifier.padding(top = 8.dp),
                                                    )
                                                }
                                                if (hasFinishedActivity) {
                                                    val userAt = turnFirstUserTimes[message.turnId]
                                                    Box(Modifier.padding(top = 18.dp)) {
                                                        TurnActivityCard(
                                                            calls = activity.flatMap { it.toolCalls }, results = toolResults,
                                                            fileEdits = edits,
                                                            workedLabel = userAt?.let { formatDuration((message.createdAt - it).coerceAtLeast(0)) }.orEmpty(),
                                                            activity = activity, onOpenFile = onOpenFile, showLiveCode = state.showLiveCode,
                                                            onWatchBrowser = ::watchBrowser, browserTargets = browserTargets,
                                                        )
                                                    }
                                                }
                                                if (isTurnFinal && !isTurnRunning &&
                                                    (edits.isNotEmpty() || turnVerification[message.turnId].orEmpty().isNotEmpty())) {
                                                    com.androidharness.app.ui.chat.components.VerificationCard(turnVerification[message.turnId].orEmpty())
                                                }
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    CopyIconButton(message.text)
                                                    message.id?.let { messageId ->
                                                        val isThisMessage = ttsState.messageId == messageId
                                                        SpeakIconButton(
                                                            active = isThisMessage,
                                                            preparing = isThisMessage && ttsState.phase == TtsPhase.PREPARING,
                                                            onClick = {
                                                                viewModel.container.tts.toggle(messageId, message.text)
                                                            },
                                                        )
                                                    }
                                                    ResponseActions(
                                                        onFork = { viewModel.requestFork(message) { onNavigateToSession(it) } },
                                                        onUndo = if (canRewind) ({ message.turnId?.let { confirmRewindTurn = it } }) else null,
                                                        details = {
                                                            Column {
                                                                message.generation.model?.let { model ->
                                                                    Text(
                                                                        uiContext.getString(
                                                                            R.string.chat_answered_by,
                                                                            com.androidharness.app.core.ChatExperience.modelLabel(model) ?: model,
                                                                        ),
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                                                    )
                                                                }
                                                                val duration = turnFirstUserTimes[message.turnId]?.let { message.createdAt - it }
                                                                Text(turnPerformanceLabel(duration, turnSpeeds[message.turnId]),
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                                            }
                                                        },
                                                    )
                                                    if (isTurnFinal && !isTurnRunning) {
                                                        val duration = turnFirstUserTimes[message.turnId]?.let {
                                                            message.createdAt - it
                                                        }
                                                        val timestamp = message.createdAt.takeIf { it > 0 }?.let {
                                                            android.text.format.DateFormat.getTimeFormat(toastContext)
                                                                .format(java.util.Date(it))
                                                        }
                                                        val label = listOfNotNull(
                                                            duration?.let { formatDuration(it.coerceAtLeast(0)) },
                                                            timestamp,
                                                        ).joinToString(" · ")
                                                        if (label.isNotBlank()) {
                                                            Spacer(Modifier.weight(1f))
                                                            Text(
                                                                label,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            )
                                                        }
                                                    }
                                                }
                                                if (isTurnFinal && !isTurnRunning) {
                                                    CacheUsageFooter(state.turnCacheUsage[message.turnId].orEmpty(),
                                                        speed = turnSpeeds[message.turnId])
                                                }
                                            }
                                        }
                                    }
                                }
                                if (isTurnRunning || isSearchTurn) {
                                    val taskCalls = message.toolCalls.filter { it.name == "task" && (isSearchTurn || it.id !in subagentResults) }
                                    val otherCalls = message.toolCalls.filter { it.name != "task" }
                                    if (taskCalls.size >= 2) {
                                        indexedItem(key = "message-$messageKey-subagents", contentType = ChatListContentType.Subagents) {
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
                                        indexedItem(key = "message-$messageKey-subagent-${call.id}", contentType = ChatListContentType.Subagent) {
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
                                    if (otherCalls.size >= 3 && otherCalls.none { BrowserToolActivity.isBrowserTool(it.name) || it.name == "pkg_install" || it.name == "github_create_pull_request" } && !(state.showLiveCode && otherCalls.any {
                                            it.name in com.androidharness.app.agent.LiveCodePreview.tools })) {
                                        indexedItem(key = "message-$messageKey-tools", contentType = ChatListContentType.Tools) {
                                            Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                                ToolGroupCard(
                                                    calls = otherCalls,
                                                    results = toolResults,
                                                    runningIds = runningIds,
                                                    onOpenFile = onOpenFile,
                                                    subagentSteps = state.subagentSteps,
                                                    showLiveCode = state.showLiveCode,
                                                    onWatchBrowser = ::watchBrowser,
                                                    browserTargets = browserTargets,
                                                    packageProgress = state.packageProgress,
                                                )
                                            }
                                        }
                                    } else {
                                        for ((callIndex, call) in otherCalls.withIndex()) {
                                            indexedItem(key = "message-$messageKey-tool-$callIndex-${call.id}", contentType = ChatListContentType.Tool) {
                                                Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                                    ToolCallCard(
                                                        call = call,
                                                        result = toolResults[call.id],
                                                        running = call.id in runningIds,
                                                        onOpenFile = onOpenFile,
                                                        showLiveCode = state.showLiveCode,
                                                        codeDraft = if (isTurnRunning) state.codeDrafts[call.id] else null,
                                                        awaitingApproval = isTurnRunning && state.pendingApproval?.call?.id == call.id,
                                                        onWatchBrowser = ::watchBrowser,
                                                        browserTarget = browserTargets[call.id],
                                                        packageProgress = state.packageProgress[call.id],
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Role.TOOL -> Unit
                            Role.SYSTEM -> {
                                if (state.conversation.isChat) continue
                                if (message.text.startsWith("Compaction failed:") || message.text.startsWith("Compaction stopped.") ||
                                    message.text.startsWith("Chat compacted, but")) {
                                    indexedItem(key = "message-$messageKey-system", contentType = ChatListContentType.System) {
                                        CompactionResultCard(message.text,
                                            showRetry = state.messages.lastOrNull { m ->
                                                m.role == Role.SYSTEM && (m.text.startsWith("Compaction failed:") || m.text.startsWith("Compaction stopped.") ||
                                                    m.text.startsWith(com.androidharness.app.agent.ContextHygiene.COMPACTION_NOTICE_PREFIX))
                                            }?.id == message.id,
                                            busy = state.busy, onRetry = { viewModel.send("/compact") })
                                    }
                                } else if (message.text.startsWith(com.androidharness.app.agent.ContextHygiene.COMPACTION_NOTICE_PREFIX)) {
                                    indexedItem(key = "message-$messageKey-system", contentType = ChatListContentType.System) {
                                        Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                            CompactionNoticeLine()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.conversation.isChat && !state.busy && state.taskControl.resumable) {
                        indexedItem(key = "chat-recovery", contentType = ChatListContentType.AssistantText) {
                            com.androidharness.app.ui.chat.components.ChatRecovery(
                                state.taskControl.reason,
                                onContinue = viewModel::resumeInterruptedTask,
                                onRetry = { retryRequest = { model -> viewModel.retryLatestResponse(model) } })
                            state.sessionId?.let { sid ->
                                com.androidharness.app.ui.chat.components.ChatAnswerVersions(
                                    sid, state.messages, viewModel.container.sessions, enabled = true,
                                    onSelect = viewModel::selectAnswerVersion)
                            }
                        }
                    }

                    // Live streaming items are rendered only while not yet in the committed list.
                    val streamAlreadyCommitted = state.streamingMessageId != null &&
                        state.messages.any { it.id == state.streamingMessageId }
                    val liveTurn = state.currentTurnId?.let { chatTurns[it] }.orEmpty()
                    if (state.conversation.isChat && state.busy && state.currentTurnId !in renderedChatActivities) {
                        chatActivityItem(state.currentTurnId, liveTurn, running = true, committed = streamAlreadyCommitted)
                    }

                    if (!streamAlreadyCommitted) {
                        val streamKey = state.streamingMessageId ?: state.currentTurnId ?: "idle"
                        if (!state.conversation.isChat) state.streamingThinking?.let { thinking ->
                            if (thinking.isNotBlank()) {
                                indexedItem(key = "message-$streamKey-thinking", contentType = ChatListContentType.Thinking) {
                                    Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                        ThinkingBlock(thinking,
                                            live = !state.streamingCommitted && state.thinkingTiming.startedAt != null,
                                            timing = state.thinkingTiming, reasoningTokens = state.reasoningTokens,
                                            autoExpand = false)
                                    }
                                }
                            }
                        }
                        state.streamingText?.let { streaming ->
                            // In chat the live disclosure above already shows
                            // progress; a blank dots bubble under it is noise.
                            if (streaming.isNotBlank() || !state.conversation.isChat) {
                                // Preserve the item identity and scroll offset when the same response is saved.
                                val answerKey = if (state.conversation.isChat) "chat-$streamKey-answer" else "message-$streamKey-text"
                                indexedItem(key = answerKey, contentType = ChatListContentType.AssistantText) {
                                    AssistantText(
                                        chatImages = responseImages,
                                        text = if (state.conversation.isChat) com.androidharness.app.core.response.ChatRichResponse.normalize(streaming, !state.streamingCommitted) else streaming,
                                        streaming = !state.streamingCommitted,
                                        onOpenUrl = { url ->
                                            webPreviewUrl = url
                                            showWebPreview = true
                                        },
                                    )
                                }
                            }
                        }
                    }

                    if (!state.conversation.isChat && state.showLiveCode) {
                        state.codeDrafts.values.filter { it.id !in committedCallIds }.forEach { draft ->
                            indexedItem(key = "draft-code-${state.currentTurnId}-${draft.id}", contentType = ChatListContentType.Tool) {
                                com.androidharness.app.ui.chat.components.LiveCodeCard(
                                    draft = draft,
                                    awaitingApproval = state.pendingApproval?.call?.id == draft.id,
                                    onOpenFile = onOpenFile,
                                )
                            }
                        }
                    }

                    state.pendingApproval?.let { approval ->
                        indexedItem(key = "approval", contentType = ChatListContentType.Approval) {
                            Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                ApprovalCard(
                                    approval = approval,
                                    onApprove = viewModel::approve,
                                    onDeny = viewModel::deny,
                                )
                            }
                        }
                    }

                    (state.pendingEnvironment ?: state.installingEnvironment)?.takeUnless { state.conversation.isChat }?.let { request ->
                        indexedItem(key = "env-install", contentType = ChatListContentType.Environment) {
                            Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                EnvironmentInstallCard(
                                    request = request,
                                    envState = state.envState,
                                    onInstall = viewModel::approveEnvironmentInstall,
                                    onSkip = viewModel::denyEnvironmentInstall,
                                    installStarted = state.installingEnvironment != null,
                                )
                            }
                        }
                    }

                    state.pendingQuestion?.let { question ->
                        indexedItem(key = "question", contentType = ChatListContentType.Question) {
                            Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                QuestionCard(
                                    question = question,
                                    onAnswer = viewModel::answerQuestion,
                                )
                            }
                        }
                    }

                    state.pendingPlan?.let { plan ->
                        indexedItem(key = "plan", contentType = ChatListContentType.Plan) {
                            Box(Modifier.animateItem(fadeInSpec = fastEffectsSpec(), placementSpec = null, fadeOutSpec = null)) {
                                PlanApprovalCard(
                                    plan = plan,
                                    onApprove = viewModel::executePendingPlan,
                                    onDiscard = viewModel::discardPendingPlan,
                                )
                            }
                        }
                    }
                    if (!state.busy && codeHandoff != null) {
                        indexedItem(key = "chat-code-handoff-${state.sessionId}", contentType = ChatListContentType.Handoff) {
                            com.androidharness.app.ui.chat.components.ChatCodeHandoffCard(
                                offer = codeHandoff, enabled = !state.conversationSaving,
                                onClick = { showCodeHandoff = true }, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    if (state.conversation.isChat && latestTurnStarted) {
                        item(key = "chat-turn-space", contentType = ChatListContentType.Empty) {
                            val space = remainingTurnSpace(latestTurnKeys)
                            androidx.compose.runtime.SideEffect { chatTrailingSpace = space + chatGapPx }
                            Spacer(Modifier.fillMaxWidth().height(with(density) { space.toDp() }))
                        }
                    }
                }
            }

                // Floating jump-to-latest button while detached from the
                // bottom; pulses when new content lands while away.
                // @-mention file picker: a trailing @path token opens the
                // workspace file list; picking rewrites the token in place.
                val mention = remember(composerText) { MentionToken.parse(composerText) }
                if (!state.conversation.isChat && mention != null && attachedSkill == null) {
                    LaunchedEffect(mention != null) { viewModel.loadMentionFiles() }
                    MentionSuggestions(
                        files = state.mentionFiles,
                        query = mention.second,
                        loading = state.mentionFiles.isEmpty(),
                        onPick = { path ->
                            setComposerText(composerText.substring(0, mention.first) + "@$path ")
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp),
                    )
                } else if (!state.conversation.isChat) {
                    SlashSuggestions(
                        state = state,
                        query = composerText,
                        expanded = slashExpanded && attachedSkill == null,
                        onPick = { cmd, kind ->
                            when (val action = SlashCommands.pickAction(cmd, composerText, kind)) {
                                is SlashCommands.Pick.AttachSkill -> {
                                    attachedSkill = action.name
                                    setComposerText(action.leftover)
                                    slashExpanded = false
                                }
                                is SlashCommands.Pick.Insert -> {
                                    setComposerText(action.text)
                                    slashExpanded = true
                                }
                                is SlashCommands.Pick.Send -> {
                                    viewModel.send(action.text)
                                    setComposerText("")
                                    attachedSkill = null
                                    slashExpanded = false
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp),
                    )
                }
                val fabBottomPadding by animateDpAsState(
                    targetValue = if (snackbar.currentSnackbarData != null) 68.dp else 10.dp,
                    label = "fab-bottom-pad",
                )
                ScrollToBottomFab(
                    visible = showJumpToLatest,
                    unread = unreadWhileAway,
                    onJump = {
                        showJumpToLatest = false
                        unreadWhileAway = false
                        returnToQuestion = false
                        pinnedToBottom = true
                        lastAnchoredTurnId = latestUserTurnId
                        scope.launch { listState.scrollToEnd(liveTailPx) { pinnedToBottom && !gestureActive.value } }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = fabBottomPadding),
                )
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    snackbar = { data ->
                        Snackbar(
                            snackbarData = data,
                            shape = RoundedCornerShape(14.dp),
                            containerColor = MaterialTheme.colorScheme.inverseSurface,
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            actionColor = MaterialTheme.colorScheme.inversePrimary,
                        )
                    },
                )
                }

                if (!state.conversation.isChat && state.todos.isNotEmpty()) {
                    TodoCard(state.todos)
                }

                if (!state.conversation.isChat && state.busy) {
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        CacheUsageFooter(state.turnCacheUsage[state.currentTurnId].orEmpty(), running = true)
                    }
                }
                if (!state.conversation.isChat) TaskProgressCard(
                    record = state.taskControl, busy = state.busy,
                    onResume = viewModel::resumeInterruptedTask,
                    onSettings = { showContext = true },
                )
                MessageQueueCard(
                    queue = state.taskControl.queue,
                    onEdit = viewModel::editQueued, onMove = viewModel::moveQueued,
                    onSendNow = viewModel::sendQueuedNow,
                )

                if (desktopLayout) {
                    attachedResponse?.let { ref -> com.androidharness.app.ui.chat.rich.ResponseContextChip(ref.title) { attachedResponseJson = null } }
                    attachedConcept?.let { concept ->
                        com.androidharness.app.ui.chat.components.ConceptAttachmentChip(concept) { attachedConceptJson = null }
                    }
                    if (state.attachments.isNotEmpty()) AttachmentChips(state.attachments, viewModel::removeAttachment)
                    if (state.fileAttachments.isNotEmpty()) FileAttachmentChips(state.fileAttachments, viewModel::removeFileAttachment)
                }

                state.compactionNote?.let { note ->
                    CompactionBanner(note, Modifier.padding(bottom = 6.dp))
                }

                if (!state.conversation.isChat) AgentStatusBar(action = state.currentAction, busy = state.busy)
                com.androidharness.app.ui.automation.ScheduledMessageRow(chatSchedules, scheduleConnection, scheduleNow) {
                    if (chatSchedules.size == 1) scheduleDraft = chatSchedules.first() else showScheduledList = true
                }
                if (!state.conversation.isChat) com.androidharness.app.ui.chat.components.FloatingSubagentDock(
                    state.sessionId, viewModel.container.runManager, onOpenSubagent)
                MessageComposer(
                    modifier = Modifier.onSizeChanged { glassComposerHeight = it.height },
                    chatOnly = state.conversation.isChat,
                    sendAllowed = viewModel.attachmentBlockReason() == null,
                    attachments = {
                        attachedResponse?.let { ref -> com.androidharness.app.ui.chat.rich.ResponseContextChip(ref.title) { attachedResponseJson = null } }
                        attachedConcept?.let { concept ->
                            com.androidharness.app.ui.chat.components.ConceptAttachmentChip(concept) { attachedConceptJson = null }
                        }
                        if (state.conversation.isChat) {
                            com.androidharness.app.ui.chat.components.ChatAttachmentReadiness(state, viewModel::removeAttachment,
                                viewModel::removeFileAttachment, viewModel::removeAttachmentImport)
                        } else {
                            if (state.attachments.isNotEmpty()) AttachmentChips(state.attachments, viewModel::removeAttachment)
                            if (state.fileAttachments.isNotEmpty()) FileAttachmentChips(state.fileAttachments, viewModel::removeFileAttachment)
                            if (state.attachmentImports.isNotEmpty()) com.androidharness.app.ui.chat.components.ChatAttachmentReadiness(
                                state.copy(attachments = emptyList(), fileAttachments = emptyList()), viewModel::removeAttachment,
                                viewModel::removeFileAttachment, viewModel::removeAttachmentImport)
                        }

                    },
                    busy = state.busy,
                    value = composerValue,
                    attachedSkill = attachedSkill,
                    onValueChange = { input ->
                        composerValue = input
                        slashExpanded = !state.conversation.isChat && attachedSkill == null && input.text.startsWith("/")
                    },
                    onClearSkill = { attachedSkill = null },
                    onSend = {
                        val payload = ResponseAttachment.attach(ArtifactAttachment.attach(SlashCommands.composeSend(attachedSkill,
                            composerText.ifBlank { if (attachedConcept != null) "Revise this concept." else if (attachedResponse != null) "Help me with this response." else "" }), attachedConcept), attachedResponse)
                        if (payload.isNotBlank() || state.fileAttachments.isNotEmpty() || state.attachments.isNotEmpty()) {
                            // Keep the draft when a selection is still loading/saving or unavailable.
                            if (viewModel.send(payload)) {
                                setComposerText("")
                                viewModel.saveDraft("", immediate = true)
                                attachedSkill = null
                                attachedConceptJson = null
                                attachedResponseJson = null
                                slashExpanded = false
                            }
                        }
                    },
                    onSchedule = if (state.conversation.isChat) null else fun() {
                        currentProject?.let { project ->
                            scheduleDraft = com.androidharness.app.automation.AutomationTask(
                                title = composerText.trim().lineSequence().first().take(64), prompt = composerText.trim(),
                                projectId = project.id, projectName = project.name, targetSessionId = state.sessionId,
                                providerId = state.activeProvider?.id, model = state.effectiveModel,
                                schedule = com.androidharness.app.automation.AutomationSchedule.ONCE,
                                unmeteredOnly = toastContext.getSharedPreferences("schedule_preferences", android.content.Context.MODE_PRIVATE).getBoolean("unmetered", false))
                            scheduledComposerText = composerText
                            scheduleKeyboard?.hide()
                        }
                    },
                    scheduleUnavailableReason = when {
                        composerText.isBlank() -> uiContext.getString(R.string.ui_write_a_message_first)
                        attachedConcept != null -> uiContext.getString(R.string.ui_remove_the_concept_attachment_to_schedule_a)
                        attachedResponse != null -> uiContext.getString(R.string.ui_remove_the_response_context_to_schedule_a)
                        attachedSkill != null || state.attachments.isNotEmpty() || state.fileAttachments.isNotEmpty() || state.attachmentImports.isNotEmpty() -> uiContext.getString(R.string.ui_schedule_a_text_message_without_attachments)
                        currentProject == null -> uiContext.getString(R.string.ui_choose_a_workspace_first)
                        else -> null
                    },
                    onStop = viewModel::stop,
                    onAttachImage = { galleryLauncher.launch("image/*") },
                    onAttachFile = { fileLauncher.launch("*/*") },
                    voiceEngine = state.voiceEngine,
                    groqRecordState = voiceController.groqRecordState,
                    recordingDurationMs = voiceController.recordingDurationMs,
                    levels = voiceController.levels,
                    cancelArmed = voiceController.cancelArmed,
                    onCancelArmedChange = { voiceController.cancelArmed = it },
                    onToggleInbuiltVoice = {
                        if (!state.voicePromoSeen) {
                            viewModel.promptVoicePromo()
                        } else if (voiceController.isListeningInbuilt) {
                            voiceController.stopNativeListening()
                        } else {
                            if (androidx.core.content.ContextCompat.checkSelfPermission(
                                    toastContext,
                                    android.Manifest.permission.RECORD_AUDIO,
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                val base = composerText
                                voiceController.startNativeListening { transcribed, isFinal ->
                                    val newText = if (base.isBlank()) transcribed else "$base $transcribed"
                                    setComposerText(newText)
                                }
                            } else {
                                audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    isInbuiltListening = voiceController.isListeningInbuilt,
                    onStartGroqRecord = { locked ->
                        if (!state.voicePromoSeen) {
                            viewModel.promptVoicePromo()
                        } else if (androidx.core.content.ContextCompat.checkSelfPermission(
                                toastContext,
                                android.Manifest.permission.RECORD_AUDIO,
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            voiceController.startGroqRecording(locked = locked)
                        } else {
                            pendingLockedRecord = locked
                            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onLockGroqRecord = voiceController::lockGroqRecording,
                    onCancelGroqRecord = voiceController::cancelGroqRecording,
                    onStopAndTranscribeGroq = {
                        voiceController.stopAndTranscribeGroq(
                            model = state.groqWhisperModel,
                        ) { transcribed ->
                            val base = composerText
                            val newText = if (base.isBlank()) transcribed else "$base $transcribed"
                            setComposerText(newText)
                        }
                    },
                    hasAttachments = state.fileAttachments.isNotEmpty() || state.attachments.isNotEmpty() || attachedConcept != null || attachedResponse != null,
                )
            }

        }
    }
    }
}

// ---------------------------------------------------------------------------
// Helpers

/** Small copy icon with a brief check confirmation, ChatGPT-style action rows. */
@Composable
internal fun CopyIconButton(text: String) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    // No size override: IconButton's own 48dp touch target is what makes these
    // reachable with a thumb. The glyph stays small, the row just gets taller.
    IconButton(onClick = {
        clipboard.setText(AnnotatedString(com.androidharness.app.core.response.RichResponse.plainText(text)))
        copied = true
    }) {
        Icon(
            painterResource(if (copied) R.drawable.ic_chat_check_check else R.drawable.ic_chat_copy),
            contentDescription = uiContext.getString(R.string.ui_copy),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
}

/** Read-aloud control: speaker, spinner while a Groq voice is generated, stop while playing. */
@Composable
internal fun SpeakIconButton(active: Boolean, preparing: Boolean, onClick: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    IconButton(onClick = onClick) {
        when {
            active && preparing -> CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
            active -> Icon(
                Icons.Outlined.StopCircle,
                contentDescription = uiContext.getString(R.string.settings_tts_stop_reading),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            else -> Icon(
                Icons.Outlined.VolumeUp,
                contentDescription = uiContext.getString(R.string.settings_tts_read_aloud),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun ResponseActions(onFork: () -> Unit, onUndo: (() -> Unit)?, details: @Composable () -> Unit = {}) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_chat_ellipsis), uiContext.getString(R.string.ui_more_response_actions), Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        com.androidharness.app.ui.common.ExpressiveDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            com.androidharness.app.ui.common.ExpressiveDropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_fork_from_this_message)) },
                onClick = { expanded = false; onFork() })
            if (onUndo != null) com.androidharness.app.ui.common.ExpressiveDropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_undo_file_changes_from_this_turn)) },
                onClick = { expanded = false; onUndo() })
            HorizontalDivider()
            details()
        }
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
    onReviewFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
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
        shape = RoundedCornerShape(13.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
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
                Icon(painterResource(R.drawable.ic_chat_file_code_2), null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                Text(
                    if (merged.size == 1) merged.first().first.substringAfterLast('/') else uiContext.getString(R.string.ui_edited_files, (merged.size).toString()),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                DiffStatText(merged.sumOf { it.second.first }, merged.sumOf { it.second.second })
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) uiContext.getString(R.string.ui_collapse_edited_files) else uiContext.getString(R.string.ui_expand_edited_files),
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
                                .clickable { onReviewFile(path) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    path.substringAfterLast('/'),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = HarnessMono,
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
                            TextButton(onClick = { onOpenFile(path, null) }) { Text(uiContext.getString(R.string.ui_open)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * True when the last item's bottom edge sits within [tolerance]px of the
 * viewport bottom. [tail] is unused room below the latest turn and
 * does not count as content.
 */
private fun isAtBottom(info: LazyListLayoutInfo, canScrollForward: Boolean, tolerance: Int = 64, tail: Int = 0): Boolean {
    if (!canScrollForward) return true
    val last = info.visibleItemsInfo.lastOrNull() ?: return false
    if (last.index != info.totalItemsCount - 1) return false
    return last.offset + last.size + info.afterContentPadding - tail <= info.viewportEndOffset + tolerance
}

/**
 * Reaches the true bottom after pending item updates. A normal scroll mutation
 * respects the user's drag/fling; a pending requestScrollToItem would cancel it
 * on the next measure even after following was switched off. [tail] is the
 * extra bottom padding that must stay below the fold.
 */
private suspend fun LazyListState.scrollToEnd(tail: Int = 0, shouldScroll: () -> Boolean) {
    withFrameNanos { }
    if (!shouldScroll()) return
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    scrollToItem(last)
    withFrameNanos { }
    if (shouldScroll()) snapToEndIfDrifted(tail)
}

/** Cheap exact snap to the true bottom; a no-op unless the end is composed. */
private suspend fun LazyListState.snapToEndIfDrifted(tail: Int = 0) {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return
    if (last.index != info.totalItemsCount - 1) return
    val delta = last.offset + last.size + info.afterContentPadding - tail - info.viewportEndOffset
    if (delta > 0 || (tail > 0 && delta < 0)) scrollBy(delta.toFloat())
}

// Follow-scroll coalescing: during a stream the list follows the newest
// content with small scrollBy deltas at most this often (ms between scrolls).
private const val FOLLOW_MIN_INTERVAL_MS = 16L

/** Floating jump-to-latest button; pulses when content landed while away. */
@Composable
private fun ScrollToBottomFab(
    visible: Boolean,
    unread: Boolean,
    onJump: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
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
        HarnessGlassSurface(
            onClick = onJump,
            chrome = true,
            shape = CircleShape,
            color = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.size(48.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                // The pulse scales the drawing only, so the tap target stays 48dp
                // even while the button is animating.
                modifier = Modifier.graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                },
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = uiContext.getString(R.string.ui_scroll_to_latest),
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
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val model = state.effectiveModel
    val providerKey = state.activeProvider?.let { com.androidharness.app.llm.ModelsDev.providerKeyFor(it.baseUrl) }
    val cost: Double? = if (state.sessionModelUsage.isNotEmpty()) {
        state.sessionModelUsage.sumOf { row ->
            val pKey = com.androidharness.app.llm.ModelsDev.providerKeyFor(row.providerName)
            com.androidharness.app.llm.ModelPrices.estimateSplit(
                model = row.model,
                totalInputTokens = row.inputTokens,
                outputTokens = row.outputTokens,
                cachedTokens = row.cachedTokens,
                cacheWriteTokens = row.cacheWriteTokens,
                peakInputTokens = row.peakInputTokens,
                peakOutputTokens = row.peakOutputTokens,
                peakCachedTokens = row.peakCachedTokens,
                peakCacheWriteTokens = row.peakCacheWriteTokens,
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(uiContext.getString(R.string.settings_action_close)) } },
        title = { Text(uiContext.getString(R.string.ui_session_cost)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(uiContext.getString(R.string.ui_input, (formatTokenCount(state.usage.totalInput)).toString()))
                Text(uiContext.getString(R.string.ui_output, (formatTokenCount(state.usage.totalOutput)).toString()))
                val hitRateStr = when {
                    state.usage.requests == 0L && state.busy -> uiContext.getString(R.string.ui_calculating)
                    state.usage.totalInput > 0 -> "${"%.1f".format(state.usage.avgCacheHitRate * 100)}%"
                    else -> "0.0%"
                }
                Text(uiContext.getString(R.string.ui_overall_cache_hit_rate_640, (hitRateStr).toString()))
                Text(
                    uiContext.getString(R.string.ui_hit_rate_updates_after_each_completed_turn),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.usage.totalCacheWrite > 0) {
                    Text(
                        uiContext.getString(R.string.ui_cache_writes, (formatTokenCount(state.usage.totalCacheWrite)).toString()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (cost != null) {
                    Text(uiContext.getString(R.string.ui_estimated_cost_extra72, ("%.4f".format(cost)).toString()))
                } else {
                    Text(uiContext.getString(R.string.ui_cost_estimate_unknown_for_this_model), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

@Composable
private fun ChatLoadingSkeleton(modifier: Modifier = Modifier) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val transition = rememberInfiniteTransition(label = "skeletonShimmer")
    val translateAnim = transition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )

    val scheme = MaterialTheme.colorScheme
    val shimmerColors = listOf(
        scheme.surfaceContainerHigh.copy(alpha = 0.6f),
        scheme.surfaceContainerHighest.copy(alpha = 0.9f),
        scheme.surfaceContainerHigh.copy(alpha = 0.6f),
    )
    val shimmerBrush = androidx.compose.ui.graphics.Brush.linearGradient(
        colors = shimmerColors,
        start = androidx.compose.ui.geometry.Offset(translateAnim.value, translateAnim.value),
        end = androidx.compose.ui.geometry.Offset(translateAnim.value + 300f, translateAnim.value + 300f),
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Centered loading badge
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = scheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                        color = scheme.primary,
                    )
                    Text(
                        uiContext.getString(R.string.ui_loading_chat),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurface,
                    )
                }
            }
        }

        // Skeleton assistant bubble (left)
        Row(
            modifier = Modifier.fillMaxWidth(0.85f),
            horizontalArrangement = Arrangement.Start,
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                color = scheme.surfaceContainerLow,
                border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(14.dp)
                            .background(shimmerBrush, androidx.compose.foundation.shape.RoundedCornerShape(7.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.65f)
                            .height(14.dp)
                            .background(shimmerBrush, androidx.compose.foundation.shape.RoundedCornerShape(7.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .height(14.dp)
                            .background(shimmerBrush, androidx.compose.foundation.shape.RoundedCornerShape(7.dp)),
                    )
                }
            }
        }

        // Skeleton user bubble (right)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                color = scheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(0.7f),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .background(shimmerBrush, androidx.compose.foundation.shape.RoundedCornerShape(7.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(14.dp)
                            .background(shimmerBrush, androidx.compose.foundation.shape.RoundedCornerShape(7.dp)),
                    )
                }
            }
        }

        // Skeleton assistant response block (left)
        Row(
            modifier = Modifier.fillMaxWidth(0.92f),
            horizontalArrangement = Arrangement.Start,
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                color = scheme.surfaceContainerLow,
                border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .height(14.dp)
                            .background(shimmerBrush, androidx.compose.foundation.shape.RoundedCornerShape(7.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(14.dp)
                            .background(shimmerBrush, androidx.compose.foundation.shape.RoundedCornerShape(7.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(14.dp)
                            .background(shimmerBrush, androidx.compose.foundation.shape.RoundedCornerShape(7.dp)),
                    )
                }
            }
        }
    }
}

/** Draw the conversation underneath the floating composer while keeping its layout/touch bounds. */
private fun Modifier.glassViewportExtra(extra: Int): Modifier = if (extra <= 0) this else layout { measurable, constraints ->
    val growth = if (constraints.hasBoundedHeight) extra else 0
    val child = measurable.measure(constraints.copy(minHeight = constraints.minHeight + growth, maxHeight = constraints.maxHeight + growth))
    layout(child.width, child.height - growth) { child.placeRelative(0, 0) }
}
