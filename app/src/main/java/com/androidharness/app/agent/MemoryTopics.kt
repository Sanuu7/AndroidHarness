package com.androidharness.app.agent

/**
 * Topic memory: optional long-form notes in .harness/memory/<topic>.md next
 * to the always-loaded core .harness/memory.md. Topic files are only listed
 * by NAME in the system prompt, so they can grow without inflating context;
 * the agent retrieves them with memory_read / memory_search.
 */
object MemoryTopics {

    const val DIR = ".harness/memory"

    /** Topic files may be far larger than the core memory's hard cap. */
    const val MAX_TOPIC_CHARS = 40_000

    /**
     * Topic -> safe file stem: lowercase, [a-z0-9_-], max 48 chars. Null when
     * nothing survives sanitizing (blank or only punctuation).
     */
    fun sanitize(topic: String): String? =
        topic.trim().lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
            .takeIf { it.isNotEmpty() }
            ?.take(48)

    fun topicPath(topic: String): String? = sanitize(topic)?.let { "$DIR/$it.md" }
}
