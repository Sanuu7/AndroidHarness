package com.androidharness.app.terminal

import com.androidharness.app.agent.RunManager
import com.androidharness.app.data.env.LinuxEnvironmentManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Owns the interactive (PTY) terminal sessions, one per local workspace.
 * Sessions run in the app process as the app user and hold the keepalive
 * while they run, so programs keep going when the app is in the background
 * or the screen rotates.
 */
class InteractiveTerminalManager(
    private val linuxEnv: LinuxEnvironmentManager,
    private val runManager: RunManager,
) {
    private val _sessions = MutableStateFlow<Map<String, TerminalSession>>(emptyMap())
    val sessions: StateFlow<Map<String, TerminalSession>> = _sessions

    /** The Terminal screen's last choice between the interactive terminal and command mode. */
    val preferInteractive = MutableStateFlow(true)

    /** Font size in sp, shared by every session so a pinch carries over. */
    val fontSizeSp = MutableStateFlow(13f)

    @Volatile private var storageMaintenance = false

    val supported: Boolean get() = Pty.available

    fun session(key: String): TerminalSession? = _sessions.value[key]

    fun hasRunning(): Boolean = _sessions.value.values.any { it.running }

    /** Blocks new sessions while storage cleanup runs; false when one is already running. */
    @Synchronized
    internal fun beginStorageMaintenance(): Boolean {
        if (hasRunning()) return false
        storageMaintenance = true
        return true
    }

    @Synchronized
    internal fun endStorageMaintenance() { storageMaintenance = false }

    /** Returns the running session for [key], starting a new shell in [cwd] when there is none. */
    @Synchronized
    fun start(key: String, cwd: File, rows: Int, cols: Int): TerminalSession {
        session(key)?.takeIf { it.running }?.let { return it }
        check(!storageMaintenance) { "Storage cleanup is running. Try again in a moment." }
        val launch = launchSpec()
        val dir = cwd.takeIf { it.isDirectory } ?: linuxEnv.shellFallbackRoot
        val pty = Pty.start(launch.executable, launch.argv, launch.env, dir, rows, cols)
        runManager.acquireKeepalive()
        val session = TerminalSession(key, launch.name, pty, rows, cols) { runManager.releaseKeepalive() }
        _sessions.update { it + (key to session) }
        return session
    }

    /** Ends the session for [key] and forgets it. */
    @Synchronized
    fun close(key: String) {
        val session = _sessions.value[key] ?: return
        session.terminate()
        _sessions.update { it - key }
    }

    private class Launch(val name: String, val executable: String, val argv: List<String>, val env: Map<String, String>)

    private fun launchSpec(): Launch {
        val env = LinkedHashMap(linuxEnv.processEnv())
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["HARNESS_TERMINAL"] = "interactive"
        // Termux-built ncurses looks for terminfo under its own build prefix.
        File(linuxEnv.prefix, "share/terminfo").takeIf { it.isDirectory }?.let { env["TERMINFO"] = it.absolutePath }
        env["HOME"]?.let { File(it).mkdirs() }
        env["TMPDIR"]?.let { File(it).mkdirs() }
        val bash = linuxEnv.bashExecutable()
        if (bash != null) {
            linuxEnv.ensureShims()
            val rc = writeRcFile()
            env["SHELL"] = bash.absolutePath
            val args = listOf(bash.absolutePath, "--rcfile", rc.absolutePath, "-i")
            val linker = linuxEnv.linkerPath()
            return if (linker != null) Launch("bash", linker, listOf(linker) + args, env)
            else Launch("bash", bash.absolutePath, args, env)
        }
        // No Linux environment yet: Android's own shell still gets a real terminal.
        env["SHELL"] = "/system/bin/sh"
        env["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
        env.remove("LD_PRELOAD")
        env.remove("LD_LIBRARY_PATH")
        return Launch("sh", "/system/bin/sh", listOf("sh", "-i"), env)
    }

    private fun writeRcFile(): File {
        val rc = File(linuxEnv.prefix, "etc/harness-terminal.bashrc")
        val shims = linuxEnv.shimFile
        val text = buildString {
            appendLine("# Written by AndroidHarness for the interactive terminal. Changes here are replaced.")
            appendLine("# Put your own settings in ~/.bashrc instead.")
            appendLine("[ -r ${quote(shims.absolutePath)} ] && . ${quote(shims.absolutePath)} 2>/dev/null")
            appendLine("HISTFILE=\"\$HOME/.bash_history\"")
            appendLine("HISTSIZE=5000")
            appendLine("HISTCONTROL=ignoredups")
            appendLine("shopt -s checkwinsize histappend 2>/dev/null")
            appendLine("PS1='\\[\\e[1;36m\\]\\w\\[\\e[0m\\] \\[\\e[1;32m\\]\\$\\[\\e[0m\\] '")
            appendLine("__harness_osc7() { printf '\\033]7;file://localhost%s\\033\\\\' \"\$PWD\"; }")
            appendLine("PROMPT_COMMAND=\"__harness_osc7\${PROMPT_COMMAND:+;\$PROMPT_COMMAND}\"")
            appendLine("alias ls='ls --color=auto' grep='grep --color=auto' 2>/dev/null")
            appendLine("[ -r \"\$HOME/.bashrc\" ] && . \"\$HOME/.bashrc\"")
        }
        rc.parentFile?.mkdirs()
        if (!rc.isFile || rc.readText() != text) rc.writeText(text)
        return rc
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
