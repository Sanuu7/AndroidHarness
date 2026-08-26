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
) : Tool {
    override val name = "shell_background"
    override val description =
        "Start a long-running shell command in the background (e.g. a dev server). " +
        "When Shizuku is connected the process runs inside its server and survives the app " +
        "being minimized or even killed; otherwise it runs as an app child kept alive by the " +
        "foreground service. Returns a process id; its output goes to a log file. " +
        "Use bg_list to inspect and bg_kill to stop it."
    override val parametersSchema = Schema.obj(
        mapOf("command" to Schema.string("The shell command to run in the background.")),
        required = listOf("command"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val command = args["command"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: command")
            val cwd = ctx.workspace.shellRoot
                ?: throw ToolFailure(
                    "This workspace has no real filesystem path, so background processes cannot " +
                        "start here. Switch to a device folder or the app workspace.",
                )
            try {
                val started = store.start(command, cwd)
                val notes = StringBuilder()
                if (started.viaShizuku) {
                    notes.append("[note: running under Shizuku: survives the app being minimized or killed]\n")
                }
                ToolResult(
                    true,
                    notes.toString() +
                        "Started background process ${started.entry.id}: ${command.take(80)}\n" +
                        "Log: ${started.entry.logPath}",
                )
            } catch (e: Exception) {
                // App-tier background processes run as the app uid, which may
                // not reach folders outside its own — start Shizuku for those.
                val hint =
                    "[note: without Shizuku, background processes run as the app user and could not " +
                        "start in $cwd. Start Shizuku (Settings → Terminal) to run servers anywhere, " +
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
        "List background shell processes started with shell_background, with status and recent log output."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val entries = store.list()
            if (entries.isEmpty()) return@withContext ToolResult(true, "No background processes running.")
            val sb = StringBuilder()
            entries.forEach { (e, alive) ->
                val uptime = (System.currentTimeMillis() - e.startedAt) / 1000
                sb.append("[${e.id}] ")
                    .append(if (alive) "running" else "exited")
                    .append(" (${uptime}s, via ").append(e.source.lowercase()).append(") ")
                    .append(e.command.take(100)).append('\n')
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
            if (store.get(id) == null) ToolResult(false, "No background process with id $id.")
            else if (store.kill(id)) ToolResult(true, "Killed background process $id.")
            else ToolResult(false, "Failed to kill process $id.")
        }
}
