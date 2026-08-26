package com.androidharness.app.data

import android.content.Context
import com.androidharness.app.agent.RunManager
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    )

    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var readJob: Job? = null
    private var cwd: File = linuxEnv.shellFallbackRoot

    private val marker = "__HCTERM_DONE__"
    private val maxLines = 1_500

    /** Output-line batching interval (~15 fps), same cadence as chat streaming. */
    private val LINE_FLUSH_MS = 66L

    // Output lines are queued and flushed in batches: a chatty command used to
    // trigger an O(n) list copy per LINE (O(n²) per command with much output).
    private val pendingLock = Any()
    private val pendingLines = ArrayDeque<String>()
    private var flushJob: Job? = null

    /** Starts the terminal if it isn't running yet. */
    fun ensureStarted() {
        if (process != null || _state.value.started) return
        startAppShell()
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
        cwd = linuxEnv.shellFallbackRoot
        resetLines()
        _state.update {
            it.copy(started = true, cwd = cwd.absolutePath, lines = emptyList())
        }
        appendLines(listOf("# terminal ready: ${if (linuxEnv.bashExecutable() != null) "bash" else "toybox sh"} (app user)"))

        readJob = scope.launch {
            val input = process!!.inputStream.bufferedReader()
            val line = StringBuilder()
            val buf = CharArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
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
            // process ended
            process = null
            runManager.releaseKeepalive()
            _state.update { it.copy(started = false, busy = false) }
            appendLines(listOf("# shell exited"))
        }
    }

    private fun handleLine(line: String) {
        // marker lines carry exit code + cwd: __HCTERM_DONE__:<code>:<pwd>
        if (line.startsWith("$marker:")) {
            val rest = line.removePrefix("$marker:")
            val idx = rest.indexOf(':')
            if (idx > 0) {
                val newCwd = rest.substring(idx + 1)
                if (newCwd.isNotBlank()) {
                    cwd = File(newCwd)
                    _state.update { it.copy(cwd = newCwd, busy = false) }
                } else {
                    _state.update { it.copy(busy = false) }
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
    fun send(command: String) {
        val cmd = command.trimEnd()
        if (cmd.isEmpty()) return
        ensureStarted()
        if (_state.value.busy) {
            appendLines(listOf("# still running: wait for it to finish"))
            return
        }
        _state.update { it.copy(busy = true) }
        appendLines(listOf("\$ $cmd"))

        if (_state.value.privileged && shizuku.isGranted()) {
            sendPrivileged(cmd)
        } else {
            sendAppTier(cmd)
        }
    }

    private fun sendAppTier(cmd: String) {
        val p = process ?: run {
            _state.update { it.copy(busy = false) }
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
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun sendPrivileged(cmd: String) {
        scope.launch {
            // Make sure the shell-user toolchain copy exists before running.
            if (linuxEnv.isReady && !shizuku.isTmpPrefixDeployed()) {
                linuxEnv.ensureShellDeploy(shizuku)
            }
            val script = "cd \"\$HC_DIR\" && eval \"\$HC_CMD\"; ec=\$?; echo \"$marker:\$ec:\$PWD\""
            val env = linuxEnv.tmpProcessEnv()
                .plus("HC_DIR" to cwd.absolutePath)
                .plus("HC_CMD" to cmd)
                .map { "${it.key}=${it.value}" }.toTypedArray()
            val bash = "${LinuxEnvironmentManager.TMP_PREFIX_BASE}/linux/bin/bash"
            val useTmpBash = shizuku.isTmpPrefixDeployed()
            val argv = if (useTmpBash) arrayOf(bash, "-c", script) else arrayOf("/system/bin/sh", "-c", script)
            val res = shizuku.runPrivileged(argv, env, cwd.absolutePath, timeoutMs = 120_000, maxBytes = 60_000)
            if (res == null) {
                appendLines(listOf("# Shizuku unavailable: dropped to app tier"))
                _state.update { it.copy(privileged = false) }
                sendAppTier(cmd)
                return@launch
            }
            val lines = res.output.lines().toMutableList()
            // last marker line: parse, strip
            val markerIndex = lines.indexOfLast { it.startsWith("$marker:") }
            if (markerIndex >= 0) {
                val markerLine = lines.removeAt(markerIndex)
                val rest = markerLine.removePrefix("$marker:")
                val idx = rest.indexOf(':')
                if (idx > 0) {
                    val newCwd = rest.substring(idx + 1)
                    if (newCwd.isNotBlank()) {
                        cwd = File(newCwd)
                        _state.update { it.copy(cwd = newCwd) }
                    }
                }
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
        readJob?.cancel()
        readJob = null
        process?.let { p ->
            runCatching { p.destroyForcibly() }
            runCatching { p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
        }
        process = null
        runManager.releaseKeepalive()
    }
}
