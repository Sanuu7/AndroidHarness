package com.androidharness.app.data

import com.androidharness.app.data.db.MemoryEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MemoryEntryLimitTest {
    @Test fun defaultsKeepThe600CharacterLimitAndRejectOversizeWithoutTruncating() {
        val settings = AppSettings()
        assertFalse(settings.memoryEntryUnlimited)
        assertEquals(600, settings.memoryEntryMaxChars)
        assertEquals("x".repeat(600), MemoryText.checked("x".repeat(600), settings))
        val error = assertThrows(IllegalArgumentException::class.java) {
            MemoryText.checked("x".repeat(601), settings)
        }
        assertTrue(error.message!!.contains("600"))
        assertTrue(error.message!!.contains("Settings > Memory"))
    }

    @Test fun customLimitsApplyAtTheirExactBoundary() {
        for (limit in listOf(1, 100, 1_500, 30_000)) {
            val settings = AppSettings(memoryEntryMaxChars = limit)
            assertEquals("x".repeat(limit), MemoryText.checked("x".repeat(limit), settings))
            assertThrows(IllegalArgumentException::class.java) { MemoryText.checked("x".repeat(limit + 1), settings) }
        }
        assertEquals("hello world", MemoryText.checked("  hello\n\tworld  ", AppSettings(memoryEntryMaxChars = 11)))
        assertThrows(IllegalArgumentException::class.java) { MemoryText.checked("  \n ", AppSettings()) }
    }

    @Test fun unlimitedIgnoresTheSavedCustomLimitForEntriesAndOrganizedMemories() {
        val text = "Detailed memory. ".repeat(10_000).trim()
        val settings = AppSettings(memoryEntryUnlimited = true, memoryEntryMaxChars = 10)
        assertNull(MemoryText.maxChars(settings))
        assertEquals(text, MemoryText.checked(text, settings))
        assertEquals(text, MemoryText.checked(text, settings, summary = true))
        assertEquals(10, MemoryText.maxChars(settings.copy(memoryEntryUnlimited = false)))
        assertThrows(IllegalArgumentException::class.java) { MemoryText.checked(text, settings.copy(memoryEntryUnlimited = false)) }
    }

    @Test fun organizedMemoriesRetainTheirExistingAllowanceAndCanUseHigherLimits() {
        assertEquals(MemoryConsolidation.MAX_CHARS, MemoryText.maxChars(AppSettings(), summary = true))
        val custom = AppSettings(memoryEntryMaxChars = 30_000)
        assertEquals(30_000, MemoryText.maxChars(custom, summary = true))
        assertEquals(30_000, MemoryText.checked("x".repeat(30_000), custom, summary = true).length)
    }

    @Test fun preferencesAndLongEntriesSurviveSettingsAndMemoryBackupsAfterLoweringTheLimit() {
        val text = "Saved detail. ".repeat(3_000).trim()
        val settings = AppSettings(memoryEntryUnlimited = true, memoryEntryMaxChars = 2_500)
        val memories = listOf(BackupMemory("entry", text), BackupMemory("digest", text, isSummary = true))
        val original = SettingsBackupFile(settings = settings, memories = memories)
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString(SettingsBackupFile.serializer(), json.encodeToString(SettingsBackupFile.serializer(), original))
        assertEquals(settings, restored.settings)
        assertEquals(memories, restored.memories)
        SettingsBackupValidation.validate(restored)
        // Existing content must remain portable even after the user turns the default cap back on.
        SettingsBackupValidation.validate(restored.copy(settings = AppSettings()))
        val merged = MemoryBackupMerge.plan(emptyList(), restored.memories)
        assertEquals(listOf(text, text), merged.map { it.text })
        val old = json.decodeFromString(AppSettings.serializer(), "{}")
        assertFalse(old.memoryEntryUnlimited)
        assertEquals(600, old.memoryEntryMaxChars)
        for (limit in listOf(0, -1)) {
            assertThrows(SettingsBackupValidationException::class.java) {
                SettingsBackupValidation.validate(original.copy(settings = settings.copy(memoryEntryMaxChars = limit)))
            }
        }
    }

    @Test fun longMemoriesSupplyRelevantBoundedPreviewsAndKeepTheFullOriginal() {
        val text = "Unrelated beginning. ".repeat(1_000) + "Garden irrigation is automated. " + "Unrelated ending. ".repeat(1_000)
        val memory = MemoryEntity("long", text, createdAt = 1, updatedAt = 1)
        val block = MemoryPrompt.build(listOf(memory), query = "garden irrigation")
        assertEquals(listOf("long"), block.usedIds)
        assertTrue(block.text.length <= MemoryPrompt.MAX_CHARS)
        assertTrue(block.text.contains("Garden irrigation is automated"))
        assertTrue(block.text.contains("recall(memory_id) for full text"))
        assertEquals(text, memory.text)
        val preview = MemoryText.excerpt(text, 4_000, "garden irrigation")
        assertTrue(preview.length <= 4_000)
        assertTrue(preview.contains("Garden irrigation is automated"))
        assertEquals("", MemoryText.excerpt(text, 0))
        assertEquals("Un", MemoryText.excerpt(text, 2))
    }
}
