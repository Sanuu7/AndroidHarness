package com.androidharness.app.tools

import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShellTierRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"

/**
 * Every git invocation runs with -c safe.directory='*'. Repos can be owned by
 * a different uid than whoever executes git (the app workspace seen by the
 * Shizuku shell uid, shared storage owned by the media uid), which otherwise
 * trips "detected dubious ownership in repository" on the very first command.
 */
private const val SAFE_DIR_ARG = "-c 'safe.directory=*'"

/**
 * Builds a shell command where every git step carries the safe.directory
 * override. Multi-step commands ("add && commit", "diff && diff") need the
 * flag on EACH segment, not just the first.
 */
internal fun gitCmd(vararg steps: String): String =
    steps.joinToString(" && ") { "git $SAFE_DIR_ARG ${it.trim()}" }

/** Runtime directory whose artifacts must never be swept into a commit. */
private const val HARNESS_DIR = ".harness"

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
