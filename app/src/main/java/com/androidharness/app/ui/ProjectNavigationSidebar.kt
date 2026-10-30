package com.androidharness.app.ui

import com.androidharness.app.ui.theme.*
import com.androidharness.app.ui.theme.HarnessGlassSurface as Surface
import com.androidharness.app.ui.common.ExpressiveDropdownMenu as DropdownMenu
import com.androidharness.app.ui.common.ExpressiveDropdownMenuItem as DropdownMenuItem

import com.androidharness.app.R
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import com.androidharness.app.ui.common.ExpressiveIconButton
import com.androidharness.app.ui.common.expressivePressFeedback
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.RunManager
import com.androidharness.app.data.MessageHit
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.ui.common.HarnessMark
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.common.formatRelativeTime
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import com.androidharness.app.data.db.SavedAnswerRow
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.Checkbox
import com.androidharness.app.ui.common.TextButton

internal enum class SidebarProjectAction { OPEN, RENAME, PIN, ARCHIVE, DELETE }
internal enum class SidebarChatAction { SELECT, RENAME, PIN, ARCHIVE, DETAILS, DELETE }
internal enum class SidebarActivity { WORKING, NEEDS_YOU }

internal fun sidebarActivity(running: Boolean, pendingPlan: String?, live: RunManager.LiveRunState? = null): SidebarActivity? = when {
    !pendingPlan.isNullOrBlank() || live?.pendingApproval != null || live?.pendingQuestion != null ||
        live?.pendingEnvironment != null || !live?.pendingPlan.isNullOrBlank() -> SidebarActivity.NEEDS_YOU
    running || live?.running == true -> SidebarActivity.WORKING
    else -> null
}

/** Pinned and archived rows appear once; legacy chats keep a reachable home. */
internal data class SidebarChats(
    val pinned: List<SessionEntity>, val projects: Map<String, List<SessionEntity>>,
    val other: List<SessionEntity>, val archived: List<SessionEntity>,
) {
    companion object {
        fun of(projects: List<ProjectEntity>, sessions: List<SessionEntity>, pinned: Set<String>, archived: Set<String>): SidebarChats {
            val known = projects.mapTo(mutableSetOf()) { it.id }
            val active = sessions.filterNot { it.id in archived }
            val rest = active.filterNot { it.id in pinned }
            return SidebarChats(active.filter { it.id in pinned }, rest.filter { it.projectId in known }.groupBy { it.projectId!! },
                rest.filter { it.projectId !in known }, sessions.filter { it.id in archived })
        }
    }
}

/** Separate conversation and project views, shared by phone and desktop. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProjectNavigationSidebar(
    projects: List<ProjectEntity>, sessions: List<SessionEntity>, selectedSession: String?, selectedProject: String?,
    pinned: Set<String>, archived: Set<String>, query: String, onQueryChange: (String) -> Unit,
    messageHits: List<MessageHit>, fuzzy: Boolean, onFuzzyChange: () -> Unit,
    activity: @Composable (SessionEntity) -> SidebarActivity?,
    onNewChat: () -> Unit, onChat: (String) -> Unit, onMessage: (MessageHit) -> Unit,
    onProjectChat: (ProjectEntity) -> Unit, onProjects: () -> Unit,
    onChatActions: (SessionEntity) -> Unit, onAction: (SessionEntity, SidebarChatAction) -> Unit,
    onBuild: () -> Unit, onTerminal: () -> Unit, onAutomation: () -> Unit, onProviders: () -> Unit, onSettings: () -> Unit,
    workspaceLabel: String, workspaceSummary: String, onWorkspaces: () -> Unit,
    onCollapse: () -> Unit, currentRoute: String?, projectCreationEnabled: Boolean = true,
    changesSummary: String? = null, onChanges: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    projectsView: Boolean = false, onProjectsViewChange: (Boolean) -> Unit = {},
    projectPins: Set<String> = emptySet(), projectArchives: Set<String> = emptySet(),
    onProjectAction: (ProjectEntity, SidebarProjectAction) -> Unit = { _, _ -> },
    chatOnly: Boolean = false,
    sectionSwitchEnabled: Boolean = true,
    onSelectSection: (com.androidharness.app.data.ConversationKind) -> Unit = {},
    selectedChats: Set<String> = emptySet(),
    onSelectedChatsChange: (Set<String>) -> Unit = {},
    onSelectedChatsAction: (Set<String>, SidebarChatAction) -> Unit = { _, _ -> },
    selectionBusy: Boolean = false,
    savedAnswers: List<SavedAnswerRow> = emptyList(),
    onOpenSavedAnswer: (SavedAnswerRow) -> Unit = {},
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val glass = LocalHarnessGlass.current.mode != HarnessGlassMode.OFF
    var expandedProjects by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var showSaved by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(projectsView, chatOnly) { showArchived = false; showSaved = false }
    // The Saved button only exists while something is saved, so leave its list rather than strand an empty one.
    LaunchedEffect(savedAnswers.isEmpty()) { if (savedAnswers.isEmpty()) showSaved = false }
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    var footerHeight by remember { mutableStateOf(0.dp) }
    val currentProject = sessions.firstOrNull { it.id == selectedSession }?.projectId ?: selectedProject
    val expanded = expandedProjects
    val grouped = remember(projects, sessions, pinned, archived) { SidebarChats.of(projects, sessions, pinned, archived) }
    val matching = remember(sessions, projects, query) {
        val names = projects.associate { it.id to it.name }
        sessions.filter { it.title.contains(query.trim(), true) || names[it.projectId]?.contains(query.trim(), true) == true }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val selection = selectedChats intersect sessions.mapTo(mutableSetOf()) { it.id }
    val selecting = selection.isNotEmpty() || selectionBusy
    val selectableIds = when {
        query.isNotBlank() -> (matching.map { it.id } + messageHits.map { it.sessionId }).toSet() intersect sessions.mapTo(mutableSetOf()) { it.id }
        showSaved -> emptySet()
        projectsView -> sessions.filter { it.id !in archived && it.projectId in expandedProjects && projects.any { p -> p.id == it.projectId && (p.id in projectArchives) == showArchived } }.mapTo(mutableSetOf()) { it.id }
        showArchived -> grouped.archived.mapTo(mutableSetOf()) { it.id }
        else -> sessions.filter { it.id !in archived }.mapTo(mutableSetOf()) { it.id }
    }
    fun toggle(id: String) { if (!selectionBusy) onSelectedChatsChange(if (id in selection) selection - id else selection + id) }
    androidx.activity.compose.BackHandler(selecting && !selectionBusy) { onSelectedChatsChange(emptySet()) }
    @Composable fun Chat(chat: SessionEntity, indented: Boolean = false, showProject: Boolean = false) {
        SidebarChatRow(chat, chat.id == selectedSession, chat.id in pinned, chat.id in archived, activity(chat),
            projectName = if (showProject) projects.firstOrNull { it.id == chat.projectId }?.name else null,
            indented = indented, onClick = { keyboard?.hide(); if (selecting) toggle(chat.id) else onChat(chat.id) },
            onActions = { if (selecting) toggle(chat.id) else onChatActions(chat) },
            onAction = { if (it == SidebarChatAction.SELECT) onSelectedChatsChange(selection + chat.id) else onAction(chat, it) },
            selecting = selecting, checked = chat.id in selection)
        if (glass) HorizontalDivider(Modifier.padding(start = if (indented) 40.dp else 20.dp, end = 20.dp),
            color = scheme.onSurface.copy(alpha = .16f))
    }
    @Composable fun Footer() {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
        Column(Modifier.fillMaxWidth().onSizeChanged { footerHeight = with(density) { it.height.toDp() } }
            .padding(horizontal = if (glass) 14.dp else 10.dp, vertical = if (glass) 12.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (glass) 8.dp else 0.dp)) {
            if (!chatOnly) SidebarNavRow(uiContext.getString(R.string.ui_tools), Icons.Outlined.Widgets, expanded = toolsOpen, onClick = { toolsOpen = !toolsOpen })
            if (toolsOpen && !chatOnly) Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(if (glass) 8.dp else 0.dp)) {
                SidebarNavRow("Terminal", Icons.Outlined.Terminal, currentRoute?.startsWith("terminal") == true, onClick = onTerminal)
                SidebarNavRow("Build & test", Icons.Outlined.Build, currentRoute?.startsWith("build-test") == true, onClick = onBuild)
                SidebarNavRow(uiContext.getString(R.string.ui_scheduled_tasks), Icons.Outlined.Schedule, currentRoute == "automation", onClick = onAutomation)
                SidebarNavRow(workspaceLabel, Icons.Outlined.FolderOpen, subtitle = workspaceSummary, onClick = onWorkspaces)
                changesSummary?.let { SidebarNavRow(it, Icons.Outlined.Difference, onClick = onChanges) }
            }
            SidebarNavRow(uiContext.getString(R.string.settings_providers_title), Icons.Outlined.Hub, currentRoute == "providers", onClick = onProviders)
            SidebarNavRow(uiContext.getString(R.string.settings_page_title), Icons.Outlined.Settings, currentRoute == "settings", onClick = onSettings)
        }
    }
    // The drawer's opening focus request should land here, not in chat search.
    val panelBackdrop = if (glass) com.kyant.backdrop.backdrops.rememberLayerBackdrop() else null
    Surface(color = scheme.surface,
        contentColor = scheme.onSurface,
        centerOpacityFloor = if (glass) .48f else 0f, exportedBackdrop = panelBackdrop,
        shape = if (glass) RoundedCornerShape(32.dp) else androidx.compose.ui.graphics.RectangleShape,
        modifier = Modifier.fillMaxSize()
            .focusable().semantics { contentDescription = uiContext.getString(R.string.ui_project_and_chat_sidebar) }) {
        CompositionLocalProvider(LocalHarnessGlassBackdrop provides (panelBackdrop ?: LocalHarnessGlassBackdrop.current)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val short = maxHeight < 440.dp
            val narrow = maxWidth < 280.dp
            val stackTabs = maxWidth < 180.dp * density.fontScale
            @Composable fun Header() {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
                Column {
                    if (selecting) ChatSelectionToolbar(if (selectionBusy) selectedChats.size else selection.size, selectableIds.isNotEmpty() && selection.containsAll(selectableIds),
                        selection.all { it in archived }, selectionBusy,
                        onClose = { onSelectedChatsChange(emptySet()) },
                        onSelectAll = { onSelectedChatsChange(if (selection.containsAll(selectableIds)) emptySet() else selection + selectableIds) },
                        onAction = { onSelectedChatsAction(selection, it) })
                    if (!selecting) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 18.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        HarnessMark(26.dp, Modifier.semantics { contentDescription = uiContext.getString(R.string.ui_androidharness_icon) })
                        Text("AndroidHarness", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ExpressiveIconButton(onClick = onCollapse) { Icon(Icons.AutoMirrored.Outlined.MenuOpen, uiContext.getString(R.string.ui_collapse_sidebar), Modifier.size(19.dp)) }
                    }
                    com.androidharness.app.ui.chat.components.ConversationSectionSwitcher(
                        com.androidharness.app.data.ConversationConfig(
                            if (chatOnly) com.androidharness.app.data.ConversationKind.CHAT else com.androidharness.app.data.ConversationKind.CODE),
                        sectionSwitchEnabled, onSelectSection)
                    Button(onClick = onNewChat, enabled = projectCreationEnabled,
                        colors = if (glass) ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = scheme.onPrimaryContainer) else ButtonDefaults.buttonColors(), shape = RoundedCornerShape(if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) 28.dp else 11.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).heightIn(min = 44.dp)
                            .then(if (glass) Modifier.harnessGlass(scheme.primaryContainer, RoundedCornerShape(28.dp)) else Modifier)) {
                        Icon(Icons.Outlined.EditNote, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp))
                        Text(uiContext.getString(if (chatOnly) R.string.settings_telegram_new_chat else R.string.conversation_new_code_chat), style = MaterialTheme.typography.labelLarge)
                    }
                    }
                    if (!selecting || !short) {
                    BasicTextField(query, onQueryChange, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                            .semantics { contentDescription = uiContext.getString(R.string.ui_search_chats_and_messages) },
                        decorationBox = { field ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).harnessGlass(scheme.surfaceContainerHigh, RoundedCornerShape(if (glass) 24.dp else 10.dp))
                                .padding(start = 12.dp, end = if (query.isBlank() && !chatOnly) 12.dp else 0.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.weight(1f)) {
                                    if (query.isEmpty()) Text(if (narrow) uiContext.getString(R.string.ui_search_chats) else uiContext.getString(R.string.ui_search_chats_and_messages),
                                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
                                    field()
                                }
                                if (query.isNotEmpty()) ExpressiveIconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Outlined.Close, uiContext.getString(R.string.settings_search_clear), Modifier.size(17.dp))
                                }
                            }
                        })
                    if (chatOnly) SidebarCollectionButtons(
                        savedCount = savedAnswers.size, showSaved = showSaved, showArchived = showArchived,
                        onToggleSaved = { onQueryChange(""); showArchived = false; showSaved = !showSaved },
                        onToggleArchived = { onQueryChange(""); showSaved = false; showArchived = !showArchived },
                    )
                    if (!chatOnly) Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        FlowRow(Modifier.weight(1f).harnessGlass(scheme.surfaceContainer, RoundedCornerShape(if (glass) 24.dp else 12.dp)).padding(3.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            maxItemsInEachRow = if (stackTabs) 1 else 2) {
                            listOf(false to uiContext.getString(R.string.ui_chats), true to uiContext.getString(R.string.ui_projects)).forEach { (projectTab, label) ->
                                val selectedTab = projectsView == projectTab
                                val tabColor by animateColorAsState(
                                    if (selectedTab) scheme.secondaryContainer else scheme.surfaceContainer,
                                    animationSpec = expressiveEffectsSpec(), label = "sidebar tab color",
                                )
                                val tabCorner by animateDpAsState(
                                    if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) 24.dp else if (selectedTab) 18.dp else 9.dp,
                                    animationSpec = expressiveSpatialSpec(), label = "sidebar tab shape",
                                )
                                Surface(onClick = { onProjectsViewChange(projectTab) }, shape = RoundedCornerShape(tabCorner),
                                    color = tabColor,
                                    modifier = Modifier.weight(1f).semantics { role = Role.Tab; selected = selectedTab }) {
                                    Box(Modifier.heightIn(min = 42.dp).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
                                        Text(label, style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            color = if (selectedTab) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        ExpressiveIconButton(onClick = { onQueryChange(""); showArchived = !showArchived }) {
                            Icon(if (showArchived) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Archive,
                                if (showArchived) uiContext.getString(R.string.ui_back_to_active, (if (projectsView) "projects" else "chats").toString()) else uiContext.getString(R.string.ui_show_archived, (if (projectsView) "projects" else "chats").toString()),
                                Modifier.size(19.dp), tint = if (showArchived) scheme.primary else scheme.onSurfaceVariant)
                        }
                    }
                    if (query.isNotBlank()) TextButton(onClick = onFuzzyChange,
                        modifier = Modifier.padding(horizontal = 14.dp).semantics { stateDescription = if (fuzzy) uiContext.getString(R.string.ui_contains_text) else uiContext.getString(R.string.ui_match_words) }) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(15.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (fuzzy) uiContext.getString(R.string.ui_contains_text) else uiContext.getString(R.string.ui_match_words), style = MaterialTheme.typography.labelSmall)
                    }
                    }
                }
            }
            Column(Modifier.fillMaxSize()) {
                if (!short || selecting) Header()
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 2.dp, bottom = 10.dp)) {
                    if (short && !selecting) item(key = "sidebar-header") { Header() }
                    if (query.isNotBlank()) {
                        if (matching.isNotEmpty()) {
                            item { SidebarSectionHeader(uiContext.getString(R.string.ui_chats), true, null) }
                            items(matching, key = { "search:${it.id}" }) { Chat(it, showProject = true) }
                        }
                        if (messageHits.isNotEmpty()) {
                            item { SidebarSectionHeader(uiContext.getString(R.string.ui_messages), true, null) }
                            items(messageHits, key = { "message:${it.messageId}" }) { hit ->
                                MessageHitRow(hit, query, fuzzy, selectionChecked = if (selecting) hit.sessionId in selection else null) {
                                    keyboard?.hide(); if (selecting) toggle(hit.sessionId) else onMessage(hit)
                                }
                            }
                        }
                        if (matching.isEmpty() && messageHits.isEmpty()) item {
                            Text(uiContext.getString(R.string.ui_no_chats_found_try_another_title_or), Modifier.padding(20.dp),
                                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    } else if (projectsView) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (showArchived) uiContext.getString(R.string.ui_archived_projects) else uiContext.getString(R.string.ui_your_projects), Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                                TextButton(onClick = onProjects) { Text(uiContext.getString(R.string.ui_manage_projects), style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                        val visibleProjects = projects.filter { (it.id in projectArchives) == showArchived }
                            .sortedBy { if (it.id in projectPins) 0 else 1 }
                        visibleProjects.forEachIndexed { index, project ->
                            val chats = sessions.filter { it.projectId == project.id && it.id !in archived }
                                .sortedBy { if (it.id in pinned) 0 else 1 }
                            if (!showArchived && project.id in projectPins && index == 0) item { SidebarSectionHeader(uiContext.getString(R.string.ui_pinned), true, null) }
                            if (!showArchived && project.id !in projectPins && index > 0 && visibleProjects[index - 1].id in projectPins)
                                item { SidebarSectionHeader(uiContext.getString(R.string.ui_all_projects), true, null) }
                            item(key = "project:${project.id}") {
                                SidebarProjectRow(project, project.id in expanded, project.id in projectPins, project.id in projectArchives,
                                    project.id == currentProject, chats.size,
                                    onExpand = { expandedProjects = if (project.id in expanded) expanded - project.id else expanded + project.id },
                                    onNewChat = { onProjectChat(project) }, creationEnabled = projectCreationEnabled,
                                    onAction = { onProjectAction(project, it) })
                            }
                            if (project.id in expanded) {
                                items(chats, key = { "project-chat:${it.id}" }) { Chat(it, indented = true) }
                                if (chats.isEmpty()) item(key = "empty:${project.id}") {
                                    Text(uiContext.getString(R.string.ui_no_chats_yet), Modifier.padding(start = 54.dp, top = 4.dp, bottom = 12.dp),
                                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (visibleProjects.isEmpty()) item {
                            Text(if (showArchived) uiContext.getString(R.string.ui_no_archived_projects) else uiContext.getString(R.string.ui_add_a_project_to_organize_its_chats),
                                Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    } else if (showSaved) {
                        item { SidebarSectionHeader(uiContext.getString(R.string.sidebar_saved_answers), true, null) }
                        items(savedAnswers, key = { "saved:${it.messageId}" }) { answer ->
                            SavedAnswerListRow(
                                answer, sessions.firstOrNull { it.id == answer.sessionId }?.title.orEmpty(),
                                Modifier.animateItem(fadeInSpec = expressiveEffectsSpec(), placementSpec = expressiveSpatialSpec(), fadeOutSpec = expressiveEffectsSpec()),
                            ) { keyboard?.hide(); onOpenSavedAnswer(answer) }
                        }
                    } else if (showArchived) {
                        item { SidebarSectionHeader(uiContext.getString(R.string.ui_archived_chats), true, null) }
                        items(grouped.archived, key = { "archive:${it.id}" }) { Chat(it, showProject = true) }
                        if (grouped.archived.isEmpty()) item {
                            Text(uiContext.getString(R.string.ui_no_archived_chats), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    } else {
                        if (grouped.pinned.isNotEmpty()) {
                            item { SidebarSectionHeader(uiContext.getString(R.string.ui_pinned), true, null) }
                            items(grouped.pinned, key = { "pinned:${it.id}" }) { Chat(it, showProject = true) }
                        }
                        val recent = sessions.filter { it.id !in pinned && it.id !in archived }
                        if (recent.isNotEmpty()) {
                            item { SidebarSectionHeader(uiContext.getString(if (chatOnly) R.string.ui_recent_chats else R.string.conversation_recent_code_chats), true, null) }
                            items(recent, key = { "chat:${it.id}" }) { Chat(it, showProject = true) }
                        }
                        if (recent.isEmpty() && grouped.pinned.isEmpty()) item {
                            Text(uiContext.getString(R.string.ui_your_conversations_will_appear_here), Modifier.padding(20.dp),
                                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    if (short) item { HorizontalDivider(color = scheme.outlineVariant); Footer() }
                }
                if (!short) { HorizontalDivider(color = scheme.outlineVariant); Footer() }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (short) 8.dp else footerHeight + 8.dp))
        }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarProjectRow(project: ProjectEntity, expanded: Boolean, pinned: Boolean, archived: Boolean,
    selected: Boolean, count: Int, onExpand: () -> Unit, onNewChat: () -> Unit, creationEnabled: Boolean,
    onAction: (SidebarProjectAction) -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val press = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
        .background(if (selected) scheme.surfaceContainer else scheme.surface, RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).expressivePressFeedback(press).clip(RoundedCornerShape(10.dp)).secondaryClick { menu = true }
            .combinedClickable(interactionSource = press, indication = ripple(color = scheme.primary),
                onClick = onExpand, onLongClick = { menu = true })
            .semantics { contentDescription = "${if (expanded) uiContext.getString(R.string.ui_collapse) else uiContext.getString(R.string.ui_expand)} ${project.name}"; stateDescription = if (expanded) uiContext.getString(R.string.ui_expanded) else uiContext.getString(R.string.ui_collapsed) }
            .heightIn(min = 48.dp).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null, Modifier.size(15.dp), tint = scheme.onSurfaceVariant)
            Box(Modifier.size(26.dp).background(scheme.secondaryContainer, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp), tint = scheme.onSecondaryContainer)
            }
            Text(project.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = if (LocalDensity.current.fontScale > 1.3f) 2 else 1, overflow = TextOverflow.Ellipsis)
            if (pinned) Icon(Icons.Outlined.PushPin, null, Modifier.size(12.dp), tint = scheme.onSurfaceVariant)
            Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        Box {
            ExpressiveIconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, uiContext.getString(R.string.ui_actions_for_project, (project.name).toString()), Modifier.size(18.dp), tint = scheme.onSurfaceVariant) }
            DropdownMenu(menu, { menu = false }) {
                if (!archived) DropdownMenuItem(text = { Text(uiContext.getString(R.string.settings_telegram_new_chat)) }, leadingIcon = { Icon(Icons.Outlined.Add, null) },
                    enabled = creationEnabled, modifier = Modifier.semantics { contentDescription = uiContext.getString(R.string.ui_new_chat_in, (project.name).toString()) },
                    onClick = { menu = false; onNewChat() })
                listOf(Triple(SidebarProjectAction.OPEN, uiContext.getString(R.string.ui_open_project), Icons.Outlined.FolderOpen),
                    Triple(SidebarProjectAction.RENAME, uiContext.getString(R.string.ui_rename_project), Icons.Outlined.Edit),
                    Triple(SidebarProjectAction.PIN, if (pinned) uiContext.getString(R.string.ui_unpin_project) else uiContext.getString(R.string.ui_pin_project), Icons.Outlined.PushPin),
                    Triple(SidebarProjectAction.ARCHIVE, if (archived) uiContext.getString(R.string.ui_restore_project) else uiContext.getString(R.string.ui_archive_project), Icons.Outlined.Archive)).forEach { (action, label, icon) ->
                    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = { menu = false; onAction(action) })
                }
                if (project.kind != com.androidharness.app.workspace.WorkspaceManager.KIND_APP) {
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(uiContext.getString(R.string.ui_delete_project), color = scheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = scheme.error) },
                        onClick = { menu = false; onAction(SidebarProjectAction.DELETE) })
                }
            }
        }
    }
}

@Composable
private fun SidebarSectionHeader(label: String, expanded: Boolean, onClick: (() -> Unit)?) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    if (LocalHarnessGlass.current.mode != HarnessGlassMode.OFF) HorizontalDivider(
        Modifier.padding(horizontal = 18.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .24f))
    val contents: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = if (onClick == null) 32.dp else 44.dp).padding(start = 20.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (label == uiContext.getString(R.string.ui_pinned)) {
                Icon(Icons.Outlined.PushPin, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onClick != null) Icon(if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (onClick == null) contents() else Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.semantics { contentDescription = uiContext.getString(R.string.ui_chats_extra87, (label).toString()); stateDescription = if (expanded) uiContext.getString(R.string.ui_expanded) else uiContext.getString(R.string.ui_collapsed) }) { contents() }
}

@Composable
private fun SidebarNavRow(label: String, icon: ImageVector, selected: Boolean = false, subtitle: String? = null,
    expanded: Boolean? = null, onClick: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val glass = LocalHarnessGlass.current.mode != HarnessGlassMode.OFF
    Surface(onClick = onClick, color = if (selected) scheme.surfaceContainerHigh else scheme.surface,
        shape = RoundedCornerShape(if (glass) 24.dp else 9.dp), modifier = Modifier.fillMaxWidth().semantics {
            this.selected = selected; expanded?.let { stateDescription = if (it) uiContext.getString(R.string.ui_expanded) else uiContext.getString(R.string.ui_collapsed) }
        }) {
        Row(Modifier.heightIn(min = if (glass) 50.dp else 44.dp).padding(horizontal = if (glass) 16.dp else 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (glass) 12.dp else 10.dp)) {
            Icon(icon, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall)
                subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (expanded != null) Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                null, Modifier.size(15.dp), tint = scheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarChatRow(chat: SessionEntity, selected: Boolean, pinned: Boolean, archived: Boolean,
    activity: SidebarActivity?, projectName: String?, indented: Boolean, onClick: () -> Unit,
    onActions: () -> Unit, onAction: (SidebarChatAction) -> Unit, selecting: Boolean = false, checked: Boolean = false) {
    val press = remember { MutableInteractionSource() }
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val statusColors = LocalStatusColors.current
    var menu by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val hasDraft = remember(chat.conversationJson) {
        com.androidharness.app.data.ConversationConfig.decode(chat.conversationJson).draftText.isNotBlank()
    }
    val rowColor by animateColorAsState(if (selecting && checked) scheme.secondaryContainer else if (selected) scheme.primary.copy(alpha = .07f)
        else if (focused) scheme.surfaceContainerHigh else scheme.surface, expressiveEffectsSpec(), label = "Chat selection")
    Row(Modifier.fillMaxWidth().padding(start = if (indented) 30.dp else 10.dp, end = 10.dp, top = 2.dp, bottom = 2.dp)) {
        if (indented) Box(Modifier.width(1.dp).height(56.dp).background(scheme.outlineVariant))
        Row(Modifier.weight(1f).padding(start = if (indented) 5.dp else 0.dp)
            .background(rowColor, RoundedCornerShape(10.dp)),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).expressivePressFeedback(press).clip(RoundedCornerShape(10.dp)).secondaryClick(onActions).pointerHoverIcon(PointerIcon.Hand)
                .onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { event ->
                    if (event.key == Key.Menu || event.key == Key.F10 && event.isShiftPressed) {
                        if (event.type == KeyEventType.KeyUp) onActions(); true
                    } else false
                }.combinedClickable(interactionSource = press, indication = ripple(color = scheme.primary), onClick = onClick, onLongClick = onActions, onLongClickLabel = uiContext.getString(R.string.ui_chat_actions))
                .semantics {
                    this.selected = if (selecting) checked else selected
                    if (selecting) { role = Role.Checkbox; toggleableState = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off }
                    contentDescription = uiContext.getString(if (selecting) R.string.sidebar_select_chat else R.string.ui_open_1656, chat.title)
                }
                .heightIn(min = 56.dp).padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selecting) Checkbox(checked, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp).size(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(chat.title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            maxLines = if (LocalDensity.current.fontScale > 1.3f) 2 else 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (hasDraft) Icon(Icons.Outlined.Edit, uiContext.getString(R.string.ui_draft), Modifier.size(12.dp), tint = scheme.primary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val color = when (activity) { SidebarActivity.WORKING -> statusColors.success; SidebarActivity.NEEDS_YOU -> statusColors.warning; null -> scheme.onSurfaceVariant }
                        if (activity == SidebarActivity.WORKING) Box(Modifier.size(6.dp).background(color, CircleShape))
                        if (activity == SidebarActivity.NEEDS_YOU) Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, Modifier.size(13.dp), tint = color)
                        Text(listOfNotNull(when (activity) { SidebarActivity.WORKING -> uiContext.getString(R.string.ui_working_1753); SidebarActivity.NEEDS_YOU -> uiContext.getString(R.string.ui_needs_you); null -> projectName },
                            if (archived) uiContext.getString(R.string.ui_archived) else null,
                            if (activity == null) formatRelativeTime(chat.updatedAt) else null).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (pinned) Icon(Icons.Outlined.PushPin, uiContext.getString(R.string.ui_pinned), Modifier.size(13.dp), tint = scheme.onSurfaceVariant)
                if (selected && !selecting) Icon(Icons.Outlined.Check, uiContext.getString(R.string.ui_current_chat), Modifier.size(15.dp), tint = scheme.primary)
            }
            if (!selecting) Box {
                ExpressiveIconButton(onClick = { menu = true }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                    Icon(Icons.Outlined.MoreHoriz, uiContext.getString(R.string.settings_provider_actions_cd, (chat.title).toString()), Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                }
                DropdownMenu(menu, { menu = false }) {
                    listOf(Triple(SidebarChatAction.SELECT, uiContext.getString(R.string.sidebar_select), Icons.Outlined.Checklist),
                        Triple(SidebarChatAction.RENAME, uiContext.getString(R.string.ui_rename), Icons.Outlined.Edit),
                        Triple(SidebarChatAction.PIN, if (pinned) uiContext.getString(R.string.ui_unpin) else uiContext.getString(R.string.ui_pin), Icons.Outlined.PushPin),
                        Triple(SidebarChatAction.DETAILS, uiContext.getString(R.string.ui_chat_details), Icons.Outlined.Info),
                        Triple(SidebarChatAction.ARCHIVE, if (archived) uiContext.getString(R.string.ui_unarchive) else uiContext.getString(R.string.ui_archive), Icons.Outlined.Archive),
                        Triple(SidebarChatAction.DELETE, uiContext.getString(R.string.settings_action_delete), Icons.Outlined.DeleteOutline)).forEach { (action, label, icon) ->
                        DropdownMenuItem(text = { Text(label, color = if (action == SidebarChatAction.DELETE) scheme.error else scheme.onSurface) },
                            leadingIcon = { Icon(icon, null, tint = if (action == SidebarChatAction.DELETE) scheme.error else scheme.onSurfaceVariant) },
                            onClick = { menu = false; onAction(action) })
                    }
                }
            }
        }
    }
}
