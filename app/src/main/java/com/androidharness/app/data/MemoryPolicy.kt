package com.androidharness.app.data

import com.androidharness.app.data.db.MemoryEntity
import java.text.Normalizer
import java.util.Locale

/** SHARED keeps existing memories available after upgrading; new agent writes have an owner. */
enum class MemorySection {
    CHAT, CODE, SHARED;
    companion object {
        fun fromStored(value: String?): MemorySection = entries.firstOrNull { it.name == value } ?: SHARED
    }
}

internal object MemoryAccess {
    fun enabled(settings: AppSettings, section: MemorySection): Boolean = settings.memoryEnabled && when (section) {
        MemorySection.CHAT -> settings.chatMemoryEnabled
        MemorySection.CODE -> settings.codeMemoryEnabled
        MemorySection.SHARED -> true
    }

    fun canRecall(settings: AppSettings, from: MemorySection, target: MemorySection): Boolean =
        enabled(settings, from) && (target == from || target == MemorySection.SHARED || when (from) {
            MemorySection.CHAT -> settings.chatRecallCodeMemory && settings.codeMemoryEnabled
            MemorySection.CODE -> settings.codeRecallChatMemory && settings.chatMemoryEnabled
            MemorySection.SHARED -> false
        })

    /**
     * [owner] is the agent whose private memory is in use, [agent] the chat
     * agent in the conversation whatever its memory mode. A folder limited to
     * some agents ([folderAgents]) shows its shared memories only to them,
     * even to an agent that otherwise keeps a private memory.
     */
    fun belongsTo(
        memory: MemoryEntity, section: MemorySection, owner: String? = null,
        agent: String? = owner, folderAgents: Map<String, Set<String>> = emptyMap(),
    ): Boolean {
        if (memory.section != section.name && memory.section != MemorySection.SHARED.name) return false
        if (memory.agentId != null) return memory.agentId == owner
        val allowed = MemoryFolderAgents.allowed(memory.folder, folderAgents) ?: return owner == null
        return agent != null && agent in allowed
    }
}

internal object MemoryMatch {
    private val words = Regex("[\\p{L}\\p{N}]+")
    private val filler = setOf("a", "an", "the", "i", "my", "me", "user", "users", "is", "are", "am", "to", "of", "and", "for", "in", "on", "it", "this", "that", "please", "remember")
    fun key(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?')
    fun tokens(text: String): Set<String> = words.findAll(key(text)).map { it.value }.filter { it !in filler }.toSet()
    fun relevance(text: String, query: String): Double {
        val terms = tokens(query)
        if (terms.isEmpty()) return 0.0
        val content = tokens(text)
        val matches = terms.intersect(content).size
        return matches.toDouble() / terms.size + if (key(query).isNotBlank() && key(text).contains(key(query))) 1.0 else 0.0
    }
    /** A suggestion, never permission to overwrite a differently worded fact. */
    fun similar(a: String, b: String): Boolean {
        val left = tokens(a); val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return false
        val overlap = left.intersect(right).size.toDouble() / minOf(left.size, right.size)
        return overlap >= .8 && left.intersect(right).size >= 3 &&
            minOf(left.size, right.size).toDouble() / maxOf(left.size, right.size) >= .65
    }
}

/** A reviewed digest groups facts without asking a model to discard details. Originals stay in the DB. */
data class MemoryConsolidation(
    val originals: List<MemoryEntity>,
    val text: String,
) {
    val section get() = MemorySection.fromStored(originals.first().section)
    val scope get() = MemoryScope.fromStored(originals.first().scope)
    val projectId get() = originals.first().projectId
    val agentId get() = originals.first().agentId

    companion object {
        const val MAX_CHARS = 20_000
        fun plans(memories: List<MemoryEntity>): List<MemoryConsolidation> = memories
            .filter { it.enabled && it.consolidatedInto == null && !it.isSummary }
            .groupBy { listOf(it.section, it.scope, it.projectId, it.agentId) }.values.mapNotNull { group ->
                val batch = mutableListOf<MemoryEntity>()
                for (entry in group.sortedBy { it.createdAt }) {
                    if (digest(batch + entry).length > MAX_CHARS) break
                    batch += entry
                }
                if (batch.size < 2) null else MemoryConsolidation(batch, digest(batch))
            }

        private fun digest(memories: List<MemoryEntity>): String = MemoryCategory.entries.mapNotNull { category ->
            val facts = memories.filter { it.category == category.name }.distinctBy { MemoryMatch.key(it.text) }
            if (facts.isEmpty()) null else when (category) {
                MemoryCategory.PROFILE -> "Profile"; MemoryCategory.PREFERENCE -> "Preferences"
                MemoryCategory.FACT -> "Facts"; MemoryCategory.DECISION -> "Decisions"; MemoryCategory.PROJECT -> "Project"
            } + ":\n" +
                facts.joinToString("\n") { "• ${it.text}" }
        }.joinToString("\n\n")
    }
}
