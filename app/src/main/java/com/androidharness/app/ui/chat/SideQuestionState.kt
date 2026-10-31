package com.androidharness.app.ui.chat

import androidx.compose.runtime.Immutable
import com.androidharness.app.agent.SideEvent
import com.androidharness.app.agent.ThinkingTiming
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.db.UsageEventEntity

/** One /btw exchange as the sheet shows it. Lives only in memory and dies with the sheet. */
@Immutable
data class SideQuestionUi(
    val question: String = "",
    val running: Boolean = false,
    /** Committed assistant rounds and tool results, in request order. */
    val messages: List<ChatMessage> = emptyList(),
    val streamingText: String = "",
    val streamingThinking: String = "",
    val runningCalls: List<ToolCallData> = emptyList(),
    val thinkingTiming: ThinkingTiming = ThinkingTiming(),
    val usage: List<UsageEventEntity> = emptyList(),
    /** True when the question replays the main task's live request and its cache. */
    val warm: Boolean = true,
    val capturedAt: Long = 0,
    val model: String = "",
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val retrying: String? = null,
    val notice: String? = null,
    val error: String? = null,
    val stopped: Boolean = false,
) {
    val asked: Boolean get() = question.isNotBlank()

    /** The final answer: the last round that called no tools, or what is streaming now. */
    val answer: String get() = messages.lastOrNull { it.role == Role.ASSISTANT && it.toolCalls.isEmpty() }?.text
        ?: if (runningCalls.isEmpty()) streamingText else ""

    val toolCount: Int get() = messages.sumOf { if (it.role == Role.ASSISTANT) it.toolCalls.size else 0 }
}

internal object SideQuestionReducer {
    fun reduce(state: SideQuestionUi, event: SideEvent, now: Long = System.currentTimeMillis()): SideQuestionUi = when (event) {
        is SideEvent.Text -> state.copy(streamingText = state.streamingText + event.text, retrying = null)
        is SideEvent.Thinking -> state.copy(streamingThinking = state.streamingThinking + event.text, retrying = null)
        is SideEvent.ThinkingProgress -> state.copy(thinkingTiming = event.timing)
        is SideEvent.AssistantCommitted -> state.copy(messages = state.messages + event.message,
            streamingText = "", streamingThinking = "", thinkingTiming = ThinkingTiming())
        is SideEvent.ToolStarted -> state.copy(runningCalls = state.runningCalls + event.call)
        is SideEvent.ToolFinished -> state.copy(messages = state.messages + event.message,
            runningCalls = state.runningCalls.filterNot { it.id == event.message.toolCallId })
        is SideEvent.Usage -> with(event.usage) {
            state.copy(usage = state.usage + UsageEventEntity(
                sessionId = "", providerName = providerName, model = model,
                inputTokens = inputTokens.toLong(), outputTokens = outputTokens.toLong(),
                cachedTokens = cachedInputTokens.toLong(), cacheWriteTokens = cacheWriteTokens.toLong(),
                createdAt = now, cacheReported = cacheReported,
                inputPrice = cachePrices?.input, cacheReadPrice = cachePrices?.read, cacheWritePrice = cachePrices?.write,
            ))
        }
        is SideEvent.Retrying -> state.copy(retrying = event.reason)
        is SideEvent.ProviderChanged -> state.copy(model = event.config.model)
        is SideEvent.Failed -> finish(state, now).copy(error = event.message)
        is SideEvent.Finished -> finish(state, now).copy(notice = event.reason)
    }

    /** Text still streaming when the run ends is kept as the answer instead of vanishing. */
    fun finish(state: SideQuestionUi, now: Long = System.currentTimeMillis()): SideQuestionUi {
        if (!state.running) return state
        val partial = state.streamingText.takeIf { it.isNotBlank() && state.runningCalls.isEmpty() }
        return state.copy(
            running = false, finishedAt = now, retrying = null, runningCalls = emptyList(),
            messages = if (partial == null) state.messages else state.messages + ChatMessage(
                role = Role.ASSISTANT, text = partial, thinking = state.streamingThinking),
            streamingText = "", streamingThinking = "",
        )
    }
}
