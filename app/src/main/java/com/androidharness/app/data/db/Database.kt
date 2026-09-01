package com.androidharness.app.data.db

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
)

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
    val imagesJson: String = "[]",
    val turnId: String? = null,
    val createdAt: Long,
)

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

/** Aggregated per (provider, model) usage within a time window. */
data class ModelUsagePojo(    val providerName: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val cacheWriteTokens: Long,
    val requests: Long,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

@Dao
interface HarnessDao {
    // sessions
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun sessionsFlow(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: String): SessionEntity?

    @Insert
    suspend fun insertSession(session: SessionEntity)

    @Query("UPDATE sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSession(id: String, title: String, updatedAt: Long)

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

    @Delete
    suspend fun deleteSession(session: SessionEntity)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    @Query("DELETE FROM checkpoints WHERE sessionId = :sessionId")
    suspend fun deleteCheckpoints(sessionId: String)

    // messages
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, rowid ASC")
    fun messagesFlow(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, rowid ASC")
    suspend fun messages(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND createdAt >= :since ORDER BY createdAt ASC, rowid ASC")
    suspend fun messagesSince(sessionId: String, since: Long): List<MessageEntity>

    @Query("SELECT MIN(createdAt) FROM messages WHERE sessionId = :sessionId AND createdAt >= :since")
    suspend fun firstMessageSince(sessionId: String, since: Long): Long?

    @Insert
    suspend fun insertMessage(message: MessageEntity)

    /** Deletes [messageId] and every message after it in the session. */
    @Query(
        "DELETE FROM messages WHERE sessionId = :sessionId AND rowid >= " +
            "(SELECT rowid FROM messages WHERE id = :messageId)",
    )
    suspend fun deleteMessagesFrom(sessionId: String, messageId: String): Int

    // chat search
    @Query(
        "SELECT m.* FROM message_fts f JOIN messages m ON m.rowid = f.docid " +
            "WHERE f.text MATCH :match ORDER BY m.createdAt DESC LIMIT :limit"
    )
    suspend fun searchFts(match: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE text LIKE :pattern ESCAPE '\\' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun searchLike(pattern: String, limit: Int): List<MessageEntity>

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

    @Query("UPDATE projects SET lastUsedAt = :at WHERE id = :id")
    suspend fun touchProject(id: String, at: Long)

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

    // snippets
    @Query("SELECT * FROM snippets ORDER BY name ASC")
    fun snippetsFlow(): Flow<List<SnippetEntity>>

    @Insert
    suspend fun insertSnippet(snippet: SnippetEntity)

    @Delete
    suspend fun deleteSnippet(snippet: SnippetEntity)

    // usage events (per-model attribution)
    @Insert
    suspend fun insertUsageEvent(event: UsageEventEntity)

    @Query(
        "SELECT providerName, model, SUM(inputTokens) AS inputTokens, " +
            "SUM(outputTokens) AS outputTokens, SUM(cachedTokens) AS cachedTokens, " +
            "SUM(cacheWriteTokens) AS cacheWriteTokens, COUNT(*) AS requests " +
            "FROM usage_events WHERE createdAt >= :since " +
            "GROUP BY providerName, model ORDER BY (SUM(inputTokens) + SUM(outputTokens)) DESC"
    )
    fun usageByModelSince(since: Long): Flow<List<ModelUsagePojo>>

    @Query(
        "SELECT providerName, model, SUM(inputTokens) AS inputTokens, " +
            "SUM(outputTokens) AS outputTokens, SUM(cachedTokens) AS cachedTokens, " +
            "SUM(cacheWriteTokens) AS cacheWriteTokens, COUNT(*) AS requests " +
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

    @Query("SELECT * FROM file_edits WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun fileEditsFlow(sessionId: String): Flow<List<FileEditEntity>>

    @Query("DELETE FROM file_edits WHERE sessionId = :sessionId")
    suspend fun deleteFileEdits(sessionId: String)

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
        SessionFileChangeEntity::class,
        MessageFtsEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): HarnessDao

    companion object {
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
    }
}
