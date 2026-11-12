package com.androidharness.app.core

import kotlinx.serialization.Serializable

/** The agent asked for storage access; the chat shows a card with the buttons that open the right Android pages. */
@Serializable
data class StorageAccessReceipt(
    /** Why the task needs it, in the agent's words. */
    val reason: String,
    /** The shared-storage location involved, when there is one. */
    val path: String? = null,
)
