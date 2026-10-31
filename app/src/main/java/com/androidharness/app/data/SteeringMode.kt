package com.androidharness.app.data

/** What a message sent while the agent works does. */
enum class SteeringMode {
    /** Read at the agent's next step, or sent as the next turn when the task already ended. */
    AH_AUTO,
    /** Waits until the whole task finishes, then starts a new turn. */
    AFTER_TASK;

    companion object {
        fun fromStored(value: String?): SteeringMode = entries.firstOrNull { it.name == value } ?: AH_AUTO
    }
}
