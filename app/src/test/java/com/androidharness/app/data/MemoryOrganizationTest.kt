package com.androidharness.app.data

import com.androidharness.app.data.db.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryOrganizationTest {
    private val day = 86_400_000L
    private val now = 400L * day

    private fun memory(
        id: String,
        text: String,
        keywords: String = "",
        pinned: Boolean = false,
        createdAt: Long = now - 300 * day,
        lastUsedAt: Long = 0,
        useCount: Int = 0,
        folder: String? = null,
        category: String = "FACT",
    ) = MemoryEntity(
        id = id, text = text, category = category, pinned = pinned, createdAt = createdAt, updatedAt = createdAt,
        lastUsedAt = lastUsedAt, useCount = useCount, folder = folder, keywords = keywords,
    )

    @Test fun builtInFolderNamesMapToCategoriesInEitherForm() {
        assertEquals(MemoryCategory.PREFERENCE, MemoryFolders.builtIn("Preferences"))
        assertEquals(MemoryCategory.FACT, MemoryFolders.builtIn(" fact "))
        assertNull(MemoryFolders.builtIn("Termux"))
        assertEquals(MemoryFolderTarget(MemoryCategory.DECISION, null), MemoryFolders.target("decisions", MemoryCategory.FACT))
        assertEquals(MemoryFolderTarget(MemoryCategory.FACT, "Lease 2027"), MemoryFolders.target("  Lease   2027 ", MemoryCategory.FACT))
        assertEquals(MemoryFolderTarget(MemoryCategory.PROFILE, null), MemoryFolders.target(null, MemoryCategory.PROFILE))
    }

    @Test fun folderNamesAreCheckedNotSilentlyChanged() {
        assertEquals("Lease 2027", MemoryFolders.checkedName(" Lease 2027 "))
        assertThrows(IllegalArgumentException::class.java) { MemoryFolders.checkedName("   ") }
        assertThrows(IllegalArgumentException::class.java) { MemoryFolders.checkedName("Facts") }
        assertThrows(IllegalArgumentException::class.java) { MemoryFolders.checkedName("a,b") }
        assertThrows(IllegalArgumentException::class.java) { MemoryFolders.checkedName("x".repeat(41)) }
        assertTrue(MemoryFolders.same("Termux", "termux"))
        assertFalse(MemoryFolders.same("Termux", null))
    }

    @Test fun keywordsNormalizeAndMatchWholeWordsOnly() {
        assertEquals("termux, dark mode", MemoryKeywords.normalize(" Termux ,dark mode,, TERMUX"))
        assertTrue(MemoryKeywords.hit("termux", "How do I install Termux packages?"))
        assertTrue(MemoryKeywords.hit("dark mode", "switch the app to Dark Mode please"))
        assertFalse(MemoryKeywords.hit("adb", "my badbeef build"))
        assertFalse(MemoryKeywords.hit("", "anything"))
        assertThrows(IllegalArgumentException::class.java) { MemoryKeywords.checked((1..13).joinToString(",") { "w$it" }) }
    }

    @Test fun keywordHitRanksAboveWordOverlapButBelowPinned() {
        val block = MemoryPrompt.build(
            listOf(
                memory("overlap", "Builds run on the phone"),
                memory("trigger", "Use the F-Droid build of the terminal", keywords = "termux"),
                memory("pinned", "Answer briefly", pinned = true),
            ),
            query = "termux builds on the phone", now = now,
        )
        assertEquals(listOf("pinned", "trigger", "overlap"), block.usedIds)
        // Pinned memories that do not match the conversation do not count as used.
        assertEquals(listOf("trigger", "overlap"), block.matchedIds)
    }

    @Test fun recentlyUsefulMemoriesOutrankStaleOnesWhenNothingMatches() {
        val stale = memory("stale", "Old unused fact", createdAt = now - 200 * day)
        val busy = memory("busy", "Often used fact", createdAt = now - 200 * day, lastUsedAt = now - day, useCount = 12)
        val fresh = memory("fresh", "Saved yesterday", createdAt = now - day)
        val block = MemoryPrompt.build(listOf(stale, fresh, busy), query = "", now = now)
        assertEquals(listOf("busy", "fresh", "stale"), block.usedIds)
        assertTrue(MemoryUse.score(stale, now) < 0.02)
        assertEquals(emptyList<String>(), block.matchedIds)
    }

    @Test fun budgetScalesWithTheContextWindow() {
        assertEquals(MemoryPrompt.MAX_CHARS, MemoryPrompt.budgetFor(null))
        assertEquals(MemoryPrompt.MIN_CHARS, MemoryPrompt.budgetFor(4_096))
        assertTrue(MemoryPrompt.budgetFor(16_384) in MemoryPrompt.MIN_CHARS until MemoryPrompt.MAX_CHARS)
        assertEquals(MemoryPrompt.MAX_CHARS, MemoryPrompt.budgetFor(32_000))
        assertEquals(7_680, MemoryPrompt.budgetFor(128_000))
        assertEquals(MemoryPrompt.LARGE_MAX_CHARS, MemoryPrompt.budgetFor(1_000_000))
    }

    @Test fun largerBudgetSuppliesMoreMemories() {
        val many = (1..40).map { memory("m$it", "Fact number $it " + "detail ".repeat(25)) }
        val small = MemoryPrompt.build(many, maxChars = MemoryPrompt.budgetFor(8_000), now = now)
        val large = MemoryPrompt.build(many, maxChars = MemoryPrompt.budgetFor(400_000), now = now)
        assertTrue(large.usedIds.size > small.usedIds.size)
        assertTrue(large.text.length <= MemoryPrompt.budgetFor(400_000))
    }

    @Test fun promptLabelsShowTheFolder() {
        val block = MemoryPrompt.build(listOf(memory("a", "Rent is due on the 1st", folder = "Lease 2027")), now = now)
        assertTrue(block.text.contains("[id:a Lease 2027]"))
    }

    @Test fun backupKeepsFoldersAndDropsInvalidOnes() {
        val good = MemoryBackupMerge.normalize(BackupMemory("1", "Fact", folder = "Lease 2027", keywords = "Rent, RENT, lease"))!!
        assertEquals("Lease 2027", good.folder)
        assertEquals("rent, lease", good.keywords)
        val builtIn = MemoryBackupMerge.normalize(BackupMemory("2", "Fact", category = "FACT", folder = "Preferences"))!!
        assertNull(builtIn.folder)
        assertEquals("PREFERENCE", builtIn.category)
        val bad = MemoryBackupMerge.normalize(BackupMemory("3", "Fact", folder = "a,b"))!!
        assertNull(bad.folder)
        val entity = MemoryBackupMerge.entity(good)
        assertEquals("Lease 2027", entity.folder)
        assertEquals("rent, lease", entity.keywords)
    }
}
