package com.androidharness.app.data

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.MessageEntity
import com.androidharness.app.data.db.SessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

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
     * Cumulative per-file changes for one session — the GitHub-style
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
