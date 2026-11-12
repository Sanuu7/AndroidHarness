package com.androidharness.app.llm.debug

/**
 * Turns the flat request log into turns: one model request as the user sees it,
 * with every send it took to get an answer.
 *
 * The interceptor only knows single exchanges. Why a second one happened is read
 * off the pair: a different provider or model is a fallback hop, a different key
 * slot is key rotation, and anything else is a plain retry.
 */
object DebugTurns {

    enum class Kind { FIRST, RETRY, KEY_ROTATION, FALLBACK }

    data class Attempt(
        val trace: DebugTrace,
        val kind: Kind,
        /** The send this one replaced; null for the first of a turn. */
        val previous: DebugTrace?,
    )

    /** [attempts] run oldest first, the order they were sent in. */
    data class Turn(val key: String, val attempts: List<Attempt>) {
        val last: DebugTrace get() = attempts.last().trace
        val retried: Boolean get() = attempts.size > 1
        /** A turn that needed more than one send but got an answer in the end. */
        val recovered: Boolean get() = retried && !last.failed
    }

    /** Newest turn first. Traces without a group (probes, one-off calls) stand alone. */
    fun group(traces: List<DebugTrace>): List<Turn> =
        traces.groupBy { it.group ?: "single-${it.id}" }
            .map { (key, members) ->
                val ordered = members.sortedWith(compareBy({ it.startedAt }, { it.id }))
                Turn(key, ordered.mapIndexed { index, trace ->
                    val previous = ordered.getOrNull(index - 1)
                    Attempt(trace, kindOf(trace, previous), previous)
                })
            }
            .sortedWith(compareByDescending<Turn> { it.last.startedAt }.thenByDescending { it.last.id })

    private fun kindOf(trace: DebugTrace, previous: DebugTrace?): Kind = when {
        previous == null -> Kind.FIRST
        previous.providerId != trace.providerId || previous.model != trace.model -> Kind.FALLBACK
        previous.keySlot != trace.keySlot -> Kind.KEY_ROTATION
        else -> Kind.RETRY
    }
}
