package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.llm.ToolSchema

/**
 * What fills the next request, split far enough to act on. Sizes use the same
 * chars / 4 estimate as [ContextEstimate], so each group matches its total to
 * within a few tokens of rounding.
 */
data class ContextBreakdown(
    val system: List<Slice>,
    val tools: List<Slice>,
    val messages: List<Slice>,
    /** Biggest tool outputs still in the history, largest first. */
    val largestResults: List<ToolResult>,
) {
    enum class Part {
        CORE, SKILLS, ENVIRONMENT, INSTRUCTIONS, MEMORY, REPO_MAP,
        TOOLS_BUILT_IN, TOOLS_CODEGRAPH, TOOLS_MCP,
        USER, ASSISTANT, TOOL_CALLS, TOOL_RESULTS, NOTES,
    }

    /** [detail] names the MCP server for [Part.TOOLS_MCP]; [count] is the number of tools in a tool slice. */
    data class Slice(val part: Part, val tokens: Int, val detail: String = "", val count: Int = 0)

    data class ToolResult(val toolName: String, val tokens: Int)

    enum class HintKind { NEAR_LIMIT, TOOL_RESULTS, MCP_TOOLS, SKILLS, MEMORY }

    /** [tokens] and [detail] fill the message for the kind: the size involved, and a tool name where one applies. */
    data class Hint(val kind: HintKind, val tokens: Int = 0, val detail: String = "")

    val systemTokens: Int get() = system.sumOf { it.tokens }
    val toolsTokens: Int get() = tools.sumOf { it.tokens }
    val messagesTokens: Int get() = messages.sumOf { it.tokens }

    /**
     * Things the user can change to make room, biggest first after the near-limit
     * warning. [used] is the context in use and [limit] the model window.
     */
    fun hints(used: Int, limit: Int): List<Hint> = buildList {
        if (limit > 0 && used >= limit * NEAR_LIMIT) add(Hint(HintKind.NEAR_LIMIT, used))
        val total = (systemTokens + toolsTokens + messagesTokens).coerceAtLeast(1)
        val results = messages.firstOrNull { it.part == Part.TOOL_RESULTS }?.tokens ?: 0
        if (results >= MIN_HINT_TOKENS && results * 100 >= total * RESULTS_SHARE_PERCENT) {
            add(Hint(HintKind.TOOL_RESULTS, results, largestResults.firstOrNull()?.toolName.orEmpty()))
        }
        val mcp = tools.filter { it.part == Part.TOOLS_MCP }.sumOf { it.tokens }
        if (mcp >= MIN_HINT_TOKENS && mcp * 100 >= total * MCP_SHARE_PERCENT) add(Hint(HintKind.MCP_TOOLS, mcp))
        val skills = system.firstOrNull { it.part == Part.SKILLS }?.tokens ?: 0
        if (skills >= MIN_HINT_TOKENS) add(Hint(HintKind.SKILLS, skills))
        val memory = system.firstOrNull { it.part == Part.MEMORY }?.tokens ?: 0
        if (memory >= MIN_HINT_TOKENS) add(Hint(HintKind.MEMORY, memory))
    }

    companion object {
        const val NEAR_LIMIT = 0.8
        const val MIN_HINT_TOKENS = 1_500
        const val RESULTS_SHARE_PERCENT = 30
        const val MCP_SHARE_PERCENT = 20
        const val MAX_RESULTS = 5
        const val MIN_RESULT_TOKENS = 200

        /**
         * Headings the harness writes into the system prompt itself. Only these start a
         * new section, so a heading inside a skill, AGENTS.md or a memory note stays
         * with the section it belongs to.
         */
        private val SECTION_HEADINGS: List<Pair<String, Part>> = listOf(
            "## Skills (mandatory)" to Part.SKILLS,
            "## AndroidHarness operating guide" to Part.SKILLS,
            "# Auto-loaded skill:" to Part.SKILLS,
            "## Live AndroidHarness environment" to Part.ENVIRONMENT,
            "## Tools available for this request" to Part.ENVIRONMENT,
            "# AGENTS.md (project instructions)" to Part.INSTRUCTIONS,
            "# Project instructions:" to Part.INSTRUCTIONS,
            "# Code agent:" to Part.INSTRUCTIONS,
            "# Reference documents" to Part.INSTRUCTIONS,
            "# User instructions for this chat" to Part.INSTRUCTIONS,
            "# Agent memory (from previous sessions)" to Part.MEMORY,
            "# Saved memory" to Part.MEMORY,
            "# Repository Map (codebase index)" to Part.REPO_MAP,
        )

        fun of(history: List<ChatMessage>, systemPrompt: String, tools: List<ToolSchema>): ContextBreakdown = ContextBreakdown(
            system = splitSystem(systemPrompt),
            tools = groupTools(tools),
            messages = splitMessages(history),
            largestResults = largestResults(history),
        )

        internal fun splitSystem(prompt: String): List<Slice> {
            val chars = LinkedHashMap<Part, Int>()
            var part = Part.CORE
            var start = 0
            while (start < prompt.length) {
                val end = prompt.indexOf('\n', start).let { if (it < 0) prompt.length else it + 1 }
                headingPart(prompt, start, end)?.let { part = it }
                chars[part] = (chars[part] ?: 0) + (end - start)
                start = end
            }
            return chars.map { (p, n) -> Slice(p, n / 4) }.filter { it.tokens > 0 }
        }

        private fun headingPart(text: String, start: Int, end: Int): Part? {
            if (start >= end || text[start] != '#') return null
            for ((heading, part) in SECTION_HEADINGS) {
                if (end - start >= heading.length && text.regionMatches(start, heading, 0, heading.length)) return part
            }
            return null
        }

        internal fun groupTools(tools: List<ToolSchema>): List<Slice> {
            data class Bucket(var chars: Int = 0, var count: Int = 0)
            val builtIn = Bucket()
            val codegraph = Bucket()
            val mcp = sortedMapOf<String, Bucket>()
            for (tool in tools) {
                val size = tool.description.length + tool.parametersJson.toString().length
                val bucket = when {
                    tool.name.startsWith("mcp__") -> mcp.getOrPut(mcpServer(tool.name)) { Bucket() }
                    tool.name.startsWith("codegraph_") -> codegraph
                    else -> builtIn
                }
                bucket.chars += size
                bucket.count++
            }
            return buildList {
                if (builtIn.count > 0) add(Slice(Part.TOOLS_BUILT_IN, builtIn.chars / 4, count = builtIn.count))
                if (codegraph.count > 0) add(Slice(Part.TOOLS_CODEGRAPH, codegraph.chars / 4, count = codegraph.count))
                mcp.forEach { (server, b) -> add(Slice(Part.TOOLS_MCP, b.chars / 4, detail = server, count = b.count)) }
            }.sortedByDescending { it.tokens }
        }

        /** mcp__<server>__<tool> gives the server; a name without the second separator is its own group. */
        internal fun mcpServer(toolName: String): String {
            val rest = toolName.removePrefix("mcp__")
            val end = rest.indexOf("__")
            return if (end > 0) rest.substring(0, end) else rest
        }

        internal fun splitMessages(history: List<ChatMessage>): List<Slice> {
            val chars = LinkedHashMap<Part, Int>()
            fun add(part: Part, n: Int) { if (n > 0) chars[part] = (chars[part] ?: 0) + n }
            for (message in history) {
                val part = when (message.role) {
                    Role.USER -> Part.USER
                    Role.ASSISTANT -> Part.ASSISTANT
                    Role.TOOL -> Part.TOOL_RESULTS
                    Role.SYSTEM -> Part.NOTES
                }
                add(part, message.text.length)
                add(Part.TOOL_CALLS, message.toolCalls.sumOf { it.argumentsJson.length })
            }
            return chars.map { (p, n) -> Slice(p, n / 4) }.filter { it.tokens > 0 }.sortedByDescending { it.tokens }
        }

        internal fun largestResults(history: List<ChatMessage>): List<ToolResult> = history
            .filter { it.role == Role.TOOL }
            .map { ToolResult(it.toolName?.takeIf(String::isNotBlank) ?: "tool", it.text.length / 4) }
            .filter { it.tokens >= MIN_RESULT_TOKENS }
            .sortedByDescending { it.tokens }
            .take(MAX_RESULTS)
    }
}
