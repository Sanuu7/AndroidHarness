package com.androidharness.app.data

import kotlinx.serialization.Serializable

/** How far the Live Mode assistant may reach beyond talking. */
@Serializable
enum class LiveModeAccess {
    /** Conversation only. No tools, so replies come back fastest. */
    TALK,
    /** Reads files and searches, never changes anything. */
    READ,
    /** Reads, and may hand a task to the main agent, which runs it with the chat's usual permissions. */
    START_TASKS,
}

/**
 * Device-local Live Mode preferences. Experimental, so [enabled] starts off and
 * a settings import never turns it on.
 */
@Serializable
data class LiveModeSettings(
    val enabled: Boolean = false,
    /** Null uses the chat's own model. */
    val providerId: String? = null,
    val model: String? = null,
    val access: LiveModeAccess = LiveModeAccess.READ,
    val speakReplies: Boolean = true,
    /** Quiet time after speech before an utterance is treated as finished. */
    val silenceMs: Int = DEFAULT_SILENCE_MS,
) {
    companion object {
        const val DEFAULT_SILENCE_MS = 1_200
        const val MIN_SILENCE_MS = 600
        const val MAX_SILENCE_MS = 3_000
    }
}
