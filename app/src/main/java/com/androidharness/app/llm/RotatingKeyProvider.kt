package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Rotation is per request. Recovery is bounded and stops as soon as any answer/tool output exists. */
class RotatingKeyProvider(
    private val delegate: LlmProvider,
    private val poolFor: suspend (ProviderConfig) -> ProviderKeyPool?,
    private val cursor: AtomicLong = AtomicLong(),
) : LlmProvider {
    override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>,
        tools: List<ToolSchema>, options: RequestOptions): Flow<StreamEvent> = flow {
        val pool = poolFor(config)
        if (pool == null) {
            delegate.streamChat(config, apiKey, systemPrompt, messages, tools, options).collect { emit(it) }
            return@flow
        }
        val enabled = pool.keys.filter { it.enabled }
        if (enabled.isEmpty()) {
            if (pool.keys.isNotEmpty()) emit(StreamEvent.Failure("Enable an API key in provider controls before using this connection.", retryable = false))
            else delegate.streamChat(config, config.effectiveApiKey(null), systemPrompt, messages, tools, options).collect { emit(it) }
            return@flow
        }
        val start = if (pool.rotate) Math.floorMod(cursor.getAndIncrement(), enabled.size.toLong()).toInt() else 0
        // One group per turn keeps every rotated attempt together in the debug console.
        val debugGroup = options.debugGroup ?: java.util.UUID.randomUUID().toString()
        for (attempt in enabled.indices) {
            val key = enabled[(start + attempt) % enabled.size]
            val attemptOptions = options.copy(debugGroup = debugGroup, debugAttempt = attempt + 1,
                debugKeySlot = (start + attempt) % enabled.size)
            var output = false
            var failure: StreamEvent.Failure? = null
            try {
                delegate.streamChat(config, key.value, systemPrompt, messages, tools, attemptOptions)
                    .takeWhile { event ->
                        fun flatten(value: StreamEvent): List<StreamEvent> = if (value is StreamEvent.Batch) value.events.flatMap(::flatten) else listOf(value)
                        val batch = if (event is StreamEvent.Batch) flatten(event) else listOf(event)
                        val failedAt = batch.indexOfFirst { it is StreamEvent.Failure }
                        if (failedAt >= 0) {
                            val before = batch.take(failedAt)
                            if (before.isNotEmpty()) {
                                val prefix = if (before.size == 1) before.single() else StreamEvent.Batch(before)
                                if (prefix.hasResponseOutput()) output = true
                                emit(prefix)
                            }
                            failure = batch[failedAt] as StreamEvent.Failure
                            false
                        } else true
                    }.collect { event ->
                        if (event.hasResponseOutput()) output = true
                        emit(event)
                    }
            } catch (e: CancellationException) { throw e }
            catch (e: ApiException) { failure = StreamEvent.Failure(e.message.orEmpty(), e.code, e.errorCode) }
            val rejected = failure ?: return@flow
            val canRotate = rejected.code in setOf(401, 402, 403, 429) ||
                rejected.errorCode?.lowercase() in setOf("rate_limit_exceeded", "rate_limit_error", "insufficient_quota", "quota_exceeded", "resource_exhausted", "credit_balance_too_low")
            if (output || !canRotate || attempt == enabled.lastIndex) {
                var message = rejected.message
                pool.keys.forEach { if (it.value.isNotEmpty()) message = message.replace(it.value, "[redacted]") }
                emit(rejected.copy(message = message))
                return@flow
            }
            emit(StreamEvent.Retrying(attempt + 1, 0, "Trying another saved API key after a provider rejection or limit."))
        }
    }

    companion object {
        @Volatile var pools: suspend (ProviderConfig) -> ProviderKeyPool? = { null }
        private val cursors = ConcurrentHashMap<String, AtomicLong>()
        fun orderedKeys(providerId: String, pool: ProviderKeyPool): List<ProviderApiKey> {
            val enabled = pool.keys.filter { it.enabled }
            if (enabled.isEmpty() || !pool.rotate) return enabled
            val start = Math.floorMod(cursors.getOrPut(providerId) { AtomicLong() }.getAndIncrement(), enabled.size.toLong()).toInt()
            return enabled.drop(start) + enabled.take(start)
        }
        fun wrap(config: ProviderConfig, provider: LlmProvider): LlmProvider =
            if (!ProviderHeaders.editable(config)) provider
            else RotatingKeyProvider(provider, { pools(it) }, cursors.getOrPut(config.id) { AtomicLong() })
    }
}
