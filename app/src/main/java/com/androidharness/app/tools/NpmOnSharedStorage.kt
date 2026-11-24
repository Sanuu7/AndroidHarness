package com.androidharness.app.tools

import java.io.File

/**
 * npm cannot create bin-links (symlinks) on shared storage (/storage/emulated),
 * so `npm install` there fails with EACCES. When the shell cwd is shared
 * storage, transparently append `--no-bin-links` to install commands and tell
 * the model what happened via a note.
 *
 * Global installs (`-g`, `--global`, `--location=global`) are left alone: they
 * go into the app's private prefix, where symlinks work, and `--no-bin-links`
 * there would stop the package's command from ever landing on PATH.
 */
object NpmOnSharedStorage {

    /** One npm invocation, up to the next shell separator. */
    private val SEGMENT_RE = Regex("""\bnpm\b[^;&|\n]*""")
    private val INSTALL_RE = Regex("""\bnpm\s+(?:install|i|ci)\b""")
    private val GLOBAL_RE = Regex("""(?:^|\s)(?:-g|--global|--location[=\s]+global)(?=\s|$)""")

    /**
     * Returns [command] unchanged plus a null note when no rewrite applies;
     * otherwise the command with `--no-bin-links` inserted after every local
     * install subcommand and an explanatory note for the tool result.
     */
    fun prepare(command: String, cwd: File): Pair<String, String?> {
        if (!isSharedStorage(cwd)) return command to null
        if (!INSTALL_RE.containsMatchIn(command)) return command to null

        var changed = false
        val modified = SEGMENT_RE.replace(command) { segment ->
            val text = segment.value
            if (!INSTALL_RE.containsMatchIn(text) ||
                text.contains("--no-bin-links") ||
                GLOBAL_RE.containsMatchIn(text)
            ) {
                text
            } else {
                changed = true
                INSTALL_RE.replaceFirst(text, "$0 --no-bin-links")
            }
        }
        if (!changed) return command to null

        val note = "[note: workspace is on shared storage where symlinks are not available; " +
            "automatically added --no-bin-links to npm. If you still see EACCES, the script " +
            "section may run its own npm install, review it for the same flag.]"
        return modified to note
    }

    /** Shared-storage detection without needing a Context: path-prefix based. */
    private fun isSharedStorage(cwd: File): Boolean {
        val path = cwd.absolutePath
        return path == "/storage/emulated/0" || path.startsWith("/storage/emulated/0/")
    }
}
