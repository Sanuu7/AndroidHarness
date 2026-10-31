package com.androidharness.app.data

/** Whether the agent may change app settings when asked in a conversation. */
enum class AgentSettingsAccess {
    /** The agent can look settings up but not change them. */
    OFF,
    /** Every change waits for the user to approve it in the chat. */
    ASK,
    /** Ordinary changes apply at once with an undo card; sensitive ones still ask. */
    ALLOWED;

    companion object {
        fun fromStored(value: String?): AgentSettingsAccess = entries.firstOrNull { it.name == value } ?: ALLOWED
    }
}
