package com.androidharness.app.terminal

import android.system.OsConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A running program on a pseudo-terminal plus the screen it draws. Output is
 * parsed on a reader thread; the UI reads [emulator] while holding its lock.
 * The session lives outside any screen, so rotating the device or leaving the
 * Terminal keeps the program running.
 */
class TerminalSession internal constructor(
    val key: String,
    val shellName: String,
    private val pty: Pty,
    rows: Int,
    cols: Int,
    private val onExit: (TerminalSession) -> Unit,
) {
    val emulator = TerminalEmulator(rows, cols, maxHistory = 3_000)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val _exitCode = MutableStateFlow<Int?>(null)
    /** Exit code once the shell has ended, or null while it runs. */
    val exitCode: StateFlow<Int?> = _exitCode
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title
    private val _directory = MutableStateFlow<String?>(null)
    val directory: StateFlow<String?> = _directory
    @Volatile var onBell: (() -> Unit)? = null

    val running: Boolean get() = _exitCode.value == null
    val pid: Int get() = pty.pid

    init {
        emulator.onReply = { pty.write(it) }
        emulator.onBell = { onBell?.invoke() }
        thread(name = "terminal-read-${pty.pid}", isDaemon = true) {
            val buffer = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = pty.input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    synchronized(emulator) {
                        emulator.append(buffer, 0, n)
                        _title.value = emulator.title
                        _directory.value = emulator.workingDirectory
                    }
                    notifyListeners()
                }
            } catch (_: IOException) {
                // EIO once every holder of the slave side is gone.
            }
            pty.close()
        }
        thread(name = "terminal-wait-${pty.pid}", isDaemon = true) {
            val code = pty.waitFor()
            // Jobs the shell left behind still hold the terminal open; hang them up.
            pty.signal(OsConstants.SIGHUP)
            _exitCode.value = code
            notifyListeners()
            onExit(this)
        }
    }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun notifyListeners() = listeners.forEach { it() }

    fun write(bytes: ByteArray) {
        if (running) pty.write(bytes)
    }

    fun writeText(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun paste(text: String) {
        val bracketed = synchronized(emulator) { emulator.bracketedPaste }
        write(KeyEncoder.paste(text, bracketed))
    }

    fun resize(rows: Int, cols: Int, widthPx: Int, heightPx: Int) {
        val changed = synchronized(emulator) {
            val before = emulator.rows to emulator.cols
            emulator.resize(rows, cols)
            before != (emulator.rows to emulator.cols)
        }
        if (changed && running) pty.resize(rows, cols, widthPx, heightPx)
        if (changed) notifyListeners()
    }

    fun transcript(): String = synchronized(emulator) { emulator.transcriptText() }

    /** Hangs up the shell, then kills it if it is still there shortly after. */
    fun terminate() {
        if (!running) return
        pty.signal(OsConstants.SIGHUP)
        thread(name = "terminal-kill-${pty.pid}", isDaemon = true) {
            Thread.sleep(1_500)
            if (running) pty.signal(OsConstants.SIGKILL)
        }
    }
}
