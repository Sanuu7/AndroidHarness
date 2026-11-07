package com.androidharness.app.agent

import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformWhile

/**
 * What a Code question does when nobody answers it: which options the agent recommended,
 * the answer the app gives on its behalf, and the countdown that decides when.
 */
object QuestionAutoContinue {
    const val DELAY_MS = 5 * 60 * 1000L

    private val marker = Regex("\\s*[(\\[]\\s*recommended\\s*[)\\]]\\s*", RegexOption.IGNORE_CASE)

    /** Options with any "(Recommended)" label removed, since the card shows its own badge. */
    data class Options(val labels: List<String>, val recommended: List<Int>)

    /**
     * Resolves what the model recommended against the options it offered.
     *
     * [named] holds option texts or one-based numbers from the `recommended` argument. A label
     * that carries "(Recommended)" counts too. Without any usable hint the first option is the
     * recommendation, which the tool description tells the model to rely on. A single choice
     * keeps one recommendation; a multiple choice keeps every one it named.
     */
    fun resolve(options: List<String>, named: List<String>, multiSelect: Boolean): Options {
        if (options.isEmpty()) return Options(emptyList(), emptyList())
        val labels = options.map { clean(it) }
        val picked = linkedSetOf<Int>()
        options.forEachIndexed { index, text -> if (marker.containsMatchIn(text)) picked += index }
        named.map { it.trim() }.filter { it.isNotEmpty() }.forEach { hint ->
            val byText = labels.indexOfFirst { it.equals(clean(hint), ignoreCase = true) }
            val byNumber = hint.toIntOrNull()?.minus(1)?.takeIf { it in labels.indices }
            (if (byText >= 0) byText else byNumber)?.let { picked += it }
        }
        val chosen = picked.sorted().ifEmpty { listOf(0) }
        return Options(labels, if (multiSelect) chosen else chosen.take(1))
    }

    private fun clean(text: String) = marker.replace(text, " ").trim().ifEmpty { text.trim() }

    /** The answer for an unanswered question, or null when there is nothing to recommend. */
    fun answer(request: QuestionRequest): String? =
        request.recommended.mapNotNull(request.options::getOrNull).takeIf { it.isNotEmpty() }?.joinToString("; ")

    /** The wording the model gets back, so it knows no person made this choice. */
    fun toolResult(answer: String): String =
        "The user did not answer within 5 minutes, so the app continued with the option you recommended: $answer. " +
            "This was not the user's own choice. Carry on with it, say in your next message that you did, and be ready to change course."

    /**
     * Runs the countdown for one open question. [enabled] is the setting, [open] stays true
     * while the question is still waiting. Turning the setting off cancels the countdown for
     * this question; turning it on again counts a fresh five minutes from that moment. The
     * first countdown starts when the question was asked, so a question that appears while the
     * setting is on never waits longer than five minutes.
     *
     * [onDeadline] publishes the end time for the card, or null while no countdown runs.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun watch(
        askedAt: Long,
        enabled: Flow<Boolean>,
        open: Flow<Boolean>,
        onDeadline: (Long?) -> Unit,
        onExpired: () -> Unit,
        clock: () -> Long = System::currentTimeMillis,
        delayMs: Long = DELAY_MS,
    ) {
        var first = true
        // Null means the question is no longer waiting. It is the last state, and because it
        // reaches collectLatest it also cancels a countdown that is still running.
        combine(enabled, open) { on, stillOpen -> if (stillOpen) on else null }
            .distinctUntilChanged()
            .transformWhile { state -> emit(state); state != null }
            .collectLatest { on ->
                if (on == null) return@collectLatest
                val fresh = first
                first = false
                if (!on) {
                    onDeadline(null)
                    return@collectLatest
                }
                val deadline = (if (fresh) askedAt else clock()) + delayMs
                onDeadline(deadline)
                delay((deadline - clock()).coerceAtLeast(0))
                onExpired()
            }
    }

    /** Counts down as `m:ss` for the card. */
    fun remaining(deadline: Long, now: Long): String {
        val seconds = ((deadline - now).coerceAtLeast(0) + 999) / 1000
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
}
