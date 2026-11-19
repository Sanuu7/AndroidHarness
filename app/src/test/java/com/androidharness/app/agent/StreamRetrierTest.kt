package com.androidharness.app.agent

import com.androidharness.app.llm.ApiException
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.StreamEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class StreamRetrierTest {

    @Test fun `confirmed subscription usage limit never retries or automatically continues`() = runBlocking {
        var requests = 0
        var recoverable: Boolean? = null
        val result = StreamRetrier.run(
            streamFor = { requests++; flowOf(StreamEvent.Failure("Usage limit reached", 429,
                "subscription_sharing_usage_limit_exceeded", retryable = false)) },
            onAttemptStart = {}, hasOutput = { false }, handleEvent = {}, retryReason = { it },
            emitEvent = { error("Must not retry exhausted accounts") }, onTerminalFailure = { recoverable = it },
        )
        assertEquals("Usage limit reached", result)
        assertEquals(1, requests)
        assertEquals(false, recoverable)
    }

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
        assertEquals("Stream stalled - no data received for 0s (timed out)", retries[0].reason)
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

    @Test
    fun `keep-alive activity holds a quiet stream open past the window`() = runBlocking {
        val seen = mutableListOf<StreamEvent>()
        val result = StreamRetrier.run(
            streamFor = {
                flow {
                    val activity = currentCoroutineContext()[StreamActivity]
                    repeat(8) { delay(50); activity?.touch() }
                    emit(StreamEvent.TextDelta("late"))
                }
            },
            onAttemptStart = {}, hasOutput = { seen.isNotEmpty() }, handleEvent = { seen += it },
            retryReason = { it }, emitEvent = { error("keep-alives must not stall: ${it.reason}") },
            sleep = {}, stallTimeoutMs = 150,
        )
        assertEquals(null, result)
        assertEquals(listOf<StreamEvent>(StreamEvent.TextDelta("late")), seen)
    }

    @Test
    fun `nested guards pass keep-alive activity to the outer guard`() = runBlocking {
        val seen = mutableListOf<StreamEvent>()
        val inner = flow {
            val activity = currentCoroutineContext()[StreamActivity]
            repeat(8) { delay(50); activity?.touch() }
            emit(StreamEvent.TextDelta("late"))
        }.stallGuard(150)
        val result = StreamRetrier.run(
            streamFor = { inner }, onAttemptStart = {}, hasOutput = { seen.isNotEmpty() }, handleEvent = { seen += it },
            retryReason = { it }, emitEvent = { error("outer guard must see inner activity: ${it.reason}") },
            sleep = {}, stallTimeoutMs = 150,
        )
        assertEquals(null, result)
        assertEquals(1, seen.size)
    }

    @Test
    fun `the first output window allows a longer silence only before the first event`() = runBlocking {
        val quietStart = StreamRetrier.run(
            streamFor = { flow { delay(300); emit(StreamEvent.TextDelta("thought")) } },
            onAttemptStart = {}, hasOutput = { false }, handleEvent = {},
            retryReason = { it }, emitEvent = { error("first output window ignored") },
            sleep = {}, stallTimeoutMs = 150, firstOutputTimeoutMs = 1_000,
        )
        assertEquals(null, quietStart)

        val quietMiddle = StreamRetrier.run(
            streamFor = { flow { emit(StreamEvent.TextDelta("a")); delay(400); emit(StreamEvent.TextDelta("b")) } },
            onAttemptStart = {}, hasOutput = { true }, handleEvent = {},
            retryReason = { it }, emitEvent = { error("output already shown, no retry") },
            sleep = {}, stallTimeoutMs = 150, firstOutputTimeoutMs = 1_000,
        )
        assertEquals("Stream stalled - no data received for 0s (timed out)", quietMiddle)
    }

    @Test
    fun `thinking gets a longer first output window`() {
        assertEquals(90_000L, StreamRetrier.firstOutputWindow(90_000, ThinkingLevel.OFF))
        assertEquals(StreamRetrier.REASONING_FIRST_OUTPUT_MS, StreamRetrier.firstOutputWindow(90_000, ThinkingLevel.HIGH))
        assertEquals(610_000L, StreamRetrier.firstOutputWindow(610_000, ThinkingLevel.HIGH))
    }

    @Test
    fun `a request's own notices do not consume the first output window`() = runBlocking {
        val seen = mutableListOf<StreamEvent>()
        val result = StreamRetrier.run(
            streamFor = {
                flow {
                    // Every provider emits this before the request hits the wire.
                    emit(StreamEvent.ThinkingConfigured("high"))
                    delay(300)
                    emit(StreamEvent.TextDelta("thought"))
                }
            },
            onAttemptStart = {}, hasOutput = { seen.isNotEmpty() }, handleEvent = { seen += it },
            retryReason = { it }, emitEvent = { error("a local notice must not shorten the reasoning window") },
            sleep = {}, stallTimeoutMs = 150, firstOutputTimeoutMs = 1_000,
        )
        assertEquals(null, result)
        assertEquals(listOf<StreamEvent>(StreamEvent.ThinkingConfigured("high"), StreamEvent.TextDelta("thought")), seen)
    }

    @Test
    fun `the first output window follows the thinking level the request actually sends`() {
        val alwaysOn = ProviderConfig("anthropic", "Anthropic", ProviderType.ANTHROPIC,
            "https://api.anthropic.com", "claude-opus-5-5")
        // Off is a selection, not the wire value: always-on models still reason.
        assertEquals(StreamRetrier.REASONING_FIRST_OUTPUT_MS,
            StreamRetrier.firstOutputWindow(90_000, RequestOptions(thinking = ThinkingLevel.OFF), alwaysOn))
        val plain = alwaysOn.copy(model = "claude-3-5-haiku-20241022")
        assertEquals(90_000L,
            StreamRetrier.firstOutputWindow(90_000, RequestOptions(thinking = ThinkingLevel.OFF), plain))
        // A model with no reasoning dial sends nothing, but the user still asked
        // for thinking, so an unknown model keeps the request's own intent.
        val unknown = alwaysOn.copy(id = "openrouter", type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://openrouter.ai/api/v1", model = "acme-unknown-1")
        assertEquals(StreamRetrier.REASONING_FIRST_OUTPUT_MS,
            StreamRetrier.firstOutputWindow(90_000, RequestOptions(thinking = ThinkingLevel.HIGH), unknown))
    }

    @Test
    fun `the default window keeps the stalled message`() {
        assertEquals(StreamRetrier.STALLED_STREAM_MESSAGE, StreamStalledException(90_000).message)
        assertTrue(RetryPolicy.isRetryable(null, StreamStalledException(90_000).message!!, null))
    }
    @Test
    fun `a self-hosted stall is final instead of resending the prompt`() = runBlocking {
        var builds = 0
        val result = StreamRetrier.run(
            streamFor = { builds++; flow<StreamEvent> { awaitCancellation() } },
            onAttemptStart = {}, hasOutput = { false }, handleEvent = {}, retryReason = { it },
            emitEvent = { error("A resend would restart the server's prompt processing") },
            sleep = {}, stallTimeoutMs = 150, retryStalls = false,
        )
        assertEquals("Stream stalled - no data received for 0s (timed out)", result)
        assertEquals(1, builds)
    }

    @Test
    fun `self-hosted servers get time to read a long prompt before the first token`() {
        val llama = com.androidharness.app.llm.ProviderConfig("llama", "llama.cpp",
            com.androidharness.app.llm.ProviderType.OPENAI_COMPAT, "http://127.0.0.1:8080/v1", "qwen")
        val lan = llama.copy(baseUrl = "http://192.168.1.20:8080/v1")
        val cloud = llama.copy(baseUrl = "https://openrouter.ai/api/v1")
        assertEquals(600_000L, StreamRetrier.firstOutputWindow(90_000, ThinkingLevel.OFF, llama))
        assertEquals(600_000L, StreamRetrier.firstOutputWindow(90_000, ThinkingLevel.HIGH, lan))
        assertEquals(90_000L, StreamRetrier.firstOutputWindow(90_000, ThinkingLevel.OFF, cloud))
        assertEquals(300_000L, StreamRetrier.firstOutputWindow(90_000, ThinkingLevel.HIGH, cloud))
        assertTrue(StreamRetrier.selfHosted(llama))
        assertEquals(false, StreamRetrier.selfHosted(cloud))
    }
}
