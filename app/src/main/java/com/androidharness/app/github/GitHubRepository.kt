package com.androidharness.app.github

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
data class GitHubPushPreset(val remoteUrl: String, val branch: String, val commitMessage: String,
    val paths: List<String> = emptyList())
data class GitChange(val path: String, val status: String, val originalPath: String? = null)
data class GitHubRepoState(val remoteUrl: String, val branch: String, val head: String,
    val changes: List<GitChange>)
data class GitCommandResult(val code: Int, val out: String, val err: String = "")
class GitHubOperationFailure(val stage: String, detail: String) : Exception("$stage: $detail")

/** Accept only a GitHub repository address, without embedded credentials or shell syntax. */
fun gitHubRepositoryUrl(input: String): String {
    val path = input.trim().removePrefix("https://github.com/").removePrefix("git@github.com:")
        .removeSuffix("/").removeSuffix(".git")
    require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(path) &&
        path.split('/').none { it == "." || it == ".." }) { "Enter a GitHub repository as owner/repository or its GitHub URL." }
    return "https://github.com/$path.git"
}

fun gitQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
fun gitCommand(args: List<String>): String = "GIT_TERMINAL_PROMPT=0 GIT_LITERAL_PATHSPECS=1 git -c safe.directory='*' -c gc.auto=0 -c maintenance.auto=false " + args.joinToString(" ", transform = ::gitQuote)

internal fun parseGitChanges(output: String): List<GitChange> {
    val parts = output.split('\u0000')
    val result = mutableListOf<GitChange>()
    var i = 0
    while (i < parts.size) {
        val entry = parts[i++]
        if (entry.isEmpty()) continue
        require(entry.length >= 4 && entry[2] == ' ') { "Git returned an incomplete file list. Refresh before committing." }
        val status = entry.take(2)
        val path = entry.substring(3)
        val original = if ('R' in status || 'C' in status) {
            require(i < parts.size && parts[i].isNotEmpty()) { "Git returned an incomplete rename. Refresh before committing." }
            parts[i++]
        } else null
        if (!privateHarnessPath(path) && (original == null || !privateHarnessPath(original))) result += GitChange(path, status, original)
    }
    return result
}

private fun privateHarnessPath(path: String) = path.split('/').any { it == ".harness" || it == ".git" }

/** Deterministic Git operations, shared by the controls and push automations. Never force-pushes. */
class GitHubRepository(private val run: suspend (List<String>) -> GitCommandResult) {
    private suspend fun command(stage: String, vararg args: String): String {
        val result = run(args.toList())
        if (result.code != 0) throw GitHubOperationFailure(stage, gitHubFailureHint(result.code, result.err + "\n" + result.out))
        return result.out.trimEnd('\n', '\r')
    }

    suspend fun inspect(): GitHubRepoState {
        // A parent repository must not accidentally be published from a nested workspace.
        val prefix = command("Repository", "rev-parse", "--show-prefix")
        check(prefix.isBlank()) { "Open the repository root workspace before committing or pushing." }
        val origin = run(listOf("config", "--get", "remote.origin.url"))
        if (origin.code == 1) throw GitHubOperationFailure("Repository", "This workspace has no GitHub origin. Connect it to an existing GitHub repository below.")
        if (origin.code != 0) throw GitHubOperationFailure("Repository", gitHubFailureHint(origin.code, origin.err + origin.out))
        val url = origin.out.trimEnd('\n', '\r')
        val remote = gitHubRepositoryUrl(url)
        check(url == remote.removeSuffix(".git") || url == remote) {
            "Origin must use the GitHub HTTPS address. Connect the HTTPS repository before using this account."
        }
        val pushUrl = run(listOf("config", "--get-all", "remote.origin.pushurl"))
        check(pushUrl.code == 1 || (pushUrl.code == 0 && pushUrl.out.trim() == url)) {
            "Origin has a different push address. Remove that override before using these controls."
        }
        val branchResult = run(listOf("symbolic-ref", "--quiet", "--short", "HEAD"))
        if (branchResult.code == 1) throw GitHubOperationFailure("Branch", "This repository is in a detached state. Check out a branch before publishing.")
        if (branchResult.code != 0) throw GitHubOperationFailure("Branch", gitHubFailureHint(branchResult.code, branchResult.err))
        val branch = branchResult.out.trimEnd('\n', '\r')
        command("Branch", "check-ref-format", "--branch", branch)
        val head = run(listOf("rev-parse", "--verify", "HEAD"))
        val state = command("Files", "status", "--porcelain=v1", "-z", "--untracked-files=all")
        val changes = parseGitChanges(state)
        check(changes.none { it.status in setOf("DD", "AU", "UD", "UA", "DU", "AA", "UU") }) {
            "Resolve merge conflicts before committing or pushing."
        }
        return GitHubRepoState(remote, branch, if (head.code == 0) head.out.trim() else "", changes)
    }

    suspend fun connect(repository: String) {
        val url = gitHubRepositoryUrl(repository)
        val existing = run(listOf("rev-parse", "--show-prefix"))
        if (existing.code != 0) command("Create repository", "init")
        else check(existing.out.isBlank()) { "Open the repository root workspace first." }
        val origin = run(listOf("config", "--get", "remote.origin.url"))
        if (origin.code == 0) {
            check(gitHubRepositoryUrl(origin.out.trim()) == url) { "This workspace already points to another repository. Import the target repository into its own workspace." }
            command("Connect repository", "remote", "set-url", "origin", url)
        } else command("Connect repository", "remote", "add", "origin", url)
    }

    suspend fun publish(expected: GitHubRepoState, selected: Set<String>, message: String, commit: Boolean): String {
        val current = inspect()
        require(current.branch == expected.branch && current.remoteUrl == expected.remoteUrl && current.head == expected.head) {
            "The repository or branch changed. Refresh and review before publishing."
        }
        if (commit) {
            require(message.isNotBlank()) { "Enter a commit message." }
            val changes = current.changes.filter { it.path in selected }
            require(changes.isNotEmpty() && changes.size == selected.size) { "The selected files changed. Refresh and review them." }
            val paths = changes.flatMap { listOfNotNull(it.path, it.originalPath.takeIf { _ -> 'R' in it.status }) }.distinct()
            require(paths.none(::privateHarnessPath)) { "App history cannot be committed." }
            // --only keeps unrelated staged changes out of this commit and leaves them staged.
            // A staged rename/deletion has already removed the old path from the index.
            // Only paths with worktree changes need staging again.
            val stagePaths = changes.filter { it.status == "??" || it.status[1] != ' ' }.map { it.path }
            if (stagePaths.isNotEmpty()) command("Stage files", "add", "--", *stagePaths.toTypedArray())
            command("Commit", "commit", "--only", "-m", message.trim(), "--", *paths.toTypedArray())
        }
        val head = command("Commit", "rev-parse", "--verify", "HEAD").trim()
        // Push the reviewed commit to an explicit branch; don't depend on guessed upstream settings.
        command("Push", "push", "--porcelain", "origin", "$head:refs/heads/${current.branch}")
        val remoteHead = command("Verify push", "ls-remote", "--exit-code", "origin", "refs/heads/${current.branch}")
            .substringBefore('\t').trim()
        check(remoteHead == head) { "The push could not be verified. Your local commit is kept. Refresh the repository before retrying." }
        return "Published ${head.take(12)} to ${current.remoteUrl.removePrefix("https://github.com/").removeSuffix(".git")} · ${current.branch}."
    }

    suspend fun runPreset(preset: GitHubPushPreset): String {
        val state = inspect()
        check(state.remoteUrl == preset.remoteUrl && state.branch == preset.branch) {
            "The saved repository or branch no longer matches this workspace. Edit the push preset."
        }
        val selected = if (preset.paths.isEmpty()) state.changes.map { it.path }.toSet()
            else state.changes.filter { it.path in preset.paths }.map { it.path }.toSet()
        return publish(state, selected, preset.commitMessage, commit = selected.isNotEmpty())
    }
}

fun gitHubFailureHint(code: Int, output: String): String {
    val detail = redactGitHubSecrets(output).trim().takeLast(2500)
    val lower = detail.lowercase()
    val hint = when {
        code == 127 || "git: not found" in lower -> "Install the Linux environment in Terminal & device settings."
        "cannot exec" in lower && "permission denied" in lower || "could not spawn" in lower -> "Git could not start a required helper. Open Terminal & device settings, check missing packages and update the Linux environment, then retry. Your local commit is kept."
        "not a git repository" in lower -> "Import a GitHub repository, or connect this workspace to an existing GitHub repository."
        "non-fast-forward" in lower || "fetch first" in lower || "rejected" in lower && "behind" in lower -> "GitHub has newer commits. Pull and resolve any conflicts before retrying; your local commit is kept."
        "protected branch" in lower || "gh006" in lower || "gh013" in lower -> "The branch rules rejected this push. Use a permitted branch and open a pull request."
        "workflow" in lower && ("scope" in lower || "permission" in lower) -> "Updating GitHub Actions requires workflow access. Reconnect with workflow permission and retry the push."
        "authentication failed" in lower || "could not read username" in lower || "bad credentials" in lower -> "Reconnect GitHub. The token may be expired, revoked or unavailable to this workspace."
        "403" in lower || "permission" in lower || "repository not found" in lower -> "Check that this account has write access and the token includes this repository. Fine-grained PATs need Contents: read and write; your organization may require approval or SSO authorization."
        "unable to access" in lower || "resolve host" in lower || "timed out" in lower -> "Check your connection. The local commit is kept; use Push existing commits to retry."
        "unable to auto-detect email" in lower || "please tell me who you are" in lower -> "Configure your Git commit name and email in the repository."
        else -> "Review Git's details below, fix the reported problem and retry. Local commits are kept."
    }
    return "$hint\n\n$detail"
}
