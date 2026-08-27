package com.androidharness.app.tools

import com.androidharness.app.data.env.ExecutionTier
import com.androidharness.app.data.env.LinuxEnvironmentManager
import com.androidharness.app.data.env.ShellTierRouter
import com.androidharness.app.data.env.ShizukuManager
import com.androidharness.app.data.env.ShizukuState
import com.androidharness.app.data.env.UserServiceState
import kotlinx.serialization.json.JsonObject

/**
 * Live environment status for the agent. The agent must use this instead of
 * filesystem forensics: the Shizuku server runs in memory (started via adb or
 * the manager app), and exec of app-private binaries only works through the
 * harness shell tool.
 */
class EnvStatusTool(
    private val shizuku: ShizukuManager,
    private val linuxEnv: LinuxEnvironmentManager,
    private val router: ShellTierRouter,
) : Tool {
    override val name = "env_status"
    override val description =
        "Report the live state of the device environment: Shizuku connection + user-service state, " +
        "the Linux toolchain installation, which execution tier the shell tool will use for the " +
        "current workspace, and what the user must do to unlock more. Call this instead of probing " +
        "/data/local/tmp or testing exec paths yourself: the harness knows the authoritative state."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val sz = shizuku.state.value
        val szText = when (sz) {
            ShizukuState.NOT_INSTALLED -> "not installed"
            ShizukuState.NOT_RUNNING ->
                "installed but the Shizuku service is not running (start it in the Shizuku app or via adb)"
            ShizukuState.RUNNING_NO_PERMISSION ->
                "running, but AndroidHarness has not been granted access (user: Settings → Terminal → Grant Shizuku access)"
            ShizukuState.GRANTED -> when (shizuku.serviceState.value) {
                UserServiceState.BOUND_READY -> "running and the privileged runner is connected ✓"
                UserServiceState.NOT_BOUND -> "running and granted; privileged runner not connected yet"
                UserServiceState.BIND_FAILED -> "running and granted, but the privileged runner could not bind (Shizuku API < 10?)"
            }
        }
        val cwd = ctx.workspace.shellRoot ?: linuxEnv.shellFallbackRoot
        val tier = router.resolveTier(cwd)
        val tierText = when (tier) {
            ExecutionTier.PRIVILEGED ->
                "Shizuku ADB-shell privileges (${if (shizuku.isTmpPrefixDeployed()) "with the full Linux toolchain deployed to /data/local/tmp" else "system /system/bin/sh, Linux toolchain not deployed"}) in $cwd"
            ExecutionTier.APP_LINUX ->
                "app-uid Linux bash (full toolchain: node, python, git, …) in $cwd"
            ExecutionTier.TOYBOX ->
                "toybox sh (Linux environment not installed)"
        }
        val storage =
            if (router.isAllFilesAccess()) "All files access granted ✓"
            else "MANAGE_EXTERNAL_STORAGE NOT granted: app-uid can only reach its own folders on /sdcard"
        // Bug 1/2 status: TLS trust + exec-capable scratch availability.
        val tlsBundle = java.io.File(linuxEnv.prefix, NetTls.BUNDLE_RELATIVE_PATH)
        val scratch = ShellPolicy.SCRATCH_ROOTS.firstOrNull {
            runCatching { java.io.File(it).isDirectory }.getOrDefault(false)
        } ?: "(not provisioned yet)"
        // Tool-contract probe: "installed ✓" must mean the headline tools the
        // skills and UI promise actually exist, not just that the marker file
        // does. Presence check (not exec); app-uid binaries only run through
        // the harness shell.
        val probeRoot =
            if (tier == ExecutionTier.PRIVILEGED && shizuku.isTmpPrefixDeployed())
                java.io.File(com.androidharness.app.data.env.LinuxEnvironmentManager.TMP_PREFIX_BASE, "linux")
            else linuxEnv.prefix
        // Bug 4 fix: probe the REAL binary names. The toolchain installs
        // python3 (there is no plain "python"), and npm is a runner script
        // that execs node, so bin/npm alone is not proof npm works.
        val headlineTools = listOf(
            "bash" to listOf("bin/bash"),
            "git" to listOf("bin/git"),
            "python3" to listOf("bin/python3", "bin/python"),
            "node" to listOf("bin/node"),
            "npm" to listOf("bin/npm"),
            "pip" to listOf("bin/pip", "bin/pip3"),
        )
        val nodePresent = java.io.File(probeRoot, "bin/node").exists()
        val missing = headlineTools.filter { (name, rels) ->
            if (name == "npm" && !nodePresent) true
            else rels.none { java.io.File(probeRoot, it).exists() }
        }.map { it.first }
        val envText = when {
            !linuxEnv.isReady -> "not installed"
            missing.isEmpty() -> "installed ✓ (bash, git, python3, node, npm, pip all present)"
            else -> "installed ⚠ missing: " + missing.joinToString(", ")
        }
        // GitHub auth status (stress-test M7): the token's master copy lives in
        // the app's encrypted settings; this file is the materialized copy both
        // shell tiers can read, so an agent can consume it (stress-test L11).
        val ghToken = java.io.File(probeRoot, com.androidharness.app.data.env.GitHubProvision.TOKEN_FILE)
        val ghText = when {
            ghToken.isFile ->
                "authenticated ✓ — token at " + ghToken.absolutePath + " (0600); git URLs are rewritten " +
                    "with it automatically, so plain https://github.com clones and pushes work. Master copy " +
                    "lives in the app's encrypted settings and survives toolchain reinstalls; manage in Settings → GitHub"
            else ->
                "no token — public HTTPS clones work anonymously; push/PR/private repos need a personal " +
                    "access token (Settings → GitHub)"
        }
        return ToolResult(
            true,
            buildString {
                append("Shizuku: ").append(szText).append('\n')
                append("Linux environment: ").append(envText).append('\n')
                append("GitHub: ").append(ghText).append('\n')
                append("TLS (Bug 1 fix): CA bundle ")
                    .append(if (tlsBundle.isFile) "ready at ${tlsBundle.absolutePath} ✓" else "missing; falling back to system anchors")
                    .append("; SSL_CERT_FILE/CURL_CA_BUNDLE/REQUESTS_CA_BUNDLE/GIT_SSL_CAINFO/NODE_EXTRA_CA_CERTS are exported to every shell\n")
                append("Exec-capable scratch (Bug 2 fix): ").append(scratch)
                    .append(": extract JDK/Gradle/native tarballs HERE, never into shared storage (no exec bits, no symlinks); the env var HARNESS_SCRATCH is exported to every shell")
                    .append('\n')
                append("Storage: ").append(storage).append('\n')
                append("Active shell tier: ").append(tierText).append('\n')
                append("Notes: the Shizuku server is an in-memory process, it has no on-disk binary; " +
                    "the bundled toolchain is copied to /data/local/tmp/androidharness/linux so the " +
                    "privileged shell can use bash/git/python/node anywhere. To unlock system paths " +
                    "or folders outside the app's own data, Shizuku must be running and granted; " +
                    "to reach shared storage as the app uid, \"All files access\" must be granted in " +
                    "Settings → Storage access. " +
                    "There is no /bin/bash on Android: scripts with a #!/bin/bash shebang fail with " +
                    "\"bad interpreter\" — use #!/system/bin/sh (toybox) for system scripts, or run " +
                    "them with the toolchain's bash (\$PREFIX/bin/bash script.sh); only HTTPS git " +
                    "transport is available (no ssh binary, so git@github.com:… remotes do not work).")
            },
        )
    }
}
