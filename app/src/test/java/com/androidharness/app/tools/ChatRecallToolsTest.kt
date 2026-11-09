package com.androidharness.app.tools

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind
import com.androidharness.app.data.MessageHit
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRecallToolsTest {
    private fun context() = ToolContext(
        workspace = FileFs(java.nio.file.Files.createTempDirectory("recall-test").toFile()),
        sessionId = "current",
    )

    @Test fun searchExcludesTheCurrentChatAndReturnsCompactIds() = runBlocking {
        val hits = listOf(
            MessageHit(
                messageId = "m1",
                sessionId = "other",
                sessionTitle = "Elden Ring graphics",
                text = "The wide shots are painterly and the art direction carries the game.",
                createdAt = 1_700_000_000_000,
            ),
            MessageHit(
                messageId = "m2",
                sessionId = "current",
                sessionTitle = "Current chat",
                text = "Elden Ring graphics in this chat.",
                createdAt = 1_700_000_000_000,
            ),
        )
        val tool = SearchChatsTool { _, _, _, _ -> hits }
        val result = tool.execute(buildJsonObject { put("query", "elden ring") }, context())
        assertTrue(result.output.contains("Elden Ring graphics"))
        assertFalse(result.output.contains("Current chat"))
        assertTrue(result.output.contains("session: other"))
        assertTrue(result.output.contains("message: m1"))
    }

    @Test fun recentChatsShowsTitlesAndKindsButNotTheCurrentChat() = runBlocking {
        val chat = SessionEntity(
            id = "other",
            title = "Elden Ring graphics",
            createdAt = 1,
            updatedAt = 2,
            conversationJson = ConversationConfig.encode(ConversationConfig(kind = ConversationKind.CHAT)),
        )
        val current = SessionEntity(
            id = "current",
            title = "Current chat",
            createdAt = 1,
            updatedAt = 3,
            conversationJson = ConversationConfig.encode(ConversationConfig(kind = ConversationKind.CHAT)),
        )
        val tool = RecentChatsTool(flowOf(listOf(current, chat)))
        val result = tool.execute(buildJsonObject { }, context())
        assertTrue(result.output.contains("Elden Ring graphics"))
        assertTrue(result.output.contains("Chat"))
        assertFalse(result.output.contains("Current chat"))
        assertTrue(result.output.contains("session: other"))
    }

    @Test fun readSnippetShowsVisibleNeighboursAndSkipsToolRows() = runBlocking {
        val messages = listOf(
            ChatMessage(Role.USER, "First question", id = "u1"),
            ChatMessage(Role.ASSISTANT, "First answer", id = "a1"),
            ChatMessage(Role.TOOL, "TOOL SECRET", toolCallId = "c1"),
            ChatMessage(Role.USER, "Second question about Elden Ring", id = "u2"),
            ChatMessage(Role.ASSISTANT, "Second answer", id = "a2"),
        )
        val tool = ReadChatSnippetTool(
            load = { messages },
            session = { SessionEntity(id = "s1", title = "Test chat", createdAt = 0, updatedAt = 0) },
        )
        val args = buildJsonObject {
            put("session_id", "s1")
            put("message_id", "u2")
            put("around", 1)
        }
        val result = tool.execute(args, context())
        assertTrue(result.output.contains("First answer"))
        assertTrue(result.output.contains("Second question"))
        assertTrue(result.output.contains("Second answer"))
        assertFalse(result.output.contains("TOOL SECRET"))
    }

    @Test fun readSnippetWithoutMessageIdReturnsTheLatestVisibleMessages() = runBlocking {
        val messages = listOf(
            ChatMessage(Role.USER, "First question", id = "u1"),
            ChatMessage(Role.ASSISTANT, "First answer", id = "a1"),
            ChatMessage(Role.USER, "Second question", id = "u2"),
            ChatMessage(Role.ASSISTANT, "Second answer", id = "a2"),
        )
        val tool = ReadChatSnippetTool(
            load = { messages },
            session = { SessionEntity(id = "s1", title = "Test chat", createdAt = 0, updatedAt = 0) },
        )
        val result = tool.execute(buildJsonObject { put("session_id", "s1"); put("around", 1) }, context())
        assertTrue(result.output.contains("Second answer"))
        assertFalse(result.output.contains("First question"))
        assertFalse(result.output.contains("TOOL SECRET"))
    }
}
