package com.androidharness.app.tools

import com.androidharness.app.data.env.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Explicit owner-app execution; never silently substitutes for ADB, root, or SSH. */
class DeviceOwnerShellTool(private val manager: DhizukuManager, private val access: DeviceAccessManager) : Tool {
    override val name = "device_owner_shell"
    override val description = "Run an Android shell command through an explicitly authorized Dhizuku Device Owner service. " +
        "Runs as the owner application's UID, NOT ADB shell or root. Android app-UID and SELinux limits still apply; " +
        "this does not turn shell commands into DevicePolicyManager API calls. Use shell access=shizuku for Shizuku/Shevery ADB commands. " +
        "Commands run on this Android device with / as the working directory, not the project folder. No automatic fallback or retry."
    override val parametersSchema = Schema.obj(mapOf(
        "command" to Schema.string("Android shell command to execute as the Device Owner app. Use absolute device paths. No background jobs."),
        "timeout_seconds" to Schema.integer("Timeout in seconds; default 30, maximum 120."),
    ), required = listOf("command"))
    override val isReadOnly = false
    override fun isAvailable(ctx: ToolContext) = ctx.workspace !is com.androidharness.app.workspace.SshFs &&
        manager.state.value.status == DhizukuStatus.GRANTED

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (ctx.workspace is com.androidharness.app.workspace.SshFs) throw ToolFailure("Dhizuku runs on this Android device, not an SSH host.")
        if (access.paused.value || access.preferences.value.mode == AccessMode.NORMAL)
            throw ToolFailure("Device Owner commands are disabled while privileged access is paused or Normal mode is selected.")
        val command = args["command"]?.jsonPrimitive?.content ?: throw ToolFailure("Missing required argument: command")
        if (!ctx.sandboxOff) {
            val root = ctx.workspace.shellRoot ?: throw ToolFailure("Device Owner commands require an editable device workspace.")
            ShellPolicy.denyReason(command, root, java.io.File("/"))?.let { return ToolResult(false, it) }
        }
        access.clearPhoneControl(DeviceBackend.DHIZUKU, command)?.let { return ToolResult(false, it) }
        val timeout = (args["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 120)
        return try {
            val result = manager.run(command, timeout * 1000L)
            access.recordOwnerExecution()
            ToolResult(result.exitCode == 0 && !result.timedOut, buildString {
                appendLine("Dhizuku Device Owner app UID (not ADB/root).")
                appendLine("exit code: ${result.exitCode}; timeout: ${result.timedOut}")
                appendLine("stdout (JSON string): ${JsonPrimitive(result.output)}")
                append("stderr (JSON string): ${JsonPrimitive(result.stderr)}")
            }, execution = listOf(ExecutionReceipt(DeviceBackend.DHIZUKU)))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ToolResult(false, e.message ?: "Dhizuku is unavailable; reconnect in Settings.") }
    }
}
