package com.androidharness.app.agent

import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.RequestOptions
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
import java.util.concurrent.atomic.AtomicBoolean
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
    "Stream stalled - no data received for ${StreamRetrier.formatStall(silentMs)} (timed out)"

/**
 * Fails a silent stream instead of hanging forever: the SSE client's read
 * timeout is infinite by design, so a dead gateway that keeps the socket open
 * would stall a request indefinitely. Any event or any line on the wire
 * (see [StreamActivity]) resets the window. Before the first output the
 * window is [firstOutputTimeoutMs], since a model may reason for minutes
 * before it says anything. Throws [StreamStalledException], which
 * [StreamRetrier] routes into the normal retry policy.
 *
 * Only real output ends that longer window. The client emits its own notices
 * ([StreamEvent.ThinkingConfigured], [StreamEvent.ProviderChanged],
 * [StreamEvent.Retrying]) before the provider has sent a byte, and counting
 * those as output used to drop every reasoning request back to the plain
 * window at t=0.
 */
internal fun <T> Flow<T>.stallGuard(timeoutMs: Long = StreamRetrier.DEFAULT_STALL_TIMEOUT_MS, firstOutputTimeoutMs: Long = timeoutMs): Flow<T> {
    val upstream = this
    return channelFlow {
        val activity = StreamActivity(currentCoroutineContext()[StreamActivity])
        val produced = AtomicBoolean(false)
        val watchdog = launch {
            while (true) {
                val limit = if (produced.get()) timeoutMs else maxOf(timeoutMs, firstOutputTimeoutMs)
                withTimeoutOrNull(limit) { activity.signals.receive() } ?: throw StreamStalledException(limit)
            }
        }
        withContext(activity) {
            upstream.collect { event ->
                if (event !is StreamEvent || !event.isLocalNotice()) produced.set(true)
                activity.touch()
                send(event)
                activity.touch()
            }
        }
        watchdog.cancel()
    }
}

/** Events this client emits itself, before the provider has sent anything. */
private fun StreamEvent.isLocalNotice(): Boolean = when (this) {
    is StreamEvent.ThinkingConfigured, is StreamEvent.ProviderChanged, is StreamEvent.Retrying -> true
    is StreamEvent.Batch -> events.all { it.isLocalNotice() }
    else -> false
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

    /** The stall window when the user has not picked one in Settings. */
    const val DEFAULT_STALL_TIMEOUT_MS = 900_000L
    const val DEFAULT_STALL_TIMEOUT_SECONDS = 900
    const val MIN_STALL_TIMEOUT_SECONDS = 30
    const val MAX_STALL_TIMEOUT_SECONDS = 3_600

    /** Saved value meaning "never pause a silent stream". */
    const val STALL_TIMEOUT_NEVER = 0

    /** Window used for [STALL_TIMEOUT_NEVER]; small enough that adding to it cannot overflow. */
    const val NO_STALL_LIMIT_MS = Long.MAX_VALUE / 4

    /** The user's stall window in ms, kept inside the range the picker offers. */
    fun stallTimeoutMs(seconds: Int): Long =
        if (seconds == STALL_TIMEOUT_NEVER) NO_STALL_LIMIT_MS
        else seconds.coerceIn(MIN_STALL_TIMEOUT_SECONDS, MAX_STALL_TIMEOUT_SECONDS) * 1_000L

    /** "90s", "5 min" or "2 min 30s"; the same wording the Settings picker uses. */
    fun formatStall(ms: Long): String {
        val seconds = ms / 1_000
        val minutes = seconds / 60
        val rest = seconds % 60
        return when {
            seconds < 120 && rest != 0L || seconds < 60 -> "${seconds}s"
            rest == 0L -> "$minutes min"
            else -> "$minutes min ${rest}s"
        }
    }

    private val stallPhrase = Regex("Stream stalled - no data received for (.+?) \\(timed out\\)", RegexOption.IGNORE_CASE)

    /**
     * Plain wording for a stall, for people who read the raw text as the agent "waiting" and
     * the app giving up on it. Says whose reply went quiet (the main model, a subagent's, or a
     * named provider in a fallback chain) and that tool time never counts. Other text is kept.
     */
    fun describeStall(reason: String, forStatusLine: Boolean = false): String {
        val match = stallPhrase.find(reason) ?: return reason
        val lead = reason.substring(0, match.range.first).trim().removeSuffix(":").trim()
        val who = when {
            lead.equals("subagent", ignoreCase = true) -> "The subagent's model"
            lead.isNotEmpty() -> lead
            else -> "The model"
        }
        val silent = match.groupValues[1]
        return if (forStatusLine) "$who sent nothing for $silent. Slow model? Raise the stall timeout in Settings."
        else "$who sent nothing for $silent mid-reply, so the connection looked dead. " +
            "Running tools, commands and subagents never counts toward this."
    }

    /** True for a pause caused by [stallGuard], so the paused card can offer a longer wait. */
    fun isStallFailure(reason: String?): Boolean =
        reason != null && (reason.contains("stalled - no data received", ignoreCase = true) ||
            reason.contains("Generation stalled", ignoreCase = true))

    /** How long a reasoning model may stay fully silent before its first event. */
    const val REASONING_FIRST_OUTPUT_MS = 300_000L

    /**
     * The stall window before the first event. With thinking on, a model or a
     * gateway that sends no keep-alives can be quiet for minutes while it
     * reasons, so it gets [REASONING_FIRST_OUTPUT_MS] instead of [stallTimeoutMs].
     */
    fun firstOutputWindow(stallTimeoutMs: Long, thinking: ThinkingLevel, config: ProviderConfig? = null): Long {
        val window = if (thinking == ThinkingLevel.OFF) stallTimeoutMs else maxOf(stallTimeoutMs, REASONING_FIRST_OUTPUT_MS)
        return if (config != null && selfHosted(config)) maxOf(window, SELF_HOSTED_FIRST_OUTPUT_MS) else window
    }

    /**
     * [firstOutputWindow] for the level the request will actually send. A user
     * selection of Off does not mean the server was asked for no reasoning:
     * models with always-on thinking resolve Off to their default level, so
     * the window has to follow the resolved value, not the selection.
     */
    fun firstOutputWindow(stallTimeoutMs: Long, options: RequestOptions, config: ProviderConfig): Long =
        firstOutputWindow(stallTimeoutMs, resolvedThinking(options, config), config)

    /**
     * The reasoning work this request may actually cause. Always-on models
     * turn an Off selection into a real level, so that wins. Models with no
     * reasoning control get the plain window. Unknown or self-managed models
     * (thinking style Automatic) send no dial, so the user's own selection is
     * the only intent available and it is kept.
     */
    private fun resolvedThinking(options: RequestOptions, config: ProviderConfig): ThinkingLevel {
        val resolved = runCatching { options.resolveThinking(config) }.getOrNull() ?: return options.thinking
        return when {
            resolved.spec.style == ThinkingSpecs.Style.NONE -> ThinkingLevel.OFF
            resolved.level != ThinkingLevel.OFF -> resolved.level
            else -> maxOf(resolved.level, options.thinking)
        }
    }

    /**
     * How long a server on this device or the local network may stay silent
     * before its first token. llama.cpp sends nothing while it reads the
     * prompt, and on a phone CPU a full agent prompt can take minutes.
     */
    const val SELF_HOSTED_FIRST_OUTPUT_MS = 600_000L

    /**
     * A server on this device or the local network (llama.cpp, Ollama, LM
     * Studio). Resending a request it is still reading only restarts that
     * work, so a silent stall there is final instead of retried.
     */
    fun selfHosted(config: ProviderConfig): Boolean =
        com.androidharness.app.llm.OpenAiCompatProvider.isLocalHost(config.baseUrl)

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
     * @param retryStalls      false when resending a silent request would only
     *                         restart the work, as on a self-hosted server.
     */
    internal suspend fun run(
        streamFor: () -> Flow<StreamEvent>,
        onAttemptStart: () -> Unit,
        hasOutput: () -> Boolean,
        handleEvent: suspend (StreamEvent) -> Unit,
        retryReason: (String) -> String,
        emitEvent: suspend (AgentEvent.Retrying) -> Unit,
        sleep: suspend (Long) -> Unit = { delay(it) },
        stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
        firstOutputTimeoutMs: Long = stallTimeoutMs,
        allowRetries: Boolean = true,
        retryStalls: Boolean = true,
        onTerminalFailure: (recoverable: Boolean) -> Unit = {},
    ): String? {
        var attempt = 0
        while (true) {
            kotlin.coroutines.coroutineContext[TaskBudget]?.check()
            onAttemptStart()
            var failure: String? = null
            var failureCode: Int? = null
            var failureKind: String? = null
            var retryAfterSeconds: Long? = null
            var retryAllowed = true
            var cause: Throwable? = null
            try {
                streamFor().stallGuard(stallTimeoutMs, firstOutputTimeoutMs).takeWhile { event ->
                    when (event) {
                        is StreamEvent.Failure -> {
                            failure = event.message
                            failureCode = event.code
                            failureKind = event.errorCode
                            retryAfterSeconds = event.retryAfterSeconds
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
                if (!retryStalls) retryAllowed = false
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                cause = e
                failure = e.message ?: e.javaClass.simpleName
            }

            val freeRate = failureKind == com.androidharness.app.llm.HarnessFreeLimit.RATE_CODE
            val retryable = allowRetries && retryAllowed && failure != null &&
                attempt < (if (freeRate) RetryPolicy.FREE_RATE_RETRIES else RetryPolicy.MAX_RETRIES) &&
                !hasOutput() &&
                RetryPolicy.isRetryable(cause, failure, failureCode)
            if (!retryable) {
                if (failure != null) onTerminalFailure(allowRetries && retryAllowed && RetryPolicy.isRetryable(cause, failure, failureCode))
                return failure
            }

            attempt++
            val delayMs = if (freeRate) RetryPolicy.freeRateDelayMs(attempt, retryAfterSeconds) else RetryPolicy.delayMs(attempt)
            // The free-limit notice also says "Try again in N seconds"; the status line already counts down.
            emitEvent(AgentEvent.Retrying(attempt, delayMs, retryReason(if (freeRate) failure.orEmpty().lineSequence().first() else failure)))
            sleep(delayMs)
        }
    }
}
