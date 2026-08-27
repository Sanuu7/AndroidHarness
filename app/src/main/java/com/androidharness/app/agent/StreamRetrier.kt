package com.androidharness.app.agent

import com.androidharness.app.llm.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.timeout
import kotlin.time.Duration.Companion.milliseconds

/**
 * Fails a silent stream instead of hanging forever: the SSE client's read
 * timeout is infinite by design, so a dead gateway that keeps the socket open
 * would stall a request indefinitely. Throws
 * [kotlinx.coroutines.TimeoutCancellationException] when no event arrives in
 * [timeoutMs]; [StreamRetrier] catches it and routes it into the normal
 * retry policy.
 */
internal fun <T> Flow<T>.stallGuard(timeoutMs: Long = 90_000): Flow<T> =
    timeout(timeoutMs.toDouble().milliseconds)

/**
 * The single shared retry wrapper for every streamed LLM request the engine
 * makes — main turn, subagent turn, compaction. Transient failures (HTTP
 * 408/429/5xx, network drops, stalled gateways) are retried with
 * [RetryPolicy]'s backoff; permanent errors surface at once.
 *
 * A failure only retries while the attempt produced no output at all:
 * re-emitting deltas the UI already showed would duplicate them. Each call
 * site states which of its buffers count as output via [hasOutput] and
 * resets its buffers per attempt via [onAttemptStart]. In-band failures
 * ([StreamEvent.Failure]) and thrown exceptions are classified identically.
 */
internal object StreamRetrier {

    /** Error text used when the stream stalled and [stallGuard] cut it off. */
    const val STALLED_STREAM_MESSAGE = "Stream stalled - no data received for 90s (timed out)"

    /**
     * Streams until completion or terminal failure. Returns null on success,
     * otherwise the final failure message (from an exception or from
     * [StreamEvent.Failure]).
     *
     * @param streamFor        builds one attempt's raw flow; retried as-is.
     * @param onAttemptStart   clears caller buffers before each attempt.
     * @param hasOutput        whether this attempt produced output yet; when
     *                         true a failure is terminal, never retried.
     * @param handleEvent      consumes non-failure events (deltas, usage…).
     * @param retryReason      formats the message shown in [AgentEvent.Retrying].
     * @param emitEvent        sink for [AgentEvent.Retrying] announcements.
     * @param sleep            waits out the backoff; injectable for tests.
     * @param stallTimeoutMs   stallGuard window; injectable for tests.
     */
    internal suspend fun run(
        streamFor: () -> Flow<StreamEvent>,
        onAttemptStart: () -> Unit,
        hasOutput: () -> Boolean,
        handleEvent: suspend (StreamEvent) -> Unit,
        retryReason: (String) -> String,
        emitEvent: suspend (AgentEvent.Retrying) -> Unit,
        sleep: suspend (Long) -> Unit = { delay(it) },
        stallTimeoutMs: Long = 90_000,
    ): String? {
        var attempt = 0
        while (true) {
            onAttemptStart()
            var failure: String? = null
            var cause: Throwable? = null
            try {
                streamFor().stallGuard(stallTimeoutMs).collect { event ->
                    when (event) {
                        is StreamEvent.Failure -> failure = event.message
                        else -> handleEvent(event)
                    }
                }
            } catch (te: TimeoutCancellationException) {
                // stallGuard: a silent gateway kept the socket open — treat
                // like any transient failure so retries can kick in. Caught
                // before CancellationException because it subclasses it;
                // genuine cancellation must still propagate.
                failure = STALLED_STREAM_MESSAGE
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                cause = e
                failure = e.message ?: e.javaClass.simpleName
            }

            val retryable = failure != null &&
                attempt < RetryPolicy.MAX_RETRIES &&
                !hasOutput() &&
                RetryPolicy.isRetryable(cause, failure)
            if (!retryable) return failure

            attempt++
            val delayMs = RetryPolicy.delayMs(attempt)
            emitEvent(AgentEvent.Retrying(attempt, delayMs, retryReason(failure!!)))
            sleep(delayMs)
        }
    }
}
