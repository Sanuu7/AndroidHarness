package com.androidharness.app.data

import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.MemoryEntity
import com.androidharness.app.data.db.MemoryFolderEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID
import androidx.room.withTransaction

enum class MemoryScope {
    GLOBAL, PROJECT;

    companion object {
        fun fromStored(value: String?): MemoryScope = entries.firstOrNull { it.name == value } ?: GLOBAL
    }
}

enum class MemoryCategory {
    PROFILE, PREFERENCE, PROJECT, DECISION, FACT;

    companion object {
        fun fromStored(value: String?): MemoryCategory =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: FACT
    }
}

internal object MemoryText {
    const val DEFAULT_MAX_ENTRY_CHARS = 600

    fun normalize(raw: String): String? {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        return text.takeIf { it.isNotEmpty() }
    }

    fun maxChars(settings: AppSettings, summary: Boolean = false): Int? {
        if (settings.memoryEntryUnlimited) return null
        val limit = settings.memoryEntryMaxChars.takeIf { it > 0 } ?: DEFAULT_MAX_ENTRY_CHARS
        // Organized memories retain their existing allowance when a smaller entry limit is selected.
        return if (summary) maxOf(limit, MemoryConsolidation.MAX_CHARS) else limit
    }

    fun checked(raw: String, settings: AppSettings, summary: Boolean = false): String {
        val text = if (summary) raw.trim() else normalize(raw).orEmpty()
        require(text.isNotBlank()) { "Enter a memory." }
        val limit = maxChars(settings, summary)
        require(limit == null || text.length <= limit) {
            "Memory is limited to $limit characters. Shorten it or change the limit in Settings > Memory."
        }
        return text
    }

    /** A bounded preview; the complete saved entry remains available through recall(memory_id). */
    fun excerpt(text: String, maxChars: Int, query: String = ""): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        if (maxChars < 3) return text.take(maxChars)
        val match = MemoryMatch.tokens(query).mapNotNull { token ->
            text.indexOf(token, ignoreCase = true).takeIf { it >= 0 }
        }.minOrNull() ?: 0
        val length = maxChars - 2
        val start = (match - length / 3).coerceIn(0, text.length - length)
        return (if (start > 0) "…" else "") + text.substring(start, start + length) +
            (if (start + length < text.length) "…" else "")
    }
}

/**
 * Builds the small memory block injected into a request. Individual entries are
 * kept for accuracy; only the highest-value ones enter the prompt, so a long
 * memory list cannot quietly turn into a second system prompt.
 */
internal object MemoryPrompt {
    const val MAX_CHARS = 5_000
    const val MIN_CHARS = 1_500
    const val LARGE_MAX_CHARS = 24_000

    /**
     * [matchedIds] are the supplied entries that matched the recent messages by
     * trigger word or by shared words. Only those count as used.
     */
    data class Block(val text: String, val usedIds: List<String>, val omitted: Int, val supplied: List<MemoryEntity> = emptyList(), val matchedIds: List<String> = emptyList())

    /**
     * Characters of memory per request for a model with [contextTokens] of
     * context: about 1.5% of a large window, never more than 24,000
     * characters. Windows under 32K tokens get a smaller share so memory
     * cannot crowd out the conversation on small local models.
     */
    fun budgetFor(contextTokens: Int?): Int {
        val tokens = contextTokens?.takeIf { it > 0 } ?: return MAX_CHARS
        return if (tokens < 32_000) (tokens * 0.15).toInt().coerceIn(MIN_CHARS, MAX_CHARS)
            else (tokens * 0.06).toInt().coerceIn(MAX_CHARS, LARGE_MAX_CHARS)
    }

    fun ranking(query: String, now: Long): Comparator<MemoryEntity> =
        compareByDescending<MemoryEntity> { it.pinned }
            .thenByDescending { MemoryKeywords.hit(it.keywords, query) }
            .thenByDescending { MemoryMatch.relevance(it.text, query) }
            .thenByDescending { it.category in setOf("PROFILE", "PREFERENCE") }
            .thenByDescending { it.scope == MemoryScope.PROJECT.name }
            // Within a tier, memories that keep proving useful stay ahead and unused ones fade.
            .thenByDescending { MemoryUse.score(it, now) }
            .thenByDescending { it.updatedAt }
            .thenBy { it.id }

    fun build(memories: List<MemoryEntity>, maxChars: Int = MAX_CHARS, query: String = "", now: Long = System.currentTimeMillis()): Block {
        val sorted = memories.filter { it.enabled && it.consolidatedInto == null && (!it.triggerOnly || MemoryKeywords.hit(it.keywords, query)) }
            .sortedWith(ranking(query, now))
        val text = StringBuilder()
        val used = mutableListOf<String>()
        val supplied = mutableListOf<MemoryEntity>()
        val matched = mutableListOf<String>()
        var omitted = 0
        for (memory in sorted) {
            val label = "- [id:${memory.id} ${MemoryFolders.label(memory)}"
            val longEntry = !memory.isSummary && memory.text.length > maxChars - label.length - 3
            val prefix = "$label${if (memory.isSummary) "; digest, recall(memory_id) for full text" else if (longEntry) "; excerpt, recall(memory_id) for full text" else ""}] "
            val content = if (memory.isSummary) {
                // Select facts from a large digest instead of dropping the whole digest.
                memory.text.lines().filter { it.isNotBlank() && !it.endsWith(":") }.sortedByDescending {
                    MemoryMatch.relevance(it, query)
                }.fold("") { result, line ->
                    if (text.length + prefix.length + result.length + line.length + 2 <= maxChars)
                        result + line + "\n" else result
                }.trim()
            } else if (longEntry) MemoryText.excerpt(memory.text, (maxChars - prefix.length - 1).coerceAtLeast(0), query)
                else memory.text
            val line = prefix + content + "\n"
            if (content.isBlank() || text.length + line.length > maxChars) { omitted++; continue }
            text.append(line)
            used += memory.id
            supplied += memory.copy(text = content)
            if (MemoryKeywords.hit(memory.keywords, query) || MemoryMatch.relevance(memory.text, query) > 0) matched += memory.id
        }
        if (omitted > 0) {
            val note = "- $omitted more saved memories: recall(query).\n"
            if (text.length + note.length <= maxChars) text.append(note)
        }
        return Block(text.toString().trim(), used, omitted, supplied, matched)
    }

    fun approxTokens(chars: Int): Int = (chars / 4).coerceAtLeast(1)
}

/**
 * App-owned Chat, Code and legacy Shared memories. It is independent of providers and
 * of the workspace files used for project notes.
 */
class MemoryRepository(private val db: AppDatabase, private val settings: SettingsRepository) {
    private val dao get() = db.dao()

    val memories: Flow<List<MemoryEntity>> get() = dao.memoriesFlow()

    /** Folders the user or agent created. Folders only named by a memory (from a backup) are added by the UI. */
    val folders: Flow<List<MemoryFolderEntity>> get() = dao.memoryFoldersFlow()

    /** Every memory in backup form, each carrying its folder's agent limit. */
    suspend fun backup(): List<BackupMemory> = dao.allMemories().toBackupMemories(dao.memoryFolders())

    suspend fun sectionForSession(sessionId: String?): MemorySection =
        if (sessionId != null && ConversationConfig.decode(dao.session(sessionId)?.conversationJson).isChat) MemorySection.CHAT else MemorySection.CODE

    private suspend fun agentForSession(sessionId: String?): ChatAgentProfile? = sessionId?.let {
        ConversationConfig.decode(dao.session(it)?.conversationJson).takeIf { config -> config.isChat }?.agent
    }

    private suspend fun agentOwner(sessionId: String?): String? = agentForSession(sessionId)?.takeIf { it.memory == AgentMemory.PRIVATE }?.id

    /** Who is looking at memory in a conversation: the section, a private-memory owner, the chat agent and the folder limits. */
    private inner class Viewer(val section: MemorySection, val owner: String?, val agent: String?, val limits: Map<String, Set<String>>) {
        fun sees(memory: MemoryEntity, target: MemorySection = section) = MemoryAccess.belongsTo(memory, target, owner, agent, limits)

        /** Refuses a write into a folder this conversation cannot see. */
        fun checkFolder(folder: String?) {
            val allowed = MemoryFolderAgents.allowed(folder, limits) ?: return
            check(agent != null && agent in allowed) { "The folder \"$folder\" is limited to other agents. Choose another folder." }
        }
    }

    private suspend fun viewer(sessionId: String?): Viewer = Viewer(
        sectionForSession(sessionId), agentOwner(sessionId), agentForSession(sessionId)?.id, MemoryFolderAgents.of(dao.memoryFolders()))

    suspend fun isEnabled(sessionId: String? = null): Boolean =
        agentForSession(sessionId)?.memory != AgentMemory.OFF &&
        (sessionId == null || ConversationConfig.decode(dao.session(sessionId)?.conversationJson).personalizationEnabled) &&
            MemoryAccess.enabled(settings.settings.first(), sectionForSession(sessionId))

    suspend fun projectIdForSession(sessionId: String?): String? = if (agentOwner(sessionId) != null) null else sessionId?.takeIf { it.isNotBlank() }?.let { dao.projectIdForSession(it) }

    /** Always supplies own/shared memories. Other-section lookup requires an explicit recall request. */
    suspend fun promptBlock(
        sessionId: String?,
        query: String = "",
        onUsed: (List<MemoryEntity>) -> Unit = {},
        maxChars: Int = MemoryPrompt.MAX_CHARS,
        countUse: Boolean = true,
    ): String? {
        onUsed(emptyList())
        if (!isEnabled(sessionId)) return null
        val viewer = viewer(sessionId)
        val available = dao.activeMemories(projectIdForSession(sessionId)).filter { viewer.sees(it) }
        val block = MemoryPrompt.build(available, maxChars = maxChars, query = query)
        if (block.text.isBlank()) return null
        onUsed(block.supplied)
        // Only entries that matched the conversation count as used, so an entry
        // that merely fits the budget does not keep itself ranked high.
        if (countUse && block.matchedIds.isNotEmpty()) dao.markMemoriesUsed(block.matchedIds, System.currentTimeMillis())
        return block.text
    }

    /** Creates a folder, or returns the existing one with the same name in any case. */
    suspend fun createFolder(raw: String): String = db.withTransaction {
        val name = MemoryFolders.checkedName(raw)
        val existing = folderNames()
        existing.firstOrNull { MemoryFolders.same(it, name) }?.let { return@withTransaction it }
        check(existing.size < MemoryFolders.MAX_FOLDERS) { "You can have up to ${MemoryFolders.MAX_FOLDERS} folders." }
        dao.insertMemoryFolder(MemoryFolderEntity(name, System.currentTimeMillis()))
        name
    }

    suspend fun renameFolder(old: String, raw: String): String = db.withTransaction {
        val name = MemoryFolders.checkedName(raw)
        check(folderNames().none { MemoryFolders.same(it, name) && !MemoryFolders.same(it, old) }) { "A folder named \"$name\" already exists." }
        val row = dao.memoryFolders().firstOrNull { it.name == old }
        dao.deleteMemoryFolder(old)
        dao.insertMemoryFolder(MemoryFolderEntity(name, row?.createdAt ?: System.currentTimeMillis(), row?.agentIds.orEmpty()))
        dao.renameMemoryFolder(old, name, System.currentTimeMillis())
        name
    }

    /** Everything needed to undo a folder deletion exactly. */
    data class FolderDeletion(val folder: MemoryFolderEntity, val before: List<MemoryEntity>, val moved: Boolean)

    /**
     * Deletes a folder. With [moveTo] its memories move there; without it they
     * are deleted too. Either way [restoreFolder] puts everything back.
     */
    suspend fun deleteFolder(name: String, moveTo: MemoryFolderTarget?): FolderDeletion = db.withTransaction {
        val all = dao.allMemories()
        val inFolder = all.filter { it.folder == name }
        val children = all.filter { child -> child.folder != name && inFolder.any { it.isSummary && child.consolidatedInto == it.id } }
        val row = dao.memoryFolders().firstOrNull { it.name == name } ?: MemoryFolderEntity(name, inFolder.minOfOrNull { it.createdAt } ?: System.currentTimeMillis())
        val destination = moveTo?.let { target -> target.folder?.let { target.copy(folder = createFolder(it)) } ?: target }
        check(destination?.folder == null || !MemoryFolders.same(destination.folder, name)) { "Choose a different folder to move these memories to." }
        val now = System.currentTimeMillis()
        if (destination != null) {
            inFolder.forEach { dao.upsertMemory(it.copy(folder = destination.folder, category = destination.category.name, updatedAt = now)) }
        } else {
            (inFolder + children).forEach { dao.deleteMemory(it.id) }
        }
        dao.deleteMemoryFolder(name)
        FolderDeletion(row, if (destination != null) inFolder else inFolder + children, moved = destination != null)
    }

    suspend fun restoreFolder(deletion: FolderDeletion) = db.withTransaction {
        dao.insertMemoryFolder(deletion.folder)
        deletion.before.forEach { dao.upsertMemory(it) }
    }

    /** Limits a folder to [agentIds]; an empty set opens it to everyone again. */
    suspend fun setFolderAgents(name: String, agentIds: Set<String>) = db.withTransaction {
        check(folderNames().any { it == name }) { "This folder was deleted." }
        dao.insertMemoryFolder(MemoryFolderEntity(name, System.currentTimeMillis()))
        dao.setMemoryFolderAgents(name, MemoryFolderAgents.encode(agentIds))
    }

    private suspend fun folderNames(): List<String> =
        (dao.memoryFolders().map { it.name } + dao.allMemories().mapNotNull { it.folder }).distinctBy { MemoryMatch.key(it) }

    /** Built-in targets pass through; a custom folder is created when missing. Returns the target and whether it was new. */
    private suspend fun ensure(target: MemoryFolderTarget): Pair<MemoryFolderTarget, String?> {
        val folder = target.folder ?: return target to null
        val known = folderNames().firstOrNull { MemoryFolders.same(it, folder) }
        return if (known != null) target.copy(folder = known) to null else target.copy(folder = createFolder(folder)).let { it to it.folder }
    }

    enum class Action { SAVED, UPDATED, UNCHANGED }
    data class WriteResult(val memory: MemoryEntity, val action: Action, val createdFolder: String? = null)

    suspend fun remember(text: String, category: MemoryCategory, requestedScope: MemoryScope, sessionId: String?): MemoryEntity? =
        if (text.isBlank()) null else rememberResult(text, category, requestedScope, sessionId).memory

    suspend fun rememberResult(
        text: String, category: MemoryCategory?, requestedScope: MemoryScope, sessionId: String?,
        memoryId: String? = null, previousText: String? = null, allowSimilar: Boolean = false,
        folder: String? = null, keywords: String? = null, triggerOnly: Boolean? = null,
    ): WriteResult {
        check(isEnabled(sessionId)) { "Memory is off for this section." }
        val viewer = viewer(sessionId)
        val section = viewer.section
        val owner = viewer.owner
        val project = projectIdForSession(sessionId)
        return db.withTransaction {
            val available = dao.allMemories().filter { it.consolidatedInto == null && viewer.sees(it) &&
                (it.scope == MemoryScope.GLOBAL.name || it.projectId == project && project != null) }
            if (memoryId != null) {
                val current = available.firstOrNull { it.id == memoryId } ?: error("Memory is not available in this section/project. Recall it first.")
                val clean = checkedText(text, current.isSummary)
                check(previousText != null && previousText == current.text) { "Memory changed or previous_text is missing. Recall it again before updating." }
                check(current.enabled) { "This memory is disabled. Enable it in Settings first." }
                val conflict = available.firstOrNull { it.id != current.id && it.scope == current.scope && it.projectId == current.projectId &&
                    MemoryMatch.key(it.text) == MemoryMatch.key(clean) }
                check(conflict == null) { "The replacement already exists as memory ${conflict?.id}." }
                val (target, created) = if (folder != null) ensure(MemoryFolders.target(folder, category ?: MemoryCategory.fromStored(current.category)))
                    else MemoryFolderTarget(category ?: MemoryCategory.fromStored(current.category), current.folder.takeIf { category == null }) to null
                if (!MemoryFolders.same(target.folder, current.folder)) viewer.checkFolder(target.folder)
                val words = keywords?.let { MemoryKeywords.checked(it) } ?: current.keywords
                val gated = checkedTriggerOnly(triggerOnly ?: current.triggerOnly, words)
                if (current.text == clean && current.category == target.category.name && current.folder == target.folder && current.keywords == words &&
                    current.triggerOnly == gated) return@withTransaction WriteResult(current, Action.UNCHANGED)
                val updated = current.copy(text = clean, category = target.category.name, folder = target.folder, keywords = words, triggerOnly = gated,
                    updatedAt = System.currentTimeMillis())
                dao.upsertMemory(updated)
                WriteResult(updated, Action.UPDATED, created)
            } else {
                val clean = checkedText(text)
                val scope = if (requestedScope == MemoryScope.PROJECT && project != null) MemoryScope.PROJECT else MemoryScope.GLOBAL
                val projectId = project.takeIf { scope == MemoryScope.PROJECT }
                val candidates = available.filter { !it.isSummary && (scope == MemoryScope.PROJECT || it.scope == MemoryScope.GLOBAL.name) }
                val duplicate = candidates.firstOrNull { MemoryMatch.key(it.text) == MemoryMatch.key(clean) }
                if (duplicate != null) {
                    check(duplicate.enabled) { "This fact already exists as a disabled memory. Enable it in Settings before using it again." }
                    return@withTransaction WriteResult(duplicate, Action.UNCHANGED)
                }
                if (!allowSimilar) {
                    val near = candidates.firstOrNull { MemoryMatch.key(it.text) != MemoryMatch.key(clean) && MemoryMatch.similar(it.text, clean) }
                    check(near == null) { "Similar memory [id:${near?.id}]: ${near?.text}. Recall it and update by memory_id, or set allow_similar only if these are distinct facts." }
                }
                val words = MemoryKeywords.checked(keywords)
                val gated = checkedTriggerOnly(triggerOnly == true, words)
                viewer.checkFolder(MemoryFolders.target(folder, category ?: MemoryCategory.FACT).folder)
                val (target, created) = ensure(MemoryFolders.target(folder, category ?: MemoryCategory.FACT))
                save(clean, target, scope, projectId, sessionId, section, owner, words, gated).copy(createdFolder = created)
            }
        }
    }

    suspend fun add(
        text: String, category: MemoryCategory, requestedScope: MemoryScope, projectId: String?, section: MemorySection = MemorySection.SHARED,
        folder: String? = null, keywords: String = "", triggerOnly: Boolean = false,
    ): MemoryEntity? =
        if (text.isBlank()) null else db.withTransaction {
            val words = MemoryKeywords.checked(keywords)
            save(checkedText(text), ensure(MemoryFolderTarget(category, folder)).first, requestedScope, projectId, null, section, keywords = words,
                triggerOnly = checkedTriggerOnly(triggerOnly, words)).memory
        }

    private suspend fun save(
        text: String, target: MemoryFolderTarget, requestedScope: MemoryScope, projectId: String?, sessionId: String?, section: MemorySection,
        owner: String? = null, keywords: String = "", triggerOnly: Boolean = false,
    ): WriteResult {
        val scope = if (requestedScope == MemoryScope.PROJECT && projectId != null) MemoryScope.PROJECT else MemoryScope.GLOBAL
        val actualProject = projectId.takeIf { scope == MemoryScope.PROJECT }
        val existing = dao.allMemories().firstOrNull { it.consolidatedInto == null && !it.isSummary && it.scope == scope.name &&
            it.projectId == actualProject && MemoryAccess.belongsTo(it, section, owner) && MemoryMatch.key(it.text) == MemoryMatch.key(text) }
        if (existing != null) {
            check(existing.enabled) { "This fact already exists as a disabled memory. Enable it in Settings before using it again." }
            return WriteResult(existing, Action.UNCHANGED)
        }
        val now = System.currentTimeMillis()
        val memory = MemoryEntity(UUID.randomUUID().toString(), text, target.category.name, scope.name, actualProject,
            sessionId, createdAt = now, updatedAt = now, section = section.name, agentId = owner, folder = target.folder, keywords = keywords, triggerOnly = triggerOnly)
        dao.upsertMemory(memory)
        trim()
        return WriteResult(memory, Action.SAVED)
    }

    private suspend fun checkedText(raw: String, summary: Boolean = false): String =
        MemoryText.checked(raw, settings.settings.first(), summary)

    /** A trigger-only memory with no trigger words could never be supplied. */
    private fun checkedTriggerOnly(triggerOnly: Boolean, keywords: String): Boolean {
        require(!triggerOnly || keywords.isNotBlank()) { "Add at least one trigger word, or turn off \"Only when triggered\"." }
        return triggerOnly
    }

    suspend fun update(
        memory: MemoryEntity, text: String, category: MemoryCategory, scope: MemoryScope, projectId: String?,
        section: MemorySection = MemorySection.fromStored(memory.section), folder: String? = memory.folder, keywords: String = memory.keywords,
        triggerOnly: Boolean = memory.triggerOnly,
    ) = db.withTransaction {
        val current = dao.memory(memory.id) ?: error("This memory was deleted.")
        check(current.updatedAt == memory.updatedAt && current.text == memory.text && current.consolidatedInto == null) { "This memory changed. Reopen it before saving." }
        check(current.agentId == null || section == MemorySection.CHAT && scope == MemoryScope.GLOBAL) { "Private agent memories stay in this agent." }
        val clean = checkedText(text, current.isSummary)
        val actualProject = projectId.takeIf { scope == MemoryScope.PROJECT }
        check(dao.allMemories().none { it.id != memory.id && it.consolidatedInto == null && it.section == section.name && it.agentId == current.agentId &&
            it.scope == scope.name && it.projectId == actualProject && MemoryMatch.key(it.text) == MemoryMatch.key(clean) }) { "This memory already exists." }
        val words = MemoryKeywords.checked(keywords)
        val gated = checkedTriggerOnly(triggerOnly, words)
        val target = ensure(MemoryFolderTarget(category, folder)).first
        dao.upsertMemory(current.copy(text = clean, category = target.category.name, scope = if (actualProject != null) "PROJECT" else "GLOBAL",
            projectId = actualProject, section = section.name, folder = target.folder, keywords = words, triggerOnly = gated, updatedAt = System.currentTimeMillis()))
    }

    suspend fun consolidate(plan: MemoryConsolidation, reviewedText: String): MemoryEntity = db.withTransaction {
        val current = plan.originals.map { original ->
            val latest = dao.memory(original.id)
            check(latest != null && latest.copy(lastUsedAt = original.lastUsedAt, useCount = original.useCount) == original && latest.consolidatedInto == null && latest.enabled) { "Memories changed. Review the consolidation again." }
            latest
        }
        check(current.size >= 2 && current.map { listOf(it.section, it.scope, it.projectId, it.agentId) }.distinct().size == 1) { "Choose memories from one section and scope." }
        val text = checkedText(reviewedText, summary = true)
        val now = System.currentTimeMillis()
        // A digest of one folder stays in that folder; a mixed digest goes to Facts.
        val place = current.map { MemoryFolderTarget.of(it) }.distinct().singleOrNull() ?: MemoryFolderTarget(MemoryCategory.FACT, null)
        val summary = MemoryEntity(UUID.randomUUID().toString(), text, category = place.category.name, scope = plan.scope.name, projectId = plan.projectId,
            createdAt = now, updatedAt = now, pinned = current.any { it.pinned }, section = plan.section.name, isSummary = true, agentId = plan.agentId,
            folder = place.folder, keywords = MemoryKeywords.parse(current.joinToString(",") { it.keywords }).take(MemoryKeywords.MAX_KEYWORDS).joinToString(", "),
            triggerOnly = current.all { it.triggerOnly && it.keywords.isNotBlank() })
        dao.upsertMemory(summary)
        dao.consolidateMemories(current.map { it.id }, summary.id)
        summary
    }

    suspend fun restoreOriginals(summaryId: String) = db.withTransaction {
        val summary = dao.memory(summaryId)
        check(summary?.isSummary == true) { "This consolidation is no longer available." }
        val originals = dao.allMemories().filter { it.consolidatedInto == summaryId }
        check(originals.isNotEmpty()) { "The original entries have already been restored." }
        val initialText = MemoryConsolidation.plans(originals.map { it.copy(consolidatedInto = null) }).singleOrNull()?.text
        dao.restoreConsolidatedMemories(summaryId)
        if (summary.text == initialText) dao.deleteMemory(summaryId)
        else dao.upsertMemory(summary.copy(enabled = false)) // Keep later edits available for review.
    }

    suspend fun remove(id: String) = db.withTransaction {
        val current = dao.memory(id)
        check(current?.consolidatedInto == null) { "Restore the original entries before deleting one of them." }
        if (current?.isSummary == true) {
            dao.allMemories().filter { it.consolidatedInto == id }.forEach { dao.deleteMemory(it.id) }
        }
        dao.deleteMemory(id)
    }

    /**
     * Moves [ids] into [target], creating a custom folder when it is new.
     * A memory moved into a custom folder keeps its category, so moving it
     * back out later lands it where it came from. Returns how many moved.
     */
    suspend fun moveMemories(ids: Collection<String>, target: MemoryFolderTarget): Int = db.withTransaction {
        val place = ensure(target).first
        val now = System.currentTimeMillis()
        var moved = 0
        ids.distinct().forEach { id ->
            val current = dao.memory(id) ?: return@forEach
            if (current.consolidatedInto != null) return@forEach
            val category = if (place.folder != null) current.category else place.category.name
            if (current.folder == place.folder && current.category == category) return@forEach
            dao.upsertMemory(current.copy(folder = place.folder, category = category, updatedAt = now))
            moved++
        }
        moved
    }

    /** Deletes several memories at once. A digest takes its originals with it, as [remove] does. */
    suspend fun removeMany(ids: Collection<String>) = db.withTransaction {
        ids.distinct().forEach { id -> if (dao.memory(id)?.consolidatedInto == null) remove(id) }
    }

    suspend fun clearAll() = dao.deleteAllMemories()

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setMemoryEnabled(id, enabled, System.currentTimeMillis())
        if (enabled) trim()
    }

    suspend fun setPinned(id: String, pinned: Boolean) = dao.setMemoryPinned(id, pinned, System.currentTimeMillis())

    /**
     * Adds memories from a backup without touching entries already present.
     * [remapProject] lets a chat restore point a project memory at its mapped
     * workspace, and lets a settings restore drop a project that no longer
     * exists (the entry is kept as global).
     */
    suspend fun importMissing(
        incoming: List<BackupMemory>,
        remapProject: (String?) -> String? = { it },
    ): Int {
        val plan = MemoryBackupMerge.plan(dao.allMemories(), incoming, remapProject)
        if (plan.isEmpty()) return 0
        val now = System.currentTimeMillis()
        db.withTransaction {
            // Reuse an existing folder's spelling so "termux" never sits beside "Termux".
            val known = folderNames()
            MemoryBackupMerge.newFolders(plan, known, now).forEach { dao.insertMemoryFolder(it) }
            plan.forEach { entry ->
                val folder = entry.folder?.let { name -> known.firstOrNull { MemoryFolders.same(it, name) } ?: name }
                dao.upsertMemory(MemoryBackupMerge.entity(entry.copy(folder = folder), now))
            }
        }
        return plan.size
    }

    suspend fun recallById(id: String, sessionId: String?, requestedSection: MemorySection? = null): MemoryEntity {
        check(isEnabled(sessionId)) { "Memory is off for this conversation." }
        val viewer = viewer(sessionId)
        val from = viewer.section
        val target = requestedSection ?: from
        check(viewer.owner == null || target == MemorySection.CHAT) { "This agent only has access to its own memory." }
        check(MemoryAccess.canRecall(settings.settings.first(), from, target)) { "Memory lookup for that section is off in Settings > Memory." }
        val entry = dao.activeMemories(projectIdForSession(sessionId)).firstOrNull { it.id == id && viewer.sees(it, target) }
            ?: error("This memory is not available in this section/project. Recall current memories first.")
        dao.markMemoriesUsed(listOf(entry.id), System.currentTimeMillis())
        return entry
    }

    /** Explicit cross-section lookup is possible only when its switch is enabled. */
    suspend fun search(query: String, sessionId: String?, requestedSection: MemorySection? = null, folder: String? = null): List<MemoryEntity> {
        check(isEnabled(sessionId)) { "Memory is off for this conversation." }
        val viewer = viewer(sessionId)
        val from = viewer.section
        val target = requestedSection ?: from
        check(viewer.owner == null || target == MemorySection.CHAT) { "This agent only has access to its own memory." }
        val prefs = settings.settings.first()
        check(MemoryAccess.canRecall(prefs, from, target)) { "Memory lookup for that section is off in Settings > Memory." }
        val active = dao.activeMemories(projectIdForSession(sessionId)).filter { viewer.sees(it, target) }
        val inFolder = folder?.takeIf { it.isNotBlank() }?.let { name ->
            val builtIn = MemoryFolders.builtIn(name)
            active.filter { if (builtIn != null) it.folder == null && it.category == builtIn.name else MemoryFolders.same(it.folder, name) }
        } ?: active
        val now = System.currentTimeMillis()
        val results = inFolder.filter { query.isBlank() || MemoryKeywords.hit(it.keywords, query) ||
                MemoryMatch.relevance(it.text + " " + MemoryFolders.label(it) + " " + it.keywords, query) > 0 }
            .sortedWith(compareByDescending<MemoryEntity> { MemoryKeywords.hit(it.keywords, query) }
                .thenByDescending { MemoryMatch.relevance(it.text, query) }
                .thenByDescending { it.pinned }.thenByDescending { MemoryUse.score(it, now) }.thenByDescending { it.updatedAt })
            .take(if (query.isBlank()) 20 else 40)
        if (query.isNotBlank() && results.isNotEmpty()) dao.markMemoriesUsed(results.map { it.id }, now)
        return results
    }

    /** Keep the store bounded without deleting anything: oldest unpinned entries are disabled. */
    private suspend fun trim() {
        val enabled = dao.enabledMemories()
        if (enabled.size <= MAX_ENABLED) return
        val now = System.currentTimeMillis()
        enabled.filterNot { it.pinned }
            .sortedWith(compareBy<MemoryEntity> { MemoryUse.score(it, now) }.thenBy { it.updatedAt })
            .take(enabled.size - MAX_ENABLED)
            .forEach { dao.setMemoryEnabled(it.id, false, now) }
    }

    companion object {
        const val MAX_ENABLED = 400
        val toolNames = setOf("remember", "recall")
    }
}
