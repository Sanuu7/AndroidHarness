package com.androidharness.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.tools.*
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MemoryReliabilityDeviceTest {
    private val c get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container
    private fun fixture(block: suspend (MemoryRepository, AppDatabase) -> Unit) = runBlocking {
        val before = c.settings.settings.first()
        val db = Room.inMemoryDatabaseBuilder(c.appContext, AppDatabase::class.java).build()
        try {
            c.settings.setMemoryEnabled(true)
            c.settings.setMemoryEntryUnlimited(false)
            c.settings.setMemoryEntryMaxChars(600)
            c.settings.setSectionMemoryEnabled(MemorySection.CHAT, true)
            c.settings.setSectionMemoryEnabled(MemorySection.CODE, true)
            c.settings.setCrossSectionMemory(MemorySection.CHAT, false)
            c.settings.setCrossSectionMemory(MemorySection.CODE, false)
            for ((id, kind) in listOf("chat" to ConversationKind.CHAT, "code" to ConversationKind.CODE, "elsewhere" to ConversationKind.CODE)) {
                db.dao().insertSession(SessionEntity(id, id, 1, 1, projectId = if (id == "elsewhere") "other" else "project",
                    conversationJson = ConversationConfig.encode(ConversationConfig(kind))))
            }
            block(MemoryRepository(db, c.settings), db)
        } finally {
            c.settings.setMemoryEnabled(before.memoryEnabled)
            c.settings.setMemoryEntryUnlimited(before.memoryEntryUnlimited)
            c.settings.setMemoryEntryMaxChars(before.memoryEntryMaxChars)
            c.settings.setSectionMemoryEnabled(MemorySection.CHAT, before.chatMemoryEnabled)
            c.settings.setSectionMemoryEnabled(MemorySection.CODE, before.codeMemoryEnabled)
            c.settings.setCrossSectionMemory(MemorySection.CHAT, before.chatRecallCodeMemory)
            c.settings.setCrossSectionMemory(MemorySection.CODE, before.codeRecallChatMemory)
            db.close()
        }
    }

    @Test fun legacyMemoryMigrationKeepsContentAndMakesOwnershipExplicit() {
        val name = "memory-migration-${System.nanoTime()}.db"
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(c.appContext).name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(22) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE memories (id TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, category TEXT NOT NULL, scope TEXT NOT NULL, projectId TEXT, sourceSessionId TEXT, pinned INTEGER NOT NULL, enabled INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL, useCount INTEGER NOT NULL)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO memories VALUES ('legacy', 'Keep exact text', 'FACT', 'GLOBAL', NULL, NULL, 1, 1, 1, 2, 3, 4)")
            AppDatabase.MIGRATION_22_23.migrate(db)
            db.query("SELECT text, section, consolidatedInto, isSummary, pinned, useCount FROM memories WHERE id = 'legacy'").use { row ->
                assertTrue(row.moveToFirst()); assertEquals("Keep exact text", row.getString(0))
                assertEquals("SHARED", row.getString(1)); assertTrue(row.isNull(2)); assertEquals(0, row.getInt(3))
                assertEquals(1, row.getInt(4)); assertEquals(4, row.getInt(5))
            }
        } finally { helper.close(); c.appContext.deleteDatabase(name) }
    }

    @Test fun entryLimitsApplyToManualAndAgentWritesAndUnlimitedEntriesRemainRecallable() = fixture { repo, db ->
        val exact = requireNotNull(repo.add("x".repeat(600), MemoryCategory.FACT, MemoryScope.GLOBAL, null, MemorySection.CHAT))
        try {
            repo.add("x".repeat(601), MemoryCategory.FACT, MemoryScope.GLOBAL, null, MemorySection.CHAT)
            fail("The default limit must reject oversize entries")
        } catch (_: IllegalArgumentException) { }
        c.settings.setMemoryEntryMaxChars(1_200)
        val custom = repo.rememberResult("y".repeat(1_200), MemoryCategory.FACT, MemoryScope.GLOBAL, "chat").memory
        try {
            repo.update(custom, "y".repeat(1_201), MemoryCategory.FACT, MemoryScope.GLOBAL, null)
            fail("Edits must use the selected custom limit")
        } catch (_: IllegalArgumentException) { }
        c.settings.setMemoryEntryUnlimited(true)
        val text = "Detailed background. ".repeat(1_000) + "Garden irrigation is automated. " + "More context. ".repeat(1_000)
        repo.update(exact, text, MemoryCategory.FACT, MemoryScope.GLOBAL, null)
        val long = requireNotNull(db.dao().memory(exact.id))
        assertEquals(text.trim(), long.text)
        val agent = repo.rememberResult("Agent detail. ".repeat(2_000), MemoryCategory.PROJECT, MemoryScope.PROJECT, "code").memory
        assertTrue(agent.text.length > 20_000)
        val root = File(c.appContext.cacheDir, "memory-limit-${System.nanoTime()}").apply { mkdirs() }
        try {
            val recall = RecallTool(repo)
            val ctx = ToolContext(FileFs(root), sessionId = "chat")
            val preview = recall.execute(buildJsonObject { put("query", "garden irrigation") }, ctx).output
            assertTrue(preview.contains("Garden irrigation is automated"))
            assertTrue(preview.contains("recall(memory_id) for full text"))
            assertTrue(preview.length <= 12_000)
            assertTrue(recall.execute(buildJsonObject { put("memory_id", long.id) }, ctx).output.contains(long.text))
        } finally { root.deleteRecursively() }
        val portable = c.settings.settings.first()
        c.settings.setMemoryEntryUnlimited(false)
        c.settings.setMemoryEntryMaxChars(100)
        assertEquals(long.text, db.dao().memory(long.id)!!.text)
        assertEquals(agent.text, MemoryBackupMerge.normalize(agent.toBackupMemory())!!.text)
        c.settings.restorePortable(portable)
        val restored = SettingsRepository(c.appContext).settings.first()
        assertTrue(restored.memoryEntryUnlimited)
        assertEquals(1_200, restored.memoryEntryMaxChars)
    }

    @Test fun engineRefreshesEditedMemoryBeforeItsNextModelRequest() = fixture { repo, db ->
        val original = requireNotNull(repo.remember("Name is Sam", MemoryCategory.PROFILE, MemoryScope.GLOBAL, "chat"))
        var round = 0
        val prompts = mutableListOf<String>()
        val provider = object : com.androidharness.app.llm.LlmProvider {
            override fun streamChat(config: com.androidharness.app.llm.ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<com.androidharness.app.core.ChatMessage>, tools: List<com.androidharness.app.llm.ToolSchema>, options: com.androidharness.app.llm.RequestOptions) = kotlinx.coroutines.flow.flow {
                prompts += systemPrompt
                if (round++ == 0) emit(com.androidharness.app.llm.StreamEvent.ToolCallBatch(listOf(
                    com.androidharness.app.core.ToolCallData("update-name", "remember", buildJsonObject {
                        put("memory_id", original.id); put("previous_text", original.text); put("text", "Name is Samuel")
                    }.toString()))))
                else emit(com.androidharness.app.llm.StreamEvent.TextDelta("I updated your name."))
                emit(com.androidharness.app.llm.StreamEvent.Done(if (round == 1) "tool_calls" else "stop"))
            }
        }
        val root = File(c.appContext.cacheDir, "memory-engine-${System.nanoTime()}").apply { mkdirs() }
        try {
            val engine = com.androidharness.app.agent.AgentEngine({ provider }, ToolRegistry(listOf(RememberTool(repo), RecallTool(repo))),
                c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills, universalMemory = repo)
            val events = engine.run("chat", "memory-turn", com.androidharness.app.llm.ProviderConfig("fixture", "Fixture",
                com.androidharness.app.llm.ProviderType.OPENAI_COMPAT, "http://localhost", "fixture"), "",
                listOf(com.androidharness.app.core.ChatMessage(com.androidharness.app.core.Role.USER, "My name is Samuel. Update the saved name.")),
                { com.androidharness.app.agent.PermissionMode.FULL_ACCESS }, mutableSetOf(), FileFs(root),
                com.androidharness.app.llm.RequestOptions(), 100_000, com.androidharness.app.agent.AgentMode.ACT,
                conversation = ConversationConfig(ConversationKind.CHAT)).toList()
            assertEquals(2, prompts.size)
            assertTrue(prompts.first().contains("Name is Sam"))
            assertTrue(prompts.last().contains("Name is Samuel"))
            assertEquals("Name is Samuel", db.dao().memory(original.id)!!.text)
            assertFalse(events.any { it is com.androidharness.app.agent.AgentEvent.Error })
        } finally { root.deleteRecursively() }
    }

    @Test fun repeatedConcurrentSavesReuseOneMemoryAndKeepSectionsSeparate() = fixture { repo, db ->
        val writes = coroutineScope { (1..8).map { async(Dispatchers.Default) {
            repo.rememberResult("I prefer Kotlin.", MemoryCategory.PREFERENCE, MemoryScope.GLOBAL, "chat")
        } }.awaitAll() }
        assertEquals(1, writes.map { it.memory.id }.toSet().size)
        assertEquals(1, db.dao().allMemories().size)
        assertEquals(1, writes.count { it.action == MemoryRepository.Action.SAVED })
        assertEquals("CHAT", writes.first().memory.section)
        val duplicate = repo.rememberResult("I prefer KOTLIN!", MemoryCategory.PREFERENCE, MemoryScope.GLOBAL, "chat")
        assertEquals(writes.first().memory.id, duplicate.memory.id)
        assertEquals(MemoryRepository.Action.UNCHANGED, duplicate.action)
        val code = repo.rememberResult("I prefer Kotlin.", MemoryCategory.PREFERENCE, MemoryScope.GLOBAL, "code")
        assertEquals("CODE", code.memory.section)
        assertNotEquals(duplicate.memory.id, code.memory.id)
        assertEquals(2, db.dao().allMemories().size)
    }

    @Test fun modelCanEditByIdButCannotOverwriteStaleOrForeignMemory() = fixture { repo, db ->
        val original = requireNotNull(repo.remember("Prefer concise Kotlin examples for Android development", MemoryCategory.PREFERENCE, MemoryScope.GLOBAL, "chat"))
        repo.setPinned(original.id, true)
        val tool = RememberTool(repo)
        val root = File(c.appContext.cacheDir, "memory-tools-${System.nanoTime()}").apply { mkdirs() }
        val ctx = ToolContext(FileFs(root), sessionId = "chat")
        try {
            val updated = tool.execute(buildJsonObject {
                put("memory_id", original.id); put("previous_text", original.text)
                put("text", "Prefer detailed Kotlin examples for Android development")
            }, ctx)
            assertTrue(updated.output.contains("[memory_action:updated]"))
            val current = requireNotNull(db.dao().memory(original.id))
            assertEquals(original.createdAt, current.createdAt); assertTrue(current.pinned)
            assertEquals("PREFERENCE", current.category)
            assertEquals(1, db.dao().allMemories().size)
            try {
                tool.execute(buildJsonObject { put("memory_id", original.id); put("previous_text", original.text); put("text", "Stale change") }, ctx)
                fail("Stale text must be rejected")
            } catch (_: ToolFailure) { }
            assertEquals(current.text, db.dao().memory(original.id)!!.text)
            try {
                tool.execute(buildJsonObject { put("text", "Use detailed Kotlin examples for Android development") }, ctx)
                fail("A similar fact needs explicit review instead of another automatic save")
            } catch (_: ToolFailure) { }
            assertEquals(1, db.dao().allMemories().size)
            c.settings.setCrossSectionMemory(MemorySection.CODE, true)
            try {
                tool.execute(buildJsonObject { put("memory_id", current.id); put("previous_text", current.text); put("text", "Foreign change") },
                    ToolContext(FileFs(root), sessionId = "code"))
                fail("Cross-section lookup must not grant write access")
            } catch (_: ToolFailure) { }
        } finally { root.deleteRecursively() }
    }

    @Test fun automaticMemoryAndExplicitCrossLookupHonorSettingsProjectsAndPersonalization() = fixture { repo, db ->
        val chat = requireNotNull(repo.remember("Chat prefers short replies", MemoryCategory.PREFERENCE, MemoryScope.GLOBAL, "chat"))
        val code = requireNotNull(repo.remember("Code project uses Room", MemoryCategory.PROJECT, MemoryScope.PROJECT, "code"))
        val foreign = requireNotNull(repo.remember("Other project uses Realm", MemoryCategory.PROJECT, MemoryScope.PROJECT, "elsewhere"))
        val shared = requireNotNull(repo.add("Existing shared profile", MemoryCategory.PROFILE, MemoryScope.GLOBAL, null))
        val prompt = repo.promptBlock("chat")!!
        assertTrue(prompt.contains(chat.text)); assertTrue(prompt.contains(shared.text)); assertFalse(prompt.contains(code.text))
        try { repo.search("", "chat", MemorySection.CODE); fail("Cross lookup starts disabled") } catch (_: IllegalStateException) { }
        c.settings.setCrossSectionMemory(MemorySection.CHAT, true)
        val recalled = repo.search("Room", "chat", MemorySection.CODE)
        assertEquals(listOf(code.id), recalled.map { it.id })
        assertFalse(repo.search("", "chat", MemorySection.CODE).any { it.id == foreign.id })
        assertFalse(repo.promptBlock("chat")!!.contains(code.text))
        c.settings.setSectionMemoryEnabled(MemorySection.CODE, false)
        assertNull(repo.promptBlock("code")); assertNotNull(repo.promptBlock("chat"))
        try { repo.search("", "chat", MemorySection.CODE); fail("Disabled target is inaccessible") } catch (_: IllegalStateException) { }
        db.dao().setConversation("chat", ConversationConfig.encode(ConversationConfig(ConversationKind.CHAT, personalizationEnabled = false)))
        assertNull(repo.promptBlock("chat"))
        c.settings.setMemoryEnabled(false)
        assertNull(repo.promptBlock("elsewhere"))
    }

    @Test fun organizedMemoryCanBeEditedByModelAndRestoringKeepsLaterEdits() = fixture { repo, db ->
        repo.add("Name is Sam. " + "Keeps technical identifiers intact. ".repeat(10), MemoryCategory.PROFILE, MemoryScope.GLOBAL, null, MemorySection.CHAT)
        repo.add("Prefers concise replies. " + "Uses clear paragraphs and lists. ".repeat(10), MemoryCategory.PREFERENCE, MemoryScope.GLOBAL, null, MemorySection.CHAT)
        val plan = MemoryConsolidation.plans(db.dao().allMemories()).single()
        val digest = repo.consolidate(plan, plan.text)
        assertTrue(digest.text.length > 600)
        val root = File(c.appContext.cacheDir, "memory-digest-${System.nanoTime()}").apply { mkdirs() }
        val ctx = ToolContext(FileFs(root), sessionId = "chat")
        try {
            val recalled = RecallTool(repo).execute(buildJsonObject { put("memory_id", digest.id) }, ctx)
            assertTrue(recalled.output.contains(digest.text))
            val revised = digest.text + "\n• Uses a screen reader"
            val updated = RememberTool(repo).execute(buildJsonObject {
                put("memory_id", digest.id); put("previous_text", digest.text); put("text", revised)
            }, ctx)
            assertTrue(updated.output.contains("[memory_action:updated]"))
            assertEquals(revised, db.dao().memory(digest.id)!!.text)
            repo.restoreOriginals(digest.id)
            assertEquals(2, db.dao().activeMemories(null).size)
            val kept = requireNotNull(db.dao().memory(digest.id))
            assertFalse(kept.enabled); assertEquals(revised, kept.text)
            repo.remove(kept.id)
            val next = repo.consolidate(MemoryConsolidation.plans(db.dao().allMemories()).single(), plan.text)
            repo.remove(next.id)
            assertTrue("Forgetting a digest also forgets its preserved originals", db.dao().allMemories().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun consolidationIsAtomicPreservesOriginalsAndCanBeRestored() = fixture { repo, db ->
        val first = requireNotNull(repo.add("Name is Sam", MemoryCategory.PROFILE, MemoryScope.GLOBAL, null, MemorySection.CHAT))
        val second = requireNotNull(repo.add("Prefers concise answers", MemoryCategory.PREFERENCE, MemoryScope.GLOBAL, null, MemorySection.CHAT))
        val plan = MemoryConsolidation.plans(db.dao().allMemories()).single()
        // Normal prompt use while reviewing does not invalidate the facts.
        repo.promptBlock("chat")
        val digest = repo.consolidate(plan, plan.text)
        assertEquals(3, db.dao().allMemories().size)
        assertEquals(listOf(digest.id), db.dao().activeMemories("project").map { it.id })
        assertEquals(first.text, db.dao().memory(first.id)!!.text)
        assertEquals(digest.id, db.dao().memory(second.id)!!.consolidatedInto)
        assertTrue(repo.promptBlock("chat")!!.contains(first.text))
        repo.restoreOriginals(digest.id)
        assertNull(db.dao().memory(digest.id))
        assertEquals(setOf(first.id, second.id), db.dao().activeMemories(null).map { it.id }.toSet())
        val stalePlan = MemoryConsolidation.plans(db.dao().allMemories()).single()
        val latest = db.dao().memory(first.id)!!
        repo.update(latest, "Name is Samuel", MemoryCategory.PROFILE, MemoryScope.GLOBAL, null)
        try { repo.consolidate(stalePlan, stalePlan.text); fail("A stale review must not hide edited originals") } catch (_: IllegalStateException) { }
        assertEquals(2, db.dao().allMemories().size)
        assertTrue(db.dao().allMemories().all { it.consolidatedInto == null })
    }
}
