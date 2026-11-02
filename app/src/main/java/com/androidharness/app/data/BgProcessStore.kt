package com.androidharness.app.data

import android.content.Context
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class BgProcessEntry(
    val id: Int,
    val command: String,
    val cwd: String,
    val logPath: String,
    /** Shell-uid pid for Shizuku-spawned processes; -1 for app-tier. */
    val pid: Int = -1,
    /** "APP" = child of the app process; "SHIZUKU" = inside Shizuku's server. */
    val source: String = "APP",
    val startedAt: Long = 0L,
    val exitCodePath: String? = null,
    val exitCode: Int? = null,
    val finishedAt: Long? = null,
    val termination: String? = null,
    val ownerSessionId: String? = null,
)

/**
 * Registry of detached background shell processes (dev servers, watchers…),
 * persisted to a JSON file so it survives app restarts.
 *
 * When Shizuku is connected, processes are spawned inside Shizuku's server
 * process ([BgProcessEntry.source] = SHIZUKU), so they keep running even if the
 * app is killed, and are re-adopted on the next launch. Without Shizuku they
 * run as plain app children (APP), kept alive by the foreground service +
 * wakelock while the app is running.
 */
class BgProcessStore(
    private val context: Context,
    private val linuxEnv: LinuxEnvironmentManager,
    private val shizuku: ShizukuManager,
    /** App workspace root, so detached logs live under it so file tools can read them. */
    workspaceRoot: File,
    private val privilegedLiveness: suspend (Int) -> Boolean? = shizuku::probeProcessAlive,
    private val deviceAccess: com.androidharness.app.data.env.DeviceAccessManager? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val storeFile = File(context.filesDir, "bg-processes.json")
    private val entries = ConcurrentHashMap<Int, BgProcessEntry>()
    private val appProcesses = ConcurrentHashMap<Int, Process>()
    private val nextId = AtomicInteger(1)
    private val ioLock = Mutex()
    private val storageLock = Mutex()

    suspend fun <T> withIdleStorage(action: suspend () -> T): T = storageLock.withLock {
        check(listLocked().none { it.second }) { "Stop background processes and refresh their list before cleaning storage." }
        action()
    }

    /**
     * Bug 2 fix: unique-per-app-run token inside every log filename. Process
     * ids restart at 1 when the registry state is gone (fresh install, >24h
     * prune), which used to APPEND new output to an old session's log. The
     * token makes log names collision-free across app restarts and lets
     * bg_list flag entries that started in a previous app session.
     */
    private val bootToken: String = java.lang.Long.toString(System.currentTimeMillis(), 36)
    /** When THIS app process started; older entries began in a previous session. */
    val bootAt: Long = System.currentTimeMillis()

    /** Logs for background processes: inside the workspace, readable by both uids and all tools. */
    private val detachedLogDir: File = File(workspaceRoot, ".harness/bg").apply { mkdirs() }

    init {
        runCatching {
            if (storeFile.exists()) {
                val loaded = json.decodeFromString<List<BgProcessEntry>>(storeFile.readText())
                // Detached processes survive app death and may run for more than a day.
                // Only a verified exit (or successful stop) can remove their reference.
                loaded.forEach { entries[it.id] = it }
                nextId.set((entries.keys.maxOrNull() ?: 0) + 1)
            }
        }
    }

    private suspend fun persist() {
        ioLock.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
                    tmp.writeText(json.encodeToString(entries.values.sortedBy { it.id }))
                    tmp.renameTo(storeFile)
                }
            }
        }
    }

    data class Started(val entry: BgProcessEntry, val viaShizuku: Boolean)

    /**
     * Starts [command] in [cwd]. Prefers the Shizuku-detached tier (survives
     * app death) when Shizuku is connected; otherwise an app-child process.
     * May throw when the app tier can't reach [cwd]; callers report that.
     */
    suspend fun start(command: String, cwd: File, sessionId: String? = null): Started = storageLock.withLock { startLocked(command, cwd, sessionId) }

    private suspend fun startLocked(command: String, cwd: File, sessionId: String?): Started {
        val id = nextId.getAndIncrement()
        val bgDir = File(cwd, ".harness/bg").apply { mkdirs() }
        val logName = "$id-$bootToken.log"
        val logFile = File(bgDir, logName).apply {
            runCatching { createNewFile() }
        }.let { if (it.exists() || it.canWrite()) it else File(detachedLogDir, logName).apply { createNewFile() } }
        val exitFile = File(logFile.path + ".exit")
        val monitoredCommand = backgroundCommand(command, exitFile.absolutePath)

        deviceAccess?.let { access ->
            return startWithAccess(access, id, command, monitoredCommand, cwd, logFile, exitFile, sessionId)
        }

        if (shizuku.isGranted()) {
            // Make sure the shell-user toolchain copy matches the package set
            // tmpProcessEnv() is built for: a copy from an older set is missing
            // the libraries that env names.
            val expected = linuxEnv.deployedTag()
            if (linuxEnv.isReady && !shizuku.isTmpPrefixDeployed(expected)) {
                linuxEnv.ensureShellDeploy(shizuku)
            }
            val toolchainDeployed = shizuku.isTmpPrefixDeployed(expected)
            val cmd = if (toolchainDeployed) {
                arrayOf("${linuxEnv.tmpPrefix}/bin/bash", "-c", monitoredCommand)
            } else {
                arrayOf("/system/bin/sh", "-c", monitoredCommand)
            }
            val env = if (toolchainDeployed) {
                linuxEnv.tmpProcessEnv().map { "${it.key}=${it.value}" }.toTypedArray()
            } else null
            val pid = shizuku.spawnDetached(cmd, env, cwd.absolutePath, logFile.absolutePath)
            check(pid == null || pid > 0) { "Privileged background start failed or its result is uncertain; it was not replayed." }
            if (pid != null && pid > 0) {
                val entry = BgProcessEntry(
                    id = id, command = command, cwd = cwd.absolutePath,
                    logPath = logFile.absolutePath, pid = pid, source = "SHIZUKU", exitCodePath = exitFile.absolutePath,
                    startedAt = System.currentTimeMillis(), ownerSessionId = sessionId,
                )
                entries[id] = entry
                persist()
                return Started(entry, viaShizuku = true)
            }
            // No dispatch began; the app tier may safely be selected below.
        }

        val process = linuxEnv.shellProcessBuilder(monitoredCommand)
            .directory(cwd)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectErrorStream(true)
            .start()
        appProcesses[id] = process
        val entry = BgProcessEntry(
            id = id, command = command, cwd = cwd.absolutePath,
            logPath = logFile.absolutePath, pid = -1, source = "APP", exitCodePath = exitFile.absolutePath,
            startedAt = System.currentTimeMillis(), ownerSessionId = sessionId,
        )
        entries[id] = entry
        persist()
        return Started(entry, viaShizuku = false)
    }

    /** Active jobs plus the latest 100 terminal records, persisted across app restarts. */
    private suspend fun startWithAccess(
        access: com.androidharness.app.data.env.DeviceAccessManager,
        id: Int, command: String, monitored: String, cwd: File, log: File, exit: File, sessionId: String?,
    ): Started {
        val result = access.execute(
            appWorkspace = access.pinsAppUser(cwd),
            normal = {
                val process = linuxEnv.shellProcessBuilder(monitored).directory(cwd)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log)).redirectErrorStream(true).start()
                appProcesses[id] = process
                com.androidharness.app.data.env.ShellRunResult(0, false, "APP", "", com.androidharness.app.data.env.ExecutionTier.APP_LINUX, null)
            },
            shizukuRun = {
                if (!shizuku.isServiceReady()) null else {
                    val tag = linuxEnv.deployedTag()
                    if (linuxEnv.isReady && !shizuku.isTmpPrefixDeployed(tag)) linuxEnv.ensureShellDeploy(shizuku)
                    val deployed = shizuku.isTmpPrefixDeployed(tag)
                    val args = if (deployed) arrayOf("${linuxEnv.tmpPrefix}/bin/bash", "-c", monitored) else arrayOf("/system/bin/sh", "-c", monitored)
                    val env = if (deployed) linuxEnv.tmpProcessEnv().map { "${it.key}=${it.value}" }.toTypedArray() else null
                    val pid = shizuku.spawnDetached(args, env, cwd.path, log.path)
                    pid?.let { com.androidharness.app.data.env.ShellRunResult(if (it > 0) 0 else -1, false, it.toString(), "Detached start failed or its result is uncertain; it was not replayed.", com.androidharness.app.data.env.ExecutionTier.PRIVILEGED, null) }
                }
            },
            rootRun = {
                val svc = access.root.service
                if (svc == null) null else {
                    val pid = runCatching { svc.spawnDetached(linuxEnv.shellProcessBuilder(monitored).command().toTypedArray(),
                        (linuxEnv.processEnv() + mapOf("HARNESS_TMP_LIB" to "${linuxEnv.prefix}/lib", "HARNESS_TMP_BIN" to "${linuxEnv.prefix}/bin"))
                            .map { "${it.key}=${it.value}" }.toTypedArray(), cwd.path, log.path) }.getOrDefault(-1)
                    com.androidharness.app.data.env.ShellRunResult(if (pid > 0) 0 else -1, false, pid.toString(), "Root task start failed or its result is uncertain; it was not replayed.", com.androidharness.app.data.env.ExecutionTier.ROOT, null)
                }
            },
        )
        check(result.exitCode == 0) { result.rawStderr.ifBlank { result.note ?: "Could not start the selected background backend." } }
        val source = when (result.tier) { com.androidharness.app.data.env.ExecutionTier.ROOT -> "ROOT"; com.androidharness.app.data.env.ExecutionTier.PRIVILEGED -> "SHIZUKU"; else -> "APP" }
        val entry = BgProcessEntry(id, command, cwd.path, log.path, result.rawOutput.toIntOrNull() ?: -1,
            source, System.currentTimeMillis(), exit.path, ownerSessionId = sessionId)
        entries[id] = entry
        persist()
        return Started(entry, source == "SHIZUKU")
    }

    private suspend fun rootLiveness(pid: Int): Boolean? = withContext(Dispatchers.IO) {
        val svc = deviceAccess?.root?.service ?: return@withContext null
        runCatching { svc.isProcessAlive(pid) }.getOrNull()
    }
    suspend fun stopPrivilegedTasks(): String {
        val candidates = entries.values.filter { it.finishedAt == null && it.source in setOf("ROOT", "SHIZUKU") }
        var stopped = 0
        candidates.forEach { if (runCatching { kill(it.id) }.getOrDefault(false)) stopped++ }
        return "Detached privileged tasks: $stopped stopped; ${candidates.size - stopped} could not be confirmed."
    }

    suspend fun list(): List<Pair<BgProcessEntry, Boolean>> = storageLock.withLock { listLocked() }

    /** Listing may show an unverified job; storage cleanup must still refuse it. */
    suspend fun listForDisplay(): List<Pair<BgProcessEntry, Boolean>> = storageLock.withLock { listLocked(allowUnverified = true) }

    private suspend fun listLocked(allowUnverified: Boolean = false): List<Pair<BgProcessEntry, Boolean>> {
        val out = mutableListOf<Pair<BgProcessEntry, Boolean>>()
        var changed = false
        entries.values.sortedBy { it.id }.forEach { e ->
            if (e.finishedAt != null) {
                out += e to false
                return@forEach
            }
            val recordedExit = e.exitCodePath?.let { runCatching { File(it).readText().trim().toIntOrNull() }.getOrNull() }
            val observed = when (e.source) {
                "ROOT" -> if (recordedExit != null) false else rootLiveness(e.pid)
                "SHIZUKU" -> if (recordedExit != null) false else privilegedLiveness(e.pid)
                else -> appProcesses[e.id]?.isAlive == true
            }
            if (observed == null) {
                check(allowUnverified) { "Cannot verify a ${e.source.lowercase()} background process. Reconnect it before cleaning storage." }
                out += e.copy(termination = "unverified") to true
            } else if (observed) {
                out += e to true
            } else {
                val code = recordedExit ?: appProcesses[e.id]?.let { runCatching { it.exitValue() }.getOrNull() }
                val finished = e.copy(exitCode = code, finishedAt = System.currentTimeMillis(), termination = "exited")
                entries[e.id] = finished
                appProcesses.remove(e.id)
                out += finished to false
                changed = true
            }
        }
        val expired = out.filter { !it.second }.sortedByDescending { it.first.finishedAt }.drop(100).map { it.first.id }.toSet()
        if (expired.isNotEmpty()) {
            expired.forEach(entries::remove)
            changed = true
        }
        if (changed) persist()
        return out.filterNot { it.first.id in expired }
    }

    fun get(id: Int): BgProcessEntry? = entries[id]

    /** Stops every background process launched through Harness. */
    suspend fun killAll(): Int {
        val ids = entries.values.filter { it.finishedAt == null }.map { it.id }
        var killed = 0
        for (id in ids) {
            if (runCatching { kill(id) }.getOrDefault(false)) killed++
        }
        return killed
    }

    suspend fun kill(id: Int): Boolean = storageLock.withLock { killLocked(id) }

    private suspend fun killLocked(id: Int): Boolean {
        val e = entries[id] ?: return false
        if (e.finishedAt != null) return false
        if (e.source == "SHIZUKU" && privilegedLiveness(e.pid) == null) return false
        if (e.source == "ROOT" && rootLiveness(e.pid) == null) return false
        val logFile = File(e.cwd, e.logPath).takeIf { it.exists() }
            ?: File(e.logPath).let { if (it.isAbsolute) it else File(detachedLogDir.parentFile, e.logPath) }
        val ok = when (e.source) {
            "ROOT" -> {
                runCatching { deviceAccess?.root?.service?.killTrackedProcess(e.pid, logFile.absolutePath) }
                rootLiveness(e.pid) == false
            }
            "SHIZUKU" -> {
                shizuku.killProcess(e.pid, logFile.absolutePath)
                killPidsWithFd(logFile)
                privilegedLiveness(e.pid) == false
            }
            else -> {
                val p = appProcesses[id]
                if (p != null) {
                    val pid = runCatching {
                        val field = p.javaClass.getDeclaredField("pid")
                        field.isAccessible = true
                        field.getInt(p)
                    }.getOrNull()
                    if (pid != null && pid > 0) {
                        runCatching { android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL) }
                        runCatching {
                            val pkill = Runtime.getRuntime().exec(arrayOf("/system/bin/pkill", "-9", "-P", pid.toString()))
                            pkill.waitFor(1, TimeUnit.SECONDS)
                        }
                        runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
                    }
                    killPidsWithFd(logFile)
                    p.destroyForcibly()
                    p.waitFor(2, TimeUnit.SECONDS)
                    !p.isAlive
                } else {
                    killPidsWithFd(logFile)
                    true
                }
            }
        }
        if (!ok) return false
        val exitCode = appProcesses[id]?.let { runCatching { it.exitValue() }.getOrNull() }
        appProcesses.remove(id)
        entries[id] = e.copy(finishedAt = System.currentTimeMillis(), exitCode = exitCode, termination = "killed")
        persist()
        return ok
    }

    fun tail(entry: BgProcessEntry, chars: Int = 2_000): String {
        val f = File(entry.cwd, entry.logPath).takeIf { it.exists() }
            ?: File(entry.logPath).let { if (it.isAbsolute) it else File(detachedLogDir.parentFile, entry.logPath) }
        return if (f.exists()) {
            val text = f.readText()
            val filtered = text.lines()
                .filterNot { isHeartbeatLine(it) }
                .joinToString("\n")
            if (filtered.length <= chars) filtered else "…\n" + filtered.takeLast(chars)
        } else ""
    }

    private fun isHeartbeatLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.contains("heartbeat", ignoreCase = true)
    }

    private fun killPidsWithFd(logFile: File) {
        if (!logFile.exists()) return
        val canonTarget = runCatching { logFile.canonicalPath }.getOrDefault(logFile.absolutePath)
        val proc = File("/proc")
        val pidDirs = proc.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return
        for (dir in pidDirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val fdDir = File(dir, "fd")
            val fds = runCatching { fdDir.listFiles() }.getOrNull() ?: continue
            for (fd in fds) {
                val link = runCatching { android.system.Os.readlink(fd.absolutePath) }.getOrNull()
                if (link == canonTarget || link == logFile.absolutePath) {
                    runCatching { android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL) }
                    runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
                    break
                }
            }
        }
    }
}

/** Run in a subshell so `exit`, exec and parse failures still produce a terminal record. */
internal fun backgroundCommand(command: String, exitPath: String): String {
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    return "(\neval ${quote(command)}\n)\nharness_bg_exit=\$?\nprintf '%s\\n' \"\$harness_bg_exit\" > ${quote(exitPath)}\nexit \"\$harness_bg_exit\""
}
