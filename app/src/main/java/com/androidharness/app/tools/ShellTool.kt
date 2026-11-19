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
        "Run a shell command on the device. Uses SSH for an SSH workspace; " +
        "otherwise follows the selected Auto, Normal, Shizuku, Root or Both access mode: " +
        "a full Linux userspace (bash, git, python, node…) as the app when installed, " +
        "Shizuku/Shevery or the app's built-in Wireless ADB shell (same ADB-shell privileges plus the same toolchain) when connected, " +
        "or direct root when requested. App-workspace development uses the app UID by default. Choose access=\"shizuku\" for Android system commands; it pins Shizuku, Shevery or the built-in Wireless ADB shell. In Both mode other ordinary commands use the privileged shell and " +
        "access=\"root\" uses the direct root service, so both can be used in the same task. " +
        "If the active workspace is a picked folder (SAF), the command runs in the app's shell " +
        "workspace and a note is added. Returns the exit code plus captured stdout and stderr " +
        "as separate sections; output written before a timeout is preserved."
    override val parametersSchema = Schema.obj(
        mapOf(
            "command" to Schema.string("The shell command to run."),
            "access" to Schema.string("auto (default), shizuku, or root. App-workspace development uses the app UID by default. Choose shizuku for Android system commands; this explicitly pins the privileged shell (Shizuku, Shevery or the app's built-in Wireless ADB) and never falls back to root or the app UID. In Both mode auto uses the privileged shell and root uses the direct root service. Root requires a verified root-capable backend and project permission; it never falls back to non-root execution."),
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
            val remote = ctx.workspace as? com.androidharness.app.workspace.SshFs
            validateShellAccess(args["access"]?.jsonPrimitive?.content, remote != null)
            val root = remote?.root?.let { File(it) } ?: ctx.workspace.shellRoot
                ?: throw ToolFailure(
                    "This workspace has no real filesystem path, so the shell cannot run here. " +
                        "Switch to a device folder or the app workspace (Settings → Workspace).",
                )
            val cwd =
                if (remote != null) File(remote.commandPath(args["cwd"]?.jsonPrimitive?.content ?: "."))
                else resolveShellWorkingDirectory(args["cwd"]?.jsonPrimitive?.content, root, ctx.sandboxOff)

            // Full access skips the denylist re-check; the engine already
            // gated this call against the same policy.
            val deny = if (ctx.sandboxOff) null else ShellPolicy.denyReason(rawCommand, root, cwd, ctx.skills?.folders().orEmpty())
            if (deny != null) {
                return@withContext ToolResult(false, deny)
            }

            val timeoutSec = (args["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 120)
                .coerceIn(1, 600)

            // Auto-append --no-bin-links for npm install on shared storage (symlinks unsupported).
            val (command, notes) = if (remote != null) rawCommand to null else run {
                val (afterNpm, npmNote) = NpmOnSharedStorage.prepare(rawCommand, cwd)
                // Bug 4 fix: retarget workspace tar extractions to the
                // exec-capable scratch dir so symlinks/exec bits survive.
                val (afterTar, tarNote) = ExecScratchRouting.prepare(afterNpm, cwd)
                afterTar to listOfNotNull(npmNote, tarNote).joinToString("\n").ifBlank { null }
            }

            val res = if (remote != null) remote.run(command, cwd.path, timeoutSec * 1000, MAX_OUTPUT_CHARS * 2)
                else router.run(PluginBinPath.prefix(ctx, command), cwd, timeoutSec * 1000, MAX_OUTPUT_CHARS * 2)

            val isSymlink = rawCommand.contains("ln ") && (rawCommand.contains("-s") || rawCommand.contains("--symbolic"))
            val hasSymlinkError = isSymlink && (res.exitCode != 0 || res.rawOutput.contains("Permission denied", true) || res.rawStderr.contains("Permission denied", true) || res.rawOutput.contains("Operation not permitted", true) || res.rawStderr.contains("Operation not permitted", true))

            if (isSymlink && remote == null) {
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
            notes?.let { sb.append(it).append('\n') }
            res.note?.let { sb.append(it).append('\n') }
            if (hasSymlinkError) {
                sb.append("[note: symlink creation failed: symlink not supported/allowed on this filesystem]\n")
            }
            // Access refusals come back as an unstarted toybox result (exit -1, nothing on stdout);
            // they are policy, not a broken bash, so they get no fallback note.
            val refused = res.exitCode == -1 && res.rawOutput.isEmpty() && !res.timedOut
            if (res.tier == ExecutionTier.TOYBOX && !refused && linuxEnv.bashExecutable() != null) {
                sb.append("[note: fell back to toybox sh, launching the Linux bash failed on this device]\n")
            }
            if (res.tier == ExecutionTier.PRIVILEGED) {
                sb.append("[note: ran through the privileged shell; the access receipt identifies the provider and verified UID]\n")
            }
            if (res.timedOut) sb.append(if (res.tier == ExecutionTier.TERMUX_SSH)
                "[SSH channel closed after ${timeoutSec}s timeout; inspect Termux before retrying modifying commands]\n"
                else "[killed after ${timeoutSec}s timeout; output below is what was written before the kill]\n")
            sb.append("exit code: ").append(if (res.timedOut) "killed (timeout)" else if (hasSymlinkError && res.exitCode == 0) 1 else res.exitCode).append('\n')
            val out = res.rawOutput.trimEnd()
            val err = res.rawStderr.trimEnd()
            if (out.isNotEmpty()) sb.append("stdout (JSON string): ").append(kotlinx.serialization.json.JsonPrimitive(out.truncated())).append('\n')
            if (err.isNotEmpty()) sb.append("stderr (JSON string): ").append(kotlinx.serialization.json.JsonPrimitive(err.truncated())).append('\n')
            if (out.isEmpty() && err.isEmpty()) sb.append("(no output)")
            ToolResult(ok = !res.timedOut && res.exitCode == 0 && !hasSymlinkError, output = sb.toString().trimEnd())
        }

    private fun String.truncated(): String =
        if (length <= MAX_OUTPUT_CHARS) this
        else substring(0, MAX_OUTPUT_CHARS) + "\n[truncated]"
}

/** A pinned Android transport must never silently execute on an SSH host. */
internal fun validateShellAccess(access: String?, remote: Boolean) {
    require(access in listOf(null, "auto", "shizuku", "root")) { "access must be auto, shizuku or root" }
    require(!remote || access == null || access == "auto") {
        "Shizuku and direct root are Android-local. Select a local workspace for this access request; SSH commands require access=auto."
    }
}

internal fun resolveShellWorkingDirectory(raw: String?, root: File, sandboxOff: Boolean): File {
    val path = raw?.trim()?.removePrefix("./")?.takeIf { it.isNotEmpty() } ?: return root
    val canonical = (if (path.startsWith('/')) File(path) else File(root, path)).canonicalFile
    if (!sandboxOff && !canonical.toPath().startsWith(root.canonicalFile.toPath())) {
        throw ToolFailure("cwd is outside the workspace and was blocked: $path")
    }
    if (!canonical.exists()) throw ToolFailure("cwd does not exist: $path")
    if (!canonical.isDirectory) throw ToolFailure("cwd is not a directory: $path")
    return canonical
}
