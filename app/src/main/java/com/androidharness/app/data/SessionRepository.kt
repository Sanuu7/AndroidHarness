package com.androidharness.app.data

import androidx.room.withTransaction
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.ChatSearch
import com.androidharness.app.data.db.MessageEntity
import com.androidharness.app.data.db.MessagePosition
import com.androidharness.app.data.db.SavedAnswerEntity
import com.androidharness.app.data.db.SavedAnswerRow
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.data.db.UsageEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import java.util.UUID

/** One message search hit from the drawer, grouped under its session. */
data class MessageHit(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String,
    val text: String,
    val createdAt: Long,
)

internal const val MAX_PERSISTED_FIELD_CHARS = 250_000

/** Greeting the agent posts as the first message when Chat continues in Code. */
internal const val CONTINUED_IN_CODE_PREFIX = "Continuing in Code."
private const val LEGACY_CONTINUED_IN_CODE_PREFIX = "Continued from Chat."

internal fun isCodeHandoffGreeting(text: String): Boolean =
    text.startsWith(CONTINUED_IN_CODE_PREFIX) || text.startsWith(LEGACY_CONTINUED_IN_CODE_PREFIX)

internal fun clampForStorage(s: String, max: Int = MAX_PERSISTED_FIELD_CHARS): String =
    if (s.length <= max) s
    else s.substring(0, max) + "\n[truncated for storage safety]"

class SessionRepository(
    private val db: AppDatabase,
    private val images: ImageStore? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val toolCallList = ListSerializer(ToolCallData.serializer())
    private val imageList = ListSerializer(ImageRef.serializer())

    /**
     * Decoded [ChatMessage]s are cached by row id and tool JSON, so adding measured
     * file counts invalidates only the affected row. Without this, each insert re-decodes the
     * tool-call JSON of the ENTIRE session (O(n) per commit, O(n²) per run).
     * Bounded across open chats so browsing old history cannot retain it all.
     */
    private val messageCache = object : LinkedHashMap<String, Pair<String, ChatMessage>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, ChatMessage>>?): Boolean = size > 512
    }

    val sessions: Flow<List<SessionEntity>> = db.dao().sessionsFlow()

    fun messagesFlow(sessionId: String): Flow<List<ChatMessage>> =
        db.dao().messagesFlow(sessionId).map { list ->
            val decoded = list.map { it.toChatMessageCached() }
            synchronized(messageCache) { if (messageCache.size > list.size) {
                val liveIds = HashSet<String>(list.size * 2)
                list.forEach { liveIds += it.id }
                messageCache.keys.retainAll(liveIds)
            } }
            decoded
        }

    data class MessageWindow(val messages: List<ChatMessage>, val hasOlder: Boolean)

    /** UI pages keep their lower boundary while new messages arrive. Model history stays complete. */
    suspend fun messageWindowStart(sessionId: String, before: MessagePosition? = null): MessagePosition {
        val dao = db.dao()
        val candidate = if (before == null) dao.recentMessagePosition(sessionId, 79)
            else dao.olderMessagePosition(sessionId, before.createdAt, before.rowId, 79)
        return completeTurnStart(sessionId, candidate)
    }

    suspend fun messagePosition(sessionId: String, messageId: String): MessagePosition? =
        db.dao().messagePosition(sessionId, messageId)?.let { completeTurnStart(sessionId, it) }

    private suspend fun completeTurnStart(sessionId: String, position: MessagePosition?): MessagePosition =
        position?.turnId?.let { db.dao().firstMessageInTurn(sessionId, it) }
            ?: position ?: MessagePosition(Long.MIN_VALUE, Long.MIN_VALUE, null)

    fun messageWindowFlow(sessionId: String, start: MessagePosition): Flow<MessageWindow> = combine(
        db.dao().messagesFromPositionFlow(sessionId, start.createdAt, start.rowId),
        db.dao().hasOlderMessagesFlow(sessionId, start.createdAt, start.rowId),
    ) { rows, hasOlder -> MessageWindow(rows.map { it.toChatMessageCached() }, hasOlder) }
        .flowOn(Dispatchers.IO)

    /** Full message list (for UI). */
    suspend fun messages(sessionId: String): List<ChatMessage> =
        db.dao().messages(sessionId).map { it.toChatMessageCached() }

    /** Ids of this chat's saved answers, so each answer's bookmark reflects the stored state. */
    fun savedAnswerIds(sessionId: String): Flow<Set<String>> =
        db.dao().savedAnswerIds(sessionId).map { it.toSet() }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /** Every saved answer, newest saved first, with a short excerpt instead of the full text. */
    fun savedAnswers(): Flow<List<SavedAnswerRow>> = db.dao().savedAnswers().flowOn(Dispatchers.IO)

    suspend fun savedAnswerText(messageId: String): String? = db.dao().savedAnswer(messageId)?.text

    /** Keeps a copy of the answer's text, so the bookmark survives regenerating or rewinding the chat. */
    suspend fun saveAnswer(sessionId: String, message: ChatMessage) {
        val id = message.id ?: return
        db.dao().saveAnswer(SavedAnswerEntity(id, sessionId, message.text.take(MAX_PERSISTED_FIELD_CHARS), message.createdAt, System.currentTimeMillis()))
    }

    suspend fun unsaveAnswer(messageId: String) = db.dao().unsaveAnswer(messageId)

    /** Removes a saved answer and hands back its stored copy, so an Undo can put it back exactly. */
    suspend fun removeSavedAnswer(messageId: String): SavedAnswerEntity? = db.withTransaction {
        db.dao().savedAnswer(messageId).also { db.dao().unsaveAnswer(messageId) }
    }

    suspend fun restoreSavedAnswer(answer: SavedAnswerEntity) = db.dao().saveAnswer(answer)

    suspend fun toolOutput(sessionId: String, toolCallId: String): String? = db.dao().toolOutput(sessionId, toolCallId)

    /** Plan awaiting approval; persisted so the card survives process death. */
    suspend fun setPendingPlan(sessionId: String, plan: String?) =
        db.dao().setPendingPlan(sessionId, plan)

    /**
     * Model-facing history must exclude subagent inner turns: inner assistant
     * rows are marked with toolCallId = their parent task call id, and their
     * tool results carry the inner call ids. Without this the main model would
     * receive subagent chatter as if it were its own history.
     */
    fun List<ChatMessage>.withoutSubagentTurns(): List<ChatMessage> {
        val innerCallIds = HashSet<String>()
        for (m in this) {
            if (m.role == Role.ASSISTANT && m.toolCallId != null) {
                m.toolCalls.forEach { innerCallIds += it.id }
            }
        }
        if (innerCallIds.isEmpty() && none { it.role == Role.ASSISTANT && it.toolCallId != null }) {
            return this
        }
        return filter { m ->
            when {
                m.role == Role.ASSISTANT && m.toolCallId != null -> false
                m.role == Role.TOOL && m.toolCallId in innerCallIds -> false
                else -> true
            }
        }
    }

    /**
     * History for the model: [compaction summary (may be empty)] plus messages
     * after the compaction point.
     */
    suspend fun historyFor(sessionId: String): Pair<String, List<ChatMessage>> {
        val session = db.dao().session(sessionId)
        val since = session?.compactionBefore ?: 0
        val rows = if (since > 0) db.dao().messagesSince(sessionId, since) else db.dao().messages(sessionId)
        val summary = session?.compactionSummary.orEmpty()
        val messages = rows.map { it.toChatMessageCached() }
        if (summary.isBlank()) return summary to messages
        return summary to (listOf(com.androidharness.app.agent.ContextHygiene.summaryMessage(summary)) + messages.filterNot {
            it.role == Role.SYSTEM && (it.text.startsWith(com.androidharness.app.agent.AgentEngine.COMPACTION_PREFIX) ||
                it.text.startsWith(com.androidharness.app.agent.ContextHygiene.COMPACTION_NOTICE_PREFIX) || it.isError)
        })
    }

    data class CompactionSnapshot(val messageIds: List<String>, val lastMessageAt: Long,
        val summary: String, val before: Long, val history: List<ChatMessage>)

    suspend fun compactionSnapshot(sessionId: String): CompactionSnapshot = db.withTransaction {
        val session = requireNotNull(db.dao().session(sessionId)) { "This chat is no longer available." }
        CompactionSnapshot(db.dao().messageIds(sessionId), db.dao().lastMessageAt(sessionId) ?: 0, session.compactionSummary, session.compactionBefore,
            with(this) { historyFor(sessionId).second.withoutSubagentTurns() })
    }

    /** Commit only a complete summary of the same snapshot; never delete original messages. */
    suspend fun commitCompaction(sessionId: String, summary: String, snapshot: CompactionSnapshot,
        retainedMessageCount: Int = 0): Long = db.withTransaction {
        require(summary.isNotBlank())
        val current = requireNotNull(db.dao().session(sessionId)) { "This chat is no longer available." }
        check(current.compactionSummary == snapshot.summary && current.compactionBefore == snapshot.before &&
            db.dao().messageIds(sessionId) == snapshot.messageIds) { "The chat changed while summarizing. History was kept. Retry /compact." }
        val recent = snapshot.history.filter { it.id != null }.takeLast(retainedMessageCount)
        // Equal timestamps conservatively retain extra rows, including whole tool exchanges.
        val before = recent.firstOrNull()?.createdAt ?: minOf(snapshot.lastMessageAt + 1, System.currentTimeMillis())
        db.dao().setCompaction(sessionId, summary, before)
        addMessage(sessionId, com.androidharness.app.agent.ContextHygiene.compactionNotice())
        before
    }

    suspend fun session(id: String): SessionEntity? = db.dao().session(id)

    fun sessionFlow(id: String): Flow<SessionEntity?> = db.dao().sessionFlow(id)

    suspend fun initializeModelSelection(id: String, initial: ChatModelSelection): ChatModelSelection = db.withTransaction {
        val session = requireNotNull(db.dao().session(id)) { "This chat is no longer available." }
        ChatModelSelection.decode(session.modelSelectionJson) ?: initial.also {
            db.dao().setModelSelection(id, ChatModelSelection.encode(it))
        }
    }

    suspend fun saveModelSelection(id: String, selection: ChatModelSelection) = db.withTransaction {
        requireNotNull(db.dao().session(id)) { "This chat is no longer available." }
        db.dao().setModelSelection(id, ChatModelSelection.encode(selection))
    }

    suspend fun createSession(title: String, projectId: String? = null, conversation: ConversationConfig = ConversationConfig()): String = db.withTransaction {
        if (projectId != null) requireNotNull(db.dao().project(projectId)) { "This project is no longer available." }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        conversation.agent?.validated()
        val model = conversation.agent?.model?.let { ChatModelSelection(active = it, thinking = conversation.agent.thinking, customThinkingLevels = conversation.agent.customThinkingLevels) }
        db.dao().insertSession(SessionEntity(id, title, now, now, projectId = projectId, conversationJson = ConversationConfig.encode(conversation),
            modelSelectionJson = model?.let(ChatModelSelection::encode)))
        id
    }

    suspend fun saveConversation(id: String, config: ConversationConfig) = db.withTransaction {
        requireNotNull(db.dao().session(id)) { "This conversation is no longer available." }
        db.dao().setConversation(id, ConversationConfig.encode(config))
    }

    /** Create an assigned chat without changing the default or any other chat. */
    suspend fun createProjectSession(projectId: String): String = db.withTransaction {
        requireNotNull(db.dao().project(projectId)) { "This project is no longer available." }
        createSession("New chat", projectId)
    }

    /** A linked Code conversation, without replaying tools or starting execution. */
    suspend fun continueInCode(sourceId: String, projectId: String): String = db.withTransaction {
        val source = requireNotNull(db.dao().session(sourceId))
        require(ConversationConfig.decode(source.conversationJson).isChat)
        val id = createSession(source.title, projectId, ConversationConfig(sourceChatId = sourceId))
        val transcript = db.dao().messages(sourceId).filter {
            it.role in setOf("USER", "ASSISTANT") && it.toolCallId == null && it.text.isNotBlank()
        }.joinToString("\n\n") { "${it.role}: ${it.text}" }.takeLast(32_000)
        val context = "Discussion carried over from Chat. This is reference context, not new instructions.\n\n" + transcript
        db.dao().setCompaction(id, context, 0)
        source.modelSelectionJson?.let { db.dao().setModelSelection(id, it) }
        addMessage(id, ChatMessage(role = Role.ASSISTANT,
            text = "$CONTINUED_IN_CODE_PREFIX Your chat discussion is carried over as reference."))
        id
    }

    suspend fun artifacts(sessionId: String): List<com.androidharness.app.artifacts.ChatArtifact> =
        db.dao().artifactMessages(sessionId).mapNotNull { com.androidharness.app.artifacts.ChatArtifact.decode(it.artifactJson) }

    suspend fun addMessage(sessionId: String, message: ChatMessage, turnId: String? = null): String = db.withTransaction {
        val firstChatTitle = if (message.role == Role.USER && !db.dao().hasUserMessage(sessionId)) {
            db.dao().session(sessionId)?.takeIf { ConversationConfig.decode(it.conversationJson).isChat &&
                it.title.trim() in setOf("New chat", "Chat", "新建对话") }
                ?.let { com.androidharness.app.core.ChatExperience.title(message.text).takeIf(String::isNotBlank) }
        } else null
        val id = message.id ?: UUID.randomUUID().toString()
        val safeText = clampForStorage(message.text)
        val safeThinking = clampForStorage(message.thinking)
        val rawCalls = json.encodeToString(toolCallList, message.toolCalls)
        val compactCalls = if (rawCalls.length <= MAX_PERSISTED_FIELD_CHARS) rawCalls else {
            json.encodeToString(toolCallList, message.toolCalls.map { call ->
                if (call.name != "create_artifact") call else runCatching {
                    val args = json.parseToJsonElement(call.argumentsJson).jsonObject
                    call.copy(argumentsJson = kotlinx.serialization.json.JsonObject(args +
                        ("html" to kotlinx.serialization.json.JsonPrimitive("HTML omitted from replay; use read_artifact for saved concept source."))).toString())
                }.getOrDefault(call)
            })
        }
        val safeCalls = if (compactCalls.length <= MAX_PERSISTED_FIELD_CHARS) compactCalls else "[]"
        db.dao().insertMessage(
            MessageEntity(
                id = id,
                sessionId = sessionId,
                role = message.role.name,
                text = safeText,
                toolCallsJson = safeCalls,
                toolCallId = message.toolCallId,
                toolName = message.toolName,
                isError = message.isError,
                thinking = safeThinking,
                thinkingMs = message.thinkingMs,
                outputTokens = message.outputTokens,
                generationMs = message.generationMs,
                firstTokenMs = message.firstTokenMs,
                streamMs = message.streamMs,
                generationJson = com.androidharness.app.core.GenerationMetadata.encode(message.generation),
                imagesJson = json.encodeToString(imageList, message.images),
                artifactJson = message.artifact?.let(com.androidharness.app.artifacts.ChatArtifact::encode),
                turnId = turnId,
                createdAt = System.currentTimeMillis(),
            )
        )
        db.dao().touchSession(sessionId, System.currentTimeMillis())
        if (firstChatTitle != null) db.dao().updateSession(sessionId, firstChatTitle, System.currentTimeMillis())
        id
    }

    suspend fun renameSession(id: String, title: String) {
        db.dao().updateSession(id, title, System.currentTimeMillis())
    }

    /** Repair placeholders left by drafts created before their first Chat message. */
    suspend fun repairChatTitles() = db.withTransaction {
        db.dao().allSessions().filter { ConversationConfig.decode(it.conversationJson).isChat &&
            it.title.trim() in setOf("New chat", "Chat", "新建对话") }.forEach { session ->
            val title = db.dao().firstUserMessage(session.id)?.text?.let(com.androidharness.app.core.ChatExperience::title)
            if (!title.isNullOrBlank()) db.dao().updateSession(session.id, title, session.updatedAt)
        }
    }

    /**
     * Forks a chat from an assistant message into a fresh session.
     * The new session title is "Fork of <parent.title>".
     *
     * In the new session transcript:
     * - The prior history (before this fork turn) is stored as compaction context
     *   (with a hidden compaction summary system message) so the agent retains full context.
     * - Only the prompt that triggered this turn and the forked assistant message are
     *   inserted as visible messages.
     * - Cumulative token counts and usage_events up to this turn are copied so context
     *   window usage, hit ratio, and model stats in ContextDialog remain accurate.
     */
    suspend fun forkSession(parentSessionId: String, targetAssistantMessageId: String): String =
        db.withTransaction {
            val parent = db.dao().session(parentSessionId)
                ?: error("Parent session not found: $parentSessionId")
            val allMessages = db.dao().messages(parentSessionId)
            val targetIdx = allMessages.indexOfFirst { it.id == targetAssistantMessageId }
            if (targetIdx < 0) error("Target message not found in session: $targetAssistantMessageId")

            val targetAssistant = allMessages[targetIdx]
            val priorMessages = allMessages.subList(0, targetIdx + 1)

            // Find the user prompt corresponding to this turn:
            // either matching turnId, or the nearest preceding user message.
            val turnId = targetAssistant.turnId
            val userMsg = (if (turnId != null) {
                priorMessages.lastOrNull { it.role == Role.USER.name && it.turnId == turnId }
            } else null) ?: priorMessages.lastOrNull { it.role == Role.USER.name }

            val earlierMessages = if (userMsg != null) {
                val userIdx = priorMessages.indexOfFirst { it.id == userMsg.id }
                if (userIdx > 0) priorMessages.subList(0, userIdx) else emptyList()
            } else {
                if (targetIdx > 0) priorMessages.subList(0, targetIdx) else emptyList()
            }

            // Build a structured context summary of the earlier conversation if any exists,
            // preserving parent's existing compaction summary.
            val summaryBuilder = StringBuilder()
            if (parent.compactionSummary.isNotBlank()) {
                summaryBuilder.append(parent.compactionSummary.trim()).append("\n\n")
            }
            val earlierChatMessages = earlierMessages.map { it.toChatMessageCached() }
                .filterNot { it.role == Role.SYSTEM && it.text.startsWith(com.androidharness.app.agent.ContextHygiene.COMPACTION_NOTICE_PREFIX) }
            for (m in earlierChatMessages) {
                if (m.role == Role.SYSTEM && m.text.startsWith(com.androidharness.app.agent.AgentEngine.COMPACTION_PREFIX)) {
                    val stripped = m.text.removePrefix(com.androidharness.app.agent.AgentEngine.COMPACTION_PREFIX).trim()
                    if (stripped.isNotBlank()) summaryBuilder.append(stripped).append("\n\n")
                    continue
                }
                when (m.role) {
                    Role.USER -> summaryBuilder.append("User: ").append(m.text).append("\n\n")
                    Role.ASSISTANT -> if (m.text.isNotBlank()) summaryBuilder.append("Assistant: ").append(m.text).append("\n\n")
                    Role.TOOL -> if (m.text.isNotBlank()) {
                        val preview = if (m.text.length > 500) m.text.take(500) + "…" else m.text
                        summaryBuilder.append("Tool (${m.toolName ?: "tool"}): ").append(preview).append("\n\n")
                    }
                    Role.SYSTEM -> {}
                }
            }

            val forkTitle = if (parent.title.startsWith("Fork of ")) {
                parent.title
            } else {
                "Fork of ${parent.title}"
            }

            val newSessionId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val finalSummary = summaryBuilder.toString().trim()

            val newSession = SessionEntity(
                id = newSessionId,
                title = forkTitle,
                createdAt = now,
                updatedAt = now,
                totalInputTokens = parent.totalInputTokens,
                totalOutputTokens = parent.totalOutputTokens,
                totalCachedTokens = parent.totalCachedTokens,
                totalCacheWriteTokens = parent.totalCacheWriteTokens,
                requestCount = parent.requestCount,
                lastInputTokens = parent.lastInputTokens,
                projectId = parent.projectId,
                modelSelectionJson = parent.modelSelectionJson,
                workspaceSelectionJson = parent.workspaceSelectionJson,
                conversationJson = parent.conversationJson,
                compactionSummary = finalSummary,
                compactionBefore = if (finalSummary.isNotBlank()) now else 0L,
            )
            db.dao().insertSession(newSession)

            val newMessages = mutableListOf<MessageEntity>()
            val savedConcepts = priorMessages.filter { it.artifactJson != null && it.id != targetAssistant.id }
            val latestConceptRows = savedConcepts.groupBy {
                com.androidharness.app.artifacts.ChatArtifact.decode(it.artifactJson)?.id ?: it.id
            }.values.map { it.last().id }.toSet()
            savedConcepts.forEach { saved ->
                newMessages.add(saved.copy(id = UUID.randomUUID().toString(), sessionId = newSessionId,
                    role = if (saved.id in latestConceptRows) Role.ASSISTANT.name else Role.SYSTEM.name,
                    text = "Saved concept", toolCallsJson = "[]", toolCallId = null,
                    toolName = null, turnId = null, createdAt = now - 3))
            }
            if (finalSummary.isNotBlank()) {
                val summaryEntity = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = newSessionId,
                    role = Role.SYSTEM.name,
                    text = "${com.androidharness.app.agent.AgentEngine.COMPACTION_PREFIX}\n\n$finalSummary",
                    toolCallsJson = "[]",
                    toolCallId = null,
                    toolName = null,
                    isError = false,
                    thinking = "",
                    thinkingMs = 0,
                    imagesJson = "[]",
                    turnId = null,
                    createdAt = now - 2,
                )
                newMessages.add(summaryEntity)
            }

            val newTurnId = UUID.randomUUID().toString()
            if (userMsg != null) {
                newMessages.add(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = newSessionId,
                        role = userMsg.role,
                        text = userMsg.text,
                        toolCallsJson = userMsg.toolCallsJson,
                        toolCallId = userMsg.toolCallId,
                        toolName = userMsg.toolName,
                        isError = userMsg.isError,
                        thinking = userMsg.thinking,
                        thinkingMs = userMsg.thinkingMs,
                        outputTokens = userMsg.outputTokens,
                        generationMs = userMsg.generationMs,
                        firstTokenMs = userMsg.firstTokenMs,
                        streamMs = userMsg.streamMs,
                        generationJson = userMsg.generationJson,
                        imagesJson = userMsg.imagesJson,
                        artifactJson = userMsg.artifactJson,
                        turnId = newTurnId,
                        createdAt = now - 1,
                    )
                )
            }

            newMessages.add(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = newSessionId,
                    role = targetAssistant.role,
                    text = targetAssistant.text,
                    toolCallsJson = targetAssistant.toolCallsJson,
                    toolCallId = targetAssistant.toolCallId,
                    toolName = targetAssistant.toolName,
                    isError = targetAssistant.isError,
                    thinking = targetAssistant.thinking,
                    thinkingMs = targetAssistant.thinkingMs,
                    outputTokens = targetAssistant.outputTokens,
                    generationMs = targetAssistant.generationMs,
                    firstTokenMs = targetAssistant.firstTokenMs,
                    streamMs = targetAssistant.streamMs,
                    generationJson = targetAssistant.generationJson,
                    imagesJson = targetAssistant.imagesJson,
                    artifactJson = targetAssistant.artifactJson,
                    turnId = newTurnId,
                    createdAt = now,
                )
            )

            for (m in newMessages) {
                db.dao().insertMessage(m)
            }

            // Copy usage events so cost and model breakdown charts in ContextDialog show up
            val usageEvents = db.dao().usageEventsForSessionUpTo(parentSessionId, targetAssistant.createdAt)
            if (usageEvents.isNotEmpty()) {
                val clonedEvents = usageEvents.map { ev ->
                    ev.copy(rowId = 0, sessionId = newSessionId)
                }
                db.dao().insertUsageEvents(clonedEvents)
            }

            newSessionId
        }

    /**
     * Drawer message search. Word mode hits the FTS4 index (every token must
     * appear, prefix-matched); fuzzy mode falls back to a substring LIKE that
     * also finds partial words, code fragments, and punctuation runs. Subagent
     * inner assistant rows stay out: they are not rendered in the main chat.
     */
    suspend fun searchMessages(rawQuery: String, fuzzy: Boolean, limit: Int = 80): List<MessageHit> {
        val q = rawQuery.trim()
        if (q.isEmpty()) return emptyList()
        val rows: List<MessageEntity> = if (fuzzy) {
            db.dao().searchLike(ChatSearch.likePattern(q), limit)
        } else {
            val match = ChatSearch.ftsMatchQuery(q) ?: return emptyList()
            db.dao().searchFts(match, limit)
        }.filterNot { it.role == Role.ASSISTANT.name && it.toolCallId != null }
        if (rows.isEmpty()) return emptyList()
        val matches = db.dao().sessionsByIds(rows.map { it.sessionId }.distinct()).associateBy { it.id }
        return rows.filter { row ->
            !ConversationConfig.decode(matches[row.sessionId]?.conversationJson).isChat || row.role in setOf("USER", "ASSISTANT")
        }.map { row ->
            MessageHit(
                messageId = row.id,
                sessionId = row.sessionId,
                sessionTitle = matches[row.sessionId]?.title.orEmpty(),
                text = if (row.role == Role.ASSISTANT.name) com.androidharness.app.core.response.RichResponse.plainText(row.text) else com.androidharness.app.core.response.UserMessageContent.visibleText(row.text),
                createdAt = row.createdAt,
            )
        }
    }

    /**
     * Find-in-conversation. Oldest hit first so next/previous walk forward
     * through the chat. Word search hits FTS; a query with no FTS tokens or no
     * hits falls back to a substring match for partial words and punctuation.
     */
    suspend fun searchMessagesInSession(sessionId: String, rawQuery: String, limit: Int = 200): List<MessageHit> {
        val q = rawQuery.trim()
        if (q.isEmpty()) return emptyList()
        val match = ChatSearch.ftsMatchQuery(q)
        val rows = (if (match == null) emptyList() else db.dao().searchFtsInSession(match, sessionId, limit))
            .ifEmpty { db.dao().searchLikeInSession(ChatSearch.likePattern(q), sessionId, limit) }
            .filterNot { it.role == Role.ASSISTANT.name && it.toolCallId != null }
        if (rows.isEmpty()) return emptyList()
        val session = db.dao().session(sessionId) ?: return emptyList()
        return rows.filter { row ->
            !ConversationConfig.decode(session.conversationJson).isChat || row.role in setOf("USER", "ASSISTANT")
        }.map { row ->
            MessageHit(
                messageId = row.id,
                sessionId = row.sessionId,
                sessionTitle = session.title,
                text = if (row.role == Role.ASSISTANT.name) com.androidharness.app.core.response.RichResponse.plainText(row.text) else com.androidharness.app.core.response.UserMessageContent.visibleText(row.text),
                createdAt = row.createdAt,
            )
        }
    }

    suspend fun addUsage(id: String, input: Long, output: Long, cached: Long, cacheWrite: Long = 0) {
        db.dao().addUsage(id, input, output, cached, cacheWrite)
    }

    /** Per-model per-request usage row (powers the stats "By model" card). */
    suspend fun recordUsage(
        sessionId: String,
        providerName: String,
        model: String,
        input: Long,
        output: Long,
        cached: Long,
        cacheWrite: Long,
        turnId: String? = null,
        cacheReported: Boolean = false,
        cachePrices: com.androidharness.app.llm.ModelsDev.CachePrices? = null,
    ) {
        if (model.isBlank()) return
        db.dao().insertUsageEvent(
            com.androidharness.app.data.db.UsageEventEntity(
                sessionId = sessionId,
                providerName = providerName,
                model = model,
                inputTokens = input,
                outputTokens = output,
                cachedTokens = cached,
                cacheWriteTokens = cacheWrite,
                turnId = turnId,
                cacheReported = cacheReported,
                inputPrice = cachePrices?.input,
                cacheReadPrice = cachePrices?.read,
                cacheWritePrice = cachePrices?.write,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    fun usageEventsFor(sessionId: String) = db.dao().usageEventsForSession(sessionId)

    /** Per-model usage breakdown for a single session. */
    fun usageByModelFor(sessionId: String): Flow<List<com.androidharness.app.data.db.ModelUsagePojo>> =
        db.dao().usageByModelForSession(sessionId)

    /** Attach measured counts to their tool call so completed activity survives reopening. */
    suspend fun recordToolCodeChange(sessionId: String, turnId: String, callId: String,
        path: String, added: Long, removed: Long) = db.withTransaction {
        for (row in db.dao().toolMessagesForTurn(sessionId, turnId)) {
            val calls = runCatching { json.decodeFromString(toolCallList, row.toolCallsJson) }.getOrNull() ?: continue
            if (calls.none { it.id == callId }) continue
            val change = com.androidharness.app.core.ToolCodeChange(path,
                added.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), removed.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            val updated = calls.map { call -> if (call.id != callId) call else call.copy(
                codeChanges = call.codeChanges.filterNot { it.path == path } + change) }
            val encoded = json.encodeToString(toolCallList, updated)
            if (encoded.length <= MAX_PERSISTED_FIELD_CHARS) db.dao().updateToolCalls(row.id, encoded)
            break
        }
    }

    /** Per-file line-change stat from one editing tool call (chat "+N −M" chips). */
    suspend fun recordFileEdit(
        sessionId: String,
        turnId: String,
        relPath: String,
        added: Long,
        removed: Long,
        activityId: String = UUID.randomUUID().toString(),
        countForUsage: Boolean = true,
    ) {
        db.withTransaction {
            db.dao().insertFileEdit(
                com.androidharness.app.data.db.FileEditEntity(
                    sessionId = sessionId,
                    turnId = turnId,
                    relPath = relPath,
                    added = added,
                    removed = removed,
                    createdAt = System.currentTimeMillis(),
                )
            )
            if (countForUsage) recordCodeActivity(sessionId, relPath, added, removed, activityId)
        }
    }

    suspend fun recordCodeActivity(sessionId: String, path: String, added: Long, removed: Long, activityId: String) {
        require(added >= 0 && removed >= 0)
        if ((added == 0L && removed == 0L) || !CodeFilePolicy.counts(path)) return
        db.dao().insertCodeActivity(com.androidharness.app.data.db.CodeActivityEntity(
            CodeFilePolicy.digest(activityId), CodeFilePolicy.fileKey(sessionId, path), added, removed, System.currentTimeMillis()))
    }

    fun codeActivityBetween(since: Long, until: Long) = db.dao().codeActivityBetween(since, until)

    /** Per-turn file-edit stats for one session, oldest first. */
    fun fileEditsFor(sessionId: String): Flow<List<com.androidharness.app.data.db.FileEditEntity>> =
        db.dao().fileEditsFlow(sessionId)

    /**
     * Cumulative per-file changes for one session, the GitHub-style
     * "Files changed" view. Room invalidates this flow on writes, so the UI
     * updates live while the agent works.
     */
    fun fileChangesFor(
        sessionId: String,
    ): Flow<List<com.androidharness.app.data.db.SessionFileChangeEntity>> =
        db.dao().sessionFileChangesFlow(sessionId)

    /**
     * Records one modification of [relPath] against the session's change set.
     *
     * Rows accumulate: the first event fixes the baseline (gzipped pre-content,
     * or an empty baseline when the file is new), later events only add to the
     * line counters and refresh status. A deletion keeps its baseline so the
     * removal stays diffable; re-creating the path clears the deleted flag.
     *
     * @param existedBefore whether the file existed before this call; a known
     *   false (file was absent) diffs against an empty baseline, while an
     *   unknown oversized pre-state (null [beforeText], true flag) records
     *   counters but no baseline ("diff unavailable" in the UI)
     */
    suspend fun recordFileChange(
        sessionId: String,
        relPath: String,
        added: Long,
        removed: Long,
        existedBefore: Boolean,
        existsAfter: Boolean,
        beforeText: String?,
    ) {
        val baselineKnown = !existedBefore || beforeText != null
        if (!baselineKnown && existsAfter && added == 0L && removed == 0L) return

        val existing = db.dao().sessionFileChange(sessionId, relPath)
        val now = System.currentTimeMillis()
        val merged = if (existing == null) {
            com.androidharness.app.data.db.SessionFileChangeEntity(
                sessionId = sessionId,
                relPath = relPath,
                added = added,
                removed = removed,
                isNew = !existedBefore,
                isDeleted = existedBefore && !existsAfter,
                baseGzip = if (existedBefore && beforeText != null) gzip(beforeText) else null,
                hasBase = baselineKnown,
                updatedAt = now,
            )
        } else {
            existing.copy(
                added = existing.added + added,
                removed = existing.removed + removed,
                // Re-creating a previously deleted path flips it back to modified.
                isDeleted = if (!existedBefore) false else !existsAfter,
                baseGzip = existing.baseGzip ?: if (existedBefore && beforeText != null) gzip(beforeText) else null,
                hasBase = existing.hasBase || baselineKnown,
                updatedAt = now,
            )
        }
        db.dao().upsertSessionFileChange(merged)
    }

    private fun gzip(text: String): ByteArray {
        val bos = java.io.ByteArrayOutputStream(text.length / 2 + 64)
        java.util.zip.GZIPOutputStream(bos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return bos.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): String =
        java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.bufferedReader(Charsets.UTF_8).readText() }

    /** Per-turn "+N −M" rows for the undo preview. */
    suspend fun fileEditsForTurns(
        sessionId: String,
        turnIds: List<String>,
    ): List<com.androidharness.app.data.db.FileEditEntity> =
        if (turnIds.isEmpty()) emptyList() else db.dao().fileEditsForTurns(sessionId, turnIds)

    /** Drops the "+N −M" chips of turns whose messages a rewind removed. */
    suspend fun deleteFileEditsForTurns(sessionId: String, turnIds: List<String>) {
        if (turnIds.isNotEmpty()) db.dao().deleteFileEditsForTurns(sessionId, turnIds)
    }

    /**
     * After a rewind restores [relPath], recompute its cumulative change row
     * against the session baseline so the "+N −M" badges and the Files-changed
     * diffs reflect the actual restored state instead of stale counters.
     */
    suspend fun refreshFileChangeAfterRewind(
        sessionId: String,
        relPath: String,
        existsAfter: Boolean,
        currentText: String?,
    ) {
        val existing = db.dao().sessionFileChange(sessionId, relPath) ?: return
        if (!existing.hasBase) return
        if (existsAfter && currentText == null) {
            // Content too large to read now, keep counters, fix status only.
            db.dao().upsertSessionFileChange(existing.copy(isDeleted = false, updatedAt = System.currentTimeMillis()))
            return
        }
        val base = if (existing.isNew || existing.baseGzip == null) "" else gunzip(existing.baseGzip!!)
        val current = if (existsAfter) currentText.orEmpty() else ""
        val (added, removed) = com.androidharness.app.core.Diff.lineCounts(base, current)
        db.dao().upsertSessionFileChange(
            existing.copy(
                added = added.toLong(),
                removed = removed.toLong(),
                isDeleted = !existsAfter,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Per-(provider, model) token totals since [since] (epoch ms; 0 = lifetime). */
    fun usageByModelSince(since: Long): Flow<List<com.androidharness.app.data.db.ModelUsagePojo>> =
        db.dao().usageByModelSince(since)

    /** Stats windows use each request's date, independently of chat activity. */
    fun statsUsageBetween(since: Long, until: Long): Flow<List<com.androidharness.app.data.db.StatsUsagePojo>> =
        db.dao().statsUsageBetween(since, until)

    suspend fun setCompaction(sessionId: String, summary: String, before: Long) {
        db.dao().setCompaction(sessionId, summary, before)
    }

    fun answerVersions(sessionId: String) = db.dao().chatAnswerVersions(sessionId)

    fun latestAnswerVersions(sessionId: String) = answerVersionsForTurn(sessionId, null)

    fun answerVersionsForTurn(sessionId: String, turnId: String?) = combine(db.dao().messagesFlow(sessionId), answerVersions(sessionId)) { rows, versions ->
        val index = rows.indexOfLast { it.role == Role.USER.name && (turnId == null || it.turnId == turnId) }
        if (index < 0) emptyList() else {
            val group = com.androidharness.app.core.ChatTurnActions.answerVersionKey(rows.getOrNull(index - 1)?.id, rows[index].text)
            versions.filter { it.groupKey == group }
        }
    }

    suspend fun answerVersionPreview(sessionId: String, versionId: String): List<ChatMessage> = kotlinx.coroutines.withContext(Dispatchers.Default) {
        val version = requireNotNull(db.dao().chatAnswerVersion(sessionId, versionId)) { "This answer version is unavailable." }
        json.decodeFromString(ListSerializer(MessageEntity.serializer()), version.messagesJson).map { it.toChatMessageCached() }
    }

    /** Called under RunManager's workspace gate before a Chat retry removes the current answer. */
    suspend fun archiveChatAnswer(sessionId: String, userMessageId: String) = db.withTransaction {
        val session = requireNotNull(db.dao().session(sessionId))
        check(ConversationConfig.decode(session.conversationJson).isChat)
        val rows = db.dao().messages(sessionId)
        val index = rows.indexOfLast { it.role == Role.USER.name }
        check(index >= 0 && rows[index].id == userMessageId) { "Only the latest answer can be versioned." }
        val tail = rows.drop(index)
        if (tail.none { it.role == Role.ASSISTANT.name && it.text.isNotBlank() }) return@withTransaction
        val group = com.androidharness.app.core.ChatTurnActions.answerVersionKey(rows.getOrNull(index - 1)?.id, rows[index].text)
        db.dao().insertChatAnswerVersion(com.androidharness.app.data.db.ChatAnswerVersionEntity(
            id = userMessageId, sessionId = sessionId, groupKey = group,
            messagesJson = json.encodeToString(ListSerializer(MessageEntity.serializer()), tail), createdAt = rows[index].createdAt,
        ))
    }

    suspend fun selectChatAnswer(sessionId: String, expectedUserId: String, versionId: String) = db.withTransaction {
        val rows = db.dao().messages(sessionId)
        val index = rows.indexOfLast { it.role == Role.USER.name }
        check(index >= 0 && rows[index].id == expectedUserId) { "The conversation changed. Try again." }
        val group = com.androidharness.app.core.ChatTurnActions.answerVersionKey(rows.getOrNull(index - 1)?.id, rows[index].text)
        val version = requireNotNull(db.dao().chatAnswerVersion(sessionId, versionId)) { "This answer version is unavailable." }
        check(version.groupKey == group)
        val restored = json.decodeFromString(ListSerializer(MessageEntity.serializer()), version.messagesJson)
        check(restored.isNotEmpty() && restored.first().role == Role.USER.name && restored.all { it.sessionId == sessionId })
        archiveChatAnswer(sessionId, expectedUserId)
        check(db.dao().deleteMessagesFrom(sessionId, expectedUserId) > 0)
        restored.forEach { db.dao().insertMessage(it) }
    }

    suspend fun clearMessages(sessionId: String) {
        val originals = originalNames(sessionId)
        db.dao().deleteChatAnswerVersions(sessionId)
        db.dao().deleteMessages(sessionId)
        db.dao().deleteCheckpoints(sessionId)
        db.dao().setCompaction(sessionId, "", 0)
        removeUnreferencedOriginals(originals)
    }

    /**
     * Deletes [messageId] and everything after it, and clears any compaction
     * summary (it may reference deleted history). Used when editing a past
     * message: the conversation is truncated there and resent.
     */
    suspend fun truncateFrom(sessionId: String, messageId: String) = db.withTransaction {
        check(db.dao().deleteMessagesFrom(sessionId, messageId) > 0) { "Message no longer exists in this chat" }
        db.dao().setCompaction(sessionId, "", 0)
    }

    suspend fun deleteSession(session: SessionEntity) = deleteSessions(listOf(session))

    /** Delete the selected histories together, then collect images no remaining chat uses. */
    suspend fun deleteSessions(sessions: List<SessionEntity>) {
        val targets = sessions.distinctBy { it.id }
        if (targets.isEmpty()) return
        val names = targets.map { originalNames(it.id) }
        val originals = if (names.any { it == null }) null else names.filterNotNull().flatten().toSet()
        db.withTransaction {
            targets.forEach { session ->
                db.dao().deleteChatAnswerVersions(session.id)
                db.dao().deleteSavedAnswers(session.id)
                db.dao().deleteMessages(session.id)
                db.dao().deleteCheckpoints(session.id)
                db.dao().deleteUsageEvents(session.id)
                db.dao().deleteFileEdits(session.id)
                db.dao().deleteSessionFileChanges(session.id)
                db.dao().deleteSession(session)
            }
        }
        removeUnreferencedOriginals(originals)
    }

    /** Branches and saved answer versions can share image references. */
    private suspend fun originalNames(sessionId: String): Set<String>? {
        if (images == null) return null
        return try {
            val rows = db.dao().messages(sessionId) + db.dao().chatAnswerVersions(sessionId).first().flatMap {
                json.decodeFromString(ListSerializer(MessageEntity.serializer()), it.messagesJson)
            }
            rows.flatMap { json.decodeFromString(imageList, it.imagesJson).map(ImageRef::name) }.toSet()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    private suspend fun removeUnreferencedOriginals(candidates: Set<String>?) {
        if (images == null || candidates.isNullOrEmpty()) return
        // If any saved data cannot be inspected, retain the originals rather
        // than risking a reference in another chat or answer version.
        try {
            val used = db.dao().allSessionIds().flatMap { id ->
                originalNames(id) ?: error("Could not inspect chat images")
            }.toSet()
            (candidates - used).forEach { name ->
                images.deleteOriginal(name)
                images.deleteChatZoom(name)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* Keep originals if reference checking fails. */ }
    }

    private fun MessageEntity.toChatMessageCached(): ChatMessage = synchronized(messageCache) {
        messageCache[id]?.takeIf { it.first == toolCallsJson + generationJson }?.second
            ?: toChatMessage().also { messageCache[id] = (toolCallsJson + generationJson) to it }
    }

    private fun MessageEntity.toChatMessage(): ChatMessage = ChatMessage(
        role = runCatching { Role.valueOf(role) }.getOrDefault(Role.USER),
        text = text,
        toolCalls = runCatching {
            json.decodeFromString(toolCallList, toolCallsJson)
        }.getOrDefault(emptyList()),
        toolCallId = toolCallId,
        toolName = toolName,
        isError = isError,
        thinking = thinking,
        thinkingMs = thinkingMs,
        outputTokens = outputTokens,
        generationMs = generationMs,
        firstTokenMs = firstTokenMs,
        streamMs = streamMs,
        generation = com.androidharness.app.core.GenerationMetadata.decode(generationJson),
        artifact = com.androidharness.app.artifacts.ChatArtifact.decode(artifactJson),
        images = runCatching {
            json.decodeFromString(imageList, imagesJson)
        }.getOrDefault(emptyList()),
        turnId = turnId,
        id = id,
        createdAt = createdAt,
    )
}
