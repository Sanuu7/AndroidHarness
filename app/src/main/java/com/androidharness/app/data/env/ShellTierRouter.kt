package com.androidharness.app.data.env

import android.content.Context
import android.os.Build
import android.os.Environment
import com.androidharness.app.tools.ShellPolicy
import java.io.File
import java.util.concurrent.TimeUnit

/** Classifies a filesystem path into one of the regions the shell tiers can reach. */
object PathClassifier {
    enum class Region { APP_DATA, SHARED_STORAGE, SYSTEM }

    fun regionOf(path: String, internalDataRoot: String): Region = when {
        path == internalDataRoot || path.startsWith("$internalDataRoot/") -> Region.APP_DATA
        path == "/storage/emulated/0" || path.startsWith("/storage/emulated/0/") -> Region.SHARED_STORAGE
        else -> Region.SYSTEM
    }
}

/** Which engine actually runs the shell command. */
enum class ExecutionTier {
    /** Inside Shizuku's server process: shell/root uid, can reach system paths and any folder. */
    PRIVILEGED,

    /** The app's own uid running the Termux-prefix Linux toolchain (with linker workaround). */
    APP_LINUX,

    /** Bare toybox sh when the app-side toolchain is not installed. */
    TOYBOX,
}

data class ShellRunResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val rawOutput: String,
    val rawStderr: String,
    val tier: ExecutionTier,
    val note: String?,
)

    /**
     * Decides which execution tier runs each shell command, based on where the
     * working directory lives and which privileges are currently available:
     *
     * - App data dir   -> app-uid toolchain (Shizuku's shell uid can't enter it).
     * - Anything else  -> Shizuku (shell uid) when granted: real exec of the
     *   deployed toolchain copy, system paths, any folder. Otherwise the app
     *   uid: on shared storage only with "All files access", on system paths
     *   best-effort with an explanatory note.
     */
class ShellTierRouter(
    private val context: Context,
    private val shizuku: ShizukuManager,
    private val linuxEnv: LinuxEnvironmentManager,
) {

    /** "All files access" (MANAGE_EXTERNAL_STORAGE). Pre-API-30 apps were not scoped. */
    fun isAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    fun resolveTier(cwd: File): ExecutionTier {
        val region = PathClassifier.regionOf(cwd.absolutePath, linuxEnv.internalDataRoot.absolutePath)
        return when (region) {
            PathClassifier.Region.APP_DATA ->
                if (linuxEnv.isReady) ExecutionTier.APP_LINUX else ExecutionTier.TOYBOX
            PathClassifier.Region.SHARED_STORAGE,
            PathClassifier.Region.SYSTEM -> when {
                // The shell uid can reach both /sdcard and system paths, and the
                // deployed tmp toolchain execs normally, so it's the best tier.
                shizuku.isGranted() -> ExecutionTier.PRIVILEGED
                isAllFilesAccess() -> if (linuxEnv.isReady) ExecutionTier.APP_LINUX else ExecutionTier.TOYBOX
                else -> if (linuxEnv.isReady) ExecutionTier.APP_LINUX else ExecutionTier.TOYBOX
            }
        }
    }

    /** A user-facing note explaining why the tier may be degraded, if so. */
    fun permissionNote(cwd: File, tier: ExecutionTier): String? {
        val region = PathClassifier.regionOf(cwd.absolutePath, linuxEnv.internalDataRoot.absolutePath)
        return when {
            tier == ExecutionTier.APP_LINUX &&
                region == PathClassifier.Region.SHARED_STORAGE &&
                !isAllFilesAccess() ->
                "[note: on this Android version the app can't reach shared storage without \"All files access\": expect permission errors here. Grant it in Settings → Storage access, or start Shizuku for shell access.]"

            tier == ExecutionTier.TOYBOX &&
                region == PathClassifier.Region.SYSTEM &&
                !shizuku.isGranted() ->
                "[note: this is a system path the app cannot touch on its own. Start Shizuku in Settings → Terminal to unlock it.]"

            else -> null
        }
    }

    /** Executes [command] with cwd [cwd] and returns a uniform result. */
    suspend fun run(command: String, cwd: File, timeoutMs: Int, maxOutput: Int): ShellRunResult =
        when (val tier = resolveTier(cwd)) {
            ExecutionTier.PRIVILEGED -> runPrivileged(command, cwd, timeoutMs, maxOutput)
            ExecutionTier.APP_LINUX -> runApp(command, cwd, timeoutMs, maxOutput, ExecutionTier.APP_LINUX)
            ExecutionTier.TOYBOX -> runApp(command, cwd, timeoutMs, maxOutput, ExecutionTier.TOYBOX)
        }

    // --- privileged tier ---------------------------------------------------

    /**
     * Bug 2 fix: provisions the shared exec-capable scratch dir via the
     * privileged side (mkdir 0777) when Shizuku is available. Best-effort;
     * the app-side init also creates it directly.
     */
    private suspend fun ensurePrivilegedScratch() {
        val scratch = ShellPolicy.SCRATCH_TMP
        shizuku.runPrivileged(
            arrayOf(
                "/system/bin/sh",
                "-c",
                "mkdir -p '$scratch' && chmod 777 '$scratch'",
            ),
            env = null,
            dir = null,
            timeoutMs = 10_000,
            maxBytes = 1_000,
        )
    }

    private suspend fun runPrivileged(
        command: String,
        cwd: File,
        timeoutMs: Int,
        maxOutput: Int,
    ): ShellRunResult {
        // Self-heal: catch a stale or vanished deployed copy even when nothing
        // in-process changed the staging state (throttled internally).
        linuxEnv.verifyDeployedCopyThrottled(shizuku)
        var toolchain = linuxEnv.isReady && shizuku.isTmpPrefixDeployed()
        if (linuxEnv.isReady && !toolchain) {
            // One-time deploy of the toolchain to an exec-allowed location.
            linuxEnv.ensureShellDeploy(shizuku)
            toolchain = shizuku.isTmpPrefixDeployed()
        }
        // Bug 2 fix: make sure the designated exec-capable scratch dir exists
        // and is writable by both the shell uid and the app uid.
        runCatching { ensurePrivilegedScratch() }

        val cmd = if (toolchain) {
            arrayOf("${LinuxEnvironmentManager.TMP_PREFIX_BASE}/linux/bin/bash", "-c", command)
        } else {
            arrayOf("/system/bin/sh", "-c", command)
        }
        val env = if (toolchain) {
            linuxEnv.tmpProcessEnv().map { "${it.key}=${it.value}" }.toTypedArray()
        } else null

        val r = shizuku.runPrivileged(cmd, env, cwd.absolutePath, timeoutMs, maxOutput)
        if (r == null) {
            // Service dropped mid-flight: fall back to the app uid tier.
            val fb = runApp(command, cwd, timeoutMs, maxOutput, ExecutionTier.APP_LINUX)
            return ShellRunResult(
                exitCode = fb.exitCode,
                timedOut = fb.timedOut,
                rawOutput = fb.rawOutput,
                rawStderr = fb.rawStderr,
                tier = ExecutionTier.APP_LINUX,
                note = "[note: Shizuku's privileged runner was unavailable, so this ran as the app user: expect permission errors on this directory]",
            )
        }
        val note = if (!toolchain) {
            "[note: privileged shell running /system/bin/sh only: install the Linux environment in Settings → Terminal for bash/git/python/node here]"
        } else null
        return ShellRunResult(r.exitCode, r.timedOut, r.output, r.stderr, ExecutionTier.PRIVILEGED, note)
    }

    // --- app-uid tier ------------------------------------------------------

    private suspend fun runApp(
        command: String,
        cwd: File,
        timeoutMs: Int,
        maxOutput: Int,
        tier: ExecutionTier,
    ): ShellRunResult {
        val process = try {
            linuxEnv.startShell(command, cwd).first
        } catch (e: Exception) {
            return ShellRunResult(
                exitCode = -1,
                timedOut = false,
                rawOutput = "Failed to start shell (directory not reachable by the app?): ${e.message}",
                rawStderr = "",
                tier = ExecutionTier.TOYBOX,
                note = permissionNote(cwd, tier),
            )
        }

        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val out = Thread { gobble(process.inputStream, stdout, maxOutput) }
        val err = Thread { gobble(process.errorStream, stderr, maxOutput) }
        listOf(out, err).forEach { it.isDaemon = true; it.start() }

        val deadline = System.currentTimeMillis() + timeoutMs
        var timedOut = false
        var exitCode = -1
        while (true) {
            if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                exitCode = process.exitValue()
                break
            }
            if (System.currentTimeMillis() > deadline) {
                timedOut = true
                val pid = runCatching {
                    val f = process.javaClass.getDeclaredField("pid")
                    f.isAccessible = true
                    f.getInt(process)
                }.getOrNull()
                if (pid != null && pid > 0) {
                    runCatching { android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL) }
                    runCatching {
                        val pkill = Runtime.getRuntime().exec(arrayOf("/system/bin/pkill", "-9", "-P", pid.toString()))
                        pkill.waitFor(1, TimeUnit.SECONDS)
                    }
                    runCatching { killDescendants(pid) }
                    runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
                }
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                reapZombies()
                break
            }
        }
        // Drain whatever the process wrote before it died so partial output
        // survives a timeout.
        out.join(2000)
        err.join(2000)
        reapZombies()

        return ShellRunResult(
            exitCode,
            timedOut,
            stdout.toString(),
            stderr.toString(),
            tier,
            permissionNote(cwd, tier),
        )
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

        val wnohang = 1
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
}
