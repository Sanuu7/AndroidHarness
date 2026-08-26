package com.androidharness.app.tools

import com.androidharness.app.data.env.ExecutionTier
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShellTierRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private const val MAX_OUTPUT_CHARS = 100_000

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
        "Returns the exit code plus captured stdout and stderr as separate sections; output " +
        "written before a timeout is preserved."
    override val parametersSchema = Schema.obj(
        mapOf(
            "command" to Schema.string("The shell command to run."),
            "cwd" to Schema.string(
                "Working directory relative to the workspace root (default: the root). " +
                    "Use it instead of 'cd dir && …' chains.",
            ),
            "timeout_seconds" to Schema.integer("Kill the command after this many seconds. Defaults to 120, max 600."),
        ),
        required = listOf("command"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val rawCommand = args["command"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: command")
            val root = ctx.workspace.shellRoot
                ?: throw ToolFailure(
                    "This workspace has no real filesystem path, so the shell cannot run here. " +
                        "Switch to a device folder or the app workspace (Settings → Workspace).",
                )
            val cwd = resolveCwd(args, root)

            val deny = ShellPolicy.denyReason(rawCommand, root, cwd)
            if (deny != null) {
                return@withContext ToolResult(false, deny)
            }

            val timeoutSec = (args["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 120)
                .coerceIn(1, 600)

            // Auto-append --no-bin-links for npm install on shared storage (symlinks unsupported).
            val (command, npmNote) = NpmOnSharedStorage.prepare(rawCommand, cwd)

            val res = router.run(command, cwd, timeoutSec * 1000, MAX_OUTPUT_CHARS * 2)

            val isSymlink = rawCommand.contains("ln ") && (rawCommand.contains("-s") || rawCommand.contains("--symbolic"))
            val hasSymlinkError = isSymlink && (res.exitCode != 0 || res.rawOutput.contains("Permission denied", true) || res.rawStderr.contains("Permission denied", true) || res.rawOutput.contains("Operation not permitted", true) || res.rawStderr.contains("Operation not permitted", true))

            if (isSymlink) {
                // Check if ln left a stale empty regular file at destination
                val tokens = ShellPolicy.extractTokens(rawCommand)
                val lastToken = tokens.lastOrNull()?.trim()
                if (!lastToken.isNullOrEmpty() && !lastToken.startsWith("-")) {
                    val destFile = if (lastToken.startsWith("/")) File(lastToken) else File(cwd, lastToken)
                    if (destFile.exists() && destFile.isFile && destFile.length() == 0L && hasSymlinkError) {
                        destFile.delete()
                    }
                }
            }

            val sb = StringBuilder()
            npmNote?.let { sb.append(it).append('\n') }
            res.note?.let { sb.append(it).append('\n') }
            if (hasSymlinkError) {
                sb.append("[note: symlink creation failed: symlink not supported/allowed on this filesystem]\n")
            }
            if (res.tier == ExecutionTier.TOYBOX && linuxEnv.bashExecutable() != null) {
                sb.append("[note: fell back to toybox sh, launching the Linux bash failed on this device]\n")
            }
            if (res.tier == ExecutionTier.PRIVILEGED) {
                sb.append("[note: ran with Shizuku ADB-shell privileges]\n")
            }
            if (res.timedOut) sb.append("[killed after ${timeoutSec}s timeout; output below is what was written before the kill]\n")
            sb.append("exit code: ").append(if (res.timedOut) "killed (timeout)" else if (hasSymlinkError && res.exitCode == 0) 1 else res.exitCode).append('\n')
            val out = res.rawOutput.trimEnd()
            val err = res.rawStderr.trimEnd()
            if (out.isNotEmpty()) sb.append("--- stdout ---\n").append(out.truncated()).append('\n')
            if (err.isNotEmpty()) sb.append("--- stderr ---\n").append(err.truncated()).append('\n')
            if (out.isEmpty() && err.isEmpty()) sb.append("(no output)")
            ToolResult(ok = !res.timedOut && res.exitCode == 0 && !hasSymlinkError, output = sb.toString().trimEnd())
        }

    /** Resolves the optional cwd argument inside the workspace root. */
    private fun resolveCwd(args: JsonObject, root: File): File {
        val rel = args["cwd"]?.jsonPrimitive?.content?.trim()
            ?.removePrefix("./")?.trimEnd('/')
            ?: return root
        if (rel.isEmpty()) return root
        val dir = File(root, rel)
        val canonicalRoot = root.canonicalFile
        val canonical = dir.canonicalFile
        if (!canonical.path.startsWith(canonicalRoot.path)) {
            throw ToolFailure("cwd is outside the workspace and was blocked: $rel")
        }
        if (!canonical.exists()) throw ToolFailure("cwd does not exist: $rel")
        if (!canonical.isDirectory) throw ToolFailure("cwd is not a directory: $rel")
        return canonical
    }

    private fun String.truncated(): String =
        if (length <= MAX_OUTPUT_CHARS) this
        else substring(0, MAX_OUTPUT_CHARS) + "\n[truncated]"
}
