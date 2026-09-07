package com.androidharness.app.ui.buildtest

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

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
) {
    val terminal = container.terminal
    val terminalState by terminal.state.collectAsStateWithLifecycle()
    val workspace by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)
    val project by container.workspace.currentProject.collectAsStateWithLifecycle(initialValue = null)
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
    val shellRoot = workspace?.shellRoot
    val defaults = remember(workspace?.displayPath) { defaultCommands(workspace) }

    LaunchedEffect(workspaceId) {
        val id = workspaceId ?: return@LaunchedEffect
        commands = store.load(id, defaults)
        activeCommand = null
    }
    LaunchedEffect(Unit) { terminal.ensureStarted() }
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
        terminal.clear()
        activeCommand = command
        startedAt = SystemClock.elapsedRealtime()
        elapsedMs = 0L
        terminal.send("cd ${shellQuote(root.absolutePath)} && ${command.command}")
    }

    Scaffold(
        containerColor = scheme.surface,
        topBar = {
            AppHeader(
                title = "Build & Test",
                subtitle = project?.name ?: "Project checks",
                onBack = onBack,
                actions = {
                    IconButton(onClick = onOpenTerminal) {
                        Icon(Icons.Outlined.Terminal, contentDescription = "Open terminal")
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Saved commands", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "One tap checks for this workspace",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { adding = true }) { Text("Add") }
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
                            "No saved checks yet. Add the command you use to test or build this project.",
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
                            "This workspace does not expose a shell path, so build commands cannot run here.",
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
                            "Errors",
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
                            Text("Let the agent fix this", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "The failed command and recent output will be sent into a fresh agent chat.",
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
                                Text("Fix with agent")
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
                            RunStatus.IDLE -> "Ready for a clean run"
                            RunStatus.RUNNING -> "Running ${command?.name.orEmpty()}"
                            RunStatus.PASSED -> "${command?.name.orEmpty()} passed"
                            RunStatus.FAILED -> "${command?.name.orEmpty()} failed"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        workspacePath.ifBlank { "Workspace loading" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricPill("Commands", commandCount.toString(), Modifier.weight(1f))
                MetricPill("Errors", errorCount.toString(), Modifier.weight(1f))
                MetricPill(
                    "Result",
                    when (status) {
                        RunStatus.IDLE -> "Ready"
                        RunStatus.RUNNING -> formatDuration(elapsedMs)
                        RunStatus.PASSED -> formatDuration(elapsedMs)
                        RunStatus.FAILED -> exitCode?.let { "Exit $it" } ?: "Failed"
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
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onRun,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 38.dp),
            ) {
                Text(if (running) "Running" else "Run")
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Command options", modifier = Modifier.size(19.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
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
                fontFamily = FontFamily.Monospace,
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
                Text("Live output", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Clear") }
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
                            "Run a saved command and its output will appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(lines) { line ->
                        Text(
                            stripAnsi(line),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
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
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var command by remember(existing?.id) { mutableStateOf(existing?.command.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add command" else "Edit command") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("Unit tests") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Command") },
                    placeholder = { Text("./gradlew test") },
                    minLines = 2,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), command.trim()) },
                enabled = name.isNotBlank() && command.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
            add(saved("npm test", "npm test"))
            add(saved("npm build", "npm run build"))
        }
        if (isEmpty()) {
            add(saved("Tests", "./gradlew test"))
            add(saved("Debug build", "./gradlew assembleDebug"))
            add(saved("npm test", "npm test"))
        }
    }
}

private fun saved(name: String, command: String) =
    SavedCommand(UUID.randomUUID().toString(), name, command)

private fun parseErrors(lines: List<String>, workspace: WorkspaceFs?): List<BuildError> {
    if (workspace == null) return emptyList()
    val root = workspace.shellRoot?.canonicalPath
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
