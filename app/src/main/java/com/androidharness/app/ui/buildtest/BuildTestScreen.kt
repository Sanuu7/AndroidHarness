package com.androidharness.app.ui.buildtest

import com.androidharness.app.R
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton

private data class SavedCommand(
    val id: String,
    val name: String,
    val command: String,
)

private data class BuildError(
    val path: String,
    val line: Int,
    val text: String,
)

private enum class RunStatus { IDLE, RUNNING, PASSED, FAILED }

private class BuildCommandStore(context: Context) {
    private val prefs = context.getSharedPreferences("build_test_commands", Context.MODE_PRIVATE)

    fun load(workspaceId: String, defaults: List<SavedCommand>): List<SavedCommand> {
        val key = "commands_$workspaceId"
        if (!prefs.contains(key)) {
            save(workspaceId, defaults)
            return defaults
        }
        return runCatching {
            val array = JSONArray(prefs.getString(key, "[]"))
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        SavedCommand(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = item.optString("name"),
                            command = item.optString("command"),
                        ),
                    )
                }
            }.filter { it.name.isNotBlank() && it.command.isNotBlank() }
        }.getOrDefault(defaults)
    }

    fun save(workspaceId: String, commands: List<SavedCommand>) {
        val array = JSONArray()
        commands.forEach { command ->
            array.put(
                JSONObject()
                    .put("id", command.id)
                    .put("name", command.name)
                    .put("command", command.command),
            )
        }
        prefs.edit().putString("commands_$workspaceId", array.toString()).apply()
    }
}

@Composable
fun BuildTestScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenFile: (String, Int) -> Unit,
    onOpenTerminal: () -> Unit,
    onFixWithAgent: (String) -> Unit,
    sessionId: String? = null,
    projectId: String? = null,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val terminal = container.terminal
    val terminalState by terminal.state.collectAsStateWithLifecycle()
    val workspace by remember(sessionId, projectId) { container.workspace.forTools(sessionId, projectId) }.collectAsStateWithLifecycle(initialValue = null)
    val project by remember(sessionId, projectId) { container.workspace.projectForTools(sessionId, projectId) }.collectAsStateWithLifecycle(initialValue = null)
    val scheme = MaterialTheme.colorScheme
    val statusColors = LocalStatusColors.current
    val store = remember { BuildCommandStore(container.appContext) }
    val outputState = rememberLazyListState()

    var commands by remember { mutableStateOf<List<SavedCommand>>(emptyList()) }
    var editing by remember { mutableStateOf<SavedCommand?>(null) }
    var adding by remember { mutableStateOf(false) }
    var activeCommand by remember { mutableStateOf<SavedCommand?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsedMs by remember { mutableLongStateOf(0L) }

    val workspaceId = project?.id
    val remote = workspace as? com.androidharness.app.workspace.SshFs
    val shellRoot = workspace?.shellRoot ?: remote?.root?.let { java.io.File(it) }
    var defaults by remember { mutableStateOf<List<SavedCommand>>(emptyList()) }
    var launchScripts by remember(workspaceId) { mutableStateOf<List<String>>(emptyList()) }
    var launchScript by remember(workspaceId) { mutableStateOf("") }
    var staticPage by remember(workspaceId) { mutableStateOf<String?>(null) }
    var launchMenu by remember { mutableStateOf(false) }
    var previewTarget by remember(workspaceId) { mutableStateOf<String?>(null) }
    var launchPending by remember(workspaceId) { mutableStateOf(false) }
    var launchError by remember(workspaceId) { mutableStateOf<String?>(null) }
    var launchReady by remember(workspaceId) { mutableStateOf(false) }
    var serverCommand by remember(workspaceId) { mutableStateOf<String?>(null) }
    val launchPrefs = remember { container.appContext.getSharedPreferences("project_launch", Context.MODE_PRIVATE) }

    LaunchedEffect(workspaceId, workspace?.displayPath) {
        val fs = workspace ?: return@LaunchedEffect
        val detected = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val packageFile = runCatching { fs.resolve("package.json") }.getOrNull()
            val scripts = if (packageFile?.exists == true && packageFile.length <= 1_000_000)
                runCatching { com.androidharness.app.core.ProjectLaunch.scripts(packageFile.readText()) }
                    .getOrDefault(emptyList()).filter { it in setOf("dev", "start", "preview") }
            else emptyList()
            scripts to listOf("index.html", "public/index.html").firstOrNull { runCatching { fs.resolve(it).isFile }.getOrDefault(false) }
        }
        launchScripts = detected.first
        launchScript = launchPrefs.getString(workspaceId, null)?.takeIf { it in launchScripts }
            ?: launchScripts.firstOrNull().orEmpty()
        staticPage = detected.second
        serverCommand = launchPrefs.getString("command_$workspaceId", null)
        launchReady = true
    }
    val launchedHere = serverCommand != null && terminalState.lastCommand == serverCommand
    LaunchedEffect(launchPending, terminalState.lines, terminalState.busy, launchedHere) {
        if (!launchPending || !launchedHere) return@LaunchedEffect
        val url = com.androidharness.app.core.ProjectLaunch.previewUrl(terminalState.lines.joinToString("\n"))
        if (url != null && remote == null) { previewTarget = url; launchPending = false }
        else if (!terminalState.busy) {
            launchPending = false
            launchError = uiContext.getString(R.string.ui_project_stopped_before_reporting_a_preview_url)
        }
    }
    previewTarget?.let { target ->
        com.androidharness.app.ui.chat.components.WebPreviewSheet(initialTarget = target,
            workspace = workspace, browserController = container.browser,
            onSendPrompt = onFixWithAgent, onDismiss = { previewTarget = null })
    }

    LaunchedEffect(workspaceId) {
        val id = workspaceId ?: return@LaunchedEffect
        defaults = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { defaultCommands(workspace) }
        commands = store.load(id, defaults)
        activeCommand = null
    }
    LaunchedEffect(workspace?.displayPath) { workspace?.let { terminal.useWorkspace(it) }; terminal.ensureStarted() }
    LaunchedEffect(terminalState.lines.size) {
        if (terminalState.lines.isNotEmpty()) {
            outputState.scrollToItem(terminalState.lines.lastIndex)
        }
    }
    LaunchedEffect(terminalState.busy, startedAt) {
        if (startedAt == 0L) return@LaunchedEffect
        while (terminalState.busy) {
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
            delay(200)
        }
        elapsedMs = SystemClock.elapsedRealtime() - startedAt
    }

    val runStatus = when {
        activeCommand == null -> RunStatus.IDLE
        terminalState.busy -> RunStatus.RUNNING
        terminalState.lastExitCode == 0 -> RunStatus.PASSED
        terminalState.lastExitCode != null -> RunStatus.FAILED
        else -> RunStatus.IDLE
    }
    val errors = remember(terminalState.lines, workspace?.displayPath) {
        parseErrors(terminalState.lines, workspace)
    }

    fun saveCommands(next: List<SavedCommand>) {
        commands = next
        workspaceId?.let { store.save(it, next) }
    }

    fun run(command: SavedCommand) {
        val root = shellRoot ?: return
        workspace?.let { terminal.useWorkspace(it) }
        terminal.clear()
        activeCommand = command
        startedAt = SystemClock.elapsedRealtime()
        elapsedMs = 0L
        val shellCommand = "cd ${shellQuote(root.absolutePath)} && ${command.command}"
        terminal.send(shellCommand, workspace)
        workspaceId?.let { container.projectChecks.track(it, command.name, shellCommand) }
    }

    Scaffold(
        containerColor = scheme.surface,
        topBar = {
            AppHeader(
                title = uiContext.getString(R.string.ui_build_test),
                subtitle = project?.name ?: uiContext.getString(R.string.ui_project_checks),
                onBack = onBack,
                actions = {
                    IconButton(onClick = onOpenTerminal) {
                        Icon(Icons.Outlined.Terminal, contentDescription = uiContext.getString(R.string.ui_open_terminal))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                RunHeroCard(
                    status = runStatus,
                    command = activeCommand,
                    exitCode = terminalState.lastExitCode,
                    elapsedMs = elapsedMs,
                    workspacePath = workspace?.displayPath.orEmpty(),
                    commandCount = commands.size,
                    errorCount = errors.size,
                    statusColor = when (runStatus) {
                        RunStatus.PASSED -> statusColors.success
                        RunStatus.FAILED -> scheme.error
                        RunStatus.RUNNING -> scheme.primary
                        RunStatus.IDLE -> scheme.onSurfaceVariant
                    },
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(uiContext.getString(R.string.ui_run_preview), style = MaterialTheme.typography.titleSmall)
                        Text(when {
                            !launchReady -> uiContext.getString(R.string.ui_checking_project_files)
                            launchedHere && terminalState.busy -> uiContext.getString(R.string.ui_development_server_is_running_in_terminal)
                            remote != null -> uiContext.getString(R.string.ui_run_scripts_on_the_ssh_host_remote)
                            launchScripts.isNotEmpty() -> uiContext.getString(R.string.ui_runs_your_project_script_then_opens_the)
                            staticPage != null -> uiContext.getString(R.string.ui_open_this_html_project_without_installing_a)
                            else -> uiContext.getString(R.string.ui_no_dev_start_or_preview_script_found)
                        }, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        if (launchScripts.isNotEmpty()) Box {
                            OutlinedButton(onClick = { launchMenu = true }, enabled = !terminalState.busy) {
                                Text(uiContext.getString(R.string.ui_script, (launchScript).toString()), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = launchMenu, onDismissRequest = { launchMenu = false }) {
                                launchScripts.forEach { script -> DropdownMenuItem(text = { Text(script) }, onClick = {
                                    launchScript = script; launchMenu = false
                                    launchPrefs.edit().putString(workspaceId, script).apply()
                                }) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = (!terminalState.busy && launchScript.isNotBlank() && shellRoot != null) ||
                                (launchScripts.isEmpty() && staticPage != null), onClick = {
                                launchError = null
                                if (launchScripts.isEmpty()) previewTarget = staticPage else {
                                    val root = shellRoot ?: return@Button
                                    val npmCommand = if (remote == null && container.linuxEnv.bashExecutable() != null &&
                                        File(container.linuxEnv.prefix, "lib/node_modules/npm/bin/npm-cli.js").isFile) {
                                        val preload = File(container.linuxEnv.prefix, "etc/project-npm.cjs")
                                        preload.parentFile?.mkdirs()
                                        preload.writeText(com.androidharness.app.core.NpmLaunchShell.preload)
                                        "node --require ${shellQuote(preload.absolutePath)} ${shellQuote(File(container.linuxEnv.prefix, "lib/node_modules/npm/bin/npm-cli.js").absolutePath)} run ${shellQuote(launchScript)}"
                                    } else com.androidharness.app.core.ProjectLaunch.npmCommand(launchScript)
                                    val command = "cd ${shellQuote(root.absolutePath)} && ( " +
                                        com.androidharness.app.core.ProjectLaunch.prepare(npmCommand, true) + " )"
                                    terminal.clear(); activeCommand = null; serverCommand = command
                                    launchPrefs.edit().putString("command_$workspaceId", command).apply()
                                    launchPending = remote == null
                                    terminal.send(command, workspace)
                                }
                            }) { Text(if (launchScripts.isEmpty()) uiContext.getString(R.string.ui_open_preview) else uiContext.getString(R.string.ui_run_project)) }
                            if (launchedHere && terminalState.busy) {
                                TextButton(onClick = { launchPending = false; terminal.stopTerminal(); serverCommand = null }) { Text(uiContext.getString(R.string.settings_action_stop)) }
                            }
                        }
                        if (launchedHere && terminalState.busy && remote == null) {
                            val url = com.androidharness.app.core.ProjectLaunch.previewUrl(terminalState.lines.joinToString("\n"))
                            if (url != null) TextButton(onClick = { previewTarget = url }) { Text(uiContext.getString(R.string.ui_open_preview)) }
                            else Text(uiContext.getString(R.string.ui_waiting_for_a_localhost_url_in_server),
                                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                        launchError?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(uiContext.getString(R.string.ui_saved_commands), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            uiContext.getString(R.string.ui_one_tap_checks_for_this_workspace),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { adding = true }) { Text(uiContext.getString(R.string.ui_add)) }
                }
            }

            if (commands.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = scheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            uiContext.getString(R.string.ui_no_saved_checks_yet_add_the_command),
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(commands, key = { it.id }) { command ->
                    CommandCard(
                        command = command,
                        running = terminalState.busy && activeCommand?.id == command.id,
                        enabled = shellRoot != null && !terminalState.busy,
                        onRun = { run(command) },
                        onEdit = { editing = command },
                        onDelete = { saveCommands(commands.filterNot { it.id == command.id }) },
                    )
                }
            }

            if (shellRoot == null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = statusColors.warning.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, statusColors.warning.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            uiContext.getString(R.string.ui_this_workspace_does_not_expose_a_shell),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (errors.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            uiContext.getString(R.string.ui_errors),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        errors.take(8).forEach { error ->
                            ErrorRow(error = error, onClick = { onOpenFile(error.path, error.line) })
                        }
                    }
                }
            }

            item {
                LiveOutputCard(
                    lines = terminalState.lines,
                    listState = outputState,
                    busy = terminalState.busy,
                    onClear = { terminal.clear() },
                )
            }

            if (runStatus == RunStatus.FAILED && activeCommand != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = scheme.errorContainer.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(uiContext.getString(R.string.ui_let_the_agent_fix_this), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                uiContext.getString(R.string.ui_the_failed_command_and_recent_output_will),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    onFixWithAgent(
                                        buildFixPrompt(
                                            workspace = workspace?.displayPath.orEmpty(),
                                            command = activeCommand!!.command,
                                            exitCode = terminalState.lastExitCode,
                                            lines = terminalState.lines,
                                        ),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(uiContext.getString(R.string.ui_fix_with_agent_1581))
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    val dialogCommand = editing
    if (adding || dialogCommand != null) {
        CommandDialog(
            existing = dialogCommand,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { name, command ->
                val next = if (dialogCommand == null) {
                    commands + SavedCommand(UUID.randomUUID().toString(), name, command)
                } else {
                    commands.map { if (it.id == dialogCommand.id) it.copy(name = name, command = command) else it }
                }
                saveCommands(next)
                adding = false
                editing = null
            },
        )
    }
}

@Composable
private fun RunHeroCard(
    status: RunStatus,
    command: SavedCommand?,
    exitCode: Int?,
    elapsedMs: Long,
    workspacePath: String,
    commandCount: Int,
    errorCount: Int,
    statusColor: Color,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = statusColor.copy(alpha = 0.14f)) {
                    Icon(
                        if (status == RunStatus.FAILED) Icons.Outlined.Close else Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.padding(10.dp).size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (status) {
                            RunStatus.IDLE -> uiContext.getString(R.string.ui_ready_for_a_clean_run)
                            RunStatus.RUNNING -> uiContext.getString(R.string.ui_running_1583, (command?.name.orEmpty()).toString())
                            RunStatus.PASSED -> "${command?.name.orEmpty()} passed"
                            RunStatus.FAILED -> "${command?.name.orEmpty()} failed"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        workspacePath.ifBlank { uiContext.getString(R.string.ui_workspace_loading) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = HarnessMono,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricPill(uiContext.getString(R.string.ui_commands), commandCount.toString(), Modifier.weight(1f))
                MetricPill(uiContext.getString(R.string.ui_errors), errorCount.toString(), Modifier.weight(1f))
                MetricPill(
                    uiContext.getString(R.string.ui_result),
                    when (status) {
                        RunStatus.IDLE -> uiContext.getString(R.string.ui_ready)
                        RunStatus.RUNNING -> formatDuration(elapsedMs)
                        RunStatus.PASSED -> formatDuration(elapsedMs)
                        RunStatus.FAILED -> exitCode?.let { uiContext.getString(R.string.ui_exit, (it).toString()) } ?: uiContext.getString(R.string.ui_failed_64)
                    },
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CommandCard(
    command: SavedCommand,
    running: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var menuOpen by remember(command.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = scheme.primaryContainer) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = scheme.onPrimaryContainer,
                    modifier = Modifier.padding(8.dp).size(17.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    command.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    command.command,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = HarnessMono,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onRun,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 38.dp),
            ) {
                Text(if (running) uiContext.getString(R.string.ui_running_1587) else uiContext.getString(R.string.ui_run))
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = uiContext.getString(R.string.ui_command_options), modifier = Modifier.size(19.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(uiContext.getString(R.string.settings_action_edit)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(uiContext.getString(R.string.settings_action_delete)) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorRow(error: BuildError, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = scheme.errorContainer.copy(alpha = 0.34f),
        border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.18f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(
                "${error.path}:${error.line}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = HarnessMono,
                color = scheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                error.text,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LiveOutputCard(
    lines: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    busy: Boolean,
    onClear: () -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = if (busy) scheme.primary else scheme.outline) {
                    Box(Modifier.size(8.dp))
                }
                Spacer(Modifier.width(9.dp))
                Text(uiContext.getString(R.string.ui_live_output), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear) { Text(uiContext.getString(R.string.ui_clear)) }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp, max = 280.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (lines.isEmpty()) {
                    item {
                        Text(
                            uiContext.getString(R.string.ui_run_a_saved_command_and_its_output),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(lines) { line ->
                        Text(
                            stripAnsi(line),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = HarnessMono,
                            color = when {
                                line.startsWith("$ ") || line.startsWith("# ") -> scheme.primary
                                line.contains("error", ignoreCase = true) || line.contains("failed", ignoreCase = true) -> scheme.error
                                else -> scheme.onSurface
                            },
                            softWrap = false,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun CommandDialog(
    existing: SavedCommand?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var command by remember(existing?.id) { mutableStateOf(existing?.command.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) uiContext.getString(R.string.ui_add_command) else uiContext.getString(R.string.ui_edit_command)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(uiContext.getString(R.string.settings_mcp_name)) },
                    placeholder = { Text(uiContext.getString(R.string.ui_unit_tests)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(uiContext.getString(R.string.settings_mcp_command)) },
                    placeholder = { Text("./gradlew test") },
                    minLines = 2,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = HarnessMono),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), command.trim()) },
                enabled = name.isNotBlank() && command.isNotBlank(),
            ) { Text(uiContext.getString(R.string.settings_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiContext.getString(R.string.settings_action_cancel)) } },
    )
}

private fun defaultCommands(workspace: WorkspaceFs?): List<SavedCommand> {
    if (workspace == null) return emptyList()
    val hasGradle = runCatching { workspace.resolve("gradlew").exists }.getOrDefault(false)
    val hasNpm = runCatching { workspace.resolve("package.json").exists }.getOrDefault(false)
    return buildList {
        if (hasGradle) {
            add(saved("Tests", "./gradlew test"))
            add(saved("Debug APK", "./gradlew assembleDebug"))
            add(saved("Lint", "./gradlew lint"))
        }
        if (hasNpm) {
            val node = workspace.resolve("package.json")
            val scripts = if (node.length <= 1_000_000) runCatching {
                com.androidharness.app.core.ProjectLaunch.scripts(node.readText())
            }.getOrDefault(emptyList()) else emptyList()
            scripts.filter { it !in setOf("dev", "start", "preview") }.forEach { script ->
                add(saved("npm $script", com.androidharness.app.core.ProjectLaunch.npmCommand(script)))
            }
        }
    }
}

private fun saved(name: String, command: String) =
    SavedCommand(UUID.randomUUID().toString(), name, command)

private fun parseErrors(lines: List<String>, workspace: WorkspaceFs?): List<BuildError> {
    if (workspace == null) return emptyList()
    val root = (workspace as? com.androidharness.app.workspace.SshFs)?.root ?: workspace.shellRoot?.canonicalPath
    val fileUri = Regex("file://(.+?):(\\d+)(?::\\d+)?")
    val colon = Regex("((?:[A-Za-z]:)?[^\\s:()]+\\.(?:kt|kts|java|xml|gradle|js|jsx|ts|tsx|py|c|cc|cpp|h|hpp)):(\\d+)(?::\\d+)?")
    val paren = Regex("([^\\s()]+\\.(?:kt|kts|java|xml|gradle|js|jsx|ts|tsx|py|c|cc|cpp|h|hpp))\\((\\d+)(?:,\\d+)?\\)")
    return lines.asSequence()
        .mapNotNull { raw ->
            val line = stripAnsi(raw)
            val match = fileUri.find(line) ?: colon.find(line) ?: paren.find(line) ?: return@mapNotNull null
            val rawPath = match.groupValues[1]
            val lineNumber = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val cleanPath = rawPath.removePrefix("file://")
            val relative = when {
                root != null && File(cleanPath).isAbsolute -> runCatching {
                    val file = File(cleanPath).canonicalFile
                    val rootFile = File(root).canonicalFile
                    file.relativeTo(rootFile).path
                }.getOrNull()
                else -> cleanPath.removePrefix("./")
            } ?: return@mapNotNull null
            val exists = runCatching { workspace.resolve(relative).exists }.getOrDefault(false)
            if (!exists) return@mapNotNull null
            BuildError(relative, lineNumber, line.trim())
        }
        .distinctBy { it.path to it.line }
        .take(20)
        .toList()
}

private fun buildFixPrompt(
    workspace: String,
    command: String,
    exitCode: Int?,
    lines: List<String>,
): String {
    val output = lines.takeLast(120).joinToString("\n") { stripAnsi(it) }.takeLast(12_000)
    return """
        A build or test command failed in this workspace. Inspect the failure, fix the root cause, then rerun the relevant check to verify it.

        Workspace: $workspace
        Command: $command
        Exit code: ${exitCode ?: "unknown"}

        Recent output:
        $output
    """.trimIndent()
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

private fun stripAnsi(value: String): String =
    value.replace(Regex("\\u001B\\[[;\\d]*[ -/]*[@-~]"), "")

private fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> "${"%.1f".format(ms / 1_000.0)}s"
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1_000}s"
}
