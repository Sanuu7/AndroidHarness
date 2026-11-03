package com.androidharness.app.data

import com.androidharness.app.data.db.MemoryEntity
import com.androidharness.app.data.db.SnippetEntity
import kotlinx.serialization.Serializable

/** One saved memory in a portable form, used by both backup files. */
@Serializable
data class BackupMemory(
    val id: String,
    val text: String,
    val category: String = "FACT",
    val scope: String = "GLOBAL",
    val projectId: String? = null,
    val sourceSessionId: String? = null,
    val pinned: Boolean = false,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val lastUsedAt: Long = 0,
    val useCount: Int = 0,
    val section: String = "SHARED",
    val consolidatedInto: String? = null,
    val isSummary: Boolean = false,
    val agentId: String? = null,
)

/** A saved prompt snippet, the chat's custom slash commands. */
@Serializable
data class BackupSnippet(val id: String, val name: String, val body: String)

internal fun MemoryEntity.toBackupMemory(): BackupMemory = BackupMemory(
    id = id,
    text = text,
    category = category,
    scope = scope,
    projectId = projectId,
    sourceSessionId = sourceSessionId,
    pinned = pinned,
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastUsedAt = lastUsedAt,
    useCount = useCount,
    section = section,
    consolidatedInto = consolidatedInto,
    isSummary = isSummary,
    agentId = agentId,
)

internal fun SnippetEntity.toBackupSnippet(): BackupSnippet = BackupSnippet(id, name, body)

/**
 * Imports never overwrite what is already on the device. Duplicates are
 * matched by id and by the same normalized fact in the same scope, so a
 * restored backup cannot double the memory list. A project memory whose
 * project is missing is restored as global instead of disappearing.
 */
internal object MemoryBackupMerge {
    private const val MAX_ID = 200

    fun plan(
        existing: List<MemoryEntity>,
        incoming: List<BackupMemory>,
        remapProject: (String?) -> String? = { it },
    ): List<BackupMemory> {
        val ids = existing.mapTo(mutableSetOf()) { it.id }
        val facts = existing.mapTo(mutableSetOf()) { key(it.text, it.scope, it.projectId, it.section, it.consolidatedInto, it.isSummary, it.agentId) }
        val plan = incoming.mapNotNull { normalize(it, remapProject) }
            .filter { entry ->
                val fact = key(entry.text, entry.scope, entry.projectId, entry.section, entry.consolidatedInto, entry.isSummary, entry.agentId)
                if (entry.id in ids || fact in facts) false else { ids += entry.id; facts += fact; true }
            }
        val summaries = (existing.filter { it.isSummary } .map { it.id } + plan.filter { it.isSummary }.map { it.id }).toSet()
        return plan.map { it.copy(consolidatedInto = it.consolidatedInto?.takeIf { id -> id in summaries && id != it.id }) }
    }

    fun normalize(entry: BackupMemory, remapProject: (String?) -> String? = { it }): BackupMemory? {
        if (entry.agentId != null && (entry.agentId.length !in 1..100 || entry.section != "CHAT")) return null
        if (entry.section !in MemorySection.entries.map { it.name }) return null
        // Saved entries can outlive a lower character limit; restoring must never shorten them.
        val text = if (entry.isSummary) entry.text.trim().takeIf { it.isNotBlank() }
            else MemoryText.normalize(entry.text)
        text ?: return null
        if (entry.id.isBlank() || entry.id.length > MAX_ID) return null
        val projectId = remapProject(entry.projectId)?.takeIf { it.isNotBlank() }
        val scope = if (entry.scope == MemoryScope.PROJECT.name && projectId != null) {
            MemoryScope.PROJECT.name
        } else MemoryScope.GLOBAL.name
        return entry.copy(
            text = text,
            category = MemoryCategory.fromStored(entry.category).name,
            scope = scope,
            projectId = projectId.takeIf { scope == MemoryScope.PROJECT.name },
            section = MemorySection.fromStored(entry.section).name,
            lastUsedAt = entry.lastUsedAt.coerceAtLeast(0),
            useCount = entry.useCount.coerceAtLeast(0),
        )
    }

    fun entity(entry: BackupMemory, now: Long = System.currentTimeMillis()): MemoryEntity = MemoryEntity(
        id = entry.id,
        text = entry.text,
        category = entry.category,
        scope = entry.scope,
        projectId = entry.projectId,
        sourceSessionId = entry.sourceSessionId,
        pinned = entry.pinned,
        enabled = entry.enabled,
        createdAt = entry.createdAt.takeIf { it > 0 } ?: now,
        updatedAt = entry.updatedAt.takeIf { it > 0 } ?: entry.createdAt.takeIf { it > 0 } ?: now,
        lastUsedAt = entry.lastUsedAt,
        useCount = entry.useCount,
        section = entry.section,
        consolidatedInto = entry.consolidatedInto,
        isSummary = entry.isSummary,
        agentId = entry.agentId,
    )

    private fun key(text: String, scope: String, projectId: String?, section: String, parent: String?, summary: Boolean, agentId: String?): String =
        MemoryMatch.key(text) + "\u0000" + scope + "\u0000" + projectId.orEmpty() + "\u0000" + section + "\u0000" + parent.orEmpty() + "\u0000" + summary + "\u0000" + agentId.orEmpty()

}

/** Snippets are matched by id and by name, since a name is the slash command. */
internal object SnippetBackupMerge {
    private const val MAX_NAME = 200
    private const val MAX_BODY = 100_000

    fun plan(existing: List<SnippetEntity>, incoming: List<BackupSnippet>): List<BackupSnippet> {
        val ids = existing.mapTo(mutableSetOf()) { it.id }
        val names = existing.mapTo(mutableSetOf()) { it.name.trim().lowercase() }
        return incoming.mapNotNull(::normalize).filter { ids.add(it.id) && names.add(it.name.lowercase()) }
    }

    fun normalize(entry: BackupSnippet): BackupSnippet? {
        val name = entry.name.trim()
        val body = entry.body.trim()
        if (entry.id.isBlank() || entry.id.length > MAX_NAME) return null
        if (name.length !in 1..MAX_NAME || body.length !in 1..MAX_BODY) return null
        return BackupSnippet(entry.id, name, body)
    }

    fun entity(entry: BackupSnippet): SnippetEntity = SnippetEntity(entry.id, entry.name, entry.body)
}
