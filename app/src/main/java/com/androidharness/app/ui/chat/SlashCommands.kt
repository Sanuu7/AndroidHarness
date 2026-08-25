package com.androidharness.app.ui.chat

import com.androidharness.app.skills.buildSlashSkillMessage

/**
 * Pure slash-command resolution. Kept out of [ChatViewModel] so the
 * send-while-busy path can be unit-tested without Android.
 */
object SlashCommands {

    enum class Kind {
        CLEAR, COMPACT, COST, INIT, SKILLS, SKILL, SNIPPET, UNKNOWN, PLAIN,
    }

    enum class Dispatch { START, QUEUE }

    data class Result(
        val kind: Kind,
        val agentText: String? = null,
        val error: String? = null,
    ) {
        val startsAgent: Boolean get() = agentText != null
    }

    data class Target(val mode: Dispatch, val text: String)

    sealed interface Pick {
        data class Send(val text: String) : Pick
        data class Insert(val text: String) : Pick
        data class AttachSkill(val name: String, val leftover: String) : Pick
    }

    const val INIT_PROMPT =
        "Explore this workspace thoroughly (files, structure, conventions), then write " +
            "an AGENTS.md at the workspace root describing the project, how to build/run it, " +
            "and the coding conventions future agent sessions should follow. " +
            "If an AGENTS.md already exists, improve it."

    fun resolve(
        input: String,
        skillNames: Set<String>,
        snippetBodies: Map<String, String>,
        skillContent: (String) -> String?,
    ): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) {
            return Result(Kind.PLAIN, agentText = trimmed.ifEmpty { null })
        }
        val parts = trimmed.split(" ", limit = 2)
        val command = parts[0].lowercase()
        val argument = parts.getOrNull(1).orEmpty()
        return when (command) {
            "/clear" -> Result(Kind.CLEAR)
            "/compact" -> Result(Kind.COMPACT)
            "/cost" -> Result(Kind.COST)
            "/skills" -> Result(Kind.SKILLS)
            "/init" -> Result(Kind.INIT, agentText = INIT_PROMPT)
            else -> {
                val skillName = command.removePrefix("/")
                if (skillName in skillNames) {
                    val content = skillContent(skillName)
                    if (content != null) {
                        return Result(
                            Kind.SKILL,
                            agentText = buildSlashSkillMessage(skillName, content, argument),
                        )
                    }
                }
                val snippetBody = snippetBodies.entries.firstOrNull {
                    it.key.lowercase() == skillName
                }?.value
                if (snippetBody != null) {
                    val body = if (argument.isNotBlank()) {
                        snippetBody.replace("\$ARG", argument)
                    } else snippetBody
                    return Result(Kind.SNIPPET, agentText = body)
                }
                Result(Kind.UNKNOWN, error = "Unknown command: $command")
            }
        }
    }

    fun dispatchTarget(isRunning: Boolean, agentText: String): Target =
        Target(if (isRunning) Dispatch.QUEUE else Dispatch.START, agentText)

    /**
     * Slash-menu tap. Skills become a composer badge and never send.
     * Snippets still drop into the box as text. Local commands fire now.
     */
    fun pickAction(entryCommand: String, typedQuery: String, kind: Kind): Pick {
        if (kind == Kind.SKILL) {
            val leftover = if (typedQuery.startsWith(entryCommand)) {
                typedQuery.removePrefix(entryCommand).trim()
            } else ""
            return Pick.AttachSkill(entryCommand.removePrefix("/"), leftover)
        }
        if (kind == Kind.SNIPPET) {
            val rest = if (typedQuery.startsWith(entryCommand)) {
                typedQuery.removePrefix(entryCommand)
            } else ""
            return if (rest.isBlank()) Pick.Insert("$entryCommand ")
            else Pick.Send(typedQuery.trim())
        }
        return Pick.Send(entryCommand)
    }

    /** What the composer actually sends once the user taps send. */
    fun composeSend(attachedSkill: String?, typed: String): String {
        val note = typed.trim()
        val skill = attachedSkill?.trim().orEmpty()
        if (skill.isEmpty()) return note
        return if (note.isEmpty()) "/$skill" else "/$skill $note"
    }
}
