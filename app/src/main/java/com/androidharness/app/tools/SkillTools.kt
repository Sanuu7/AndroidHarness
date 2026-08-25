package com.androidharness.app.tools

import com.androidharness.app.skills.SkillSource
import com.androidharness.app.skills.SkillStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SkillViewTool(
    private val store: SkillStore,
) : Tool {
    override val name = "skill_view"
    override val description =
        "Load a skill's full instructions, or one of its supporting files. " +
            "Call this BEFORE acting whenever a catalog skill matches the task. " +
            "name is the skill name from the catalog. file_path is optional " +
            "(e.g. references/catalog.md)."
    override val parametersSchema = Schema.obj(
        mapOf(
            "name" to Schema.string("Skill name from the catalog, e.g. systematic-debugging."),
            "file_path" to Schema.string("Optional supporting file inside the skill (references/, templates/, scripts/, assets/)."),
        ),
        required = listOf("name"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = args["name"]?.jsonPrimitive?.content?.trim()
            ?: throw ToolFailure("Missing required argument: name")
        val filePath = args["file_path"]?.jsonPrimitive?.content
        val viewed = store.view(name, filePath).getOrElse { err ->
            return ToolResult(false, err.message ?: "Skill '$name' not found.")
        }
        val origin = when (viewed.source) {
            SkillSource.BUNDLED -> "bundled"
            SkillSource.USER -> "your skills"
            SkillSource.PROJECT -> "this workspace"
        }
        val sb = StringBuilder()
        if (filePath.isNullOrBlank()) {
            sb.append("# ").append(viewed.name).append("  (").append(origin).append(")\n\n")
            sb.append(viewed.content.trimEnd()).append('\n')
            if (viewed.linkedFiles.isNotEmpty()) {
                sb.append("\nSupporting files:\n")
                viewed.linkedFiles.forEach { sb.append("- ").append(it).append('\n') }
                sb.append("Load extras with skill_view(name=\"").append(viewed.name)
                    .append("\", file_path=\"…\")\n")
            }
        } else {
            sb.append("# ").append(viewed.name).append(" / ").append(filePath)
                .append("  (").append(origin).append(")\n\n")
            sb.append(viewed.content.trimEnd()).append('\n')
        }
        return ToolResult(true, sb.toString().trimEnd())
    }
}

class SkillsListTool(
    private val store: SkillStore,
) : Tool {
    override val name = "skills_list"
    override val description =
        "List installed skills (name, description, category, source, enabled). " +
            "Use when the catalog is not enough or you need to find a skill by theme."
    override val parametersSchema = Schema.obj(
        mapOf("category" to Schema.string("Optional category filter, e.g. design or android.")),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val category = args["category"]?.jsonPrimitive?.content?.trim()?.ifBlank { null }
        val skills = store.list().filter { category == null || it.category == category }
        if (skills.isEmpty()) {
            return ToolResult(true, "No skills found" + if (category != null) " in '$category'." else ".")
        }
        val text = buildString {
            var last = ""
            for (s in skills) {
                if (s.category != last) {
                    if (isNotEmpty()) append('\n')
                    append(s.category).append(":\n")
                    last = s.category
                }
                val src = when (s.source) {
                    SkillSource.BUNDLED -> "bundled"
                    SkillSource.USER -> "user"
                    SkillSource.PROJECT -> "workspace"
                }
                val flag = if (s.enabled) "" else " [disabled]"
                append("- ").append(s.name).append(" (").append(src).append(flag).append("): ")
                    .append(s.description).append('\n')
            }
            append("\nUse skill_view(name) to load one.")
        }
        return ToolResult(true, text.trimEnd())
    }
}

class SkillManageTool(
    private val store: SkillStore,
) : Tool {
    override val name = "skill_manage"
    override val description =
        "Create, patch, or delete a user skill so a proven workflow can be reused. " +
            "create needs name + content (full SKILL.md with frontmatter). " +
            "patch needs name + old_string + new_string (unique match). " +
            "delete needs name. Bundled skills are copied into your skills on patch, not overwritten in the APK."
    override val parametersSchema = Schema.obj(
        mapOf(
            "action" to Schema.string("create | patch | delete"),
            "name" to Schema.string("Skill name (required for patch and delete; optional for create if frontmatter has it)."),
            "content" to Schema.string("Full SKILL.md for create, including --- frontmatter ---."),
            "old_string" to Schema.string("Exact text to replace when action is patch."),
            "new_string" to Schema.string("Replacement text when action is patch."),
        ),
        required = listOf("action"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content?.trim()?.lowercase()
            ?: throw ToolFailure("Missing required argument: action")
        return when (action) {
            "create" -> {
                val content = args["content"]?.jsonPrimitive?.content
                    ?: throw ToolFailure("create requires content (a full SKILL.md).")
                val parsed = runCatching { store.saveUser(content) }
                    .getOrElse { return ToolResult(false, it.message ?: "Could not save skill.") }
                ToolResult(true, "Created user skill '${parsed.name}'. It will appear in the catalog on the next run.")
            }
            "patch" -> {
                val name = args["name"]?.jsonPrimitive?.content?.trim()
                    ?: throw ToolFailure("patch requires name.")
                val old = args["old_string"]?.jsonPrimitive?.content
                    ?: throw ToolFailure("patch requires old_string.")
                val new = args["new_string"]?.jsonPrimitive?.content
                    ?: throw ToolFailure("patch requires new_string.")
                store.patchUserOrCopy(name, old, new).fold(
                    onSuccess = { ToolResult(true, it) },
                    onFailure = { ToolResult(false, it.message ?: "Patch failed.") },
                )
            }
            "delete" -> {
                val name = args["name"]?.jsonPrimitive?.content?.trim()
                    ?: throw ToolFailure("delete requires name.")
                if (store.deleteUser(name)) ToolResult(true, "Deleted user skill '$name'.")
                else ToolResult(false, "No user skill named '$name' to delete (bundled skills cannot be deleted).")
            }
            else -> ToolResult(false, "Unknown action '$action'. Use create, patch, or delete.")
        }
    }
}

fun skillTemplate(name: String = "my-skill", description: String = "When to load this skill, as a situation."): String = """
    ---
    name: $name
    description: $description
    category: general
    ---

    # ${name.replace('-', ' ').replaceFirstChar { it.uppercase() }}

    ## When to use
    -

    ## When not to use
    -

    ## Procedure
    1. 
    2. 

    ## Pitfalls
    -

    ## Verification
    -
""".trimIndent()
