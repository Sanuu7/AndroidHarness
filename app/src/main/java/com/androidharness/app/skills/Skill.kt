package com.androidharness.app.skills

enum class SkillSource { BUNDLED, USER, PROJECT }

data class SkillMeta(
    val name: String,
    val description: String,
    val category: String,
    val source: SkillSource,
    val relativeDir: String,
    val enabled: Boolean,
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
