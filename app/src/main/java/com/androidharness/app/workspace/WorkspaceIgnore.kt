package com.androidharness.app.workspace

/**
 * Directories a coding harness should never crawl during search/grep.
 * list_dir still shows them so the agent knows they exist.
 */
object WorkspaceIgnore {

    private val DIR_NAMES = setOf(
        ".git", ".svn", ".hg",
        "node_modules", "bower_components",
        "build", ".gradle", ".idea",
        "__pycache__", ".mypy_cache", ".pytest_cache",
        ".next", ".nuxt", ".turbo", ".cache",
        "dist", "out", "coverage",
        "venv", ".venv", "target",
        ".dart_tool", ".terraform",
    )

    fun isIgnoredDir(name: String): Boolean = name in DIR_NAMES

    /** True when any path segment *below* [startRelPath] is an ignored directory. */
    fun shouldSkip(relPath: String, startRelPath: String = "."): Boolean {
        val startParts = parts(startRelPath)
        val pathParts = parts(relPath)
        val extra = if (startParts.isEmpty()) {
            pathParts
        } else if (pathParts.take(startParts.size) == startParts) {
            pathParts.drop(startParts.size)
        } else {
            pathParts
        }
        return extra.any { isIgnoredDir(it) }
    }

    /**
     * Whether [dirName] should be skipped while walking from [startRelPath].
     * Walks that *start* inside an ignored directory still enter it — the user
     * asked to look there.
     */
    fun shouldSkipEnter(startRelPath: String, dirName: String): Boolean {
        if (!isIgnoredDir(dirName)) return false
        return dirName !in parts(startRelPath)
    }

    private fun parts(path: String): List<String> =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
}
