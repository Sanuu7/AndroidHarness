package com.androidharness.app.terminal

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * A child process attached to a pseudo-terminal. The child is a session
 * leader with the PTY slave as its controlling terminal, so the kernel line
 * discipline turns Ctrl+C into SIGINT for the foreground job and a resize
 * into SIGWINCH.
 */
class Pty private constructor(
    val pid: Int,
    private val masterFd: Int,
    private val descriptor: ParcelFileDescriptor,
) {
    val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)
    private val writeLock = Any()
    @Volatile var closed = false
        private set

    fun write(bytes: ByteArray) {
        if (closed || bytes.isEmpty()) return
        synchronized(writeLock) {
            try {
                output.write(bytes)
            } catch (_: IOException) {
                // The child has exited; the reader notices and reports it.
            }
        }
    }

    fun resize(rows: Int, cols: Int, widthPx: Int = 0, heightPx: Int = 0) {
        if (!closed) nativeResize(masterFd, rows, cols, widthPx, heightPx)
    }

    /** Blocks until the child exits; returns its exit code, or 128 + signal number. */
    fun waitFor(): Int = nativeWaitFor(pid)

    fun foregroundGroup(): Int = if (closed) -1 else nativeForegroundGroup(masterFd)

    /** Sends [signal] to the child's whole session, which owns every job it started. */
    fun signal(signal: Int) {
        runCatching { android.system.Os.kill(-pid, signal) }
        runCatching { android.system.Os.kill(pid, signal) }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { descriptor.close() }
    }

    companion object {
        /** False when the native library is missing (builds without the NDK). */
        val available: Boolean by lazy {
            runCatching { System.loadLibrary("harness_pty") }.isSuccess
        }

        fun start(executable: String, argv: List<String>, env: Map<String, String>, cwd: File, rows: Int, cols: Int): Pty {
            check(available) { "This build has no terminal support library." }
            val pid = IntArray(1)
            val envp = env.map { "${it.key}=${it.value}" }.toTypedArray()
            val fd = nativeStart(executable, argv.toTypedArray(), envp, cwd.absolutePath, rows.coerceAtLeast(1), cols.coerceAtLeast(1), pid)
            return Pty(pid[0], fd, ParcelFileDescriptor.adoptFd(fd))
        }

        @JvmStatic private external fun nativeStart(
            path: String, argv: Array<String>, envp: Array<String>, cwd: String, rows: Int, cols: Int, pid: IntArray,
        ): Int
        @JvmStatic private external fun nativeResize(fd: Int, rows: Int, cols: Int, widthPx: Int, heightPx: Int)
        @JvmStatic private external fun nativeWaitFor(pid: Int): Int
        @JvmStatic private external fun nativeForegroundGroup(fd: Int): Int
    }
}
