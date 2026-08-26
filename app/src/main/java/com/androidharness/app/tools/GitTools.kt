package com.androidharness.app.tools

import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShellTierRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"

private suspend fun runGit(
    router: ShellTierRouter,
    linuxEnv: LinuxEnvironmentManager,
    ctx: ToolContext,
    gitArgs: String,
): ToolResult {
    val cwd = ctx.workspace.shellRoot
        ?: return ToolResult(
            false,
            "This workspace has no real filesystem path, so git cannot run here. " +
                "Switch to a device folder or the app workspace (Settings → Workspace).",
        )
    val res = router.run("git $gitArgs", cwd, timeoutMs = 60_000, maxOutput = 24_000)
    if (res.rawOutput.contains("not found") || res.rawOutput.contains("no such file", true) && res.exitCode == 127) {
        return ToolResult(
            false,
            "git is not available here. Install the Linux environment (Settings → Terminal → Install) first.",
        )
    }
    val fullOutput = "${res.rawOutput}\n${res.rawStderr}"
    if (res.exitCode != 0 && fullOutput.contains("not a git repository", ignoreCase = true)) {
        return ToolResult(false, "The workspace is not a git repository (no .git folder found).")
    }
    return ToolResult(
        ok = !res.timedOut && res.exitCode == 0,
        output = buildString {
            if (res.note != null) append(res.note).append('\n')
            val text = res.rawOutput.trimEnd()
            if (text.isNotEmpty()) {
                append("--- stdout ---\n").append(text).append('\n')
            }
            val err = res.rawStderr.trimEnd()
            if (err.isNotEmpty()) {
                append("--- stderr ---\n").append(err)
            }
            if (text.isEmpty() && err.isEmpty()) append("(no output)")
        },
    )
}

private suspend fun runGitWithRetry(
    router: ShellTierRouter,
    linuxEnv: LinuxEnvironmentManager,
    ctx: ToolContext,
    gitArgs: String,
    maxRetries: Int = 3,
): ToolResult {
    var res = runGit(router, linuxEnv, ctx, gitArgs)
    var attempt = 0
    while (!res.ok && isIndexLocked(res.output) && attempt < maxRetries) {
        attempt++
        kotlinx.coroutines.delay(200L * (1L shl (attempt - 1)))
        res = runGit(router, linuxEnv, ctx, gitArgs)
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
        withContext(Dispatchers.IO) { runGitWithRetry(router, linuxEnv, ctx, "status --short --branch") }
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
            val cmd = buildString {
                append("diff")
                if (staged) append(" --staged")
                append(" --stat")
                append(" && git diff")
                if (staged) append(" --staged")
                if (!path.isNullOrBlank()) append(" -- ").append(path.shellQuote())
            }
            runGitWithRetry(router, linuxEnv, ctx, cmd)
        }
}

class GitCommitTool(
    private val router: ShellTierRouter,
    private val linuxEnv: LinuxEnvironmentManager,
) : Tool {
    override val name = "git_commit"
    override val description =
        "Stage all changes in the workspace repository and commit them with the given " +
        "message. Runs as a modifying operation, so the user approves it first."
    override val parametersSchema = Schema.obj(
        mapOf("message" to Schema.string("The commit message.")),
        required = listOf("message"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val message = args["message"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: message")
            val commitCmd = "add -A && git commit -m ${message.shellQuote()}"
            val res = runGitWithRetry(router, linuxEnv, ctx, commitCmd)
            if (!res.ok && isIdentityUnknown(res.output)) {
                val configRes = runGitWithRetry(
                    router,
                    linuxEnv,
                    ctx,
                    "config user.name 'Android Harness' && git config user.email 'harness@android.local'",
                )
                if (configRes.ok) {
                    val retryRes = runGitWithRetry(router, linuxEnv, ctx, commitCmd)
                    return@withContext ToolResult(
                        ok = retryRes.ok,
                        output = "[note: auto-configured repository git identity 'Android Harness <harness@android.local>']\n" + retryRes.output,
                    )
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
