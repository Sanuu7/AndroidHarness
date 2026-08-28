package com.androidharness.app.tools

import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShellTierRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"

/**
 * Every git invocation runs with -c safe.directory='*'. Repos can be owned by
 * a different uid than whoever executes git (the app workspace seen by the
 * Shizuku shell uid, shared storage owned by the media uid), which otherwise
 * trips "detected dubious ownership in repository" on the very first command.
 *
 * gc.auto=0 and maintenance.auto=false stop git from spawning its detached
 * auto-maintenance subprocess after commits: the sandbox cannot exec it, and
 * the resulting "fatal: cannot exec 'maintenance'" noise made successful
 * commits look failed (on-device QA, 2026-08-28).
 */
private const val GIT_BASE_ARGS = "-c 'safe.directory=*' -c gc.auto=0 -c maintenance.auto=false"

/**
 * Builds a shell command where every git step carries the safe.directory
 * override. Multi-step commands ("add && commit", "diff && diff") need the
 * flag on EACH segment, not just the first.
 */
internal fun gitCmd(vararg steps: String): String =
    steps.joinToString(" && ") { "git $GIT_BASE_ARGS ${it.trim()}" }

/** Runtime directory whose artifacts must never be swept into a commit. */
private const val HARNESS_DIR = ".harness"

internal fun gitLogCmd(limit: Int, path: String?, stat: Boolean): String =
    gitCmd(
        buildString {
            append("log -n ").append(limit.coerceIn(1, 100))
            append(" --date=short --pretty=format:'%h %ad %an  %s'")
            if (stat) append(" --stat")
            if (!path.isNullOrBlank()) append(" -- ").append(path.shellQuote())
        },
    )

internal fun gitShowCmd(hash: String, noPatch: Boolean): String =
    gitCmd(
        buildString {
            append("show")
            // -s BEFORE --stat: --no-patch suppresses the stat in any position,
            // while "show -s --stat" keeps message + stat (verified on git 2.55).
            if (noPatch) append(" -s")
            append(" --stat")
            append(' ').append(hash.trim().shellQuote())
        },
    )

internal fun gitCheckoutCmd(branch: String?, create: Boolean, paths: List<String>): String {
    val cleanPaths = paths.map { it.trim() }.filter { it.isNotEmpty() }
    val b = branch?.trim().orEmpty()
    if (b.isEmpty() && cleanPaths.isEmpty()) {
        throw ToolFailure("checkout needs a branch, paths, or both")
    }
    return gitCmd(
        buildString {
            append("checkout")
            if (b.isNotEmpty()) {
                if (create) append(" -b")
                append(' ').append(b.shellQuote())
            } else {
                append(" --")
            }
            if (cleanPaths.isNotEmpty()) {
                if (b.isNotEmpty()) append(" --")
                cleanPaths.forEach { append(' ').append(it.shellQuote()) }
            }
        },
    )
}

internal fun gitPushCmd(remote: String?, branch: String?, setUpstream: Boolean): String =
    gitCmd(
        buildString {
            append("push")
            if (setUpstream) append(" -u")
            append(' ').append((remote?.trim()?.ifEmpty { null } ?: "origin").shellQuote())
            val b = branch?.trim().orEmpty()
            append(' ').append(if (b.isEmpty()) "HEAD" else b.shellQuote())
        },
    )

internal fun gitPullCmd(remote: String?, mode: String?): String {
    val r = (remote?.trim()?.ifEmpty { null } ?: "origin").shellQuote()
    return gitCmd(
        when (mode?.trim()?.lowercase()) {
            null, "", "ff-only" -> "pull --ff-only $r"
            "merge" -> "pull $r"
            "rebase" -> "pull --rebase $r"
            else -> throw ToolFailure("Unknown pull mode '$mode' (use ff-only, merge or rebase)")
        },
    )
}

internal fun isNoUpstream(output: String): Boolean =
    output.contains("has no upstream", ignoreCase = true) ||
        output.contains("no upstream configured", ignoreCase = true) ||
        output.contains("set the remote as upstream", ignoreCase = true)

internal fun isDubiousOwnership(output: String): Boolean =
    output.contains("dubious ownership", ignoreCase = true)

private suspend fun runGit(
    router: ShellTierRouter,
    linuxEnv: LinuxEnvironmentManager,
    ctx: ToolContext,
    command: String,
): ToolResult {
    val cwd = ctx.workspace.shellRoot
        ?: return ToolResult(
            false,
            "This workspace has no real filesystem path, so git cannot run here. " +
                "Switch to a device folder or the app workspace (Settings → Workspace).",
        )
    var res = router.run(command, cwd, timeoutMs = 60_000, maxOutput = 24_000)
    val fullOutput = "${res.rawOutput}\n${res.rawStderr}"
    if (res.rawOutput.contains("not found") || res.rawOutput.contains("no such file", true) && res.exitCode == 127) {
        return ToolResult(
            false,
            "git is not available here. Install the Linux environment (Settings → Terminal → Install) first.",
        )
    }
    if (res.exitCode != 0 && fullOutput.contains("not a git repository", ignoreCase = true)) {
        // A fresh workspace is not yet a repo: init one in place and retry,
        // so the git tools work everywhere instead of dead-ending there
        // (stress-test C4).
        val initRes = router.run(gitCmd("init"), cwd, timeoutMs = 30_000, maxOutput = 2_000)
        if (initRes.exitCode == 0) {
            val retry = router.run(command, cwd, timeoutMs = 60_000, maxOutput = 24_000)
            return buildGitResult(
                retry,
                note = "[note: the workspace was not a git repository; initialized one in place]",
            )
        }
        return ToolResult(
            false,
            "The workspace is not a git repository and initializing one failed:\n" +
                (initRes.rawOutput.trimEnd() + "\n" + initRes.rawStderr.trimEnd()).trim(),
        )
    }
    // Defense in depth: -c should make dubious ownership impossible, but an
    // exotic setup that still hits it gets '*' persisted into the global
    // config once and a re-run — this is also what creates ~/.gitconfig when
    // none existed before.
    if (res.exitCode != 0 && isDubiousOwnership(fullOutput)) {
        val fixRes = router.run(
            gitCmd("config --global --add safe.directory '*'"),
            cwd,
            timeoutMs = 30_000,
            maxOutput = 2_000,
        )
        if (fixRes.exitCode == 0) {
            res = router.run(command, cwd, timeoutMs = 60_000, maxOutput = 24_000)
            return buildGitResult(
                res,
                note = "[note: added safe.directory '*' to the global git config — the repository was owned by another uid]",
            )
        }
    }
    return buildGitResult(res)
}

private fun buildGitResult(
    res: com.androidharness.app.data.env.ShellRunResult,
    note: String? = null,
): ToolResult = ToolResult(
    ok = !res.timedOut && res.exitCode == 0,
    // trimEnd kills the trailing newline that used to render as a stray blank line.
    output = buildString {
        val header = note ?: res.note
        if (header != null) append(header).append('\n')
        val text = res.rawOutput.trimEnd()
        if (text.isNotEmpty()) {
            append("--- stdout ---\n").append(text).append('\n')
        }
        val err = res.rawStderr.trimEnd()
        if (err.isNotEmpty()) {
            append("--- stderr ---\n").append(err)
        }
        if (text.isEmpty() && err.isEmpty()) append("(no output)")
    }.trimEnd(),
)

private suspend fun runGitWithRetry(
    router: ShellTierRouter,
    linuxEnv: LinuxEnvironmentManager,
    ctx: ToolContext,
    command: String,
    maxRetries: Int = 3,
): ToolResult {
    var res = runGit(router, linuxEnv, ctx, command)
    var attempt = 0
    while (!res.ok && isIndexLocked(res.output) && attempt < maxRetries) {
        attempt++
        kotlinx.coroutines.delay(200L * (1L shl (attempt - 1)))
        res = runGit(router, linuxEnv, ctx, command)
    }
    return res
}

class GitStatusTool(
    private val router: ShellTierRouter,
    private val linuxEnv: LinuxEnvironmentManager,
) : Tool {
    override val name = "git_status"
    override val description =
        "Show git status for the workspace repository (branch + changed files). " +
        "Use before committing or to answer 'what changed'."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) { runGitWithRetry(router, linuxEnv, ctx, gitCmd("status --short --branch")) }
}

class GitDiffTool(
    private val router: ShellTierRouter,
    private val linuxEnv: LinuxEnvironmentManager,
) : Tool {
    override val name = "git_diff"
    override val description =
        "Show the git diff of unstaged (or staged) changes in the workspace repository, " +
        "optionally limited to one path."
    override val parametersSchema = Schema.obj(
        mapOf(
            "path" to Schema.string("Optional file path to limit the diff to."),
            "staged" to Schema.string("Pass \"true\" to diff staged changes instead of unstaged."),
        ),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val path = args["path"]?.jsonPrimitive?.content
            val staged = args["staged"]?.jsonPrimitive?.content == "true"
            val statCmd = buildString {
                append("diff")
                if (staged) append(" --staged")
                append(" --stat")
            }
            val detailCmd = buildString {
                append("diff")
                if (staged) append(" --staged")
                if (!path.isNullOrBlank()) append(" -- ").append(path.shellQuote())
            }
            runGitWithRetry(router, linuxEnv, ctx, gitCmd(statCmd, detailCmd))
        }
}

class GitCommitTool(
    private val router: ShellTierRouter,
    private val linuxEnv: LinuxEnvironmentManager,
) : Tool {
    override val name = "git_commit"
    override val description =
        "Stage all changes in the workspace repository and commit them with the given " +
        "message. Runtime artifacts under .harness/ are never staged. Runs as a " +
        "modifying operation, so the user approves it first."
    override val parametersSchema = Schema.obj(
        mapOf("message" to Schema.string("The commit message.")),
        required = listOf("message"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val message = args["message"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: message")
            val stageCmd =
                "add -A -- ${":(exclude)$HARNESS_DIR".shellQuote()} ${":(exclude)$HARNESS_DIR/**".shellQuote()}"
            val commitCommand = gitCmd(stageCmd, "commit -m ${message.shellQuote()}")
            val res = runGitWithRetry(router, linuxEnv, ctx, commitCommand)
            if (!res.ok && isIdentityUnknown(res.output)) {
                val repoConfig = runGitWithRetry(
                    router,
                    linuxEnv,
                    ctx,
                    gitCmd(
                        "config user.name 'Android Harness'",
                        "config user.email 'harness@android.local'",
                    ),
                )
                var note =
                    "[note: auto-configured repository git identity 'Android Harness <harness@android.local>']"
                if (!repoConfig.ok) {
                    // Repo-local config failed (read-only .git/config etc.) —
                    // fall back to the global ~/.gitconfig identity.
                    val globalConfig = runGitWithRetry(
                        router,
                        linuxEnv,
                        ctx,
                        gitCmd(
                            "config --global user.name 'Android Harness'",
                            "config --global user.email 'harness@android.local'",
                        ),
                    )
                    if (globalConfig.ok) {
                        note = "[note: auto-configured global git identity 'Android Harness <harness@android.local>' in ~/.gitconfig]"
                    }
                }
                val retryRes = runGitWithRetry(router, linuxEnv, ctx, commitCommand)
                if (retryRes.ok) {
                    return@withContext ToolResult(true, "$note\n${retryRes.output}")
                }
            }
            res
        }
}

class GitLogTool(
    private val router: ShellTierRouter,
    private val linuxEnv: LinuxEnvironmentManager,
) : Tool {
    override val name = "git_log"
    override val description =
        "Show recent commit history of the workspace repository (short hash, date, author, " +
        "subject), optionally limited to one path. Use it to answer 'what happened recently', " +
        "to find a commit hash for git_show, or to check whether a file was touched before."
    override val parametersSchema = Schema.obj(
        mapOf(
            "limit" to Schema.integer("Maximum number of commits (default 20, max 100)."),
            "path" to Schema.string("Optional file path to only show commits touching it."),
            "stat" to Schema.string("Pass \"true\" to append a per-commit change summary (--stat)."),
        ),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
            val path = args["path"]?.jsonPrimitive?.content
            val stat = args["stat"]?.jsonPrimitive?.content == "true"
            runGitWithRetry(router, linuxEnv, ctx, gitLogCmd(limit, path, stat))
        }
}

class GitShowTool(
    private val router: ShellTierRouter,
    private val linuxEnv: LinuxEnvironmentManager,
) : Tool {
    override val name = "git_show"
    override val description =
        "Show one commit: message, per-file stats and the patch. Defaults to HEAD. Use after " +
        "git_log to inspect a specific change. Pass no_patch=true for just the message and stats."
    override val parametersSchema = Schema.obj(
        mapOf(
            "hash" to Schema.string("Commit hash or ref (default HEAD)."),
            "no_patch" to Schema.string("Pass \"true\" to omit the diff and show message + stats only."),
        ),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val hash = args["hash"]?.jsonPrimitive?.content?.trim() ?: "HEAD"
            val noPatch = args["no_patch"]?.jsonPrimitive?.content == "true"
            runGitWithRetry(router, linuxEnv, ctx, gitShowCmd(hash, noPatch))
        }
}

class GitBranchTool(
    private val router: ShellTierRouter,
    private val linuxEnv: LinuxEnvironmentManager,
) : Tool {
    override val name = "git_branch"
    override val description =
        "List git branches of the workspace repository, current one marked with *. " +
        "With all=true also lists remote-tracking branches."
    override val parametersSchema = Schema.obj(
        mapOf("all" to Schema.string("Pass \"true\" to include remote-tracking branches.")),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val all = args["all"]?.jsonPrimitive?.content == "true"
            runGitWithRetry(router, linuxEnv, ctx, gitCmd(if (all) "branch -a -v" else "branch -v"))
        }
}

class GitBranchManageTool(
    private val router: ShellTierRouter,
    private val linuxEnv: LinuxEnvironmentManager,
) : Tool {
    override val name = "git_branch_manage"
    override val description =
        "Create or delete a git branch in the workspace repository. action='create' makes a " +
        "new branch pointing at HEAD (does not switch to it — use git_checkout); " +
        "action='delete' removes it (-d refuses unmerged branches unless force=true). " +
        "Runs as a modifying operation, so the user approves it first."
    override val parametersSchema = Schema.obj(
        mapOf(
            "action" to Schema.string("create | delete (required)."),
            "name" to Schema.string("The branch name (required)."),
            "force" to Schema.string("delete only: pass \"true\" to delete even if unmerged (-D)."),
        ),
        required = listOf("action", "name"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val action = args["action"]?.jsonPrimitive?.content?.trim()?.lowercase()
                ?: throw ToolFailure("Missing required argument: action")
            val name = args["name"]?.jsonPrimitive?.content?.trim()
                ?: throw ToolFailure("Missing required argument: name")
            if (name.isEmpty()) throw ToolFailure("Branch name must not be empty")
            val force = args["force"]?.jsonPrimitive?.content == "true"
            val command = when (action) {
                "create" -> gitCmd("branch ${name.shellQuote()}")
                "delete" -> gitCmd("branch ${if (force) "-D" else "-d"} ${name.shellQuote()}")
                else -> throw ToolFailure("Unknown action '$action' (use create or delete)")
            }
            runGitWithRetry(router, linuxEnv, ctx, command)
        }
}

class GitCheckoutTool(
    private val router: ShellTierRouter,
    private val linuxEnv: LinuxEnvironmentManager,
) : Tool {
    override val name = "git_checkout"
    override val description =
        "Switch the workspace repository to another branch, or restore files. With branch: " +
        "switches to it (create=true makes it first). With paths: discards uncommitted " +
        "changes to those files (destructive). With both: restores the paths from the " +
        "given branch. Runs as a modifying operation, so the user approves it first."
    override val parametersSchema = Schema.obj(
        mapOf(
            "branch" to Schema.string("Branch to switch to (or to restore paths from)."),
            "create" to Schema.string("Pass \"true\" to create the branch before switching (-b)."),
            "paths" to Schema.array(
                Schema.string("File path to restore."),
                "Paths to restore from the branch (or to reset if no branch is given). " +
                    "Discards uncommitted changes to them.",
            ),
        ),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val branch = args["branch"]?.jsonPrimitive?.content
            val create = args["create"]?.jsonPrimitive?.content == "true"
            val paths = args["paths"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?: emptyList()
            if (branch.isNullOrBlank() && paths.isEmpty()) {
                throw ToolFailure("checkout needs a branch, paths, or both")
            }
            runGitWithRetry(router, linuxEnv, ctx, gitCheckoutCmd(branch, create, paths))
        }
}

class GitPushTool(
    private val router: ShellTierRouter,
    private val linuxEnv: LinuxEnvironmentManager,
) : Tool {
    override val name = "git_push"
    override val description =
        "Push committed work to a remote (default origin; branch defaults to the current one). " +
        "GitHub HTTPS remotes authenticate automatically with the stored GitHub token " +
        "(doctor --github verifies it); other HTTPS remotes need credentials already set up " +
        "in the shell. A first push without an upstream is retried with -u. " +
        "Runs as a modifying operation, so the user approves it first."
    override val parametersSchema = Schema.obj(
        mapOf(
            "remote" to Schema.string("Remote name (default origin)."),
            "branch" to Schema.string("Branch to push (default: the current branch)."),
        ),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val remote = args["remote"]?.jsonPrimitive?.content
            val branch = args["branch"]?.jsonPrimitive?.content
            val res = runGitWithRetry(router, linuxEnv, ctx, gitPushCmd(remote, branch, setUpstream = false))
            if (!res.ok && isNoUpstream(res.output)) {
                val retry = runGitWithRetry(router, linuxEnv, ctx, gitPushCmd(remote, branch, setUpstream = true))
                if (retry.ok) {
                    return@withContext ToolResult(
                        true,
                        "[note: no upstream was configured; pushed with -u to set it]\n${retry.output}",
                    )
                }
                return@withContext retry
            }
            res
        }
}

class GitPullTool(
    private val router: ShellTierRouter,
    private val linuxEnv: LinuxEnvironmentManager,
) : Tool {
    override val name = "git_pull"
    override val description =
        "Pull changes from a remote (default origin) into the current branch. mode: " +
        "'ff-only' (default) refuses to create a merge commit, 'merge' allows one, " +
        "'rebase' replays local commits on top. On conflicts: resolve the marked files, " +
        "then stage and commit them. Runs as a modifying operation, so the user approves it first."
    override val parametersSchema = Schema.obj(
        mapOf(
            "remote" to Schema.string("Remote name (default origin)."),
            "mode" to Schema.string("ff-only (default) | merge | rebase."),
        ),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val remote = args["remote"]?.jsonPrimitive?.content
            val mode = args["mode"]?.jsonPrimitive?.content
            val res = runGitWithRetry(router, linuxEnv, ctx, gitPullCmd(remote, mode))
            if (!res.ok && res.output.contains("CONFLICT", ignoreCase = true)) {
                res.copy(
                    output = res.output +
                        "\n[note: merge conflicts — edit the marked files, then stage and commit them]",
                )
            } else {
                res
            }
        }
}

private fun isIndexLocked(output: String): Boolean {
    val lower = output.lowercase()
    return lower.contains("index.lock") || lower.contains("another git process seems to be running")
}

private fun isIdentityUnknown(output: String): Boolean {
    val lower = output.lowercase()
    return lower.contains("author identity unknown") ||
        lower.contains("tell me who you are") ||
        lower.contains("empty ident name") ||
        lower.contains("please tell me who you are")
}
