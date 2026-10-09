package com.androidharness.app.agent

import com.androidharness.app.llm.ApiException
import com.androidharness.app.llm.StreamEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class StreamRetrierTest {

    @Test fun `partial output server failure remains eligible for saved progress continue`() = runBlocking {
        var recoverable: Boolean? = null
        var hasOutput = false
        val result = StreamRetrier.run(
            streamFor = { flowOf(StreamEvent.TextDelta("partial progress"), StreamEvent.Failure("gateway failure", 502)) },
            onAttemptStart = {}, hasOutput = { hasOutput }, handleEvent = { hasOutput = true },
            retryReason = { it }, emitEvent = { error("Do not replay streamed output") },
            onTerminalFailure = { recoverable = it },
        )
        assertEquals("gateway failure", result)
        assertEquals(true, recoverable)
    }

    @Test fun `explicit permanent code overrides transient wording for continue`() = runBlocking {
        var recoverable: Boolean? = null
        StreamRetrier.run(
            streamFor = { flowOf(StreamEvent.Failure("temporarily overloaded", 401)) },
            onAttemptStart = {}, hasOutput = { false }, handleEvent = {}, retryReason = { it },
            emitEvent = { error("Permanent failure must not retry") },
            onTerminalFailure = { recoverable = it },
        )
        assertEquals(false, recoverable)
    }

    @Test fun `exhausted request retries still classify network failure for bounded continue`() = runBlocking {
        var recoverable: Boolean? = null
        var requests = 0
        StreamRetrier.run(
            streamFor = { requests++; throw IOException("disconnected") },
            onAttemptStart = {}, hasOutput = { false }, handleEvent = {}, retryReason = { it },
            emitEvent = {}, sleep = {}, onTerminalFailure = { recoverable = it },
        )
        assertEquals(RetryPolicy.MAX_RETRIES + 1, requests)
        assertEquals(true, recoverable)
    }

    @Test fun `local failure never triggers automatic continue`() = runBlocking {
        var recoverable: Boolean? = null
        StreamRetrier.run(
            streamFor = { flowOf(StreamEvent.Failure("timed out")) },
            onAttemptStart = {}, hasOutput = { false }, handleEvent = {}, retryReason = { it },
            emitEvent = {}, allowRetries = false, onTerminalFailure = { recoverable = it },
        )
        assertEquals(false, recoverable)
    }

    private val noopEmit: suspend (AgentEvent.Retrying) -> Unit = {}

    @Test
    fun `clean stream returns null and forwards all events`() = runBlocking {
        val seen = mutableListOf<StreamEvent>()
        val result = StreamRetrier.run(
            streamFor = {
                flowOf(
                    StreamEvent.TextDelta("hi"),
                    StreamEvent.Done("stop"),
                )
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = { seen += it },
            retryReason = { it },
            emitEvent = noopEmit,
        )
        assertEquals(null, result)
        // Failure must NOT reach handleEvent; Done must.
        assertEquals(2, seen.size)
        assertTrue(seen[0] is StreamEvent.TextDelta)
        assertEquals("stop", (seen[1] as StreamEvent.Done).finishReason)
    }

    @Test
    fun `transient in-stream failure retries and succeeds`() = runBlocking {
        var attempt = 0
        val retries = mutableListOf<AgentEvent.Retrying>()
        val slept = mutableListOf<Long>()
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) flowOf(StreamEvent.Failure("server overloaded"))
                    else flowOf(StreamEvent.TextDelta("ok"))
                }
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = { slept += it },
        )
        assertEquals(null, result)
        assertEquals(1, retries.size)
        assertEquals(1, retries[0].attempt)
        assertEquals("server overloaded", retries[0].reason)
        assertEquals(retries[0].delayMs, slept.single())
        // First-attempt base delay 1s ±30% jitter.
        assertTrue(slept.single() in 700..1300)
    }

    @Test
    fun `permanent in-stream failure never retries`() = runBlocking {
        var builds = 0
        val retries = mutableListOf<AgentEvent.Retrying>()
        val result = StreamRetrier.run(
            streamFor = {
                builds++
                flowOf(StreamEvent.Failure("Invalid API key provided"))
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = { error("no retry expected") },
        )
        assertEquals("Invalid API key provided", result)
        assertEquals(1, builds)
        assertTrue(retries.isEmpty())
    }

    @Test
    fun `io exception exhausts retries then returns last failure`() = runBlocking {
        var builds = 0
        val retries = mutableListOf<AgentEvent.Retrying>()
        var attemptsStarted = 0
        val result = StreamRetrier.run(
            streamFor = {
                builds++
                throw IOException("connection reset by peer")
            },
            onAttemptStart = { attemptsStarted++ },
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = {},
        )
        assertEquals(RetryPolicy.MAX_RETRIES, retries.size)
        assertEquals(listOf(1, 2, 3), retries.map { it.attempt })
        assertEquals(RetryPolicy.MAX_RETRIES + 1, attemptsStarted)
        assertTrue(result!!.contains("connection reset"))
    }

    @Test
    fun `api exception 400 is terminal`() = runBlocking {
        var builds = 0
        val result = StreamRetrier.run(
            streamFor = {
                builds++
                throw ApiException(400, "bad request")
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { error("no retry expected") },
            sleep = { error("no retry expected") },
        )
        assertTrue(result!!.contains("400"))
        assertEquals(1, builds)
    }

    @Test
    fun `prior output blocks retry`() = runBlocking {
        var builds = 0
        val deltasSeen = mutableListOf<String>()
        val result = StreamRetrier.run(
            streamFor = {
                builds++
                flowOf(StreamEvent.TextDelta("partial"), StreamEvent.Failure("overloaded"))
            },
            onAttemptStart = {},
            hasOutput = { deltasSeen.isNotEmpty() },
            handleEvent = { if (it is StreamEvent.TextDelta) deltasSeen += it.text },
            retryReason = { it },
            emitEvent = { error("no retry after output was streamed") },
            sleep = { error("no retry after output was streamed") },
        )
        assertEquals("overloaded", result)
        assertEquals(1, builds)
        assertEquals(listOf("partial"), deltasSeen)
    }

    @Test
    fun `retry resets buffers via onAttemptStart`() = runBlocking {
        var attempt = 0
        var resets = 0
        var lastText = ""
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) flowOf(StreamEvent.Failure("timed out"))
                    else flowOf(StreamEvent.TextDelta("second try"))
                }
            },
            onAttemptStart = {
                resets++
                lastText = ""
            },
            hasOutput = { lastText.isNotEmpty() },
            handleEvent = { if (it is StreamEvent.TextDelta) lastText = it.text },
            retryReason = { it },
            emitEvent = {},
            sleep = {},
        )
        assertEquals(null, result)
        assertEquals(2, resets)
        assertEquals("second try", lastText)
    }

    @Test
    fun `local requests can disable retries even for transient wording`() = runBlocking {
        var attempts = 0
        val result = StreamRetrier.run(
            streamFor = { attempts++; flowOf(StreamEvent.Failure("timed out")) },
            onAttemptStart = {}, hasOutput = { false }, handleEvent = {}, retryReason = { it },
            emitEvent = { error("Local requests must not restart themselves") },
            allowRetries = false,
        )
        assertEquals(1, attempts)
        assertEquals("timed out", result)
    }

    @Test
    fun `stalled stream is treated as transient failure`() = runBlocking {
        var builds = 0
        val retries = mutableListOf<AgentEvent.Retrying>()
        val result = StreamRetrier.run(
            streamFor = {
                builds++
                if (builds == 1) flow<StreamEvent> { awaitCancellation() }
                else flowOf(StreamEvent.TextDelta("recovered"))
            },
            onAttemptStart = {},
            hasOutput = { false },
            handleEvent = {},
            retryReason = { it },
            emitEvent = { retries += it },
            sleep = {},
            stallTimeoutMs = 150,
        )
        assertEquals(null, result)
        assertEquals(1, retries.size)
        assertEquals("Generation stalled - no data received for 0s (timed out)", retries[0].reason)
    }

    @Test
    fun `failure terminates collection immediately even when upstream stays open`() = runBlocking {
        val seen = mutableListOf<StreamEvent>()
        val result = StreamRetrier.run(
            streamFor = {
                flow {
                    emit(StreamEvent.Failure("Invalid request", 400))
                    emit(StreamEvent.TextDelta("must be ignored"))
                    awaitCancellation()
                }
            },
            onAttemptStart = {}, hasOutput = { false }, handleEvent = { seen += it },
            retryReason = { it }, emitEvent = { error("400 must not retry") },
            sleep = {}, stallTimeoutMs = 150,
        )
        assertEquals("Invalid request", result)
        assertTrue(seen.isEmpty())
    }
}
