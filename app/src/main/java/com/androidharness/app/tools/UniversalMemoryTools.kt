package com.androidharness.app.tools

import com.androidharness.app.data.MemoryCategory
import com.androidharness.app.data.MemoryRepository
import com.androidharness.app.data.MemorySection
import com.androidharness.app.data.MemoryScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Saves durable facts to the universal memory shared by Chat and Code. The
 * workspace memory_write tool stays separate for project notes on disk.
 */
class RememberTool(private val memory: MemoryRepository) : Tool {
    override val name = "remember"
    override val description =
        "Save a durable fact in the current Chat or Code memory. Recall related facts before saving. " +
        "To correct a saved fact, pass its memory_id and exact previous_text instead of creating a duplicate. " +
        "For a consolidated memory, first retrieve its full text with recall(memory_id) and preserve the other facts when editing. " +
            "Use it for stable user preferences, ongoing work, decisions, and reusable project facts. " +
            "Keep each entry short and standalone. Do not save secrets, credentials, transient details, " +
            "or long code. Use recall first when you may be repeating or correcting an existing memory."
    override val parametersSchema = Schema.obj(
        mapOf(
            "memory_id" to Schema.string("Existing memory ID to update. Omit only for a new fact."),
            "previous_text" to Schema.string("Required when updating: exact text most recently supplied or recalled for this ID."),
            "allow_similar" to Schema.boolean("Set true only after checking the similar memory and confirming these are distinct facts."),
            "text" to Schema.string("The fact to remember, written as a short standalone statement."),
            "scope" to Schema.string(
                "'global' applies throughout the current Chat/Code memory section; 'project' only to the current project, " +
                    "'auto' (default) uses the project when this conversation has one and global otherwise.",
            ),
            "category" to Schema.string(
                "One of profile, preference, project, decision, or fact (default fact).",
            ),
        ),
        required = listOf("text"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!memory.isEnabled(ctx.sessionId)) throw ToolFailure("Memory is off for this section in Settings > Memory.")
        val text = args["text"]?.jsonPrimitive?.content
            ?: throw ToolFailure("Missing required argument: text")
        val category = args["category"]?.jsonPrimitive?.content?.let { MemoryCategory.fromStored(it) }
        val requested = when (args["scope"]?.jsonPrimitive?.content?.trim()?.lowercase()) {
            "global" -> MemoryScope.GLOBAL
            "project" -> MemoryScope.PROJECT
            // Stable user traits belong everywhere even when the chat has a project.
            else -> if (category in setOf(MemoryCategory.PROFILE, MemoryCategory.PREFERENCE) ||
                memory.projectIdForSession(ctx.sessionId) == null) MemoryScope.GLOBAL
            else MemoryScope.PROJECT
        }
        val result = try {
            memory.rememberResult(text, category, requested, ctx.sessionId,
                memoryId = args["memory_id"]?.jsonPrimitive?.content,
                previousText = args["previous_text"]?.jsonPrimitive?.content,
                allowSimilar = args["allow_similar"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true)
        } catch (e: IllegalArgumentException) { throw ToolFailure(e.message ?: "Could not save memory.") }
          catch (e: IllegalStateException) { throw ToolFailure(e.message ?: "Could not save memory.") }
        val action = result.action.name.lowercase()
        return ToolResult(true, when (result.action) {
            MemoryRepository.Action.SAVED -> "Memory saved."
            MemoryRepository.Action.UPDATED -> "Memory updated."
            MemoryRepository.Action.UNCHANGED -> "Already saved; no duplicate was created."
        } + "\n[memory_id:${result.memory.id}]\n[memory_action:$action]")

    }
}

/** Searches the same universal memory from either section. */
class RecallTool(private val memory: MemoryRepository) : Tool {
    override val name = "recall"
    override val description =
        "Search saved memories for the current section, including existing shared memories. " +
        "Use section=chat or section=code for an explicit other-section lookup when enabled in Settings. Use it when the user refers to " +
            "something from an earlier conversation or when stored preferences or project facts would help. " +
            "With no query, returns the most recently saved memories."
    override val parametersSchema = Schema.obj(
        mapOf("memory_id" to Schema.string("Read the complete memory by ID before updating, especially an organized digest."),
            "query" to Schema.string("Optional words to find in saved memories."),
            "section" to Schema.string("current (default), chat, or code. Other-section access requires its Settings switch.")),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!memory.isEnabled(ctx.sessionId)) throw ToolFailure("Memory is off for this section in Settings > Memory.")
        val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        val target = when (args["section"]?.jsonPrimitive?.content?.lowercase()) {
            null, "current" -> null
            "chat" -> MemorySection.CHAT
            "code" -> MemorySection.CODE
            else -> throw ToolFailure("section must be current, chat, or code.")
        }
        args["memory_id"]?.jsonPrimitive?.content?.let { id ->
            val entry = try { memory.recallById(id, ctx.sessionId, target) }
                catch (e: IllegalStateException) { throw ToolFailure(e.message ?: "Memory is unavailable.") }
            return ToolResult(true, "[id:${entry.id} ${entry.section.lowercase()}/${entry.category.lowercase()}${if (entry.isSummary) "/digest" else ""}]\n${entry.text}")
        }
        val results = try { memory.search(query, ctx.sessionId, target) }
            catch (e: IllegalStateException) { throw ToolFailure(e.message ?: "Memory lookup is unavailable.") }
        if (results.isEmpty()) {
            return ToolResult(true, "No saved memories matched" + if (query.isEmpty()) "." else " '$query'.")
        }
        val output = StringBuilder()
        for (entry in results) {
            val scope = if (entry.scope == MemoryScope.PROJECT.name) "project" else "global"
            val text = if (entry.isSummary) entry.text.lines().filter { it.isNotBlank() && !it.endsWith(":") }
                .sortedByDescending { com.androidharness.app.data.MemoryMatch.relevance(it, query) }
                .take(12).joinToString("\n").take(4_000)
                else com.androidharness.app.data.MemoryText.excerpt(entry.text, 4_000, query)
            val partial = entry.isSummary || text != entry.text
            val line = "- [id:${entry.id} ${entry.section.lowercase()}/$scope/${entry.category.lowercase()}${if (entry.isSummary) "/digest" else ""}${if (partial) "; recall(memory_id) for full text" else ""}] $text\n"
            if (output.length + line.length > 12_000) {
                output.append("More matches are available; refine your query.\n")
                break
            }
            output.append(line)
        }
        return ToolResult(true, output.toString())

    }
}
