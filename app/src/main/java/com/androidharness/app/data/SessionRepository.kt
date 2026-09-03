package com.androidharness.app.data

import androidx.room.withTransaction
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.ChatSearch
import com.androidharness.app.data.db.MessageEntity
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.data.db.UsageEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
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

class SessionRepository(
    private val db: AppDatabase,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val toolCallList = ListSerializer(ToolCallData.serializer())
    private val imageList = ListSerializer(ImageRef.serializer())

    /**
     * Message rows are insert/delete-only, so decoded [ChatMessage]s are
     * cached by row id. Without this, every insert during a run re-decodes the
     * tool-call JSON of the ENTIRE session (O(n) per commit, O(n²) per run).
     * Pruned to the observed session's ids on each flow emission.
     */
    private val messageCache = java.util.concurrent.ConcurrentHashMap<String, ChatMessage>()

    val sessions: Flow<List<SessionEntity>> = db.dao().sessionsFlow()

    fun messagesFlow(sessionId: String): Flow<List<ChatMessage>> =
        db.dao().messagesFlow(sessionId).map { list ->
            val decoded = list.map { it.toChatMessageCached() }
            if (messageCache.size > list.size) {
                val liveIds = HashSet<String>(list.size * 2)
                list.forEach { liveIds += it.id }
                messageCache.keys.retainAll(liveIds)
            }
            decoded
        }

    /** Full message list (for UI). */
    suspend fun messages(sessionId: String): List<ChatMessage> =
        db.dao().messages(sessionId).map { it.toChatMessageCached() }

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
        val msgs = if (since > 0) db.dao().messagesSince(sessionId, since)
        else db.dao().messages(sessionId)
        return (session?.compactionSummary ?: "") to msgs.map { it.toChatMessageCached() }
    }

    suspend fun session(id: String): SessionEntity? = db.dao().session(id)

    suspend fun createSession(title: String, projectId: String? = null): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.dao().insertSession(SessionEntity(id, title, now, now, projectId = projectId))
        return id
    }

    suspend fun addMessage(sessionId: String, message: ChatMessage, turnId: String? = null): String {
        val id = message.id ?: UUID.randomUUID().toString()
        db.dao().insertMessage(
            MessageEntity(
                id = id,
                sessionId = sessionId,
                role = message.role.name,
                text = message.text,
                toolCallsJson = json.encodeToString(toolCallList, message.toolCalls),
                toolCallId = message.toolCallId,
                toolName = message.toolName,
                isError = message.isError,
                thinking = message.thinking,
                thinkingMs = message.thinkingMs,
                imagesJson = json.encodeToString(imageList, message.images),
                turnId = turnId,
                createdAt = System.currentTimeMillis(),
            )
        )
        db.dao().touchSession(sessionId, System.currentTimeMillis())
        return id
    }

    suspend fun renameSession(id: String, title: String) {
        db.dao().updateSession(id, title, System.currentTimeMillis())
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
                compactionSummary = finalSummary,
                compactionBefore = if (finalSummary.isNotBlank()) now else 0L,
            )
            db.dao().insertSession(newSession)

            val newMessages = mutableListOf<MessageEntity>()
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
                        imagesJson = userMsg.imagesJson,
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
                    imagesJson = targetAssistant.imagesJson,
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
        val titles = db.dao().sessionsByIds(rows.map { it.sessionId }.distinct())
            .associate { it.id to it.title }
        return rows.map { row ->
            MessageHit(
                messageId = row.id,
                sessionId = row.sessionId,
                sessionTitle = titles[row.sessionId].orEmpty(),
                text = row.text,
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
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    /** Per-model usage breakdown for a single session. */
    fun usageByModelFor(sessionId: String): Flow<List<com.androidharness.app.data.db.ModelUsagePojo>> =
        db.dao().usageByModelForSession(sessionId)

    /** Per-file line-change stat from one editing tool call (chat "+N −M" chips). */
    suspend fun recordFileEdit(
        sessionId: String,
        turnId: String,
        relPath: String,
        added: Long,
        removed: Long,
    ) {
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
    }

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

    suspend fun setCompaction(sessionId: String, summary: String, before: Long) {
        db.dao().setCompaction(sessionId, summary, before)
    }

    suspend fun clearMessages(sessionId: String) {
        db.dao().deleteMessages(sessionId)
        db.dao().deleteCheckpoints(sessionId)
        db.dao().setCompaction(sessionId, "", 0)
    }

    /**
     * Deletes [messageId] and everything after it, and clears any compaction
     * summary (it may reference deleted history). Used when editing a past
     * message: the conversation is truncated there and resent.
     */
    suspend fun truncateFrom(sessionId: String, messageId: String) {
        db.dao().deleteMessagesFrom(sessionId, messageId)
        db.dao().setCompaction(sessionId, "", 0)
    }

    suspend fun deleteSession(session: SessionEntity) {
        db.dao().deleteMessages(session.id)
        db.dao().deleteCheckpoints(session.id)
        db.dao().deleteUsageEvents(session.id)
        db.dao().deleteFileEdits(session.id)
        db.dao().deleteSessionFileChanges(session.id)
        db.dao().deleteSession(session)
    }

    private fun MessageEntity.toChatMessageCached(): ChatMessage =
        messageCache.getOrPut(id) { toChatMessage() }

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
        images = runCatching {
            json.decodeFromString(imageList, imagesJson)
        }.getOrDefault(emptyList()),
        turnId = turnId,
        id = id,
        createdAt = createdAt,
    )
}
