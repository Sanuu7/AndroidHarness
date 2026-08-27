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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.chat.components.FullAccessOrange
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.data.env.ShizukuState
import com.androidharness.app.data.env.UserServiceState
import com.androidharness.app.ui.common.formatTokenCount
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.common.AddWorkspaceDialog
import com.androidharness.app.ui.common.SystemGrants
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val CONTEXT_PRESETS = listOf(131_072, 262_144, 400_000, 1_000_000, 2_000_000)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenStats: () -> Unit = {},
    onRunSetup: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
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

    var showAddWorkspace by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ProjectEntity?>(null) }

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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

            GitHubSection(container)

            AppearanceSection(container = container, settings = settings, scope = scope)
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
    LinuxEnvironmentCard(container = container, envState = envState)
}

// ---------------------------------------------------------------------------
// GitHub
// ---------------------------------------------------------------------------

@Composable
private fun GitHubSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var hasToken by remember { mutableStateOf(container.keys.githubToken() != null) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    SettingsHeader("GitHub")
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
                    Text("Personal access token", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (hasToken)
                            "Stored in the app's encrypted settings and re-materialized into the " +
                                "toolchain (~/.gh-token + git credential rewrite) on every start — " +
                                "toolchain reinstalls no longer lose it."
                        else
                            "Enables push, PRs and private repos over HTTPS. Public clones work " +
                                "anonymously without one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusText(if (hasToken) "Set" else "Off", ok = hasToken)
            }

            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("ghp_… / github_pat_…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Fine-grained or classic PAT with Contents read/write for the repos you push to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                container.keys.putGitHubToken(draft)
                                container.refreshGitHubAuth()
                                hasToken = true
                                editing = false
                                draft = ""
                                status = "Saved and materialized into the toolchain."
                            }
                        },
                        enabled = draft.isNotBlank(),
                    ) { Text("Save") }
                    TextButton(onClick = {
                        editing = false
                        draft = ""
                    }) { Text("Cancel") }
                }
            } else {
                Text(
                    "The toolchain's git config rewrites every https://github.com URL with the token: " +
                        "credential helpers cannot exec on Android, so URL rewriting is the only " +
                        "transport that always works.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasToken) {
                        OutlinedButton(onClick = {
                            verifying = true
                            status = null
                            scope.launch(Dispatchers.IO) {
                                val token = container.keys.githubToken().orEmpty()
                                status = runCatching {
                                    val req = okhttp3.Request.Builder()
                                        .url("https://api.github.com/user")
                                        .header("Authorization", "Bearer $token")
                                        .header("Accept", "application/vnd.github+json")
                                        .build()
                                    okhttp3.OkHttpClient.Builder()
                                        .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                        .build()
                                        .newCall(req).execute().use { resp ->
                                            val body = resp.body?.string().orEmpty()
                                            if (!resp.isSuccessful) "Verify failed: HTTP ${resp.code}"
                                            else {
                                                val login = Regex("\"login\"\\s*:\\s*\"([^\"]+)\"")
                                                    .find(body)?.groupValues?.get(1)
                                                if (login != null) "Verified as $login ✓"
                                                else "Token accepted (HTTP ${resp.code}) ✓"
                                            }
                                        }
                                }.getOrElse { "Verify failed: ${it.message}" }
                                verifying = false
                            }
                        }) { Text(if (verifying) "Verifying…" else "Verify") }
                        OutlinedButton(onClick = {
                            editing = true
                            draft = ""
                        }) { Text("Replace") }
                        OutlinedButton(onClick = { confirmRemove = true }) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Button(onClick = {
                            editing = true
                            draft = ""
                        }) { Text("Add token") }
                    }
                }
            }
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove GitHub token?") },
            text = {
                Text(
                    "The stored token is deleted, and the toolchain copies (~/.gh-token and the " +
                        "credential rewrite in git config) are cleared. Push and private-repo " +
                        "access stop working until a new token is added.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch {
                        container.keys.removeGitHubToken()
                        container.refreshGitHubAuth()
                        hasToken = false
                        status = "Token removed and toolchain copies cleared."
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            },
        )
    }
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
                            "Packages: ${installed.joinToString(", ")}",
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                is com.androidharness.app.data.env.EnvState.Installing -> {
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
}

// ---------------------------------------------------------------------------
// Agent / Appearance / slash commands
// ---------------------------------------------------------------------------

/**
 * Read-only snapshot of what's actually driving requests right now. Model and
 * thinking changed from the chat header (picker + overflow), so they show
 * here as status, not settings — keeps this screen single-purpose.
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
(Commands to build, run, and test — plain names, e.g. `gradlew assembleDebug`.)

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
                    ) { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
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
                            "${s.release.tag} available — see the dialog",
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
