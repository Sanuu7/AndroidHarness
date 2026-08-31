package com.androidharness.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.MessageEntity
import com.androidharness.app.data.db.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ChatBackupException(message: String) : Exception(message)

/**
 * Chats-only backup file: sessions with their messages, nothing else. No API
 * keys, provider config, MCP servers, or settings ever enter the file, so it
 * is safe to keep anywhere. toolCallsJson and imagesJson ride along as the
 * exact strings stored in the database, lossless even if those payloads
 * evolve in later app versions.
 */
@Serializable
data class ChatBackupFile(
    val format: String = ChatBackupCodec.FORMAT,
    val version: Int = ChatBackupCodec.VERSION,
    val exportedAt: Long = 0,
    val sessions: List<BackupSession> = emptyList(),
)

@Serializable
data class BackupSession(
    val id: String,
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
    val messages: List<BackupMessage> = emptyList(),
)

@Serializable
data class BackupMessage(
    val id: String,
    val role: String,
    val text: String,
    val toolCallsJson: String = "[]",
    val toolCallId: String? = null,
    val toolName: String? = null,
    val isError: Boolean = false,
    val thinking: String = "",
    val thinkingMs: Long = 0,
    val imagesJson: String = "[]",
    val turnId: String? = null,
    val createdAt: Long,
)

/** Pure encode/decode plus file validation; no Android types, so JVM-testable. */
object ChatBackupCodec {
    const val FORMAT = "androidharness-chats"
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(file: ChatBackupFile): String =
        json.encodeToString(ChatBackupFile.serializer(), file)

    /** Decodes and validates; throws [ChatBackupException] with a user-facing message. */
    fun decode(text: String): ChatBackupFile {
        val file = try {
            json.decodeFromString(ChatBackupFile.serializer(), text)
        } catch (e: Exception) {
            throw ChatBackupException("Not a chat backup file")
        }
        if (file.format != FORMAT) throw ChatBackupException("Not a chat backup file")
        if (file.version > VERSION) {
            throw ChatBackupException("This backup was written by a newer version of the app")
        }
        return file
    }
}

/**
 * Pure import planning: sessions already in the database (by id) are skipped,
 * so re-importing the same file is a no-op instead of a duplicate flood.
 */
object ChatBackupMerge {
    data class Plan(val toImport: List<BackupSession>, val skipped: Int)

    fun plan(existingSessionIds: Set<String>, incoming: List<BackupSession>): Plan {
        val fresh = incoming.filterNot { it.id in existingSessionIds }
        return Plan(fresh, incoming.size - fresh.size)
    }
}

/**
 * Exports and imports chats through SAF uris. Export writes every session
 * with its full message list; import merges, skipping sessions whose id
 * already exists. All work runs on Dispatchers.IO; callers surface errors.
 */
class ChatBackupManager(
    private val db: AppDatabase,
    private val appContext: Context,
) {
    data class ExportResult(val sessions: Int, val messages: Int)
    data class ImportResult(val sessions: Int, val messages: Int, val skipped: Int)

    suspend fun exportTo(uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        val sessions = db.dao().allSessions()
        val bySession = db.dao().allMessages().groupBy { it.sessionId }
        val file = ChatBackupFile(
            exportedAt = System.currentTimeMillis(),
            sessions = sessions.map { s ->
                BackupSession(
                    id = s.id,
                    title = s.title,
                    createdAt = s.createdAt,
                    updatedAt = s.updatedAt,
                    totalInputTokens = s.totalInputTokens,
                    totalOutputTokens = s.totalOutputTokens,
                    totalCachedTokens = s.totalCachedTokens,
                    totalCacheWriteTokens = s.totalCacheWriteTokens,
                    requestCount = s.requestCount,
                    lastInputTokens = s.lastInputTokens,
                    projectId = s.projectId,
                    compactionSummary = s.compactionSummary,
                    compactionBefore = s.compactionBefore,
                    messages = bySession[s.id].orEmpty().map { m ->
                        BackupMessage(
                            id = m.id,
                            role = m.role,
                            text = m.text,
                            toolCallsJson = m.toolCallsJson,
                            toolCallId = m.toolCallId,
                            toolName = m.toolName,
                            isError = m.isError,
                            thinking = m.thinking,
                            thinkingMs = m.thinkingMs,
                            imagesJson = m.imagesJson,
                            turnId = m.turnId,
                            createdAt = m.createdAt,
                        )
                    },
                )
            },
        )
        val out = appContext.contentResolver.openOutputStream(uri)
            ?: throw ChatBackupException("Could not open the file for writing")
        out.use { stream ->
            stream.buffered().writer(Charsets.UTF_8).use { it.write(ChatBackupCodec.encode(file)) }
        }
        ExportResult(file.sessions.size, file.sessions.sumOf { it.messages.size })
    }

    suspend fun importFrom(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val text = appContext.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw ChatBackupException("Could not open the file")
        val file = ChatBackupCodec.decode(text)
        val plan = ChatBackupMerge.plan(db.dao().allSessionIds().toSet(), file.sessions)
        if (plan.toImport.isNotEmpty()) {
            db.withTransaction {
                for (s in plan.toImport) {
                    db.dao().insertSession(
                        SessionEntity(
                            id = s.id,
                            title = s.title,
                            createdAt = s.createdAt,
                            updatedAt = s.updatedAt,
                            totalInputTokens = s.totalInputTokens,
                            totalOutputTokens = s.totalOutputTokens,
                            totalCachedTokens = s.totalCachedTokens,
                            totalCacheWriteTokens = s.totalCacheWriteTokens,
                            requestCount = s.requestCount,
                            lastInputTokens = s.lastInputTokens,
                            projectId = s.projectId,
                            compactionSummary = s.compactionSummary,
                            compactionBefore = s.compactionBefore,
                        )
                    )
                    val messages = s.messages.map { m ->
                        MessageEntity(
                            id = m.id,
                            sessionId = s.id,
                            role = m.role,
                            text = m.text,
                            toolCallsJson = m.toolCallsJson,
                            toolCallId = m.toolCallId,
                            toolName = m.toolName,
                            isError = m.isError,
                            thinking = m.thinking,
                            thinkingMs = m.thinkingMs,
                            imagesJson = m.imagesJson,
                            turnId = m.turnId,
                            createdAt = m.createdAt,
                        )
                    }
                    if (messages.isNotEmpty()) db.dao().insertImportedMessages(messages)
                }
            }
        }
        ImportResult(plan.toImport.size, plan.toImport.sumOf { it.messages.size }, plan.skipped)
    }
}
