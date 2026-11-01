package com.androidharness.app.plugins

import com.androidharness.app.data.env.PackageInstallStatus
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit

/** The Linux environment as the plugin manager needs it: installing runtimes and running one command. */
interface PluginRuntime {
    val ready: Boolean
    /** Packages from [wanted] that the package repository actually offers. */
    suspend fun available(wanted: List<String>): List<String>
    suspend fun install(packages: List<String>, requester: String, progress: suspend (PackageInstallStatus) -> Unit)
    fun process(command: String): ProcessBuilder
}

/**
 * Runs an approved installer command (npx, uvx) in a throwaway folder with its own HOME and caches, then hands
 * the files it wrote to the bundle adapter. Only that folder is kept; the user's projects are not its working folder.
 */
object PluginInstallers {
    const val TIMEOUT_MINUTES = 10L

    fun quote(value: String): String =
        if (value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "@%+=:,./_-^~" }) value else "'" + value.replace("'", "'\\''") + "'"

    /** Linux packages an installer command needs before it can start. */
    fun runtimesFor(command: String): List<String> = PluginRuntimes.forCommand(command).ifEmpty { listOf("nodejs", "npm") }

    suspend fun run(runtime: PluginRuntime, command: String, workspace: File, output: (String) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val project = File(workspace, "project").apply { mkdirs() }
        val home = File(workspace, "home").apply { mkdirs() }
        val cache = File(workspace, "cache").apply { mkdirs() }
        val builder = runtime.process("cd ${quote(project.absolutePath)} && $command")
        builder.directory(project)
        builder.redirectErrorStream(true)
        builder.environment().apply {
            put("HOME", home.absolutePath); put("USERPROFILE", home.absolutePath)
            put("XDG_CONFIG_HOME", File(home, ".config").absolutePath); put("XDG_DATA_HOME", File(home, ".local/share").absolutePath)
            put("XDG_CACHE_HOME", cache.absolutePath); put("npm_config_cache", File(cache, "npm").absolutePath)
            put("UV_CACHE_DIR", File(cache, "uv").absolutePath); put("PIP_CACHE_DIR", File(cache, "pip").absolutePath)
            put("npm_config_yes", "true"); put("npm_config_update_notifier", "false"); put("npm_config_fund", "false"); put("npm_config_audit", "false")
            put("CI", "1"); put("NO_COLOR", "1"); put("TERM", "dumb"); put("FORCE_COLOR", "0")
        }
        val process = builder.start()
        process.outputStream.close()
        val log = StringBuilder()
        val reader = launch {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                synchronized(log) { log.append(line).append('\n'); if (log.length > 16_000) log.delete(0, log.length - 12_000) }
                output(line.take(300))
            } }
        }
        try {
            val finished = runInterruptible { process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES) }
            if (!finished) error("The installer did not finish within $TIMEOUT_MINUTES minutes. Nothing was installed.")
            reader.join()
            val code = process.exitValue()
            if (code != 0) error("The installer exited with code $code. Nothing was installed.\n\n" + synchronized(log) { log.toString().takeLast(2000) })
        } finally {
            if (process.isAlive) { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly() }
            reader.cancel()
        }
        cache.deleteRecursively()
        listOf(".npm", ".cache", ".local/share/pnpm", ".bun").forEach { File(home, it).deleteRecursively() }
        workspace
    }
}
