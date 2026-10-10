package com.androidharness.app.github

import org.junit.Assert.*
import org.junit.Test

class GitChangeTreeTest {
    private val changes = listOf(
        GitChange("app/src/Main.kt", " M"),
        GitChange("app/src/deleted.kt", " D"),
        GitChange("app/assets/new name.txt", "R ", "outside/old.txt"),
        GitChange("app-copy/other.txt", "??"),
        GitChange("README.md", " M"),
    )

    @Test fun folderViewsIncludeAllNestedChangesAndKeepFileMetadata() {
        val tree = GitChangeTree(changes)
        assertEquals(listOf("app", "app-copy", "README.md"), tree.entries("").map { it.path })
        val app = tree.entries("").first()
        assertTrue(app.isDirectory)
        assertEquals(changes.take(3).map { it.path }.toSet(), app.paths)
        assertEquals(listOf("app/assets", "app/src"), tree.entries("app").map { it.path })
        assertEquals(changes[2], tree.entries("app/assets").single().changes.single())
        assertEquals(changes[1], tree.entries("app/src").last().changes.single())
    }

    @Test fun navigatingAndTogglingFoldersKeepsSelectionsInOtherDirectories() {
        val tree = GitChangeTree(changes)
        val app = tree.entries("").first()
        var selected = setOf("README.md", "app/src/Main.kt")
        assertEquals(1, app.selectedCount(selected))
        selected = toggleGitPaths(selected, app.paths)
        assertEquals(app.paths + "README.md", selected)
        // Browsing into and out of folders cannot mutate a selection.
        tree.entries("app"); tree.entries("app/assets"); tree.entries("")
        assertEquals(3, app.selectedCount(selected))
        selected = toggleGitPaths(selected, tree.entries("app").last().paths)
        assertEquals(setOf("README.md", "app/assets/new name.txt"), selected)
        assertEquals(1, app.selectedCount(selected))
        assertFalse(selected.contains("app-copy/other.txt"))
    }

    @Test fun refreshPrunesMissingFilesAndReturnsToNearestSurvivingFolder() {
        val selected = changes.map { it.path }.toSet()
        val tree = GitChangeTree(listOf(changes[0], changes[3]))
        assertEquals(setOf(changes[0].path, changes[3].path), selected.intersect(tree.paths))
        assertEquals("app", tree.survivingDirectory("app/assets"))
        assertEquals("app/src", tree.survivingDirectory("app/src"))
        assertEquals("", GitChangeTree(emptyList()).survivingDirectory("app/src"))
    }

    @Test fun deletedFileAndReplacementFolderCanShareANameWithoutSharingSelection() {
        val tree = GitChangeTree(listOf(GitChange("app", " D"), GitChange("app/new.txt", "??")))
        val entries = tree.entries("")
        assertEquals(2, entries.size)
        assertEquals(setOf("app/new.txt"), entries.first().paths)
        assertEquals(setOf("app"), entries.last().paths)
    }

    @Test fun unicodeSpacesAndNewlinesRemainExactPaths() {
        val path = "project 世界/sub folder/a\nb.txt"
        val tree = GitChangeTree(listOf(GitChange(path, "??")))
        assertEquals(setOf(path), tree.entries("").single().paths)
        assertEquals("a\nb.txt", tree.entries("project 世界/sub folder").single().name)
    }
}
