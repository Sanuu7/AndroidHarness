package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData

/**
 * Code feed layout for a turn. Read-only "explore" calls (reading, searching, listing,
 * looking things up) fold into one disclosure per run, the way a terminal agent shows
 * "Read 4 files, searched 2 patterns". Anything that changes or runs something (edits,
 * commands, installs, browser, subagents, questions) keeps its own card, and visible
 * prose ends a run so the transcript stays chronological.
 */
object CodeActivityGroups {

    /** What an explore call counts as in a group's summary. */
    enum class Verb { READ, SEARCH, LIST, WEB, MEMORY, OTHER }

    sealed interface Entry {
        data class Thought(val message: ChatMessage) : Entry
        data class Call(val call: ToolCallData) : Entry
    }

    data class Group(val key: String, val entries: List<Entry>) {
        val calls: List<ToolCallData> get() = entries.filterIsInstance<Entry.Call>().map { it.call }
    }

    sealed interface Slot {
        data class Explore(val group: Group) : Slot
        data class Single(val call: ToolCallData) : Slot
    }

    /**
     * [slots] says what each assistant message renders for its tool calls, in order; a group
     * renders once, at the message where it starts. [foldedThinking] holds message ids whose
     * reasoning sits between two explore calls and therefore lives inside the group.
     */
    data class Plan(val slots: Map<String, List<Slot>>, val foldedThinking: Set<String>) {
        companion object { val EMPTY = Plan(emptyMap(), emptySet()) }
    }

    private val standalone = setOf(
        "task", "ask_user", "todo_write", "pkg_install", "android_sdk_install", "github_create_pull_request",
        "offer_code_handoff", "app_settings_change", "create_artifact", "memory_write", "remember",
    )
    private val bookkeeping = setOf("env_status", "doctor", "file_history", "bg_list", "pkg_search", "pkg_list")

    fun isExplore(call: ToolCallData): Boolean {
        if (call.name in standalone || call.name in LiveCodePreview.tools) return false
        if (com.androidharness.app.browser.BrowserToolActivity.isBrowserTool(call.name) && call.name != "browser_read") return false
        if (call.name in bookkeeping) return true
        return toolPresentation(call).kind in setOf(
            ToolActivityKind.READ, ToolActivityKind.SEARCH, ToolActivityKind.WEB, ToolActivityKind.MEMORY,
        )
    }

    fun verb(name: String): Verb = when (name) {
        "read_file", "file_info", "codegraph_node", "read_image", "read_logcat", "read_tool_output", "read_artifact",
        "skill_view", "visual_guide", "git_diff", "git_log", "git_show", "git_status", "memory_read",
        "browser_read", "browser_screenshot", "browser_get_dom", "browser_get_logs", "browser_get_url", "file_history" -> Verb.READ
        "grep", "search_files", "codegraph_explore", "codegraph_impact", "codegraph_affected", "pkg_search",
        "app_settings_find" -> Verb.SEARCH
        "list_dir", "pkg_list", "skills_list", "bg_list" -> Verb.LIST
        "web_search", "web_fetch", "http_request", "github_api", "github_search", "github_file" -> Verb.WEB
        "memory_search", "recall", "search_chats", "recent_chats", "read_chat_snippet" -> Verb.MEMORY
        else -> Verb.OTHER
    }

    /** Ordered verb counts for a summary line such as "Read 3 files, searched 2 times". */
    fun summary(calls: List<ToolCallData>): List<Pair<Verb, Int>> =
        calls.groupingBy { verb(it.name) }.eachCount().entries
            .sortedBy { it.key.ordinal }.map { it.key to it.value }

    /** Plans assistant messages in chronological order; a run never crosses a user message or a turn. */
    fun plan(turn: List<ChatMessage>): Plan {
        val slots = HashMap<String, List<Slot>>()
        val folded = HashSet<String>()
        var open: MutableList<Entry>? = null
        var openKey: String? = null
        var openOwner: MutableList<Slot>? = null
        var openIndex = -1
        fun close() {
            val entries = open ?: return
            openOwner!![openIndex] = Slot.Explore(Group(openKey!!, entries.toList()))
            open = null; openKey = null; openOwner = null; openIndex = -1
        }
        var lastTurn: String? = null
        for (message in turn) {
            if (message.role == Role.USER) { close(); continue }
            if (message.role != Role.ASSISTANT || message.toolCallId != null) continue
            if (message.turnId != lastTurn) close()
            lastTurn = message.turnId
            val id = message.id ?: continue
            if (message.text.isNotBlank()) close()
            val calls = message.toolCalls.filter { it.name != "task" }
            if (message.toolCalls.any { it.name == "task" }) close()
            val mine = ArrayList<Slot>(calls.size)
            val thought = message.thinking.isNotBlank() || (message.generation.reasoningTokens ?: 0) > 0
            if (open != null && thought && calls.firstOrNull()?.let(::isExplore) == true) {
                folded += id
                open!! += Entry.Thought(message)
            }
            for (call in calls) {
                if (isExplore(call)) {
                    if (open == null) {
                        open = mutableListOf()
                        openKey = "explore-${call.id}"
                        openOwner = mine
                        openIndex = mine.size
                        mine += Slot.Explore(Group(openKey!!, emptyList()))
                    }
                    open!! += Entry.Call(call)
                } else {
                    close()
                    mine += Slot.Single(call)
                }
            }
            slots[id] = mine
        }
        close()
        return Plan(slots, folded)
    }
}
