package com.androidharness.app.skills

object SkillParser {

    fun parse(raw: String): ParsedSkill {
        val text = raw.replace("\r\n", "\n").trimStart('\uFEFF')
        if (!text.startsWith("---")) {
            throw SkillParseException("Skill must start with YAML frontmatter (---).")
        }
        val rest = text.removePrefix("---")
        val close = rest.indexOf("\n---")
        if (close < 0) {
            throw SkillParseException("Frontmatter is not closed with ---.")
        }
        val yaml = rest.substring(0, close).trim()
        val body = rest.substring(close + 4).trim()
        if (body.isEmpty()) {
            throw SkillParseException("Skill body is empty.")
        }

        val fields = parseSimpleYaml(yaml)
        val name = fields["name"]?.trim().orEmpty()
        val description = fields["description"]?.trim().orEmpty()
        val category = fields["category"]?.trim()?.ifBlank { null } ?: "general"

        if (name.isEmpty()) throw SkillParseException("Missing required field: name.")
        if (".." in name || name.contains('/') || name.contains('\\')) {
            throw SkillParseException("Skill name cannot contain path separators or ..")
        }
        if (!ParsedSkill.NAME_REGEX.matches(name) || name.length > ParsedSkill.MAX_NAME) {
            throw SkillParseException(
                "Skill name must be lowercase letters, digits, and hyphens (max ${ParsedSkill.MAX_NAME}).",
            )
        }
        if (description.isEmpty()) throw SkillParseException("Missing required field: description.")

        return ParsedSkill(
            name = name,
            description = description,
            category = category.ifBlank { "general" },
            body = body,
            raw = text.trimEnd() + "\n",
        )
    }

    fun validateUserContent(raw: String): ParsedSkill {
        if (raw.length > ParsedSkill.MAX_USER_CHARS) {
            throw SkillParseException("Skill is too long (max ${ParsedSkill.MAX_USER_CHARS} characters).")
        }
        return parse(raw)
    }

    /**
     * Tiny YAML mapping parser for the fields we actually use.
     * Handles `key: value` and `key: "quoted value"`. Nested maps are ignored.
     */
    private fun parseSimpleYaml(yaml: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        yaml.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            if (trimmed.startsWith("-")) return@forEach
            val colon = trimmed.indexOf(':')
            if (colon <= 0) return@forEach
            val key = trimmed.substring(0, colon).trim()
            var value = trimmed.substring(colon + 1).trim()
            if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                value = value.substring(1, value.length - 1)
            } else if (value.startsWith("'") && value.endsWith("'") && value.length >= 2) {
                value = value.substring(1, value.length - 1)
            }
            if (key.isNotEmpty() && value.isNotEmpty() && key !in out) {
                out[key] = value
            }
        }
        return out
    }
}

fun buildSkillsPromptBlock(catalogIndex: String): String {
    if (catalogIndex.isBlank()) return ""
    return buildString {
        append("## Skills (mandatory)\n")
        append("Before replying, scan the skills below. If a skill matches or is even partially relevant ")
        append("to your task, you MUST load it with skill_view(name) and follow its instructions. ")
        append("Err on the side of loading. Skills contain specialized workflows that outperform ")
        append("guessing. Load the skill even if you think you could handle the task with shell, ")
        append("grep, or web_search.\n")
        append("If a skill is outdated or wrong after you use it, patch it with skill_manage. ")
        append("After a difficult task that produced a reusable workflow, offer to save it as a skill.\n")
        append("If genuinely none apply, proceed without loading one.\n\n")
        append("<available_skills>\n")
        append(catalogIndex.trimEnd())
        append("\n</available_skills>\n")
    }
}

fun buildSlashSkillMessage(name: String, content: String, instruction: String): String {
    val header = buildString {
        append("[IMPORTANT: The user has invoked the \"")
        append(name)
        append("\" skill.\n")
        append("The full skill content is loaded below.]")
    }
    val body = content.trimEnd()
    val rest = instruction.trim()
    return if (rest.isEmpty()) {
        "$header\n\n$body\n"
    } else {
        "$header\n\n$body\n\n" +
            "The user has provided the following instruction alongside the skill invocation: $rest\n"
    }
}

fun slashSkillInstruction(text: String): String? {
    if (slashInvokedSkillName(text) == null) return null
    val marker = "The user has provided the following instruction alongside the skill invocation: "
    val idx = text.lastIndexOf(marker)
    if (idx < 0) return ""
    return text.substring(idx + marker.length).trim()
}

fun slashInvokedSkillName(text: String): String? {
    val prefix = "[IMPORTANT: The user has invoked the \""
    if (!text.startsWith(prefix)) return null
    val end = text.indexOf('"', prefix.length)
    if (end <= prefix.length) return null
    return text.substring(prefix.length, end)
}
