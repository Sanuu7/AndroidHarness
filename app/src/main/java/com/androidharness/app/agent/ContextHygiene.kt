package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role

/**
 * Keeps the model-facing history small without lying that the user said the
 * summary. Old tool dumps are truncated in the *working* copy only; the DB
 * still holds the full output for the UI.
 */
object ContextHygiene {

    /** Most recent tool results stay verbatim so the model can still act on them. */
    const val RECENT_FULL_TOOLS = 6

    const val STALE_TOOL_CHARS = 4_000

    /**
     * Maximum number of recent images to keep in model context across the
     * whole conversation (keeps only the 2 most recent images in context,
     * while user can attach freely).
     */
    const val MAX_CONTEXT_IMAGES = 2

    fun shrinkToolResults(
        history: List<ChatMessage>,
        recentFull: Int = RECENT_FULL_TOOLS,
        maxChars: Int = STALE_TOOL_CHARS,
        maxImages: Int = MAX_CONTEXT_IMAGES,
    ): List<ChatMessage> {
        val toolIndices = history.indices.filter { history[it].role == Role.TOOL }
        val keepFull = toolIndices.takeLast(recentFull).toSet()

        // Count all images across the conversation from newest to oldest
        var keptImages = 0
        val withEvictedImages = history.asReversed().map { m ->
            if (m.imageData.isEmpty()) {
                m
            } else {
                val availableQuota = (maxImages - keptImages).coerceAtLeast(0)
                if (availableQuota >= m.imageData.size) {
                    keptImages += m.imageData.size
                    m
                } else if (availableQuota > 0) {
                    val keptSlice = m.imageData.takeLast(availableQuota)
                    keptImages += availableQuota
                    m.copy(imageData = keptSlice)
                } else {
                    // All image quota exhausted: strip base64 payloads for older turns
                    m.copy(imageData = emptyList())
                }
            }
        }.asReversed()

        return withEvictedImages.mapIndexed { i, m ->
            if (m.role != Role.TOOL || i in keepFull || m.text.length <= maxChars) m
            else m.copy(text = truncate(m.text, maxChars))
        }
    }

    fun summaryMessage(summary: String): ChatMessage = ChatMessage(
        role = Role.SYSTEM,
        text = "${AgentEngine.COMPACTION_PREFIX}\n\n$summary",
    )

    /**
     * Marker prefix of the user-visible compaction notice line; the summary
     * itself stays hidden, this tells the user the transcript was folded.
     */
    const val COMPACTION_NOTICE_PREFIX = "[Context compacted:"

    fun compactionNotice(): ChatMessage = ChatMessage(
        role = Role.SYSTEM,
        text = "$COMPACTION_NOTICE_PREFIX older messages were summarized into the hidden note above]",
    )

    /**
     * Model-facing slice: last compaction summary plus everything after it,
     * with stale tool dumps truncated. The UI still sees the full transcript.
     */
    fun forModel(history: List<ChatMessage>): List<ChatMessage> {
        val lastSummary = history.indexOfLast {
            it.role == Role.SYSTEM && it.text.startsWith(AgentEngine.COMPACTION_PREFIX)
        }
        val sliced = if (lastSummary >= 0) history.subList(lastSummary, history.size) else history
        return shrinkToolResults(sliced)
    }

    private fun truncate(text: String, maxChars: Int): String {
        val marker = "\n[truncated ${text.length - maxChars} chars of tool output]\n"
        val keep = ((maxChars - marker.length) / 2).coerceAtLeast(64)
        return text.take(keep) + marker + text.takeLast(keep)
    }
}
