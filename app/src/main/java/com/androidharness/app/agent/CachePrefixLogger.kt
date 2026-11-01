package com.androidharness.app.agent

import android.util.Log
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.llm.ToolSchema
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-request prompt cache diagnostics (Step 0).
 *
 * Logged under tag "CachePrefix" at DEBUG level before every provider call.
 * Records a compact diff between consecutive requests in the same session:
 *   - hash and char-count of each "# Heading" section of the system prompt
 *   - hash of the serialised tool list (name+schema concatenated)
 *   - hash of each message (role + text + tool-argument JSON)
 *   - index of the first element that changed vs. the previous request
 *
 * No prompt text, user data, credentials, or tool outputs are ever logged.
 */
object CachePrefixLogger {

    private data class Snapshot(
        val sections: List<Pair<String, Int>>,   // (heading, contentHash)
        val toolsHash: Int,
        val messageHashes: List<Int>,
    )

    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    private val requestCounts = ConcurrentHashMap<String, Int>()

    /**
     * Call this just before streaming a request.
     * [sessionId] identifies the conversation; [systemPrompt] is the full
     * request-level system string; [messages] includes the volatile tail if
     * present.  Usage token counts are optional (filled in from the response).
     */
    fun record(
        sessionId: String,
        systemPrompt: String,
        tools: List<ToolSchema>,
        messages: List<ChatMessage>,
    ) {
        val reqIdx = requestCounts.merge(sessionId, 1, Int::plus) ?: 1
        val sections = splitSections(systemPrompt)
        val sectionHashes = sections.map { (h, body) -> h to body.hashCode() }
        val toolsHash = tools.joinToString("|") { it.name + it.parametersJson.toString() }.hashCode()
        val msgHashes = messages.map { m ->
            (m.role.name + m.text + m.toolCalls.joinToString { it.argumentsJson }).hashCode()
        }
        val current = Snapshot(sectionHashes, toolsHash, msgHashes)
        val prev = snapshots.put(sessionId, current)

        val sb = StringBuilder()
        sb.append("session=").append(sessionId.take(8))
        sb.append(" req=").append(reqIdx)
        sb.append(" sections=").append(sectionHashes.size)
        sb.append(" tools=").append(tools.size)
        sb.append(" msgs=").append(messages.size)

        if (prev == null) {
            sb.append(" firstRequest")
            Log.d("CachePrefix", sb.toString())
            return
        }

        // Identify first difference to pinpoint cache invalidation cause
        var firstDiff = "none"
        val prevSecMap = prev.sections.toMap()
        outer@ for ((heading, hash) in sectionHashes) {
            if (prevSecMap[heading] != hash) {
                firstDiff = "system§${heading.trimStart('#').trim().take(40)}"
                break@outer
            }
        }
        if (firstDiff == "none" && prev.sections.size != sectionHashes.size) {
            firstDiff = "system§(section-count ${prev.sections.size}->${sectionHashes.size})"
        }
        if (firstDiff == "none" && prev.toolsHash != toolsHash) {
            firstDiff = "tools"
        }
        if (firstDiff == "none") {
            val idx = msgHashes.indices.firstOrNull { i ->
                i >= prev.messageHashes.size || prev.messageHashes[i] != msgHashes[i]
            }
            if (idx != null) {
                val roleTag = messages.getOrNull(idx)?.let { m ->
                    m.role.name + if (m.ephemeral) "(tail)" else ""
                } ?: "?"
                firstDiff = "msg[$idx]($roleTag)"
            } else if (msgHashes.size != prev.messageHashes.size) {
                firstDiff = "msgCount(${prev.messageHashes.size}->${msgHashes.size})"
            }
        }

        sb.append(" firstDiff=").append(firstDiff)
        Log.d("CachePrefix", sb.toString())
    }

    /** Report usage after the response to complete the round's picture. */
    fun recordUsage(sessionId: String, cachedTokens: Int, inputTokens: Int) {
        val req = requestCounts[sessionId] ?: return
        val pct = if (inputTokens > 0) cachedTokens * 100 / inputTokens else -1
        Log.d("CachePrefix", "session=${sessionId.take(8)} req=$req usage cached=$cachedTokens input=$inputTokens hit=${pct}%")
    }

    /** Drop state when a session ends or is compacted. */
    fun clearSession(sessionId: String) {
        snapshots.remove(sessionId)
        requestCounts.remove(sessionId)
    }

    // ------------------------------------------------------------------

    /** Split system prompt into labelled sections at "# Heading" boundaries. */
    private fun splitSections(system: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val headingRe = Regex("^# .+", RegexOption.MULTILINE)
        val matches = headingRe.findAll(system).toList()
        if (matches.isEmpty()) {
            result += "(preamble)" to system
            return result
        }
        if (matches.first().range.first > 0) {
            result += "(preamble)" to system.substring(0, matches.first().range.first)
        }
        for (i in matches.indices) {
            val start = matches[i].range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else system.length
            result += matches[i].value.trim() to system.substring(start, end)
        }
        return result
    }
}
