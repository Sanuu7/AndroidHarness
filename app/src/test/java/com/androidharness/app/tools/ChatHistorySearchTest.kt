package com.androidharness.app.tools

import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatHistorySearchTest {
    private fun ctx() = ToolContext(FileFs(java.io.File(System.getProperty("java.io.tmpdir"))), sessionId = "current")

    @Test fun restrictedAndRepeatedHitsDoNotHideAnOlderAllowedConversation() = runBlocking {
        val hidden = (0 until 64).map { MessageHit("h$it", "hidden", "Hidden", "topic", 200) }
        val wanted = MessageHit("exact", "allowed", "Earlier topic", "topic answer", 100, ConversationKind.CHAT)
        val offsets = mutableListOf<Int>()
        val tool = SearchChatsTool { _, _, _, offset ->
            offsets += offset
            if (offset == 0) hidden else listOf(wanted)
        }.apply { canAccess = { _, target -> target == "allowed" } }
        val result = tool.execute(buildJsonObject { put("query", "topic") }, ctx())
        assertEquals(listOf(0, 64), offsets)
        assertFalse(result.output.contains("Hidden"))
        val card = result.chatHistory!!.matches.single()
        assertEquals("allowed", card.sessionId)
        assertEquals("exact", card.messageId)
        assertEquals(ConversationKind.CHAT, card.kind)
    }

    @Test fun directReadCannotBypassHistoryPermission() = runBlocking {
        val tool = ReadChatSnippetTool(load = { error("Must not load denied messages") }, session = { error("Must not load denied session") })
            .apply { canAccess = { _, _ -> false } }
        try {
            tool.execute(buildJsonObject { put("session_id", "blocked") }, ctx())
            fail("Denied history must not be read")
        } catch (_: ToolFailure) { }
    }

    @Test fun missingMessageUsesTheActualFallbackMessageAsCardTarget() = runBlocking {
        val tool = ReadChatSnippetTool(load = { listOf(ChatMessage(Role.USER, "Latest question", id = "actual", createdAt = 5)) },
            session = { SessionEntity("saved", "Earlier chat", 1, 5) })
        val result = tool.execute(buildJsonObject { put("session_id", "saved"); put("message_id", "deleted") }, ctx())
        assertEquals("actual", result.chatHistory!!.matches.single().messageId)
        assertTrue(result.output.contains("exact hit was not found"))
    }
}
