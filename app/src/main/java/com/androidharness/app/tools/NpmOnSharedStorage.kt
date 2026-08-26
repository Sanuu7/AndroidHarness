package com.androidharness.app.tools

import com.androidharness.app.data.env.PathClassifier
import java.io.File

/**
 * npm cannot create bin-links (symlinks) on shared storage (/storage/emulated),
 * so `npm install` there fails with EACCES. When the shell cwd is shared
 * storage, transparently append `--no-bin-links` to install commands and tell
 * the model what happened via a note.
 */
object NpmOnSharedStorage {

    private val INSTALL_RE = Regex("""\bnpm\s+(?:install|i|ci)\b""")

    /**
     * Returns [command] unchanged plus a null note when no rewrite applies;
     * otherwise the command with `--no-bin-links` inserted after every
     * install subcommand and an explanatory note for the tool result.
     */
    fun prepare(command: String, cwd: File): Pair<String, String?> {
        if (!isSharedStorage(cwd)) return command to null
        if (!INSTALL_RE.containsMatchIn(command)) return command to null
        if (command.contains("--no-bin-links")) return command to null

        val modified = INSTALL_RE.replace(command) { match -> "${match.value} --no-bin-links" }
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
