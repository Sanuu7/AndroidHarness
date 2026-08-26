package com.androidharness.app.tools

import java.io.File

/**
 * Bug 4 fix (companion to the Bug 2 exec-capable scratch dirs): the usual
 * workspace mount (shared storage /storage/emulated, FUSE) neither preserves
 * POSIX exec bits nor allows symlinks, so extracting JDK/Gradle tarballs there
 * yields unusable trees (every path -rw-rw----, symlinked bin/java broken).
 *
 * When the shell extracts a tarball INTO the workspace, transparently retarget
 * it into the designated exec-capable scratch dir ($HARNESS_SCRATCH) instead,
 * exactly the same way NpmOnSharedStorage rewrites npm install commands. The
 * tool result carries a note so the model knows where the files landed.
 */
object ExecScratchRouting {

    /** GNU/busybox tar extraction invocations (`tar x`, `tar xf`, `tar -xzf`…). */
    private val TAR_EXTRACT_RE =
        Regex("""(^|[\s;&|])(?:\S*[/\\])?tar(?:\.gz)?\s+-?[A-Za-z]*[xX][A-Za-z]*(?:\s|$)""")

    /** Matches `-C DIR` / `--directory=DIR` / `--directory DIR` with quoting. */
    private val CHANGE_DIR_RE = Regex(
        """(?<flag>-C|--directory)(?:=|\s+)(?:"(?<dq>[^"]+)"|'(?<sq>[^']+)'|(?<bare>[^\s;&|]+))""",
    )

    private val EXTENSIONS = listOf(".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz", ".tar.zst", ".tar")

    /**
     * Returns [command] plus an optional explanatory note. Rewrites the
     * destination directory of tar extractions targeting the workspace onto
     * the exec-capable scratch dir.
     */
    fun prepare(command: String, cwd: File): Pair<String, String?> {
        if (!TAR_EXTRACT_RE.containsMatchIn(command)) return command to null
        val scratch = preferredScratch()
        val match = CHANGE_DIR_RE.find(command)

        return if (match != null) {
            val rawDir = (match.groups["dq"] ?: match.groups["sq"] ?: match.groups["bare"])!!.value
            val target = resolveAgainst(rawDir, cwd)
            if (target == null || !isWorkspacePath(target, cwd)) return command to null
            if (!isExecHostile(target)) return command to null
            val newName = uniqueScratchDir(File(target).name, scratch)
            val rewritten = command.replaceRange(match.range, "${match.groups["flag"]!!.value} \"$newName\"")
            rewritten to note(newName)
        } else {
            // No -C given: busybox/GNU tar extracts into cwd. Only rewrite when
            // this invocation is the last thing in the command chain and cwd
            // itself cannot host the extracted tree.
            val tail = command.substringAfterLast("&&").substringAfterLast(";")
                .substringAfterLast("|").trim()
            if (!TAR_EXTRACT_RE.containsMatchIn(tail)) return command to null
            val base = archiveBaseName(tail) ?: return command to null
            if (!isExecHostile(cwd.absolutePath)) return command to null
            val newName = uniqueScratchDir(base, scratch)
            val rewritten = "$command -C \"$newName\""
            rewritten to note(newName)
        }
    }

    /** First scratch root that actually exists on this device. */
    private fun preferredScratch(): String =
        ShellPolicy.SCRATCH_ROOTS.firstOrNull { runCatching { File(it).isDirectory }.getOrDefault(false) }
            ?: ShellPolicy.SCRATCH_TMP

    /** Avoid clobbering a previous extraction of the same archive. */
    private fun uniqueScratchDir(wanted: String, scratch: String): String {
        val safe = wanted.filter { it.isLetterOrDigit() || it in "._-" }.ifBlank { "toolchain" }
        var candidate = "$scratch/$safe"
        var n = 2
        while (runCatching { File(candidate).exists() }.getOrDefault(false)) {
            candidate = "$scratch/$safe-$n"
            n++
        }
        return candidate
    }

    /**
     * Best-effort archive basename from the raw tail text: picks the last
     * argument-looking token that carries a known archive extension.
     */
    private fun archiveBaseName(tail: String): String? =
        tail.split(Regex("""[\s"']"""))
            .lastOrNull { token -> EXTENSIONS.any { token.endsWith(it) } }
            ?.let { full -> EXTENSIONS.firstNotNullOfOrNull { ext -> full.removeSuffix(ext).takeIf { full != it } } }

    private fun resolveAgainst(path: String, cwd: File): String? = try {
        val f = File(path)
        (if (f.isAbsolute) f else File(cwd, path)).canonicalFile.path
    } catch (_: Exception) {
        null
    }

    /** True when [absolutePath] lives under the shell's working tree. */
    private fun isWorkspacePath(absolutePath: String, cwd: File): Boolean =
        absolutePath == cwd.canonicalPath || absolutePath.startsWith("${cwd.canonicalPath}/") ||
            cwd.canonicalPath.startsWith("$absolutePath/")

    /** FUSE shared storage: no exec bits, no symlinks. */
    private fun isExecHostile(absolutePath: String): Boolean {
        val p = absolutePath
        return p == "/storage/emulated/0" || p.startsWith("/storage/emulated/0/") ||
            p == "/sdcard" || p.startsWith("/sdcard/")
    }

    private fun note(where: String) =
        "[note: the workspace filesystem does not preserve exec permissions or symlinks, " +
            "so the tarball was extracted to the exec-capable scratch dir $where instead. " +
            "Run extracted binaries from there (e.g. export JAVA_HOME=$where)." +
            "]"
}
