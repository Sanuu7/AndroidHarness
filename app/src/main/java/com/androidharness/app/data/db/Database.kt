package com.androidharness.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
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
    val imagesJson: String = "[]",
    val turnId: String? = null,
    val createdAt: Long,
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

    @Query("DELETE FROM checkpoints WHERE sessionId = :sessionId AND turnId = :turnId")
    suspend fun deleteCheckpoints(sessionId: String, turnId: String)

    // snippets
    @Query("SELECT * FROM snippets ORDER BY name ASC")
    fun snippetsFlow(): Flow<List<SnippetEntity>>

    @Insert
    suspend fun insertSnippet(snippet: SnippetEntity)

    @Delete
    suspend fun deleteSnippet(snippet: SnippetEntity)
}

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ProjectEntity::class,
        CheckpointEntity::class,
        SnippetEntity::class,
    ],
    version = 5,
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
    }
}
