package com.androidharness.app.ui.chat

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role

/** Keep the final answer outside the activity, preserving earlier narration and reasoning. */
internal fun completedTurnActivities(messages: List<ChatMessage>): Map<String, List<ChatMessage>> =
    messages.filter { it.role == Role.ASSISTANT && it.toolCallId == null && it.turnId != null }
        .groupBy { it.turnId!! }
        .mapValues { (_, assistants) ->
            assistants.dropLast(1) + assistants.last().copy(text = "")
        }
        .mapValues { (_, activity) ->
            activity.filter { it.text.isNotBlank() || it.thinking.isNotBlank() || it.toolCalls.isNotEmpty() }
        }
