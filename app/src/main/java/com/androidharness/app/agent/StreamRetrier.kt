package com.androidharness.app.agent

import com.androidharness.app.llm.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Proof of life for a stream under [stallGuard]. The SSE reader calls
 * [touch] for every line it reads, keep-alive comments included, so a
 * provider that is still reasoning but sends only pings is not mistaken for
 * a dead one. Nested guards share the signal through [parent].
 */
internal class StreamActivity(private val parent: StreamActivity?) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StreamActivity>

    internal val signals = Channel<Unit>(Channel.CONFLATED)

    fun touch() {
        signals.trySend(Unit)
        parent?.touch()
    }
}

/** Thrown by [stallGuard] when the connection went quiet for the whole window. */
internal class StreamStalledException(val silentMs: Long) : IOException(stalledMessage(silentMs))

internal fun stalledMessage(silentMs: Long): String =
    if (silentMs == 90_000L) StreamRetrier.STALLED_STREAM_MESSAGE
    else "Generation stalled - no data received for ${silentMs / 1000}s (timed out)"

/**
 * Fails a silent stream instead of hanging forever: the SSE client's read
 * timeout is infinite by design, so a dead gateway that keeps the socket open
 * would stall a request indefinitely. Any event or any line on the wire
 * (see [StreamActivity]) resets the window. Before the first event the
 * window is [firstOutputTimeoutMs], since a model may reason for minutes
 * before it says anything. Throws [StreamStalledException], which
 * [StreamRetrier] routes into the normal retry policy.
 */
internal fun <T> Flow<T>.stallGuard(timeoutMs: Long = 90_000, firstOutputTimeoutMs: Long = timeoutMs): Flow<T> {
    val upstream = this
    return channelFlow {
        val activity = StreamActivity(currentCoroutineContext()[StreamActivity])
        var produced = false
        val watchdog = launch {
            while (true) {
                val limit = if (produced) timeoutMs else maxOf(timeoutMs, firstOutputTimeoutMs)
                withTimeoutOrNull(limit) { activity.signals.receive() } ?: throw StreamStalledException(limit)
            }
        }
        withContext(activity) {
            upstream.collect {
                produced = true
                activity.touch()
                send(it)
                activity.touch()
            }
        }
        watchdog.cancel()
    }
}

/**
 * The single shared retry wrapper for every streamed LLM request the engine
 * makes, main turn, subagent turn, compaction. Transient failures (HTTP
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

    /** How long a reasoning model may stay fully silent before its first event. */
    const val REASONING_FIRST_OUTPUT_MS = 300_000L

    /**
     * The stall window before the first event. With thinking on, a model or a
     * gateway that sends no keep-alives can be quiet for minutes while it
     * reasons, so it gets [REASONING_FIRST_OUTPUT_MS] instead of [stallTimeoutMs].
     */
    fun firstOutputWindow(stallTimeoutMs: Long, thinking: ThinkingLevel): Long =
        if (thinking == ThinkingLevel.OFF) stallTimeoutMs else maxOf(stallTimeoutMs, REASONING_FIRST_OUTPUT_MS)

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
     * @param firstOutputTimeoutMs stallGuard window before the first event,
     *                         longer while a model may reason in silence.
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
        firstOutputTimeoutMs: Long = stallTimeoutMs,
        allowRetries: Boolean = true,
        onTerminalFailure: (recoverable: Boolean) -> Unit = {},
    ): String? {
        var attempt = 0
        while (true) {
            kotlin.coroutines.coroutineContext[TaskBudget]?.check()
            onAttemptStart()
            var failure: String? = null
            var failureCode: Int? = null
            var retryAllowed = true
            var cause: Throwable? = null
            try {
                streamFor().stallGuard(stallTimeoutMs, firstOutputTimeoutMs).takeWhile { event ->
                    when (event) {
                        is StreamEvent.Failure -> {
                            failure = event.message
                            failureCode = event.code
                            retryAllowed = event.retryable != false
                        }
                        else -> handleEvent(event)
                    }
                    event !is StreamEvent.Failure
                }.collect { }
            } catch (stall: StreamStalledException) {
                // stallGuard: a silent gateway kept the socket open, treat
                // like any transient failure so retries can kick in.
                failure = stalledMessage(stall.silentMs)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                cause = e
                failure = e.message ?: e.javaClass.simpleName
            }

            val retryable = allowRetries && retryAllowed && failure != null &&
                attempt < RetryPolicy.MAX_RETRIES &&
                !hasOutput() &&
                RetryPolicy.isRetryable(cause, failure, failureCode)
            if (!retryable) {
                if (failure != null) onTerminalFailure(allowRetries && retryAllowed && RetryPolicy.isRetryable(cause, failure, failureCode))
                return failure
            }

            attempt++
            val delayMs = RetryPolicy.delayMs(attempt)
            emitEvent(AgentEvent.Retrying(attempt, delayMs, retryReason(failure)))
            sleep(delayMs)
        }
    }
}
