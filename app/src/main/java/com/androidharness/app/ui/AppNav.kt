package com.androidharness.app.ui

import com.androidharness.app.ui.theme.*
import com.androidharness.app.R
import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.foundation.layout.statusBarsPadding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.androidharness.app.ui.common.formatTokens
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.content.Context
import com.androidharness.app.AppContainer
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.MessageHit
import com.androidharness.app.data.db.ChatSearch
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.ui.chat.ChatViewModel
import com.androidharness.app.ui.projects.ProjectsScreen
import com.androidharness.app.ui.projects.ProjectScreen
import com.androidharness.app.ui.projects.ProjectMemoryScreen
import com.androidharness.app.ui.buildtest.BuildTestScreen
import com.androidharness.app.ui.common.formatRelativeTime
import com.androidharness.app.ui.files.ChangesScreen
import com.androidharness.app.ui.files.WorkspaceEditorScreen
import com.androidharness.app.ui.files.FilesScreen
import com.androidharness.app.ui.settings.ProvidersScreen
import com.androidharness.app.ui.settings.SettingsScreen
import com.androidharness.app.ui.settings.SkillsScreen
import com.androidharness.app.ui.setup.SetupScreen
import com.androidharness.app.ui.terminal.TerminalScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onSizeChanged

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppNav(container: AppContainer) {
    androidx.compose.runtime.LaunchedEffect(container) { container.sessions.repairChatTitles() }
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    com.androidharness.app.ui.settings.LocalModelWarningDialog(container.localModels)
    val nav = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val sessions by container.sessions.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    // Nullable gate: the synthetic AppSettings() default (onboardingDone=false)
    // used to pass for one frame as a real emission and flash the setup screen
    // for fully-onboarded users. Null = DataStore hasn't spoken yet.
    val settingsState by container.settings.settings
        .map { it as AppSettings? }
        .collectAsStateWithLifecycle(initialValue = null)
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val allWorkspaces by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val keyboard = LocalSoftwareKeyboardController.current

    // Setup fires exactly once, decided from DataStore's REAL first emission.
    val settings = settingsState
    if (settings == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    // Stay on setup until Skip or Start harness. Connecting a provider used
    // to flip this and remount NavHost onto chat mid-flow.
    val needsSetup = !settings.onboardingDone
    // Read startup preferences once. Changes made in Settings apply to the next launch,
    // and regular navigation must not recalculate or remount the initial destination.
    val launchSettings = remember(container) { settings }
    val startup by androidx.compose.runtime.produceState<com.androidharness.app.data.ConversationStartup?>(initialValue = null, key1 = container) {
        val previous = launchSettings.lastActiveSessionId?.let { container.sessions.session(it) }
        val previousKind = previous?.let { com.androidharness.app.data.ConversationConfig.decode(it.conversationJson).kind }
        value = com.androidharness.app.data.ConversationStartup.resolve(launchSettings, previousKind)
    }
    val launch = startup
    if (launch == null) { Box(Modifier.fillMaxSize()); return }
    val startDestination = remember {
        when {
            needsSetup -> "setup"
            launch.sessionId != null -> "chat/${launch.sessionId}"
            else -> "chat?kind=${launch.kind.name}"
        }
    }

    val desktopNavigation = com.androidharness.app.data.useWorkspacePanes(
        settings.workspaceLayout, androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.toFloat(),
        androidx.compose.ui.platform.LocalDensity.current.fontScale)
    var sidebarVisible by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    val desktopGuard = remember { com.androidharness.app.ui.files.EditorNavigationGuard() }
    val desktopFocus = remember { FocusRequester() }
    val desktopKeyboard = remember { DesktopKeyboardRouter() }
    androidx.compose.runtime.LaunchedEffect(desktopNavigation) {
        if (desktopNavigation) { drawerState.close(); androidx.compose.runtime.withFrameNanos { }; desktopFocus.requestFocus() }
    }

    var searchQuery by remember { mutableStateOf("") }
    var fuzzySearch by remember { mutableStateOf(false) }
    var messageHits by remember { mutableStateOf<List<MessageHit>>(emptyList()) }

    // Message hits lag the title filter by a short debounce so typing stays
    // fluid: word mode asks the FTS index, fuzzy mode falls back to LIKE.
    androidx.compose.runtime.LaunchedEffect(searchQuery, fuzzySearch) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            messageHits = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        messageHits = runCatching { container.sessions.searchMessages(q, fuzzySearch) }
            .getOrDefault(emptyList())
    }

    // End text entry as the drawer starts moving. Its initial focus belongs
    // to the sidebar container; later taps on search must keep their focus.
    androidx.compose.runtime.LaunchedEffect(drawerState.targetValue) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        if (drawerState.targetValue == DrawerValue.Closed) {
            // Drawer closed: collapse search so it starts fresh next open.
            searchQuery = ""
        }
    }

    // Which session the current back stack shows, for drawer highlighting.
    val currentEntry by nav.currentBackStackEntryFlow.collectAsStateWithLifecycle(initialValue = null)
    var draftSessionId by remember { mutableStateOf<String?>(null) }
    var workspaceChatViewModel by remember { mutableStateOf<ChatViewModel?>(null) }
    var lastConversation by remember { mutableStateOf(com.androidharness.app.data.ConversationConfig(kind = launch.kind)) }
    val currentSessionId = when (currentEntry?.destination?.route) {
        "chat/{sessionId}?messageId={messageId}" -> currentEntry?.arguments?.getString("sessionId")
        "chat?kind={kind}" -> draftSessionId
        else -> null
    }
    val isConversationRoute = currentEntry?.destination?.route in listOf("chat?kind={kind}", "chat/{sessionId}?messageId={messageId}")
    val activeChatState = workspaceChatViewModel?.state?.collectAsStateWithLifecycle()?.value
    val activeConversation = activeChatState?.conversation
        ?: sessions.firstOrNull { it.id == currentSessionId }?.let { com.androidharness.app.data.ConversationConfig.decode(it.conversationJson) }
        ?: lastConversation
    androidx.compose.runtime.SideEffect {
        if (activeChatState?.conversationReady == true) lastConversation = activeConversation
    }
    val activeKind = activeConversation.kind
    androidx.compose.runtime.LaunchedEffect(isConversationRoute, activeChatState?.conversationReady, activeKind) {
        if (isConversationRoute && activeChatState?.conversationReady == true) {
            container.settings.setLastConversationKind(activeKind)
        }
    }
    var codeRoute by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var plainRoute by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val workspaceSessionId = currentSessionId
        ?: currentEntry?.arguments?.getString("sessionId")
        ?: currentEntry?.arguments?.getString("session")?.takeIf { it.isNotBlank() }
    val workspaceProjectId = currentEntry?.arguments?.getString("projectId")
        ?: currentEntry?.arguments?.getString("project")?.takeIf { it.isNotBlank() }
    val currentWorkspace by remember(workspaceSessionId, workspaceProjectId) { container.workspace.projectForTools(workspaceSessionId, workspaceProjectId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val chatWorkspaceSelection by remember(workspaceSessionId) { container.workspace.selectionForChat(workspaceSessionId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val chatWorkspaceContext = workspaceSessionId != null || workspaceChatViewModel != null
    val workspaceSummary = if (chatWorkspaceContext) {
        val count = chatWorkspaceSelection?.grants?.size ?: 1
        "$count workspace${if (count == 1) "" else "s"}" +
            currentWorkspace?.name?.let { " · Primary: $it" }.orEmpty()
    } else currentWorkspace?.name ?: uiContext.getString(R.string.ui_choose_workspace)
    val activeFs by remember(workspaceSessionId, workspaceProjectId) { container.workspace.forTools(workspaceSessionId, workspaceProjectId) }
        .collectAsStateWithLifecycle(initialValue = null)
    var workspaceError by remember { mutableStateOf<String?>(null) }
    var creatingProjectChat by remember { mutableStateOf(false) }
    fun selectWorkspace(id: String) {
        val sid = workspaceSessionId
        scope.launch {
            try {
                if (sid == null && workspaceProjectId != null) nav.navigate("project/${encode(id)}")
                else if (sid == null) container.workspace.setActiveProject(id)
                else container.runManager.assignWorkspace(sid, id)
            } catch (e: Exception) { workspaceError = e.message }
        }
    }

    // Track active session in DataStore whenever navigating to a chat session
    androidx.compose.runtime.LaunchedEffect(currentSessionId) {
        if (!currentSessionId.isNullOrBlank()) {
            container.settings.setLastActiveSessionId(currentSessionId)
        }
    }
    val currentRoute = currentEntry?.destination?.route
    val drawerGesturesEnabled = drawerSwipeAllowed(currentRoute)
    // A drawer that was opening on the previous screen must not cover Settings.
    androidx.compose.runtime.LaunchedEffect(drawerGesturesEnabled) {
        if (!drawerGesturesEnabled) drawerState.close()
    }
    val runningSessionIds by container.runManager.runningSessionIds.collectAsStateWithLifecycle()

    var actionsSession by remember { mutableStateOf<SessionEntity?>(null) }
    var viewingSavedAnswer by remember { mutableStateOf<com.androidharness.app.data.db.SavedAnswerRow?>(null) }
    val savedAnswerRows by remember { container.sessions.savedAnswers() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var renamingSession by remember { mutableStateOf<SessionEntity?>(null) }
    var renamingProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var deletingProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var projectName by remember { mutableStateOf("") }
    var detailsSession by remember { mutableStateOf<SessionEntity?>(null) }
    var deletingSession by remember { mutableStateOf<SessionEntity?>(null) }
    var selectedChatIds by androidx.compose.runtime.saveable.rememberSaveable(activeKind) { mutableStateOf(emptyList<String>()) }
    var batchDetails by remember { mutableStateOf(emptySet<String>()) }
    var batchDelete by remember { mutableStateOf(emptySet<String>()) }
    var batchBusy by remember { mutableStateOf(false) }
    val chatSelectionMutex = remember { kotlinx.coroutines.sync.Mutex() }
    androidx.compose.runtime.LaunchedEffect(activeKind) { batchDetails = emptySet(); batchDelete = emptySet() }
    androidx.compose.runtime.LaunchedEffect(drawerState.currentValue, desktopNavigation) {
        if (!desktopNavigation && drawerState.currentValue == DrawerValue.Closed) selectedChatIds = emptyList()
    }
    val sidebarSnackbar = remember { androidx.compose.material3.SnackbarHostState() }
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showAddWorkspace by remember { mutableStateOf(false) }

    // One-time notice for installs whose Linux environment predates a package
    // addition (gh): shown once, then never again, whichever button closes it.
    val latePackages = remember { container.linuxEnv.latePackagesPending() }
    var showLatePackagesNotice by remember {
        val prefs = container.appContext.getSharedPreferences("notices", Context.MODE_PRIVATE)
        mutableStateOf(latePackages.isNotEmpty() && !prefs.getBoolean("late_packages_v1", false))
    }
    fun dismissLatePackagesNotice() {
        container.appContext.getSharedPreferences("notices", Context.MODE_PRIVATE)
            .edit().putBoolean("late_packages_v1", true).apply()
        showLatePackagesNotice = false
    }

    // System folder picker for adding a SAF workspace from the drawer.
    val safWorkspacePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { scope.launch {
        try { selectWorkspace(container.workspace.addPickedFolder(it).id) }
        catch (e: Exception) { workspaceError = e.message }
    } } }

    // Run-result notifications deep-link into the session's chat.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        container.pendingSubagentChat.collect { destination ->
            if (destination != null) {
                val (sid, callId) = destination
                if (container.sessions.session(sid) != null) {
                    nav.navigateToConversation("chat/${encode(sid)}")
                    nav.navigate("subagent/${encode(sid)}/${encode(callId)}") { launchSingleTop = true }
                }
                container.pendingSubagentChat.compareAndSet(destination, null)
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        container.pendingSessionId.collect { sid -> nav.navigateToConversation("chat/$sid") }
    }
    androidx.compose.runtime.LaunchedEffect(container) {
        container.pendingImportedWorkspace.collect { projectId ->
            if (projectId != null) {
                container.pendingImportedWorkspace.compareAndSet(projectId, null)
                desktopGuard.navigate {
                    nav.navigate("files?project=${encode(projectId)}") { launchSingleTop = true }
                }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        container.pendingSettingsScroll.collect { target ->
            if (target != null && nav.currentDestination?.route != "settings") nav.navigate("settings") { launchSingleTop = true }
        }
    }

    fun openChat(sessionId: String?, messageId: String? = null, kind: com.androidharness.app.data.ConversationKind = activeKind) {
        scope.launch { drawerState.close() }
        if (sessionId == null) {
            draftSessionId = null
            scope.launch { container.settings.setLastActiveSessionId(null) }
            nav.navigateToConversation("chat?kind=${kind.name}")
        } else {
            val target = messageId?.let { "?messageId=${encode(it)}" }.orEmpty()
            nav.navigateToConversation("chat/${encode(sessionId)}$target")
        }
    }

    fun navigateFromSidebar(action: () -> Unit) {
        if (batchBusy) return
        selectedChatIds = emptyList()
        val navigate = { scope.launch { drawerState.close() }; action() }
        if (desktopNavigation) desktopGuard.navigate(navigate) else navigate()
    }
    fun sidebarAction(session: SessionEntity, action: SidebarChatAction) {
        when (action) {
            SidebarChatAction.SELECT -> selectedChatIds = listOf(session.id)
            SidebarChatAction.RENAME -> renamingSession = session
            SidebarChatAction.DETAILS -> detailsSession = session
            SidebarChatAction.DELETE -> deletingSession = session
            SidebarChatAction.PIN -> scope.launch { container.settings.setPinned(session.id, session.id !in settings.pinnedSessions) }
            SidebarChatAction.ARCHIVE -> scope.launch {
                val wasArchived = session.id in settings.archivedSessions
                container.settings.setArchived(session.id, !wasArchived)
                if (sidebarSnackbar.showSnackbar(if (wasArchived) uiContext.getString(R.string.ui_chat_restored) else uiContext.getString(R.string.ui_chat_archived), actionLabel = uiContext.getString(R.string.ui_undo)) ==
                    androidx.compose.material3.SnackbarResult.ActionPerformed) container.settings.setArchived(session.id, wasArchived)
            }
        }
    }
    fun selectedChatAction(ids: Set<String>, action: SidebarChatAction) {
        if (batchBusy) return
        val targets = sessions.filter { it.id in ids && com.androidharness.app.data.ConversationConfig.decode(it.conversationJson).kind == activeKind }
        if (targets.isEmpty()) { selectedChatIds = emptyList(); return }
        val selectedIds = targets.mapTo(mutableSetOf()) { it.id }
        when (action) {
            SidebarChatAction.DETAILS -> batchDetails = selectedIds
            SidebarChatAction.DELETE -> batchDelete = selectedIds
            SidebarChatAction.ARCHIVE -> scope.launch {
                batchBusy = true
                val change = try {
                    chatSelectionMutex.withLock {
                        val previous = container.settings.settings.first().archivedSessions.toSet()
                        val archive = !selectedIds.all { it in previous }
                        container.settings.setArchived(selectedIds, archive)
                        previous to archive
                    }
                } catch (e: Exception) {
                    batchBusy = false
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    sidebarSnackbar.showSnackbar(uiContext.getString(R.string.sidebar_selection_error))
                    return@launch
                }
                selectedChatIds = emptyList()
                batchBusy = false
                val (previous, archive) = change
                try {
                    val message = uiContext.resources.getQuantityString(if (archive) R.plurals.sidebar_archived_count else R.plurals.sidebar_restored_count, selectedIds.size, selectedIds.size)
                    if (sidebarSnackbar.showSnackbar(message, actionLabel = uiContext.getString(R.string.ui_undo)) == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                        chatSelectionMutex.withLock {
                            val existing = container.sessions.sessions.first().mapTo(mutableSetOf()) { it.id }
                            container.settings.restoreArchived(selectedIds intersect existing, previous)
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    sidebarSnackbar.showSnackbar(uiContext.getString(R.string.sidebar_selection_error))
                }
            }
            else -> Unit
        }
    }
    fun selectSection(kind: com.androidharness.app.data.ConversationKind, source: ChatViewModel? = null) {
        if (batchBusy) return
        val origin = source?.state?.value
        if (origin != null) {
            if (kind == origin.conversation.kind) return
            val here = origin.sessionId?.let { "chat/$it" } ?: "chat?kind=${origin.conversation.kind.name}"
            if (origin.conversation.kind == com.androidharness.app.data.ConversationKind.CODE) codeRoute = here else plainRoute = here
        }
        val previous = if (kind == com.androidharness.app.data.ConversationKind.CODE) codeRoute else plainRoute
        // Chat and Code each start fresh on their first entry in this app session
        // unless the user asked to continue that section's latest conversation.
        // Returning to a section already open here still uses its route, so drafts survive.
        val resume = when (kind) {
            com.androidharness.app.data.ConversationKind.CHAT ->
                settings.chatStart == com.androidharness.app.data.ChatStartMode.LAST_CHAT
            com.androidharness.app.data.ConversationKind.CODE ->
                settings.codeStart == com.androidharness.app.data.CodeStartMode.LAST_CODE
        }
        val last = sessions.firstOrNull { com.androidharness.app.data.ConversationConfig.decode(it.conversationJson).kind == kind && it.id !in settings.archivedSessions }
            ?.takeIf { resume }
        val availablePrevious = previous?.takeIf { route -> sessions.any { "chat/${it.id}" == route && it.id !in settings.archivedSessions } }
        val target = availablePrevious ?: last?.let { "chat/${it.id}" } ?: "chat?kind=${kind.name}"
        nav.navigateToConversation(target)
    }
    val sectionRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    fun sectionMotion(from: androidx.navigation.NavBackStackEntry, to: androidx.navigation.NavBackStackEntry): Int {
        fun kind(entry: androidx.navigation.NavBackStackEntry): com.androidharness.app.data.ConversationKind? {
            if (entry.destination.route?.startsWith("chat") != true) return null
            return entry.arguments?.getString("kind")?.let {
                runCatching { com.androidharness.app.data.ConversationKind.valueOf(it) }.getOrNull()
            } ?: sessions.firstOrNull { it.id == entry.arguments?.getString("sessionId") }
                ?.let { com.androidharness.app.data.ConversationConfig.decode(it.conversationJson).kind }
        }
        val origin = kind(from) ?: return 0
        val destination = kind(to) ?: return 0
        val direction = if (origin == destination) 0 else if (destination == com.androidharness.app.data.ConversationKind.CHAT) 1 else -1
        return direction * if (sectionRtl) -1 else 1
    }
    @Composable fun Sidebar(onCollapse: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
        val changes by remember(currentSessionId) {
            currentSessionId?.let(container.sessions::fileChangesFor) ?: kotlinx.coroutines.flow.flowOf(emptyList())
        }.collectAsStateWithLifecycle(initialValue = emptyList())
        ProjectNavigationSidebar(
            projects = if (activeConversation.isChat) emptyList() else allWorkspaces, sessions = sessions.filter { com.androidharness.app.data.ConversationConfig.decode(it.conversationJson).kind == activeKind }, selectedSession = currentSessionId, selectedProject = currentWorkspace?.id,
            pinned = settings.pinnedSessions.toSet(), archived = settings.archivedSessions.toSet(), query = searchQuery,
            onQueryChange = { searchQuery = it }, messageHits = messageHits.filter { hit -> sessions.any { it.id == hit.sessionId && com.androidharness.app.data.ConversationConfig.decode(it.conversationJson).kind == activeKind } }, fuzzy = fuzzySearch, onFuzzyChange = { fuzzySearch = !fuzzySearch },
            activity = { session ->
                if (session.id in runningSessionIds) {
                    val live by remember(session.id) { container.runManager.live(session.id) }.collectAsStateWithLifecycle()
                    sidebarActivity(true, session.pendingPlan, live)
                } else sidebarActivity(false, session.pendingPlan)
            },
            onNewChat = { navigateFromSidebar {
                scope.launch { currentWorkspace?.id?.let { container.workspace.setActiveProject(it) }; openChat(null) }
            } }, onChat = { sid -> navigateFromSidebar { openChat(sid) } },
            onMessage = { hit -> navigateFromSidebar { openChat(hit.sessionId, hit.messageId) } },
            onProjectChat = { project -> if (!creatingProjectChat) {
                creatingProjectChat = true
                val create = { scope.launch {
                    try { openChat(container.sessions.createSession(uiContext.getString(R.string.settings_telegram_new_chat), project.id)) }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; workspaceError = e.message ?: uiContext.getString(R.string.ui_could_not_create_chat) }
                    finally { creatingProjectChat = false }
                }; Unit }
                if (desktopNavigation) {
                    // A cancelled editor guard must not leave every new-chat button disabled.
                    creatingProjectChat = false
                    desktopGuard.navigate { creatingProjectChat = true; create() }
                } else create()
            } },
            onProjects = { navigateFromSidebar { nav.navigate("projects") { launchSingleTop = true } } },
            onChatActions = { actionsSession = it }, onAction = ::sidebarAction,
            selectedChats = selectedChatIds.toSet(), onSelectedChatsChange = { selectedChatIds = it.toList() },
            onSelectedChatsAction = ::selectedChatAction, selectionBusy = batchBusy,
            savedAnswers = if (activeConversation.isChat) savedAnswerRows.filter { row -> sessions.any { it.id == row.sessionId } } else emptyList(),
            onOpenSavedAnswer = { viewingSavedAnswer = it },
            onBuild = { navigateFromSidebar { nav.navigate("build-test?session=${encode(workspaceSessionId.orEmpty())}&project=${encode(workspaceProjectId.orEmpty())}") } },
            onTerminal = { navigateFromSidebar { nav.navigate("terminal?session=${encode(workspaceSessionId.orEmpty())}&project=${encode(workspaceProjectId.orEmpty())}") } },
            onAutomation = { navigateFromSidebar { nav.navigate("automation") { launchSingleTop = true } } },
            onProviders = { navigateFromSidebar { nav.navigate("providers") { launchSingleTop = true } } },
            onSettings = { navigateFromSidebar { nav.navigate("settings") { launchSingleTop = true } } },
            workspaceLabel = if (chatWorkspaceContext) uiContext.getString(R.string.ui_workspaces_for_this_chat) else uiContext.getString(R.string.ui_workspace), workspaceSummary = workspaceSummary,
            onWorkspaces = { selectedChatIds = emptyList(); scope.launch { drawerState.close() }; showWorkspaceSheet = true },
            onCollapse = { if (!batchBusy) { selectedChatIds = emptyList(); onCollapse() } },
            currentRoute = currentRoute, projectCreationEnabled = !creatingProjectChat,
            changesSummary = changes.takeIf { it.isNotEmpty() }?.let { uiContext.getString(R.string.ui_files_changed_758, (it.size).toString()) },
            onChanges = { currentSessionId?.let { sid -> navigateFromSidebar { nav.navigate("changes/${encode(sid)}") } } },
            snackbar = sidebarSnackbar,
            chatOnly = activeConversation.isChat,
            sectionSwitchEnabled = workspaceChatViewModel?.state?.value?.let { it.conversationReady && !it.conversationSaving } ?: true,
            onSelectSection = { kind ->
                searchQuery = ""
                val source = workspaceChatViewModel
                if (source != null && isConversationRoute) {
                    source.requestSectionSwitch(kind)?.let { error -> scope.launch { sidebarSnackbar.showSnackbar(error) } }
                } else navigateFromSidebar { selectSection(kind) }
            },
            projectsView = settings.sidebarProjects && !activeConversation.isChat,
            onProjectsViewChange = { scope.launch { container.settings.setSidebarProjects(it) } },
            projectPins = settings.pinnedProjects, projectArchives = settings.archivedProjects,
            onProjectAction = { project, action -> when (action) {
                SidebarProjectAction.OPEN -> navigateFromSidebar { nav.navigate("project/${encode(project.id)}") { launchSingleTop = true } }
                SidebarProjectAction.RENAME -> { projectName = project.name; renamingProject = project }
                SidebarProjectAction.DELETE -> {
                    val show = { deletingProject = project; Unit }
                    if (desktopNavigation) desktopGuard.navigate(show) else show()
                }
                SidebarProjectAction.PIN -> scope.launch { container.settings.setProjectPinned(project.id, project.id !in settings.pinnedProjects) }
                SidebarProjectAction.ARCHIVE -> scope.launch {
                    val wasArchived = project.id in settings.archivedProjects
                    container.settings.setProjectArchived(project.id, !wasArchived)
                    if (sidebarSnackbar.showSnackbar(if (wasArchived) uiContext.getString(R.string.ui_project_restored) else uiContext.getString(R.string.ui_project_archived), actionLabel = uiContext.getString(R.string.ui_undo)) ==
                        androidx.compose.material3.SnackbarResult.ActionPerformed) container.settings.setProjectArchived(project.id, wasArchived)
                }
            } },
        )
    }

    val incomingShare by container.pendingShare.collectAsStateWithLifecycle()
    var openingShare by remember { mutableStateOf(false) }
    var shareError by remember(incomingShare?.id) { mutableStateOf<String?>(null) }
    // Restore the selected destination if the activity was recreated before draft import.
    androidx.compose.runtime.LaunchedEffect(incomingShare?.id, incomingShare?.targetSessionId, needsSetup) {
        val sid = incomingShare?.targetSessionId
        if (!needsSetup && sid != null && (currentSessionId != sid || !isConversationRoute)) {
            openChat(sid)
        }
    }
    incomingShare?.takeIf { !needsSetup && it.targetSessionId == null }?.let { share ->
        com.androidharness.app.ui.chat.components.ShareToChatSheet(
            share = share,
            chats = sessions.filter {
                com.androidharness.app.data.ConversationConfig.decode(it.conversationJson).isChat && it.id !in settings.archivedSessions
            },
            opening = openingShare,
            error = shareError,
            onDismiss = { container.pendingShare.compareAndSet(share, null) },
            onOpen = { chosenId, question ->
                navigateFromSidebar {
                    if (!openingShare) {
                        openingShare = true
                        scope.launch {
                            var created: String? = null
                            try {
                                val sid = if (chosenId == null) {
                                    container.sessions.createSession(uiContext.getString(R.string.settings_telegram_new_chat),
                                        conversation = com.androidharness.app.data.ConversationConfig(com.androidharness.app.data.ConversationKind.CHAT))
                                        .also { created = it; container.chatModels.initialize(it) }
                                } else {
                                    val destination = container.sessions.session(chosenId)
                                    check(destination != null && com.androidharness.app.data.ConversationConfig.decode(destination.conversationJson).isChat)
                                    chosenId
                                }
                                val text = listOfNotNull(share.text, question).joinToString("\n\n").takeIf(String::isNotBlank)
                                if (!container.pendingShare.compareAndSet(share, share.copy(text = text, targetSessionId = sid))) {
                                    created?.let { container.sessions.session(it)?.let { session -> container.sessions.deleteSession(session) } }
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                created?.let { container.sessions.session(it)?.let { session -> container.sessions.deleteSession(session) } }
                                shareError = uiContext.getString(R.string.chat_share_failed)
                            } finally { openingShare = false }
                        }
                    }
                }
            },
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalDesktopLayout provides desktopNavigation,
        LocalDesktopInput provides (settings.workspaceLayout == com.androidharness.app.data.WorkspaceLayoutMode.DESKTOP),
        LocalDesktopNavigationGuard provides desktopGuard,
        LocalDesktopKeyboardRouter provides if (desktopNavigation) desktopKeyboard else null,
    ) {
    val glass = LocalHarnessGlass.current.mode != HarnessGlassMode.OFF
    var glassDrawerWidth by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val glassDrawerCapture by remember(drawerState) { androidx.compose.runtime.derivedStateOf {
        drawerState.currentValue != DrawerValue.Closed || drawerState.targetValue != DrawerValue.Closed ||
            drawerState.isAnimationRunning || (glassDrawerWidth > 0 && drawerState.currentOffset > -glassDrawerWidth + .5f)
    } }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !desktopNavigation && drawerGesturesEnabled,
        drawerContent = {
            if (!desktopNavigation) ModalDrawerSheet(
                modifier = if (glass) Modifier.onSizeChanged { glassDrawerWidth = it.width }
                    .padding(horizontal = 10.dp).statusBarsPadding().navigationBarsPadding().padding(vertical = 8.dp) else Modifier,
                drawerShape = if (glass) androidx.compose.foundation.shape.RoundedCornerShape(32.dp) else androidx.compose.material3.DrawerDefaults.shape,
                drawerContainerColor = if (glass) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface,
                windowInsets = if (glass) androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0) else androidx.compose.material3.DrawerDefaults.windowInsets,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalHarnessGlassCaptureActive provides glassDrawerCapture,
                    LocalHarnessGlassBackdrop provides (LocalHarnessGlassOverlayBackdrop.current ?: LocalHarnessGlassBackdrop.current)) {
                Sidebar { scope.launch { drawerState.close() } }
                }
            }
        },
    ) {
        Row(Modifier.fillMaxSize().focusRequester(desktopFocus).onPreviewKeyEvent { event ->
            if (desktopNavigation && desktopKeyboard.handler?.invoke(event) == true) true
            else if (!desktopNavigation || !event.isCtrlPressed) false
            else when (event.key) {
                Key.N -> { if (event.type == KeyEventType.KeyUp) desktopGuard.navigate { openChat(null) }; true }
                Key.Comma -> { if (event.type == KeyEventType.KeyUp) desktopGuard.navigate {
                    nav.navigate("settings") { launchSingleTop = true }
                }; true }
                Key.B -> if (event.isShiftPressed) { if (event.type == KeyEventType.KeyUp) sidebarVisible = !sidebarVisible; true } else false
                else -> false
            }
        }.then(if (desktopNavigation) Modifier.focusable() else Modifier).then(if (desktopNavigation) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)) {
            RetainedWorkspaceContent(desktopNavigation && sidebarVisible,
                Modifier.width(if (desktopNavigation && sidebarVisible) 232.dp else 0.dp)) {
                Sidebar { sidebarVisible = false }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
        if (currentRoute?.startsWith("project") != true) {
            com.androidharness.app.ui.common.SshWorkspaceBar(container, activeFs as? com.androidharness.app.workspace.SshFs)
        }
        NavHost(
            modifier = Modifier.weight(1f).recordHarnessGlassScene(LocalHarnessGlassScene.current),
            navController = nav,
            startDestination = startDestination,
            // Section changes share an expressive fade-through and a short directional move.
            enterTransition = {
                val direction = sectionMotion(initialState, targetState)
                if (direction == 0) fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 28 }
                else fadeIn(com.androidharness.app.ui.theme.expressiveEffectsSpec()) +
                    androidx.compose.animation.scaleIn(com.androidharness.app.ui.theme.expressiveSpatialSpec(), initialScale = .98f) +
                    androidx.compose.animation.slideInHorizontally(com.androidharness.app.ui.theme.expressiveSpatialSpec()) { it / 10 * direction }
            },
            exitTransition = {
                val direction = sectionMotion(initialState, targetState)
                if (direction == 0) fadeOut(tween(180, easing = FastOutSlowInEasing))
                else fadeOut(com.androidharness.app.ui.theme.expressiveEffectsSpec()) +
                    androidx.compose.animation.slideOutHorizontally(com.androidharness.app.ui.theme.expressiveSpatialSpec()) { -it / 20 * direction }
            },
            popEnterTransition = {
                val direction = sectionMotion(initialState, targetState)
                if (direction == 0) fadeIn(tween(220, easing = FastOutSlowInEasing))
                else fadeIn(com.androidharness.app.ui.theme.expressiveEffectsSpec()) +
                    androidx.compose.animation.scaleIn(com.androidharness.app.ui.theme.expressiveSpatialSpec(), initialScale = .98f) +
                    androidx.compose.animation.slideInHorizontally(com.androidharness.app.ui.theme.expressiveSpatialSpec()) { it / 10 * direction }
            },
            popExitTransition = {
                val direction = sectionMotion(initialState, targetState)
                if (direction == 0) fadeOut(tween(180, easing = FastOutSlowInEasing)) +
                    slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it / 28 }
                else fadeOut(com.androidharness.app.ui.theme.expressiveEffectsSpec()) +
                    androidx.compose.animation.slideOutHorizontally(com.androidharness.app.ui.theme.expressiveSpatialSpec()) { -it / 20 * direction }
            },
        ) {
            composable("chat?kind={kind}", arguments = listOf(navArgument("kind") { defaultValue = "CODE" })) { entry ->
                val kind = runCatching { com.androidharness.app.data.ConversationKind.valueOf(entry.arguments?.getString("kind").orEmpty()) }
                    .getOrDefault(com.androidharness.app.data.ConversationKind.CODE)
                val vm: ChatViewModel = viewModel(factory = ChatViewModel.factory(container, null, kind))
                androidx.compose.runtime.DisposableEffect(vm) {
                    workspaceChatViewModel = vm
                    onDispose { if (workspaceChatViewModel === vm) workspaceChatViewModel = null }
                }
                val draftState by vm.state.collectAsStateWithLifecycle()
                androidx.compose.runtime.LaunchedEffect(draftState.sessionId) { draftSessionId = draftState.sessionId }
                ChatWorkspaceScreen(
                    viewModel = vm,
                    onSelectSection = { kind -> selectSection(kind, vm) },
                    onOpenDrawer = {
                        focusManager.clearFocus(force = true)
                        if (desktopNavigation) sidebarVisible = !sidebarVisible else scope.launch { drawerState.open() }
                    },
                    onOpenFile = { path, line ->
                        nav.navigate("viewer/${encode(path)}?line=${line ?: 0}&session=${encode(vm.state.value.sessionId.orEmpty())}")
                    },
                    onNewChat = {
                        draftSessionId = null
                        scope.launch { container.settings.setLastActiveSessionId(null) }
                        openChat(null, kind = vm.state.value.conversation.kind)
                    },
                    onOpenTerminal = { nav.navigate("terminal?session=${encode(vm.state.value.sessionId.orEmpty())}") },
                    onOpenFiles = { nav.navigate("files?session=${encode(vm.state.value.sessionId.orEmpty())}") },
                    onOpenSubagent = { callId ->
                        vm.state.value.sessionId?.let { sid ->
                            nav.navigate("subagent/${encode(sid)}/${encode(callId)}")
                        }
                    },
                    onOpenSettings = { nav.navigate("settings") },
                    onNavigateToSession = { sid ->
                        nav.navigateToConversation("chat/$sid")
                    },
                )
            }
            composable(
                "chat/{sessionId}?messageId={messageId}",
                arguments = listOf(
                    navArgument("sessionId") { type = NavType.StringType },
                    navArgument("messageId") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                val sessionId = entry.arguments?.getString("sessionId")
                val searchMessageId = entry.arguments?.getString("messageId")
                val initialKind = sessions.firstOrNull { it.id == sessionId }?.let {
                    com.androidharness.app.data.ConversationConfig.decode(it.conversationJson).kind
                } ?: if (sessionId == launch.sessionId) launch.kind else com.androidharness.app.data.ConversationKind.CODE
                val vm: ChatViewModel =
                    viewModel(factory = ChatViewModel.factory(container, sessionId, initialKind))
                androidx.compose.runtime.DisposableEffect(vm) {
                    workspaceChatViewModel = vm
                    onDispose { if (workspaceChatViewModel === vm) workspaceChatViewModel = null }
                }
                ChatWorkspaceScreen(
                    viewModel = vm,
                    onSelectSection = { kind -> selectSection(kind, vm) },
                    searchMessageId = searchMessageId,
                    onOpenDrawer = {
                        focusManager.clearFocus(force = true)
                        if (desktopNavigation) sidebarVisible = !sidebarVisible else scope.launch { drawerState.open() }
                    },
                    onOpenFile = { path, line ->
                        val sid = vm.state.value.sessionId ?: sessionId
                        nav.navigate("viewer/${encode(path)}?line=${line ?: 0}&session=${encode(sid.orEmpty())}")
                    },
                    onNewChat = {
                        draftSessionId = null
                        scope.launch { container.settings.setLastActiveSessionId(null) }
                        openChat(null, kind = vm.state.value.conversation.kind)
                    },
                    onOpenTerminal = { nav.navigate("terminal?session=${encode(vm.state.value.sessionId.orEmpty())}") },
                    onOpenFiles = { nav.navigate("files?session=${encode(vm.state.value.sessionId.orEmpty())}") },
                    onOpenSubagent = { callId ->
                        vm.state.value.sessionId?.let { sid ->
                            nav.navigate("subagent/${encode(sid)}/${encode(callId)}")
                        }
                    },
                    onOpenSettings = { nav.navigate("settings") },
                    onNavigateToSession = { sid ->
                        nav.navigateToConversation("chat/$sid")
                    },
                )
            }
            composable(
                "subagent/{sessionId}/{toolCallId}",
                arguments = listOf(
                    navArgument("sessionId") { type = NavType.StringType },
                    navArgument("toolCallId") { type = NavType.StringType },
                ),
            ) { entry ->
                com.androidharness.app.ui.subagent.SubagentScreen(
                    container = container,
                    sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                    toolCallId = entry.arguments?.getString("toolCallId").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable("terminal?session={session}&project={project}", arguments = listOf(
                navArgument("session") { defaultValue = "" }, navArgument("project") { defaultValue = "" })) { entry ->
                TerminalScreen(container = container, sessionId = entry.arguments?.getString("session")?.takeIf { it.isNotBlank() },
                    projectId = entry.arguments?.getString("project")?.takeIf { it.isNotBlank() }, onBack = { nav.popBackStack() })
            }
            composable("files?session={session}&project={project}", arguments = listOf(
                navArgument("session") { defaultValue = "" }, navArgument("project") { defaultValue = "" })) { entry ->
                val filesSessionId = entry.arguments?.getString("session")?.takeIf { it.isNotBlank() }
                val filesProjectId = entry.arguments?.getString("project")?.takeIf { it.isNotBlank() }
                FilesScreen(
                    container = container,
                    sessionId = filesSessionId,
                    projectId = filesProjectId,
                    onBack = { nav.popBackStack() },
                    onOpenFile = { path ->
                        nav.navigate("viewer/${encode(path)}?line=0&session=${encode(filesSessionId.orEmpty())}&project=${encode(filesProjectId.orEmpty())}")
                    },
                    onOpenFileAtLine = { path, line ->
                        nav.navigate("viewer/${encode(path)}?line=${line ?: 0}&session=${encode(filesSessionId.orEmpty())}&project=${encode(filesProjectId.orEmpty())}")
                    },
                    onOpenTerminal = { nav.navigate("terminal?session=${encode(filesSessionId.orEmpty())}&project=${encode(filesProjectId.orEmpty())}") },
                    onOpenChanges = filesSessionId?.let { sid ->
                        { nav.navigate("changes/${encode(sid)}") }
                    },
                )
            }
            composable("changes/{sessionId}") { entry ->
                val sid = entry.arguments?.getString("sessionId").orEmpty()
                ChangesScreen(
                    container = container,
                    sessionId = sid,
                    onBack = { nav.popBackStack() },
                    onAskAgent = { prompt ->
                        container.pendingAgentPrompt.value = prompt
                        nav.navigateToConversation("chat/${encode(sid)}")
                    },
                )
            }
            composable(
                "viewer/{path}?line={line}&session={session}&project={project}",
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType },
                    navArgument("line") { type = NavType.IntType; defaultValue = 0 },
                    navArgument("session") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("project") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val path = entry.arguments?.getString("path").orEmpty()
                val line = entry.arguments?.getInt("line")?.takeIf { it > 0 }
                val sessionArg = entry.arguments?.getString("session").orEmpty()
                WorkspaceEditorScreen(
                    container = container,
                    path = path,
                    requestId = entry.id,
                    onOpenTerminal = { nav.navigate("terminal?session=${encode(sessionArg)}&project=${encode(entry.arguments?.getString("project").orEmpty())}") },
                    initialLine = line,
                    sessionId = sessionArg.takeIf { it.isNotBlank() },
                    projectId = entry.arguments?.getString("project")?.takeIf { it.isNotBlank() },
                    onBack = { nav.popBackStack() },
                    onAskAgent = { sourceId, prompt ->
                        scope.launch {
                            try {
                                val sid = sessionArg.takeIf { it.isNotBlank() }
                                    ?: sourceId?.let { container.sessions.createProjectSession(it) }
                                    ?: error(uiContext.getString(R.string.ui_choose_a_workspace_before_asking_about_code))
                                container.pendingCodeDraft.value = com.androidharness.app.ui.files.CodeChatDraft(sid, prompt)
                                val route = if (draftSessionId == sid) "chat" else "chat/$sid"
                                if (!nav.popBackStack(route, inclusive = false)) openChat(sid)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { workspaceError = e.message }
                        }
                    },
                )
            }
            composable("automation") {
                com.androidharness.app.ui.automation.AutomationScreen(container,
                    onBack = { nav.popBackStack() },
                    onOpenSession = { openChat(it) })
            }
            composable("projects") {
                ProjectsScreen(container, onBack = { nav.popBackStack() },
                    onOpenProject = { nav.navigate("project/${encode(it)}") },
                    onProjectDeleted = {
                        draftSessionId = null
                        nav.navigate("projects") { popUpTo(nav.graph.id); launchSingleTop = true }
                    })
            }
            composable("project/{projectId}", arguments = listOf(navArgument("projectId") { type = NavType.StringType })) { entry ->
                val id = requireNotNull(entry.arguments?.getString("projectId"))
                fun openProjectChat(projectId: String, prompt: String? = null) {
                    if (creatingProjectChat) return
                    creatingProjectChat = true
                    scope.launch {
                        try {
                            val sid = container.sessions.createProjectSession(projectId)
                            if (prompt != null) container.pendingAgentPrompt.value = prompt
                            nav.navigateToConversation("chat/${encode(sid)}")
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { workspaceError = e.message }
                        finally { creatingProjectChat = false }
                    }
                }
                ProjectScreen(container, id, onBack = { nav.popBackStack() },
                    onOpenChat = { openChat(it) },
                    onNewChat = { openProjectChat(it) }, creatingChat = creatingProjectChat,
                    onMemory = { nav.navigate("project/${encode(id)}/memory") },
                    onAutomation = { taskId, create ->
                        nav.navigate("project/${encode(id)}/automation?taskId=${encode(taskId.orEmpty())}&create=$create")
                    }, onOpenSettings = { nav.navigate("settings") },
                    onOpenFiles = { nav.navigate("files?project=${encode(id)}") },
                    onOpenTerminal = { nav.navigate("terminal?project=${encode(id)}") },
                    onOpenBuildTest = { nav.navigate("build-test?project=${encode(id)}") },
                    onSendPrompt = { openProjectChat(id, it) },
                    onProjectDeleted = {
                        draftSessionId = null
                        nav.navigate("projects") { popUpTo(nav.graph.id); launchSingleTop = true }
                    })
            }
            composable("project/{projectId}/memory", arguments = listOf(navArgument("projectId") { type = NavType.StringType })) { entry ->
                ProjectMemoryScreen(container, requireNotNull(entry.arguments?.getString("projectId")), onBack = { nav.popBackStack() })
            }
            composable("project/{projectId}/automation?taskId={taskId}&create={create}", arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("taskId") { type = NavType.StringType; defaultValue = "" },
                navArgument("create") { type = NavType.BoolType; defaultValue = false },
            )) { entry ->
                com.androidharness.app.ui.automation.AutomationScreen(container,
                    onBack = { nav.popBackStack() }, onOpenSession = { openChat(it) },
                    projectId = entry.arguments?.getString("projectId"),
                    initialTaskId = entry.arguments?.getString("taskId")?.takeIf { it.isNotBlank() },
                    createOnOpen = entry.arguments?.getBoolean("create") == true)
            }
            composable("build-test?session={session}&project={project}", arguments = listOf(
                navArgument("session") { defaultValue = "" }, navArgument("project") { defaultValue = "" })) { entry ->
                val buildSessionId = entry.arguments?.getString("session")?.takeIf { it.isNotBlank() }
                val buildProjectId = entry.arguments?.getString("project")?.takeIf { it.isNotBlank() }
                BuildTestScreen(
                    container = container,
                    sessionId = buildSessionId,
                    projectId = buildProjectId,
                    onBack = { nav.popBackStack() },
                    onOpenFile = { path, line ->
                        nav.navigate("viewer/${encode(path)}?line=$line&session=${encode(buildSessionId.orEmpty())}&project=${encode(buildProjectId.orEmpty())}")
                    },
                    onOpenTerminal = { nav.navigate("terminal?session=${encode(buildSessionId.orEmpty())}&project=${encode(buildProjectId.orEmpty())}") },
                    onFixWithAgent = { prompt ->
                        if (!creatingProjectChat) scope.launch {
                            creatingProjectChat = true
                            try {
                                val sid = buildSessionId ?: buildProjectId?.let { container.sessions.createProjectSession(it) }
                                container.pendingAgentPrompt.value = prompt
                                if (sid != null) nav.navigateToConversation("chat/${encode(sid)}")
                                else openChat(null, kind = com.androidharness.app.data.ConversationKind.CODE)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { workspaceError = e.message }
                            finally { creatingProjectChat = false }
                        }
                    },
                )
            }
            // Restore back stacks saved by builds that included the removed page.
            composable("my-ai") {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    nav.navigate("settings") { popUpTo("my-ai") { inclusive = true }; launchSingleTop = true }
                }
            }
            composable("settings") {
                SettingsScreen(
                    container = container,
                    onBack = { nav.popBackStack() },
                    onOpenStats = { nav.navigate("stats") },
                    onRunSetup = { nav.navigate("setup") },
                    onOpenSkills = { nav.navigate("skills") },
                    onOpenProviders = { nav.navigate("providers") },
                )
            }
            composable("skills") {
                SkillsScreen(container = container, onBack = { nav.popBackStack() })
            }
            composable("stats") {
                com.androidharness.app.ui.stats.StatsScreen(
                    container = container,
                    onBack = { nav.popBackStack() },
                )
            }
            composable("providers") {
                ProvidersScreen(container = container, onBack = { nav.popBackStack() })
            }
            composable("setup") {
                com.androidharness.app.ui.setup.SetupScreen(
                    container = container,
                    onFinish = {
                        nav.navigate("chat?kind=${launch.kind.name}") { popUpTo("setup") { inclusive = true } }
                    },
                )
            }
        }
    }

    }

    }
    }

    androidx.activity.compose.BackHandler(enabled = !desktopNavigation && drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    com.androidharness.app.ui.common.ShizukuDisabledWarning(container.shizuku.workspaceWarning)

    // The sidebar opens the same per-chat access editor, including unsent chats.
    if (showWorkspaceSheet) {
        com.androidharness.app.ui.common.ShizukuWorkspaceWarningEffect(container.shizuku)
        if (chatWorkspaceContext) com.androidharness.app.ui.chat.components.ChatWorkspaceManagerSheet(
            container, workspaceSessionId, onDismiss = { showWorkspaceSheet = false },
            onSave = workspaceChatViewModel?.let { vm -> vm::saveWorkspaces })
        else com.androidharness.app.ui.chat.components.WorkspaceSwitcherSheet(
            projects = allWorkspaces,
            currentProjectId = currentWorkspace?.id,
            chatWorkspace = workspaceSessionId != null,
            describe = { container.workspace.describe(it) },
            onSelect = ::selectWorkspace,
            onAdd = { showAddWorkspace = true },
            onDismiss = { showWorkspaceSheet = false },
            onDelete = { project ->
                scope.launch {
                    try { container.workspace.deleteProject(project) }
                    catch (e: Exception) { workspaceError = e.message }
                }
            },
        )
    }
    workspaceError?.let { message ->
        AlertDialog(onDismissRequest = { workspaceError = null }, title = { Text(uiContext.getString(R.string.ui_workspace)) },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { workspaceError = null }) { Text(uiContext.getString(R.string.ui_ok)) } })
    }
    if (showAddWorkspace) {
        com.androidharness.app.ui.common.AddWorkspaceDialog(
            container = container,
            onSelected = ::selectWorkspace,
            onDismiss = { showAddWorkspace = false },
            onPickSaf = {
                showAddWorkspace = false
                safWorkspacePicker.launch(null)
            },
        )
    }

    // One-time "the toolchain gained new packages" notice for installs that
    // predate the addition. "Fetch now" lands on Settings and starts the
    // update so the progress card is visible; every path marks it as seen.
    if (showLatePackagesNotice) {
        AlertDialog(
            onDismissRequest = { dismissLatePackagesNotice() },
            title = { Text(uiContext.getString(R.string.ui_terminal_environment_updated)) },
            text = {
                Text(
                    uiContext.getString(R.string.ui_the_linux_toolchain_gained_new_packages, (latePackages.joinToString(", ")).toString()) +
                        uiContext.getString(R.string.ui_fetch_them_to_add_the_new_tools) +
                        uiContext.getString(R.string.ui_until_then_they_show_up_as_missing),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dismissLatePackagesNotice()
                    nav.navigate("settings")
                    scope.launch { container.linuxEnv.updateEnvironment() }
                }) { Text(uiContext.getString(R.string.ui_fetch_now)) }
            },
            dismissButton = {
                TextButton(onClick = { dismissLatePackagesNotice() }) { Text(uiContext.getString(R.string.ui_later)) }
            },
        )
    }

    // Long-press session actions.
    viewingSavedAnswer?.let { answer ->
        SavedAnswerSheet(
            answer = answer,
            chatTitle = sessions.firstOrNull { it.id == answer.sessionId }?.title.orEmpty(),
            loadText = container.sessions::savedAnswerText,
            onOpenChat = { viewingSavedAnswer = null; navigateFromSidebar { openChat(answer.sessionId, answer.messageId) } },
            onRemove = {
                viewingSavedAnswer = null
                scope.launch {
                    val removed = container.sessions.removeSavedAnswer(answer.messageId)
                    sidebarSnackbar.currentSnackbarData?.dismiss()
                    val result = sidebarSnackbar.showSnackbar(uiContext.getString(R.string.chat_answer_unsaved), actionLabel = uiContext.getString(R.string.ui_undo))
                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed && removed != null) container.sessions.restoreSavedAnswer(removed)
                }
            },
            onDismiss = { viewingSavedAnswer = null },
        )
    }

    actionsSession?.let { session ->
        val pinned = session.id in settings.pinnedSessions
        val archived = session.id in settings.archivedSessions
        AlertDialog(
            onDismissRequest = { actionsSession = null },
            title = { Text(session.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SessionAction(uiContext.getString(R.string.sidebar_select)) {
                        sidebarAction(session, SidebarChatAction.SELECT)
                        actionsSession = null
                    }
                    SessionAction(uiContext.getString(R.string.ui_rename)) {
                        renamingSession = session
                        actionsSession = null
                    }
                    SessionAction(if (pinned) uiContext.getString(R.string.ui_unpin) else uiContext.getString(R.string.ui_pin)) {
                        sidebarAction(session, SidebarChatAction.PIN)
                        actionsSession = null
                    }
                    SessionAction(if (archived) uiContext.getString(R.string.ui_unarchive) else uiContext.getString(R.string.ui_archive)) {
                        sidebarAction(session, SidebarChatAction.ARCHIVE)
                        actionsSession = null
                    }
                    SessionAction(uiContext.getString(R.string.ui_chat_details)) { detailsSession = session; actionsSession = null }
                    SessionAction(uiContext.getString(R.string.settings_action_delete), destructive = true) {
                        deletingSession = session
                        actionsSession = null
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionsSession = null }) { Text(uiContext.getString(R.string.settings_action_close)) }
            },
        )
    }

    val detailTargets = sessions.filter { it.id in batchDetails }
    if (detailTargets.isNotEmpty()) ChatBatchDetailsDialog(detailTargets,
        workspace = { session -> allWorkspaces.firstOrNull { it.id == session.projectId }?.name ?: uiContext.getString(R.string.sidebar_other_chats) },
        model = { session ->
            val choice = if (settings.sameModelAcrossChats) com.androidharness.app.data.ChatModelSelection.from(settings, providers).active
                else runCatching { com.androidharness.app.data.ChatModelSelection.decode(session.modelSelectionJson)?.active
                    ?: com.androidharness.app.data.ChatModelSelection.from(settings, providers).active }.getOrNull()
            choice?.model ?: uiContext.getString(R.string.sidebar_model_unavailable)
        }, onDismiss = { batchDetails = emptySet() })
    val deleteTargets = sessions.filter { it.id in batchDelete }
    if (deleteTargets.isNotEmpty()) ChatBatchDeleteDialog(deleteTargets, deleteTargets.any { it.id in runningSessionIds },
        onDismiss = { batchDelete = emptySet() }, onConfirm = {
            val ids = deleteTargets.mapTo(mutableSetOf()) { it.id }
            val remove = { scope.launch {
                batchBusy = true
                try {
                    chatSelectionMutex.withLock {
                    deleteTargets.forEach { container.runManager.stopAndJoin(it.id) }
                    container.sessions.deleteSessions(deleteTargets)
                    container.settings.forgetSidebarSessions(ids)
                    }
                    selectedChatIds = emptyList()
                    if (codeRoute?.takeIf { it.startsWith("chat/") }?.substringAfter("chat/")?.substringBefore('?') in ids) codeRoute = null
                    if (plainRoute?.takeIf { it.startsWith("chat/") }?.substringAfter("chat/")?.substringBefore('?') in ids) plainRoute = null
                    if (currentSessionId in ids) openChat(null)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    batchBusy = false
                    sidebarSnackbar.showSnackbar(uiContext.getString(R.string.sidebar_selection_error))
                } finally { batchBusy = false }
            }; Unit }
            batchDelete = emptySet()
            if (desktopNavigation && currentSessionId in ids) desktopGuard.navigate(remove) else remove()
        })
    deletingSession?.let { session ->
        AlertDialog(onDismissRequest = { deletingSession = null }, title = { Text(uiContext.getString(R.string.ui_delete_chat)) },
            text = { Text(uiContext.getString(R.string.ui_delete_and_its_conversation_history, (session.title).toString())) },
            confirmButton = { TextButton(onClick = {
                val remove = { scope.launch {
                    container.runManager.stopAndJoin(session.id)
                    container.sessions.deleteSession(session)
                    if (session.id == currentSessionId) openChat(null)
                    else if (session.id == settings.lastActiveSessionId) container.settings.setLastActiveSessionId(null)
                }; Unit }
                deletingSession = null
                if (desktopNavigation && session.id == currentSessionId) desktopGuard.navigate(remove) else remove()
            }) { Text(uiContext.getString(R.string.ui_delete_chat_1705), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deletingSession = null }) { Text(uiContext.getString(R.string.ui_keep_chat)) } })
    }
    detailsSession?.let { session ->
        val choice = if (settings.sameModelAcrossChats) com.androidharness.app.data.ChatModelSelection.from(settings, providers).active
            else runCatching { com.androidharness.app.data.ChatModelSelection.decode(session.modelSelectionJson)?.active
                ?: com.androidharness.app.data.ChatModelSelection.from(settings, providers).active }.getOrNull()
        AlertDialog(onDismissRequest = { detailsSession = null }, title = { Text(uiContext.getString(R.string.ui_chat_details)) },
            text = { Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(session.title, style = MaterialTheme.typography.titleSmall)
                Text(uiContext.getString(R.string.ui_workspace_1707, (allWorkspaces.firstOrNull { it.id == session.projectId }?.name ?: "Other chats").toString()))
                Text(uiContext.getString(R.string.ui_model_1708, (choice?.model ?: "Unavailable").toString()))
                Text(uiContext.getString(R.string.ui_tokens_used, (formatTokens(session.totalInputTokens + session.totalOutputTokens)).toString()))
                Text(uiContext.getString(R.string.ui_updated_1710, (formatRelativeTime(session.updatedAt)).toString()))
            } }, confirmButton = { TextButton(onClick = { detailsSession = null }) { Text(uiContext.getString(R.string.settings_action_close)) } })
    }

    deletingProject?.let { project ->
        com.androidharness.app.ui.projects.ProjectDeletionDialog(container, project,
            onDismiss = { deletingProject = null }, onDeleted = { deletedChats ->
                deletingProject = null
                scope.launch { drawerState.close() }
                if (draftSessionId in deletedChats) draftSessionId = null
                nav.navigate("projects") { popUpTo(nav.graph.id); launchSingleTop = true }
            })
    }

    renamingProject?.let { project ->
        AlertDialog(onDismissRequest = { renamingProject = null }, title = { Text(uiContext.getString(R.string.ui_rename_project)) },
            text = { OutlinedTextField(projectName, { projectName = it }, singleLine = true, label = { Text(uiContext.getString(R.string.ui_project_name)) }) },
            confirmButton = { TextButton(enabled = projectName.isNotBlank(), onClick = { scope.launch {
                try { container.workspace.renameProject(project.id, projectName); renamingProject = null }
                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; workspaceError = e.message }
            } }) { Text(uiContext.getString(R.string.settings_action_save)) } },
            dismissButton = { TextButton(onClick = { renamingProject = null }) { Text(uiContext.getString(R.string.settings_action_cancel)) } })
    }

    renamingSession?.let { session ->
        var title by remember(session) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renamingSession = null },
            title = { Text(uiContext.getString(R.string.ui_rename_chat)) },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank()) {
                        scope.launch { container.sessions.renameSession(session.id, title.trim()) }
                    }
                    renamingSession = null
                }) { Text(uiContext.getString(R.string.settings_action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renamingSession = null }) { Text(uiContext.getString(R.string.settings_action_cancel)) }
            },
        )
    }
}

@Composable
internal fun MessageHitRow(
    hit: MessageHit,
    query: String,
    fuzzy: Boolean,
    selectionChecked: Boolean? = null,
    onClick: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val annotated = run {
        val snippet = ChatSearch.snippet(hit.text, ChatSearch.highlightNeedles(query, fuzzy))
        buildAnnotatedString {
            append(snippet.text)
            for (r in snippet.ranges) {
                addStyle(
                    SpanStyle(color = scheme.primary, fontWeight = FontWeight.SemiBold),
                    r.first,
                    r.last + 1,
                )
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectionChecked != null) androidx.compose.material3.Checkbox(selectionChecked, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp).size(24.dp))
            Text(
                hit.sessionTitle.ifBlank { uiContext.getString(R.string.ui_chat) },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatRelativeTime(hit.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Text(
            annotated,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun SessionAction(
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            color = if (destructive) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun encode(value: String): String = android.net.Uri.encode(value)

/** Conversations are peer destinations; Back must not replay previously selected chats. */
private fun androidx.navigation.NavHostController.navigateToConversation(route: String) {
    navigate(route) {
        popUpTo(graph.id)
        launchSingleTop = true
    }
}
