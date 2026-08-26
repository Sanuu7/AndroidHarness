package com.androidharness.app.data.env

import android.content.Context
import android.os.Build
import android.os.Environment
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
                // deployed tmp toolchain execs normally — it's the best tier.
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

    private suspend fun runPrivileged(
        command: String,
        cwd: File,
        timeoutMs: Int,
        maxOutput: Int,
    ): ShellRunResult {
        var toolchain = linuxEnv.isReady && shizuku.isTmpPrefixDeployed()
        if (linuxEnv.isReady && !toolchain) {
            // One-time deploy of the toolchain to an exec-allowed location.
            linuxEnv.ensureShellDeploy(shizuku)
            toolchain = shizuku.isTmpPrefixDeployed()
        }

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
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                break
            }
        }
        // Drain whatever the process wrote before it died so partial output
        // survives a timeout.
        out.join(2000)
        err.join(2000)

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
}
