package com.androidharness.app.github

/** A folder view of Git's changed paths, including files no longer on disk. */
class GitChangeTree(changes: List<GitChange>) {
    private val changes = changes.distinctBy { it.path }
    val paths: Set<String> = this.changes.map { it.path }.toSet()

    data class Entry(val path: String, val isDirectory: Boolean, val changes: List<GitChange>) {
        val name: String get() = path.substringAfterLast('/')
        val paths: Set<String> = changes.map { it.path }.toSet()
        fun selectedCount(selected: Set<String>): Int = paths.count { it in selected }
    }

    fun entries(directory: String): List<Entry> {
        val prefix = if (directory.isEmpty()) "" else "$directory/"
        val folders = linkedMapOf<String, MutableList<GitChange>>()
        val files = mutableListOf<Entry>()
        for (change in changes) {
            if (!change.path.startsWith(prefix)) continue
            val relative = change.path.removePrefix(prefix)
            if ('/' in relative) {
                val folder = prefix + relative.substringBefore('/')
                folders.getOrPut(folder) { mutableListOf() }.add(change)
            } else files += Entry(change.path, false, listOf(change))
        }
        return folders.map { (path, children) -> Entry(path, true, children) }.sortedBy { it.name } +
            files.sortedBy { it.name }
    }

    /** Return to a surviving ancestor if refresh removes the currently open folder. */
    fun survivingDirectory(directory: String): String {
        var current = directory
        while (current.isNotEmpty() && paths.none { it.startsWith("$current/") }) {
            current = current.substringBeforeLast('/', "")
        }
        return current
    }
}

/** Folder selection always resolves to exact changed files, never a Git directory pathspec. */
fun toggleGitPaths(selected: Set<String>, paths: Set<String>): Set<String> =
    if (selected.containsAll(paths)) selected - paths else selected + paths
