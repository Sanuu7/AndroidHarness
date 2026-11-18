package com.androidharness.app.tools

import java.io.File

/**
 * Makes the executables in the project's plugin bin folders callable by name, as Claude Code does for a plugin's bin/.
 * Android does not let app files run directly, so each script also gets a shell function that starts it with the
 * interpreter its #! line names.
 */
object PluginBinPath {
    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

    fun prefix(ctx: ToolContext, command: String): String {
        val bins = ctx.configuration?.pluginBins.orEmpty()
        if (bins.isEmpty()) return command
        val functions = bins.flatMap { dir -> File(dir).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name } }
            .distinctBy { it.name }
            .filter { Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*").matches(it.name) }
            .mapNotNull { file -> runner(file)?.let { "${file.name}() { $it ${quote(file.absolutePath)} \"\$@\"; }" } }
        val path = "export PATH=${bins.joinToString(":") { quote(it) }}:\"\$PATH\""
        return (listOf(path) + functions + command).joinToString("\n")
    }

    /** The interpreter a script's #! line asks for, or null for a binary or an unknown interpreter. */
    internal fun runner(file: File): String? {
        val first = runCatching { file.bufferedReader().use { it.readLine() } }.getOrNull()?.takeIf { it.startsWith("#!") } ?: return null
        val words = first.removePrefix("#!").trim().split(Regex("\\s+"))
        val program = (if (words.firstOrNull()?.endsWith("/env") == true) words.drop(1).firstOrNull { !it.startsWith("-") } else words.firstOrNull())
            ?.substringAfterLast('/') ?: return null
        return program.takeIf { Regex("(ba|z|da)?sh|python[0-9.]*|node|bun|ruby|perl|php|lua").matches(it) }
    }
}
