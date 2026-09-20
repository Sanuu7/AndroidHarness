package com.androidharness.app.data

import android.content.Context
import com.androidharness.app.agent.RunManager
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShizukuManager
import com.androidharness.app.data.env.ShizukuState
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
) {

    data class TerminalState(
        val lines: List<String> = emptyList(),
        val cwd: String = "",
        val busy: Boolean = false,
        val privileged: Boolean = false,
        val started: Boolean = false,
        val lastCommand: String? = null,
        val lastExitCode: Int? = null,
    )

    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var process: Process? = null
    private var readJob: Job? = null
    private var sshJob: Job? = null
    private var cwd: File = linuxEnv.shellFallbackRoot
    private var remoteWorkspace: com.androidharness.app.workspace.SshFs? = null

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
            shizuku.state.collect { state ->
                _state.update { it.copy(privileged = state == ShizukuState.GRANTED) }
            }
        }
    }

    /** Starts the terminal if it isn't running yet. */
    fun ensureStarted() {
        if (remoteWorkspace != null) {
            _state.update { it.copy(started = true, cwd = cwd.absolutePath) }
            return
        }
        if (process != null || _state.value.started) return
        startAppShell()
    }

    fun useWorkspace(fs: com.androidharness.app.workspace.WorkspaceFs) {
        if (_state.value.busy) return
        val remote = fs as? com.androidharness.app.workspace.SshFs
        val root = remote?.root?.let { File(it) } ?: fs.shellRoot ?: linuxEnv.shellFallbackRoot
        if (remoteWorkspace?.displayPath == remote?.displayPath && root == cwd) return
        stopProcess()
        remoteWorkspace = remote
        cwd = root
        _state.update { it.copy(started = false, cwd = root.path) }
    }

    fun setPrivileged(on: Boolean) {
        _state.update { it.copy(privileged = on) }
    }

    private fun startAppShell() {
        stopProcess()
        val builder = runCatching {
            val bash = linuxEnv.bashExecutable()
            if (bash != null) {
                val linker = when (android.os.Build.SUPPORTED_ABIS.firstOrNull()) {
                    "x86_64", "arm64-v8a" -> "/system/bin/linker64"
                    else -> "/system/bin/linker"
                }
                if (File(linker).exists()) ProcessBuilder(linker, bash.absolutePath)
                else ProcessBuilder(bash.absolutePath)
            } else {
                ProcessBuilder("sh")
            }
        }.getOrElse { ProcessBuilder("sh") }

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
        resetLines()
        _state.update {
            it.copy(started = true, cwd = cwd.absolutePath, lines = emptyList())
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
    fun send(command: String, workspace: com.androidharness.app.workspace.WorkspaceFs? = null) {
        val cmd = command.trimEnd()
        if (cmd.isEmpty()) return
        if (_state.value.busy) {
            appendLines(listOf("# still running: wait for it to finish"))
            return
        }
        workspace?.let { useWorkspace(it) }
        ensureStarted()
        _state.update { it.copy(busy = true, lastCommand = cmd, lastExitCode = null) }
        appendLines(listOf("\$ $cmd"))

        if (remoteWorkspace != null) {
            sendSsh(cmd)
        } else if (_state.value.privileged && shizuku.isGranted()) {
            sendPrivileged(cmd)
        } else {
            sendAppTier(cmd)
        }
    }

    private fun sendSsh(cmd: String) {
        val workspace = remoteWorkspace ?: return
        sshJob = scope.launch {
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
        scope.launch {
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
        scope.launch {
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
                return@launch
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

    /** Called when the terminal screen is left for good. */
    fun stopTerminal() {
        stopProcess()
        resetLines()
        _state.update { it.copy(started = false, busy = false, lines = emptyList()) }
    }

    private fun stopProcess() {
        sshJob?.cancel()
        sshJob = null
        readJob?.cancel()
        readJob = null
        val previous = synchronized(this) { process.also { process = null } }
        previous?.let { p ->
            runManager.releaseKeepalive()
            runCatching { p.destroyForcibly() }
            runCatching { p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
        }
    }
}
