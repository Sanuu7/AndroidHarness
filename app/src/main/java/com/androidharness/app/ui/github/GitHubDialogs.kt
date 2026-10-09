package com.androidharness.app.ui.github

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.automation.AutomationSchedule
import com.androidharness.app.automation.AutomationTask
import com.androidharness.app.github.*
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
private fun GitHubDialog(title: String, busy: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(12.dp).fillMaxHeight(0.9f)) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}

@Composable
fun GitHubImportDialog(container: AppContainer, onDismiss: () -> Unit, onImported: () -> Unit = onDismiss) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<String>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val environment by container.linuxEnv.state.collectAsStateWithLifecycle()
    val gitToolsReady = environment is com.androidharness.app.data.env.EnvState.Ready && container.linuxEnv.gitToolsReady
    fun load() {
        busy = true
        scope.launch {
            try {
                val next = container.githubRepositories.listRepositories(page)
                repos = (repos + next).distinct(); page++; more = next.size == 30; message = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = container.github.safeMessage(e) }
            finally { busy = false }
        }
    }
    GitHubDialog("Import from GitHub", busy, onDismiss) {
        Text("Clone a repository into its own private workspace. Public repositories can be imported without signing in.",
            style = MaterialTheme.typography.bodySmall)
        if (!gitToolsReady) {
            Text("Install Git tools once to import repositories on this device.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                busy = true; message = "Installing Git tools…"
                scope.launch {
                    try {
                        container.linuxEnv.install(container.linuxEnv.corePackages)
                        check(container.linuxEnv.gitToolsReady) { (container.linuxEnv.state.value as? com.androidharness.app.data.env.EnvState.Failed)?.message ?: "Git tools could not be installed. Retry the installation." }
                        message = "Git tools ready."
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = container.github.safeMessage(e) }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Install Git tools") }
        }
        OutlinedTextField(input, onValueChange = { input = it }, label = { Text("Repository URL or owner/repository") },
            enabled = !busy, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            busy = true; message = "Importing repository…"
            scope.launch {
                try { container.githubRepositories.importRepository(input); onImported() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = container.github.safeMessage(e) }
                finally { busy = false }
            }
        }, enabled = !busy && gitToolsReady && input.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Import repository") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
        OutlinedButton(onClick = ::load, enabled = !busy && more, modifier = Modifier.fillMaxWidth()) {
            Text(if (repos.isEmpty()) "Browse my repositories" else "Load more repositories")
        }
        repos.forEach { repo ->
            OutlinedButton(onClick = { input = repo }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(repo) }
        }
    }
}

/** Entry point from chat; both OAuth and PAT connections share the same controls. */
@Composable
fun GitHubWorkspaceDialog(container: AppContainer, projectId: String, projectName: String,
    onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val connection by container.github.state.collectAsStateWithLifecycle()
    if (connection.login == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Sign in to GitHub") },
            text = { Text("You haven't connected GitHub yet. Sign in with GitHub or add a personal access token in Settings to import private repositories and publish your work.") },
            confirmButton = {
                TextButton(onClick = { onDismiss(); onOpenSettings() }) { Text("Sign in") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
    } else {
        GitHubPublishDialog(container, projectId, projectName, onDismiss)
    }
}

@Composable
fun GitHubPublishDialog(container: AppContainer, projectId: String, projectName: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val environment by container.linuxEnv.state.collectAsStateWithLifecycle()
    var fs by remember(projectId) { mutableStateOf<WorkspaceFs?>(null) }
    var state by remember(projectId) { mutableStateOf<GitHubRepoState?>(null) }
    var paths by remember(projectId) { mutableStateOf<Set<String>>(emptySet()) }
    var commitMessage by remember { mutableStateOf("") }
    var repositoryInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var preset by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    suspend fun refresh() {
        // Failed refreshes must not leave controls targeting an old repository.
        fs = null
        state = null
        paths = emptySet()
        val project = container.workspace.projects.first().firstOrNull { it.id == projectId }
            ?: error("This workspace was removed.")
        val captured = container.workspace.fsFor(project)
        fs = captured
        if (!container.linuxEnv.gitToolsReady) return
        val snapshot = try { container.githubRepositories.inspect(captured) }
        catch (e: GitHubOperationFailure) {
            // A plain folder or an unconnected local repository needs setup controls, not an error.
            if (e.stage == "Repository" && (e.message.orEmpty().contains("not a git repository", ignoreCase = true) ||
                    e.message.orEmpty().contains("This workspace has no GitHub origin"))) {
                return
            }
            throw e
        }
        state = snapshot
        paths = snapshot.changes.map { it.path }.toSet()
    }
    fun act(action: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val failure = container.github.safeMessage(e)
                val previouslySelected = paths
                // A commit can succeed even when its push fails. Refresh HEAD for an immediate push retry.
                try { refresh(); paths = previouslySelected.intersect(requireNotNull(state).changes.map { it.path }.toSet()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
                message = failure
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(projectId) {
        busy = true
        try { refresh() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = container.github.safeMessage(e) }
        finally { busy = false }
    }
    if (importing) {
        GitHubImportDialog(container, onDismiss = { importing = false }, onImported = onDismiss)
        return
    }
    GitHubDialog("Commit & push", busy, onDismiss) {
        Text(projectName, style = MaterialTheme.typography.titleMedium)
        if (environment !is com.androidharness.app.data.env.EnvState.Ready || !container.linuxEnv.gitToolsReady) {
            OutlinedButton(onClick = { act {
                container.linuxEnv.install(container.linuxEnv.corePackages)
                check(container.linuxEnv.gitToolsReady) { (container.linuxEnv.state.value as? com.androidharness.app.data.env.EnvState.Failed)?.message ?: "Git tools could not be installed. Retry the installation." }
                refresh(); message = "Git tools ready."
            } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Install or repair Git tools") }
        }
        state?.let { snapshot ->
            Text(snapshot.remoteUrl.removePrefix("https://github.com/").removeSuffix(".git"))
            Text("Branch: ${snapshot.branch}", style = MaterialTheme.typography.bodySmall)
            Text("${snapshot.changes.size} changed files · app history excluded", style = MaterialTheme.typography.bodySmall)
            if (snapshot.changes.isNotEmpty()) {
                TextButton(onClick = { paths = if (paths.isEmpty()) snapshot.changes.map { it.path }.toSet() else emptySet() },
                    enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (paths.isEmpty()) "Select all files" else "Deselect all files") }
                snapshot.changes.forEach { change ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        paths = if (change.path in paths) paths - change.path else paths + change.path
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(change.path in paths, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(change.path, style = MaterialTheme.typography.bodyMedium)
                            Text(change.originalPath?.let { "Renamed from $it" } ?: change.status.trim(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedTextField(commitMessage, onValueChange = { commitMessage = it }, label = { Text("Commit message") },
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                Button(onClick = { act {
                    message = container.githubRepositories.publish(requireNotNull(fs), snapshot, paths, commitMessage, true)
                    refresh()
                } }, enabled = !busy && paths.isNotEmpty() && commitMessage.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Commit selected files & push")
                }
            } else Text("No uncommitted changes. You can push existing local commits.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { act {
                message = container.githubRepositories.publish(requireNotNull(fs), snapshot, emptySet(), "", false)
                refresh()
            } }, enabled = !busy && snapshot.head.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Push existing commits") }
            OutlinedButton(onClick = { preset = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save reusable push preset") }
        }
        if (state == null && !busy) {
            OutlinedButton(onClick = { importing = true }, modifier = Modifier.fillMaxWidth()) { Text("Import from GitHub") }
            OutlinedTextField(repositoryInput, onValueChange = { repositoryInput = it }, label = { Text("Existing GitHub repository") },
                modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { act {
                container.githubRepositories.connect(requireNotNull(fs), repositoryInput)
                refresh(); message = "Repository connected."
            } }, enabled = fs?.shellRoot != null && container.linuxEnv.gitToolsReady && repositoryInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { Text("Connect repository") }
        }
        OutlinedButton(onClick = { act { refresh(); message = null } }, enabled = !busy,
            modifier = Modifier.fillMaxWidth()) { Text("Refresh repository") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (preset && state != null) GitHubPushPresetDialog(container, projectId, projectName,
        initial = GitHubPushPreset(requireNotNull(state).remoteUrl, requireNotNull(state).branch,
            commitMessage.ifBlank { "Update from AndroidHarness" }, paths.toList()), onDismiss = { preset = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GitHubPushPresetDialog(container: AppContainer, projectId: String, projectName: String,
    onDismiss: () -> Unit, initial: GitHubPushPreset? = null, task: AutomationTask? = null) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(initial ?: task?.githubPush) }
    var title by remember { mutableStateOf(task?.title ?: "Push $projectName to GitHub") }
    var commitMessage by remember { mutableStateOf(initial?.commitMessage ?: task?.githubPush?.commitMessage ?: "Update from AndroidHarness") }
    var schedule by remember { mutableStateOf(task?.schedule ?: AutomationSchedule.MANUAL) }
    var hour by remember { mutableStateOf((task?.hour ?: 8).toString()) }
    var minute by remember { mutableStateOf((task?.minute ?: 0).toString()) }
    var allChanges by remember { mutableStateOf(task?.githubPush?.paths?.isEmpty() == true) }
    var busy by remember { mutableStateOf(snapshot == null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(projectId) {
        if (snapshot == null) {
            try {
                val project = container.workspace.projects.first().first { it.id == projectId }
                val state = container.githubRepositories.inspect(container.workspace.fsFor(project))
                snapshot = GitHubPushPreset(state.remoteUrl, state.branch, commitMessage, state.changes.map { it.path })
            } catch (e: Exception) { message = container.github.safeMessage(e) } finally { busy = false }
        }
    }
    GitHubDialog(if (task == null) "GitHub push preset" else "Edit GitHub push preset", busy, onDismiss) {
        Text("$projectName · ${snapshot?.branch.orEmpty()}", style = MaterialTheme.typography.titleMedium)
        snapshot?.let { Text(it.remoteUrl, style = MaterialTheme.typography.bodySmall) }
        Text("Reuse this repository and branch in Automation. The preset runs Git directly, checks the push, and stops if a task is working in this workspace. It uses your current GitHub connection.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(title, onValueChange = { title = it }, label = { Text("Preset name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(commitMessage, onValueChange = { commitMessage = it }, label = { Text("Commit message") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().clickable { allChanges = !allChanges }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(allChanges, onCheckedChange = null)
            Text("Include all future changes in this workspace", modifier = Modifier.weight(1f))
        }
        if (!allChanges) Text("Selected paths: ${snapshot?.paths?.joinToString(", ")?.ifBlank { "No paths selected. Enable future changes or select files in Commit & push." }}",
            style = MaterialTheme.typography.bodySmall)
        Text("Schedule", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(AutomationSchedule.MANUAL, AutomationSchedule.HOURLY, AutomationSchedule.DAILY).forEach { choice ->
                FilterChip(selected = schedule == choice, onClick = { schedule = choice }, label = { Text(choice.name.lowercase().replaceFirstChar { it.uppercase() }) })
            }
        }
        if (schedule == AutomationSchedule.DAILY) {
            OutlinedTextField(hour, onValueChange = { hour = it.filter(Char::isDigit).take(2) }, label = { Text("Hour (0–23)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(minute, onValueChange = { minute = it.filter(Char::isDigit).take(2) }, label = { Text("Minute (0–59)") }, modifier = Modifier.fillMaxWidth())
        }
        if (schedule != AutomationSchedule.MANUAL) Text("Android may delay scheduled work. Failed or blocked pushes stay visible in Automation history.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            busy = true
            scope.launch {
                try {
                    val saved = requireNotNull(snapshot).copy(commitMessage = commitMessage.trim(), paths = if (allChanges) emptyList() else requireNotNull(snapshot).paths)
                    val base = task ?: AutomationTask(title = title, prompt = "Publish reviewed changes to GitHub", projectId = projectId, projectName = projectName)
                    container.automation.save(base.copy(title = title.trim(), githubPush = saved, schedule = schedule,
                        hour = hour.toIntOrNull() ?: -1, minute = minute.toIntOrNull() ?: -1, enabled = true))
                    onDismiss()
                } catch (e: Exception) { message = container.github.safeMessage(e) } finally { busy = false }
            }
        }, enabled = !busy && snapshot != null && title.isNotBlank() && commitMessage.isNotBlank() &&
            (allChanges || snapshot?.paths?.isNotEmpty() == true), modifier = Modifier.fillMaxWidth()) { Text("Save push preset") }
        if (task != null) TextButton(onClick = { container.automation.delete(task.id); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Delete preset") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
