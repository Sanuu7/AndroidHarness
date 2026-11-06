package com.androidharness.app.data

import android.content.Context
import com.androidharness.app.agent.RunManager
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShizukuManager
import com.androidharness.app.data.env.ShizukuState
import com.androidharness.app.data.env.UserServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * A persistent interactive terminal for the Terminal screen. App tier: one
 * long-lived bash (or toybox sh) process with a marker protocol for command
 * completion + cwd tracking. Privileged tier: per-command exec through the
 * Shizuku user service with client-side cd tracking.
 *
 * The shell lives in the app-wide scope and holds the keepalive, so it keeps
 * running while the app is minimized.
 */
class TerminalManager(
    private val context: Context,
    private val linuxEnv: LinuxEnvironmentManager,
    private val shizuku: ShizukuManager,
    private val runManager: RunManager,
    private val router: com.androidharness.app.data.env.ShellTierRouter? = null,
    private val interactive: com.androidharness.app.terminal.InteractiveTerminalManager? = null,
) {

    data class TerminalState(
        val lines: List<String> = emptyList(),
        val cwd: String = "",
        val busy: Boolean = false,
        val stopping: Boolean = false,
        val privileged: Boolean = false,
        val started: Boolean = false,
        val lastCommand: String? = null,
        val lastExitCode: Int? = null,
        val backendLabel: String = "Normal",
    )

    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var process: Process? = null
    private var readJob: Job? = null
    @Volatile private var commandJob: Job? = null
    private var cwd: File = linuxEnv.shellFallbackRoot
    private var remoteWorkspace: com.androidharness.app.workspace.SshFs? = null
    private var workspacePath: String? = null

    private val marker = "__HCTERM_DONE__"
    private val maxLines = 1_500

    /** Output-line batching interval (~15 fps), same cadence as chat streaming. */
    private val LINE_FLUSH_MS = 66L

    // Output lines are queued and flushed in batches: a chatty command used to
    // trigger an O(n) list copy per LINE (O(n²) per command with much output).
    private val pendingLock = Any()
    private val pendingLines = ArrayDeque<String>()
    private var flushJob: Job? = null

    init {
        scope.launch {
            kotlinx.coroutines.flow.combine(shizuku.state, shizuku.serviceState) { state, service ->
                state == ShizukuState.GRANTED && service.isReady
            }.collect { ready ->
                _state.update { it.copy(privileged = ready) }
            }
        }
    }

    private var storageMaintenance = false

    suspend fun <T> withClosedStorage(action: suspend () -> T): T {
        synchronized(this) {
            check(!storageMaintenance && !_state.value.started && !_state.value.busy && process == null) {
                "Close the terminal before cleaning storage."
            }
            check(interactive?.beginStorageMaintenance() != false) {
                "Close the interactive terminal before cleaning storage."
            }
            storageMaintenance = true
        }
        try { return action() } finally {
            synchronized(this) { storageMaintenance = false }
            interactive?.endStorageMaintenance()
        }
    }

    /** Starts the terminal if it isn't running yet. */
    @Synchronized
    fun ensureStarted() {
        if (storageMaintenance || _state.value.stopping) return
        if (remoteWorkspace != null) {
            _state.update { it.copy(started = true, cwd = cwd.absolutePath) }
            return
        }
        if (process != null || _state.value.started) return
        startAppShell()
    }

    fun useWorkspace(fs: com.androidharness.app.workspace.WorkspaceFs) {
        if (_state.value.busy || _state.value.stopping) return
        // cwd changes after `cd` and can use a different Android path alias.
        // Rebinding the same workspace must not restart the shell or erase output.
        if (workspacePath == fs.displayPath) return
        val remote = fs as? com.androidharness.app.workspace.SshFs
        val root = remote?.root?.let { File(it) } ?: fs.shellRoot ?: linuxEnv.shellFallbackRoot
        stopProcess()
        workspacePath = fs.displayPath
        remoteWorkspace = remote
        cwd = root
        _state.update { it.copy(started = false, cwd = root.path) }
    }

    fun setPrivileged(on: Boolean) {
        _state.update { it.copy(privileged = on && shizuku.isGranted() && shizuku.isServiceReady()) }
    }

    private fun startAppShell() {
        stopProcess()
        val builder = runCatching { linuxEnv.shellProcessBuilder() }
            .getOrElse { ProcessBuilder("sh") }

        builder.directory(cwd)
        builder.redirectErrorStream(true)
        builder.environment().putAll(linuxEnv.processEnv())
        builder.environment()["PS1"] = ""

        try {
            process = builder.start()
        } catch (e: Exception) {
            appendLines(listOf("failed to start shell: ${e.message}"))
            return
        }
        runManager.acquireKeepalive()
        val keepOutput = _state.value.lastExitCode == -130
        if (!keepOutput) resetLines()
        _state.update {
            it.copy(started = true, cwd = cwd.absolutePath, lines = if (keepOutput) it.lines else emptyList())
        }
        appendLines(listOf("# terminal ready: ${if (linuxEnv.bashExecutable() != null) "bash" else "toybox sh"} (app user)"))

        val startedProcess = process ?: return
        readJob = scope.launch {
            try {
                val input = startedProcess.inputStream.bufferedReader()
                val line = StringBuilder()
                val buf = CharArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (process !== startedProcess) break
                    for (i in 0 until n) {
                        val ch = buf[i]
                        if (ch == '\n') {
                            handleLine(line.toString())
                            line.setLength(0)
                        } else if (ch != '\r') {
                            line.append(ch)
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // Switching workspace closes the previous shell's reader.
            } finally {
                synchronized(this@TerminalManager) {
                    if (process === startedProcess) {
                        process = null
                        runManager.releaseKeepalive()
                        _state.update {
                            it.copy(started = false, busy = false,
                                lastExitCode = if (it.busy && it.lastExitCode == null) -1 else it.lastExitCode)
                        }
                        appendLines(listOf("# shell exited"))
                    }
                }
            }
        }
    }

    private fun handleLine(line: String) {
        // marker lines carry exit code + cwd: __HCTERM_DONE__:<code>:<pwd>
        if (line.startsWith("$marker:")) {
            val rest = line.removePrefix("$marker:")
            val idx = rest.indexOf(':')
            if (idx > 0) {
                val exitCode = rest.substring(0, idx).toIntOrNull()
                val newCwd = rest.substring(idx + 1)
                if (newCwd.isNotBlank()) {
                    cwd = File(newCwd)
                    _state.update { it.copy(cwd = newCwd, busy = false, lastExitCode = exitCode) }
                } else {
                    _state.update { it.copy(busy = false, lastExitCode = exitCode) }
                }
            } else {
                _state.update { it.copy(busy = false) }
            }
            return
        }
        appendLines(listOf(line))
    }

    private fun appendLines(new: List<String>) {
        synchronized(pendingLock) { pendingLines.addAll(new) }
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(LINE_FLUSH_MS)
            val batch = synchronized(pendingLock) {
                val out = ArrayList<String>(pendingLines.size)
                out.addAll(pendingLines)
                pendingLines.clear()
                out
            }
            if (batch.isNotEmpty()) {
                _state.update {
                    val combined = it.lines + batch
                    it.copy(lines = if (combined.size > maxLines) combined.takeLast(maxLines) else combined)
                }
            }
        }
    }

    /** Discards queued-but-unflushed lines; call before clearing [TerminalState.lines]. */
    private fun resetLines() {
        flushJob?.cancel()
        flushJob = null
        synchronized(pendingLock) { pendingLines.clear() }
    }

    /** Sends one command line. */
    @Synchronized
    fun send(command: String, workspace: com.androidharness.app.workspace.WorkspaceFs? = null, sessionId: String? = null, projectId: String? = null) {
        if (storageMaintenance) { appendLines(listOf("# storage cleanup in progress; try again shortly")); return }
        val cmd = command.trimEnd()
        if (cmd.isEmpty()) return
        if (_state.value.busy || _state.value.stopping) {
            appendLines(listOf("# still running: wait for it to finish"))
            return
        }
        workspace?.let { useWorkspace(it) }
        ensureStarted()
        _state.update { it.copy(busy = true, lastCommand = cmd, lastExitCode = null) }
        appendLines(listOf("\$ $cmd"))

        if (remoteWorkspace != null) {
            sendSsh(cmd)
        } else if (router?.deviceAccess != null) {
            sendDevice(cmd, sessionId, projectId)
        } else if (_state.value.privileged && shizuku.isGranted()) {
            sendPrivileged(cmd)
        } else {
            sendAppTier(cmd)
        }
    }

    private fun launchCommand(block: suspend CoroutineScope.() -> Unit) {
        // Publish the handle before it can run, so Stop cannot miss a newly started worker.
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY, block = block)
        commandJob = job
        job.start()
    }

    private fun sendDevice(cmd: String, sessionId: String?, projectId: String?) {
        val access = router?.deviceAccess ?: return
        launchCommand {
            access.withRequest(projectId, sessionId, forceDevice = true, manual = true) {
                try {
                    val decision = access.select()
                    if (decision.backend == com.androidharness.app.data.env.DeviceBackend.NORMAL) {
                        _state.update { it.copy(backendLabel = "Normal") }
                        decision.reason?.let { appendLines(listOf("# Access fallback: $it; using Normal")) }
                        sendAppTier(cmd)
                        return@withRequest
                    }
                    if (decision.backend == null) {
                        appendLines(listOf("# ${decision.reason}"))
                        _state.update { it.copy(busy = false, lastExitCode = -1) }
                        return@withRequest
                    }
                    val quoted = "'" + cmd.replace("'", "'\\''") + "'"
                    val script = "eval $quoted; ec=\$?; printf '\\n$marker:%s:%s\\n' \"\$ec\" \"\$PWD\"; exit \"\$ec\""
                    val result = router.run(script, cwd, 120_000, 60_000)
                    result.rawOutput.lines().forEach(::handleLine)
                    if (result.rawStderr.isNotBlank()) appendLines(result.rawStderr.lines())
                    result.note?.let { appendLines(listOf(it)) }
                    val receipt = kotlinx.coroutines.currentCoroutineContext()[com.androidharness.app.data.env.AccessRequest]?.receipts?.lastOrNull()
                    _state.update { it.copy(busy = false, lastExitCode = result.exitCode, backendLabel = receipt?.label ?: "Unavailable") }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    appendLines(listOf("# ${e.message}"))
                    _state.update { it.copy(busy = false, lastExitCode = -1) }
                }
            }
        }
    }

    private fun sendSsh(cmd: String) {
        val workspace = remoteWorkspace ?: return
        launchCommand {
            runManager.acquireKeepalive()
            try {
                // A fresh exec channel per command, with cwd tracked like the
                // Shizuku tier. Shell variables do not persist between commands.
                val quote = "'" + cmd.replace("'", "'\\''") + "'"
                val script = "eval $quote; ec=\$?; printf '\\n$marker:%s:%s\\n' \"\$ec\" \"\$PWD\"; exit \"\$ec\""
                val result = workspace.run(script, cwd.path, 120_000, 60_000)
                result.rawOutput.lines().forEach(::handleLine)
                if (result.rawStderr.isNotBlank()) appendLines(result.rawStderr.lines())
                if (result.timedOut) appendLines(listOf("# SSH command timed out; check Termux before retrying a modifying command"))
                _state.update { it.copy(lastExitCode = result.exitCode) }
            } finally {
                _state.update { it.copy(busy = false) }
                runManager.releaseKeepalive()
            }
        }
    }

    private fun sendAppTier(cmd: String) {
        val p = process ?: run {
            _state.update { it.copy(busy = false, lastExitCode = -1) }
            return
        }
        launchCommand {
            try {
                val out = p.outputStream
                // The marker echoes the exit code and the new pwd in one shot.
                out.write((cmd + "\n" + "echo \"$marker:\$?:\$PWD\"\n").toByteArray())
                out.flush()
            } catch (e: Exception) {
                appendLines(listOf("write failed: ${e.message}"))
                _state.update { it.copy(busy = false, lastExitCode = -1) }
            }
        }
    }

    private fun sendPrivileged(cmd: String) {
        launchCommand {
            // Make sure the shell-user toolchain copy matches the package set
            // tmpProcessEnv() is built for before running from it: a copy from
            // an older set is missing the libraries that env names.
            val expected = linuxEnv.deployedTag()
            if (linuxEnv.isReady && !shizuku.isTmpPrefixDeployed(expected)) {
                linuxEnv.ensureShellDeploy(shizuku)
            }
            val script = "cd \"\$HC_DIR\" && eval \"\$HC_CMD\"; ec=\$?; echo \"$marker:\$ec:\$PWD\""
            val env = linuxEnv.tmpProcessEnv()
                .plus("HC_DIR" to cwd.absolutePath)
                .plus("HC_CMD" to cmd)
                .map { "${it.key}=${it.value}" }.toTypedArray()
            val bash = "${linuxEnv.tmpPrefix}/bin/bash"
            val useTmpBash = shizuku.isTmpPrefixDeployed(expected)
            val argv = if (useTmpBash) arrayOf(bash, "-c", script) else arrayOf("/system/bin/sh", "-c", script)
            val res = shizuku.runPrivileged(argv, env, cwd.absolutePath, timeoutMs = 120_000, maxBytes = 60_000)
            if (res == null) {
                appendLines(listOf("# Shizuku unavailable: dropped to app tier"))
                sendAppTier(cmd)
                return@launchCommand
            }
            val lines = res.output.lines().toMutableList()
            res.stderr.trim().takeIf { it.isNotEmpty() }?.let { err ->
                // keep stderr visible in the terminal, as before the split
                lines.addAll(err.lines())
            }
            // last marker line: parse, strip
            val markerIndex = lines.indexOfLast { it.startsWith("$marker:") }
            if (markerIndex >= 0) {
                val markerLine = lines.removeAt(markerIndex)
                val rest = markerLine.removePrefix("$marker:")
                val idx = rest.indexOf(':')
                if (idx > 0) {
                    val exitCode = rest.substring(0, idx).toIntOrNull()
                    val newCwd = rest.substring(idx + 1)
                    if (newCwd.isNotBlank()) {
                        cwd = File(newCwd)
                        _state.update { it.copy(cwd = newCwd, lastExitCode = exitCode) }
                    } else {
                        _state.update { it.copy(lastExitCode = exitCode) }
                    }
                }
            } else {
                _state.update { it.copy(lastExitCode = res.exitCode) }
            }
            appendLines(lines.filter { it.isNotBlank() })
            _state.update { it.copy(busy = false) }
        }
    }

    fun clear() {
        resetLines()
        _state.update { it.copy(lines = emptyList()) }
    }

    /** Wait for this command's cleanup before allowing another command to use the terminal. */
    fun interrupt() {
        val running = synchronized(this) {
            if (!_state.value.busy || _state.value.stopping) return
            _state.update { it.copy(stopping = true) }
            commandJob.also { it?.cancel() }
        }
        scope.launch {
            running?.join()
            synchronized(this@TerminalManager) {
                stopProcess()
                _state.update { it.copy(started = false, busy = false, stopping = false, lastExitCode = -130) }
            }
            appendLines(listOf("# command stopped"))
        }
    }

    /** Called when the terminal screen is left for good. */
    fun stopTerminal() {
        stopProcess()
        resetLines()
        _state.update { it.copy(started = false, busy = false, lines = emptyList()) }
    }

    private fun stopProcess() {
        commandJob?.cancel()
        commandJob = null
        readJob?.cancel()
        readJob = null
        val previous = synchronized(this) { process.also { process = null } }
        previous?.let { p ->
            runManager.releaseKeepalive()
            val pid = runCatching {
                p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p)
            }.getOrNull() ?: Regex("pid=(\\d+)").find(p.toString())?.groupValues?.get(1)?.toIntOrNull()
            if (pid != null && pid > 0) {
                val children = File("/proc").listFiles().orEmpty().mapNotNull { entry ->
                    val child = entry.name.toIntOrNull() ?: return@mapNotNull null
                    val parent = runCatching { File(entry, "status").useLines { lines ->
                        lines.firstOrNull { it.startsWith("PPid:") }?.substringAfter(':')?.trim()?.toIntOrNull()
                    } }.getOrNull() ?: return@mapNotNull null
                    child to parent
                }
                fun descendants(parent: Int): List<Int> = children.filter { it.second == parent }
                    .flatMap { descendants(it.first) + it.first }
                descendants(pid).forEach { child -> runCatching { android.system.Os.kill(child, android.system.OsConstants.SIGKILL) } }
            }
            runCatching { p.destroyForcibly() }
            runCatching { p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
        }
    }
}
