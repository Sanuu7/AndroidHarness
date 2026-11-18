package com.androidharness.app.skills

enum class SkillSource { BUNDLED, USER, PROJECT }

data class SkillMeta(
    val name: String,
    val description: String,
    val category: String,
    val source: SkillSource,
    val relativeDir: String,
    val enabled: Boolean,
    /** False when the skill says disable-model-invocation: only the user can run it, as /name. */
    val modelInvocable: Boolean = true,
    /** False when the skill says user-invocable: false: it is left out of the slash list. */
    val userInvocable: Boolean = true,
)

/** One skill's full file as the settings screen shows it; [folder] is set when the skill has a real directory. */
data class SkillDocument(
    val meta: SkillMeta,
    val raw: String,
    val files: List<String>,
    val folder: String?,
)

data class SkillView(
    val name: String,
    val description: String,
    val category: String,
    val content: String,
    val source: SkillSource,
    val linkedFiles: List<String>,
)

data class ParsedSkill(
    val name: String,
    val description: String,
    val category: String,
    val body: String,
    val raw: String,
    /** Every top-level frontmatter field, such as allowed-tools, model or argument-hint. */
    val fields: Map<String, String> = emptyMap(),
) {
    val catalogDescription: String
        get() = if (description.length <= MAX_CATALOG_DESCRIPTION) description
        else description.take(MAX_CATALOG_DESCRIPTION - 3).trimEnd() + "..."

    companion object {
        const val MAX_CATALOG_DESCRIPTION = 80
        const val MAX_NAME = 64
        const val MAX_USER_CHARS = 20_000
        val NAME_REGEX = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
    }
}

class SkillParseException(message: String) : Exception(message)
