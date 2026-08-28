package com.androidharness.app.tools

import com.androidharness.app.agent.MemoryTopics
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Topic file names available under .harness/memory (empty when none). */
fun listMemoryTopics(workspace: WorkspaceFs): List<String> = runCatching {
    val dir = workspace.resolve(MemoryTopics.DIR)
    if (!dir.exists || !dir.isDirectory) return emptyList()
    dir.list()
        .filter { it.isFile && it.name.endsWith(".md") }
        .map { it.name.removeSuffix(".md") }
        .sorted()
}.getOrDefault(emptyList())

private fun readMemoryNode(workspace: WorkspaceFs, relPath: String): String? {
    val node = workspace.resolve(relPath)
    return if (node.exists && node.isFile) node.readText() else null
}

/**
 * Reads agent memory without the system prompt's truncation: the core file,
 * a topic file in full, or (with no arguments) the core plus a topic index.
 */
class MemoryReadTool : Tool {
    override val name = "memory_read"
    override val description =
        "Read agent memory without truncation. With no arguments: the core memory plus a " +
            "list of available topic files. With topic: that topic file in full. Use " +
            "memory_search first when you are looking for where something was recorded."
    override val parametersSchema = Schema.obj(
        mapOf(
            "topic" to Schema.string(
                "Optional topic file (as listed by the system prompt or a no-argument call).",
            ),
        ),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val topic = args["topic"]?.jsonPrimitive?.content?.trim()
            if (!topic.isNullOrEmpty()) {
                val path = MemoryTopics.topicPath(topic)
                    ?: throw ToolFailure(
                        "Invalid topic '$topic' — use letters, digits, '-' or '_' (max 48 chars).",
                    )
                val content = readMemoryNode(ctx.workspace, path)
                    ?: return@withContext ToolResult(
                        false,
                        "No memory recorded for topic '$topic' yet. Topics on disk: " +
                            (listMemoryTopics(ctx.workspace).joinToString(", ").ifEmpty { "none" }) + ".",
                    )
                return@withContext ToolResult(true, content)
            }

            val core = readMemoryNode(ctx.workspace, MemoryWriteTool.MEMORY_PATH)
            val topics = listMemoryTopics(ctx.workspace)
            ToolResult(
                true,
                buildString {
                    append("# Core memory (${MemoryWriteTool.MEMORY_PATH})\n")
                    append(core?.takeIf { it.isNotBlank() } ?: "(empty)")
                    append("\n\n# Topic files (${MemoryTopics.DIR}/)\n")
                    if (topics.isEmpty()) append("(none)")
                    else topics.forEach { append("- ${it}.md\n") }
                },
            )
        }
}

/**
 * Case-insensitive search across the core memory and every topic file.
 * Returns path:line matches so the agent can follow up with memory_read.
 */
class MemorySearchTool : Tool {
    override val name = "memory_search"
    override val description =
        "Search agent memory (core memory and all topic files) for a case-insensitive " +
            "substring. Returns file:line matches. Use it to recall details that are not in " +
            "the always-loaded core memory, and before memory_write to avoid duplicate notes."
    override val parametersSchema = Schema.obj(
        mapOf("query" to Schema.string("Text to search for (case-insensitive).")),
        required = listOf("query"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val query = args["query"]?.jsonPrimitive?.content
                ?: throw ToolFailure("Missing required argument: query")
            val needle = query.lowercase()

            val files = buildList {
                add(MemoryWriteTool.MEMORY_PATH to (readMemoryNode(ctx.workspace, MemoryWriteTool.MEMORY_PATH) ?: ""))
                listMemoryTopics(ctx.workspace).forEach { topic ->
                    add("${MemoryTopics.DIR}/$topic.md" to (readMemoryNode(ctx.workspace, "${MemoryTopics.DIR}/$topic.md") ?: ""))
                }
            }

            val matches = StringBuilder()
            var matchCount = 0
            for ((path, content) in files) {
                content.lineSequence().forEachIndexed { idx, line ->
                    if (line.lowercase().contains(needle)) {
                        matchCount++
                        if (matches.length < 8_000) {
                            matches.append(path).append(':').append(idx + 1).append(": ")
                                .append(line.trim().take(300)).append('\n')
                        }
                    }
                }
            }

            if (matchCount == 0) {
                ToolResult(
                    false,
                    "No memory matches '${query.take(80)}'. Topics on disk: " +
                        (listMemoryTopics(ctx.workspace).joinToString(", ").ifEmpty { "none" }) + ".",
                )
            } else {
                val truncated = if (matches.length >= 8_000) "\n[truncated]" else ""
                ToolResult(true, "$matchCount match(es):\n${matches.trimEnd()}$truncated")
            }
        }
}
