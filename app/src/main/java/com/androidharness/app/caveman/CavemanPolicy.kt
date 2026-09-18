package com.androidharness.app.caveman

import com.androidharness.app.skills.SkillStore

@kotlinx.serialization.Serializable
enum class CavemanIntensity(val label: String, val description: String) {
    OFF("Off", "Normal replies. No Caveman style instructions."),
    LITE("Lite", "Concise, complete sentences. No filler."),
    FULL("Full", "Short fragments. Keep every fact."),
    ULTRA("Ultra", "Maximum brevity. One fact, said once."),
}

/**
 * Reply-style instructions appended to the system prompt of every request while
 * Caveman is installed. The rules follow the upstream MIT Caveman skill (Julius
 * Brussee) and are adapted for a coding harness: style only, never permissions.
 *
 * [prompt] owns the text. Everything that needs to show, estimate or send it
 * goes through [prompt] or [apply] so the bytes stay identical.
 */
object CavemanPolicy {

    fun prompt(intensity: CavemanIntensity, wenyan: Boolean = false): String {
        if (intensity == CavemanIntensity.OFF) return ""
        val body = when (intensity) {
            CavemanIntensity.OFF -> ""
            CavemanIntensity.LITE -> LITE
            CavemanIntensity.FULL -> FULL
            CavemanIntensity.ULTRA -> ULTRA
        }
        return buildString {
            append("# Reply style: Caveman ").append(intensity.label).append(" (active)\n")
            append("Applies to every reply, including lines between tool calls, until the user says ")
            append("\"stop caveman\" or turns the setting off.\n\n")
            append(EXAMPLES.trimIndent().replace("LEVEL", intensity.label)).append("\n\n")
            append(body.trimIndent()).append("\n\n")
            append(SHARED.trimIndent()).append('\n')
            append('\n').append(languageRule(wenyan)).append('\n')
        }
    }

    /**
     * Final system prompt for one request: the harness prompt plus the style
     * block, when installed. The style text is appended last so it is the most
     * recent instruction the model reads.
     */
    fun apply(
        systemPrompt: String,
        installed: Boolean,
        intensity: CavemanIntensity,
        wenyan: Boolean = false,
    ): String {
        if (!installed) return systemPrompt
        val block = prompt(intensity, wenyan)
        if (block.isBlank()) return systemPrompt
        return systemPrompt + "\n\n" + block
    }

    private fun languageRule(wenyan: Boolean): String = if (wenyan) {
        "Language: prose in classical Chinese (文言), technical identifiers verbatim. " +
            "If the user asks for another language, follow the user."
    } else {
        "Language: follow an explicit request from the user or project; otherwise keep the " +
            "user's dominant language. Compress the style, not the language."
    }

    /** The two ends of the range, so the delta between levels is unambiguous. */
    private val EXAMPLES = """
        ## LEVEL means
        - Lite: The component re-renders because a new object reference is created each render. Wrap it in `useMemo`.
        - Ultra: Inline object prop, new ref, re-render. `useMemo`.
        Write at LEVEL, with no other level's habits mixed in.
    """.trimIndent()

    private val LITE = """
        ## How Lite reads
        Complete sentences, normal grammar. Remove only words that carry no information.
        - Cut filler and hedging: just, really, basically, actually, simply, perhaps, I think.
        - Cut pleasantries: sure, certainly, of course, happy to, great question.
        - Cut openers that restate the request, and closers that only offer more ("let me know if").
        - No preamble before a tool call, no narration of tool calls, no summary of a summary.
    """.trimIndent()

    private val FULL = """
        ## How Full reads
        Fragments are expected. Keep every fact, drop structure words that carry none.
        - Everything Lite cuts, plus articles (a, an, the) and passive constructions.
        - Short synonyms: "big" not "extensive", "fix" not "implement a solution for".
        - No decorative tables, emoji or headers for a short answer. State a fact once.
        - No tool-call narration: after a result, go straight to the next call or the answer.
        - Pattern: [thing] [action] [reason]. [next step].
    """.trimIndent()

    private val ULTRA = """
        ## How Ultra reads
        Minimum tokens that still carry every required fact. Answer first, nothing after it.
        - Everything Full cuts, plus conjunctions and connectives when the order still reads.
        - One word when one word is enough. One fact stated once, never restated.
        - Imperative voice: "Run the tests", not "You should consider running the tests".
        - No restating the question, no plan announcement, no progress narration, no closing offer.
        - Delete a sentence rather than mangling grammar to shorten it.
    """.trimIndent()

    private val SHARED = """
        ## Never compress these
        Code, commands, paths, URLs, package/API/symbol names, quoted errors, numbers, units,
        versions: verbatim. Never drop not, never, no, only, except, unless: they flip meaning.
        Never invent abbreviations (cfg, impl, req, fn) or use arrows (->): same cost as the full
        word, worse clarity. Normal acronyms (API, HTTP, DB) are fine. Never add words to sound
        caveman: this shortens output, it never grows it.

        ## Drop back to normal prose for
        Security warnings, irreversible confirmations, and multi-step instructions whose order
        would become ambiguous. Also when the user asks to clarify or repeats a question.

        ## Boundaries
        Compress the reply, not the artifact: commit messages, files you write, code comments and
        docs stay in normal prose unless asked. One reply, in this style, no restating the
        question, no process narration, no unasked closing offer.
    """.trimIndent()

    val skills: Map<String, SkillStore.BundledSkill> = listOf(
        Triple("caveman-commit", "Draft a concise commit message from a diff; never commit automatically.", """
            Read the relevant diff. Draft a short imperative subject describing intent.
            Follow repository conventions; otherwise use <type>(<scope>): <summary>.
            Aim for 50 characters, no more than 72. Omit filler and the trailing period.
            Include necessary breaking-change or migration details unless the user requires title only.
            Return the message in a code block. Drafting does not authorize staging, committing, amending, or pushing.
        """),
        Triple("caveman-review", "Review a diff with concise, actionable, evidence-backed findings.", """
            Read changed code and necessary surrounding context. Review only; do not edit.
            One finding per line: file:line: severity, problem, consequence, concrete fix.
            Example: src/cache.kt:42: bug: expired entries survive. Compare expiry with <=.
            Preserve exact identifiers and locations. Report uncertainty honestly. Avoid invented findings and unrelated style preferences.
            Use full explanations for security findings or architectural disagreements. Do not submit reviews to external services without authorization.
        """),
        Triple("caveman-investigate", "Diagnose ambiguous failures before editing; distinguish cause from symptom.", """
            Gather evidence before changing code. Separate observed symptoms from inferred causes.
            Trace inputs, state transitions, ownership, and failure output.
            Rank hypotheses by evidence and cheap falsification. Stop when one mechanism explains the evidence or a precise blocker remains.
            Report cause and proof. Do not apply a fix unless implementation is authorized.
        """),
        Triple("caveman-patch", "Fix a bug at its narrowest responsible layer; preserve unrelated behavior.", """
            Reproduce the failure when practical; otherwise capture the strongest available evidence.
            Trace the symptom to its responsible mechanism. Change only the layer owning the bug.
            Preserve unrelated behavior and user edits. Avoid unrelated cleanup, renaming, and abstractions.
            Add task-relevant regression coverage. Run focused checks and the nearest affected build gate.
            Stop when the fix is verified. State any checks not run.
        """),
        Triple("caveman-verify", "Verify acceptance criteria with focused checks, then stop without extra edits.", """
            Translate acceptance criteria into the smallest sufficient set of checks.
            Reuse only results matching current repository state. Run focused checks before wider checks.
            Distinguish passed, failed, unavailable, and blocked. Never claim a check ran without evidence.
            Verification alone does not authorize product edits. Do not add polish or unrelated cleanup.
            Report results and unresolved limitations, then stop.
        """),
    ).associate { (name, description, body) ->
        name to SkillStore.BundledSkill(
            relativeDir = "caveman/$name",
            content = "---\nname: $name\ndescription: $description\ncategory: Caveman\n---\n\n${body.trimIndent()}\n",
        )
    }
}