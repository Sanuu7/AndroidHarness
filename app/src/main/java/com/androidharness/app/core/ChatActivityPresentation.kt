package com.androidharness.app.core

import com.androidharness.app.tools.PageImageText
import com.androidharness.app.tools.WebSearchText
import com.androidharness.app.tools.ReadableWebPage
import com.androidharness.app.tools.canonicalResultUrl
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Derived only from this turn's parent assistant calls and their matching results. */
internal object ChatActivityPresentation {
    data class Source(val title: String, val url: String, val host: String, val read: Boolean)
    data class Step(val name: String, val finished: Boolean, val failed: Boolean)
    data class ResearchProgress(
        val sourcesUsed: Int,
        val sourcesMax: Int,
        val pagesRead: Int,
        val readsRequired: Int,
    )

    fun calls(messages: List<ChatMessage>): List<ToolCallData> = messages
        .filter { it.role == Role.ASSISTANT && it.toolCallId == null }
        .flatMap { it.toolCalls }.distinctBy { it.id }

    fun steps(messages: List<ChatMessage>): List<Step> {
        val results = messages.filter { it.role == Role.TOOL }.associateBy { it.toolCallId }
        return calls(messages).filter { it.name != "offer_code_handoff" }.map {
            Step(it.name, results[it.id] != null, results[it.id]?.isError == true)
        }
    }

    /** True only when a remember call in this turn received a successful result. */
    fun savedMemory(messages: List<ChatMessage>): Boolean {
        val rememberIds = calls(messages).filter { it.name == "remember" }.map { it.id }.toSet()
        if (rememberIds.isEmpty()) return false
        return messages.any { it.role == Role.TOOL && it.toolCallId in rememberIds && !it.isError }
    }

    fun memoryAction(messages: List<ChatMessage>): String {
        val ids = calls(messages).filter { it.name == "remember" }.map { it.id }.toSet()
        val actions = messages.filter { it.role == Role.TOOL && !it.isError && it.toolCallId in ids }
            .map { Regex("\\[memory_action:(saved|updated|unchanged)]").find(it.text)?.groupValues?.get(1) ?: "saved" }
        return when { "updated" in actions -> "updated"; "saved" in actions -> "saved"; else -> "unchanged" }
    }

    fun savedMemoryIds(messages: List<ChatMessage>): Set<String> {
        val ids = calls(messages).filter { it.name == "remember" }.map { it.id }.toSet()
        return messages.filter { it.role == Role.TOOL && !it.isError && it.toolCallId in ids }
            .mapNotNull { Regex("\\[memory_id:([a-zA-Z0-9-]+)]").find(it.text)?.groupValues?.get(1) }.toSet()
    }

    fun activeCall(messages: List<ChatMessage>, running: List<ToolCallData>): ToolCallData? {
        val finished = messages.filter { it.role == Role.TOOL }.mapNotNull { it.toolCallId }.toSet()
        return running.lastOrNull { it.id !in finished } ?: calls(messages).lastOrNull { it.id !in finished }
    }

    sealed interface Entry {
        val key: String
        data class Thought(override val key: String, val text: String, val milliseconds: Long) : Entry
        data class Commentary(override val key: String, val text: String) : Entry
        data class Tool(override val key: String, val call: ToolCallData, val result: ChatMessage?) : Entry
    }

    /** Preserve model request boundaries: thought, commentary, calls, then the next request. */
    fun timeline(messages: List<ChatMessage>, running: List<ToolCallData> = emptyList(), streamingThinking: String = "", streamId: String = "live", streamingCommentary: String = ""): List<Entry> {
        val results = messages.filter { it.role == Role.TOOL }.associateBy { it.toolCallId }
        val parents = messages.withIndex().filter { it.value.role == Role.ASSISTANT && it.value.toolCallId == null }
        val answerIndex = parents.lastOrNull()?.takeIf { it.value.toolCalls.isEmpty() }?.index
        val seenCalls = hashSetOf<String>()
        return buildList {
            for ((index, message) in parents) {
                val key = message.id ?: "message-$index"
                if (message.thinking.isNotBlank() || message.thinkingMs > 0 || (message.generation.reasoningTokens ?: 0) > 0)
                    add(Entry.Thought("$key-thinking", message.thinking, message.thinkingMs))
                if (message.text.isNotBlank() && index != answerIndex) add(Entry.Commentary("$key-commentary", message.text))
                message.toolCalls.filter { it.name != "offer_code_handoff" }.forEach { call ->
                    if (seenCalls.add(call.id)) add(Entry.Tool("tool-${call.id}", call, results[call.id]))
                }
            }
            if (streamingThinking.isNotBlank()) add(Entry.Thought("$streamId-thinking", streamingThinking, 0))
            if (streamingCommentary.isNotBlank()) add(Entry.Commentary("$streamId-commentary", streamingCommentary))
            running.filter { it.name != "offer_code_handoff" && seenCalls.add(it.id) }.forEach {
                add(Entry.Tool("tool-${it.id}", it, results[it.id]))
            }
        }
    }

    fun sources(messages: List<ChatMessage>, limit: Int = 100): List<Source> {
        val results = messages.filter { it.role == Role.TOOL && !it.isError }.associateBy { it.toolCallId }
        val sources = linkedMapOf<String, Source>()
        fun add(title: String, rawUrl: String, read: Boolean) {
            val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return
            if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return
            val url = uri.toASCIIString()
            val key = canonicalResultUrl(url)
            val existing = sources[key]
            if (existing != null) sources[key] = existing.copy(read = existing.read || read)
            else if (sources.size < limit) sources[key] = Source(title.trim().ifBlank { uri.host }.take(240), url, uri.host.removePrefix("www."), read)
        }
        for (call in calls(messages)) {
            val result = results[call.id] ?: continue
            when (call.name) {
                "web_search" -> {
                    WebSearchText.parse(result.text.lineSequence().take(400).joinToString("\n"))
                        .forEach { add(it.title, it.url, false) }
                    com.androidharness.app.tools.SearchPassages.readSources(result.text).forEach { add(it.title, it.url, true) }
                }
                "web_fetch", "browser_read" -> if (result.generation.research?.pageRead ?: ReadableWebPage.legacyRead(result.text)) {
                    val url = result.generation.research?.pageUrl ?: runCatching { Json.parseToJsonElement(call.argumentsJson).jsonObject["url"]?.jsonPrimitive?.content }.getOrNull()
                    if (url != null) add("", url, true)
                }
            }
        }
        return sources.values.toList()
    }

    /** Direct image URL to the page it was found on, for captions and source actions. */
    fun imageSources(messages: List<ChatMessage>): Map<String, String> {
        val results = messages.filter { it.role == Role.TOOL && !it.isError }.associateBy { it.toolCallId }
        val out = linkedMapOf<String, String>()
        for (call in calls(messages)) {
            if (call.name != "web_fetch" && call.name != "browser_read" && call.name != "web_search") continue
            val text = results[call.id]?.text ?: continue
            for (image in PageImageText.parse(text)) out.putIfAbsent(image.imageUrl, image.pageUrl)
        }
        return out
    }

    /** All matching tool receipts contribute; a later fetch never waits for another search. */
    fun researchProgress(messages: List<ChatMessage>): ResearchProgress? {
        val results = messages.filter { it.role == Role.TOOL && !it.isError }.associateBy { it.toolCallId }
        val pattern = Regex(
            "\\[sources this turn: (\\d+)/(\\d+), \\d+ remaining" +
                "(?:; pages read: (\\d+)/(\\d+) required)?",
        )
        var latest: ResearchProgress? = null
        var readCount = 0
        val readPages = hashSetOf<String>()
        for (call in calls(messages)) {
            val result = results[call.id] ?: continue
            val receipt = result.generation.research
            if (call.name in setOf("web_search", "web_fetch", "browser_read") && receipt != null) {
                readCount = maxOf(readCount, receipt.pagesRead ?: 0)
                if (receipt.pageRead) receipt.pageUrl?.let { readPages += canonicalResultUrl(it) }
                if (receipt.sourcesMax != null && receipt.sourcesMax > 0 && receipt.sourcesUsed != null) latest = ResearchProgress(
                    maxOf(latest?.sourcesUsed ?: 0, receipt.sourcesUsed), receipt.sourcesMax,
                    readCount, receipt.readsRequired ?: latest?.readsRequired ?: 0)
                continue
            }
            if (call.name == "web_fetch" || call.name == "browser_read") {
                if (ReadableWebPage.legacyRead(result.text)) runCatching {
                    Json.parseToJsonElement(call.argumentsJson).jsonObject["url"]?.jsonPrimitive?.content
                }.getOrNull()?.let { readPages += canonicalResultUrl(it) }
                continue
            }
            if (call.name != "web_search") continue
            val text = result.text
            val match = pattern.findAll(text).lastOrNull() ?: continue
            val values = match.groupValues
            latest = ResearchProgress(
                sourcesUsed = maxOf(latest?.sourcesUsed ?: 0, values[1].toIntOrNull() ?: continue),
                sourcesMax = values[2].toIntOrNull() ?: continue,
                pagesRead = values[3].toIntOrNull() ?: 0,
                readsRequired = values[4].toIntOrNull() ?: 0,
            )
            readCount = maxOf(readCount, latest.pagesRead)
        }
        return latest?.copy(pagesRead = maxOf(readCount, readPages.size))
    }
}
