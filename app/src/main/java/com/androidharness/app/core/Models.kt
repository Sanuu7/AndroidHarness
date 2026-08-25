package com.androidharness.app.core

import kotlinx.serialization.Serializable

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

@Serializable
data class ToolCallData(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/** A user-attached image; bytes live in the ImageStore, keyed by [name]. */
@Serializable
data class ImageRef(
    val name: String,
    val mime: String,
)

/** Resolved image bytes for provider requests (transient, never persisted). */
data class ImageData(
    val mime: String,
    val base64: String,
)

/**
 * Provider-neutral chat message. Tool calls made by the assistant live on the
 * ASSISTANT message; tool results are separate TOOL messages keyed by [toolCallId].
 */
data class ChatMessage(
    val role: Role,
    val text: String = "",
    val toolCalls: List<ToolCallData> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val isError: Boolean = false,
    val thinking: String = "",
    /** How long the model spent thinking before this message committed. */
    val thinkingMs: Long = 0,
    val images: List<ImageRef> = emptyList(),
    /** Resolved image bytes for the current request; never persisted. */
    val imageData: List<ImageData> = emptyList(),
    /** The agent turn this message belongs to (used for undo checkpoints). */
    val turnId: String? = null,
    /** Database id + timestamp when loaded from storage; null/0 for in-flight. */
    val id: String? = null,
    val createdAt: Long = 0,
)
