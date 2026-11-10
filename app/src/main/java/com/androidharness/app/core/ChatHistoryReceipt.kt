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

    fun matches(messages: List<ChatMessage>): List<ChatHistoryMatch> {
        val calls = messages.filter { it.role == Role.ASSISTANT }.flatMap { it.toolCalls }
            .filter { it.name in toolNames }.map { it.id }.toSet()
        val found = linkedMapOf<String, ChatHistoryMatch>()
        messages.filter { it.role == Role.TOOL && !it.isError && it.toolCallId in calls }.forEach { message ->
            message.generation.chatHistory?.matches.orEmpty().forEach { hit ->
                if (hit.sessionId.isNotBlank() && (hit.sessionId !in found || hit.messageId != null)) found[hit.sessionId] = hit
            }
        }
        return found.values.toList()
    }
}
