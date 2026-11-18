package com.androidharness.app.skills

/**
 * Skills written for Claude Code name their own folder with ${CLAUDE_SKILL_DIR}, their plugin with
 * ${CLAUDE_PLUGIN_ROOT} and the open project with ${CLAUDE_PROJECT_DIR}. Expanding them to real paths when a
 * skill is loaded lets the original SKILL.md run unedited from where it is installed. A variable with no known
 * value stays as written, so the model sees what the skill asked for instead of an empty path.
 */
object SkillVariables {
    private const val NAMES = "CLAUDE_SKILL_DIR|CLAUDE_PLUGIN_ROOT|CLAUDE_PROJECT_DIR"
    // The bare form must not swallow a longer name such as $CLAUDE_SKILL_DIRECTORY.
    private val variable = Regex("\\$(?:\\{($NAMES)\\}|($NAMES)(?![A-Za-z0-9_]))")

    fun expand(text: String, skillDir: String?, pluginRoot: String?, projectDir: String?): String {
        if ('$' !in text) return text
        return variable.replace(text) { match ->
            val value = when (match.groupValues[1].ifEmpty { match.groupValues[2] }) {
                "CLAUDE_SKILL_DIR" -> skillDir
                "CLAUDE_PLUGIN_ROOT" -> pluginRoot
                else -> projectDir
            }
            value ?: match.value
        }
    }
}

/** A short note for skills written for another agent host, naming this app's equivalent of what they ask for. */
object SkillHostNotes {
    private val delegation = Regex("subagent_type|\\bAgent tool\\b|\\bTask tool\\b", RegexOption.IGNORE_CASE)

    fun forBody(body: String): String =
        if (!delegation.containsMatchIn(body)) ""
        else "Note: where this skill says the Agent tool or Task tool, use this app's task tool. Put the whole brief " +
            "in prompt, because a subagent shares no context, and a short name. subagent_type and description are not " +
            "parameters here. Call task several times in one message to run them at once. A subagent can only write " +
            "files when Subagent action tools is on in Settings.\n\n"
}
