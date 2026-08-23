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
)

/**
 * Registry of detached background shell processes (dev servers, watchers…),
 * persisted to a JSON file so it survives app restarts.
 *
 * When Shizuku is connected, processes are spawned inside Shizuku's server
 * process ([BgProcessEntry.source] = SHIZUKU) — they keep running even if the
 * app is killed, and are re-adopted on the next launch. Without Shizuku they
 * run as plain app children (APP), kept alive by the foreground service +
 * wakelock while the app is running.
 */
class BgProcessStore(
    private val context: Context,
    private val linuxEnv: LinuxEnvironmentManager,
    private val shizuku: ShizukuManager,
    /** App workspace root — detached logs live under it so file tools can read them. */
    workspaceRoot: File,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val storeFile = File(context.filesDir, "bg-processes.json")
    private val entries = ConcurrentHashMap<Int, BgProcessEntry>()
    private val appProcesses = ConcurrentHashMap<Int, Process>()
    private val nextId = AtomicInteger(1)
    private val ioLock = Mutex()

    /** Logs for Shizuku-tier processes: inside the workspace, readable by both uids. */
    private val detachedLogDir: File = File(workspaceRoot, ".harness-bg")

    init {
        runCatching {
            if (storeFile.exists()) {
                val loaded = json.decodeFromString<List<BgProcessEntry>>(storeFile.readText())
                val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                // prune stale entries from runs older than a day
                loaded.filter { it.startedAt >= cutoff }.forEach { entries[it.id] = it }
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
     * May throw when the app tier can't reach [cwd] — callers report that.
     */
    suspend fun start(command: String, cwd: File): Started {
        val id = nextId.getAndIncrement()

        if (shizuku.isGranted()) {
            // Make sure the shell-user toolchain copy exists before spawning.
            if (linuxEnv.isReady && !shizuku.isTmpPrefixDeployed()) {
                linuxEnv.ensureShellDeploy(shizuku)
            }
            val toolchainDeployed = shizuku.isTmpPrefixDeployed()
            val cmd = if (toolchainDeployed) {
                arrayOf("${LinuxEnvironmentManager.TMP_PREFIX_BASE}/linux/bin/bash", "-c", command)
            } else {
                arrayOf("/system/bin/sh", "-c", command)
            }
            val env = if (toolchainDeployed) {
                linuxEnv.tmpProcessEnv().map { "${it.key}=${it.value}" }.toTypedArray()
            } else null
            val log = File(detachedLogDir, "$id.log")
            val pid = shizuku.spawnDetached(cmd, env, cwd.absolutePath, log.absolutePath)
            if (pid != null && pid > 0) {
                val entry = BgProcessEntry(
                    id = id, command = command, cwd = cwd.absolutePath,
                    logPath = log.absolutePath, pid = pid, source = "SHIZUKU",
                    startedAt = System.currentTimeMillis(),
                )
                entries[id] = entry
                persist()
                return Started(entry, viaShizuku = true)
            }
            // service dropped mid-flight — fall back to the app tier below
        }

        val logDir = File(cwd, ".harness/bg").apply { mkdirs() }
        val logFile = File(logDir, "$id.log")
        val process = linuxEnv.shellProcessBuilder(command)
            .directory(cwd)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectErrorStream(true)
            .start()
        appProcesses[id] = process
        val entry = BgProcessEntry(
            id = id, command = command, cwd = cwd.absolutePath,
            logPath = logFile.absolutePath, pid = -1, source = "APP",
            startedAt = System.currentTimeMillis(),
        )
        entries[id] = entry
        persist()
        return Started(entry, viaShizuku = false)
    }

    /** All tracked processes with a live liveness check. */
    suspend fun list(): List<Pair<BgProcessEntry, Boolean>> {
        val out = mutableListOf<Pair<BgProcessEntry, Boolean>>()
        entries.values.sortedBy { it.id }.forEach { e ->
            val alive = when (e.source) {
                "SHIZUKU" -> shizuku.isProcessAlive(e.pid)
                else -> appProcesses[e.id]?.isAlive == true
            }
            out += e to alive
        }
        return out
    }

    fun get(id: Int): BgProcessEntry? = entries[id]

    suspend fun kill(id: Int): Boolean {
        val e = entries[id] ?: return false
        val ok = when (e.source) {
            "SHIZUKU" -> shizuku.killProcess(e.pid)
            else -> {
                val p = appProcesses[id]
                if (p != null) {
                    p.destroyForcibly()
                    p.waitFor(2, TimeUnit.SECONDS)
                    true
                } else true // nothing alive to kill (e.g. after an app restart)
            }
        }
        appProcesses.remove(id)
        entries.remove(id)
        persist()
        return ok
    }

    fun tail(entry: BgProcessEntry, chars: Int = 2_000): String {
        val f = File(entry.logPath)
        return if (f.exists()) {
            val text = f.readText()
            if (text.length <= chars) text else "…\n" + text.takeLast(chars)
        } else ""
    }
}
