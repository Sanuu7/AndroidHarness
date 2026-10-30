package com.androidharness.app.ui.settings

import com.androidharness.app.ui.theme.HarnessGlassSwitch as Switch
import com.androidharness.app.ui.common.localizedOption
import com.androidharness.app.ui.common.localizedThinkingOption
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.filterNotNull
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.ChatBackupException
import com.androidharness.app.data.audio.GroqTtsClient
import com.androidharness.app.data.audio.TtsError
import com.androidharness.app.data.audio.TtsVoiceOption
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.chat.components.FullAccessOrange
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import com.androidharness.app.ui.settings.ProviderManagerSheet
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.data.env.ShizukuState
import com.androidharness.app.data.env.UserServiceState
import com.androidharness.app.data.env.adb.AdbPairingService
import com.androidharness.app.data.env.adb.WirelessAdbManager
import com.androidharness.app.ui.common.formatTokenCount
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.common.HarnessMark
import com.androidharness.app.ui.common.openOAuthBrowser
import com.androidharness.app.ui.common.AddWorkspaceDialog
import com.androidharness.app.ui.common.BiometricAuth
import com.androidharness.app.ui.common.TtsTermsDialog
import com.androidharness.app.ui.common.findFragmentActivity
import com.androidharness.app.ui.common.SecureDialogEffect
import com.androidharness.app.ui.common.SecureScreenEffect
import com.androidharness.app.ui.common.SystemGrants
import com.androidharness.app.ui.common.rememberStorageAccess
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.tools.mcp.McpConfigParser
import com.androidharness.app.tools.mcp.McpServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.RadioButton
import com.androidharness.app.ui.common.TextButton


@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenStats: () -> Unit = {},
    onRunSetup: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenProviders: () -> Unit = {},
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val workspace by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)
    val projects by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val currentProject by container.workspace.currentProject.collectAsStateWithLifecycle(initialValue = null)
    val envState by container.linuxEnv.state.collectAsStateWithLifecycle()
    val shizukuState by container.shizuku.state.collectAsStateWithLifecycle(initialValue = ShizukuState.NOT_INSTALLED)
    val serviceState by container.shizuku.serviceState.collectAsStateWithLifecycle(initialValue = UserServiceState.NOT_BOUND)
    val scope = rememberCoroutineScope()

    // Settings itself is screenshot-able; the surfaces that actually put a
    // key or token on screen raise the FLAG_SECURE policy themselves (the
    // web-search entry forms below, plus the MCP dialogs).

    var showAddWorkspace by remember { mutableStateOf(false) }
    var showChatAgents by rememberSaveable { mutableStateOf(false) }
    var startingChatAgent by remember { mutableStateOf(false) }
    if (showChatAgents) com.androidharness.app.ui.chat.components.ChatAgentsSheet(container,
        onDismiss = { showChatAgents = false }, onStart = { profile ->
            if (!startingChatAgent) {
            startingChatAgent = true
            scope.launch {
                try {
                    val id = container.startChatAgent(profile)
                    showChatAgents = false
                    container.pendingSessionId.emit(id)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { android.widget.Toast.makeText(uiContext, e.message, android.widget.Toast.LENGTH_LONG).show() }
                finally { startingChatAgent = false }
            }
            }
        })
    var pendingDelete by remember { mutableStateOf<ProjectEntity?>(null) }

    var pageName by rememberSaveable { mutableStateOf<String?>(null) }
    var pageHistory by rememberSaveable { mutableStateOf(listOf<String>()) }
    val desktop = com.androidharness.app.ui.LocalDesktopLayout.current
    val page = pageName?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
        ?: if (desktop) SettingsPage.APPEARANCE else null
    // Label of the control a search result or deep link asked for, cleared once
    // the user has had a moment to see where they landed.
    var highlight by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(highlight) {
        if (highlight != null) {
            kotlinx.coroutines.delay(2600)
            highlight = null
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val homeScroll = rememberScrollState()
    fun goBack() {
        when {
            pageHistory.isNotEmpty() -> { pageName = pageHistory.last(); pageHistory = pageHistory.dropLast(1); highlight = null }
            desktop -> onBack()
            page != null -> pageName = null
            query.isNotBlank() -> query = ""
            else -> onBack()
        }
    }
    BackHandler(enabled = page != null || query.isNotBlank()) { goBack() }
    LaunchedEffect(Unit) {
        container.pendingSettingsScroll.filterNotNull().collect { target ->
            settingsDeepLink(target)?.let { destination ->
                if (target == "chat-agents") showChatAgents = true
                val current = pageName
                if (current != null && current != destination.name) pageHistory = pageHistory + current
                pageName = destination.name
            }
            highlight = settingsDeepLinkAnchor(target)
            container.pendingSettingsScroll.value = null
        }
    }
    fun openPage(destination: SettingsPage, anchor: Int? = null) {
        highlight = anchor
        when (destination) {
            SettingsPage.SKILLS -> onOpenSkills()
            SettingsPage.USAGE -> onOpenStats()
            SettingsPage.SETUP -> onRunSetup()
            else -> {
                val current = pageName
                if (desktop && !(page == SettingsPage.CODING && destination in settingsCodingPages)) pageHistory = emptyList()
                else if (current != null && current != destination.name) pageHistory = pageHistory + current
                pageName = destination.name
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) scope.launch { container.workspace.addPickedFolder(uri) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppHeader(
                title = if (desktop) stringResource(R.string.settings_page_title) else page?.let { stringResource(it.title) }
                    ?: stringResource(R.string.settings_page_title),
                onBack = { if (desktop) onBack() else goBack() },
            )
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (desktop) {
                DesktopSettingsNavigation(page, query, { query = it }, ::openPage,
                    Modifier.width(240.dp).fillMaxHeight())
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        if (page == null) {
            SettingsHome(
                query = query,
                onQueryChange = { query = it },
                onOpen = ::openPage,
                modifier = Modifier.fillMaxSize().verticalScroll(homeScroll),
            )
        } else {
            key(page) {
                SettingsHighlightScope(highlight) {
                    Column(
                        modifier = Modifier.then(if (desktop) Modifier.widthIn(max = 880.dp) else Modifier)
                            .fillMaxSize().then(if (page == SettingsPage.LOCAL_MODELS) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (page != SettingsPage.LOCAL_MODELS && page != SettingsPage.AGENT && page != SettingsPage.TELEGRAM && page != SettingsPage.STORAGE && page != SettingsPage.ENVIRONMENT) SettingsPageIntro(page)
                    when (page) {
                        SettingsPage.LANGUAGE -> LanguageSection()
                        SettingsPage.CHAT_OPTIONS -> ChatOptionsSection(
                            settings,
                            onStartScreen = { value -> scope.launch { container.settings.setStartScreen(value) } },
                            onChatStart = { value -> scope.launch { container.settings.setChatStart(value) } },
                            onKeepThinkingCollapsed = { value -> scope.launch { container.settings.setChatKeepThinkingCollapsed(value) } },
                            onImageFrequency = { value -> scope.launch { container.settings.setChatImageFrequency(value) } },
                            onRumbleEnabled = { value -> scope.launch { container.settings.setRumbleEnabled(value) } },
                            onRumbleStrength = { value -> scope.launch { container.settings.setRumbleStrength(value) } },
                            onRumbleSmoothness = { value -> scope.launch { container.settings.setRumbleSmoothness(value) } },
                            onInstructions = { container.settings.setChatInstructions(it) },
                            agentCount = settings.chatAgents.size,
                            onManageAgents = { showChatAgents = true },
                        )
                        SettingsPage.MEMORY -> {
                            MemoryOptionsSection(
                                container = container,
                                settings = settings,
                                onEnabled = { value -> scope.launch { container.settings.setMemoryEnabled(value) } },
                                onOpen = {},
                                showManage = false,
                            )
                            MemoryManagerSection(container)
                        }
                        SettingsPage.CODING -> {
                            CodeStartOptionsSection(
                                settings,
                                onCodeStart = { value -> scope.launch { container.settings.setCodeStart(value) } },
                            )
                            CodingSection { page -> openPage(page) }
                        }
                        SettingsPage.NINE_ROUTER -> NineRouterSection(container)
                        SettingsPage.CONNECTED_ACCOUNTS -> ConnectedAccountsSection(container)
                        SettingsPage.MODELS -> {
                            SettingsPanel(Modifier.fillMaxWidth()) {
                                SettingsAnchor(R.string.settings_anchor_manage_providers) {
                                    SettingRow(
                                        icon = Icons.Outlined.Key,
                                        title = stringResource(R.string.settings_anchor_manage_providers),
                                        subtitle = stringResource(R.string.settings_models_manage_subtitle),
                                        onClick = onOpenProviders,
                                        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                                    )
                                }
                            }
                            SettingsAnchor(R.string.settings_anchor_dual_planning) {
                                PlanningModelSection(container, settings, scope, onOpenProviders)
                            }
                            CurrentSetupCard(settings, providers)
                            FallbackSettingsSection(container, settings.fallbackPolicy)
                        }
                        SettingsPage.SUBAGENT -> {
                            SubagentModelSection(container, settings)
                            SubagentSettingsSection(
                                settings = settings,
                                onSubagentTools = { scope.launch { container.settings.setSubagentFullAccess(it) } },
                                onBackgroundSubagents = { scope.launch { container.settings.setBackgroundSubagents(it) } },
                                onCompletionNotifications = { scope.launch { container.settings.setSubagentCompletionNotifications(it) } },
                                onOpenBackgroundSettings = { openPage(SettingsPage.BACKGROUND) },
                            )
                        }
                        SettingsPage.LOCAL_MODELS -> LocalModelsSection(container, scrollContent = true)
                        SettingsPage.AGENT -> AgentSection(container, settings, scope)
                        SettingsPage.CHAT -> {
                            ChatBehaviorSection(container, settings, scope)
                            SlashCommandsSection(container)
                        }
                        SettingsPage.CODE_INTELLIGENCE -> CodeIntelligenceSection(container, workspace, currentProject)
                        SettingsPage.CAVEMAN -> CavemanSection(container, settings)
                        SettingsPage.VOICE -> VoiceSpeechSection(container, settings, scope)
                        SettingsPage.APPEARANCE -> AppearanceSection(container, settings, scope)
                        SettingsPage.PRIVACY -> PrivacySection(container, settings, scope)
                        SettingsPage.GITHUB -> GitHubIntegrationSection(container)
                        SettingsPage.SEARCH -> WebSearchSection(container)
                        SettingsPage.MCP -> McpSection(container)
                        SettingsPage.TELEGRAM -> TelegramSection(container)
                        SettingsPage.WORKSPACE -> {
                            RememberedPermissionsSection(container)
                            WorkspaceSection(
                                container, projects, currentProject, workspace?.displayPath, shizukuState,
                                onAddWorkspace = { showAddWorkspace = true },
                                onDeleteWorkspace = { pendingDelete = it },
                                onSelectWorkspace = { id -> scope.launch { container.workspace.setActiveProject(id) } },
                            )
                        }
                        SettingsPage.BACKGROUND -> BackgroundSettingsSection(container, settings)
                        SettingsPage.STORAGE -> StorageSection(container)
                        SettingsPage.ENVIRONMENT -> TerminalSection(container, envState, shizukuState, serviceState)
                        SettingsPage.TROUBLESHOOTING -> TroubleshootingSection(container, ::openPage)
                        SettingsPage.BACKUP -> {
                            SettingsBackupSection(container)
                            ChatsBackupSection(container)
                        }
                        SettingsPage.UPDATES -> UpdatesCard(container)
                        SettingsPage.SYNC -> com.androidharness.app.ui.transfer.SyncSettingsSection(container)
                        SettingsPage.FTP_SERVER -> com.androidharness.app.ui.transfer.FtpServerSection(container)
                        SettingsPage.ABOUT -> AboutSection(container)
                        SettingsPage.SKILLS, SettingsPage.USAGE, SettingsPage.SETUP -> Unit
                    }
                        if (page != SettingsPage.LOCAL_MODELS) Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
            }
        }
    }

    if (showAddWorkspace) {
        AddWorkspaceDialog(
            container = container,
            onDismiss = { showAddWorkspace = false },
            onPickSaf = {
                showAddWorkspace = false
                folderPicker.launch(null)
            },
        )
    }

    pendingDelete?.let { project ->
        var deleting by remember(project.id) { mutableStateOf(false) }
        var deleteError by remember(project.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!deleting) pendingDelete = null },
            title = { Text(stringResource(R.string.settings_workspace_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_workspace_delete_message, project.name))
                    deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    deleteError = null
                    scope.launch {
                        try {
                            container.workspace.deleteProject(project)
                            pendingDelete = null
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            deleteError = e.message?.takeIf { it.isNotBlank() } ?: uiContext.getString(R.string.ui_could_not_remove_workspace_try_again)
                        } finally {
                            deleting = false
                        }
                    }
                }) { Text(if (deleting) uiContext.getString(R.string.ui_deleting) else uiContext.getString(R.string.settings_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { pendingDelete = null }) { Text(stringResource(R.string.settings_action_cancel)) }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Workspace
// ---------------------------------------------------------------------------

@Composable
private fun WorkspaceSection(
    container: AppContainer,
    projects: List<ProjectEntity>,
    currentProject: ProjectEntity?,
    workspacePath: String?,
    shizukuState: ShizukuState,
    onAddWorkspace: () -> Unit,
    onDeleteWorkspace: (ProjectEntity) -> Unit,
    onSelectWorkspace: (String) -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    com.androidharness.app.ui.common.ShizukuWorkspaceWarningEffect(container.shizuku)
    SettingsHeader(uiContext.getString(R.string.ui_workspace))

    currentProject?.let { current ->
        val desc = container.workspace.describe(current)
        val scheme = MaterialTheme.colorScheme
        Surface(
            color = scheme.surface,
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(14.dp),
            ) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = scheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_workspace_active), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    Text(current.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        workspacePath ?: desc.kindLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = stringResource(R.string.settings_workspace_active_badge),
                    tint = scheme.primary,
                )
            }
        }
    }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            projects.forEachIndexed { index, project ->
                val desc = container.workspace.describe(project)
                val isActive = project.id == currentProject?.id
                SettingRow(
                    icon = if (project.kind == "APP") Icons.Outlined.Folder else Icons.Outlined.FolderOpen,
                    title = project.name,
                    subtitle = desc.kindLabel + " · " + if (desc.shellCapable) uiContext.getString(R.string.ui_full_shell_1091) else uiContext.getString(R.string.ui_file_tools_only_1092),
                    onClick = { onSelectWorkspace(project.id) }.takeIf { !isActive },
                    divider = index != projects.lastIndex,
                    trailing = {
                        if (isActive) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = stringResource(R.string.settings_workspace_active), tint = MaterialTheme.colorScheme.primary)
                        } else if (project.kind != "APP") {
                            IconButton(onClick = { onDeleteWorkspace(project) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.settings_workspace_delete_cd, project.name), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            }
                        }
                    },
                )
            }
        }
        Button(
            onClick = onAddWorkspace,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.settings_workspace_add))
        }
    }
}

// ---------------------------------------------------------------------------
// Terminal & environment
// ---------------------------------------------------------------------------

@Composable
private fun TerminalSection(
    container: AppContainer,
    envState: com.androidharness.app.data.env.EnvState,
    shizukuState: ShizukuState,
    serviceState: UserServiceState,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val storageAccess = rememberStorageAccess()
    val allFiles = storageAccess.granted
    val connectionFailure by container.shizuku.connectionFailure.collectAsStateWithLifecycle()
    val shellProvider by container.shizuku.provider.collectAsStateWithLifecycle()
    val wirelessState by container.wirelessAdb.state.collectAsStateWithLifecycle()


    // What the shell can reach right now.
    val appShellOk = envState is com.androidharness.app.data.env.EnvState.Ready
    val storageText = if (allFiles) uiContext.getString(R.string.ui_granted) else uiContext.getString(R.string.ui_needs_grant)
    val systemText = if (wirelessState is WirelessAdbManager.State.Connected && container.shizuku.wirelessTransportActive) uiContext.getString(R.string.wireless_adb_section_title) else when (shizukuState) {
        ShizukuState.GRANTED -> when (serviceState) {
            UserServiceState.BOUND_READY -> container.shizuku.transportLabel
            UserServiceState.COMPAT_READY -> uiContext.getString(R.string.ui_compatibility)
            UserServiceState.BINDING -> uiContext.getString(R.string.ui_connecting)
            UserServiceState.BIND_FAILED -> uiContext.getString(R.string.ui_connection_failed)
            UserServiceState.NOT_BOUND -> uiContext.getString(R.string.ui_not_connected)
        }
        ShizukuState.RUNNING_NO_PERMISSION -> uiContext.getString(R.string.ui_needs_grant)
        ShizukuState.NOT_RUNNING -> uiContext.getString(R.string.ui_start_shizuku)
        ShizukuState.NOT_INSTALLED -> uiContext.getString(R.string.ui_not_installed)
    }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Terminal, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.settings_terminal_access), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                StatusText(if (container.shizuku.isServiceReady()) container.shizuku.transportLabel else stringResource(R.string.ui_ready), ok = true)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(stringResource(R.string.settings_anchor_shared_storage) + " · " + storageText,
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                }
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(stringResource(R.string.settings_search_linux_title) + " · " + stringResource(if (appShellOk) R.string.ui_installed else R.string.ui_not_installed),
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    SettingsGroupHeading(R.string.settings_device_connections)
    WirelessAdbSection(container)
    SettingsAnchor(R.string.settings_anchor_system_paths) {
    SettingsDisclosure(Icons.Outlined.Shield, "Shizuku / Shery", systemText,
        anchors = setOf(R.string.settings_anchor_system_paths)) {
    ShizukuCard(
        state = shizukuState,
        serviceState = serviceState,
        failure = connectionFailure,
        connectionReport = container.shizuku::connectionReport,
        startupReport = container.shizuku::startupReport,
        onRefresh = { container.shizuku.refresh() },
        onGrant = { container.shizuku.requestPermission() },
        providerLabel = shellProvider?.label ?: stringResource(R.string.shell_access_providers),
    )
    }
    }
    SettingsAnchor(R.string.settings_anchor_shared_storage) {
    SettingsDisclosure(Icons.Outlined.SdStorage, stringResource(R.string.settings_anchor_shared_storage), storageText,
        anchors = setOf(R.string.settings_anchor_shared_storage)) {
        StorageAccessCard(allFiles = allFiles, onGrant = storageAccess.request)
    }
    }
    SettingsGroupHeading(R.string.settings_device_tools)
    SettingsAnchor(R.string.settings_anchor_app_shell) {
    SettingsDisclosure(Icons.Outlined.Terminal, stringResource(R.string.settings_search_linux_title),
        stringResource(if (appShellOk) R.string.ui_installed else R.string.settings_linux_optional),
        anchors = setOf(R.string.settings_anchor_app_shell),
        forceExpanded = envState is com.androidharness.app.data.env.EnvState.Downloading || envState is com.androidharness.app.data.env.EnvState.Installing || envState is com.androidharness.app.data.env.EnvState.Preparing) {
        LinuxEnvironmentCard(container = container, envState = envState)
    }
    }
    SettingsDisclosure(Icons.Outlined.Shield, stringResource(R.string.settings_device_advanced), stringResource(R.string.settings_device_advanced_summary),
        anchors = setOf(R.string.settings_root_title)) {
        RootAccessSection(container)
        DhizukuCard(container.dhizuku)
    }
    SettingsDisclosure(Icons.Outlined.BatteryFull, stringResource(R.string.settings_device_background), stringResource(R.string.settings_device_background_summary)) {
        BatteryCard(container = container)
    }
}

// ---------------------------------------------------------------------------
// Code intelligence
// ---------------------------------------------------------------------------

@Composable
private fun CodeIntelligenceSection(
    container: AppContainer,
    workspace: com.androidharness.app.workspace.WorkspaceFs?,
    currentProject: ProjectEntity?,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val codeGraphState by container.codeGraph.state.collectAsStateWithLifecycle()
    val workspaceRun by container.codeGraph.workspaceState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val rootPath = workspace?.shellRoot?.absolutePath
    var indexRevision by remember(rootPath) { mutableStateOf(0) }
    val indexed = remember(rootPath, indexRevision) { container.codeGraph.isIndexed(workspace) }

    LaunchedEffect(rootPath) {
        container.codeGraph.refresh()
        // Ask GitHub once per visit so the buttons can say what is actually
        // available instead of offering a blind "Update".
        container.codeGraph.checkForUpdates()
    }

    SettingsHeader("CodeGraph")
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_search_codegraph_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        uiContext.getString(R.string.ui_local_semantic_code_graph_for_symbols_callers),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusText(
                    when {
                        codeGraphState.version == null -> uiContext.getString(R.string.ui_not_installed)
                        codeGraphState.updateAvailable -> "v${codeGraphState.version} → v${codeGraphState.latest}"
                        codeGraphState.latest != null -> "v${codeGraphState.version} · latest"
                        else -> "v${codeGraphState.version}"
                    },
                    ok = codeGraphState.version != null,
                )
            }

            if (codeGraphState.busy) {
                ThinLinearProgress(modifier = Modifier.fillMaxWidth())
                Text(
                    codeGraphState.action ?: uiContext.getString(R.string.ui_working),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            codeGraphState.message?.takeLast(1_200)?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (codeGraphState.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (codeGraphState.version == null) {
                    Button(
                        enabled = !codeGraphState.busy,
                        onClick = { scope.launch { container.codeGraph.installOrUpdate() } },
                    ) { Text(stringResource(R.string.settings_codegraph_install)) }
                } else {
                    // Only offer an update when a newer release actually exists;
                    // otherwise the tap is a check that answers "already latest".
                    if (codeGraphState.updateAvailable) {
                        Button(
                            enabled = !codeGraphState.busy,
                            onClick = { scope.launch { container.codeGraph.installOrUpdate() } },
                        ) { Text(stringResource(R.string.settings_codegraph_update_to, codeGraphState.latest.orEmpty())) }
                    } else {
                        OutlinedButton(
                            enabled = !codeGraphState.busy,
                            onClick = { scope.launch { container.codeGraph.checkForUpdates() } },
                        ) { Text(stringResource(R.string.settings_search_updates_title)) }
                    }
                    OutlinedButton(
                        enabled = !codeGraphState.busy,
                        onClick = { scope.launch { container.codeGraph.uninstall() } },
                    ) { Text(stringResource(R.string.settings_action_uninstall)) }
                }
            }

            Text(
                uiContext.getString(R.string.ui_harness_installs_codegraph_into_its_private_linux),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    SettingsHeader(uiContext.getString(R.string.settings_workspace_active))
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(currentProject?.name ?: uiContext.getString(R.string.ui_no_workspace), style = MaterialTheme.typography.titleSmall)
                    Text(
                        rootPath ?: uiContext.getString(R.string.ui_this_workspace_does_not_expose_a_real),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (rootPath != null) {
                    StatusText(if (indexed) uiContext.getString(R.string.ui_indexed) else uiContext.getString(R.string.ui_not_indexed), ok = indexed)
                }
            }

            // Both an install and a sync make this card unusable for a moment,
            // so the row reports whichever is running.
            val workspaceBusy = workspaceRun.busy || codeGraphState.busy
            val workspaceAction = workspaceRun.action ?: codeGraphState.action

            if (workspaceBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        workspaceAction ?: uiContext.getString(R.string.ui_working),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Live output of the run that is happening or has just finished,
            // shown here rather than in the install card above, which is not
            // what was tapped. During a long index these lines are the only
            // sign of life, so the tail scrolls with the work.
            workspaceRun.message?.takeLast(1_200)?.let { message ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        message.trim().lines().takeLast(9).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = HarnessMono),
                        color = if (workspaceRun.failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            when {
                rootPath == null -> Text(
                    uiContext.getString(R.string.ui_codegraph_needs_a_shell_capable_workspace_with),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                codeGraphState.version == null -> Text(
                    uiContext.getString(R.string.ui_install_codegraph_first_then_enable_it_for),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                !indexed -> Button(
                    enabled = !workspaceBusy,
                    onClick = {
                        val current = workspace
                        scope.launch {
                            container.codeGraph.initWorkspace(current)
                            indexRevision++
                        }
                    },
                ) { Text(stringResource(R.string.settings_codegraph_enable)) }
                else -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        enabled = !workspaceBusy,
                        onClick = {
                            val current = workspace
                            scope.launch {
                                container.codeGraph.syncWorkspace(current)
                                indexRevision++
                            }
                        },
                    ) { Text(stringResource(R.string.settings_action_sync)) }
                    OutlinedButton(
                        enabled = !workspaceBusy,
                        onClick = {
                            val current = workspace
                            scope.launch {
                                container.codeGraph.reindexWorkspace(current)
                                indexRevision++
                            }
                        },
                    ) { Text(stringResource(R.string.settings_codegraph_reindex)) }
                    TextButton(
                        enabled = !workspaceBusy,
                        onClick = {
                            val current = workspace
                            scope.launch {
                                container.codeGraph.disableWorkspace(current)
                                indexRevision++
                            }
                        },
                    ) { Text(stringResource(R.string.settings_action_disable)) }
                }
            }
        }
    }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.settings_codegraph_agent_integration), style = MaterialTheme.typography.titleSmall)
            Text(
                uiContext.getString(R.string.ui_when_this_workspace_is_indexed_harness_exposes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Web search
// ---------------------------------------------------------------------------

@Composable
private fun WebSearchSection(container: AppContainer) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val provider = settings.webSearchProvider
    val providerName = when (provider) {
        "brave" -> "Brave Search API"
        "tavily" -> "Tavily API"
        else -> uiContext.getString(R.string.ui_keyless)
    }
    var keyDraft by remember { mutableStateOf("") }
    var keyEpoch by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val hasKey = remember(provider, keyEpoch) { container.keys.searchApiKey(provider) != null }
    val keyUrl = if (provider == "brave") "https://brave.com/search/api/" else "https://app.tavily.com/home"

    // The pasted key is only on screen while the entry form is open.
    SecureScreenEffect(container, expanded)

    SettingsHeader(uiContext.getString(R.string.settings_page_search_title))
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_search_web_backend_label), style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            provider == "keyless" -> uiContext.getString(R.string.ui_public_search_engines_no_setup)
                            hasKey -> uiContext.getString(R.string.ui_key_saved, (providerName).toString())
                            else -> uiContext.getString(R.string.ui_no_key_yet_keyless_fallback, (providerName).toString())
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusText(
                    when {
                        provider == "keyless" -> uiContext.getString(R.string.ui_keyless)
                        hasKey -> uiContext.getString(R.string.ui_on)
                        else -> uiContext.getString(R.string.ui_no_key)
                    },
                    ok = provider == "keyless" || hasKey,
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("keyless" to uiContext.getString(R.string.ui_keyless), "brave" to "Brave", "tavily" to "Tavily")
                    .forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = provider == value,
                            onClick = {
                                if (provider != value) {
                                    expanded = false
                                    status = null
                                    keyDraft = ""
                                    scope.launch { container.settings.setWebSearchProvider(value) }
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                        ) { Text(label) }
                    }
            }

            when {
                provider == "keyless" -> Text(
                    uiContext.getString(R.string.ui_the_agent_searches_public_engines_with_zero) +
                        uiContext.getString(R.string.ui_give_cleaner_higher_quality_results),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                expanded -> {
                    Text(
                        (if (provider == "brave") {
                            uiContext.getString(R.string.ui_free_key_at_brave_com_search_api)
                        } else {
                            uiContext.getString(R.string.ui_free_key_at_app_tavily_com_1)
                        }) + "The key is checked with the provider before it is saved, and it " +
                            uiContext.getString(R.string.ui_lives_only_in_the_app_s_encrypted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(keyUrl)))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Outlined.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_provider_get_api_key))
                    }
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        label = { Text(if (hasKey) uiContext.getString(R.string.ui_replace_api_key) else uiContext.getString(R.string.ui_paste_api_key)) },
                        placeholder = { Text(if (provider == "brave") "BSA…" else "tvly-…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = keyDraft.isNotBlank() && !checking,
                            onClick = {
                                checking = true
                                status = uiContext.getString(R.string.ui_checking_that_key_with, (providerName).toString())
                                scope.launch {
                                    val key = keyDraft.trim()
                                    val error = withContext(Dispatchers.IO) { checkSearchKey(provider, key) }
                                    if (error == null) {
                                        withContext(Dispatchers.IO) { container.keys.putSearchApiKey(provider, key) }
                                        keyDraft = ""
                                        expanded = false
                                        status = uiContext.getString(R.string.ui_key_verified_and_saved)
                                        keyEpoch++
                                    } else {
                                        status = error
                                    }
                                    checking = false
                                }
                            },
                        ) { Text(if (checking) uiContext.getString(R.string.ui_checking) else uiContext.getString(R.string.ui_save_and_check)) }
                        TextButton(onClick = {
                            expanded = false
                            keyDraft = ""
                            status = null
                        }) { Text(stringResource(R.string.settings_action_cancel)) }
                    }
                    status?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                hasKey -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        expanded = true
                        keyDraft = ""
                        status = null
                    }) { Text(stringResource(R.string.settings_provider_replace_key)) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { container.keys.removeSearchApiKey(provider) }
                            keyEpoch++
                        }
                    }) { Text(stringResource(R.string.settings_action_remove), color = MaterialTheme.colorScheme.error) }
                }

                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(keyUrl)))
                        }
                    }) { Text(stringResource(R.string.settings_provider_get_api_key)) }
                    OutlinedButton(onClick = {
                        expanded = true
                        keyDraft = ""
                        status = null
                    }) { Text(stringResource(R.string.settings_provider_have_key)) }
                }
            }
        }
    }
}

/**
 * Asks the provider to accept a search API key before it is saved, so an
 * invalid paste never becomes the stored credential.
 * Returns null when the key works, or a human-readable reason why not.
 */
private fun checkSearchKey(provider: String, key: String): String? {
    return runCatching {
        val req = when (provider) {
            "brave" -> okhttp3.Request.Builder()
                .url("https://api.search.brave.com/res/v1/web/search?q=android&count=1")
                .header("X-Subscription-Token", key)
                .header("Accept", "application/json")
                .build()
            else -> okhttp3.Request.Builder()
                .url("https://api.tavily.com/search")
                .header("Authorization", "Bearer $key")
                .post(
                    """{"query":"android","max_results":1}"""
                        .toRequestBody("application/json".toMediaTypeOrNull()),
                )
                .build()
        }
        val name = if (provider == "brave") "Brave" else "Tavily"
        okhttp3.OkHttpClient.Builder()
            .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
            .newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val detail = Regex("\"(?:message|detail|error)\"\\s*:\\s*\"([^\"]+)\"")
                    .find(body)?.groupValues?.get(1)
                when {
                    resp.code == 401 || resp.code == 403 ->
                        "$name rejected that key" + (detail?.let { " ($it)" } ?: "") +
                            ". Make sure the whole key was copied, that it belongs to $name, " +
                            "and that it has not been deactivated."
                    resp.code in setOf(402, 432, 433) ->
                        "$name has no available credits or the plan limit was reached. Check the provider dashboard."
                    resp.code == 429 ->
                        "$name is temporarily rate-limiting requests. Wait" +
                            (resp.header("Retry-After")?.toLongOrNull()?.let { " $it seconds" } ?: " before checking again") + "."
                    !resp.isSuccessful ->
                        "$name returned HTTP ${resp.code}" + (detail?.let { ": $it" } ?: "") +
                            ". Try again shortly."
                    else -> null
                }
            }
    }.getOrElse { "Could not reach the provider. Check your connection and try again." }
}

// ---------------------------------------------------------------------------
// Voice & speech (Groq Whisper / Inbuilt)
// ---------------------------------------------------------------------------

@Composable
private fun VoiceSpeechSection(
    container: AppContainer,
    settings: AppSettings,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val voiceEngine = settings.voiceEngine
    val groqModel = settings.groqWhisperModel
    val isGroq = voiceEngine == AppSettings.VOICE_ENGINE_GROQ
    val ttsEngine = settings.ttsEngine
    val isTtsGroq = ttsEngine == AppSettings.TTS_ENGINE_GROQ

    var keyDraft by remember { mutableStateOf("") }
    var keyEpoch by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var voicePicker by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf(false) }
    var modelEpoch by remember { mutableStateOf(0) }
    var customVoice by remember { mutableStateOf("") }
    var customModel by remember { mutableStateOf("") }
    var ttsTermsError by remember { mutableStateOf<TtsError?>(null) }
    val hasGroqKey = remember(keyEpoch) { container.keys.groqApiKey() != null }
    val groqKeyUrl = "https://console.groq.com/keys"

    // Model ids come from the account's /models list, so new Groq TTS models
    // appear without an app update. Fetched only while the model dialog is open.
    val groqModels by produceState<List<String>?>(null, modelPicker, hasGroqKey, modelEpoch) {
        value = if (modelPicker && hasGroqKey) container.tts.groqSpeechModels() else null
    }

    // The pasted key is only on screen while the entry form is open.
    SecureScreenEffect(container, expanded && (isGroq || isTtsGroq))

    // "Test voice" failures otherwise have no surface to appear on.
    LaunchedEffect(Unit) {
        container.tts.errors.collect { error ->
            if (error.termsRequired) ttsTermsError = error
            else android.widget.Toast.makeText(context, error.message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    SettingsHeader(uiContext.getString(R.string.ui_voice_speech))

    // One key serves both Whisper (input) and PlayAI (output).
    if (isGroq || isTtsGroq) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_tts_groq_key), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (hasGroqKey) uiContext.getString(R.string.settings_tts_groq_key_saved)
                            else uiContext.getString(R.string.settings_tts_groq_key_needed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusText(
                        if (hasGroqKey) uiContext.getString(R.string.ui_on) else uiContext.getString(R.string.ui_no_key),
                        ok = hasGroqKey,
                    )
                }

                when {
                    expanded -> {
                        Text(
                            uiContext.getString(R.string.ui_get_a_free_api_key_at_console) +
                                uiContext.getString(R.string.ui_groq_before_it_is_saved_and_it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(groqKeyUrl)))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Outlined.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_voice_get_groq_key))
                        }
                        OutlinedTextField(
                            value = keyDraft,
                            onValueChange = { keyDraft = it },
                            label = { Text(if (hasGroqKey) uiContext.getString(R.string.ui_replace_groq_api_key) else uiContext.getString(R.string.ui_paste_groq_api_key)) },
                            placeholder = { Text("gsk_…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = keyDraft.isNotBlank() && !checking,
                                onClick = {
                                    checking = true
                                    status = uiContext.getString(R.string.ui_checking_that_key_with_groq)
                                    scope.launch {
                                        val key = keyDraft.trim()
                                        val res = com.androidharness.app.data.audio.GroqWhisperClient.validateApiKey(key)
                                        if (res.success) {
                                            withContext(Dispatchers.IO) { container.keys.putGroqApiKey(key) }
                                            keyDraft = ""
                                            expanded = false
                                            status = uiContext.getString(R.string.ui_key_verified_and_saved)
                                            keyEpoch++
                                        } else {
                                            status = res.error ?: uiContext.getString(R.string.ui_verification_failed)
                                        }
                                        checking = false
                                    }
                                },
                            ) { Text(if (checking) uiContext.getString(R.string.ui_checking) else uiContext.getString(R.string.ui_save_and_check)) }
                            TextButton(onClick = {
                                expanded = false
                                keyDraft = ""
                                status = null
                            }) { Text(stringResource(R.string.settings_action_cancel)) }
                        }
                        status?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    hasGroqKey -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            expanded = true
                            keyDraft = ""
                            status = null
                        }) { Text(stringResource(R.string.settings_provider_replace_key)) }
                        OutlinedButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { container.keys.removeGroqApiKey() }
                                keyEpoch++
                            }
                        }) { Text(stringResource(R.string.settings_action_remove), color = MaterialTheme.colorScheme.error) }
                    }

                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(groqKeyUrl)))
                            }
                        }) { Text(stringResource(R.string.settings_provider_get_api_key)) }
                        OutlinedButton(onClick = {
                            expanded = true
                            keyDraft = ""
                            status = null
                        }) { Text(stringResource(R.string.settings_provider_have_key)) }
                    }
                }
            }
        }
    }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    SettingsAnchor(R.string.settings_anchor_stt) {
                        Text(stringResource(R.string.settings_anchor_stt), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        when {
                            !isGroq -> uiContext.getString(R.string.ui_inbuilt_android_recognizer)
                            hasGroqKey -> uiContext.getString(R.string.ui_groq_whisper_key_saved, (groqModel).toString())
                            else -> uiContext.getString(R.string.ui_groq_whisper_no_key_yet)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusText(
                    when {
                        !isGroq -> uiContext.getString(R.string.ui_inbuilt)
                        hasGroqKey -> uiContext.getString(R.string.ui_on)
                        else -> uiContext.getString(R.string.ui_no_key)
                    },
                    ok = !isGroq || hasGroqKey,
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(
                    AppSettings.VOICE_ENGINE_INBUILT to uiContext.getString(R.string.ui_inbuilt),
                    AppSettings.VOICE_ENGINE_GROQ to "Groq",
                ).forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = voiceEngine == value,
                        onClick = {
                            if (voiceEngine != value) {
                                expanded = false
                                status = null
                                keyDraft = ""
                                scope.launch { container.settings.setVoiceEngine(value) }
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    ) { Text(label) }
                }
            }

            if (!isGroq) {
                Text(
                    uiContext.getString(R.string.ui_uses_the_on_device_or_system_speech),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SettingsAnchor(R.string.settings_anchor_whisper) {
                    Text(
                        stringResource(R.string.settings_anchor_whisper),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(
                        AppSettings.GROQ_MODEL_WHISPER_V3 to "Whisper 3",
                        AppSettings.GROQ_MODEL_WHISPER_TURBO to "Turbo",
                    ).forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = groqModel == value,
                            onClick = {
                                if (groqModel != value) {
                                    scope.launch { container.settings.setGroqWhisperModel(value) }
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        ) { Text(label) }
                    }
                }
            }
        }
    }

    SettingsHeader(uiContext.getString(R.string.settings_tts_output))
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    SettingsAnchor(R.string.settings_anchor_tts) {
                        Text(stringResource(R.string.settings_anchor_tts), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        if (isTtsGroq) uiContext.getString(R.string.settings_tts_groq_hint)
                        else uiContext.getString(R.string.settings_tts_device_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusText(
                    when {
                        isTtsGroq && !hasGroqKey -> uiContext.getString(R.string.ui_no_key)
                        isTtsGroq -> "Groq"
                        else -> uiContext.getString(R.string.ui_inbuilt)
                    },
                    ok = !isTtsGroq || hasGroqKey,
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(
                    AppSettings.TTS_ENGINE_SYSTEM to uiContext.getString(R.string.settings_tts_device),
                    AppSettings.TTS_ENGINE_GROQ to "Groq",
                ).forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = ttsEngine == value,
                        onClick = {
                            if (ttsEngine != value) scope.launch { container.settings.setTtsEngine(value) }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    ) { Text(label) }
                }
            }

            if (isTtsGroq && !hasGroqKey) {
                Text(
                    uiContext.getString(R.string.settings_tts_no_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (isTtsGroq) {
                Text(
                    stringResource(R.string.settings_tts_model),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        customModel = settings.ttsGroqModel
                        modelPicker = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(GroqTtsClient.modelLabel(settings.ttsGroqModel), Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }

            Text(
                stringResource(R.string.settings_tts_voice),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { voicePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (isTtsGroq) settings.ttsGroqVoice
                    else settings.ttsSystemVoice?.replace('_', ' ')
                        ?: uiContext.getString(R.string.settings_tts_voice_automatic),
                    Modifier.weight(1f),
                )
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }

            Text(
                stringResource(R.string.settings_tts_speed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(0.75f to "0.75×", 1f to "1×", 1.25f to "1.25×", 1.5f to "1.5×")
                    .forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = kotlin.math.abs(settings.ttsSpeed - value) < 0.01f,
                            onClick = {
                                if (kotlin.math.abs(settings.ttsSpeed - value) >= 0.01f) {
                                    scope.launch { container.settings.setTtsSpeed(value) }
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 4),
                        ) { Text(label) }
                    }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.settings_tts_always_speak), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_tts_always_speak_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.ttsAlwaysSpeak,
                    onCheckedChange = { scope.launch { container.settings.setTtsAlwaysSpeak(it) } },
                )
            }

            OutlinedButton(onClick = { container.tts.preview() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.settings_tts_test))
            }
        }
    }

    if (voicePicker) {
        val deviceVoices by produceState(initialValue = emptyList<TtsVoiceOption>(), ttsEngine) {
            value = if (ttsEngine == AppSettings.TTS_ENGINE_SYSTEM) container.tts.systemVoices() else emptyList()
        }
        val groqVoices = if (isTtsGroq) GroqTtsClient.voicesFor(settings.ttsGroqModel) else emptyList()
        val options = if (isTtsGroq) {
            groqVoices.map { TtsVoiceOption(it, it.replace('-', ' ')) }
        } else {
            listOf(TtsVoiceOption("", uiContext.getString(R.string.settings_tts_voice_automatic))) + deviceVoices
        }
        val selectedId = if (isTtsGroq) settings.ttsGroqVoice else settings.ttsSystemVoice.orEmpty()

        AlertDialog(
            onDismissRequest = { voicePicker = false },
            title = { Text(stringResource(R.string.settings_tts_voice)) },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    options.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = option.id == selectedId,
                                    role = Role.RadioButton,
                                    onClick = {
                                        voicePicker = false
                                        scope.launch {
                                            if (isTtsGroq) container.settings.setTtsGroqVoice(option.id)
                                            else container.settings.setTtsSystemVoice(option.id.ifBlank { null })
                                        }
                                    },
                                )
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = option.id == selectedId, onClick = null)
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (!isTtsGroq && deviceVoices.isEmpty()) {
                        Text(
                            uiContext.getString(R.string.settings_tts_no_device_voices),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Groq has no voices endpoint, so an unknown model gets a free-form id.
                    if (isTtsGroq && groqVoices.isEmpty()) {
                        Text(
                            uiContext.getString(R.string.settings_tts_voice_custom),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = customVoice,
                            onValueChange = { customVoice = it },
                            label = { Text(stringResource(R.string.settings_tts_voice_id)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            enabled = customVoice.isNotBlank(),
                            onClick = {
                                voicePicker = false
                                scope.launch { container.settings.setTtsGroqVoice(customVoice.trim()) }
                            },
                        ) { Text(stringResource(R.string.settings_tts_save)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { voicePicker = false }) {
                    Text(stringResource(R.string.settings_action_done))
                }
            },
        )
    }

    if (modelPicker) {
        AlertDialog(
            onDismissRequest = { modelPicker = false },
            title = { Text(stringResource(R.string.settings_tts_model)) },
            text = {
                Column(
                    Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        !hasGroqKey -> Text(
                            uiContext.getString(R.string.settings_tts_no_key),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        groqModels == null -> Text(
                            uiContext.getString(R.string.settings_tts_models_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        groqModels!!.isEmpty() -> Text(
                            uiContext.getString(R.string.settings_tts_models_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> groqModels!!.forEach { id ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .selectable(
                                        selected = id == settings.ttsGroqModel,
                                        role = Role.RadioButton,
                                        onClick = {
                                            modelPicker = false
                                            scope.launch {
                                                container.settings.setTtsGroqModel(id)
                                                GroqTtsClient.defaultVoiceFor(id)?.let {
                                                    container.settings.setTtsGroqVoice(it)
                                                }
                                            }
                                        },
                                    )
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                RadioButton(selected = id == settings.ttsGroqModel, onClick = null)
                                Column(Modifier.weight(1f)) {
                                    Text(GroqTtsClient.modelLabel(id), style = MaterialTheme.typography.bodyLarge)
                                    if (GroqTtsClient.modelLabel(id) != id) {
                                        Text(
                                            id,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text(
                        uiContext.getString(R.string.settings_tts_models_custom),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = customModel,
                        onValueChange = { customModel = it },
                        label = { Text(stringResource(R.string.settings_tts_model_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = customModel.isNotBlank(),
                            onClick = {
                                val id = customModel.trim()
                                modelPicker = false
                                scope.launch {
                                    container.settings.setTtsGroqModel(id)
                                    GroqTtsClient.defaultVoiceFor(id)?.let {
                                        container.settings.setTtsGroqVoice(it)
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.settings_tts_save)) }
                        OutlinedButton(
                            onClick = { modelEpoch++ },
                            enabled = hasGroqKey,
                        ) { Text(stringResource(R.string.settings_tts_refresh)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { modelPicker = false }) {
                    Text(stringResource(R.string.settings_action_done))
                }
            },
        )
    }

    ttsTermsError?.let { TtsTermsDialog(it) { ttsTermsError = null } }
}

// ---------------------------------------------------------------------------
// MCP servers
// ---------------------------------------------------------------------------

@Composable
private fun McpSection(container: AppContainer) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val servers by container.mcp.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    val statuses by container.mcp.statuses.collectAsStateWithLifecycle(initialValue = emptyMap())
    val configTampered by container.mcp.configTampered.collectAsStateWithLifecycle(initialValue = false)
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    val anyConnected = servers.any { statuses[it.name]?.state == "connected" }

    SettingsHeader(uiContext.getString(R.string.settings_search_mcp_title))
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (configTampered) {
                Text(
                    uiContext.getString(R.string.ui_the_mcp_server_list_was_modified_outside) +
                        uiContext.getString(R.string.ui_check_its_contents_were_ignored_re_add),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_search_mcp_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        uiContext.getString(R.string.ui_extra_tools_the_agent_can_use_in),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusText(
                    when {
                        servers.isEmpty() -> uiContext.getString(R.string.ui_off)
                        anyConnected -> uiContext.getString(R.string.ui_on)
                        else -> uiContext.getString(R.string.ui_idle)
                    },
                    ok = anyConnected,
                )
            }

            servers.forEach { server ->
                val status = statuses[server.name]
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dotColor = when {
                            !server.enabled -> MaterialTheme.colorScheme.outlineVariant
                            status?.state == "connected" -> LocalStatusColors.current.success
                            status?.state == "auth" -> LocalStatusColors.current.warning
                            status?.state == "failed" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                        Box(Modifier.size(9.dp).background(dotColor, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${server.name} · ${server.type}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = server.enabled,
                            onCheckedChange = { checked ->
                                scope.launch { container.mcp.setServerEnabled(server.name, checked) }
                            },
                        )
                    }
                    Text(
                        when {
                            !server.enabled -> uiContext.getString(R.string.ui_off_enable_to_use_its_tools)
                            status == null -> uiContext.getString(R.string.ui_not_connected_yet_tap_test_or_it)
                            status.state == "connected" -> uiContext.getString(R.string.ui_connected_tools, (status.toolCount).toString())
                            status.state == "auth" -> uiContext.getString(R.string.ui_sign_in_to_use_this_server)
                            status.state == "connecting" -> uiContext.getString(R.string.ui_connecting)
                            else -> uiContext.getString(R.string.ui_failed, (status.error?.take(110) ?: "unknown error").toString())
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (status?.needsAuth == true && server.enabled) {
                        Button(
                            onClick = {
                                authError = null
                                scope.launch {
                                    container.mcp.startAuthentication(server.name).fold(
                                        onSuccess = { url ->
                                            runCatching {
                                                openOAuthBrowser(context, Uri.parse(url))
                                            }
                                        },
                                        onFailure = { e ->
                                            authError = e.message?.take(160)
                                        },
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.settings_action_authenticate)) }
                        authError?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            scope.launch { withContext(Dispatchers.IO) { container.mcp.testConnection(server) } }
                        }) { Text(stringResource(R.string.settings_action_test)) }
                        TextButton(onClick = { editing = server }) { Text(stringResource(R.string.settings_action_edit)) }
                        TextButton(onClick = {
                            scope.launch { container.mcp.removeServer(server.name) }
                        }) { Text(stringResource(R.string.settings_action_remove)) }
                    }
                }
            }

            OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.settings_action_add_server))
            }
        }
    }

    if (showAdd) {
        McpAddDialog(container, onDismiss = { showAdd = false }, onManual = {
            showAdd = false
            editing = McpServerConfig(name = "", type = "stdio")
        })
    }
    editing?.let { config ->
        McpServerDialog(container, initial = config, onDismiss = { editing = null })
    }
}

/**
 * Paste-first add dialog: accepts a Claude-style `{"mcpServers": {…}}` JSON, a
 * single-server JSON object, or a `claude mcp add …` command line, showing a
 * live preview of what will be added. A manual editor is one tap away.
 */
@Composable
private fun McpAddDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
    onManual: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    // Pasted config routinely carries auth headers; the dialog has its own
    // window, so it raises FLAG_SECURE itself.
    SecureDialogEffect()
    val scope = rememberCoroutineScope()
    var paste by remember { mutableStateOf("") }
    val parsed = remember(paste) { McpConfigParser.parsePaste(paste) }
    val existing = container.mcp.servers.value.map { it.name.lowercase() }
    val conflicts = parsed.count { it.name.lowercase() in existing }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_mcp_add_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = paste,
                    onValueChange = { paste = it },
                    label = { Text(stringResource(R.string.settings_mcp_paste_config)) },
                    placeholder = {
                        Text(
                            "{\"mcpServers\": {\"supabase\": {\"type\": \"http\", \"url\": \"…\"}}}\n" +
                                "or: claude mcp add --transport http supabase \"https://…\"",
                        )
                    },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    paste.isBlank() -> Text(
                        uiContext.getString(R.string.ui_works_with_any_of_a_full_mcpservers) +
                            uiContext.getString(R.string.ui_a_single_server_json_object_or_a),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    parsed.isEmpty() -> Text(
                        uiContext.getString(R.string.ui_could_not_parse_that_use_the_manual),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Column {
                        Text(
                            uiContext.getString(R.string.ui_will_add_server_s, (parsed.size).toString()),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        parsed.forEach { c ->
                            Text(
                                "• ${c.name} · ${c.type} · " +
                                    (c.url ?: c.command + if (c.args.isEmpty()) "" else " ${c.args.joinToString(" ")}"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (conflicts > 0) {
                            Text(
                                uiContext.getString(R.string.ui_name_s_already_exist_and_will_be, (conflicts).toString()),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalStatusColors.current.warning,
                            )
                        }
                    }
                }
                Text(
                    "Tools appear in chats as mcp__server__tool and follow the normal " +
                        "permission rules. If a server needs sign-in, an Authenticate " +
                        uiContext.getString(R.string.ui_button_appears_after_test),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed.isNotEmpty(),
                onClick = {
                    scope.launch {
                        parsed.forEach { container.mcp.addServer(it) }
                        onDismiss()
                    }
                },
            ) { Text(if (parsed.size > 1) uiContext.getString(R.string.ui_add_all) else uiContext.getString(R.string.ui_add)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onManual) { Text(stringResource(R.string.settings_mcp_manual_editor)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) }
            }
        },
    )
}

@Composable
private fun McpServerDialog(
    container: AppContainer,
    initial: McpServerConfig?,
    onDismiss: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    // Header and env values are secrets; this dialog owns its own window.
    SecureDialogEffect()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: "stdio") }
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var argsText by remember { mutableStateOf(initial?.args?.joinToString("\n") ?: "") }
    var envText by remember {
        mutableStateOf(initial?.env?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: "")
    }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var headersText by remember {
        mutableStateOf(initial?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: "")
    }
    val duplicate = (initial == null || initial.name.isBlank()) &&
        container.mcp.servers.value.any { it.name.equals(name.trim(), ignoreCase = true) }
    val valid = when (type) {
        "http", "sse" -> name.isNotBlank() && url.isNotBlank() && !duplicate
        else -> name.isNotBlank() && command.isNotBlank() && !duplicate
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial?.name.isNullOrBlank()) uiContext.getString(R.string.settings_mcp_add_title) else uiContext.getString(R.string.ui_edit_mcp_server)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_mcp_name)) },
                    placeholder = { Text(stringResource(R.string.settings_mcp_name_hint)) },
                    singleLine = true,
                    supportingText = { if (duplicate) Text(stringResource(R.string.settings_mcp_name_taken)) },
                    isError = duplicate,
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("stdio" to uiContext.getString(R.string.ui_local), "http" to "HTTP", "sse" to "SSE")
                        .forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = type == value,
                                onClick = { type = value },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                            ) { Text(label, maxLines = 1) }
                        }
                }
                if (type == "stdio") {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text(stringResource(R.string.settings_mcp_command)) },
                        placeholder = { Text(stringResource(R.string.settings_mcp_command_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = argsText,
                        onValueChange = { argsText = it },
                        label = { Text(stringResource(R.string.settings_mcp_arguments)) },
                        placeholder = { Text("-y\n@modelcontextprotocol/server-filesystem /path") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = envText,
                        onValueChange = { envText = it },
                        label = { Text(stringResource(R.string.settings_mcp_env_vars)) },
                        minLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        uiContext.getString(R.string.ui_local_process_stdio_transport_running_as_the) +
                            uiContext.getString(R.string.ui_linux_toolchain_s_path_it_starts_lazily) +
                            uiContext.getString(R.string.ui_the_app_needs_node_python_from_the),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.settings_mcp_server_url)) },
                        placeholder = { Text("https://mcp.example.com/mcp") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        label = { Text(stringResource(R.string.settings_mcp_headers)) },
                        placeholder = { Text("Authorization: Bearer …") },
                        minLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(
                            if (type == "http") R.string.settings_mcp_transport_http
                            else R.string.settings_mcp_transport_sse,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    fun kvLines(text: String, sep: Char) = text.lines().mapNotNull { line ->
                        val parts = line.split(sep, limit = 2)
                        if (parts.size == 2 && parts[0].trim().isNotEmpty()) {
                            parts[0].trim() to parts[1].trim()
                        } else null
                    }.toMap()
                    val config = McpServerConfig(
                        name = name.trim(),
                        type = type,
                        command = if (type == "stdio") command.trim() else "",
                        args = if (type == "stdio") argsText.lines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList(),
                        env = if (type == "stdio") kvLines(envText, '=') else emptyMap(),
                        url = if (type == "stdio") null else url.trim(),
                        headers = if (type == "stdio") emptyMap() else kvLines(headersText, ':'),
                        enabled = initial?.enabled ?: true,
                    )
                    scope.launch {
                        container.mcp.addServer(config)
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.settings_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) }
        },
    )
}

@Composable
private fun StorageAccessCard(allFiles: Boolean, onGrant: () -> Unit) {
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SdStorage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_env_storage_access), style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (allFiles) stringResource(R.string.settings_env_storage_granted)
                        else stringResource(R.string.settings_env_storage_optional),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!allFiles) {
                    Button(onClick = onGrant) { Text(stringResource(R.string.settings_action_grant)) }
                }
            }
        }
    }
}

@Composable
internal fun ShizukuCard(
    state: ShizukuState,
    serviceState: UserServiceState,
    failure: com.androidharness.app.data.env.ShizukuConnectionFailure?,
    connectionReport: () -> String,
    startupReport: suspend () -> com.androidharness.app.data.env.ShizukuStartupReport,
    onRefresh: () -> Unit,
    onGrant: () -> Unit,
    providerLabel: String = "Shizuku",
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(providerLabel, style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (state) {
                            ShizukuState.NOT_INSTALLED -> uiContext.getString(R.string.ui_not_installed_optional)
                            ShizukuState.NOT_RUNNING -> uiContext.getString(R.string.ui_installed_not_running)
                            ShizukuState.RUNNING_NO_PERMISSION -> uiContext.getString(R.string.ui_running_grant_androidharness_access)
                            ShizukuState.GRANTED -> when (serviceState) {
                                UserServiceState.BOUND_READY -> uiContext.getString(R.string.ui_connected_shell_privileges_ready)
                                UserServiceState.COMPAT_READY -> uiContext.getString(R.string.ui_connected_in_compatibility_mode_shell_privileges_ready)
                                UserServiceState.BINDING -> uiContext.getString(R.string.ui_access_granted_connecting_to_the_shell_runner)
                                UserServiceState.BIND_FAILED -> uiContext.getString(R.string.ui_access_granted_but_the_shell_runner_could)
                                UserServiceState.NOT_BOUND -> uiContext.getString(R.string.ui_access_granted_shell_runner_not_connected)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ShizukuFailureDetails(failure, connectionReport, startupReport,
                recovered = serviceState == UserServiceState.COMPAT_READY)
            when (state) {
                ShizukuState.GRANTED -> {
                    if (serviceState.isReady) {
                        Text(
                            if (serviceState == UserServiceState.COMPAT_READY) uiContext.getString(R.string.ui_compatibility_runner_ready) else uiContext.getString(R.string.ui_privileged_runner_ready),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(onClick = onRefresh, enabled = serviceState != UserServiceState.BINDING) {
                        Text(if (serviceState.isReady) uiContext.getString(R.string.ui_re_check_status) else uiContext.getString(R.string.ui_retry_connection))
                    }
                }
                ShizukuState.RUNNING_NO_PERMISSION -> Button(onClick = onGrant) { Text(stringResource(R.string.shell_access_grant, providerLabel)) }
                ShizukuState.NOT_RUNNING -> OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.settings_env_refresh_status)) }
                ShizukuState.NOT_INSTALLED -> OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.settings_env_check_again)) }
            }
        }
    }
}

@Composable
private fun WirelessAdbSection(container: AppContainer) {
    val context = LocalContext.current
    val manager = container.wirelessAdb
    val state by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var manual by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var test by remember { mutableStateOf<String?>(null) }
    var instructions by rememberSaveable { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    val supported = manager.isSupported()
    val notifications = remember(state, manual) { wirelessNotificationsEnabled(context) }
    val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startWirelessPairing(context)
    }

    fun beginPairing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!notifications) {
            // Without a notification there is no reply field for the code.
            manual = true
            return
        }
        if (Build.VERSION.SDK_INT >= 36 &&
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            localNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            startWirelessPairing(context)
        }
    }

    fun reconnect() {
        scope.launch {
            busy = true
            message = null
            manager.setAutoReconnect(true)
            manager.connect().onFailure { message = it.message ?: it.javaClass.simpleName }
            com.androidharness.app.data.env.adb.WirelessAdbService.resume(context)
            busy = false
        }
    }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.wireless_adb_section_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.wireless_adb_compact_summary), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(wirelessAdbStatus(state), style = MaterialTheme.typography.labelMedium,
                    color = if (state is WirelessAdbManager.State.Connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(instructions) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.wireless_adb_explain), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.wireless_adb_explain_steps), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.wireless_adb_explain_notification), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.wireless_adb_explain_requirements), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.wireless_adb_explain_miui), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.wireless_adb_explain_security), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.wireless_adb_privileged_note), style = MaterialTheme.typography.bodySmall)

                }
            }

            if (!supported) {
                Text(stringResource(R.string.wireless_adb_unsupported), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            } else {
                if (!notifications) {
                    Text(stringResource(R.string.wireless_adb_notifications_disabled), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { openWirelessNotificationSettings(context) }) {
                        Text(stringResource(R.string.wireless_adb_notification_settings))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state !is WirelessAdbManager.State.Connected) {
                        Button(onClick = { beginPairing() }, enabled = !busy) {
                            Text(stringResource(R.string.wireless_adb_pair_button))
                        }
                    }
                    if (state is WirelessAdbManager.State.Paired || state is WirelessAdbManager.State.Failed) {
                        Button(onClick = { reconnect() }, enabled = !busy) {
                            Text(stringResource(R.string.wireless_adb_reconnect))
                        }
                    }
                    if (state is WirelessAdbManager.State.Connected) {
                        FilledTonalButton(onClick = { com.androidharness.app.data.env.adb.WirelessAdbService.disconnect(context) }) {
                            Text(stringResource(R.string.wireless_adb_disconnect))
                        }
                    }
                    TextButton(onClick = { instructions = !instructions }) { Text(stringResource(R.string.settings_connection_help)) }
                }
                if (busy) ThinLinearProgress(Modifier.fillMaxWidth())
                TextButton(onClick = { options = !options }) {
                    Text(stringResource(R.string.settings_connection_options))
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
                AnimatedVisibility(options) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state is WirelessAdbManager.State.Connected) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                test = runCatching { manager.testUid() }
                                    .fold({ "UID $it" }, { it.message ?: "failed" })
                            }
                        }, enabled = !busy) { Text(stringResource(R.string.wireless_adb_test)) }
                    }
                    if (manager.isPaired) {
                        OutlinedButton(onClick = { com.androidharness.app.data.env.adb.WirelessAdbService.disconnect(context); AdbPairingService.stop(context); manager.forget(); message = null; test = null }, enabled = !busy) {
                            Text(stringResource(R.string.wireless_adb_forget))
                        }
                    }
                    TextButton(onClick = { manual = true }, enabled = !busy) {
                        Text(stringResource(R.string.wireless_adb_manual_pairing))
                    }
                    TextButton(onClick = { openDeveloperOptions(context) }) {
                        Text(stringResource(R.string.wireless_adb_developer_options))
                    }
                }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            test?.let {
                Text(stringResource(R.string.wireless_adb_test_result, it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    if (manual) {
        var port by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var dialogError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) manual = false },
            title = { Text(stringResource(R.string.wireless_adb_manual_pairing)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.wireless_adb_manual_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.wireless_adb_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.wireless_adb_code)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && port.toIntOrNull()?.let { it in 1..65535 } == true && code.length == 6,
                    onClick = {
                        scope.launch {
                            busy = true
                            dialogError = null
                            runCatching { manager.pair(code, port.toInt()) }
                                .onFailure { dialogError = it.message }
                                .onSuccess {
                                    manager.connect().onFailure { dialogError = it.message }.onSuccess {
                                        com.androidharness.app.data.env.adb.WirelessAdbService.enable(context)
                                        manual = false
                                    }
                                }
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.wireless_adb_pair_action)) }
            },
            dismissButton = {
                TextButton(onClick = { manual = false }, enabled = !busy) {
                    Text(stringResource(R.string.wireless_adb_cancel))
                }
            },
        )
    }
}

@Composable
private fun wirelessAdbStatus(state: WirelessAdbManager.State): String = when (state) {
    WirelessAdbManager.State.Unpaired -> stringResource(R.string.wireless_adb_status_unpaired)
    WirelessAdbManager.State.Paired -> stringResource(R.string.wireless_adb_status_paired)
    WirelessAdbManager.State.Connecting -> stringResource(R.string.wireless_adb_status_connecting)
    is WirelessAdbManager.State.Connected -> stringResource(R.string.wireless_adb_status_connected, state.uid)
    is WirelessAdbManager.State.Failed -> stringResource(R.string.ui_connection_failed)
}

private fun wirelessNotificationsEnabled(context: Context): Boolean {
    val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return false
    if (!manager.areNotificationsEnabled()) return false
    val channel = manager.getNotificationChannel("wireless_adb_pairing") ?: return true
    return channel.importance != android.app.NotificationManager.IMPORTANCE_NONE
}

private fun startWirelessPairing(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) AdbPairingService.start(context)
}

private fun openDeveloperOptions(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        })
    }
}

private fun openWirelessNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }
}

@Composable
private fun LinuxEnvironmentCard(
    container: AppContainer,
    envState: com.androidharness.app.data.env.EnvState,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var checkResult by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var showPackagesSheet by remember { mutableStateOf(false) }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_search_linux_title), style = MaterialTheme.typography.titleSmall)
                    Text("bash, git, gh, python, pip, node, npm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusText(
                    when (envState) {
                        is com.androidharness.app.data.env.EnvState.Ready -> uiContext.getString(R.string.ui_installed)
                        is com.androidharness.app.data.env.EnvState.Downloading -> uiContext.getString(R.string.ui_downloading)
                        is com.androidharness.app.data.env.EnvState.Installing -> uiContext.getString(R.string.settings_packages_installing)
                        is com.androidharness.app.data.env.EnvState.Preparing -> uiContext.getString(R.string.ui_resolving)
                        is com.androidharness.app.data.env.EnvState.Failed -> uiContext.getString(R.string.ui_failed_64)
                        else -> uiContext.getString(R.string.ui_not_installed)
                    },
                    ok = envState is com.androidharness.app.data.env.EnvState.Ready,
                )
            }

            when (envState) {
                is com.androidharness.app.data.env.EnvState.Ready -> {
                    val installed = container.linuxEnv.installedPackages()
                    if (installed.isNotEmpty()) {
                        Text(
                            uiContext.getString(R.string.ui_packages, (installed.size).toString(), (installed.joinToString(", ")).toString()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    checkResult?.let { report ->
                        Text(
                            report,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (report.startsWith("All present")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                    }
                    // Step 1: check. Step 2 (only when something is actually
                    // missing/broken): confirm and update. Nothing to do → no
                    // dead button.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FilledTonalButton(onClick = { showPackagesSheet = true }) {
                            Icon(Icons.Outlined.Inventory2, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_env_packages))
                        }
                        OutlinedButton(
                            enabled = !checking,
                            onClick = {
                                checking = true
                                scope.launch {
                                    checkResult = container.linuxEnv.checkMissing()
                                    checking = false
                                }
                            },
                        ) {
                            Text(if (checking) uiContext.getString(R.string.ui_checking) else uiContext.getString(R.string.ui_check_missing))
                        }
                        if (checkResult != null && !checkResult!!.startsWith("All present")) {
                            Button(onClick = {
                                checkResult = null
                                scope.launch { container.linuxEnv.updateEnvironment() }
                            }) {
                                Text(stringResource(R.string.settings_action_update))
                            }
                        }
                        OutlinedButton(onClick = { confirmUninstall = true }) {
                            Text(stringResource(R.string.settings_action_uninstall))
                        }
                    }
                }
                is com.androidharness.app.data.env.EnvState.NotInstalled,
                is com.androidharness.app.data.env.EnvState.Failed -> {
                    if (envState is com.androidharness.app.data.env.EnvState.Failed) {
                        Text(
                            uiContext.getString(R.string.ui_install_failed, (envState.message).toString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            uiContext.getString(R.string.ui_installs_bash_git_gh_python_3_pip),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { scope.launch { container.linuxEnv.install(container.linuxEnv.fullPackages) } }) {
                        Text(if (envState is com.androidharness.app.data.env.EnvState.Failed) uiContext.getString(R.string.ui_retry_install) else uiContext.getString(R.string.ui_install_full_environment))
                    }
                }
                is com.androidharness.app.data.env.EnvState.Downloading,
                is com.androidharness.app.data.env.EnvState.Installing,
                is com.androidharness.app.data.env.EnvState.Preparing -> {
                    com.androidharness.app.ui.common.PackageInstallProgress(envState)
                }
            }
        }
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text(stringResource(R.string.settings_env_uninstall_title)) },
            text = { Text(stringResource(R.string.settings_env_uninstall_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    checkResult = null
                    scope.launch {
                        try { container.storage.uninstallToolchain() }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { checkResult = e.message }
                    }
                }) { Text(stringResource(R.string.settings_action_uninstall), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text(stringResource(R.string.settings_action_cancel)) }
            },
        )
    }

    if (showPackagesSheet) {
        PackageManagerSheet(
            container = container,
            onDismiss = { showPackagesSheet = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Agent / Appearance / slash commands
// ---------------------------------------------------------------------------

/**
 * Read-only snapshot of what's actually driving requests right now. Model and
 * thinking changed from the chat header (picker + overflow), so they show
 * here as status, not settings, keeps this screen single-purpose.
 */
@Composable
private fun CurrentSetupCard(
    settings: AppSettings,
    providers: List<com.androidharness.app.llm.ProviderConfig>,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val active = providers.firstOrNull { it.id == settings.activeProviderId }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.settings_models_current_config), style = MaterialTheme.typography.titleSmallEmphasized)
            SetupLine(
                uiContext.getString(R.string.ui_model),
                if (active == null) uiContext.getString(R.string.ui_no_provider_connected)
                else "${active.name} · ${settings.activeModel?.takeIf { it.isNotBlank() } ?: active.model}",
            )
            SetupLine(uiContext.getString(R.string.ui_thinking_2), localizedThinkingOption(settings.thinkingLevel.label))
            SetupLine("${uiContext.getString(R.string.ui_context_window_1183)} · ${uiContext.getString(R.string.conversation_code)}", formatTokenCount(settings.maxContextTokens.toLong()))
            SetupLine(uiContext.getString(R.string.ui_default_permission), localizedOption(settings.permissionMode.label))
            Text(
                uiContext.getString(R.string.ui_model_and_thinking_change_from_the_chat),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SetupLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f).padding(end = 12.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.62f),
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}


@Composable
internal fun CodingSection(onOpen: (SettingsPage) -> Unit) {
    val groups = listOf(
        R.string.settings_coding_workflow to listOf(SettingsPage.AGENT, SettingsPage.SUBAGENT, SettingsPage.CHAT),
        R.string.settings_coding_intelligence to listOf(SettingsPage.SKILLS, SettingsPage.CODE_INTELLIGENCE, SettingsPage.CAVEMAN),
        R.string.settings_coding_execution to listOf(SettingsPage.WORKSPACE, SettingsPage.ENVIRONMENT),
    )
    groups.forEach { (title, pages) ->
        SettingsGroupHeading(title)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pages.forEach { destination ->
                SettingsFeatureRow(settingsIcon(destination), stringResource(destination.title), stringResource(destination.description), { onOpen(destination) })
            }
        }
    }
}

@Composable
private fun AgentSection(
    container: AppContainer,
    settings: AppSettings,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var showAgentsDialog by remember { mutableStateOf(false) }
    AgentBehaviorSection(
        settings = settings,
        onPermissionMode = { scope.launch { container.settings.setPermissionMode(it) } },
        onContextLimit = { scope.launch { container.settings.setMaxContextTokens(it) } },
        onIterationLimit = { scope.launch { container.settings.setMaxIterations(it) } },
        onProjectInstructions = { showAgentsDialog = true },
        onLiveUpdates = { scope.launch { container.settings.setLiveUpdatesEnabled(it) } },
        showLiveUpdates = false,
    )
    if (showAgentsDialog) {
        AgentsInstructionsDialog(container, onDismiss = { showAgentsDialog = false })
    }
}

/** Maximum size injected into the system prompt (AgentEngine.readWorkspaceDoc). */
private const val AGENTS_MD_CAP = 16_000

private val AGENTS_MD_TEMPLATE = """# AGENTS.md

## Project
(What this project is, in one or two sentences.)

## Build & run
(Commands to build, run, and test; plain names, e.g. `gradlew assembleDebug`.)

## Conventions
(Code style, naming, file organization the agent should follow.)

## Constraints
(Things the agent must never do in this workspace.)
"""

@Composable
private fun AgentsInstructionsDialog(container: AppContainer, onDismiss: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) } // null = loading
    var existed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load(): Pair<Boolean, String> {
        val fs = container.workspace.currentOnce()
        val node = runCatching { fs.resolve("AGENTS.md") }.getOrNull()
        return if (node != null && node.exists && node.isFile) true to node.readText() else false to ""
    }

    LaunchedEffect(Unit) {
        val (found, content) = runCatching { load() }.getOrElse { false to "" }
        existed = found
        text = if (found) content else AGENTS_MD_TEMPLATE
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_anchor_project_instructions)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!existed) {
                    Text(
                        uiContext.getString(R.string.ui_no_agents_md_found_in_this_workspace) +
                            uiContext.getString(R.string.ui_or_type_init_in_chat_to_have),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = text ?: "",
                    onValueChange = {
                        text = it
                        if (it.length > AGENTS_MD_CAP) {
                            error = uiContext.getString(R.string.ui_only_the_first_characters_are_injected_into, (AGENTS_MD_CAP).toString())
                        } else if (error != null) {
                            error = null
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp, max = 420.dp),
                    label = { Text(stringResource(R.string.settings_agent_instructions_content)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = HarnessMono,
                    ),
                )
                if (error != null) {
                    Text(
                        error!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val content = text ?: return@TextButton
                    scope.launch {
                        runCatching {
                            val fs = container.workspace.currentOnce()
                            fs.resolve("AGENTS.md").writeText(content)
                        }.onSuccess {
                            existed = true
                            onDismiss()
                        }.onFailure { e -> error = uiContext.getString(R.string.ui_could_not_save, (e.message).toString()) }
                    }
                },
                enabled = text != null,
            ) { Text(if (existed) uiContext.getString(R.string.settings_action_save) else uiContext.getString(R.string.ui_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}

@Composable
private fun AppearanceSection(container: AppContainer, settings: AppSettings, scope: kotlinx.coroutines.CoroutineScope) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    SettingsHeader(uiContext.getString(R.string.settings_page_appearance_title))
    WorkspaceLayoutSection(settings.workspaceLayout) { scope.launch { container.settings.setWorkspaceLayout(it) } }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsAnchor(R.string.settings_anchor_theme) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_anchor_theme), style = MaterialTheme.typography.titleSmall)
                ThemeMode.entries.chunked(2).forEach { modes ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        modes.forEach { mode ->
                            ThemeChoice(
                                mode = mode,
                                selected = settings.themeMode == mode,
                                modifier = Modifier.weight(1f),
                                onClick = { scope.launch { container.settings.setThemeMode(mode) } },
                            )
                        }
                    }
                }
            }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SettingsAnchor(R.string.settings_anchor_dynamic_color) {
                ThemeColorSection(
                    palette = settings.colorPalette, useWallpaper = settings.dynamicColor, themeMode = settings.themeMode,
                    onWallpaperChange = { scope.launch { container.settings.setDynamicColor(it) } },
                    onPaletteChange = { scope.launch { container.settings.setColorPalette(it) } },
                    presetActive = settings.activeCustomPaletteId == null && !settings.harnessCanvas.adaptColors,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SettingsAnchor(R.string.theme_custom_title) {
                CustomPaletteSection(settings.customPalettes, settings.activeCustomPaletteId.takeUnless { settings.dynamicColor || settings.harnessCanvas.adaptColors },
                    onSave = { scope.launch { container.settings.saveCustomPalette(it) } },
                    onSelect = { scope.launch { container.settings.selectCustomPalette(it) } },
                    onRemove = { scope.launch { container.settings.removeCustomPalette(it) } })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SettingsAnchor(R.string.settings_anchor_progress_bar_style) {
                ProgressBarStyleSection(settings.progressBarStyle) {
                    scope.launch { container.settings.setProgressBarStyle(it) }
                }
            }
        }
    }
    SettingsAnchor(R.string.theme_canvas_title) {
        HarnessCanvasSection(settings.harnessCanvas, container.settings)
    }
    SettingsAnchor(R.string.glass_title) {
        HarnessGlassSection(settings, container.settings)
    }
}

@Composable
private fun ChatBehaviorSection(container: AppContainer, settings: AppSettings, scope: kotlinx.coroutines.CoroutineScope) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    SettingsHeader(stringResource(R.string.settings_page_chat_title))
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsAnchor(R.string.settings_anchor_show_live_code) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                    .clickable { scope.launch { container.settings.setShowLiveCode(!settings.showLiveCode) } }) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_anchor_show_live_code), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.settings_chat_show_live_code_subtitle),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.showLiveCode,
                        onCheckedChange = { scope.launch { container.settings.setShowLiveCode(it) } })
                }
            }
            HorizontalDivider()
            SettingsAnchor(R.string.settings_anchor_repo_map) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_anchor_repo_map), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (settings.repoMapEnabled) {
                                uiContext.getString(R.string.ui_on_injects_a_compact_project_symbol_map)
                            } else {
                                uiContext.getString(R.string.ui_off_agent_explores_files_only_using_tool)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.repoMapEnabled,
                        onCheckedChange = { scope.launch { container.settings.setRepoMapEnabled(it) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivacySection(container: AppContainer, settings: AppSettings, scope: kotlinx.coroutines.CoroutineScope) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    SettingsHeader(uiContext.getString(R.string.ui_privacy))
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_search_app_lock_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (settings.biometricLockEnabled) {
                            uiContext.getString(R.string.ui_on_requires_fingerprint_face_or_device_pin)
                        } else {
                            uiContext.getString(R.string.ui_off_the_app_opens_immediately_without_authentication)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val biometricTitle = if (settings.biometricLockEnabled)
                    stringResource(R.string.settings_privacy_lock_disable_title)
                else stringResource(R.string.settings_privacy_lock_enable_title)
                val biometricPrompt = stringResource(R.string.settings_privacy_biometric_prompt)
                Switch(
                    checked = settings.biometricLockEnabled,
                    onCheckedChange = { targetState ->
                        val activity = context.findFragmentActivity()
                        if (activity != null) {
                            BiometricAuth.prompt(
                                activity = activity,
                                title = biometricTitle,
                                subtitle = biometricPrompt,
                                onSuccess = {
                                    scope.launch { container.settings.setBiometricLockEnabled(targetState) }
                                },
                            )
                        } else {
                            scope.launch { container.settings.setBiometricLockEnabled(targetState) }
                        }
                    },
                )
            }
            if (settings.biometricLockEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.settings_search_autolock_title), style = MaterialTheme.typography.labelLarge)
                    val timeouts = listOf(0 to uiContext.getString(R.string.ui_instant), 1 to "1m", 5 to "5m", 15 to "15m")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        timeouts.forEachIndexed { index, (mins, label) ->
                            SegmentedButton(
                                selected = settings.biometricLockTimeoutMinutes == mins,
                                onClick = { scope.launch { container.settings.setBiometricLockTimeout(mins) } },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = timeouts.size),
                            ) {
                                Text(label, maxLines = 1)
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_search_screenshots_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (settings.allowScreenshots) {
                            uiContext.getString(R.string.ui_on_screenshots_work_everywhere_except_while_a)
                        } else {
                            uiContext.getString(R.string.ui_off_screenshots_and_the_recents_preview_are) +
                                uiContext.getString(R.string.ui_key_screens_stay_blocked_even_when_this)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.allowScreenshots,
                    onCheckedChange = { scope.launch { container.settings.setAllowScreenshots(it) } },
                )
            }
        }
    }
}

@Composable
private fun PlanningModelSection(
    container: AppContainer,
    settings: AppSettings,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenProviders: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val catalogs by container.providers.catalogs.collectAsStateWithLifecycle(initialValue = emptyMap())
    var pickingRole by remember { mutableStateOf<String?>(null) }
    var managingRoleProvider by remember { mutableStateOf<String?>(null) }

    // The sheet opens on the role's provider when one is set, else the
    // active one, so the list is never empty for a first pick.
    val fallbackProviderId = settings.activeProviderId ?: providers.firstOrNull()?.id

    SettingsHeader(uiContext.getString(R.string.settings_search_dual_planning_title))
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_models_dual_subtitle), style = MaterialTheme.typography.bodyLarge)
                Text(
                    uiContext.getString(R.string.ui_configure_models_for_dual_planning_when_activated) +
                        uiContext.getString(R.string.ui_planning_runs_with_the_plan_model_and),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ModelRoleRow(
                label = stringResource(R.string.settings_search_plan_model_title),
                model = settings.planningModel
                    ?: providers.firstOrNull { it.id == (settings.planningProviderId ?: fallbackProviderId) }?.model,
            ) { pickingRole = "plan" }
            ModelRoleRow(
                label = stringResource(R.string.settings_search_execute_model_title),
                model = settings.executionModel
                    ?: providers.firstOrNull { it.id == (settings.executionProviderId ?: fallbackProviderId) }?.model,
            ) { pickingRole = "exec" }
        }
    }

    pickingRole?.let { role ->
        val isPlan = role == "plan"
        ModelPickerSheet(
            providers = providers,
            activeProviderId = (if (isPlan) settings.planningProviderId else settings.executionProviderId)
                ?: fallbackProviderId,
            activeModel = if (isPlan) settings.planningModel else settings.executionModel,
            catalogs = catalogs,
            onDismiss = { pickingRole = null },
            onSelect = { providerId, model ->
                scope.launch {
                    if (isPlan) container.settings.setPlanningModel(providerId, model)
                    else container.settings.setExecutionModel(providerId, model)
                }
                pickingRole = null
            },
            onRefreshCatalog = { providerId ->
                val provider = providers.firstOrNull { it.id == providerId }
                val key = container.providers.apiKey(providerId)
                when {
                    provider == null -> uiContext.getString(R.string.ui_unknown_provider)
                    else -> {
                        val usable = provider.effectiveApiKey(key)
                        if (usable.isBlank()) uiContext.getString(R.string.ui_no_api_key_for_this_provider)
                        else when (val result = com.androidharness.app.llm.ModelCatalog.listModels(provider, usable)) {
                            is com.androidharness.app.llm.ModelCatalog.Result.Models -> {
                                container.providers.saveCatalog(providerId, result.models)
                                null
                            }
                            is com.androidharness.app.llm.ModelCatalog.Result.Failed -> result.message
                        }
                    }
                }
            },
            onAddCustomModel = { providerId, model, reasoning ->
                scope.launch { container.providers.addCustomModel(providerId, model, reasoning) }
            },
            onDeleteCustomModel = { providerId, model ->
                scope.launch { container.providers.removeCustomModel(providerId, model) }
            },
            onEnableProvider = { id -> scope.launch { container.providers.setEnabled(id, true) } },
            onManageProviders = {
                managingRoleProvider = role
                pickingRole = null
            },
        )
    }

    managingRoleProvider?.let { role ->
        val isPlan = role == "plan"
        ProviderManagerSheet(
            providers = providers,
            activeProviderId = (if (isPlan) settings.planningProviderId else settings.executionProviderId)
                ?: fallbackProviderId,
            apiKeys = container.providers::apiKeys,
            onDismiss = { managingRoleProvider = null },
            onSetActive = { providerId ->
                scope.launch {
                    if (isPlan) container.settings.setPlanningModel(providerId, null)
                    else container.settings.setExecutionModel(providerId, null)
                }
                managingRoleProvider = null
            },
            catalogs = catalogs, onRefreshCatalog = container.providers::refreshCatalog,
            onDelete = { providerId ->
                container.providers.delete(providerId)
            },
            onEnabledChange = { id, enabled -> container.providers.setEnabled(id, enabled) },
            onMove = { id, direction, visible -> scope.launch { container.providers.move(id, direction, visible) } },
            onSave = { existing, name, type, baseUrl, model, apiKey, headers ->
                scope.launch {
                    val created = if (existing == null) {
                        container.providers.add(name, type, baseUrl, model, apiKey, headers)
                    } else {
                        val updated = existing.copy(name = name, type = type, baseUrl = baseUrl, model = model, headers = headers)
                        container.providers.update(updated, apiKey)
                        updated
                    }
                    if (isPlan) container.settings.setPlanningModel(created.id, null)
                    else container.settings.setExecutionModel(created.id, null)
                    managingRoleProvider = null
                }
            },
        )
    }
}

@Composable
private fun ModelRoleRow(label: String, model: String?, onClick: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                model ?: uiContext.getString(R.string.ui_provider_default),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.settings_models_choose_model, label),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SlashCommandsSection(container: AppContainer) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    SettingsAnchor(R.string.settings_anchor_slash_commands) {
        SettingsHeader(uiContext.getString(R.string.settings_search_slash_commands_title))
    }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var snippetName by remember { mutableStateOf("") }
            var snippetBody by remember { mutableStateOf("") }
            var showAdd by remember { mutableStateOf(false) }
            TextButton(onClick = { showAdd = !showAdd }) {
                Text(if (showAdd) uiContext.getString(R.string.settings_action_cancel) else uiContext.getString(R.string.ui_add_custom_command))
            }
            AnimatedVisibility(showAdd) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = snippetName, onValueChange = { snippetName = it },
                        label = { Text(stringResource(R.string.settings_mcp_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = snippetBody, onValueChange = { snippetBody = it },
                        label = { Text(stringResource(R.string.settings_slash_prompt)) }, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = {
                        val n = snippetName.trim(); val b = snippetBody.trim()
                        if (n.isNotBlank() && b.isNotBlank()) {
                            scope.launch { container.snippets.add(n, b) }
                            snippetName = ""; snippetBody = ""; showAdd = false
                        }
                    }) { Text(stringResource(R.string.settings_action_save)) }
                }
            }
            val snippets by container.snippets.snippets.collectAsStateWithLifecycle(initialValue = emptyList())
            snippets.forEach { snippet ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "/${snippet.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = HarnessMono,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { scope.launch { container.snippets.delete(snippet) } }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.settings_action_delete), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryCard(container: AppContainer) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_env_keep_alive), style = MaterialTheme.typography.titleSmall)
                    Text(
                        uiContext.getString(R.string.ui_hold_the_cpu_while_a_run_or),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.keepAlive,
                    onCheckedChange = { scope.launch { container.settings.setKeepAlive(it) } },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_search_battery_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (exempt) uiContext.getString(R.string.ui_excluded_android_will_not_throttle_background_runs)
                        else uiContext.getString(R.string.ui_on_samsung_and_other_oems_may_kill),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!exempt) {
                    Button(onClick = {
                        requestBatteryExemption(context)
                        exempt = isIgnoringBatteryOptimizations(context)
                    }) { Text(stringResource(R.string.settings_action_fix)) }
                }
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean =
    com.androidharness.app.ui.common.SystemGrants.isIgnoringBatteryOptimizations(context)

private fun requestBatteryExemption(context: android.content.Context) =
    com.androidharness.app.ui.common.SystemGrants.requestBatteryExemption(context)

// ---------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------

@Composable
private fun SettingsHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMediumEmphasized,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 14.dp, bottom = 2.dp),
    )
}

@Composable
internal fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    divider: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) = SettingsRow(
    leading = {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    },
    title = title,
    subtitle = subtitle,
    onClick = onClick,
    divider = divider,
    trailing = trailing,
)

/** Same row, for the places that need something other than a themed icon up front. */
@Composable
private fun SettingsRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    divider: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
    if (divider) {
        HorizontalDivider(
            Modifier.padding(start = 50.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun StatusText(text: String, ok: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (ok) LocalStatusColors.current.success else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DropdownSetting(
    label: String,
    current: String,
    entries: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    divider: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 12.dp),
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    current,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.settings_models_change_model, label),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Box {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                entries.forEach { (value, entryLabel) ->
                    val isFullAccess = value == PermissionMode.FULL_ACCESS.name
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    entryLabel,
                                    Modifier.weight(1f),
                                    color = if (isFullAccess) FullAccessOrange else Color.Unspecified,
                                )
                                if (entryLabel == current) {
                                    Icon(
                                        Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = if (isFullAccess) FullAccessOrange else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdatesCard(container: AppContainer) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val step by container.updates.step.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val version = remember(container) { appVersionLabel(container.appContext) }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.settings_page_updates_title), style = MaterialTheme.typography.titleSmall)
            }
            Text(
                uiContext.getString(R.string.ui_current_version_checks_github_releases_for_newer, (version).toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            UpdateInstallationChoice(container)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { scope.launch { container.updates.check(manual = true) } },
                    enabled = step !is com.androidharness.app.data.update.UpdateManager.Step.Checking &&
                        step !is com.androidharness.app.data.update.UpdateManager.Step.Downloading,
                ) {
                    if (step is com.androidharness.app.data.update.UpdateManager.Step.Checking) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (step is com.androidharness.app.data.update.UpdateManager.Step.Checking) uiContext.getString(R.string.ui_checking) else uiContext.getString(R.string.settings_search_updates_title))
                }
                when (val s = step) {
                    is com.androidharness.app.data.update.UpdateManager.Step.UpToDate -> {
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_updates_uptodate), style = MaterialTheme.typography.labelMedium)
                    }
                    is com.androidharness.app.data.update.UpdateManager.Step.Available -> {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            uiContext.getString(R.string.ui_available_see_the_dialog, (s.release.tag).toString()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------

private const val AUTHOR_URL = "https://github.com/Sanuu7"
private const val REPO_URL = "https://github.com/Sanuu7/AndroidHarness"
private const val LICENSE_URL = "https://github.com/Sanuu7/AndroidHarness/blob/main/LICENSE"

/** Installed version, shared by the About page and the update card. */
private fun appVersionLabel(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull().orEmpty().ifBlank { "?" }

/** A project AndroidHarness leans on, listed on the About page. */
private data class Credit(val name: String, val role: String, val url: String)

private val BUILT_WITH = listOf(
    Credit("Termux", "Linux packages and shell tooling", "https://github.com/termux"),
    Credit("Shizuku", "Elevated shell access without root", "https://github.com/RikkaApps/Shizuku"),
    Credit("Shevery", "Compatible privileged shell provider", "https://github.com/HmnDev-Tech/shevery"),
    Credit("Dhizuku API", "Device Owner permission sharing · MIT", "https://github.com/iamr0s/Dhizuku-API"),
    Credit("CodeGraph", "Semantic code index behind the graph tools", "https://github.com/colbymchenry/codegraph"),
    Credit("Caveman", "Terse reply modes, adapted from Julius Brussee's skills", "https://github.com/JuliusBrussee/caveman"),
    Credit("sora-editor", "The in-app code editor", "https://github.com/Rosemoe/sora-editor"),
    Credit("Eruda", "Devtools inside the web preview", "https://github.com/liriliri/eruda"),
)

private val INSPIRED_BY = listOf(
    Credit("Hermes Agent", "Agent loop and scheduled runs", "https://github.com/NousResearch/hermes-agent"),
    Credit("Aider", "Repo map and edit workflow", "https://github.com/Aider-AI/aider"),
    Credit("pi (ohmypi)", "Terminal agent design", "https://github.com/earendil-works/pi"),
    Credit("OpenCode", "Provider and relay handling", "https://github.com/anomalyco/opencode"),
    Credit("Claude Code", "Tool cards and permission model", "https://github.com/anthropics"),
    Credit("Roo Code", "Approval flow and modes", "https://github.com/RooCodeInc/Roo-Code"),
    Credit("Cline", "Approval flow and modes", "https://github.com/cline/cline"),
    Credit("browser-use", "Agent-driven browsing", "https://github.com/browser-use/browser-use"),
    Credit("llama.cpp", "Local server compatibility", "https://github.com/ggerganov/llama.cpp"),
)

@Composable
private fun AboutSection(container: AppContainer) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val version = remember(container) { appVersionLabel(container.appContext) }
    val open: (String) -> Unit = { url ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HarnessMark(size = 58.dp)
            Text("AndroidHarness", style = MaterialTheme.typography.titleLarge)
            Text(
                uiContext.getString(R.string.ui_a_coding_agent_that_lives_on_your),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Surface(color = scheme.surfaceContainerHigh, shape = CircleShape) {
                Text(
                    uiContext.getString(R.string.ui_version, (version).toString()),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
    }

    SettingsHeader(uiContext.getString(R.string.ui_made_by))
    SettingsPanel(Modifier.fillMaxWidth()) {
        SettingsRow(
            leading = { GitHubMark() },
            title = "Sanuu",
            subtitle = "github.com/Sanuu7",
            onClick = { open(AUTHOR_URL) },
            divider = true,
            trailing = { ExternalLinkIcon() },
        )
        SettingsRow(
            leading = {
                Icon(Icons.Outlined.Code, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            },
            title = stringResource(R.string.settings_about_source),
            subtitle = "github.com/Sanuu7/AndroidHarness",
            onClick = { open(REPO_URL) },
            trailing = { ExternalLinkIcon() },
        )
    }

    SettingsHeader(uiContext.getString(R.string.settings_search_license_title))
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.settings_about_license), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Surface(color = scheme.primaryContainer, shape = CircleShape) {
                    Text(
                        uiContext.getString(R.string.ui_open_source),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                uiContext.getString(R.string.ui_use_it_change_it_ship_it_even) +
                    uiContext.getString(R.string.ui_copyright_notice_and_the_license_text_travel),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { open(LICENSE_URL) }.padding(vertical = 4.dp),
            ) {
                Text(stringResource(R.string.settings_about_read_license), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
            }
        }
    }

    SettingsHeader(uiContext.getString(R.string.settings_search_credits_title))
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                uiContext.getString(R.string.ui_androidharness_is_built_on_open_source_work),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        CreditsGroup(uiContext.getString(R.string.ui_built_with), BUILT_WITH, open)
        CreditsGroup(uiContext.getString(R.string.ui_inspired_by), INSPIRED_BY, open)
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                uiContext.getString(R.string.ui_every_project_keeps_its_own_license_androidharness),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** One labelled block of the credits list, separated from the block above it. */
@Composable
private fun CreditsGroup(label: String, credits: List<Credit>, onOpen: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
    Text(
        label,
        style = MaterialTheme.typography.labelMediumEmphasized,
        color = scheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
    )
    credits.forEachIndexed { index, credit ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(credit.url) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(credit.name, style = MaterialTheme.typography.bodyMedium)
                Text(credit.role, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        if (index != credits.lastIndex) {
            HorizontalDivider(
                Modifier.padding(start = 16.dp, end = 16.dp),
                color = scheme.outlineVariant.copy(alpha = 0.3f),
            )
        }
    }
}

/** GitHub's mark, tinted with the theme like every other icon in settings. */
@Composable
private fun GitHubMark() {
    Icon(
        painterResource(R.drawable.ic_github),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
private fun ExternalLinkIcon() {
    Icon(
        Icons.AutoMirrored.Outlined.OpenInNew,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun ChatsBackupSection(container: AppContainer) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var restorePreview by remember { mutableStateOf<com.androidharness.app.data.ChatImportPreview?>(null) }

    val suggestedName = remember {
        "androidharness-chats-" +
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) +
            ".json"
    }

    fun report(text: String, error: Boolean = false) {
        statusText = text
        statusIsError = error
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            statusText = null
            try {
                val r = container.chatBackup.exportTo(uri)
                report(uiContext.getString(R.string.ui_exported_chats_messages, (r.sessions).toString(), (r.messages).toString(), (r.memories).toString()))
            } catch (e: ChatBackupException) {
                report(e.message ?: uiContext.getString(R.string.ui_export_failed), error = true)
            } catch (e: Exception) {
                report(uiContext.getString(R.string.ui_export_failed_1230, (e.message ?: "unknown error").toString()), error = true)
            } finally {
                busy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            statusText = null
            try {
                val preview = container.chatBackup.prepareImport(uri)
                if (preview.missingWorkspaces.isNotEmpty()) {
                    restorePreview = preview
                    return@launch
                }
                val r = container.chatBackup.importPrepared(preview, emptyMap())
                report(
                    uiContext.getString(R.string.ui_imported_chats_messages, (r.sessions).toString(), (r.messages).toString(), (r.memories).toString()) +
                        if (r.skipped > 0) uiContext.getString(R.string.ui_already_present, (r.skipped).toString()) else ".",
                )
            } catch (e: ChatBackupException) {
                report(e.message ?: uiContext.getString(R.string.ui_import_failed), error = true)
            } catch (e: Exception) {
                report(uiContext.getString(R.string.ui_import_failed_1234, (e.message ?: "unknown error").toString()), error = true)
            } finally {
                busy = false
            }
        }
    }

    restorePreview?.let { preview -> ChatRestoreDialog(container, preview,
        onDismiss = { restorePreview = null }, onImported = { result ->
            restorePreview = null
            report(uiContext.getString(R.string.ui_imported_chats_messages_already_present, (result.sessions).toString(), (result.messages).toString(), (result.memories).toString(), (result.skipped).toString()))
        }) }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Archive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_backup_chat_backup), style = MaterialTheme.typography.titleSmall)
                    Text(
                        uiContext.getString(R.string.ui_save_all_chats_to_a_file_or) +
                            uiContext.getString(R.string.ui_chats_and_messages_only_no_api_keys),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch(suggestedName) }, enabled = !busy) {
                    Text(stringResource(R.string.settings_action_export))
                }
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.settings_action_import))
                }
            }
            if (busy) ThinLinearProgress(Modifier.fillMaxWidth())
            statusText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
