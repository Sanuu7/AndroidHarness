package com.androidharness.app.workspace

import java.io.File

/**
 * File operations behind "Reset app workspace" (issue #106). The app workspace is the
 * permanent default, so it cannot be deleted like other workspaces; resetting empties it
 * instead. Kept free of Android APIs so it is unit-testable.
 */
internal object AppWorkspaceReset {
    /** Deletes every child of [root] and recreates the empty folder. */
    fun clear(root: File) {
        root.listFiles()?.forEach { it.deleteRecursively() }
        root.mkdirs()
    }

    /** Bytes held by the files under [root]; a missing folder counts as zero. */
    fun bytes(root: File): Long =
        if (!root.exists()) 0L else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
