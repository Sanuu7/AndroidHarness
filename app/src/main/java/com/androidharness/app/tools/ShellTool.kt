package com.androidharness.app.tools

import com.androidharness.app.data.env.ExecutionTier
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShellTierRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_OUTPUT_CHARS = 30_000

class ShellTool(
    private val linuxEnv: LinuxEnvironmentManager,
    private val router: ShellTierRouter,
) : Tool {
    override val name = "shell"
    override val description =
        "Run a shell command on the device. Runs with the best available native environment: " +
        "a full Linux userspace (bash, git, python, node…) as the app when installed, " +
        "Shizuku ADB-shell privileges (plus the same toolchain) when Shizuku is connected and " +
        "the target folder needs it, otherwise toybox sh. If the active workspace is a picked " +
        "folder (SAF), the command runs in the app's shell workspace and a note is added. " +
        "Returns the exit code and captured stdout/stderr."
    override val parametersSchema = Schema.obj(
        mapOf(
            "command" to Schema.string("The shell command to run."),
            "timeout_seconds" to Schema.integer("Kill the command after this many seconds. Defaults to 120, max 600."),
        ),
        required = listOf("command"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val command = args["command"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: command")
            val safMode = ctx.workspace.shellRoot == null
            val cwd = ctx.workspace.shellRoot ?: linuxEnv.shellFallbackRoot
            val timeoutSec = (args["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 120)
                .coerceIn(1, 600)

            val res = router.run(command, cwd, timeoutSec * 1000, MAX_OUTPUT_CHARS * 2)

            val sb = StringBuilder()
            if (safMode) {
                sb.append(
                    "[note: the active workspace is a picked folder (SAF) and is only reachable via " +
                        "file tools; this command ran in the app's shell workspace " +
                        "(${cwd.absolutePath}). To run code with the shell (node, python…), " +
                        "create the files in the shell workspace itself (e.g. via shell heredocs " +
                        "or by asking the user to switch the workspace in Settings).]\n"
                )
            }
            res.note?.let { sb.append(it).append('\n') }
            if (res.tier == ExecutionTier.TOYBOX && linuxEnv.bashExecutable() != null) {
                sb.append("[note: fell back to toybox sh, launching the Linux bash failed on this device]\n")
            }
            if (res.tier == ExecutionTier.PRIVILEGED) {
                sb.append("[note: ran with Shizuku ADB-shell privileges]\n")
            }
            if (res.timedOut) sb.append("[killed after ${timeoutSec}s timeout]\n")
            sb.append("exit code: ").append(if (res.timedOut) "timeout" else res.exitCode).append('\n')
            val text = res.rawOutput.trimEnd()
            if (text.isNotEmpty()) {
                sb.append("--- stdout ---\n").append(text.truncated()).append('\n')
            } else {
                sb.append("(no output)")
            }
            ToolResult(ok = !res.timedOut && res.exitCode == 0, output = sb.toString().trimEnd())
        }

    private fun String.truncated(): String =
        if (length <= MAX_OUTPUT_CHARS) this
        else substring(0, MAX_OUTPUT_CHARS) + "\n[truncated]"
}
