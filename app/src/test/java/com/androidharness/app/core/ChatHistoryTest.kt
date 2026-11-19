package com.androidharness.app.core

import com.androidharness.app.data.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChatHistoryTest {
    private val chat = ConversationConfig(ConversationKind.CHAT)
    private val code = ConversationConfig(ConversationKind.CODE)
    private val hit = ChatHistoryMatch("saved", "message", "Earlier work", "A useful answer", ConversationKind.CHAT, 123)

    @Test fun defaultsAndOldSettingsAllowBothDirections() {
        val old = Json.decodeFromString<AppSettings>("{}")
        assertTrue(ChatHistoryPolicy.canRead(chat, code, old))
        assertTrue(ChatHistoryPolicy.canRead(code, chat, old))
    }

    @Test fun crossModeSwitchesAreIndependentAndKeepSameModeHistory() {
        val settings = AppSettings(chatSearchCodeChats = false)
        assertFalse(ChatHistoryPolicy.canRead(chat, code, settings))
        assertTrue(ChatHistoryPolicy.canRead(code, chat, settings))
        assertTrue(ChatHistoryPolicy.canRead(chat, chat, settings))
        val both = settings.copy(codeSearchChatChats = false)
        assertFalse(ChatHistoryPolicy.canRead(code, chat, both))
        assertTrue(ChatHistoryPolicy.canRead(code, code, both))
        assertEquals(both, Json.decodeFromString<AppSettings>(Json.encodeToString(AppSettings.serializer(), both)))
    }

    @Test fun personalizationAndPrivateAgentsStillRestrictSearch() {
        assertFalse(ChatHistoryPolicy.canRead(chat.copy(personalizationEnabled = false), code, AppSettings()))
        val agent = chat.copy(agent = ChatAgentProfile(id = "agent", name = "Private"))
        assertFalse(ChatHistoryPolicy.canRead(agent, chat, AppSettings()))
        assertFalse(ChatHistoryPolicy.canRead(code, agent, AppSettings()))
        assertTrue(ChatHistoryPolicy.canRead(agent, agent, AppSettings()))
        val shared = agent.copy(agent = agent.agent!!.copy(sharedHistory = true))
        assertTrue(ChatHistoryPolicy.canRead(shared, code, AppSettings()))
        assertFalse(ChatHistoryPolicy.canRead(shared, code, AppSettings(chatSearchCodeChats = false)))
    }

    @Test fun cardsSurvivePersistenceAndRequireSuccessfulHistoryToolResults() {
        val receipt = GenerationMetadata(chatHistory = ChatHistoryReceipt(listOf(hit)))
        val saved = GenerationMetadata.decode(GenerationMetadata.encode(receipt))
        assertEquals(receipt, saved)
        val call = ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("find", "search_chats", "{}")))
        val result = ChatMessage(Role.TOOL, toolCallId = "find", generation = saved)
        assertEquals(listOf(hit), ChatHistoryPresentation.matches(listOf(call, result)))
        assertTrue(ChatHistoryPresentation.matches(listOf(result)).isEmpty())
        assertTrue(ChatHistoryPresentation.matches(listOf(call, result.copy(isError = true))).isEmpty())
        assertTrue(ChatHistoryPresentation.matches(listOf(call, result.copy(role = Role.ASSISTANT))).isEmpty())
        val wrong = call.copy(toolCalls = listOf(ToolCallData("find", "web_search", "{}")))
        assertTrue(ChatHistoryPresentation.matches(listOf(wrong, result)).isEmpty())
    }

    @Test fun repeatedMatchesKeepAnExactMessageInsteadOfARecentChatLink() {
        val calls = ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("find", "search_chats", "{}"), ToolCallData("recent", "recent_chats", "{}")))
        val found = ChatMessage(Role.TOOL, toolCallId = "find", generation = GenerationMetadata(chatHistory = ChatHistoryReceipt(listOf(hit))))
        val recent = ChatMessage(Role.TOOL, toolCallId = "recent", generation = GenerationMetadata(chatHistory = ChatHistoryReceipt(listOf(hit.copy(messageId = null)))))
        assertEquals(listOf(hit), ChatHistoryPresentation.matches(listOf(calls, found, recent)))
    }

    @Test fun onlyTheChatsTheAgentSettledOnAreShown() {
        fun result(id: String, vararg hits: ChatHistoryMatch) =
            ChatMessage(Role.TOOL, toolCallId = id, generation = GenerationMetadata(chatHistory = ChatHistoryReceipt(hits.toList())))
        val browsed = (1..12).map { hit.copy(sessionId = "recent-$it", messageId = null) }
        val searched = (1..8).map { hit.copy(sessionId = "found-$it") }
        val calls = ChatMessage(Role.ASSISTANT, toolCalls = listOf(
            ToolCallData("recent", "recent_chats", "{}"), ToolCallData("find", "search_chats", "{}"), ToolCallData("read", "read_chat_snippet", "{}")))
        val recent = result("recent", *browsed.toTypedArray())
        val found = result("find", *searched.toTypedArray())
        val opened = result("read", hit.copy(sessionId = "found-3", messageId = null))

        // Opening a chat means that is the one it used; the search hit supplies the exact message.
        assertEquals(listOf(searched[2]), ChatHistoryPresentation.matches(listOf(calls, recent, found, opened)))
        // Without an opened chat, search hits win over the browsing list.
        assertEquals(searched, ChatHistoryPresentation.matches(listOf(calls, recent, found)))
        // A recent list alone is still shown.
        assertEquals(browsed, ChatHistoryPresentation.matches(listOf(calls, recent)))
    }

    @Test fun theAnswerPlacesChatCardsAndOnlyFoundChatsResolve() {
        val calls = ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("find", "search_chats", "{}")))
        val found = ChatMessage(Role.TOOL, toolCallId = "find", generation = GenerationMetadata(chatHistory = ChatHistoryReceipt(listOf(
            hit.copy(sessionId = "a"), hit.copy(sessionId = "b")))))
        val turn = listOf(calls, found)
        val placed = "Here it is.\n\n```harness-ui\n{\"type\":\"chats\",\"title\":\"Earlier\",\"ids\":[\"b\",\"made-up\",\"b\"]}\n```\n\nMore text."
        val node = com.androidharness.app.core.response.RichResponse.parse(placed)
            .filterIsInstance<com.androidharness.app.core.response.RichResponse.Part.Content>().single().node
        assertEquals(com.androidharness.app.core.response.RichResponse.Node.Chats("Earlier", listOf("b", "made-up")), node)
        // An answer that placed its own cards gets nothing extra underneath.
        assertTrue(ChatHistoryPresentation.fallback(turn, placed).isEmpty())
        // One that placed none falls back to the chats the agent settled on.
        assertEquals(listOf("a", "b"), ChatHistoryPresentation.fallback(turn, "No block here.").map { it.sessionId })
        // Only IDs the tools returned are known; the invented one has nothing to draw.
        val known = ChatHistoryPresentation.found(turn).associateBy { it.sessionId }
        assertEquals(listOf("b"), listOf("b", "made-up").mapNotNull { known[it]?.sessionId })
    }
}
