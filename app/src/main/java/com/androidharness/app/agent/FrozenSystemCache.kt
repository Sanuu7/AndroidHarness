package com.androidharness.app.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-session cache for the stable Code-mode system prompt.
 *
 * The system prompt for Code/Agent mode is expensive to build (reads
 * workspace files, skills, repo map) and must stay byte-identical across
 * consecutive executions in the same session so the KV prefix cache of the
 * LLM provider is not invalidated on every new user message.
 *
 * Callers should:
 * 1. Compute [cacheKey] from all inputs that, if changed, require a rebuild:
 *    fullAccess flag, Caveman settings, provider id, model, pinnedInstructions,
 *    and enabled skill names.
 * 2. Call [get] — if non-null, reuse it.
 * 3. Build the system prompt and call [put] to store it.
 * 4. Call [invalidate] after compaction (repo map / workspace state may have
 *    changed significantly) and whenever a rebuild is explicitly required
 *    (e.g. user switches provider/model).
 *
 * Chat-mode prompts are NOT cached here because they are shorter and their
 * stability depends on different inputs.
 */
class FrozenSystemCache {

    private data class Entry(val key: String, val text: String)

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Returns the cached system prompt for [sessionId] if [cacheKey] matches
     * the stored key, or null if the cache is empty or stale.
     */
    fun get(sessionId: String, cacheKey: String): String? =
        entries[sessionId]?.takeIf { it.key == cacheKey }?.text

    /** Stores [text] under [sessionId] with [cacheKey] as the validity token. */
    fun put(sessionId: String, cacheKey: String, text: String) {
        entries[sessionId] = Entry(cacheKey, text)
    }

    /**
     * Removes the cached entry for [sessionId].
     * Call after compaction or any event that may change the stable content
     * (workspace restructured, model changed, etc.).
     */
    fun invalidate(sessionId: String) {
        entries.remove(sessionId)
    }

    companion object {
        /**
         * Builds the cache key from the inputs that affect the stable system
         * prompt.  A change in any of these produces a different key and
         * triggers a rebuild on the next [get] call.
         *
         * @param fullAccess   Whether Full-access mode is active.
         * @param cavemanInstalled Whether the Caveman style is on.
         * @param cavemanIntensity Caveman intensity label (e.g. "LITE").
         * @param cavemanWenyan    Whether the Wenyan variant is on.
         * @param providerId       Provider id string (e.g. "deepseek").
         * @param modelId          Model id string (e.g. "deepseek-chat").
         * @param pinnedInstructions User-pinned instructions for this session.
         * @param enabledSkillNames Sorted list of enabled skill names.
         */
        fun buildKey(
            fullAccess: Boolean,
            cavemanInstalled: Boolean,
            cavemanIntensity: String,
            cavemanWenyan: Boolean,
            providerId: String,
            modelId: String,
            pinnedInstructions: String,
            enabledSkillNames: List<String>,
        ): String = buildString {
            append(if (fullAccess) "1" else "0").append('|')
            append(if (cavemanInstalled) "1" else "0").append(',')
            append(cavemanIntensity).append(',')
            append(if (cavemanWenyan) "1" else "0").append('|')
            append(providerId).append('|')
            append(modelId).append('|')
            append(pinnedInstructions).append('|')
            enabledSkillNames.sorted().joinTo(this, ",")
        }
    }
}
