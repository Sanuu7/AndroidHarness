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
     * older conversation. Every image in the newest tool batch is additionally
     * retained for the next request so a fresh inspection is never silently dropped.
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
        // Every image in the latest tool batch must reach the very next model
        // request. The normal image budget applies to older context only.
        val latestAssistant = history.indexOfLast { it.role == Role.ASSISTANT && it.toolCallId == null }
        val freshToolIds = history.getOrNull(latestAssistant)
            ?.toolCalls.orEmpty().mapTo(hashSetOf()) { it.id }

        // Count all images across the conversation from newest to oldest
        var keptImages = 0
        val withEvictedImages = history.asReversed().map { m ->
            if (m.imageData.isEmpty()) {
                imageContextState(m, emptyList(), "attachment_unavailable", "No image bytes are included in this request. Saved metadata is listed separately and is not visual evidence.")
            } else if (m.role == Role.TOOL && m.toolCallId in freshToolIds) {
                keptImages += m.imageData.size
                imageContextState(m, m.imageData, "not_in_current_context", "")
            } else {
                val availableQuota = (maxImages - keptImages).coerceAtLeast(0)
                if (availableQuota >= m.imageData.size) {
                    keptImages += m.imageData.size
                    imageContextState(m, m.imageData, "not_in_current_context", "")
                } else if (availableQuota > 0) {
                    val keptSlice = m.imageData.takeLast(availableQuota)
                    keptImages += availableQuota
                    imageContextState(m, keptSlice, "not_in_current_context", "Some attachments are omitted from this request's older-image budget. Saved image files were not deleted; request the image_id again if needed.")
                } else {
                    // All image quota exhausted: strip base64 payloads for older turns
                    imageContextState(m, emptyList(), "not_in_current_context", "This older attachment is omitted from the current request only. Its saved image file was not deleted; request image_id again before making visual claims.")
                }
            }
        }.asReversed()

        return withEvictedImages.mapIndexed { i, m ->
            if (m.role != Role.TOOL || i in keepFull || m.text.length <= maxChars) m
            else m.copy(text = truncate(m.text, maxChars, m.toolCallId))
        }
    }

    /**
     * Strips all base64 image data from messages while inserting a text notice
     * so text-only models understand an image was previously present without
     * receiving unsupported binary payloads.
     */
    fun stripImages(
        messages: List<ChatMessage>,
        note: String = "[Attached image omitted: active model does not support image input]",
    ): List<ChatMessage> = messages.map { m ->
        if (m.imageData.isEmpty()) {
            m
        } else {
            val names = m.images.joinToString(", ") { it.name }.ifEmpty { "image" }
            val placeholder = "$note ($names)"
            val newText = if (m.text.isNotBlank()) "${m.text}\n\n$placeholder" else placeholder
            imageContextState(m, emptyList(), "images_disabled", note).let { stripped ->
                if (stripped.text == m.text) stripped.copy(text = newText) else stripped
            }
        }
    }

    /** Keep model-facing status honest when context pruning removes an actual attachment. */
    private fun imageContextState(message: ChatMessage, kept: List<com.androidharness.app.core.ImageData>, error: String, note: String): ChatMessage {
        return com.androidharness.app.tools.ImageInspectionDelivery.update(message, kept, error, note)
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
        text = "$COMPACTION_NOTICE_PREFIX earlier messages were summarized; full history is still available]",
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
        return shrinkToolResults(sliced).map(Steering::forModel)
    }

    private fun truncate(text: String, maxChars: Int, toolCallId: String?): String {
        val recovery = toolCallId?.let { " Use read_tool_output with tool_call_id=${kotlinx.serialization.json.JsonPrimitive(it)} and offset/limit to retrieve omitted text." }.orEmpty()
        val marker = "\n[truncated ${text.length - maxChars} chars of tool output.$recovery]\n"
        val keep = ((maxChars - marker.length) / 2).coerceAtLeast(64)
        return text.take(keep) + marker + text.takeLast(keep)
    }
}
