package com.androidharness.app.agent

/**
 * Tracks which tools are currently active (sent to the model) and their usage.
 *
 * Always-on tools are never removed. On-demand tools are activated via
 * [RequestToolTool] and removed during compaction if unused or stale.
 */
class ToolManager(
    private val allToolNames: Set<String>,
    private val alwaysOn: Set<String> = DEFAULT_ALWAYS_ON,
) {
    data class Usage(
        val count: Int = 0,
        val lastUsedAt: Int = 0, // compact cycle counter
    )

    /** Tools currently sent to the model. */
    val active: MutableSet<String> = alwaysOn.toMutableSet()

    /** Usage stats per tool. */
    val usage: MutableMap<String, Usage> = mutableMapOf()

    /** Monotonically increasing compact cycle counter. */
    var compactCycle: Int = 0
        private set

    /**
     * Tools available for request (not in always-on, not yet active).
     */
    val available: Set<String>
        get() = allToolNames - alwaysOn - active

    /**
     * Activate a tool (and optionally its family). Returns list of all newly
     * activated tool names.
     */
    fun activate(name: String, autoLoadFamily: Boolean = true): List<String> {
        if (name in active) return emptyList()
        val loaded = mutableListOf(name)
        active.add(name)
        usage.putIfAbsent(name, Usage())
        if (autoLoadFamily) {
            val family = FAMILIES.entries.firstOrNull { it.value.contains(name) }?.value
            if (family != null) {
                for (t in family) {
                    if (t !in active) {
                        active.add(t)
                        usage.putIfAbsent(t, Usage())
                        loaded.add(t)
                    }
                }
            }
        }
        return loaded
    }

    /** Record that a tool was called by the model. Auto-activates if not yet active. */
    fun onToolCalled(name: String): List<String> {
        val newlyLoaded = mutableListOf<String>()
        if (name !in active) {
            newlyLoaded += activate(name, autoLoadFamily = true)
        }
        val u = usage[name] ?: Usage()
        usage[name] = u.copy(count = u.count + 1, lastUsedAt = compactCycle)
        return newlyLoaded
    }

    /**
     * Advance the compact cycle and determine which tools to remove.
     * Returns the set of tool names to deactivate.
     */
    fun onCompact(): Set<String> {
        compactCycle++
        val toRemove = mutableSetOf<String>()
        for (tool in active) {
            if (tool in alwaysOn) continue
            val u = usage[tool] ?: Usage()
            val isStale = u.count <= 2 && (compactCycle - u.lastUsedAt) > 3
            if (u.count == 0 || isStale) {
                toRemove.add(tool)
            }
        }
        if (toRemove.isNotEmpty()) {
            active.removeAll(toRemove)
        }
        return toRemove
    }

    /**
     * The set of active tool names to pass to [ToolRegistry.restrictedTo].
     * Returns null if all tools should be sent (no filtering).
     */
    fun activeSet(): Set<String>? {
        if (active.size >= allToolNames.size) return null
        return active.toSet()
    }

    companion object {
        val DEFAULT_ALWAYS_ON: Set<String> = setOf(
            "shell",
            "read_file",
            "write_file",
            "edit_file",
            "grep",
            "search_files",
            "web_fetch",
            "list_dir",
            "todo_write",
            "web_search",
            "skill_view",
            "task",
            "ask_user",
            "request_tool",
        )

        val FAMILIES: Map<String, Set<String>> = mapOf(
            "files" to setOf(
                "list_dir", "read_file", "file_info", "write_file", "edit_file",
                "multi_edit", "apply_patch", "search_files", "grep",
                "create_dir", "delete_file", "move_file",
            ),
            "execution" to setOf(
                "shell", "shell_background", "env_status", "doctor",
                "read_logcat", "pkg_install", "android_sdk_install",
                "pkg_search", "pkg_list", "bg_list", "bg_kill",
            ),
            "git" to setOf(
                "git_status", "git_diff", "git_log", "git_show",
                "git_commit", "git_branch", "git_branch_manage",
                "git_checkout", "git_push", "git_pull",
            ),
            "web" to setOf("web_fetch", "web_search", "http_request"),
            "memory" to setOf(
                "memory_write", "memory_read", "memory_search",
                "remember", "recall",
            ),
            "coordination" to setOf("task", "ask_user", "todo_write"),
            "skills" to setOf("skill_view", "skills_list", "skill_manage"),
            "codegraph" to setOf(
                "codegraph_explore", "codegraph_node", "codegraph_impact",
                "codegraph_affected", "codegraph_sync",
            ),
            "browser" to setOf(
                "browser_navigate", "browser_click", "browser_type",
                "browser_scroll", "browser_eval", "browser_get_dom",
                "browser_get_logs", "browser_screenshot", "browser_wait_for",
                "browser_back", "browser_forward", "browser_refresh",
                "browser_get_url", "browser_open_external",
            ),
            "artifacts" to setOf("create_artifact", "read_artifact"),
        )

        /** Human-readable list of families for the request_tool description. */
        fun familySummary(): String = FAMILIES.entries.joinToString("\n") { (name, tools) ->
            "  $name: ${tools.joinToString(", ")}"
        }
    }
}
