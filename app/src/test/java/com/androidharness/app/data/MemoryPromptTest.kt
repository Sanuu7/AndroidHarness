package com.androidharness.app.data

import com.androidharness.app.data.db.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptTest {
    private fun memory(
        id: String,
        text: String,
        scope: MemoryScope = MemoryScope.GLOBAL,
        pinned: Boolean = false,
        lastUsedAt: Long = 0,
        updatedAt: Long = 0,
        enabled: Boolean = true,
    ) = MemoryEntity(
        id = id,
        text = text,
        category = "FACT",
        scope = scope.name,
        projectId = if (scope == MemoryScope.PROJECT) "project" else null,
        pinned = pinned,
        enabled = enabled,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        lastUsedAt = lastUsedAt,
    )

    @Test fun pinnedEntriesComeFirstThenProjectThenRecentGlobal() {
        val block = MemoryPrompt.build(
            listOf(
                memory("global-new", "New global fact", lastUsedAt = 300, updatedAt = 300),
                memory("project", "Project fact", scope = MemoryScope.PROJECT, lastUsedAt = 200, updatedAt = 200),
                memory("pinned", "Pinned fact", pinned = true, lastUsedAt = 1, updatedAt = 1),
            ),
        )
        assertEquals(listOf("pinned", "project", "global-new"), block.usedIds)
        assertTrue(block.text.indexOf("Pinned fact") < block.text.indexOf("Project fact"))
    }

    @Test fun disabledEntriesAreNeverSent() {
        val block = MemoryPrompt.build(listOf(memory("off", "Hidden fact", enabled = false)))
        assertEquals(emptyList<String>(), block.usedIds)
        assertTrue(block.text.isEmpty())
    }

    @Test fun capOmitsExtraEntriesAndTellsTheModelHowToRecallThem() {
        val block = MemoryPrompt.build(
            listOf(
                memory("a", "a".repeat(60), updatedAt = 3),
                memory("b", "b".repeat(60), updatedAt = 2),
                memory("c", "c".repeat(60), updatedAt = 1),
            ),
            maxChars = 130,
        )
        assertEquals(listOf("a"), block.usedIds)
        assertEquals(2, block.omitted)
        assertTrue(block.text.contains("2 more saved memories"))
    }

    @Test fun normalizationDoesNotTruncateSavedText() {
        assertEquals("hello world", MemoryText.normalize("  hello \n\t world  "))
        assertEquals(null, MemoryText.normalize("   "))
        assertEquals(1_000, MemoryText.normalize("x".repeat(1_000))!!.length)
        assertFalse(MemoryText.normalize("valid")!!.isEmpty())
    }
}
