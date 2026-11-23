package com.androidharness.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.MessageEntity
import com.androidharness.app.data.db.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

class ChatBackupException(message: String) : Exception(message)

/**
 * Chats-only backup file: sessions with their messages, the workspaces those
 * chats used, saved memories, and prompt snippets. No API keys, provider
 * config, MCP servers, or settings ever enter the file, so it is safe to keep
 * anywhere. toolCallsJson and imagesJson ride along as the exact strings stored
 * in the database, lossless even if those payloads evolve in later app versions.
 */
@Serializable
data class ChatBackupFile(
    val format: String = ChatBackupCodec.FORMAT,
    val version: Int = ChatBackupCodec.VERSION,
    val exportedAt: Long = 0,
    val sessions: List<BackupSession> = emptyList(),
    val workspaces: List<BackupWorkspace> = emptyList(),
    val memories: List<BackupMemory> = emptyList(),
    val snippets: List<BackupSnippet> = emptyList(),
)

@Serializable
data class BackupWorkspace(val id: String, val name: String)

/** A chat found in a backup file, with whether a chat with the same id is already on this phone. */
data class ImportChat(val info: BackupChatInfo, val exists: Boolean)

/** What a restore would bring in, read from the manifest (or a legacy JSON file) before any write. */
data class ChatImportPreview(
    val uri: Uri,
    val chats: List<ImportChat>,
    val workspaces: List<BackupWorkspace>,
    val exportedAt: Long,
    val memories: Int,
    val snippets: Int,
    val memoryProjects: List<String> = emptyList(),
    val legacy: Boolean = false,
    val workProjects: List<BackupWorkProject> = emptyList(),
)

enum class DuplicatePolicy { SKIP, KEEP_BOTH }

/** Where a backup or restore is up to; [title] is the chat being packed or unpacked. */
data class BackupProgress(val done: Int, val total: Int, val title: String = "")

internal object ChatRestoreMapping {
    const val UNASSIGNED = "legacy-unassigned-workspace"
    fun referenced(session: BackupSession): Set<String> = referenced(session.projectId, session.workspaceSelectionJson)
    fun referenced(projectId: String?, workspaceSelectionJson: String?): Set<String> = buildSet {
        add(projectId ?: UNASSIGNED)
        workspaceSelectionJson?.let { raw ->
            val selection = com.androidharness.app.workspace.ChatWorkspaceSelection.decode(raw)
            require(selection.primaryId == projectId) { "A restored chat has inconsistent workspace access." }
            addAll(selection.grants.map { it.projectId })
        }
    }
    /** Re-points a stored workspace selection at the workspaces chosen on this phone. */
    fun remapSelection(raw: String, mapping: Map<String, String>): String {
        val saved = com.androidharness.app.workspace.ChatWorkspaceSelection.decode(raw)
        val grants = saved.grants.map { grant -> grant.copy(projectId = mapping[grant.projectId] ?: grant.projectId) }
        return com.androidharness.app.workspace.ChatWorkspaceSelection.encode(
            saved.copy(primaryId = mapping[saved.primaryId] ?: saved.primaryId, grants = grants).validated())
    }
    fun remap(session: BackupSession, mapping: Map<String, String>): BackupSession {
        val primary = session.projectId ?: UNASSIGNED
        val selection = session.workspaceSelectionJson?.let {
            val saved = com.androidharness.app.workspace.ChatWorkspaceSelection.decode(it)
            val grants = saved.grants.map { grant -> grant.copy(projectId = mapping[grant.projectId] ?: grant.projectId) }
            com.androidharness.app.workspace.ChatWorkspaceSelection.encode(
                saved.copy(primaryId = mapping[saved.primaryId] ?: saved.primaryId, grants = grants).validated())
        }
        return session.copy(projectId = mapping[primary] ?: session.projectId, workspaceSelectionJson = selection)
    }
}

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
    val modelSelectionJson: String? = null,
    val workspaceSelectionJson: String? = null,
    val conversationJson: String? = null,
    val workProjectId: String? = null,
    /** Per-request token records behind the Stats screen. */
    val usage: List<BackupUsage> = emptyList(),
    /** Earlier versions of regenerated answers, stored exactly as the database keeps them. */
    val answerVersions: List<BackupAnswerVersion> = emptyList(),
    val savedAnswers: List<BackupSavedAnswer> = emptyList(),
)

@Serializable
data class BackupUsage(
    val providerName: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val createdAt: Long,
    val turnId: String? = null,
    val cacheReported: Boolean = false,
    val inputPrice: Double? = null,
    val cacheReadPrice: Double? = null,
    val cacheWritePrice: Double? = null,
)

@Serializable
data class BackupAnswerVersion(val id: String, val groupKey: String, val messagesJson: String, val createdAt: Long)

@Serializable
data class BackupSavedAnswer(val messageId: String, val text: String, val answeredAt: Long, val savedAt: Long)

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

/** Pure encode/decode plus file validation; no Android types, so JVM-testable. */
object ChatBackupCodec {
    const val FORMAT = "androidharness-chats"
    const val VERSION = 5

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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

    /** Streams a legacy single-file backup straight into objects, without a second full-text copy. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun decode(input: java.io.InputStream): ChatBackupFile {
        val file = try {
            json.decodeFromStream(ChatBackupFile.serializer(), input)
        } catch (e: OutOfMemoryError) {
            throw ChatBackupException("This backup is too large for this phone's memory")
        } catch (e: Exception) {
            throw ChatBackupException("Not a chat backup file")
        }
        if (file.format != FORMAT) throw ChatBackupException("Not a chat backup file")
        if (file.version > VERSION) throw ChatBackupException("This backup was written by a newer version of the app")
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

