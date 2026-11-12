package com.androidharness.app.llm.debug

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Request log storage, in its own database file.
 *
 * Deliberately separate from `harness.db`: the log has its own retention, can be
 * wiped without touching chats, and needs no migration path through the main
 * schema. Bodies are stored as sealed blobs (see [DebugCrypto]); everything a
 * filter touches stays a plain column.
 */
@Entity(
    tableName = "debug_traces",
    indices = [Index("startedAt"), Index("sessionId")],
)
data class DebugTraceEntity(
    @PrimaryKey val id: Long,
    val startedAt: Long,
    val sessionId: String?,
    val providerId: String,
    val providerName: String,
    val model: String,
    val protocol: String,
    val method: String,
    val url: String,
    val requestHeaders: String,
    val requestBody: ByteArray?,
    val requestBodyElided: Boolean = false,
    val requestBodyOmitted: Int = 0,
    val requestBodyValidJson: Boolean = true,
    val statusCode: Int?,
    val responseHeaders: String,
    val responsePreview: ByteArray?,
    val errorBody: ByteArray?,
    val error: String?,
    val durationMs: Long,
    val previewOnly: Boolean,
    val truncated: Boolean,
    val probe: Boolean,
    val stream: Boolean,
    val streamBody: ByteArray?,
    val streamEvents: Int,
    val streamUsage: String? = null,
    val attempt: Int,
    val keySlot: Int,
    val turnGroup: String?,
    val failed: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is DebugTraceEntity && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

@Dao
interface DebugTraceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<DebugTraceEntity>)

    @Query(
        "SELECT * FROM debug_traces WHERE startedAt >= :since " +
            "AND (:sessionId IS NULL OR sessionId = :sessionId) " +
            "AND (:failedOnly = 0 OR failed = 1) " +
            "ORDER BY startedAt DESC LIMIT :limit"
    )
    suspend fun query(since: Long, sessionId: String?, failedOnly: Boolean, limit: Int): List<DebugTraceEntity>

    @Query("SELECT DISTINCT sessionId FROM debug_traces WHERE sessionId IS NOT NULL AND startedAt >= :since")
    suspend fun sessions(since: Long): List<String>

    @Query("SELECT COUNT(*) FROM debug_traces")
    suspend fun count(): Int

    @Query("DELETE FROM debug_traces WHERE startedAt < :cutoff")
    suspend fun pruneBefore(cutoff: Long)

    @Query("DELETE FROM debug_traces WHERE id NOT IN (SELECT id FROM debug_traces ORDER BY startedAt DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM debug_traces")
    suspend fun clear()
}

@Database(entities = [DebugTraceEntity::class], version = 2, exportSchema = false)
abstract class DebugLogDatabase : RoomDatabase() {
    abstract fun dao(): DebugTraceDao

    companion object {
        fun open(context: Context): DebugLogDatabase =
            Room.databaseBuilder(context.applicationContext, DebugLogDatabase::class.java, "debug-log.db")
                // The log is disposable by design; a schema change may drop it.
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
