package com.androidharness.app.tools

import com.androidharness.app.core.ChatHistoryMatch
import com.androidharness.app.core.ChatHistoryReceipt
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.response.RichResponse
import com.androidharness.app.core.response.UserMessageContent
import com.androidharness.app.data.MessageHit
import com.androidharness.app.data.db.ChatSearch
import com.androidharness.app.data.db.SessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_SNIPPET_CHARS = 260

/**
 * Search the user's other chats. Results stay compact: one hit per chat,
 * a short excerpt, and the IDs needed to read a small window.
 */
class SearchChatsTool(
    private val search: suspend (rawQuery: String, fuzzy: Boolean, limit: Int, offset: Int) -> List<MessageHit>,
) : Tool {
    var canAccess: suspend (String?, String) -> Boolean = { _, _ -> true }
    override val name = "search_chats"
    override val description =
        "Search the user's other AndroidHarness chats for relevant messages. Use it when the user " +
            "refers to an earlier conversation, a previous decision, or something discussed before. " +
            "Searches message text and chat titles across projects. Results appear as tappable chat cards. Returns compact excerpts with chat titles and dates, not full chats. Use read_chat_snippet " +
            "only when an excerpt is not enough."
    override val parametersSchema = Schema.obj(
        mapOf(
            "query" to Schema.string("Distinctive keywords, names, or a short phrase. At least two characters."),
            "limit" to Schema.integer("Number of chats to return (default 5, max 8)."),
            "fuzzy" to Schema.boolean("Use partial-word matching for code fragments or uncertain spelling (default false)."),
        ),
        required = listOf("query"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.length < 2) throw ToolFailure("Provide at least two characters to search for.")
        val limit = (args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 5).coerceIn(1, 8)
        val fuzzy = args["fuzzy"]?.jsonPrimitive?.booleanOrNull == true
        val tokens = ChatSearch.tokens(query)
        val needles = if (fuzzy) listOf(query) else tokens
        fun score(hit: MessageHit): Int =
            (if (tokens.any { hit.sessionTitle.contains(it, ignoreCase = true) }) 6 else 0) +
                (if (hit.text.contains(query, ignoreCase = true)) 3 else 0) +
                (if (tokens.count { hit.text.contains(it, ignoreCase = true) } >= 2) 2 else 0)
        // Page past current/private/disallowed chats. Retain only the best hit per conversation.
        val hits = linkedMapOf<String, MessageHit>()
        val access = mutableMapOf<String, Boolean>()
        var offset = 0
        while (true) {
            val page = search(query, fuzzy, 64, offset)
            for (hit in page) {
                if (hit.sessionId == ctx.sessionId || !access.getOrPut(hit.sessionId) { canAccess(ctx.sessionId, hit.sessionId) }) continue
                val previous = hits[hit.sessionId]
                if (previous == null || score(hit) > score(previous)) hits[hit.sessionId] = hit
            }
            if (page.size < 64 || hits.size >= 24) break
            offset += page.size
        }
        if (hits.isEmpty()) {
            return ToolResult(true, "No matching messages in other chats. Try fewer or different keywords.")
        }
        val ranked = hits.values.sortedWith(compareByDescending<MessageHit>(::score).thenByDescending { it.createdAt }).take(limit)
        val out = StringBuilder("[search_chats results]\n")
        ranked.forEachIndexed { index, hit ->
            val title = hit.sessionTitle.take(100).ifBlank { "Untitled chat" }
            out.append(index + 1).append(". \"").append(title).append("\" (")
                .append(day(hit.createdAt)).append(")\n")
                .append("   ").append(ChatSearch.snippet(hit.text, needles, MAX_SNIPPET_CHARS).text).append('\n')
                .append("   session: ").append(hit.sessionId).append(" message: ").append(hit.messageId).append('\n')
        }
        if (hits.size > ranked.size) {
            out.append("[More chats matched. Refine the query if these are not the right ones.]\n")
        }
        out.append("[Excerpts only. Use read_chat_snippet for a small window around one hit; never request a whole chat.]")
        return ToolResult(true, out.toString(), chatHistory = ChatHistoryReceipt(ranked.map { hit ->
            ChatHistoryMatch(hit.sessionId, hit.messageId, hit.sessionTitle.ifBlank { "Untitled chat" }.take(100),
                ChatSearch.snippet(hit.text, needles, MAX_SNIPPET_CHARS).text, hit.kind, hit.createdAt)
        }))
    }
}

/** Browsing surface for vague references such as "that chat from yesterday". */
class RecentChatsTool(
    private val sessions: Flow<List<SessionEntity>>,
) : Tool {
    var canAccess: suspend (String?, String) -> Boolean = { _, _ -> true }
    override val name = "recent_chats"
    override val description =
        "List the user's most recent AndroidHarness chats and Code sessions with titles, dates, and session IDs. " +
            "Use it when the user asks what they were working on or refers to a chat without enough keywords. " +
            "Follow with read_chat_snippet for details."
    override val parametersSchema = Schema.obj(
        mapOf("limit" to Schema.integer("Number of sessions (default 8, max 12).")),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val limit = (args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 8).coerceIn(1, 12)
        val list = sessions.first()
            .filter { it.id != ctx.sessionId && it.title.isNotBlank() && canAccess(ctx.sessionId, it.id) }
            .sortedByDescending { it.updatedAt }
            .take(limit)
        if (list.isEmpty()) return ToolResult(true, "No other chats yet.")
        val out = StringBuilder("[recent_chats]\n")
        list.forEach { session ->
            val kind = if (com.androidharness.app.data.ConversationConfig.decode(session.conversationJson).isChat) "Chat" else "Code"
            out.append("- ").append(day(session.updatedAt)).append(" · ").append(kind)
                .append(" · \"").append(session.title.take(100)).append("\"")
                .append(" · session: ").append(session.id).append('\n')
        }
        out.append("[Use read_chat_snippet with a session ID and no message ID to read the latest messages.]")
        return ToolResult(true, out.toString(), chatHistory = ChatHistoryReceipt(list.map { session ->
            ChatHistoryMatch(session.id, title = session.title.take(100),
                kind = ConversationConfig.decode(session.conversationJson).kind, updatedAt = session.updatedAt)
        }))
    }
}

/** Small window around a hit, or the latest messages when message_id is omitted. */
class ReadChatSnippetTool(
    private val load: suspend (String) -> List<ChatMessage>,
    private val session: suspend (String) -> SessionEntity?,
) : Tool {
    var canAccess: suspend (String?, String) -> Boolean = { _, _ -> true }
    override val name = "read_chat_snippet"
    override val description =
        "Read a small window of visible messages around a chat search hit, or the latest messages when " +
            "message_id is omitted. Use it only when search_chats or recent_chats is not enough. Never request a whole chat."
    override val parametersSchema = Schema.obj(
        mapOf(
            "session_id" to Schema.string("Session ID from search_chats or recent_chats."),
            "message_id" to Schema.string("Optional message ID from search_chats."),
            "around" to Schema.integer("Messages before and after the hit (0..3, default 1)."),
            "max_chars" to Schema.integer("Maximum characters to return (500..3000, default 1500)."),
        ),
        required = listOf("session_id"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val sid = args["session_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (sid.isBlank()) throw ToolFailure("Missing required argument: session_id")
        if (!canAccess(ctx.sessionId, sid)) throw ToolFailure("That conversation is outside the current history access settings.")
        val meta = session(sid) ?: throw ToolFailure("That chat no longer exists.")
        val visible = load(sid).mapNotNull { message ->
            if (message.role != Role.USER && message.role != Role.ASSISTANT) return@mapNotNull null
            if (message.role == Role.ASSISTANT && message.toolCallId != null) return@mapNotNull null
            val text = if (message.role == Role.USER) UserMessageContent.visibleText(message.text)
            else RichResponse.plainText(message.text)
            val clean = text.replace(Regex("\\s+"), " ").trim()
            if (clean.isEmpty()) null else message to clean
        }
        if (visible.isEmpty()) return ToolResult(true, "That chat has no readable messages.")
        val messageId = args["message_id"]?.jsonPrimitive?.contentOrNull?.trim()
        val around = (args["around"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1).coerceIn(0, 3)
        val maxChars = (args["max_chars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1500).coerceIn(500, 3000)
        val target = if (messageId.isNullOrBlank()) visible.lastIndex
        else visible.indexOfFirst { it.first.id == messageId }
        val window = if (target >= 0) {
            visible.subList(
                (target - around).coerceAtLeast(0),
                (target + around).coerceAtMost(visible.lastIndex) + 1,
            )
        } else {
            visible.takeLast(around * 2 + 1)
        }
        val title = meta.title.ifBlank { "Chat" }
        val out = StringBuilder("Chat: \"").append(title.take(100)).append("\" (")
            .append(day(meta.updatedAt)).append(")\n")
        if (target < 0 && !messageId.isNullOrBlank()) {
            out.append("[The exact hit was not found; showing the latest messages.]\n")
        }
        for ((message, text) in window) {
            val remaining = maxChars - out.length
            if (remaining <= 20) break
            val label = if (message.role == Role.USER) "user" else "assistant"
            val body = if (text.length <= remaining) text else text.take(remaining - 1) + "…"
            out.append(label).append(": ").append(body).append('\n')
            if (body.length < text.length) break
        }
        val anchor = visible.getOrNull(target) ?: visible.last()
        return ToolResult(true, out.toString().trimEnd(), chatHistory = ChatHistoryReceipt(listOf(
            ChatHistoryMatch(sid, anchor.first.id, title.take(100), anchor.second.take(MAX_SNIPPET_CHARS),
                ConversationConfig.decode(meta.conversationJson).kind, anchor.first.createdAt)
        )))
    }
}

private fun day(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .toString()
