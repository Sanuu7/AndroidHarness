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

    private val pidToLog = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val pidToDir = java.util.concurrent.ConcurrentHashMap<Int, String>()

    init {
        // PR_SET_CHILD_SUBREAPER (36) so orphaned descendants reparent to this service instead of PID 1
        runCatching {
            val method = android.system.Os::class.java.getMethod(
                "prctl",
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
            method.invoke(null, 36, 1L, 0L, 0L, 0L)
        }

        // Background daemon thread to continuously reap any defunct/zombie children
        Thread {
            while (true) {
                try {
                    Thread.sleep(1000)
                    reapZombies()
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "ZombieReaper"
            start()
        }
    }

    override fun spawnDetached(
        cmd: Array<String>,
        env: Array<String>?,
        dir: String?,
        logPath: String,
    ): Int {
        return try {
            val wrappedCmd = if (File("/system/bin/setsid").exists()) {
                arrayOf("/system/bin/setsid", *cmd)
            } else {
                cmd
            }
            val pb = ProcessBuilder(*wrappedCmd)
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
            log.createNewFile()
            val canonLog = runCatching { log.canonicalPath }.getOrDefault(logPath)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            pb.redirectErrorStream(true)
            val p = pb.start()
            val pid = pidOf(p) ?: -1
            if (pid > 0) {
                pidToLog[pid] = canonLog
                dir?.let { pidToDir[pid] = runCatching { File(it).canonicalPath }.getOrDefault(it) }
            }
            pid
        } catch (e: Exception) {
            -1
        }
    }

    override fun isProcessAlive(pid: Int): Boolean =
        runCatching { android.system.Os.kill(pid, 0); true }.getOrDefault(false)

    override fun killProcess(pid: Int): Boolean =
        runCatching {
            val logPath = pidToLog.remove(pid)
            val dir = pidToDir.remove(pid)

            // 1. Kill process group
            runCatching { android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL) }
            // 2. Kill all child processes by parent pid
            runCatching {
                val pkill = Runtime.getRuntime().exec(arrayOf("/system/bin/pkill", "-9", "-P", pid.toString()))
                pkill.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
            }
            // 3. Recursively kill all descendant processes and processes holding log fds
            killProcessesAssociatedWith(pid, logPath, dir)
            // 4. Kill the target pid
            runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
            reapZombies()
            true
        }.getOrDefault(false)

    private fun killProcessesAssociatedWith(rootPid: Int, logPath: String?, dir: String?) {
        val proc = File("/proc")
        val pidDirs = proc.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return
        val matchedPids = mutableSetOf<Int>()

        for (pDir in pidDirs) {
            val p = pDir.name.toIntOrNull() ?: continue
            if (p <= 1 || p == rootPid) continue

            // 1. Check parent PID from /proc/<p>/stat
            val statFile = File(pDir, "stat")
            val statContent = runCatching { statFile.readText() }.getOrNull()
            if (statContent != null) {
                val lastParen = statContent.lastIndexOf(')')
                if (lastParen > 0 && lastParen + 2 < statContent.length) {
                    val rest = statContent.substring(lastParen + 2).trimStart()
                    val tokens = rest.split(' ')
                    if (tokens.size >= 2) {
                        val ppid = tokens[1].toIntOrNull()
                        if (ppid == rootPid) {
                            matchedPids += p
                        }
                    }
                }
            }

            // 2. Check open file descriptors in /proc/<p>/fd
            if (!logPath.isNullOrBlank()) {
                val fdDir = File(pDir, "fd")
                val fds = runCatching { fdDir.listFiles() }.getOrNull()
                if (fds != null) {
                    for (fd in fds) {
                        val link = runCatching { android.system.Os.readlink(fd.absolutePath) }.getOrNull()
                        if (link != null && (link == logPath || link.startsWith(logPath))) {
                            matchedPids += p
                            break
                        }
                    }
                }
            }
        }

        // Kill all discovered processes (and their process groups if they called setsid)
        for (targetPid in matchedPids) {
            runCatching { killDescendants(targetPid) }
            runCatching { android.system.Os.kill(-targetPid, android.system.OsConstants.SIGKILL) }
            runCatching { android.system.Os.kill(targetPid, android.system.OsConstants.SIGKILL) }
        }
    }

    private fun killDescendants(rootPid: Int) {
        val proc = File("/proc")
        val pidDirs = proc.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return
        val children = mutableListOf<Int>()
        for (dir in pidDirs) {
            val p = dir.name.toIntOrNull() ?: continue
            if (p <= 1 || p == rootPid) continue
            val statFile = File(dir, "stat")
            val statContent = runCatching { statFile.readText() }.getOrNull() ?: continue
            val lastParen = statContent.lastIndexOf(')')
            if (lastParen > 0 && lastParen + 2 < statContent.length) {
                val rest = statContent.substring(lastParen + 2).trimStart()
                val tokens = rest.split(' ')
                if (tokens.size >= 2) {
                    val ppid = tokens[1].toIntOrNull()
                    if (ppid == rootPid) {
                        children += p
                    }
                }
            }
        }
        for (child in children) {
            runCatching { killDescendants(child) }
            runCatching { android.system.Os.kill(-child, android.system.OsConstants.SIGKILL) }
            runCatching { android.system.Os.kill(child, android.system.OsConstants.SIGKILL) }
        }
    }

    private val waitpidMethod by lazy {
        runCatching {
            android.system.Os::class.java.methods.firstOrNull { it.name == "waitpid" }
        }.getOrNull()
    }

    private fun reapZombies() {
        val method = waitpidMethod ?: return
        val paramTypes = method.parameterTypes
        val dummyStatus = if (paramTypes.size >= 2 && paramTypes[1] != Int::class.javaPrimitiveType) {
            runCatching { paramTypes[1].getDeclaredConstructor().newInstance() }.getOrNull()
        } else null

        val wnohang = 1 // WNOHANG constant
        while (true) {
            val res = runCatching {
                if (paramTypes.size == 3) {
                    (method.invoke(null, -1, dummyStatus, wnohang) as? Number)?.toInt() ?: 0
                } else if (paramTypes.size == 2) {
                    (method.invoke(null, -1, wnohang) as? Number)?.toInt() ?: 0
                } else 0
            }.getOrDefault(0)
            if (res <= 0) break
        }
    }

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
            val wrappedCmd = if (File("/system/bin/setsid").exists()) {
                arrayOf("/system/bin/setsid", *cmd)
            } else {
                cmd
            }
            val pb = ProcessBuilder(*wrappedCmd)
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
                reapZombies()
                break
            }
            if (System.currentTimeMillis() - startedAt > timeoutMs) {
                timedOut = true
                val pid = pidOf(process)
                if (pid != null && pid > 0) {
                    // 1. Kill process group
                    runCatching { android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL) }
                    // 2. Kill all child processes by parent pid
                    runCatching {
                        val pkill = Runtime.getRuntime().exec(arrayOf("/system/bin/pkill", "-9", "-P", pid.toString()))
                        pkill.waitFor(1, TimeUnit.SECONDS)
                    }
                    // 3. Recursively kill all descendant processes in /proc
                    runCatching { killDescendants(pid) }
                    // 4. Kill target process
                    runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
                }
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                reapZombies()
                break
            }
        }
        // Give the gobblers time to drain whatever the process managed to
        // write before it died, so partial output survives the timeout.
        out.join(2000)
        err.join(2000)
        reapZombies()

        return buildString {
            append("exit=").append(exit).append('\n')
            append("timeout=").append(if (timedOut) 1 else 0).append('\n')
            append(stdout)
            if (stdout.isNotEmpty() && !stdout.endsWith("\n")) append('\n')
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
