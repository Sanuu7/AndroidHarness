package com.androidharness.app.data

import com.androidharness.app.data.db.MemoryEntity
import com.androidharness.app.data.db.MemoryFolderEntity
import kotlin.math.ln
import kotlin.math.pow

/**
 * Where a memory lives. The five categories are the built-in folders, so
 * existing memories keep their place; any other folder is one the user or
 * the agent created.
 */
data class MemoryFolderTarget(val category: MemoryCategory, val folder: String?) {
    companion object {
        fun of(memory: MemoryEntity) = MemoryFolderTarget(MemoryCategory.fromStored(memory.category), memory.folder)
    }
}

internal object MemoryFolders {
    const val MAX_NAME = 40
    const val MAX_FOLDERS = 50

    private val builtInNames: Map<String, MemoryCategory> = mapOf(
        "profile" to MemoryCategory.PROFILE, "profiles" to MemoryCategory.PROFILE,
        "preference" to MemoryCategory.PREFERENCE, "preferences" to MemoryCategory.PREFERENCE,
        "project" to MemoryCategory.PROJECT, "projects" to MemoryCategory.PROJECT,
        "decision" to MemoryCategory.DECISION, "decisions" to MemoryCategory.DECISION,
        "fact" to MemoryCategory.FACT, "facts" to MemoryCategory.FACT,
    )

    fun builtIn(name: String): MemoryCategory? = builtInNames[MemoryMatch.key(name)]

    /** A clean folder name, or an error that says what to change. Built-in names are refused. */
    fun checkedName(raw: String): String {
        val name = raw.replace(Regex("\\s+"), " ").trim()
        require(name.isNotEmpty()) { "Enter a folder name." }
        require(name.length <= MAX_NAME) { "Keep folder names within $MAX_NAME characters." }
        require(name.none { it.isISOControl() || it == ',' }) { "Folder names can't contain commas or control characters." }
        require(builtIn(name) == null) { "\"$name\" is already a built-in folder." }
        return name
    }

    /** Resolves a folder name from the agent or a backup. A built-in name maps to its category. */
    fun target(raw: String?, fallback: MemoryCategory): MemoryFolderTarget {
        val name = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (name.isEmpty()) return MemoryFolderTarget(fallback, null)
        builtIn(name)?.let { return MemoryFolderTarget(it, null) }
        return MemoryFolderTarget(fallback, checkedName(name))
    }

    /** Same folder regardless of case, so "Termux" and "termux" never become two folders. */
    fun same(a: String?, b: String?): Boolean = a != null && b != null && MemoryMatch.key(a) == MemoryMatch.key(b)

    /** The folder name the agent sees in prompts and recall results. */
    fun label(memory: MemoryEntity): String = memory.folder ?: memory.category.lowercase()
}

/** Which chat agents may see a folder. No entry means everyone. */
internal object MemoryFolderAgents {
    fun parse(raw: String?): Set<String> = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun encode(ids: Collection<String>): String = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString(",")

    /** Limited folders by case-insensitive name. */
    fun of(rows: List<MemoryFolderEntity>): Map<String, Set<String>> =
        rows.mapNotNull { row -> parse(row.agentIds).takeIf { it.isNotEmpty() }?.let { MemoryMatch.key(row.name) to it } }.toMap()

    /** The agents allowed into [folder], or null when the folder is open to everyone. */
    fun allowed(folder: String?, limits: Map<String, Set<String>>): Set<String>? = folder?.let { limits[MemoryMatch.key(it)] }
}

internal object MemoryKeywords {
    const val MAX_KEYWORDS = 12
    const val MAX_KEYWORD = 40

    /** Comma-separated, trimmed, lowercase, de-duplicated. */
    fun normalize(raw: String?): String = parse(raw).joinToString(", ")

    fun parse(raw: String?): List<String> = raw.orEmpty().split(',', '\n', ';')
        .map { MemoryMatch.key(it).trim('.', '!', '?', ' ') }
        .filter { it.isNotEmpty() }
        .distinct()

    /** Like [normalize], but refuses input that would be silently cut. */
    fun checked(raw: String?): String {
        val words = parse(raw)
        require(words.size <= MAX_KEYWORDS) { "Use at most $MAX_KEYWORDS trigger words." }
        require(words.all { it.length <= MAX_KEYWORD }) { "Keep each trigger word within $MAX_KEYWORD characters." }
        return words.joinToString(", ")
    }

    /** True when a trigger word or phrase appears as whole words in [query]. */
    fun hit(keywords: String, query: String): Boolean {
        if (keywords.isBlank() || query.isBlank()) return false
        val text = MemoryMatch.key(query)
        return parse(keywords).any { word ->
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word) + "(?![\\p{L}\\p{N}])").containsMatchIn(text)
        }
    }
}

/**
 * How useful a memory has been lately. Counts only uses where the memory
 * matched what the user was asking, so being in the prompt does not keep an
 * entry alive by itself. Unused entries fade over about a month; nothing is
 * deleted.
 */
internal object MemoryUse {
    private const val HALF_LIFE_DAYS = 30.0
    private const val DAY_MS = 86_400_000.0

    fun score(memory: MemoryEntity, now: Long): Double {
        val last = maxOf(memory.lastUsedAt, memory.createdAt)
        val ageDays = ((now - last).coerceAtLeast(0)) / DAY_MS
        return (1.0 + ln(1.0 + memory.useCount.coerceAtLeast(0))) * 0.5.pow(ageDays / HALF_LIFE_DAYS)
    }
}
