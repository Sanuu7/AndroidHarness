package com.androidharness.app.tools

import com.androidharness.app.data.env.ShizukuManager
import com.androidharness.app.data.env.ShizukuState
import com.androidharness.app.data.env.UserServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Built-in agent tool to read Android system and application logs.
 * When Shizuku is connected and granted, queries run under the shell UID (2000),
 * allowing full access to logs across all apps, system services, and crashes.
 * In fallback mode (no Shizuku), runs as the app UID (best-effort, app's own logs).
 */
class ReadLogcatTool(
    private val shizuku: ShizukuManager? = null,
    private val runner: LogcatRunner = DefaultLogcatRunner(shizuku),
) : Tool {
    override val name = "read_logcat"
    override val description =
        "Read recent Android logcat logs with optional filtering by log level, tag, package name, buffer, or keyword. " +
        "Useful for debugging Android app crashes, ANRs, runtime exceptions, and system events. " +
        "When Shizuku is running and granted, captures device-wide logs; otherwise captures logs available to the app."

    override val parametersSchema = Schema.obj(
        mapOf(
            "lines" to Schema.integer("Number of recent log lines to retrieve (default 100, max 1000)."),
            "level" to Schema.string("Minimum log level: V (Verbose), D (Debug), I (Info), W (Warn), E (Error), F (Fatal). Default is V."),
            "tag" to Schema.string("Filter logs by specific tag (e.g., 'AndroidRuntime', 'ActivityManager', or custom app tag)."),
            "package_name" to Schema.string("Filter logs by Android package name (e.g., 'com.example.app'). Resolves its PID if running or filters matching log lines."),
            "buffer" to Schema.string("Logcat ring buffer to read from: 'main', 'system', 'crash', 'events', 'radio', 'all', or 'default'. Default is 'default' (main, system, crash)."),
            "filter" to Schema.string("Case-insensitive keyword/substring to filter the output lines."),
        ),
    )

    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val linesCount = (args["lines"]?.jsonPrimitive?.intOrNull ?: 100).coerceIn(1, MAX_LINES)
            val level = parseLevel(args["level"]?.jsonPrimitive?.content)
            val tag = args["tag"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            val pkg = args["package_name"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            val buffer = args["buffer"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            val filter = args["filter"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

            val query = LogcatQuery(
                lines = linesCount,
                level = level,
                tag = tag,
                packageName = pkg,
                buffer = buffer,
                filter = filter,
            )

            val runResult = runner.runLogcat(query)
            if (!runResult.ok) {
                return@withContext ToolResult(false, runResult.output)
            }

            var outputLines = runResult.output.lines()

            // Filter in-memory if substring requested or if package_name was provided but no PID was available
            if (!filter.isNullOrBlank()) {
                outputLines = outputLines.filter { it.contains(filter, ignoreCase = true) }
            }
            if (!pkg.isNullOrBlank() && runResult.pid == null) {
                // If we couldn't resolve PID, do best-effort text matching for package name
                outputLines = outputLines.filter { it.contains(pkg, ignoreCase = true) }
            }

            // Cap lines to requested count after post-filtering
            val finalLines = if (outputLines.size > linesCount) {
                outputLines.takeLast(linesCount)
            } else {
                outputLines
            }

            val truncatedOutput = finalLines.joinToString("\n").trim()
            val prefixNote = buildString {
                if (runResult.tierNote != null) {
                    append(runResult.tierNote)
                    append("\n")
                }
                if (pkg != null) {
                    if (runResult.pid != null) {
                        append("[filtered by PID ${runResult.pid} for package $pkg]\n")
                    } else {
                        append("[package $pkg is not currently running; matched text occurrences]\n")
                    }
                }
            }

            val finalOutput = if (truncatedOutput.isBlank()) {
                "${prefixNote}No matching logcat entries found for query: lines=$linesCount, level=$level" +
                    (if (tag != null) ", tag=$tag" else "") +
                    (if (pkg != null) ", package=$pkg" else "") +
                    (if (buffer != null) ", buffer=$buffer" else "") +
                    (if (filter != null) ", filter='$filter'" else "")
            } else {
                prefixNote + truncatedOutput
            }

            ToolResult(true, finalOutput.take(MAX_OUTPUT_CHARS))
        }

    private fun parseLevel(raw: String?): String {
        val upper = raw?.trim()?.uppercase() ?: return "V"
        return when (upper) {
            "V", "VERBOSE" -> "V"
            "D", "DEBUG" -> "D"
            "I", "INFO" -> "I"
            "W", "WARN", "WARNING" -> "W"
            "E", "ERROR" -> "E"
            "F", "FATAL" -> "F"
            else -> "V"
        }
    }

    companion object {
        const val MAX_LINES = 1000
        const val MAX_OUTPUT_CHARS = 65536
    }
}

data class LogcatQuery(
    val lines: Int,
    val level: String,
    val tag: String? = null,
    val packageName: String? = null,
    val buffer: String? = null,
    val filter: String? = null,
)

data class LogcatRunResult(
    val ok: Boolean,
    val output: String,
    val pid: String? = null,
    val tierNote: String? = null,
)

interface LogcatRunner {
    suspend fun runLogcat(query: LogcatQuery): LogcatRunResult
}

class DefaultLogcatRunner(
    private val shizuku: ShizukuManager?,
) : LogcatRunner {

    override suspend fun runLogcat(query: LogcatQuery): LogcatRunResult {
        val isPrivileged = shizuku != null &&
            shizuku.state.value == ShizukuState.GRANTED &&
            shizuku.serviceState.value == UserServiceState.BOUND_READY

        // Resolve PID for package if specified
        var pid: String? = null
        if (!query.packageName.isNullOrBlank()) {
            pid = resolvePid(query.packageName, isPrivileged)
        }

        val cmdArgs = buildLogcatArgs(query, pid)

        return if (isPrivileged) {
            runPrivilegedLogcat(cmdArgs, pid)
        } else {
            runLocalLogcat(cmdArgs, pid)
        }
    }

    private suspend fun resolvePid(packageName: String, isPrivileged: Boolean): String? {
        val pidCmd = arrayOf("/system/bin/pidof", packageName)
        val raw = if (isPrivileged) {
            shizuku?.runPrivileged(pidCmd, env = null, dir = null, timeoutMs = 3_000, maxBytes = 256)?.output
        } else {
            runCatching {
                val p = ProcessBuilder(*pidCmd).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(2, TimeUnit.SECONDS)
                out
            }.getOrNull()
        }
        val firstPid = raw?.trim()?.split(Regex("\\s+"))?.firstOrNull { it.all { c -> c.isDigit() } }
        return firstPid?.takeIf { it.isNotEmpty() }
    }

    private fun buildLogcatArgs(query: LogcatQuery, pid: String?): List<String> {
        val args = mutableListOf("/system/bin/logcat", "-d")

        // Lines count (retrieve slightly more to allow post filtering if needed)
        val fetchCount = if (query.filter != null || (query.packageName != null && pid == null)) {
            (query.lines * 2).coerceIn(query.lines, ReadLogcatTool.MAX_LINES)
        } else {
            query.lines
        }
        args.addAll(listOf("-t", fetchCount.toString()))

        // Buffer selection
        if (!query.buffer.isNullOrBlank() && query.buffer != "default") {
            args.addAll(listOf("-b", query.buffer))
        }

        // PID filter if known
        if (pid != null) {
            args.add("--pid=$pid")
        }

        // Filterspec: e.g. "AndroidRuntime:E *:S" or "*:E" or "<tag>:<level>"
        val filterspec = when {
            query.tag != null && query.tag.isNotEmpty() -> {
                listOf("${query.tag}:${query.level}", "*:S")
            }
            query.level != "V" -> {
                listOf("*:${query.level}")
            }
            else -> emptyList()
        }
        args.addAll(filterspec)

        return args
    }

    private suspend fun runPrivilegedLogcat(args: List<String>, pid: String?): LogcatRunResult {
        val result = shizuku?.runPrivileged(
            args.toTypedArray(),
            env = null,
            dir = null,
            timeoutMs = 15_000,
            maxBytes = ReadLogcatTool.MAX_OUTPUT_CHARS,
        ) ?: return LogcatRunResult(false, "Shizuku runner returned no result.")

        return if (result.exitCode == 0 || result.output.isNotEmpty()) {
            LogcatRunResult(
                ok = true,
                output = result.output,
                pid = pid,
                tierNote = null,
            )
        } else {
            LogcatRunResult(
                ok = false,
                output = "logcat failed with exit code ${result.exitCode}: ${result.stderr.ifEmpty { result.output }}",
            )
        }
    }

    private fun runLocalLogcat(args: List<String>, pid: String?): LogcatRunResult {
        return try {
            val process = ProcessBuilder(args).start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append('\n')
                if (sb.length >= ReadLogcatTool.MAX_OUTPUT_CHARS) break
            }
            process.waitFor(10, TimeUnit.SECONDS)
            val output = sb.toString()

            val tierNote = "[note: running without Shizuku privileges; modern Android only permits reading this app's own logs. Connect Shizuku in Settings → Terminal for device-wide logcat]"

            LogcatRunResult(
                ok = true,
                output = output,
                pid = pid,
                tierNote = tierNote,
            )
        } catch (e: Exception) {
            LogcatRunResult(
                ok = false,
                output = "Failed to run logcat: ${e.message}",
            )
        }
    }
}
