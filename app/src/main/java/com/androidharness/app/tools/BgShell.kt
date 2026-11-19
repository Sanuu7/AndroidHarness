package com.androidharness.app.tools

import com.androidharness.app.data.BgProcessStore
import com.androidharness.app.data.env.LinuxEnvironmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class ShellBackgroundTool(
    private val store: BgProcessStore,
    private val linuxEnv: LinuxEnvironmentManager,
    private val router: com.androidharness.app.data.env.ShellTierRouter? = null,
) : Tool {
    override val name = "shell_background"
    override val description =
        "Start a long-running shell command in the background (e.g. a dev server). " +
        "When a privileged shell is connected (Shizuku, Shevery or the app's built-in Wireless ADB) the process runs inside it and survives the app " +
        "being minimized or even killed; otherwise it runs as an app child kept alive by the " +
        "foreground service. App-workspace development uses the app UID by default; access=\"shizuku\" explicitly selects the privileged shell. In Both mode other background commands use the privileged shell and " +
        "access=\"root\" uses the direct root service, which lasts only while that connection " +
        "survives. Returns a process id; its output goes to a log file. " +
        "Use bg_list to inspect and bg_kill to stop it."
    override val parametersSchema = Schema.obj(
        mapOf("command" to Schema.string("The shell command to run in the background."),
            "access" to Schema.string("auto (default), shizuku, or root. App-workspace development uses the app UID by default. Choose shizuku for Android system commands; this explicitly pins the privileged shell (Shizuku, Shevery or the app's built-in Wireless ADB) and never falls back to root or the app UID. In Both mode auto uses the privileged shell and root uses the direct root service. Root requires a verified backend and project permission.")),
        required = listOf("command"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val rawCommand = args["command"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: command")
            val remote = ctx.workspace as? com.androidharness.app.workspace.SshFs
            validateShellAccess(args["access"]?.jsonPrimitive?.content, remote != null)
            if (remote != null) {
                val deny = if (ctx.sandboxOff) null else ShellPolicy.denyReason(rawCommand, java.io.File(remote.root), java.io.File(remote.root))
                if (deny != null) return@withContext ToolResult(false, deny)
                return@withContext com.androidharness.app.workspace.SshJobs.start(remote, rawCommand)
            }
            val cwd = ctx.workspace.shellRoot
                ?: throw ToolFailure(
                    "This workspace has no real filesystem path, so background processes cannot " +
                        "start here. Switch to a device folder or the app workspace.",
                )

            // Full access skips the denylist re-check; the engine already
            // gated this call against the same policy.
            val deny = if (ctx.sandboxOff) null else ShellPolicy.denyReason(rawCommand, cwd, cwd, ctx.skills?.folders().orEmpty())
            if (deny != null) {
                return@withContext ToolResult(false, deny)
            }

            // Auto-append --no-bin-links for npm install on shared storage (symlinks unsupported).
            val (command, npmNote) = NpmOnSharedStorage.prepare(rawCommand, cwd)

            try {
                val started = store.start(PluginBinPath.prefix(ctx, command), cwd, ctx.sessionId)
                val notes = StringBuilder()
                npmNote?.let { notes.append(it).append('\n') }
                if (started.entry.source == "ROOT") notes.append("[note: running via direct root; tied to this root service/app session]\n")
                if (started.viaShizuku) {
                    notes.append("[note: running under the privileged shell (Shizuku or Wireless ADB): survives the app being minimized or killed]\n")
                }
                ToolResult(
                    true,
                    notes.toString() +
                        "Started background process ${started.entry.id}: ${command.take(80)}\n" +
                        "Log: ${started.entry.logPath}",
                )
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                // App-tier background processes run as the app uid, which may
                // not reach folders outside its own; a privileged shell is needed for those.
                val hint =
                    if (router?.deviceAccess != null) "[note: check access mode, connection readiness and project root permission in Settings → Terminal & device.]"
                    else "[note: without a privileged shell, background processes run as the app user and could not " +
                        "start in $cwd. Connect Shizuku or Wireless ADB (Settings → Terminal) to run servers anywhere, " +
                        "or move the project into the app workspace or a folder the app can reach directly.]"
                ToolResult(false, hint + "\nFailed to start background process: ${e.message}")
            }
        }
}

class BgListTool(
    private val store: BgProcessStore,
) : Tool {
    override val name = "bg_list"
    override val description =
        "List background jobs owned by this chat by default. Use scope=workspace or scope=all to inspect earlier or other chats before starting another server. Includes working directories, ownership and previous-app-session labels."
    override val parametersSchema = Schema.obj(mapOf("scope" to Schema.string("chat (default), workspace, or all. Older jobs without chat ownership are shown only in workspace/all.")))
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            (ctx.workspace as? com.androidharness.app.workspace.SshFs)?.let {
                return@withContext com.androidharness.app.workspace.SshJobs.list(it)
            }
            val scope = args["scope"]?.jsonPrimitive?.content ?: "chat"
            require(scope in setOf("chat", "workspace", "all")) { "scope must be chat, workspace or all" }
            val all = store.listForDisplay()
            val inScope = all.filter { (entry, _) -> backgroundJobMatches(entry, scope, ctx) }
            // Jobs that finished before this app session started can't hold a port or be killed;
            // listing each with its log only crowds the answer. scope=all still shows them.
            val (stale, entries) = inScope.partition { (e, alive) ->
                scope != "all" && !alive && e.termination != "unverified" && e.startedAt < store.bootAt
            }
            val hidden = all.size - inScope.size
            if (entries.isEmpty() && hidden == 0 && stale.isEmpty()) return@withContext ToolResult(true, "No tracked background jobs.")
            val sb = StringBuilder()
            if (entries.isEmpty()) sb.append("No running or current jobs in this scope.\n")
            if (stale.isNotEmpty()) sb.append("${stale.size} job(s) that finished in earlier app sessions are not shown; use scope=all to list them.\n")
            if (hidden > 0) sb.append("$hidden job(s) outside this scope; use scope=workspace or scope=all to inspect possible port conflicts.\n")
            entries.forEach { (e, alive) ->
                val uptime = ((e.finishedAt ?: System.currentTimeMillis()) - e.startedAt) / 1000
                sb.append("[${e.id}] ")
                    .append(if (e.termination == "unverified") "status unknown (reconnect ${e.source.lowercase()})"
                        else if (alive) "running" else "${e.termination ?: "exited"} (exit ${e.exitCode?.toString() ?: "unknown"})")
                    .append(" (${uptime}s, via ").append(e.source.lowercase()).append(")")
                    .append(if (e.startedAt < store.bootAt) " [prev app session]" else "")
                    .append(" ")
                    .append(e.command.take(100)).append('\n')
                sb.append("  cwd: ").append(e.cwd).append('\n')
                sb.append("  owner: ").append(when {
                    e.ownerSessionId == null -> "unknown (legacy job)"
                    e.ownerSessionId == ctx.sessionId -> "this chat"
                    else -> "another chat (${e.ownerSessionId})"
                }).append('\n')
                val log = store.tail(e, 800)
                if (log.isNotBlank()) sb.append(log.trimEnd()).append('\n')
            }
            ToolResult(true, sb.toString().trimEnd())
        }
}

class BgKillTool(
    private val store: BgProcessStore,
) : Tool {
    override val name = "bg_kill"
    override val description = "Kill a background shell process by its id."
    override val parametersSchema = Schema.obj(
        mapOf("id" to Schema.integer("The process id returned by shell_background.")),
        required = listOf("id"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val id = args["id"]?.jsonPrimitive?.intOrNull
                ?: throw ToolFailure("Missing required argument: id")
            (ctx.workspace as? com.androidharness.app.workspace.SshFs)?.let {
                return@withContext com.androidharness.app.workspace.SshJobs.stop(it, id)
            }
            val entry = store.list().firstOrNull { it.first.id == id }?.first
            if (entry == null) ToolResult(false, "No background process with id $id.")
            else if (entry.finishedAt != null) ToolResult(true, "Background process $id already ${entry.termination ?: "exited"} (exit ${entry.exitCode?.toString() ?: "unknown"}).")
            else if (store.kill(id)) ToolResult(true, "Killed background process $id.")
            else ToolResult(false, "Failed to kill process $id.")
        }
}

internal fun backgroundJobMatches(entry: com.androidharness.app.data.BgProcessEntry, scope: String, ctx: ToolContext): Boolean = when (scope) {
    "all" -> true
    "workspace" -> ctx.workspace.shellRoot?.let { root ->
        java.io.File(entry.cwd).canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
    } == true
    else -> ctx.sessionId != null && entry.ownerSessionId == ctx.sessionId
}
