package com.androidharness.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val totalCachedTokens: Long = 0,
    val totalCacheWriteTokens: Long = 0,
    val requestCount: Long = 0,
    val lastInputTokens: Long = 0,
    val projectId: String? = null,
    val compactionSummary: String = "",
    val compactionBefore: Long = 0,
    /** Plan awaiting user approval; survives process death so the card reappears. */
    val pendingPlan: String? = null,
    val modelSelectionJson: String? = null,
    val workspaceSelectionJson: String? = null,
    val conversationJson: String? = null,
)

@kotlinx.serialization.Serializable
@Entity(tableName = "messages", indices = [Index("sessionId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val text: String,
    val toolCallsJson: String,
    val toolCallId: String?,
    val toolName: String?,
    val isError: Boolean,
    val thinking: String = "",
    val thinkingMs: Long = 0,
    val outputTokens: Int = 0,
    val generationMs: Long = 0,
    val firstTokenMs: Long = 0,
    val streamMs: Long = 0,
    val imagesJson: String = "[]",
    val turnId: String? = null,
    val createdAt: Long,
    val artifactJson: String? = null,
    val generationJson: String? = null,
)

@Entity(tableName = "chat_answer_versions", indices = [Index("sessionId")])
data class ChatAnswerVersionEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val groupKey: String,
    val messagesJson: String,
    val createdAt: Long,
)

/**
 * A chat answer the user bookmarked. The text is a copy taken when it was saved, so the answer
 * outlives regenerating, editing or rewinding the chat it came from. Keyed by the message id, so
 * saving the same answer twice is one row.
 */
@Entity(tableName = "saved_answers", indices = [Index("sessionId"), Index("savedAt")])
data class SavedAnswerEntity(
    @PrimaryKey val messageId: String,
    val sessionId: String,
    val text: String,
    val answeredAt: Long,
    val savedAt: Long,
)

/** What the sidebar list needs: a bounded excerpt, never the full text of every saved answer. */
data class SavedAnswerRow(
    val messageId: String,
    val sessionId: String,
    val excerpt: String,
    val answeredAt: Long,
    val savedAt: Long,
)

/** Stable ordering even when several messages share a millisecond. No schema change. */
data class MessagePosition(val createdAt: Long, val rowId: Long, val turnId: String?)

/**
 * External-content FTS4 index over message text. Room installs content-sync
 * triggers on [MessageEntity] so every insert/delete keeps this current; the
 * v9 migration backfills it with existing history.
 */
@Fts4(contentEntity = MessageEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "message_fts")
data class MessageFtsEntity(
    val text: String,
)

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String, // "APP" or "SAF"
    val uri: String?,
    val lastUsedAt: Long,
)

/** A saved fact or reviewed digest, owned by a section and optionally scoped to a project. */
@Entity(tableName = "memories", indices = [Index("scope"), Index("projectId"), Index("enabled")])
data class MemoryEntity(
    @PrimaryKey val id: String,
    val text: String,
    val category: String = "FACT",
    val scope: String = "GLOBAL",
    val projectId: String? = null,
    val sourceSessionId: String? = null,
    val pinned: Boolean = false,
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long = 0,
    val useCount: Int = 0,
    @ColumnInfo(defaultValue = "'SHARED'") val section: String = "SHARED",
    val consolidatedInto: String? = null,
    @ColumnInfo(defaultValue = "0") val isSummary: Boolean = false,
    val agentId: String? = null,
)

@Entity(tableName = "checkpoints", indices = [Index("sessionId")])
data class CheckpointEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val turnId: String,
    val relPath: String,
    val contentB64: String,
    val existedBefore: Boolean,
    val wasDirectory: Boolean = false,
    val createdAt: Long,
)

@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val body: String,
)

/** One model request's token cost, attributed to its model, drives per-model stats. */
@Entity(tableName = "usage_events", indices = [Index("sessionId")])
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: String,
    val providerName: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val createdAt: Long,
    val turnId: String? = null,
    @ColumnInfo(defaultValue = "0") val cacheReported: Boolean = false,
    val inputPrice: Double? = null,
    val cacheReadPrice: Double? = null,
    val cacheWritePrice: Double? = null,
)

/** Per-file line-change stats from one editing tool call, "+N −M" chips in chat. */
@Entity(tableName = "file_edits", indices = [Index("sessionId"), Index("turnId")])
data class FileEditEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: String,
    val turnId: String,
    val relPath: String,
    val added: Long,
    val removed: Long,
    val createdAt: Long,
)

/** Historical AI activity survives chat deletion and undo; no file contents or readable paths. */
@Entity(tableName = "code_activity", indices = [Index("createdAt")])
data class CodeActivityEntity(
    @PrimaryKey val id: String,
    val fileKey: String,
    val added: Long,
    val removed: Long,
    val createdAt: Long,
)

data class CodeActivityTotals(val added: Long = 0, val removed: Long = 0, val files: Long = 0, val edits: Long = 0)

/**
 * Cumulative per-file change tracking for one chat session, powers the
 * GitHub-style "Files changed" view. Rows key on (session, path) and
 * accumulate [added]/[removed] across all editing calls in the session. The
 * first modification captures a gzipped pre-change snapshot ([baseGzip]) so
 * every later diff renders against session-start content, exactly like a git
 * commit's base. New files have no baseline bytes (diff against empty), and
 * oversized pre-states leave [hasBase] false ("diff unavailable").
 */
@Entity(
    tableName = "session_file_changes",
    primaryKeys = ["sessionId", "relPath"],
    indices = [Index("updatedAt")],
)
data class SessionFileChangeEntity(
    val sessionId: String,
    val relPath: String,
    val added: Long = 0,
    val removed: Long = 0,
    /** The file did not exist before this session created it. */
    val isNew: Boolean = false,
    /** Last observed state of the file in this session is deleted. */
    val isDeleted: Boolean = false,
    /** gzip'd UTF-8 content at the time this session first touched the file. */
    val baseGzip: ByteArray? = null,
    /** True when a usable baseline exists (new file ⇒ empty baseline). */
    val hasBase: Boolean = false,
    val updatedAt: Long = 0,
)

/** A checkpointed turn with the timestamp of its earliest snapshot. */
data class TurnFirstPojo(
    val turnId: String,
    val firstAt: Long,
)

/** Request totals in a date window, retaining session and model attribution. */
data class StatsUsagePojo(
    val sessionId: String,
    val providerName: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val cacheWriteTokens: Long,
    val requests: Long,
    /** Token totals from requests made in DeepSeek's peak window, priced at list rate. */
    val peakInputTokens: Long = 0,
    val peakOutputTokens: Long = 0,
    val peakCachedTokens: Long = 0,
    val peakCacheWriteTokens: Long = 0,
)

/** Aggregated per (provider, model) usage within a time window. */
data class ModelUsagePojo(    val providerName: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val cacheWriteTokens: Long,
    val requests: Long,
    val peakInputTokens: Long = 0,
    val peakOutputTokens: Long = 0,
    val peakCachedTokens: Long = 0,
    val peakCacheWriteTokens: Long = 0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/**
 * DeepSeek's rate window: 01:00-04:00 and 06:00-10:00 UTC on weekdays are
 * peak; everything else, including weekends, is off-peak. SQLite's
 * 'unixepoch' modifier is UTC, so no device timezone leaks into the split.
 */
private const val PEAK_WINDOW_SQL =
    "(CAST(strftime('%w', createdAt / 1000, 'unixepoch') AS INTEGER) BETWEEN 1 AND 5 " +
        "AND CAST(strftime('%H', createdAt / 1000, 'unixepoch') AS INTEGER) IN (1,2,3,6,7,8,9))"

@Dao
interface HarnessDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND artifactJson IS NOT NULL ORDER BY createdAt, rowid")
    suspend fun artifactMessages(sessionId: String): List<MessageEntity>

    @Query("UPDATE sessions SET conversationJson = :config WHERE id = :id")
    suspend fun setConversation(id: String, config: String)

    // sessions
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun sessionsFlow(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun sessionFlow(id: String): Flow<SessionEntity?>

    @Query("UPDATE sessions SET modelSelectionJson = :selection WHERE id = :id")
    suspend fun setModelSelection(id: String, selection: String)

    @Query("UPDATE sessions SET projectId = :projectId, workspaceSelectionJson = NULL WHERE id = :id")
    suspend fun setSessionProject(id: String, projectId: String)

    @Query("UPDATE sessions SET projectId = :projectId, workspaceSelectionJson = :selection WHERE id = :id")
    suspend fun setChatWorkspaces(id: String, projectId: String, selection: String)

    @Query("SELECT relPath FROM checkpoints WHERE sessionId = :id UNION SELECT relPath FROM file_edits WHERE sessionId = :id UNION SELECT relPath FROM session_file_changes WHERE sessionId = :id")
    suspend fun savedWorkspacePaths(id: String): List<String>

    @Query("UPDATE checkpoints SET relPath = :newPath WHERE sessionId = :id AND relPath = :oldPath")
    suspend fun qualifyCheckpointPath(id: String, oldPath: String, newPath: String)
    @Query("UPDATE file_edits SET relPath = :newPath WHERE sessionId = :id AND relPath = :oldPath")
    suspend fun qualifyFileEditPath(id: String, oldPath: String, newPath: String)
    @Query("UPDATE session_file_changes SET relPath = :newPath WHERE sessionId = :id AND relPath = :oldPath")
    suspend fun qualifyFileChangePath(id: String, oldPath: String, newPath: String)

    @androidx.room.Transaction
    suspend fun saveChatWorkspaces(id: String, projectId: String, selection: String, qualifyLegacy: Boolean) {
        if (qualifyLegacy) savedWorkspacePaths(id).forEach { path ->
            require(!path.startsWith('/') && path.split('/').none { it == ".." } && !path.removePrefix("./").startsWith("@workspaces/")) {
                "This chat has saved changes outside its workspace or in a reserved path. Undo those changes before adding workspaces."
            }
            val newPath = "@workspaces/$projectId/" + com.androidharness.app.workspace.normalizeRelPath(path)
            qualifyCheckpointPath(id, path, newPath)
            qualifyFileEditPath(id, path, newPath)
            qualifyFileChangePath(id, path, newPath)
        }
        setChatWorkspaces(id, projectId, selection)
    }

    @Query("UPDATE sessions SET projectId = :projectId WHERE id = :id AND projectId IS NULL")
    suspend fun bindSessionProject(id: String, projectId: String)

    @Query("SELECT COUNT(*) FROM sessions WHERE projectId = :projectId OR instr(workspaceSelectionJson, '\"' || :projectId || '\"') > 0")
    suspend fun projectSessionCount(projectId: String): Int

    @Query("SELECT * FROM sessions WHERE projectId = :projectId OR instr(workspaceSelectionJson, '\"' || :projectId || '\"') > 0")
    suspend fun projectSessions(projectId: String): List<SessionEntity>

    @androidx.room.Transaction
    suspend fun deleteProjectAndChats(projectId: String, expectedChatIds: Set<String>) {
        val project = requireNotNull(project(projectId)) { "This project is no longer available." }
        check(project.kind != "APP") { "The app workspace cannot be deleted." }
        val chats = projectSessions(projectId)
        check(chats.all { it.projectId == projectId }) {
            "Chats in another project still use this workspace. Remove its access from those chats before deleting it."
        }
        check(chats.map { it.id }.toSet() == expectedChatIds) {
            "The project's chats changed. Close this dialog and review deletion again."
        }
        chats.forEach { chat ->
            deleteSavedAnswers(chat.id)
            deleteMessages(chat.id)
            deleteCheckpoints(chat.id)
            deleteUsageEvents(chat.id)
            deleteFileEdits(chat.id)
            deleteSessionFileChanges(chat.id)
            deleteSession(chat)
        }
        deleteProject(project)
    }

    @Insert
    suspend fun insertSession(session: SessionEntity)

    @Query("UPDATE sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSession(id: String, title: String, updatedAt: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE sessionId = :id AND role = 'USER')")
    suspend fun hasUserMessage(id: String): Boolean

    @Query("SELECT * FROM messages WHERE sessionId = :id AND role = 'USER' ORDER BY createdAt, rowid LIMIT 1")
    suspend fun firstUserMessage(id: String): MessageEntity?

    @Query("UPDATE sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchSession(id: String, updatedAt: Long)

    @Query(
        "UPDATE sessions SET totalInputTokens = totalInputTokens + :input, " +
            "totalOutputTokens = totalOutputTokens + :output, " +
            "totalCachedTokens = totalCachedTokens + :cached, " +
            "totalCacheWriteTokens = totalCacheWriteTokens + :cacheWrite, " +
            "requestCount = requestCount + 1, lastInputTokens = :input WHERE id = :id"
    )
    suspend fun addUsage(id: String, input: Long, output: Long, cached: Long, cacheWrite: Long)

    @Query("UPDATE sessions SET compactionSummary = :summary, compactionBefore = :before WHERE id = :id")
    suspend fun setCompaction(id: String, summary: String, before: Long)

    @Query("UPDATE sessions SET pendingPlan = :plan WHERE id = :id")
    suspend fun setPendingPlan(id: String, plan: String?)

    @Delete
    suspend fun deleteSession(session: SessionEntity)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    @Query("DELETE FROM checkpoints WHERE sessionId = :sessionId")
    suspend fun deleteCheckpoints(sessionId: String)

    // messages
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, rowid ASC")
    fun messagesFlow(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT createdAt, rowid AS rowId, turnId FROM messages WHERE sessionId = :sessionId ORDER BY createdAt DESC, rowid DESC LIMIT 1 OFFSET :offset")
    suspend fun recentMessagePosition(sessionId: String, offset: Int): MessagePosition?

    @Query("SELECT createdAt, rowid AS rowId, turnId FROM messages WHERE sessionId = :sessionId AND (createdAt < :at OR (createdAt = :at AND rowid < :rowId)) ORDER BY createdAt DESC, rowid DESC LIMIT 1 OFFSET :offset")
    suspend fun olderMessagePosition(sessionId: String, at: Long, rowId: Long, offset: Int): MessagePosition?

    @Query("SELECT createdAt, rowid AS rowId, turnId FROM messages WHERE sessionId = :sessionId AND id = :messageId")
    suspend fun messagePosition(sessionId: String, messageId: String): MessagePosition?

    @Query("SELECT createdAt, rowid AS rowId, turnId FROM messages WHERE sessionId = :sessionId AND turnId = :turnId ORDER BY createdAt ASC, rowid ASC LIMIT 1")
    suspend fun firstMessageInTurn(sessionId: String, turnId: String): MessagePosition?

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND (createdAt > :at OR (createdAt = :at AND rowid >= :rowId)) ORDER BY createdAt ASC, rowid ASC")
    fun messagesFromPositionFlow(sessionId: String, at: Long, rowId: Long): Flow<List<MessageEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE sessionId = :sessionId AND (createdAt < :at OR (createdAt = :at AND rowid < :rowId)))")
    fun hasOlderMessagesFlow(sessionId: String, at: Long, rowId: Long): Flow<Boolean>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, rowid ASC")
    suspend fun messages(sessionId: String): List<MessageEntity>

    @Query("SELECT text FROM messages WHERE sessionId = :sessionId AND role = 'TOOL' AND toolCallId = :toolCallId ORDER BY createdAt DESC, rowid DESC LIMIT 1")
    suspend fun toolOutput(sessionId: String, toolCallId: String): String?

    @Query("SELECT id FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, rowid ASC")
    suspend fun messageIds(sessionId: String): List<String>

    @Query("SELECT MAX(createdAt) FROM messages WHERE sessionId = :sessionId")
    suspend fun lastMessageAt(sessionId: String): Long?

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND createdAt >= :since ORDER BY createdAt ASC, rowid ASC")
    suspend fun messagesSince(sessionId: String, since: Long): List<MessageEntity>

    @Query("SELECT MIN(createdAt) FROM messages WHERE sessionId = :sessionId AND createdAt >= :since")
    suspend fun firstMessageSince(sessionId: String, since: Long): Long?

    @Insert
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChatAnswerVersion(version: ChatAnswerVersionEntity)

    @Query("SELECT * FROM chat_answer_versions WHERE sessionId = :sessionId ORDER BY createdAt, id")
    fun chatAnswerVersions(sessionId: String): Flow<List<ChatAnswerVersionEntity>>

    @Query("SELECT * FROM chat_answer_versions WHERE sessionId = :sessionId AND id = :id")
    suspend fun chatAnswerVersion(sessionId: String, id: String): ChatAnswerVersionEntity?

    @Query("DELETE FROM chat_answer_versions WHERE sessionId = :sessionId")
    suspend fun deleteChatAnswerVersions(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAnswer(answer: SavedAnswerEntity)

    @Query("DELETE FROM saved_answers WHERE messageId = :messageId")
    suspend fun unsaveAnswer(messageId: String)

    @Query("DELETE FROM saved_answers WHERE sessionId = :sessionId")
    suspend fun deleteSavedAnswers(sessionId: String)

    @Query("SELECT messageId FROM saved_answers WHERE sessionId = :sessionId")
    fun savedAnswerIds(sessionId: String): Flow<List<String>>

    @Query("SELECT messageId, sessionId, substr(text, 1, 1200) AS excerpt, answeredAt, savedAt FROM saved_answers ORDER BY savedAt DESC")
    fun savedAnswers(): Flow<List<SavedAnswerRow>>

    @Query("SELECT * FROM saved_answers WHERE messageId = :messageId")
    suspend fun savedAnswer(messageId: String): SavedAnswerEntity?

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND turnId = :turnId AND role = 'ASSISTANT' AND toolCallsJson != '[]'")
    suspend fun toolMessagesForTurn(sessionId: String, turnId: String): List<MessageEntity>

    @Query("UPDATE messages SET toolCallsJson = :calls WHERE id = :messageId")
    suspend fun updateToolCalls(messageId: String, calls: String)

    /** Deletes [messageId] and every message after it in the session. */
    @Query(
        "DELETE FROM messages WHERE sessionId = :sessionId AND (createdAt > " +
            "(SELECT createdAt FROM messages WHERE id = :messageId AND sessionId = :sessionId) " +
            "OR (createdAt = (SELECT createdAt FROM messages WHERE id = :messageId AND sessionId = :sessionId) " +
            "AND rowid >= (SELECT rowid FROM messages WHERE id = :messageId AND sessionId = :sessionId)))",
    )
    suspend fun deleteMessagesFrom(sessionId: String, messageId: String): Int

    // chat search
    @Query(
        "SELECT m.* FROM message_fts f JOIN messages m ON m.rowid = f.docid " +
            "WHERE f.text MATCH :match AND m.id NOT LIKE 'internal-chat-continuation-%' ORDER BY m.createdAt DESC LIMIT :limit"
    )
    suspend fun searchFts(match: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE text LIKE :pattern ESCAPE '\\' AND id NOT LIKE 'internal-chat-continuation-%' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun searchLike(pattern: String, limit: Int): List<MessageEntity>

    @Query(
        "SELECT m.* FROM message_fts f JOIN messages m ON m.rowid = f.docid " +
            "WHERE f.text MATCH :match AND m.sessionId = :sessionId AND m.id NOT LIKE 'internal-chat-continuation-%' ORDER BY m.createdAt ASC LIMIT :limit"
    )
    suspend fun searchFtsInSession(match: String, sessionId: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE text LIKE :pattern ESCAPE '\\' AND sessionId = :sessionId AND id NOT LIKE 'internal-chat-continuation-%' ORDER BY createdAt ASC LIMIT :limit")
    suspend fun searchLikeInSession(pattern: String, sessionId: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM sessions WHERE id IN (:ids)")
    suspend fun sessionsByIds(ids: List<String>): List<SessionEntity>

    // chat backup (export/import)
    @Query("SELECT * FROM sessions ORDER BY createdAt ASC")
    suspend fun allSessions(): List<SessionEntity>

    @Query("SELECT id FROM sessions")
    suspend fun allSessionIds(): List<String>

    @Query("SELECT * FROM messages ORDER BY sessionId, createdAt ASC, rowid ASC")
    suspend fun allMessages(): List<MessageEntity>

    /** Batch insert for restored chats; IGNORE keeps a partial collision from aborting the import. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImportedMessages(messages: List<MessageEntity>)

    // projects
    @Query("SELECT * FROM projects ORDER BY lastUsedAt DESC")
    fun projectsFlow(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun project(id: String): ProjectEntity?

    @Insert
    suspend fun insertProject(project: ProjectEntity)

    @Query("UPDATE projects SET kind = :kind, uri = :uri WHERE id = :id")
    suspend fun setProjectLocation(id: String, kind: String, uri: String)

    @Query("UPDATE projects SET lastUsedAt = :at WHERE id = :id")
    suspend fun touchProject(id: String, at: Long)

    @Query("UPDATE projects SET name = :name WHERE id = :id")
    suspend fun renameProject(id: String, name: String)

    @Delete
    suspend fun deleteProject(project: ProjectEntity)

    // checkpoints
    @Insert
    suspend fun insertCheckpoint(checkpoint: CheckpointEntity)

    @Query("SELECT * FROM checkpoints WHERE sessionId = :sessionId AND turnId = :turnId ORDER BY createdAt DESC")
    suspend fun checkpointsForTurn(sessionId: String, turnId: String): List<CheckpointEntity>

    @Query("SELECT DISTINCT turnId FROM checkpoints WHERE sessionId = :sessionId")
    suspend fun turnsWithCheckpoints(sessionId: String): List<String>

    /** Checkpointed turns in chronological order of their first snapshot. */
    @Query(
        "SELECT turnId AS turnId, MIN(createdAt) AS firstAt FROM checkpoints " +
            "WHERE sessionId = :sessionId GROUP BY turnId ORDER BY firstAt ASC"
    )
    suspend fun checkpointTurnsOrdered(sessionId: String): List<TurnFirstPojo>

    /** Per-turn "+N −M" stats for the undo preview; file_edits rows. */
    @Query("SELECT * FROM file_edits WHERE sessionId = :sessionId AND turnId IN (:turnIds)")
    suspend fun fileEditsForTurns(sessionId: String, turnIds: List<String>): List<FileEditEntity>

    @Query("DELETE FROM file_edits WHERE sessionId = :sessionId AND turnId IN (:turnIds)")
    suspend fun deleteFileEditsForTurns(sessionId: String, turnIds: List<String>)

    @Query("DELETE FROM checkpoints WHERE sessionId = :sessionId AND turnId = :turnId")
    suspend fun deleteCheckpoints(sessionId: String, turnId: String)

    @Delete
    suspend fun deleteCheckpoint(checkpoint: CheckpointEntity)

    // snippets
    @Query("SELECT * FROM snippets ORDER BY name ASC")
    fun snippetsFlow(): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets ORDER BY name ASC")
    suspend fun allSnippets(): List<SnippetEntity>

    @Insert
    suspend fun insertSnippet(snippet: SnippetEntity)

    @Delete
    suspend fun deleteSnippet(snippet: SnippetEntity)

    @Query("SELECT * FROM usage_events WHERE sessionId = :sessionId ORDER BY rowId")
    fun usageEventsForSession(sessionId: String): Flow<List<UsageEventEntity>>

    // usage events (per-model attribution)
    @Insert
    suspend fun insertUsageEvent(event: UsageEventEntity)

    @Query(
        "SELECT sessionId, providerName, model, SUM(inputTokens) AS inputTokens, " +
            "SUM(outputTokens) AS outputTokens, SUM(cachedTokens) AS cachedTokens, " +
            "SUM(cacheWriteTokens) AS cacheWriteTokens, " +
            "SUM(CASE WHEN " + PEAK_WINDOW_SQL + " THEN inputTokens ELSE 0 END) AS peakInputTokens, " +
            "SUM(CASE WHEN " + PEAK_WINDOW_SQL + " THEN outputTokens ELSE 0 END) AS peakOutputTokens, " +
            "SUM(CASE WHEN " + PEAK_WINDOW_SQL + " THEN cachedTokens ELSE 0 END) AS peakCachedTokens, " +
            "SUM(CASE WHEN " + PEAK_WINDOW_SQL + " THEN cacheWriteTokens ELSE 0 END) AS peakCacheWriteTokens, " +
            "COUNT(*) AS requests " +
            "FROM usage_events WHERE createdAt >= :since AND createdAt < :until " +
            "GROUP BY sessionId, providerName, model"
    )
    fun statsUsageBetween(since: Long, until: Long): Flow<List<StatsUsagePojo>>

    @Query(
        "SELECT providerName, model, SUM(inputTokens) AS inputTokens, " +
            "SUM(outputTokens) AS outputTokens, SUM(cachedTokens) AS cachedTokens, " +
            "SUM(cacheWriteTokens) AS cacheWriteTokens, " +
            "SUM(CASE WHEN " + PEAK_WINDOW_SQL + " THEN inputTokens ELSE 0 END) AS peakInputTokens, " +
            "SUM(CASE WHEN " + PEAK_WINDOW_SQL + " THEN outputTokens ELSE 0 END) AS peakOutputTokens, " +
            "SUM(CASE WHEN " + PEAK_WINDOW_SQL + " THEN cachedTokens ELSE 0 END) AS peakCachedTokens, " +
            "SUM(CASE WHEN " + PEAK_WINDOW_SQL + " THEN cacheWriteTokens ELSE 0 END) AS peakCacheWriteTokens, " +
            "COUNT(*) AS requests " +
            "FROM usage_events WHERE createdAt >= :since " +
            "GROUP BY providerName, model ORDER BY (SUM(inputTokens) + SUM(outputTokens)) DESC"
    )
    fun usageByModelSince(since: Long): Flow<List<ModelUsagePojo>>

    @Query(
        "SELECT providerName, model, SUM(inputTokens) AS inputTokens, " +
            "SUM(outputTokens) AS outputTokens, SUM(cachedTokens) AS cachedTokens, " +
            "SUM(cacheWriteTokens) AS cacheWriteTokens, " +
            "SUM(CASE WHEN " + PEAK_WINDOW_SQL + " THEN inputTokens ELSE 0 END) AS peakInputTokens, " +
            "SUM(CASE WHEN " + PEAK_WINDOW_SQL + " THEN outputTokens ELSE 0 END) AS peakOutputTokens, " +
            "SUM(CASE WHEN " + PEAK_WINDOW_SQL + " THEN cachedTokens ELSE 0 END) AS peakCachedTokens, " +
            "SUM(CASE WHEN " + PEAK_WINDOW_SQL + " THEN cacheWriteTokens ELSE 0 END) AS peakCacheWriteTokens, " +
            "COUNT(*) AS requests " +
            "FROM usage_events WHERE sessionId = :sessionId " +
            "GROUP BY providerName, model ORDER BY (SUM(inputTokens) + SUM(outputTokens)) DESC"
    )
    fun usageByModelForSession(sessionId: String): Flow<List<ModelUsagePojo>>

    @Query("SELECT * FROM usage_events WHERE sessionId = :sessionId AND createdAt <= :upToCreatedAt ORDER BY createdAt ASC")
    suspend fun usageEventsForSessionUpTo(sessionId: String, upToCreatedAt: Long): List<UsageEventEntity>

    @Insert
    suspend fun insertUsageEvents(events: List<UsageEventEntity>)

    @Query("DELETE FROM usage_events WHERE sessionId = :sessionId")
    suspend fun deleteUsageEvents(sessionId: String)

    // file edits (diff chips in chat)
    @Insert
    suspend fun insertFileEdit(edit: FileEditEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCodeActivity(activity: CodeActivityEntity)

    @Query("SELECT COALESCE(SUM(added), 0) AS added, COALESCE(SUM(removed), 0) AS removed, COUNT(DISTINCT fileKey) AS files, COUNT(*) AS edits FROM code_activity WHERE createdAt >= :since AND createdAt < :until")
    fun codeActivityBetween(since: Long, until: Long): Flow<CodeActivityTotals>

    @Query("SELECT * FROM file_edits WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun fileEditsFlow(sessionId: String): Flow<List<FileEditEntity>>

    @Query("DELETE FROM file_edits WHERE sessionId = :sessionId")
    suspend fun deleteFileEdits(sessionId: String)

    // memories
    @Query("SELECT * FROM memories ORDER BY pinned DESC, updatedAt DESC")
    fun memoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY pinned DESC, updatedAt DESC")
    suspend fun allMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE enabled = 1 AND consolidatedInto IS NULL AND (scope = 'GLOBAL' OR (scope = 'PROJECT' AND projectId = :projectId)) ORDER BY pinned DESC, lastUsedAt DESC, updatedAt DESC")
    suspend fun activeMemories(projectId: String?): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE enabled = 1 AND consolidatedInto IS NULL")
    suspend fun enabledMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE lower(text) = lower(:text) AND scope = :scope AND ((projectId IS NULL AND :projectId IS NULL) OR projectId = :projectId) LIMIT 1")
    suspend fun findMemory(text: String, scope: String, projectId: String?): MemoryEntity?

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun memory(id: String): MemoryEntity?

    @Query("SELECT projectId FROM sessions WHERE id = :id")
    suspend fun projectIdForSession(id: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemory(id: String)

    @Query("DELETE FROM memories")
    suspend fun deleteAllMemories()

    @Query("UPDATE memories SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setMemoryEnabled(id: String, enabled: Boolean, now: Long)

    @Query("UPDATE memories SET pinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setMemoryPinned(id: String, pinned: Boolean, now: Long)

    @Query("UPDATE memories SET lastUsedAt = :now, useCount = useCount + 1 WHERE id IN (:ids)")
    suspend fun markMemoriesUsed(ids: List<String>, now: Long)

    @Query("UPDATE memories SET text = :text, category = :category, scope = :scope, projectId = :projectId, updatedAt = :now WHERE id = :id")
    suspend fun updateMemory(id: String, text: String, category: String, scope: String, projectId: String?, now: Long)

    @Query("UPDATE memories SET consolidatedInto = :summaryId WHERE id IN (:ids)")
    suspend fun consolidateMemories(ids: List<String>, summaryId: String)

    @Query("UPDATE memories SET consolidatedInto = NULL WHERE consolidatedInto = :summaryId")
    suspend fun restoreConsolidatedMemories(summaryId: String)

    // session file changes (GitHub-style "Files changed" per chat)
    @Query(
        "SELECT * FROM session_file_changes WHERE sessionId = :sessionId " +
            "ORDER BY updatedAt DESC"
    )
    fun sessionFileChangesFlow(sessionId: String): Flow<List<SessionFileChangeEntity>>

    @Query("SELECT * FROM session_file_changes WHERE sessionId = :sessionId AND relPath = :relPath")
    suspend fun sessionFileChange(sessionId: String, relPath: String): SessionFileChangeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSessionFileChange(change: SessionFileChangeEntity)

    @Query("DELETE FROM session_file_changes WHERE sessionId = :sessionId")
    suspend fun deleteSessionFileChanges(sessionId: String)
}

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ProjectEntity::class,
        CheckpointEntity::class,
        SnippetEntity::class,
        UsageEventEntity::class,
        FileEditEntity::class,
        CodeActivityEntity::class,
        SessionFileChangeEntity::class,
        MessageFtsEntity::class,
        MemoryEntity::class,
        ChatAnswerVersionEntity::class,
        SavedAnswerEntity::class,
    ],
    version = 25,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): HarnessDao

    companion object {
        val MIGRATION_24_25 = object : androidx.room.migration.Migration(24, 25) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS saved_answers (messageId TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, text TEXT NOT NULL, answeredAt INTEGER NOT NULL, savedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_saved_answers_sessionId ON saved_answers(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_saved_answers_savedAt ON saved_answers(savedAt)")
            }
        }
        val MIGRATION_23_24 = object : androidx.room.migration.Migration(23, 24) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN agentId TEXT")
            }
        }
        val MIGRATION_22_23 = object : androidx.room.migration.Migration(22, 23) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN section TEXT NOT NULL DEFAULT 'SHARED'")
                db.execSQL("ALTER TABLE memories ADD COLUMN consolidatedInto TEXT")
                db.execSQL("ALTER TABLE memories ADD COLUMN isSummary INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS chat_answer_versions (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, groupKey TEXT NOT NULL, messagesJson TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_answer_versions_sessionId ON chat_answer_versions(sessionId)")
            }
        }

        val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memories (" +
                        "id TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, category TEXT NOT NULL, " +
                        "scope TEXT NOT NULL, projectId TEXT, sourceSessionId TEXT, " +
                        "pinned INTEGER NOT NULL, enabled INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL, useCount INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_scope ON memories(scope)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_projectId ON memories(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_enabled ON memories(enabled)")
            }
        }

        val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN conversationJson TEXT")
            }
        }

        val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS code_activity (id TEXT NOT NULL PRIMARY KEY, fileKey TEXT NOT NULL, added INTEGER NOT NULL, removed INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_code_activity_createdAt ON code_activity(createdAt)")
                db.query("SELECT rowId, sessionId, relPath, added, removed, createdAt FROM file_edits").use { rows ->
                    while (rows.moveToNext()) {
                        val path = rows.getString(2)
                        if (com.androidharness.app.data.CodeFilePolicy.counts(path) && (rows.getLong(3) > 0 || rows.getLong(4) > 0)) {
                            db.execSQL("INSERT OR IGNORE INTO code_activity (id, fileKey, added, removed, createdAt) VALUES (?, ?, ?, ?, ?)", arrayOf<Any>(
                                "legacy-${rows.getLong(0)}", com.androidharness.app.data.CodeFilePolicy.fileKey(rows.getString(1), path),
                                rows.getLong(3), rows.getLong(4), rows.getLong(5)))
                        }
                    }
                }
            }
        }
        val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN generationJson TEXT")
            }
        }

        val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN artifactJson TEXT")
            }
        }
        val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN workspaceSelectionJson TEXT")
            }
        }

        val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN modelSelectionJson TEXT")
            }
        }

        val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN firstTokenMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN streamMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Android CursorWindow hard-caps rows at 2MB; rows exceeding the cap
         * throw SQLiteBlobTooBigException and crash the app on startup/query.
         * Truncate oversized rows on open so corrupted sessions self-heal.
         */
        val OVERSIZED_ROW_SANITIZER = object : RoomDatabase.Callback() {
            override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                super.onOpen(db)
                runCatching {
                    db.execSQL(
                        "UPDATE messages SET text = substr(text, 1, 100000) || char(10) || '[truncated for storage safety]' " +
                            "WHERE length(text) > 250000"
                    )
                    db.execSQL(
                        "UPDATE messages SET thinking = substr(thinking, 1, 100000) || char(10) || '[truncated for storage safety]' " +
                            "WHERE length(thinking) > 250000"
                    )
                    db.execSQL(
                        "UPDATE messages SET toolCallsJson = '[]' " +
                            "WHERE length(toolCallsJson) > 250000"
                    )
                }
            }
        }

        /** Attribute cache usage to turns; old rows keep unknown cache reporting. */
        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_events ADD COLUMN inputPrice REAL")
                db.execSQL("ALTER TABLE usage_events ADD COLUMN cacheReadPrice REAL")
                db.execSQL("ALTER TABLE usage_events ADD COLUMN cacheWritePrice REAL")
            }
        }

        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_events ADD COLUMN turnId TEXT")
                db.execSQL("ALTER TABLE usage_events ADD COLUMN cacheReported INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Persist measured model response speed without changing existing chat history. */
        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN outputTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN generationMs INTEGER NOT NULL DEFAULT 0")
            }
        }


        /** v5: per-session cache-write tokens (Anthropic cache creation). */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE sessions ADD COLUMN totalCacheWriteTokens INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v6: per-request, per-model usage attribution for the stats redesign. */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS usage_events (" +
                        "rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sessionId TEXT NOT NULL, " +
                        "providerName TEXT NOT NULL, " +
                        "model TEXT NOT NULL, " +
                        "inputTokens INTEGER NOT NULL, " +
                        "outputTokens INTEGER NOT NULL, " +
                        "cachedTokens INTEGER NOT NULL DEFAULT 0, " +
                        "cacheWriteTokens INTEGER NOT NULL DEFAULT 0, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_usage_events_sessionId ON usage_events(sessionId)"
                )
            }
        }

        /** v7: thinking duration on messages + per-file edit stats for chat chips. */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN thinkingMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS file_edits (" +
                        "rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sessionId TEXT NOT NULL, " +
                        "turnId TEXT NOT NULL, " +
                        "relPath TEXT NOT NULL, " +
                        "added INTEGER NOT NULL, " +
                        "removed INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_file_edits_sessionId ON file_edits(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_file_edits_turnId ON file_edits(turnId)")
            }
        }

        /** v8: cumulative per-session file changes ("Files changed" view). */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS session_file_changes (" +
                        "sessionId TEXT NOT NULL, " +
                        "relPath TEXT NOT NULL, " +
                        "added INTEGER NOT NULL DEFAULT 0, " +
                        "removed INTEGER NOT NULL DEFAULT 0, " +
                        "isNew INTEGER NOT NULL DEFAULT 0, " +
                        "isDeleted INTEGER NOT NULL DEFAULT 0, " +
                        "baseGzip BLOB, " +
                        "hasBase INTEGER NOT NULL DEFAULT 0, " +
                        "updatedAt INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(sessionId, relPath))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_session_file_changes_updatedAt " +
                        "ON session_file_changes(updatedAt)"
                )
            }
        }

        /**
         * v9: FTS4 search index over message text. Column list and options
         * must stay byte-identical to Room's generated CREATE for schema
         * validation; the rebuild backfills the index with existing history.
         */
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `message_fts` USING FTS4(" +
                        "`text` TEXT NOT NULL, tokenize=unicode61, content=`messages`)"
                )
                db.execSQL("INSERT INTO message_fts(message_fts) VALUES('rebuild')")
            }
        }

        /** v10: plan approval persisted per session so it survives process death. */
        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN pendingPlan TEXT")
            }
        }
    }
}
