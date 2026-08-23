package com.androidharness.app.data

import com.androidharness.app.data.db.CheckpointEntity
import com.androidharness.app.data.db.HarnessDao
import com.androidharness.app.workspace.WorkspaceFs
import java.util.UUID

/**
 * Stores pre-modification file snapshots so a turn can be rewound.
 * Snapshots are text-based (what the agent reads/writes is text).
 */
class CheckpointStore(private val dao: HarnessDao) {

    /** Capture the "before" state of [relPath] in [sessionId]'s workspace. */
    suspend fun snapshot(
        sessionId: String,
        turnId: String,
        workspace: WorkspaceFs,
        relPath: String,
    ) {
        // one snapshot per (turn, path) — keep the earliest (pre-turn) state
        val existing = dao.checkpointsForTurn(sessionId, turnId)
        if (existing.any { it.relPath == relPath }) return

        val node = workspace.resolve(relPath)
        dao.insertCheckpoint(
            CheckpointEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                turnId = turnId,
                relPath = relPath,
                contentB64 = if (node.exists && node.isFile) {
                    android.util.Base64.encodeToString(
                        node.readText().toByteArray(),
                        android.util.Base64.NO_WRAP,
                    )
                } else "",
                existedBefore = node.exists,
                wasDirectory = node.isDirectory,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun turnsWithCheckpoints(sessionId: String): Set<String> =
        dao.turnsWithCheckpoints(sessionId).toSet()

    /**
     * Restore the workspace to its state before [turnId]: replay that turn's
     * snapshots in reverse, then drop them.
     */
    suspend fun rewind(sessionId: String, turnId: String, workspace: WorkspaceFs): Int {
        val checkpoints = dao.checkpointsForTurn(sessionId, turnId)
        checkpoints.forEach { cp ->
            runCatching {
                val node = workspace.resolve(cp.relPath)
                if (!cp.existedBefore) {
                    if (node.exists) node.delete()
                } else if (!cp.wasDirectory) {
                    val text = String(android.util.Base64.decode(cp.contentB64, android.util.Base64.NO_WRAP))
                    node.writeText(text)
                }
            }
        }
        dao.deleteCheckpoints(sessionId, turnId)
        return checkpoints.size
    }

    suspend fun clearSession(sessionId: String) {
        dao.deleteCheckpoints(sessionId)
    }
}
