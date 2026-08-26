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
    if (res.exitCode != 0 && res.rawOutput.contains("not a git repository", true)) {
        return ToolResult(false, "The workspace at ${cwd.absolutePath} is not a git repository.")
    }
    return ToolResult(
        ok = !res.timedOut && res.exitCode == 0,
        output = buildString {
            if (res.note != null) append(res.note).append('\n')
            val text = res.rawOutput.trimEnd()
            append(if (text.isEmpty()) "(no output)" else text)
        },
    )
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
        withContext(Dispatchers.IO) { runGit(router, linuxEnv, ctx, "status --short --branch") }
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
            runGit(router, linuxEnv, ctx, cmd)
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
            runGit(router, linuxEnv, ctx, "add -A && git commit -m ${message.shellQuote()}")
        }
}
