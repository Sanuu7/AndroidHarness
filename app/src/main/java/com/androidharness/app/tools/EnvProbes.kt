package com.androidharness.app.tools

import android.system.Os
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShellTierRouter
import com.androidharness.app.data.env.ShizukuManager
import java.io.File

/**
 * Tier-routed environment probes shared by the status/doctor tools. Their
 * contract: report what the shell tool will ACTUALLY be able to do in the
 * workspace's execution tier — not what blind app-uid stat()s suggest. The
 * deployed /data/local/tmp copy is opaque to the app uid (its File.exists()
 * returns false for working binaries), so privileged-tier probes route
 * through the Shizuku shell.
 */
internal object EnvProbes {

    private fun isTmpPrefix(probeRoot: File): Boolean =
        probeRoot.absolutePath ==
            LinuxEnvironmentManager.TMP_PREFIX_BASE + "/linux"

    /**
     * Where the toolchain copies live for the [tier] the shell tool will use:
     * the app prefix, or the deployed /data/local/tmp copy in the privileged
     * tier. Callers must not probe this root with plain File checks (see
     * [fileMode] / [commandPresence] for tier-routed reads).
     */
    suspend fun probeRoot(
        linuxEnv: LinuxEnvironmentManager,
        shizuku: ShizukuManager,
        tier: com.androidharness.app.data.env.ExecutionTier,
    ): File =
        if (tier == com.androidharness.app.data.env.ExecutionTier.PRIVILEGED && shizuku.isTmpPrefixDeployed())
            File(LinuxEnvironmentManager.TMP_PREFIX_BASE, "linux")
        else linuxEnv.prefix

    /**
     * Real permission bits (e.g. "600") of [probeRoot]/[rel], or null when the
     * file is absent or unreadable from here. App-prefix files are stated
     * directly; deployed tmp-prefix files go through the privileged shell.
     */
    suspend fun fileMode(shizuku: ShizukuManager, probeRoot: File, rel: String): String? {
        val f = File(probeRoot, rel)
        return if (isTmpPrefix(probeRoot)) {
            shizuku.runPrivileged(
                arrayOf("/system/bin/sh", "-c", "if [ -f '${f.absolutePath}' ]; then stat -c %a '${f.absolutePath}'; fi"),
                env = null,
                dir = null,
                timeoutMs = 5_000,
                maxBytes = 200,
            )?.output?.trim()?.ifBlank { null }
        } else {
            runCatching {
                "%o".format(Os.stat(f.absolutePath).st_mode and 0x1FF)
            }.getOrNull()?.takeIf { f.isFile }
        }
    }

    /**
     * Resolves [names] in the live shell (`command -v`), which sees both real
     * binaries and the app-tier linker shims — the ground truth for "can the
     * agent run this tool right now". Null when the probe could not run and
     * the caller should fall back to filesystem checks.
     */
    suspend fun commandPresence(
        router: ShellTierRouter,
        cwd: File,
        names: List<String>,
    ): Map<String, Boolean>? {
        val script = "for t in ${names.joinToString(" ")}; do " +
            "if command -v \"\$t\" >/dev/null 2>&1; then echo \"\$t=ok\"; else echo \"\$t=missing\"; fi; done"
        val res = runCatching { router.run(script, cwd, timeoutMs = 10_000, maxOutput = 4_000) }.getOrNull()
            ?: return null
        if (res.rawOutput.isBlank()) return null
        val found = names.associateWith { false }.toMutableMap()
        res.rawOutput.lineSequence().forEach { line ->
            val parts = line.trim().split('=', limit = 2)
            val name = parts.getOrNull(0)
            if (name != null && name in found && parts.getOrNull(1) == "ok") found[name] = true
        }
        return found
    }
}
