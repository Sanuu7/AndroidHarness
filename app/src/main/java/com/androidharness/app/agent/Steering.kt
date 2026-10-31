package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role

/**
 * Messages the user sent while a run works. [take] saves each one to the chat
 * and removes it from the queue before returning it, so a message is either
 * read by the running task or left queued for the next turn, never both.
 */
fun interface SteeringInbox {
    suspend fun take(): List<ChatMessage>
}

object Steering {
    const val ID_PREFIX = "steer-"

    fun messageId(promptId: String): String = ID_PREFIX + promptId
    fun isSteering(message: ChatMessage): Boolean = message.role == Role.USER && message.id?.startsWith(ID_PREFIX) == true

    /**
     * The chat stores what the user typed. The model also learns it arrived
     * mid-task, so it adjusts course instead of starting over. Applied the same
     * way live and when history is rebuilt, so the cached prefix stays identical.
     */
    fun forModel(message: ChatMessage): ChatMessage =
        if (!isSteering(message) || message.text.startsWith(OPEN)) message else message.copy(text = wrap(message.text))

    private const val OPEN = "<steering_message>"

    private fun wrap(text: String): String =
        "$OPEN\nThe user sent this message while you were working. Take it into account from your next step on " +
            "and continue the task. If it changes what they want, follow it.\n\n$text\n</steering_message>"
}
