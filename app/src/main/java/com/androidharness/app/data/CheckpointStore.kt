package com.androidharness.app.data

import com.androidharness.app.data.db.CheckpointEntity
import com.androidharness.app.data.db.HarnessDao
import com.androidharness.app.data.db.TurnFirstPojo
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

    /** Checkpointed turns in chronological order (earliest snapshot first). */
    suspend fun turnsOrdered(sessionId: String): List<TurnFirstPojo> =
        dao.checkpointTurnsOrdered(sessionId)

    /** All checkpoints for the given turns (any order). */
    suspend fun entitiesForTurns(sessionId: String, turnIds: List<String>): List<CheckpointEntity> =
        turnIds.flatMap { dao.checkpointsForTurn(sessionId, it) }

    /**
     * Restore the workspace to its state before [turnId]: replay that turn's
     * snapshots, then drop them. Returns how many paths restored cleanly and
     * how many failed (failures were previously silent — the UI now reports
     * them so "undo" can't quietly lie). [RewindResult.paths] lists every
     * restored relPath so callers can refresh derived state.
     */
    suspend fun rewind(sessionId: String, turnId: String, workspace: WorkspaceFs): RewindResult {
        val checkpoints = dao.checkpointsForTurn(sessionId, turnId)
        var restored = 0
        var failed = 0
        val paths = LinkedHashSet<String>()
        checkpoints.forEach { cp ->
            val ok = runCatching {
                val node = workspace.resolve(cp.relPath)
                when {
                    !cp.existedBefore -> if (node.exists) node.delete()
                    !cp.wasDirectory -> {
                        val text = String(android.util.Base64.decode(cp.contentB64, android.util.Base64.NO_WRAP))
                        node.writeText(text)
                    }
                    // Directory the agent removed comes back (empty).
                    cp.wasDirectory && !node.exists -> node.mkdirs()
                }
            }.isSuccess
            if (ok) {
                restored++
                paths += cp.relPath
            } else {
                failed++
            }
        }
        dao.deleteCheckpoints(sessionId, turnId)
        return RewindResult(restored, failed, paths)
    }

    data class RewindResult(
        val restored: Int,
        val failed: Int,
        val paths: Set<String> = emptySet(),
    )

    suspend fun clearSession(sessionId: String) {
        dao.deleteCheckpoints(sessionId)
    }
}
