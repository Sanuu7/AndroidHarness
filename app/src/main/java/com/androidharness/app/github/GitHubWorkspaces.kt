package com.androidharness.app.github

import com.androidharness.app.AppContainer
import com.androidharness.app.workspace.FileFs
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Phone-owned clones use private app storage and need no broad storage grant. */
class GitHubWorkspaces(private val c: AppContainer) {
    private val imports = Mutex()
    private val root = File(c.appContext.filesDir, "github-projects")

    private fun requireGitTools() = check(c.linuxEnv.gitToolsReady) {
        "Install or repair Git tools from Import from GitHub or Commit & push, then retry."
    }

    private fun repository(fs: WorkspaceFs): GitHubRepository {
        check(fs.shellRoot != null) {
            "These controls need a device workspace with shell access. Import the repository, or configure Git and credentials on your SSH host."
        }
        requireGitTools()
        return GitHubRepository { args ->
            val result = c.shellRouter.runWorkspace(gitCommand(args), fs, timeoutMs = 180_000, maxOutput = 256_000)
            // Never commit using an incomplete file list.
            if (result.rawOutput.length >= 256_000 || result.rawStderr.length >= 256_000)
                error("Git output is too large. Reduce the changes and refresh before publishing.")
            GitCommandResult(result.exitCode, result.rawOutput, result.rawStderr +
                if (result.timedOut) "\nCommand timed out. Check repository status before retrying." else "")
        }
    }

    suspend fun inspect(fs: WorkspaceFs): GitHubRepoState = repository(fs).inspect()

    suspend fun connect(fs: WorkspaceFs, url: String) = c.runManager.withIdleWorkspace(fs) {
        repository(fs).connect(url)
    }

    private suspend fun checkAccess(url: String, token: String) {
        val name = gitHubRepositoryUrl(url).removePrefix("https://github.com/").removeSuffix(".git")
        val repo = c.github.api.get("/repos/$name", token).jsonObject
        check(repo["archived"]?.jsonPrimitive?.booleanOrNull != true) { "This GitHub repository is archived. Unarchive it before publishing." }
        check((repo["permissions"] as? JsonObject)?.get("push")?.jsonPrimitive?.booleanOrNull != false) {
            "This GitHub account cannot push to $name. Ask for write access or connect a repository you can write to."
        }
    }

    suspend fun publish(fs: WorkspaceFs, state: GitHubRepoState, paths: Set<String>, message: String, commit: Boolean): String =
        c.runManager.withIdleWorkspace(fs) {
            c.github.withConnection { token ->
                check(token != null) { "Connect GitHub in Settings before publishing." }
                checkAccess(state.remoteUrl, token)
                c.refreshGitHubAuth()
                repository(fs).publish(state, paths, message, commit)
            }
        }

    suspend fun pushPreset(fs: WorkspaceFs, preset: GitHubPushPreset): String = c.runManager.withIdleWorkspace(fs) {
        c.github.withConnection { token ->
            check(token != null) { "Connect GitHub in Settings before publishing." }
            checkAccess(preset.remoteUrl, token)
            c.refreshGitHubAuth()
            repository(fs).runPreset(preset)
        }
    }

    suspend fun listRepositories(page: Int = 1): List<String> {
        val token = c.github.accessToken() ?: error("Sign in to list your repositories. A public repository can also be imported by URL.")
        return c.github.api.get("/user/repos?sort=updated&per_page=30&page=$page", token).jsonArray
            .mapNotNull { (it as? JsonObject)?.string("full_name") }
    }

    suspend fun importRepository(input: String): String = imports.withLock { withContext(Dispatchers.IO) {
        val url = gitHubRepositoryUrl(input)
        requireGitTools()
        check(root.isDirectory || root.mkdirs()) { "Could not create the repository storage folder." }
        val name = url.substringAfterLast('/').removeSuffix(".git")
        val folder = File(root, "$name-${UUID.randomUUID().toString().take(8)}")
        // Clone to a new folder only. Failed imports are isolated and never replace an existing project.
        var registered = false
        try {
            val result = c.github.withConnection {
                c.refreshGitHubAuth()
                c.shellRouter.runWorkspace(gitCommand(listOf("clone", "--", url, folder.absolutePath)),
                    FileFs(root), timeoutMs = 600_000, maxOutput = 32_000)
            }
            if (result.exitCode != 0 || result.timedOut) {
                throw GitHubOperationFailure("Import", gitHubFailureHint(result.exitCode, result.rawStderr + result.rawOutput))
            }
            currentCoroutineContext().ensureActive()
            // Registration must finish once started, so cancellation cannot
            // leave a registered workspace pointing at a deleted clone.
            withContext(NonCancellable) {
                c.workspace.addShellProject(folder.absolutePath)
                registered = true
            }
            "Imported $name into its own workspace."
        } finally {
            if (!registered) withContext(NonCancellable) { folder.deleteRecursively() }
        }
    } }
}
