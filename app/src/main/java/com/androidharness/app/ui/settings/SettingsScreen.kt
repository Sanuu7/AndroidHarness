package com.androidharness.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.ChatBackupException
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.chat.components.FullAccessOrange
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import com.androidharness.app.ui.settings.ProviderManagerSheet
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.data.env.ShizukuState
import com.androidharness.app.data.env.UserServiceState
import com.androidharness.app.ui.common.formatTokenCount
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.common.openOAuthBrowser
import com.androidharness.app.ui.common.AddWorkspaceDialog
import com.androidharness.app.ui.common.BiometricAuth
import com.androidharness.app.ui.common.findFragmentActivity
import com.androidharness.app.ui.common.SecureDialogEffect
import com.androidharness.app.ui.common.SecureScreenEffect
import com.androidharness.app.ui.common.SystemGrants
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.tools.mcp.McpConfigParser
import com.androidharness.app.tools.mcp.McpServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CONTEXT_PRESETS = listOf(131_072, 262_144, 400_000, 1_000_000, 2_000_000)

private val GH_OPTIONAL_SCOPES = listOf(
    Triple("workflow", "workflow", "update GitHub Actions workflow files"),
    Triple("gist", "gist", "create and manage gists"),
    Triple("read:org", "read:org", "see org-owned repos and membership"),
    Triple("delete_repo", "delete_repo", "let the agent delete repos it created"),
)

@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenStats: () -> Unit = {},
    onRunSetup: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenProviders: () -> Unit = {},
) {
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
    var pendingDelete by remember { mutableStateOf<ProjectEntity?>(null) }

    // The chat promo dialog's Configure button deep-links here and expects
    // the screen to scroll to the planning-model card. The section's content
    // offset is derived from window positions (self-correcting at any scroll)
    // plus the current scroll value.
    val scrollState = rememberScrollState()
    var containerTop by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var planningContentY by remember { androidx.compose.runtime.mutableFloatStateOf(Float.NaN) }
    var voiceContentY by remember { androidx.compose.runtime.mutableFloatStateOf(Float.NaN) }
    LaunchedEffect(Unit) {
        container.pendingSettingsScroll.filterNotNull().collect { target ->
            if (target == "planning") {
                withTimeoutOrNull(2_000) {
                    while (planningContentY.isNaN()) kotlinx.coroutines.delay(50)
                }
                if (!planningContentY.isNaN()) {
                    scrollState.animateScrollTo(planningContentY.roundToInt().coerceAtLeast(0))
                }
            } else if (target == "voice") {
                withTimeoutOrNull(2_000) {
                    while (voiceContentY.isNaN()) kotlinx.coroutines.delay(50)
                }
                if (!voiceContentY.isNaN()) {
                    scrollState.animateScrollTo(voiceContentY.roundToInt().coerceAtLeast(0))
                }
            }
            container.pendingSettingsScroll.value = null
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
                title = "Settings",
                subtitle = "Agent, workspace, environment, appearance",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .onGloballyPositioned { containerTop = it.positionInRoot().y }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GitHubSection(container)

            WebSearchSection(container)

            Box(
                Modifier.onGloballyPositioned { coords ->
                    voiceContentY = coords.positionInRoot().y - containerTop + scrollState.value
                },
            ) {
                VoiceSpeechSection(container = container, settings = settings, scope = scope)
            }

            McpSection(container)

            Box(
                Modifier.onGloballyPositioned { coords ->
                    planningContentY = coords.positionInRoot().y - containerTop + scrollState.value
                },
            ) {
                PlanningModelSection(
                    container = container,
                    settings = settings,
                    scope = scope,
                    onOpenProviders = onOpenProviders,
                )
            }

            CurrentSetupCard(settings = settings, providers = providers)

            AgentSection(
                container = container,
                settings = settings,
                scope = scope,
                onOpenStats = onOpenStats,
                onRunSetup = onRunSetup,
                onOpenSkills = onOpenSkills,
            )

            WorkspaceSection(
                container = container,
                projects = projects,
                currentProject = currentProject,
                workspacePath = workspace?.displayPath,
                shizukuState = shizukuState,
                onAddWorkspace = { showAddWorkspace = true },
                onDeleteWorkspace = { pendingDelete = it },
                onSelectWorkspace = { id -> scope.launch { container.workspace.setActiveProject(id) } },
            )

            TerminalSection(
                container = container,
                envState = envState,
                shizukuState = shizukuState,
                serviceState = serviceState,
            )

            AppearanceSection(container = container, settings = settings, scope = scope)
            ChatBehaviorSection(container = container, settings = settings, scope = scope)
            PrivacySection(container = container, settings = settings, scope = scope)
            ChatsBackupSection(container)
            SlashCommandsSection(container = container)

            UpdatesCard(container = container)

            Spacer(Modifier.height(8.dp))
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
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete workspace?") },
            text = { Text("Remove \"${project.name}\" from the list? Files on disk are not deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.workspace.deleteProject(project)
                        pendingDelete = null
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
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
    SettingsHeader("Workspace")

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
                    Text("Active workspace", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    Text(current.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        workspacePath ?: desc.kindLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = "Active",
                    tint = scheme.primary,
                )
            }
        }
    }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            projects.forEachIndexed { index, project ->
                val desc = container.workspace.describe(project)
                val isActive = project.id == currentProject?.id
                SettingRow(
                    icon = if (project.kind == "APP") Icons.Outlined.Folder else Icons.Outlined.FolderOpen,
                    title = project.name,
                    subtitle = desc.kindLabel + " · " + if (desc.shellCapable) "full shell" else "file tools only",
                    onClick = { onSelectWorkspace(project.id) }.takeIf { !isActive },
                    divider = index != projects.lastIndex,
                    trailing = {
                        if (isActive) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = "Active workspace", tint = MaterialTheme.colorScheme.primary)
                        } else if (project.kind != "APP") {
                            IconButton(onClick = { onDeleteWorkspace(project) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete ${project.name}", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
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
            Text("Add workspace")
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val allFiles = container.shellRouter.isAllFilesAccess()

    SettingsHeader("Terminal & environment")

    LinuxEnvironmentCard(container = container, envState = envState)

    // What the shell can reach right now.
    val appShellOk = envState is com.androidharness.app.data.env.EnvState.Ready
    val storageText = if (allFiles) "All files ✓" else "Needs grant"
    val systemText = when (shizukuState) {
        ShizukuState.GRANTED -> if (serviceState == UserServiceState.BOUND_READY) "Shizuku ✓" else "Connecting…"
        ShizukuState.RUNNING_NO_PERMISSION -> "Needs grant"
        ShizukuState.NOT_RUNNING -> "Start Shizuku"
        ShizukuState.NOT_INSTALLED -> "Not installed"
    }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            SettingRow(
                icon = Icons.Outlined.Terminal,
                title = "App workspace shell",
                subtitle = "Full Linux toolchain in the private workspace",
                divider = true,
                trailing = { StatusText(if (appShellOk) "Ready" else "toybox", ok = appShellOk) },
            )
            SettingRow(
                icon = Icons.Outlined.SdStorage,
                title = "Shared storage",
                subtitle = "Read/write any folder on the device",
                onClick = { SystemGrants.openAllFilesAccess(context) },
                divider = true,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusText(storageText, ok = allFiles)
                        if (!allFiles) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open storage settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
            SettingRow(
                icon = Icons.Outlined.Shield,
                title = "System paths & any folder",
                subtitle = "ADB-shell privileges via Shizuku",
                divider = false,
                trailing = { StatusText(systemText, ok = shizukuState == ShizukuState.GRANTED) },
            )
        }
    }

    StorageAccessCard(allFiles = allFiles, onGrant = { SystemGrants.openAllFilesAccess(context) })
    ShizukuCard(
        state = shizukuState,
        serviceState = serviceState,
        onRefresh = { container.shizuku.refresh() },
        onGrant = { container.shizuku.requestPermission() },
    )
    BatteryCard(container = container)
}

// ---------------------------------------------------------------------------
// GitHub
// ---------------------------------------------------------------------------

@Composable
private fun GitHubSection(container: AppContainer) {
    val context = LocalContext.current
    val auth = container.githubOAuth
    val state by auth.state.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    var selectedScopes by remember { mutableStateOf(setOf<String>()) }
    var showPermissions by remember { mutableStateOf(false) }

    SettingsHeader("GitHub")
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("GitHub", style = MaterialTheme.typography.titleSmall)
                    Text(if (state.connected) state.login?.let { "Signed in as $it" } ?: "Connected"
                        else "Push commits, open pull requests and access private repos",
                        style = MaterialTheme.typography.bodySmall)
                }
                StatusText(if (state.connected) "On" else "Off", ok = state.connected)
            }
            if (!auth.configured) {
                Text("GitHub browser login is not available in this build yet. The app publisher needs to finish login setup.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Sign in securely in your browser and approve AndroidHarness on GitHub.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showPermissions = !showPermissions }, enabled = !state.busy && !state.waiting) {
                    Text("Optional permissions")
                }
                if (showPermissions) {
                    Text("Repository access is requested for push, pull and private repos.", style = MaterialTheme.typography.bodySmall)
                    GH_OPTIONAL_SCOPES.forEach { (permission, label, hint) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = permission in selectedScopes, enabled = !state.busy && !state.waiting,
                                onCheckedChange = { checked -> selectedScopes = if (checked) selectedScopes + permission else selectedScopes - permission })
                            Column {
                                Text(label)
                                Text(hint, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Button(onClick = {
                    runCatching {
                        com.androidharness.app.ui.common.openOAuthBrowser(context, Uri.parse(auth.begin(selectedScopes)))
                    }.onFailure { auth.browserFailed() }
                }, enabled = !state.busy && !state.waiting, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.busy) "Connecting…" else if (state.waiting) "Waiting for GitHub…"
                        else if (state.connected) "Sign in again / switch account" else "Continue with GitHub")
                }
            }
            if (state.waiting && !state.busy) {
                TextButton(onClick = { auth.cancel() }) { Text("Cancel sign-in") }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.connected) {
                OutlinedButton(onClick = { confirmLogout = true }, enabled = !state.busy) {
                    Text("Log out", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmLogout) {
        AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("Log out of GitHub?") },
            text = { Text("This removes GitHub credentials from this device and its terminal tools. To revoke AndroidHarness access on GitHub as well, remove it from your GitHub account’s authorized applications.") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; auth.logout() }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } })
    }
}

// ---------------------------------------------------------------------------
// Web search
// ---------------------------------------------------------------------------

@Composable
private fun WebSearchSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val provider = settings.webSearchProvider
    val providerName = when (provider) {
        "brave" -> "Brave Search API"
        "tavily" -> "Tavily API"
        else -> "Keyless"
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

    SettingsHeader("Web search")
    OutlinedCard(Modifier.fillMaxWidth()) {
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
                    Text("web_search backend", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            provider == "keyless" -> "Public search engines, no setup"
                            hasKey -> "$providerName · key saved"
                            else -> "$providerName · no key yet (keyless fallback)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusText(
                    when {
                        provider == "keyless" -> "Keyless"
                        hasKey -> "On"
                        else -> "No key"
                    },
                    ok = provider == "keyless" || hasKey,
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("keyless" to "Keyless", "brave" to "Brave", "tavily" to "Tavily")
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
                    "The agent searches public engines with zero setup; Brave or Tavily " +
                        "give cleaner, higher-quality results.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                expanded -> {
                    Text(
                        (if (provider == "brave") {
                            "Free key at brave.com/search/api (Free plan: 1 query/second). "
                        } else {
                            "Free key at app.tavily.com (1,000 requests/month). "
                        }) + "The key is checked with the provider before it is saved, and it " +
                            "lives only in the app's encrypted storage.",
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
                        Text("Get API key")
                    }
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        label = { Text(if (hasKey) "Replace API key" else "Paste API key") },
                        placeholder = { Text(if (provider == "brave") "BSA…" else "tvly-…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = keyDraft.isNotBlank() && !checking,
                            onClick = {
                                checking = true
                                status = "Checking that key with $providerName…"
                                scope.launch {
                                    val key = keyDraft.trim()
                                    val error = withContext(Dispatchers.IO) { checkSearchKey(provider, key) }
                                    if (error == null) {
                                        withContext(Dispatchers.IO) { container.keys.putSearchApiKey(provider, key) }
                                        keyDraft = ""
                                        expanded = false
                                        status = "Key verified and saved"
                                        keyEpoch++
                                    } else {
                                        status = error
                                    }
                                    checking = false
                                }
                            },
                        ) { Text(if (checking) "Checking…" else "Save and check") }
                        TextButton(onClick = {
                            expanded = false
                            keyDraft = ""
                            status = null
                        }) { Text("Cancel") }
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
                    }) { Text("Replace key") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { container.keys.removeSearchApiKey(provider) }
                            keyEpoch++
                        }
                    }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }

                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(keyUrl)))
                        }
                    }) { Text("Get API key") }
                    OutlinedButton(onClick = {
                        expanded = true
                        keyDraft = ""
                        status = null
                    }) { Text("I have a key") }
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
                .post(
                    """{"api_key":"$key","query":"android","max_results":1}"""
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
    val context = LocalContext.current
    val voiceEngine = settings.voiceEngine
    val groqModel = settings.groqWhisperModel
    val isGroq = voiceEngine == AppSettings.VOICE_ENGINE_GROQ

    var keyDraft by remember { mutableStateOf("") }
    var keyEpoch by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val hasGroqKey = remember(keyEpoch) { container.keys.groqApiKey() != null }
    val groqKeyUrl = "https://console.groq.com/keys"

    // The pasted key is only on screen while the entry form is open.
    SecureScreenEffect(container, expanded)

    SettingsHeader("Voice & speech")
    OutlinedCard(Modifier.fillMaxWidth()) {
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
                    Text("Speech-to-text engine", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            !isGroq -> "Inbuilt Android recognizer"
                            hasGroqKey -> "Groq Whisper ($groqModel) · key saved"
                            else -> "Groq Whisper · no key yet"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusText(
                    when {
                        !isGroq -> "Inbuilt"
                        hasGroqKey -> "On"
                        else -> "No key"
                    },
                    ok = !isGroq || hasGroqKey,
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(
                    AppSettings.VOICE_ENGINE_INBUILT to "Inbuilt",
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
                    "Uses the on-device or system speech recognizer without sending audio to external APIs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Whisper Model",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

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

                when {
                    expanded -> {
                        Text(
                            "Get a free API key at console.groq.com/keys. The key is checked with " +
                                "Groq before it is saved, and it lives only in the app's encrypted storage.",
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
                            Text("Get Groq API key")
                        }
                        OutlinedTextField(
                            value = keyDraft,
                            onValueChange = { keyDraft = it },
                            label = { Text(if (hasGroqKey) "Replace Groq API key" else "Paste Groq API key") },
                            placeholder = { Text("gsk_…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = keyDraft.isNotBlank() && !checking,
                                onClick = {
                                    checking = true
                                    status = "Checking that key with Groq…"
                                    scope.launch {
                                        val key = keyDraft.trim()
                                        val res = com.androidharness.app.data.audio.GroqWhisperClient.validateApiKey(key)
                                        if (res.success) {
                                            withContext(Dispatchers.IO) { container.keys.putGroqApiKey(key) }
                                            keyDraft = ""
                                            expanded = false
                                            status = "Key verified and saved"
                                            keyEpoch++
                                        } else {
                                            status = res.error ?: "Verification failed"
                                        }
                                        checking = false
                                    }
                                },
                            ) { Text(if (checking) "Checking…" else "Save and check") }
                            TextButton(onClick = {
                                expanded = false
                                keyDraft = ""
                                status = null
                            }) { Text("Cancel") }
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
                        }) { Text("Replace key") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { container.keys.removeGroqApiKey() }
                                keyEpoch++
                            }
                        }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }

                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(groqKeyUrl)))
                            }
                        }) { Text("Get API key") }
                        OutlinedButton(onClick = {
                            expanded = true
                            keyDraft = ""
                            status = null
                        }) { Text("I have a key") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// MCP servers
// ---------------------------------------------------------------------------

@Composable
private fun McpSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val servers by container.mcp.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    val statuses by container.mcp.statuses.collectAsStateWithLifecycle(initialValue = emptyMap())
    val configTampered by container.mcp.configTampered.collectAsStateWithLifecycle(initialValue = false)
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    val anyConnected = servers.any { statuses[it.name]?.state == "connected" }

    SettingsHeader("MCP servers")
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (configTampered) {
                Text(
                    "The MCP server list was modified outside the app and failed its integrity " +
                        "check; its contents were ignored. Re-add your servers below to rebuild it.",
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
                    Text("MCP servers", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Extra tools the agent can use in any chat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusText(
                    when {
                        servers.isEmpty() -> "Off"
                        anyConnected -> "On"
                        else -> "Idle"
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
                            !server.enabled -> "Off (enable to use its tools)"
                            status == null -> "Not connected yet; tap Test, or it connects before a run"
                            status.state == "connected" -> "Connected · ${status.toolCount} tools"
                            status.state == "auth" -> "Sign in to use this server"
                            status.state == "connecting" -> "Connecting…"
                            else -> "Failed: ${status.error?.take(110) ?: "unknown error"}"
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
                        ) { Text("Authenticate") }
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
                        }) { Text("Test") }
                        TextButton(onClick = { editing = server }) { Text("Edit") }
                        TextButton(onClick = {
                            scope.launch { container.mcp.removeServer(server.name) }
                        }) { Text("Remove") }
                    }
                }
            }

            OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add server")
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
        title = { Text("Add MCP server") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = paste,
                    onValueChange = { paste = it },
                    label = { Text("Paste config") },
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
                        "Works with any of: a full mcpServers JSON (Claude Desktop / Cursor format), " +
                            "a single server JSON object, or a `claude mcp add` command.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    parsed.isEmpty() -> Text(
                        "Could not parse that. Use the manual editor below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Column {
                        Text(
                            "Will add ${parsed.size} server(s):",
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
                                "$conflicts name(s) already exist and will be replaced.",
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalStatusColors.current.warning,
                            )
                        }
                    }
                }
                Text(
                    "Tools appear in chats as mcp__server__tool and follow the normal " +
                        "permission rules. If a server needs sign-in, an Authenticate " +
                        "button appears after Test.",
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
            ) { Text(if (parsed.size > 1) "Add all" else "Add") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onManual) { Text("Manual editor") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
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
        title = { Text(if (initial?.name.isNullOrBlank()) "Add MCP server" else "Edit MCP server") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. filesystem, supabase") },
                    singleLine = true,
                    supportingText = { if (duplicate) Text("A server with this name already exists.") },
                    isError = duplicate,
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("stdio" to "Local", "http" to "HTTP", "sse" to "SSE")
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
                        label = { Text("Command") },
                        placeholder = { Text("e.g. npx, python3, /full/path/server") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = argsText,
                        onValueChange = { argsText = it },
                        label = { Text("Arguments (one per line)") },
                        placeholder = { Text("-y\n@modelcontextprotocol/server-filesystem /path") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = envText,
                        onValueChange = { envText = it },
                        label = { Text("Environment variables (KEY=value per line)") },
                        minLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Local process (stdio transport) running as the app user with the " +
                            "Linux toolchain's PATH; it starts lazily before a run and dies with " +
                            "the app. Needs node/python from the Linux environment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Server URL") },
                        placeholder = { Text("https://mcp.example.com/mcp") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        label = { Text("Headers (Key: value per line, optional)") },
                        placeholder = { Text("Authorization: Bearer …") },
                        minLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (type == "http") {
                            "Streamable HTTP transport. If the server requires sign-in, save first; " +
                                "the Authenticate button appears after the server demands it."
                        } else {
                            "Legacy HTTP+SSE transport for older remote servers."
                        },
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
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun StorageAccessCard(allFiles: Boolean, onGrant: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SdStorage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Storage access", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (allFiles) "Granted: the shell and file tools can use real paths anywhere."
                        else "Off: outside the app's own folders, access is denied.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!allFiles && Build.VERSION.SDK_INT >= 30) {
                    Button(onClick = onGrant) { Text("Grant") }
                }
            }
        }
    }
}

@Composable
private fun ShizukuCard(
    state: ShizukuState,
    serviceState: UserServiceState,
    onRefresh: () -> Unit,
    onGrant: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Shizuku (ADB privileges)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (state) {
                            ShizukuState.NOT_INSTALLED -> "Not installed: optional."
                            ShizukuState.NOT_RUNNING -> "Installed, not running."
                            ShizukuState.RUNNING_NO_PERMISSION -> "Running: grant AndroidHarness access."
                            ShizukuState.GRANTED -> "Connected: system paths unlocked."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (state) {
                ShizukuState.GRANTED -> {
                    if (serviceState == UserServiceState.BOUND_READY) {
                        Text(
                            "Privileged runner ready ✓",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(onClick = onRefresh) { Text("Re-check status") }
                }
                ShizukuState.RUNNING_NO_PERMISSION -> Button(onClick = onGrant) { Text("Grant Shizuku access") }
                ShizukuState.NOT_RUNNING -> OutlinedButton(onClick = onRefresh) { Text("Refresh status") }
                ShizukuState.NOT_INSTALLED -> OutlinedButton(onClick = onRefresh) { Text("Check again") }
            }
        }
    }
}

@Composable
private fun LinuxEnvironmentCard(
    container: AppContainer,
    envState: com.androidharness.app.data.env.EnvState,
) {
    val scope = rememberCoroutineScope()
    var checkResult by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var showPackagesSheet by remember { mutableStateOf(false) }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Linux environment", style = MaterialTheme.typography.titleSmall)
                    Text("bash, git, gh, python, pip, node, npm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusText(
                    when (envState) {
                        is com.androidharness.app.data.env.EnvState.Ready -> "Installed"
                        is com.androidharness.app.data.env.EnvState.Downloading -> "Installing…"
                        is com.androidharness.app.data.env.EnvState.Installing -> "Installing…"
                        is com.androidharness.app.data.env.EnvState.Preparing -> "Resolving…"
                        is com.androidharness.app.data.env.EnvState.Failed -> "Failed"
                        else -> "Not installed"
                    },
                    ok = envState is com.androidharness.app.data.env.EnvState.Ready,
                )
            }

            when (envState) {
                is com.androidharness.app.data.env.EnvState.Ready -> {
                    val installed = container.linuxEnv.installedPackages()
                    if (installed.isNotEmpty()) {
                        Text(
                            "Packages (${installed.size}): ${installed.joinToString(", ")}",
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
                            Text("Packages")
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
                            Text(if (checking) "Checking…" else "Check missing")
                        }
                        if (checkResult != null && !checkResult!!.startsWith("All present")) {
                            Button(onClick = {
                                checkResult = null
                                scope.launch { container.linuxEnv.updateEnvironment() }
                            }) {
                                Text("Update")
                            }
                        }
                        OutlinedButton(onClick = { confirmUninstall = true }) {
                            Text("Uninstall")
                        }
                    }
                }
                is com.androidharness.app.data.env.EnvState.NotInstalled,
                is com.androidharness.app.data.env.EnvState.Failed -> {
                    if (envState is com.androidharness.app.data.env.EnvState.Failed) {
                        Text(
                            "Install failed: ${envState.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            "Installs bash, git, gh, python 3, pip, node.js and npm into private storage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { scope.launch { container.linuxEnv.install(container.linuxEnv.fullPackages) } }) {
                        Text(if (envState is com.androidharness.app.data.env.EnvState.Failed) "Retry install" else "Install full environment")
                    }
                }
                is com.androidharness.app.data.env.EnvState.Downloading,
                is com.androidharness.app.data.env.EnvState.Installing,
                is com.androidharness.app.data.env.EnvState.Preparing -> {
                    val pkgName = (envState as? com.androidharness.app.data.env.EnvState.Downloading)?.pkg
                        ?: (envState as? com.androidharness.app.data.env.EnvState.Installing)?.pkg
                    Text(
                        if (pkgName != null) "Setting up $pkgName …" else "Installing environment…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ThinLinearProgress(Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text("Uninstall Linux environment?") },
            text = { Text("This deletes the whole toolchain (bash, git, python, node, npm and all downloaded packages). The agent falls back to toybox sh until it is installed again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    checkResult = null
                    scope.launch { container.linuxEnv.uninstall() }
                }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text("Cancel") }
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
    val active = providers.firstOrNull { it.id == settings.activeProviderId }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Current configuration", style = MaterialTheme.typography.titleSmallEmphasized)
            SetupLine(
                "Model",
                if (active == null) "No provider connected"
                else "${active.name} · ${settings.activeModel?.takeIf { it.isNotBlank() } ?: active.model}",
            )
            SetupLine("Thinking", settings.thinkingLevel.label)
            SetupLine("Context window", formatTokenCount(settings.maxContextTokens.toLong()))
            SetupLine("Default permission", settings.permissionMode.label)
            Text(
                "Model and thinking change from the chat header.",
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
            modifier = Modifier.width(132.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}


@Composable
private fun AgentSection(
    container: AppContainer,
    settings: AppSettings,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenStats: () -> Unit,
    onRunSetup: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
) {
    SettingsHeader("Agent")
    var showAgentsDialog by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            DropdownSetting(
                label = "Default permission mode",
                current = settings.permissionMode.label,
                entries = PermissionMode.entries.map { it.name to it.label },
                onSelect = { scope.launch { container.settings.setPermissionMode(PermissionMode.valueOf(it)) } },
                divider = true,
            )
            if (settings.permissionMode == PermissionMode.FULL_ACCESS) {
                Text(
                    "Full access runs every file and shell action without confirmation; " +
                        "destructive commands are the model's judgment alone. Only the git " +
                        "tools (commit, push, pull, checkout, branch) still document asking " +
                        "you first. Enable only for workspaces you trust.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FullAccessOrange,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            DropdownSetting(
                label = "Max context window",
                current = formatTokenCount(settings.maxContextTokens.toLong()),
                entries = CONTEXT_PRESETS.map { it.toString() to formatTokenCount(it.toLong()) },
                onSelect = { scope.launch { container.settings.setMaxContextTokens(it.toInt()) } },
                divider = true,
            )

            DropdownSetting(
                label = "Tool-call iteration limit",
                current = if (settings.maxIterations <= 0) "Unlimited" else settings.maxIterations.toString(),
                entries = listOf(
                    "0" to "Unlimited",
                    "25" to "25",
                    "100" to "100",
                    "250" to "250",
                ),
                onSelect = { scope.launch { container.settings.setMaxIterations(it.toInt()) } },
                divider = true,
            )

            // Detected state is loaded when the dialog opens; this row just
            // navigates there, so a light subtitle covers both cases.
            SettingRow(
                icon = Icons.Outlined.Description,
                title = "Project instructions (AGENTS.md)",
                subtitle = "Injected into every run for this workspace",
                onClick = { showAgentsDialog = true },
                divider = true,
            )

            SettingRow(
                icon = Icons.Outlined.AutoStories,
                title = "Skills",
                subtitle = "Catalog, toggles, add your own playbooks",
                onClick = onOpenSkills,
                divider = true,
            )

            SettingRow(
                icon = Icons.Outlined.BarChart,
                title = "Stats",
                subtitle = "Tokens, cache hit rates, usage over time",
                onClick = onOpenStats,
                divider = true,
            )

            SettingRow(
                icon = Icons.Outlined.Shield,
                title = "Run setup again",
                subtitle = "Provider, Shizuku, Linux environment, notifications",
                onClick = onRunSetup,
            )
        }
    }
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
        title = { Text("Project instructions (AGENTS.md)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!existed) {
                    Text(
                        "No AGENTS.md found in this workspace. Start from the template below, " +
                            "or type /init in chat to have the agent analyze the workspace and write one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = text ?: "",
                    onValueChange = {
                        text = it
                        if (it.length > AGENTS_MD_CAP) {
                            error = "Only the first $AGENTS_MD_CAP characters are injected into the prompt."
                        } else if (error != null) {
                            error = null
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp, max = 420.dp),
                    label = { Text("AGENTS.md content") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
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
                        }.onFailure { e -> error = "Could not save: ${e.message}" }
                    }
                },
                enabled = text != null,
            ) { Text(if (existed) "Save" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AppearanceSection(container: AppContainer, settings: AppSettings, scope: kotlinx.coroutines.CoroutineScope) {
    SettingsHeader("Appearance")
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.themeMode == mode,
                        onClick = { scope.launch { container.settings.setThemeMode(mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                    ) {
                        // maxLines=1: the checkmark eats the segment width, a
                        // wrapped label breaks the row.
                        Text(
                            mode.name.lowercase().replaceFirstChar { it.uppercase() },
                            maxLines = 1,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Dynamic color", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Match your wallpaper (Android 12+)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.dynamicColor,
                    onCheckedChange = { scope.launch { container.settings.setDynamicColor(it) } },
                )
            }
        }
    }
}

@Composable
private fun ChatBehaviorSection(container: AppContainer, settings: AppSettings, scope: kotlinx.coroutines.CoroutineScope) {
    SettingsHeader("Chat behavior")
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Resume last chat on launch", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (settings.resumeLastChat) {
                            "On: opens your most recent chat when launching the app."
                        } else {
                            "Off: starts a new empty chat each time the app opens."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.resumeLastChat,
                    onCheckedChange = { scope.launch { container.settings.setResumeLastChat(it) } },
                )
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Workspace code index (Repo map)", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (settings.repoMapEnabled) {
                            "On: injects a compact project symbol map (classes, functions, types) into context so the agent understands codebase structure without extra searches."
                        } else {
                            "Off: agent explores files only using tool commands."
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

@Composable
private fun PrivacySection(container: AppContainer, settings: AppSettings, scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current
    SettingsHeader("Privacy")
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Biometric app lock", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (settings.biometricLockEnabled) {
                            "On: requires fingerprint, face or device PIN to open the app."
                        } else {
                            "Off: the app opens immediately without authentication."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.biometricLockEnabled,
                    onCheckedChange = { targetState ->
                        val activity = context.findFragmentActivity()
                        if (activity != null) {
                            BiometricAuth.prompt(
                                activity = activity,
                                title = if (targetState) "Enable Biometric Lock" else "Disable Biometric Lock",
                                subtitle = "Scan fingerprint, face, or PIN to confirm",
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
                    Text("Auto-lock timeout", style = MaterialTheme.typography.labelLarge)
                    val timeouts = listOf(0 to "Instant", 1 to "1m", 5 to "5m", 15 to "15m")
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
                    Text("Allow screenshots", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (settings.allowScreenshots) {
                            "On: screenshots work everywhere except while a key or token is on screen."
                        } else {
                            "Off: screenshots and the recents preview are blocked app wide. " +
                                "Key screens stay blocked even when this is on."
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
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val catalogs by container.providers.catalogs.collectAsStateWithLifecycle(initialValue = emptyMap())
    var pickingRole by remember { mutableStateOf<String?>(null) }
    var managingRoleProvider by remember { mutableStateOf<String?>(null) }

    // The sheet opens on the role's provider when one is set, else the
    // active one, so the list is never empty for a first pick.
    val fallbackProviderId = settings.activeProviderId ?: providers.firstOrNull()?.id

    SettingsHeader("Dual planning models")
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Text("Separate plan and execute models", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Configure models for dual planning. When activated from chat, " +
                        "planning runs with the plan model and execution runs with the execute model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ModelRoleRow(
                label = "Plan model",
                model = settings.planningModel
                    ?: providers.firstOrNull { it.id == (settings.planningProviderId ?: fallbackProviderId) }?.model,
            ) { pickingRole = "plan" }
            ModelRoleRow(
                label = "Execute model",
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
                    provider == null -> "Unknown provider"
                    key.isNullOrBlank() -> "No API key for this provider"
                    else -> when (val result = com.androidharness.app.llm.ModelCatalog.listModels(provider, key)) {
                        is com.androidharness.app.llm.ModelCatalog.Result.Models -> {
                            container.providers.saveCatalog(providerId, result.models)
                            null
                        }
                        is com.androidharness.app.llm.ModelCatalog.Result.Failed -> result.message
                    }
                }
            },
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
            apiKey = container.providers::apiKey,
            onDismiss = { managingRoleProvider = null },
            onSetActive = { providerId ->
                scope.launch {
                    if (isPlan) container.settings.setPlanningModel(providerId, null)
                    else container.settings.setExecutionModel(providerId, null)
                }
                managingRoleProvider = null
            },
            onDelete = { providerId ->
                scope.launch { container.providers.delete(providerId) }
            },
            onSave = { existing, name, type, baseUrl, model, apiKey ->
                scope.launch {
                    val created = if (existing == null) {
                        container.providers.add(name, type, baseUrl, model, apiKey)
                    } else {
                        val updated = existing.copy(name = name, type = type, baseUrl = baseUrl, model = model)
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                model ?: "Provider default",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Choose $label model",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SlashCommandsSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    SettingsHeader("Slash commands")
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var snippetName by remember { mutableStateOf("") }
            var snippetBody by remember { mutableStateOf("") }
            var showAdd by remember { mutableStateOf(false) }
            TextButton(onClick = { showAdd = !showAdd }) {
                Text(if (showAdd) "Cancel" else "Add custom command")
            }
            AnimatedVisibility(showAdd) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = snippetName, onValueChange = { snippetName = it },
                        label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = snippetBody, onValueChange = { snippetBody = it },
                        label = { Text("Prompt (\$ARG for argument)") }, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = {
                        val n = snippetName.trim(); val b = snippetBody.trim()
                        if (n.isNotBlank() && b.isNotBlank()) {
                            scope.launch { container.snippets.add(n, b) }
                            snippetName = ""; snippetBody = ""; showAdd = false
                        }
                    }) { Text("Save") }
                }
            }
            val snippets by container.snippets.snippets.collectAsStateWithLifecycle(initialValue = emptyList())
            snippets.forEach { snippet ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "/${snippet.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { scope.launch { container.snippets.delete(snippet) } }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryCard(container: AppContainer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Keep runs alive", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Hold the CPU while a run or terminal is active so the terminal will not die when the app is minimized.",
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
                    Text("Battery optimization", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (exempt) "Excluded: Android will not throttle background runs."
                        else "On: Samsung and other OEMs may kill background work. Exclude the app for reliable long runs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!exempt) {
                    Button(onClick = {
                        requestBatteryExemption(context)
                        exempt = isIgnoringBatteryOptimizations(context)
                    }) { Text("Fix") }
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
private fun SettingRow(
    icon: ImageVector,
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
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
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
                    contentDescription = "Change $label",
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
    val step by container.updates.step.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val version = remember {
        runCatching {
            container.appContext.packageManager
                .getPackageInfo(container.appContext.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("Updates", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "Current version $version. Checks GitHub Releases for newer builds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    Text(if (step is com.androidharness.app.data.update.UpdateManager.Step.Checking) "Checking…" else "Check for updates")
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
                        Text("Up to date", style = MaterialTheme.typography.labelMedium)
                    }
                    is com.androidharness.app.data.update.UpdateManager.Step.Available -> {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${s.release.tag} available, see the dialog",
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

@Composable
private fun ChatsBackupSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

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
                report("Exported ${r.sessions} chats (${r.messages} messages).")
            } catch (e: ChatBackupException) {
                report(e.message ?: "Export failed.", error = true)
            } catch (e: Exception) {
                report("Export failed: ${e.message ?: "unknown error"}", error = true)
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
                val r = container.chatBackup.importFrom(uri)
                report(
                    "Imported ${r.sessions} chats (${r.messages} messages)" +
                        if (r.skipped > 0) ", ${r.skipped} already present." else ".",
                )
            } catch (e: ChatBackupException) {
                report(e.message ?: "Import failed.", error = true)
            } catch (e: Exception) {
                report("Import failed: ${e.message ?: "unknown error"}", error = true)
            } finally {
                busy = false
            }
        }
    }

    OutlinedCard(Modifier.fillMaxWidth()) {
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
                    Text("Chat backup", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Save all chats to a file, or restore them from one. " +
                            "Chats and messages only: no API keys, providers, or settings are in the file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch(suggestedName) }, enabled = !busy) {
                    Text("Export")
                }
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
                    },
                    enabled = !busy,
                ) {
                    Text("Import")
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
