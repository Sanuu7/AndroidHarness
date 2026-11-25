package com.androidharness.app.data

import com.androidharness.app.data.db.MemoryEntity

/**
 * The shape of the memory map: one hub per folder, every memory tied to its
 * hub, and memories tied to each other when they share trigger words or talk
 * about the same things. Pure data, so it can be built off the main thread
 * and tested without a screen.
 */
data class MemoryGraph(val hubs: List<Hub>, val nodes: List<Node>, val links: List<Link>) {
    /** [key] is "c:CATEGORY" for a built-in folder or "f:Name" for a custom one. */
    data class Hub(val key: String, val size: Int)

    data class Node(val memory: MemoryEntity, val hub: Int)

    enum class Why { KEYWORD, TOPIC }

    /** Between two indexes of [nodes]; [strength] runs from 0 to 1. */
    data class Link(val a: Int, val b: Int, val strength: Float, val why: Why)

    fun neighbours(node: Int): List<Int> = links.mapNotNull { when (node) { it.a -> it.b; it.b -> it.a; else -> null } }

    companion object {
        /** How many links each memory may start, so a busy topic stays readable. */
        const val MAX_LINKS_PER_NODE = 3
        const val MIN_STRENGTH = .14f

        fun folderKey(memory: MemoryEntity): String =
            memory.folder?.let { "f:$it" } ?: "c:${MemoryCategory.fromStored(memory.category).name}"

        fun build(memories: List<MemoryEntity>, folderOrder: List<String>): MemoryGraph {
            val live = memories.filter { it.consolidatedInto == null }
            val order = (MemoryCategory.entries.map { "c:${it.name}" } + folderOrder.map { "f:$it" })
            val keys = live.map(::folderKey)
            val hubKeys = (order + keys).distinct().filter { it in keys }
            val hubIndex = hubKeys.withIndex().associate { it.value to it.index }
            val hubs = hubKeys.map { key -> Hub(key, keys.count { it == key }) }
            val nodes = live.mapIndexed { i, memory -> Node(memory, hubIndex.getValue(keys[i])) }
            return MemoryGraph(hubs, nodes, links(live))
        }

        private val stopWords = setOf(
            "about", "after", "again", "also", "always", "because", "been", "before", "being", "could", "does", "doing",
            "each", "every", "from", "have", "having", "into", "just", "like", "made", "make", "more", "most", "much",
            "must", "never", "only", "other", "over", "same", "should", "some", "such", "than", "that", "their", "them",
            "then", "there", "these", "they", "thing", "things", "this", "those", "through", "uses", "using", "very",
            "want", "wants", "were", "what", "when", "where", "which", "while", "will", "with", "would", "your", "user",
            "users", "prefers", "prefer",
        )

        internal fun words(text: String): Set<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 4 && it !in stopWords && !it.all(Char::isDigit) }
                .map { if (it.length > 5 && it.endsWith("s")) it.dropLast(1) else it }
                .toSet()

        /**
         * Scores only pairs that share at least one word, through an index of
         * which memories use each word, so a few thousand memories stay quick.
         * Words used by most memories say nothing about a pair and are skipped.
         */
        internal fun links(memories: List<MemoryEntity>): List<Link> {
            if (memories.size < 2) return emptyList()
            val topics = memories.map { words(it.text) }
            val triggers = memories.map { MemoryKeywords.parse(it.keywords).map { word -> word.lowercase() }.toSet() }
            val common = (memories.size * .25f).toInt().coerceAtLeast(8)
            val index = HashMap<String, MutableList<Int>>()
            topics.forEachIndexed { i, set -> set.forEach { index.getOrPut(it) { ArrayList() }.add(i) } }
            val triggerIndex = HashMap<String, MutableList<Int>>()
            triggers.forEachIndexed { i, set -> set.forEach { triggerIndex.getOrPut(it) { ArrayList() }.add(i) } }
            val best = HashMap<Long, Link>()
            for (i in memories.indices) {
                val shared = HashMap<Int, Int>()
                topics[i].forEach { word -> index[word]?.takeIf { it.size <= common }?.forEach { j -> if (j != i) shared.merge(j, 1, Int::plus) } }
                val sharedTriggers = HashMap<Int, Int>()
                triggers[i].forEach { word -> triggerIndex[word]?.forEach { j -> if (j != i) sharedTriggers.merge(j, 1, Int::plus) } }
                val scored = (shared.keys + sharedTriggers.keys).map { j ->
                    val overlap = shared[j] ?: 0
                    val union = (topics[i].size + topics[j].size - overlap).coerceAtLeast(1)
                    val topic = overlap.toFloat() / union
                    val keyword = sharedTriggers[j] ?: 0
                    val strength = (topic + if (keyword > 0) .45f + .1f * (keyword - 1) else 0f).coerceAtMost(1f)
                    Triple(j, strength, if (keyword > 0) Why.KEYWORD else Why.TOPIC)
                }.filter { it.second >= MIN_STRENGTH }.sortedByDescending { it.second }.take(MAX_LINKS_PER_NODE)
                scored.forEach { (j, strength, why) ->
                    val a = minOf(i, j); val b = maxOf(i, j)
                    val id = a.toLong() shl 32 or b.toLong()
                    val known = best[id]
                    if (known == null || known.strength < strength) best[id] = Link(a, b, strength, why)
                }
            }
            return best.values.sortedWith(compareBy({ it.a }, { it.b }))
        }
    }
}
