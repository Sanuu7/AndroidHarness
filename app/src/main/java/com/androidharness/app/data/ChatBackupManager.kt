package com.androidharness.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.MessageEntity
import com.androidharness.app.data.db.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Backs chats up to, and restores them from, SAF documents. Export streams one chat at a time
 * into a [ChatBackupArchive], so a phone with hundreds of long coding chats never holds more than
 * one of them in memory. Restore reads the same way and saves each chat in its own transaction.
 * Older single-file JSON backups still restore.
 */
class ChatBackupManager(
    private val db: AppDatabase,
    private val appContext: Context,
    private val workspace: com.androidharness.app.workspace.WorkspaceManager = com.androidharness.app.workspace.WorkspaceManager(appContext, db.dao()),
) {
    data class ExportOptions(
        val chatIds: Set<String>? = null,
        val memories: Boolean = true,
        val snippets: Boolean = true,
    )
    data class ExportResult(val sessions: Int, val messages: Int, val memories: Int = 0, val snippets: Int = 0)
    data class ImportResult(
        val sessions: Int,
        val messages: Int,
        val skipped: Int,
        val memories: Int = 0,
        val snippets: Int = 0,
        val copies: Int = 0,
    )

    private val dao get() = db.dao()

    /** Every chat, newest first, with message totals for the picker. */
    suspend fun chatsForExport(): List<BackupChatInfo> = withContext(Dispatchers.IO) {
        val counts = dao.messageCounts().associate { it.sessionId to it.count }
        dao.allSessions().map { it.info(counts[it.id] ?: 0) }.sortedByDescending { it.updatedAt }
    }

    suspend fun exportTo(
        uri: Uri,
        options: ExportOptions = ExportOptions(),
        onProgress: (BackupProgress) -> Unit = {},
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            write(uri, options, onProgress)
        } catch (e: Throwable) {
            // A half-written backup looks restorable but is not, so never leave one behind.
            withContext(NonCancellable) {
                runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, uri) }
            }
            if (e is OutOfMemoryError) {
                throw ChatBackupException("This phone ran out of memory. Try backing up fewer chats at a time.")
            }
            throw e
        }
    }

    private suspend fun write(uri: Uri, options: ExportOptions, onProgress: (BackupProgress) -> Unit): ExportResult {
        val counts = dao.messageCounts().associate { it.sessionId to it.count }
        val sessions = dao.allSessions().filter { options.chatIds == null || it.id in options.chatIds }
        val memories = if (options.memories) dao.allMemories().map { it.toBackupMemory() } else emptyList()
        val snippets = if (options.snippets) dao.allSnippets().map { it.toBackupSnippet() } else emptyList()
        val manifest = BackupManifest(
            exportedAt = System.currentTimeMillis(),
            chats = sessions.map { it.info(counts[it.id] ?: 0) },
            workspaces = dao.projectsFlow().first().map { BackupWorkspace(it.id, it.name) },
            memories = memories.size,
            snippets = snippets.size,
            memoryProjects = memories.filter { it.scope == MemoryScope.PROJECT.name }
                .mapNotNull { it.projectId?.takeIf(String::isNotBlank) }.distinct(),
        )
        val resolver = appContext.contentResolver
        val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() ?: resolver.openOutputStream(uri)
            ?: throw ChatBackupException("Could not open the file for writing")
        var messages = 0
        ChatBackupArchive.Writer(out).use { archive ->
            archive.manifest(manifest)
            sessions.forEachIndexed { index, session ->
                coroutineContext.ensureActive()
                onProgress(BackupProgress(index, sessions.size, session.title))
                val rows = dao.messages(session.id)
                messages += rows.size
                archive.chat(session.toBackup(rows))
            }
            onProgress(BackupProgress(sessions.size, sessions.size))
            if (memories.isNotEmpty()) archive.memories(memories)
            if (snippets.isNotEmpty()) archive.snippets(snippets)
        }
        return ExportResult(sessions.size, messages, memories.size, snippets.size)
    }

    suspend fun prepareImport(uri: Uri): ChatImportPreview = withContext(Dispatchers.IO) {
        val existing = dao.allSessionIds().toSet()
        if (isArchive(uri)) {
            val manifest = open(uri).use { ChatBackupArchive.readManifest(it) }
            ChatImportPreview(uri, manifest.chats.map { ImportChat(it, it.id in existing) }, manifest.workspaces,
                manifest.exportedAt, manifest.memories, manifest.snippets, manifest.memoryProjects)
        } else {
            val file = open(uri).use { ChatBackupCodec.decode(it) }
            ChatImportPreview(
                uri,
                file.sessions.map { ImportChat(it.info(), it.id in existing) },
                file.workspaces,
                file.exportedAt,
                file.memories.size,
                file.snippets.size,
                file.memories.filter { it.scope == MemoryScope.PROJECT.name }
                    .mapNotNull { it.projectId?.takeIf(String::isNotBlank) }.distinct(),
                legacy = true,
            )
        }
    }

    /** Workspaces the chosen chats (and their memories) point at that this phone does not have. */
    suspend fun missingWorkspaces(
        preview: ChatImportPreview,
        selected: Set<String>,
        policy: DuplicatePolicy,
        extras: Boolean,
    ): List<BackupWorkspace> = withContext(Dispatchers.IO) {
        val available = dao.projectsFlow().first().map { it.id }.toSet()
        val chats = preview.chats.filter { it.info.id in selected && (!it.exists || policy == DuplicatePolicy.KEEP_BOTH) }
        (chats.flatMap { ChatRestoreMapping.referenced(it.info.projectId, it.info.workspaceSelectionJson) } +
            if (extras) preview.memoryProjects else emptyList())
            .distinct().filterNot { it in available }
            .map { id ->
                BackupWorkspace(id, preview.workspaces.firstOrNull { it.id == id }?.name
                    ?: if (id == ChatRestoreMapping.UNASSIGNED) "Chats without a workspace" else
                        chats.firstOrNull { it.info.projectId == id }?.info?.title?.let { "Workspace for $it" } ?: "Restored workspace")
            }
    }

    suspend fun importPrepared(
        preview: ChatImportPreview,
        workspaceMapping: Map<String, String>,
        selected: Set<String> = preview.chats.map { it.info.id }.toSet(),
        policy: DuplicatePolicy = DuplicatePolicy.SKIP,
        extras: Boolean = true,
        onProgress: (BackupProgress) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        require(missingWorkspaces(preview, selected, policy, extras).all { !workspaceMapping[it.id].isNullOrBlank() }) {
            "Choose a workspace for each restored project before importing."
        }
        val job = coroutineContext[Job]
        val total = preview.chats.count { it.info.id in selected }
        var done = 0
        var sessions = 0
        var messages = 0
        var skipped = 0
        var copies = 0
        var memories = emptyList<BackupMemory>()
        var snippets = emptyList<BackupSnippet>()
        val available = dao.projectsFlow().first().map { it.id }.toSet()

        suspend fun restoreChat(incoming: BackupSession) {
            job?.ensureActive()
            if (incoming.id !in selected) return
            onProgress(BackupProgress(done, total, incoming.title))
            val exists = dao.session(incoming.id) != null
            if (exists && policy == DuplicatePolicy.SKIP) { skipped++; done++; return }
            val session = ChatRestoreMapping.remap(if (exists) incoming.asCopy() else incoming, workspaceMapping)
            require(ChatRestoreMapping.referenced(session).all { it in available }) {
                "A chosen workspace is no longer available. Review the restored projects again."
            }
            session.workspaceSelectionJson?.let {
                workspace.validateSelection(com.androidharness.app.workspace.ChatWorkspaceSelection.decode(it))
            }
            db.withTransaction {
                dao.insertSession(session.toEntity())
                session.messages.map { it.toEntity(session.id) }.takeIf { it.isNotEmpty() }
                    ?.chunked(500)?.forEach { dao.insertImportedMessages(it) }
            }
            sessions++
            messages += session.messages.size
            if (exists) copies++
            done++
        }

        val uri = preview.uri
        if (preview.legacy) {
            val file = open(uri).use { ChatBackupCodec.decode(it) }
            file.sessions.forEach { restoreChat(it) }
            memories = file.memories
            snippets = file.snippets
        } else open(uri).use { input ->
            ChatBackupArchive.read(input, object : ChatBackupArchive.Visitor {
                override suspend fun chat(session: BackupSession) = restoreChat(session)
                override suspend fun memories(found: List<BackupMemory>) { memories = found }
                override suspend fun snippets(found: List<BackupSnippet>) { snippets = found }
            })
        }
        onProgress(BackupProgress(total, total))

        var restoredMemories = 0
        var restoredSnippets = 0
        if (extras && (memories.isNotEmpty() || snippets.isNotEmpty())) db.withTransaction {
            val memoryPlan = MemoryBackupMerge.plan(dao.allMemories(), memories) { id -> id?.let { workspaceMapping[it] ?: it } }
            val now = System.currentTimeMillis()
            memoryPlan.forEach { dao.upsertMemory(MemoryBackupMerge.entity(it, now)) }
            restoredMemories = memoryPlan.size
            val snippetPlan = SnippetBackupMerge.plan(dao.allSnippets(), snippets)
            snippetPlan.forEach { dao.insertSnippet(SnippetBackupMerge.entity(it)) }
            restoredSnippets = snippetPlan.size
        }
        ImportResult(sessions, messages, skipped, restoredMemories, restoredSnippets, copies)
    }

    private fun open(uri: Uri): InputStream =
        appContext.contentResolver.openInputStream(uri) ?: throw ChatBackupException("Could not open the file")

    private fun isArchive(uri: Uri): Boolean = open(uri).use { input ->
        val header = ByteArray(4)
        var read = 0
        while (read < 4) { val n = input.read(header, read, 4 - read); if (n < 0) break; read += n }
        ChatBackupArchive.isArchive(header.copyOf(read))
    }
}

internal fun SessionEntity.info(messages: Int) = BackupChatInfo(id, title, createdAt, updatedAt, messages, projectId, workspaceSelectionJson)

internal fun BackupSession.info() = BackupChatInfo(id, title, createdAt, updatedAt, messages.size, projectId, workspaceSelectionJson)

/** A second copy beside a chat already on this phone: fresh ids, so nothing collides or overwrites. */
internal fun BackupSession.asCopy(): BackupSession {
    val ids = messages.associate { it.id to UUID.randomUUID().toString() }
    return copy(
        id = UUID.randomUUID().toString(),
        title = "$title (copy)",
        messages = messages.map { it.copy(id = ids.getValue(it.id)) },
    )
}

private fun SessionEntity.toBackup(rows: List<MessageEntity>) = BackupSession(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    totalInputTokens = totalInputTokens,
    totalOutputTokens = totalOutputTokens,
    totalCachedTokens = totalCachedTokens,
    totalCacheWriteTokens = totalCacheWriteTokens,
    requestCount = requestCount,
    lastInputTokens = lastInputTokens,
    projectId = projectId,
    compactionSummary = compactionSummary,
    compactionBefore = compactionBefore,
    modelSelectionJson = modelSelectionJson,
    workspaceSelectionJson = workspaceSelectionJson,
    conversationJson = conversationJson,
    messages = rows.map { m ->
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
            outputTokens = m.outputTokens,
            generationMs = m.generationMs,
            firstTokenMs = m.firstTokenMs,
            streamMs = m.streamMs,
            generationJson = m.generationJson,
            imagesJson = m.imagesJson,
            artifactJson = m.artifactJson,
            turnId = m.turnId,
            createdAt = m.createdAt,
        )
    },
)

private fun BackupSession.toEntity() = SessionEntity(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    totalInputTokens = totalInputTokens,
    totalOutputTokens = totalOutputTokens,
    totalCachedTokens = totalCachedTokens,
    totalCacheWriteTokens = totalCacheWriteTokens,
    requestCount = requestCount,
    lastInputTokens = lastInputTokens,
    projectId = projectId,
    compactionSummary = compactionSummary,
    compactionBefore = compactionBefore,
    modelSelectionJson = modelSelectionJson,
    workspaceSelectionJson = workspaceSelectionJson,
    conversationJson = conversationJson,
)

private fun BackupMessage.toEntity(sessionId: String) = MessageEntity(
    id = id,
    sessionId = sessionId,
    role = role,
    text = text,
    toolCallsJson = toolCallsJson,
    toolCallId = toolCallId,
    toolName = toolName,
    isError = isError,
    thinking = thinking,
    thinkingMs = thinkingMs,
    outputTokens = outputTokens,
    generationMs = generationMs,
    firstTokenMs = firstTokenMs,
    streamMs = streamMs,
    generationJson = generationJson,
    imagesJson = imagesJson,
    artifactJson = artifactJson,
    turnId = turnId,
    createdAt = createdAt,
)
