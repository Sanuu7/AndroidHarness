package com.androidharness.app.chatgpt

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.llm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile

/** Retries only a rejected request, never an already-streaming answer or executed tool. */
class ChatGptAutoSwitch(
    private val delegate: LlmProvider,
    private val enabled: () -> Boolean,
    private val nextAccount: suspend (ProviderConfig, Set<String>, String, List<ChatMessage>, List<ToolSchema>) -> ProviderConfig?,
    private val recordLimit: suspend (String) -> Unit = {},
    private val recordSuccess: suspend (String) -> Unit = {},
) : LlmProvider {
    override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
        messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions): Flow<StreamEvent> = flow {
        var current = config
        val attempted = mutableSetOf<String>()
        while (attempted.add(current.id)) {
            var failure: StreamEvent.Failure? = null
            var output = false
            var completed = false
            delegate.streamChat(current, apiKey, systemPrompt, messages, tools, options).takeWhile { event ->
                if (event is StreamEvent.Failure) {
                    failure = event
                    false
                } else {
                    if (event.hasOutput()) output = true
                    if (event is StreamEvent.Done) completed = true
                    emit(event)
                    true
                }
            }.collect {}
            val rejected = failure
            if (rejected == null) {
                if (completed) recordSuccess(current.id)
                return@flow
            }
            if (rejected.errorCode != USAGE_LIMIT) {
                emit(rejected)
                return@flow
            }
            recordLimit(current.id)
            if (!enabled() || output) {
                emit(rejected.copy(retryable = false))
                return@flow
            }
            val next = try { nextAccount(current, attempted, systemPrompt, messages, tools) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            if (next == null || next.id in attempted || !ChatGptProtocol.isProvider(next.id)) {
                emit(rejected.copy(message = "No other ChatGPT account can continue this request. " + rejected.message,
                    retryable = false))
                return@flow
            }
            // Respect a toggle changed while a model catalog was loading.
            if (!enabled()) {
                emit(rejected.copy(retryable = false))
                return@flow
            }
            emit(StreamEvent.ProviderChanged(next, "Usage limit reached · switched to ${next.name} · ${next.model}"))
            current = next
        }
    }

    private fun StreamEvent.hasOutput(): Boolean = when (this) {
        is StreamEvent.TextDelta -> text.isNotEmpty()
        is StreamEvent.ThinkingDelta -> text.isNotEmpty()
        is StreamEvent.ToolCallReady, is StreamEvent.ToolCallBatch, is StreamEvent.Usage, is StreamEvent.Done -> true
        is StreamEvent.Batch -> events.any { it.hasOutput() }
        else -> false
    }

    companion object { const val USAGE_LIMIT = "subscription_sharing_usage_limit_exceeded" }
}
