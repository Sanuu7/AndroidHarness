package com.androidharness.app.data.env

import android.content.Context
import androidx.annotation.Keep
import com.androidharness.app.IHarnessService
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The IHarnessService implementation. Shizuku's server loads this class into
 * its own process (running as the shell uid, or root with Sui) and calls its
 * methods directly; the app never runs this code in its own process.
 *
 * Both the implicit no-arg constructor (Shizuku v12) and the Context
 * constructor (v13) are required. [Keep] stops R8 from stripping the class.
 */
@Keep
class HarnessUserService() : IHarnessService.Stub() {

    @Keep
    constructor(context: Context?) : this()

    override fun spawnDetached(
        cmd: Array<String>,
        env: Array<String>?,
        dir: String?,
        logPath: String,
    ): Int {
        return try {
            val pb = ProcessBuilder(*cmd)
            pb.directory(dir?.let { File(it) })
            if (env != null) {
                val e = pb.environment()
                for (entry in env) {
                    val i = entry.indexOf('=')
                    if (i > 0) e[entry.substring(0, i)] = entry.substring(i + 1)
                }
            }
            val log = File(logPath)
            log.parentFile?.mkdirs()
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            pb.redirectErrorStream(true)
            val p = pb.start()
            pidOf(p) ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    override fun isProcessAlive(pid: Int): Boolean =
        runCatching { android.system.Os.kill(pid, 0); true }.getOrDefault(false)

    override fun killProcess(pid: Int): Boolean =
        runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL); true }
            .getOrDefault(false)

    /** java.lang.ProcessImpl keeps the pid in a private field. */
    private fun pidOf(p: Process): Int? = runCatching {
        val f = p.javaClass.getDeclaredField("pid")
        f.isAccessible = true
        f.getInt(p)
    }.getOrNull()

    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    override fun exec(
        cmd: Array<String>,
        env: Array<String>?,
        dir: String?,
        maxBytes: Int,
        timeoutMs: Int,
    ): String {
        val process = try {
            val pb = ProcessBuilder(*cmd)
            pb.directory(dir?.let { File(it) })
            if (env != null) {
                val e = pb.environment()
                for (entry in env) {
                    val i = entry.indexOf('=')
                    if (i > 0) e[entry.substring(0, i)] = entry.substring(i + 1)
                }
            }
            pb.start()
        } catch (e: Exception) {
            return "exit=-1\ntimeout=0\nfailed to launch: ${e.message}"
        }

        val limit = maxBytes.coerceIn(1024, 512_000)
        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val out = Thread { gobble(process.inputStream, stdout, limit) }
        val err = Thread { gobble(process.errorStream, stderr, limit) }
        listOf(out, err).forEach { it.isDaemon = true; it.start() }

        val startedAt = System.currentTimeMillis()
        var timedOut = false
        var exit = -1
        while (true) {
            if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                exit = process.exitValue()
                break
            }
            if (System.currentTimeMillis() - startedAt > timeoutMs) {
                timedOut = true
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                break
            }
        }
        // Give the gobblers time to drain whatever the process managed to
        // write before it died, so partial output survives the timeout.
        out.join(2000)
        err.join(2000)

        return buildString {
            append("exit=").append(exit).append('\n')
            append("timeout=").append(if (timedOut) 1 else 0).append('\n')
            append(stdout)
            append(STDERR_SEPARATOR).append('\n')
            append(stderr)
        }
    }

    private fun gobble(stream: java.io.InputStream, into: StringBuffer, max: Int) {
        try {
            val buf = CharArray(4096)
            stream.bufferedReader().use { reader ->
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    synchronized(into) {
                        if (into.length < max) {
                            val toAppend = minOf(n, max - into.length)
                            into.append(buf, 0, toAppend)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Marks where captured stdout ends and stderr begins in exec() results. */
        const val STDERR_SEPARATOR = "<<<HARNESS_STDERR>>>"
    }
}
