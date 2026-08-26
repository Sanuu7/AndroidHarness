package com.androidharness.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.androidharness.app.AppContainer
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.ui.chat.ChatScreen
import com.androidharness.app.ui.chat.ChatViewModel
import com.androidharness.app.ui.common.HarnessMark
import com.androidharness.app.ui.common.formatRelativeTime
import com.androidharness.app.ui.files.CodeViewerScreen
import com.androidharness.app.ui.files.FilesScreen
import com.androidharness.app.ui.settings.ProvidersScreen
import com.androidharness.app.ui.settings.SettingsScreen
import com.androidharness.app.ui.settings.SkillsScreen
import com.androidharness.app.ui.setup.SetupScreen
import com.androidharness.app.ui.terminal.TerminalScreen
import com.androidharness.app.ui.theme.fastEffectsSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Calendar

private enum class SessionGroup(val label: String) {
    PINNED("Pinned"),
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This week"),
    OLDER("Older"),
    ARCHIVED("Archived"),
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppNav(container: AppContainer) {
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
    val currentWorkspace by container.workspace.currentProject.collectAsStateWithLifecycle(initialValue = null)
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
    val startDestination = remember { if (needsSetup) "setup" else "chat" }

    // Keyboard belongs to manual taps only. Two mechanisms fought this:
    // (1) the composer's focus survives the drawer opening, and (2) the
    // drawer's own accessibility focus pass can land ON the search field —
    // that one arrives a frame AFTER the open event, so clearing immediately
    // loses the race. Clear on both edges, once more after the settle, and
    // hide the IME outright.
    androidx.compose.runtime.LaunchedEffect(drawerState.currentValue) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        if (drawerState.currentValue == DrawerValue.Open) {
            delay(120)
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    // Which session the current back stack shows, for drawer highlighting.
    val currentEntry by nav.currentBackStackEntryFlow.collectAsStateWithLifecycle(initialValue = null)
    val currentSessionId = currentEntry
        ?.takeIf { it.destination.route == "chat/{sessionId}" }
        ?.arguments?.getString("sessionId")

    var searchQuery by remember { mutableStateOf("") }
    var actionsSession by remember { mutableStateOf<SessionEntity?>(null) }
    var renamingSession by remember { mutableStateOf<SessionEntity?>(null) }
    var collapsedGroups by remember { mutableStateOf(setOf(SessionGroup.OLDER, SessionGroup.ARCHIVED)) }
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showAddWorkspace by remember { mutableStateOf(false) }

    // System folder picker for adding a SAF workspace from the drawer.
    val safWorkspacePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { scope.launch { container.workspace.addPickedFolder(it) } } }

    // Run-result notifications deep-link into the session's chat.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        container.pendingSessionId.collect { sid -> nav.navigate("chat/$sid") }
    }

    fun openChat(sessionId: String?) {
        scope.launch { drawerState.close() }
        if (sessionId == null) {
            nav.navigate("chat") { popUpTo("chat") { inclusive = true } }
        } else {
            nav.navigate("chat/$sessionId")
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxSize()) {
                    // ----- Wordmark header -----
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 24.dp, end = 28.dp, top = 24.dp, bottom = 16.dp),
                    ) {
                        HarnessMark(size = 40.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                "AndroidHarness",
                                style = MaterialTheme.typography.titleMediumEmphasized,
                            )
                            Text(
                                "Workspace agent",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // ----- New chat -----
                    Button(
                        onClick = { openChat(null) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New chat", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(8.dp))

                    // ----- Workspace switcher -----
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .combinedClickable(onClick = { showWorkspaceSheet = true })
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                currentWorkspace?.name ?: "Workspace",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                currentWorkspace?.let { container.workspace.describe(it).kindLabel }
                                    ?: "Choose workspace",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Switch workspace",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))

                    // ----- Search -----
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search chats") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(6.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        val filtered = sessions.filter {
                            it.title.contains(searchQuery, ignoreCase = true)
                        }
                        val grouped = filtered.groupBy { sessionGroup(it, settings) }
                        val order = SessionGroup.entries
                        order.forEach { group ->
                            val items = grouped[group].orEmpty()
                            if (items.isEmpty()) return@forEach
                            val collapsed = group in collapsedGroups
                            stickyHeader {
                                DrawerGroupHeader(
                                    label = group.label,
                                    collapsible = group == SessionGroup.OLDER || group == SessionGroup.ARCHIVED,
                                    collapsed = collapsed,
                                    onClick = {
                                        collapsedGroups = if (collapsed) collapsedGroups - group
                                        else collapsedGroups + group
                                    },
                                )
                            }
                            if (!collapsed) {
                                items(items, key = { it.id }) { session ->
                                    SessionRow(
                                        session = session,
                                        selected = session.id == currentSessionId,
                                        pinned = session.id in settings.pinnedSessions,
                                        onClick = { openChat(session.id) },
                                        onLongClick = { actionsSession = session },
                                    )
                                }
                            }
                        }

                        if (filtered.isEmpty()) {
                            item {
                                Text(
                                    if (searchQuery.isBlank()) "No chats yet." else "No chats match \"$searchQuery\".",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(20.dp),
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    DrawerRow(
                        icon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                        title = "Terminal",
                        subtitle = "Shell in this workspace",
                        onClick = {
                            scope.launch { drawerState.close() }
                            nav.navigate("terminal")
                        },
                    )
                    DrawerRow(
                        icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        title = "Workspace files",
                        subtitle = "Browse and open project files",
                        onClick = {
                            scope.launch { drawerState.close() }
                            nav.navigate("files")
                        },
                    )
                    DrawerRow(
                        icon = { Icon(Icons.Outlined.Hub, contentDescription = null) },
                        title = "Providers",
                        subtitle = run {
                            val active = providers.firstOrNull { it.id == settings.activeProviderId }
                            if (active == null) "Add a provider to get started"
                            else "${active.name} · ${settings.activeModel?.takeIf { it.isNotBlank() } ?: active.model}"
                        },
                        onClick = {
                            scope.launch { drawerState.close() }
                            nav.navigate("providers")
                        },
                    )
                    DrawerRow(
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        title = "Settings",
                        subtitle = "Agent, workspace, environment, appearance",
                        onClick = {
                            scope.launch { drawerState.close() }
                            nav.navigate("settings")
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        },
    ) {
        NavHost(
            navController = nav,
            startDestination = startDestination,
            // Quiet transitions: a short fade with a small rise. No shared-element
            // theatrics — screens should feel instant.
            enterTransition = {
                fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 28 }
            },
            exitTransition = { fadeOut(tween(180, easing = FastOutSlowInEasing)) },
            popEnterTransition = { fadeIn(tween(220, easing = FastOutSlowInEasing)) },
            popExitTransition = {
                fadeOut(tween(180, easing = FastOutSlowInEasing)) +
                    slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it / 28 }
            },
        ) {
            composable("chat") {
                val vm: ChatViewModel = viewModel(factory = ChatViewModel.factory(container, null))
                ChatScreen(
                    viewModel = vm,
                    onOpenDrawer = {
                        focusManager.clearFocus(force = true)
                        scope.launch { drawerState.open() }
                    },
                    onOpenFile = { path, line ->
                        nav.navigate("viewer/${encode(path)}?line=${line ?: 0}")
                    },
                    onNewChat = { nav.navigate("chat") { popUpTo("chat") { inclusive = true } } },
                    onOpenTerminal = { nav.navigate("terminal") },
                    onOpenSubagent = { callId ->
                        vm.state.value.sessionId?.let { sid ->
                            nav.navigate("subagent/${encode(sid)}/${encode(callId)}")
                        }
                    },
                )
            }
            composable(
                "chat/{sessionId}",
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { entry ->
                val sessionId = entry.arguments?.getString("sessionId")
                val vm: ChatViewModel =
                    viewModel(factory = ChatViewModel.factory(container, sessionId))
                ChatScreen(
                    viewModel = vm,
                    onOpenDrawer = {
                        focusManager.clearFocus(force = true)
                        scope.launch { drawerState.open() }
                    },
                    onOpenFile = { path, line ->
                        nav.navigate("viewer/${encode(path)}?line=${line ?: 0}")
                    },
                    onNewChat = { nav.navigate("chat") { popUpTo("chat") { inclusive = true } } },
                    onOpenTerminal = { nav.navigate("terminal") },
                    onOpenSubagent = { callId ->
                        vm.state.value.sessionId?.let { sid ->
                            nav.navigate("subagent/${encode(sid)}/${encode(callId)}")
                        }
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
            composable("terminal") {
                TerminalScreen(container = container, onBack = { nav.popBackStack() })
            }
            composable("files") {
                FilesScreen(
                    container = container,
                    onBack = { nav.popBackStack() },
                    onOpenFile = { path -> nav.navigate("viewer/${encode(path)}") },
                )
            }
            composable(
                "viewer/{path}?line={line}",
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType },
                    navArgument("line") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) { entry ->
                val path = entry.arguments?.getString("path").orEmpty()
                val line = entry.arguments?.getInt("line")?.takeIf { it > 0 }
                CodeViewerScreen(
                    container = container,
                    path = path,
                    initialLine = line,
                    onBack = { nav.popBackStack() },
                )
            }
            composable("settings") {
                SettingsScreen(
                    container = container,
                    onBack = { nav.popBackStack() },
                    onOpenStats = { nav.navigate("stats") },
                    onRunSetup = { nav.navigate("setup") },
                    onOpenSkills = { nav.navigate("skills") },
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
                        nav.navigate("chat") { popUpTo("setup") { inclusive = true } }
                    },
                )
            }
        }
    }

    // Workspace switching from the drawer + chat overflow shares one sheet.
    if (showWorkspaceSheet) {
        com.androidharness.app.ui.chat.components.WorkspaceSwitcherSheet(
            projects = allWorkspaces,
            currentProjectId = currentWorkspace?.id,
            describe = { container.workspace.describe(it) },
            onSelect = { id -> scope.launch { container.workspace.setActiveProject(id) } },
            onAdd = { showAddWorkspace = true },
            onDismiss = { showWorkspaceSheet = false },
            onDelete = { project ->
                scope.launch { container.workspace.deleteProject(project) }
            },
        )
    }
    if (showAddWorkspace) {
        com.androidharness.app.ui.common.AddWorkspaceDialog(
            container = container,
            onDismiss = { showAddWorkspace = false },
            onPickSaf = {
                showAddWorkspace = false
                safWorkspacePicker.launch(null)
            },
        )
    }

    // Long-press session actions.
    actionsSession?.let { session ->
        val pinned = session.id in settings.pinnedSessions
        val archived = session.id in settings.archivedSessions
        AlertDialog(
            onDismissRequest = { actionsSession = null },
            title = { Text(session.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SessionAction("Rename") {
                        renamingSession = session
                        actionsSession = null
                    }
                    SessionAction(if (pinned) "Unpin" else "Pin") {
                        scope.launch { container.settings.setPinned(session.id, !pinned) }
                        actionsSession = null
                    }
                    SessionAction(if (archived) "Unarchive" else "Archive") {
                        scope.launch { container.settings.setArchived(session.id, !archived) }
                        actionsSession = null
                    }
                    SessionAction("Delete", destructive = true) {
                        scope.launch { container.sessions.deleteSession(session) }
                        actionsSession = null
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionsSession = null }) { Text("Close") }
            },
        )
    }

    renamingSession?.let { session ->
        var title by remember(session) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renamingSession = null },
            title = { Text("Rename chat") },
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
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renamingSession = null }) { Text("Cancel") }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Drawer building blocks

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerGroupHeader(
    label: String,
    collapsible: Boolean,
    collapsed: Boolean,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        animationSpec = fastEffectsSpec(),
        label = "group chevron",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .padding(start = 24.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (collapsible) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (collapsed) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(rotation),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionEntity,
    selected: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) scheme.surfaceContainerHigh else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        if (pinned) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = "Pinned",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                session.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
            )
            Text(
                buildString {
                    append(formatRelativeTime(session.updatedAt))
                    if (session.totalInputTokens > 0) {
                        append(" · ")
                        append((session.totalInputTokens + session.totalOutputTokens) / 1000)
                        append("k tokens")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Current chat",
                tint = scheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            icon()
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun sessionGroup(session: SessionEntity, settings: AppSettings): SessionGroup {
    if (session.id in settings.pinnedSessions) return SessionGroup.PINNED
    if (session.id in settings.archivedSessions) return SessionGroup.ARCHIVED

    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val todayStart = cal.timeInMillis
    val yesterdayStart = todayStart - 24L * 60 * 60 * 1000
    cal.add(Calendar.DAY_OF_YEAR, -(cal.get(Calendar.DAY_OF_WEEK) - cal.firstDayOfWeek))
    val weekStart = cal.timeInMillis

    return when {
        session.updatedAt >= todayStart -> SessionGroup.TODAY
        session.updatedAt >= yesterdayStart -> SessionGroup.YESTERDAY
        session.updatedAt >= weekStart -> SessionGroup.THIS_WEEK
        else -> SessionGroup.OLDER
    }
}

private fun encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
