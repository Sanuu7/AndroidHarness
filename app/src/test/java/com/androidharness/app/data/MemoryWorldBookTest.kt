package com.androidharness.app.data

import com.androidharness.app.data.db.MemoryEntity
import com.androidharness.app.data.db.MemoryFolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trigger-only memories and folders limited to chosen chat agents. */
class MemoryWorldBookTest {
    private fun memory(
        id: String, text: String, keywords: String = "", triggerOnly: Boolean = false, pinned: Boolean = false,
        folder: String? = null, agentId: String? = null, section: String = "CHAT",
    ) = MemoryEntity(id = id, text = text, createdAt = 1, updatedAt = 1, keywords = keywords, triggerOnly = triggerOnly,
        pinned = pinned, folder = folder, agentId = agentId, section = section)

    @Test fun triggerOnlyMemoryStaysOutUntilAKeywordComesUp() {
        val lore = memory("lore", "The dragon guards the north gate.", keywords = "dragon, castle", triggerOnly = true, pinned = true)
        val plain = memory("plain", "The user likes short answers.")
        val quiet = MemoryPrompt.build(listOf(lore, plain), query = "what should I cook tonight")
        assertEquals(listOf("plain"), quiet.usedIds)
        assertEquals(0, quiet.omitted)
        val asked = MemoryPrompt.build(listOf(lore, plain), query = "tell me about the castle")
        assertEquals(listOf("lore", "plain"), asked.usedIds)
    }

    @Test fun wordsThatMerelyShareTextDoNotWakeATriggerOnlyMemory() {
        val lore = memory("lore", "The dragon guards the north gate.", keywords = "dragon", triggerOnly = true)
        assertTrue(MemoryPrompt.build(listOf(lore), query = "the north gate").usedIds.isEmpty())
    }

    @Test fun limitedFolderIsSeenOnlyByItsAgents() {
        val limits = MemoryFolderAgents.of(listOf(MemoryFolderEntity("Lore", 1, "aria,kai"), MemoryFolderEntity("Open", 1)))
        val lore = memory("a", "Dragon lore", folder = "lore")
        val open = memory("b", "Open fact", folder = "Open")
        // A shared-memory agent that is listed, one that is not, and no agent at all.
        assertTrue(MemoryAccess.belongsTo(lore, MemorySection.CHAT, owner = null, agent = "aria", folderAgents = limits))
        assertFalse(MemoryAccess.belongsTo(lore, MemorySection.CHAT, owner = null, agent = "coder", folderAgents = limits))
        assertFalse(MemoryAccess.belongsTo(lore, MemorySection.CHAT, owner = null, agent = null, folderAgents = limits))
        assertFalse(MemoryAccess.belongsTo(lore.copy(section = "SHARED"), MemorySection.CODE, folderAgents = limits))
        // A listed agent with private memory still sees the folder; an unlisted one sees nothing shared.
        assertTrue(MemoryAccess.belongsTo(lore, MemorySection.CHAT, owner = "kai", agent = "kai", folderAgents = limits))
        assertFalse(MemoryAccess.belongsTo(open, MemorySection.CHAT, owner = "kai", agent = "kai", folderAgents = limits))
        assertTrue(MemoryAccess.belongsTo(open, MemorySection.CHAT, owner = null, agent = "coder", folderAgents = limits))
    }

    @Test fun privateMemoryStaysWithItsOwnerWhateverTheFolder() {
        val limits = MemoryFolderAgents.of(listOf(MemoryFolderEntity("Lore", 1, "aria")))
        val mine = memory("p", "Kai's own note", folder = "Lore", agentId = "kai")
        assertTrue(MemoryAccess.belongsTo(mine, MemorySection.CHAT, owner = "kai", agent = "kai", folderAgents = limits))
        assertFalse(MemoryAccess.belongsTo(mine, MemorySection.CHAT, owner = "aria", agent = "aria", folderAgents = limits))
    }

    @Test fun agentListIsTrimmedSortedAndDeduplicated() {
        assertEquals("aria,kai", MemoryFolderAgents.encode(listOf(" kai", "aria", "kai", "")))
        assertEquals(setOf("aria", "kai"), MemoryFolderAgents.parse("kai, aria,,"))
        assertTrue(MemoryFolderAgents.of(listOf(MemoryFolderEntity("Open", 1, ""))).isEmpty())
    }

    @Test fun backupCarriesTriggerOnlyAndTheFolderLimit() {
        val folders = listOf(MemoryFolderEntity("Lore", 1, "kai,aria"))
        val saved = listOf(memory("a", "Dragon lore", keywords = "dragon", triggerOnly = true, folder = "Lore")).toBackupMemories(folders).single()
        assertTrue(saved.triggerOnly)
        assertEquals("aria,kai", saved.folderAgents)
        val restored = MemoryBackupMerge.plan(emptyList(), listOf(saved)).single()
        assertTrue(MemoryBackupMerge.entity(restored).triggerOnly)
        assertEquals(listOf(MemoryFolderEntity("Lore", 5, "aria,kai")), MemoryBackupMerge.newFolders(listOf(restored), emptyList(), now = 5))
        // A folder already on this device keeps its own setting.
        assertTrue(MemoryBackupMerge.newFolders(listOf(restored), listOf("lore")).isEmpty())
    }

    @Test fun restoredTriggerOnlyWithoutKeywordsIsTurnedOff() {
        val broken = BackupMemory(id = "x", text = "Lore", triggerOnly = true)
        assertFalse(MemoryBackupMerge.plan(emptyList(), listOf(broken)).single().triggerOnly)
    }
}
