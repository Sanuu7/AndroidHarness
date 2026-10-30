package com.androidharness.app.ui.github

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.github.gitHubRepositoryUrl
import com.androidharness.app.ui.theme.HarnessGlassAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Key this picker by account: requests and selections belong to that connection. */
@Composable
internal fun GitHubImportPicker(
    login: String?,
    loadRepositories: suspend (Int) -> List<String>,
    importRepository: suspend (String, (String) -> Unit) -> String,
    errorMessage: (Exception) -> String,
    onImported: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var repositories by remember { mutableStateOf(emptyList<String>()) }
    var page by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var showUrl by remember { mutableStateOf(login == null) }
    var pending by remember { mutableStateOf<String?>(null) }
    var importedProject by remember { mutableStateOf<String?>(null) }
    var importedRepository by remember { mutableStateOf("") }

    suspend fun load() {
        if (loading) return
        loading = true
        loadError = null
        try {
            val next = loadRepositories(page)
            repositories = (repositories + next).distinct()
            page++
            more = next.size == 30
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { loadError = errorMessage(e) }
        finally { loading = false }
    }
    LaunchedEffect(login) { if (login != null) load() }

    fun confirm(repository: String) {
        importError = null
        try {
            pending = gitHubRepositoryUrl(repository).removePrefix("https://github.com/").removeSuffix(".git")
        } catch (e: Exception) { importError = errorMessage(e) }
    }

    GitHubDialog(stringResource(R.string.settings_github_import), importing, onDismiss, footer = {
        if (status != null || importError != null) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            importError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }) {
        Text(stringResource(R.string.github_import_choose), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.github_import_destination), style = MaterialTheme.typography.bodySmall)
        if (importedProject != null) {
            Text(stringResource(R.string.github_import_success, importedRepository))
            Button(onClick = {
                try { onImported(requireNotNull(importedProject)) }
                catch (e: Exception) { importError = errorMessage(e) }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.github_import_open_workspace))
            }
        } else {
            if (login != null) TextButton(onClick = { showUrl = !showUrl }, enabled = !importing) {
                Text(stringResource(R.string.github_import_by_url))
            }
            if (showUrl) {
                OutlinedTextField(input, onValueChange = { input = it },
                    label = { Text(stringResource(R.string.ui_repository_url_or_owner_repository)) },
                    enabled = !importing, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { confirm(input) }, enabled = !importing && input.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_import_repository)) }
                if (login != null) HorizontalDivider()
            }
            if (login != null) {
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.github_import_loading), style = MaterialTheme.typography.bodySmall)
                }
                repositories.forEach { repository ->
                    OutlinedButton(onClick = { confirm(repository) }, enabled = !importing,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(repository, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!loading && loadError == null && repositories.isEmpty()) {
                    Text(stringResource(R.string.github_import_empty), style = MaterialTheme.typography.bodySmall)
                }
                loadError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { scope.launch { load() } }, enabled = !loading && !importing) {
                        Text(stringResource(R.string.ui_retry))
                    }
                }
                if (more) OutlinedButton(onClick = { scope.launch { load() } }, enabled = !loading && !importing,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_load_more_repositories)) }
            }
        }
    }
    pending?.let { repository ->
        HarnessGlassAlertDialog(onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.github_import_confirm_title)) },
            text = { Text(stringResource(R.string.github_import_confirm_body, repository)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    importing = true
                    importError = null
                    status = context.getString(R.string.ui_importing_repository)
                    scope.launch {
                        try {
                            val project = importRepository(repository) { status = it }
                            // Keep the completed clone if opening it fails; retrying must not clone again.
                            importedRepository = repository
                            importedProject = project
                            onImported(project)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { importError = errorMessage(e) }
                        finally { importing = false; status = null }
                    }
                }) { Text(stringResource(R.string.ui_import_repository)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.settings_action_cancel)) } })
    }
}
