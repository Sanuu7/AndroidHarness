package com.androidharness.app.agent

import java.util.Collections

/**
 * All inputs that determine the content of the stable Code-mode system prompt.
 *
 * Every field that [AgentEngine.systemPrompt] reads and includes in its output
 * must appear here so a change in any of them produces a different cache key
 * and forces a rebuild.
 *
 * **Intentionally excluded** (content is frozen at first build; changes are
 * low-risk within a session and handled by [FrozenSystemCache.invalidate] on
 * compaction):
 * - Skill text bodies: only [enabledSkillNames] is in the key because skill
 *   content changes require an app update or a file edit, not an in-session
 *   user action.
 * - AGENTS.md / HARNESS.md content: same reasoning; the model is told it is
 *   a session snapshot.
 * - Workspace memory file (.harness/memory.md): frozen snapshot; fresh recall
 *   is delivered every round via the volatile tail.
 * - Repo-map content: frozen snapshot; the model is told to use list_dir /
 *   search_files for the live state.
 * - projectConfig details (autoLoad, prompt, permissions): assumed stable
 *   within a session.
 */
data class SystemKeyInputs(
    /** AgentMode.name — Plan vs Act produces different "PLAN MODE IS ACTIVE" block. */
    val mode: String,
    /** Whether Full-access mode is active (FULL ACCESS note in rules). */
    val fullAccess: Boolean,
    /** Whether the Caveman style is installed. */
    val cavemanInstalled: Boolean,
    /** Caveman intensity label, e.g. "OFF", "LITE". */
    val cavemanIntensity: String,
    /** Whether the Wenyan variant is active. */
    val cavemanWenyan: Boolean,
    /** Provider identifier, e.g. "deepseek". */
    val providerId: String,
    /** Model identifier, e.g. "deepseek-chat". */
    val modelId: String,
    /** User-pinned instructions for this session (included verbatim in system). */
    val pinnedInstructions: String,
    /**
     * Sorted names of enabled skills.  Only names (not content) are keyed;
     * content changes are handled by compaction-triggered invalidation.
     */
    val enabledSkillNames: List<String>,
    /** Whether the repo map section is enabled for this session. */
    val repoMapEnabled: Boolean,
    /**
     * Whether memory/personalization is allowed (controls the memory snapshot
     * and topic-list sections of the system prompt).
     */
    val memoryAllowed: Boolean,
    /**
     * Workspace display path.  Changing the active workspace produces a new
     * session, but multi-workspace sessions share a sessionId, so include it.
     */
    val workspaceDisplayPath: String,
)

/**
 * Per-session cache for the stable Code-mode system prompt.
 *
 * Building the system prompt is expensive (reads workspace files, skills,
 * repo map) and must produce byte-identical output across consecutive
 * executions of the same session so the LLM prefix cache is not invalidated
 * on every new user message.
 *
 * Usage:
 * ```
 * val text = cache.getOrBuild(sessionId, inputs) { buildSystemPrompt(...) }
 * ```
 *
 * The cache is bounded to [MAX_SESSIONS] entries (LRU) so it does not grow
 * without bound across many sessions.  Call [invalidate] after compaction or
 * any event that may have changed the stable content significantly.
 *
 * The frozen text lives only in memory.  If the app process is killed and
 * restarted mid-session, the first request of the resumed session recalculates
 * the system prompt from scratch (acceptable: the KV cache on the provider side
 * is also cold after a restart).
 */
class FrozenSystemCache {

    private data class Entry(val key: String, val text: String)

    private val entries: MutableMap<String, Entry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Entry>(16, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Entry>) = size > MAX_SESSIONS
        },
    )

    /**
     * Returns the cached system text if [inputs] matches the stored key, or
     * builds it by calling [build], caches the result, and returns it.
     *
     * [build] is invoked at most once per unique (sessionId, key) combination.
     */
    suspend fun getOrBuild(
        sessionId: String,
        inputs: SystemKeyInputs,
        build: suspend () -> String,
    ): String {
        val key = buildKey(inputs)
        entries[sessionId]?.let { entry ->
            if (entry.key == key) return entry.text
        }
        val text = build()
        entries[sessionId] = Entry(key, text)
        return text
    }

    /**
     * Removes the cached entry for [sessionId].  Call after compaction, session
     * deletion, or any event that may have changed stable content (workspace
     * restructured, model changed, etc.).
     */
    fun invalidate(sessionId: String) {
        entries.remove(sessionId)
    }

    companion object {
        /** Maximum number of sessions kept in the LRU cache. */
        const val MAX_SESSIONS = 32

        /**
         * Produces a stable string key from [inputs].  Two [SystemKeyInputs]
         * values that differ in any field produce different keys.
         */
        fun buildKey(inputs: SystemKeyInputs): String = buildString {
            append(inputs.mode).append('|')
            append(if (inputs.fullAccess) "1" else "0").append('|')
            append(if (inputs.cavemanInstalled) "1" else "0").append(',')
            append(inputs.cavemanIntensity).append(',')
            append(if (inputs.cavemanWenyan) "1" else "0").append('|')
            append(inputs.providerId).append('|')
            append(inputs.modelId).append('|')
            append(if (inputs.repoMapEnabled) "1" else "0").append('|')
            append(if (inputs.memoryAllowed) "1" else "0").append('|')
            append(inputs.workspaceDisplayPath).append('|')
            inputs.enabledSkillNames.sorted().joinTo(this, ",")
            append('|')
            append(inputs.pinnedInstructions)
        }
    }
}
