package com.androidharness.app.ui.chat

import com.androidharness.app.skills.buildSlashSkillMessage

/**
 * Pure slash-command resolution. Kept out of [ChatViewModel] so the
 * send-while-busy path can be unit-tested without Android.
 */
object SlashCommands {

    enum class Kind {
        CLEAR, COMPACT, COST, DOCTOR, INIT, SKILLS, SKILL, SNIPPET, UNKNOWN, PLAIN,
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

    const val DOCTOR_PROMPT =
        "You are the Harness Doctor. Run a self-test of every tool family and report\n" +
            "pass/fail per check, plus the exact error message on any failure. Do NOT\n" +
            "modify user files beyond the temporary fixtures listed; clean them all up\n" +
            "at the end.\n\n" +
            "Run these checks in order:\n\n" +
            "1. FILE CRUD & UNICODE\n" +
            "   - write_file \"doctor/unicode.txt\" containing: \"café 中文 ✅\\nwith tabs\\there\\n\" (no trailing newline after 'here')\n" +
            "   - read_file it back and confirm the 3 lines round-trip byte-identically\n" +
            "   - move_file to \"doctor/moved.txt\", then delete_file it\n" +
            "   - create_dir \"doctor/nested/deep\" then delete_file \"doctor\" (recursive)\n\n" +
            "2. SANDBOX BOUNDARIES (all must be BLOCKED)\n" +
            "   - write_file \"../escape.txt\"       → expect \"outside the workspace\" error\n" +
            "   - read_file \"/etc/hostname\"        → expect \"outside the workspace\" error\n" +
            "   - write_file \"doctor/../../esc.txt\"→ expect \"outside the workspace\" error\n\n" +
            "3. ROOT-DELETE GUARD (Bug #1 regression)\n" +
            "   - write_file \"marker.txt\" (content: \"x\")\n" +
            "   - delete_file \".\"                  → expect REFUSAL, workspace intact\n" +
            "   - verify marker.txt still exists, then delete it\n\n" +
            "4. NEWLINE-LESS PATCH (Bug #2 regression)\n" +
            "   - write_file \"nl.txt\" (via shell): printf 'a\\na\\nunique\\na' > nl.txt\n" +
            "     (must NOT end in a newline; verify with `tail -c 3 nl.txt | xxd`)\n" +
            "   - apply_patch replacing \"unique\" → \"CHANGED\" must SUCCEED on the first try\n" +
            "   - the wrong-hunk error must MENTION the newline condition\n\n" +
            "5. CREATE_DIR OVER FILE (Bug #3 regression)\n" +
            "   - write_file \"occ.txt\" (content: \"x\")\n" +
            "   - create_dir \"occ.txt\"            → expect \"already exists and is a file\" error\n\n" +
            "6. PATCH ATOMICITY\n" +
            "   - write_file \"m.txt\" with a duplicated line + one unique line\n" +
            "   - edit_file on the duplicated line → expect \"appears N times\" ambiguity error\n" +
            "   - multi_edit with [valid edit, missing edit] → expect failure AND rollback\n" +
            "     (verify valid edit was NOT applied)\n\n" +
            "7. SEARCH\n" +
            "   - grep an alternation/anchored pattern over doctor fixtures → matches\n" +
            "   - search_files \"*.txt\" lists expected files\n" +
            "   - grep \"[\" (unclosed class) → expect \"Invalid regex\" error\n\n" +
            "8. SHELL\n" +
            "   - echo to stdout AND stderr → expect SEPARATE --- stdout --- / --- stderr --- sections\n" +
            "   - `false` → expect non-zero exit code surfaced\n" +
            "   - `sleep 8` with timeout_seconds=3 → expect \"killed (timeout)\" + elapsed time\n" +
            "   - `which bash git python3 node` → all resolve in the toolchain\n\n" +
            "9. BACKGROUND PROCS\n" +
            "   - shell_background: `for i in 1 2 3; do echo tick\$i; sleep 1; done`\n" +
            "   - bg_list must show it running, log must contain ONLY tick1/2/3 (no heartbeat)\n" +
            "   - bg_kill it, then bg_list must NOT retain it (or show it pruned)\n\n" +
            "10. GIT\n" +
            "    - git init -q → must produce NO template warning (GIT_TEMPLATE_DIR set)\n" +
            "    - git_commit one fixture → succeeds\n" +
            "    - git_status / git_diff return clean output\n\n" +
            "11. WEB\n" +
            "    - web_search (any query) returns results\n" +
            "    - web_fetch https://example.com returns text\n" +
            "    - http_request GET https://httpbin.org/status/404 → 404 handled cleanly\n\n" +
            "12. SUBAGENTS\n" +
            "    - two task() calls in ONE block → both complete, consistent workspace view\n\n" +
            "13. MEMORY & SKILLS\n" +
            "    - memory_write (append) a test line, read back .harness/memory.md\n" +
            "    - skills_list returns the catalog; skill_view a known skill succeeds\n\n" +
            "CLEANUP: delete doctor/ and every fixture created above; leave the workspace\n" +
            "exactly as you found it.\n\n" +
            "OUTPUT: a table of PASS/FAIL per check. For any FAIL, quote the exact error\n" +
            "message and note which regression it maps to (Bug #1/#2/#3 or a new one)."

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
            "/doctor" -> Result(Kind.DOCTOR, agentText = DOCTOR_PROMPT)
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
