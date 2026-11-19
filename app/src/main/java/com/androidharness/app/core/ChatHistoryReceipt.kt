package com.androidharness.app.core

import com.androidharness.app.data.ConversationKind
import kotlinx.serialization.Serializable

/** App-generated navigation targets, stored with the tool result rather than model-written links. */
@Serializable
data class ChatHistoryMatch(
    val sessionId: String,
    val messageId: String? = null,
    val title: String,
    val excerpt: String = "",
    val kind: ConversationKind = ConversationKind.CODE,
    val updatedAt: Long,
)

@Serializable
data class ChatHistoryReceipt(val matches: List<ChatHistoryMatch>)

object ChatHistoryPresentation {
    val toolNames = setOf("search_chats", "recent_chats", "read_chat_snippet")

    /** Every conversation the recall tools returned in [messages], one per chat, keeping the exact message when any hit had it. */
    fun found(messages: List<ChatMessage>): List<ChatHistoryMatch> {
        val tools = messages.filter { it.role == Role.ASSISTANT }.flatMap { it.toolCalls }
            .filter { it.name in toolNames }.map { it.id }.toSet()
        val bySession = linkedMapOf<String, ChatHistoryMatch>()
        messages.filter { it.role == Role.TOOL && !it.isError && it.toolCallId in tools }.forEach { message ->
            message.generation.chatHistory?.matches.orEmpty().forEach { hit ->
                if (hit.sessionId.isNotBlank() && (hit.sessionId !in bySession || hit.messageId != null)) bySession[hit.sessionId] = hit
            }
        }
        return bySession.values.toList()
    }

    /**
     * Cards drawn under an answer that placed none itself: the chats the agent
     * settled on. An answer with a chats block chose its own, so nothing is added.
     */
    fun fallback(turn: List<ChatMessage>, answer: String): List<ChatHistoryMatch> =
        if (com.androidharness.app.core.response.RichResponse.chatIds(answer).isNotEmpty()) emptyList() else matches(turn)

    /**
     * The past conversations worth showing for one turn, best first. Every lookup
     * records its hits, but a recent_chats listing is the agent browsing and a
     * search returns candidates; only what it settled on belongs on screen:
     * the chats it opened with read_chat_snippet, else its search hits, and the
     * recent list only when it did nothing else.
     */
    fun matches(messages: List<ChatMessage>): List<ChatHistoryMatch> {
        val tools = messages.filter { it.role == Role.ASSISTANT }.flatMap { it.toolCalls }
            .filter { it.name in toolNames }.associate { it.id to it.name }
        val byTool = linkedMapOf<String, LinkedHashMap<String, ChatHistoryMatch>>()
        messages.filter { it.role == Role.TOOL && !it.isError && it.toolCallId in tools }.forEach { message ->
            val found = byTool.getOrPut(tools.getValue(message.toolCallId!!)) { linkedMapOf() }
            message.generation.chatHistory?.matches.orEmpty().forEach { hit ->
                if (hit.sessionId.isNotBlank() && (hit.sessionId !in found || hit.messageId != null)) found[hit.sessionId] = hit
            }
        }
        val chosen = listOf("read_chat_snippet", "search_chats", "recent_chats").firstNotNullOfOrNull { name ->
            byTool[name]?.takeIf { it.isNotEmpty() }
        } ?: return emptyList()
        // A search may have pinned the exact message for a chat that was then opened or listed without one.
        val exact = byTool.values.flatMap { it.values }.filter { it.messageId != null }.associateBy { it.sessionId }
        return chosen.values.map { hit -> if (hit.messageId == null) exact[hit.sessionId] ?: hit else hit }
    }
}
