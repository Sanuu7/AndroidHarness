package com.androidharness.app.core

import kotlinx.serialization.Serializable

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

@Serializable
data class ToolCallData(
    val id: String,
    val name: String,
    val argumentsJson: String,
    /** Opaque Responses reasoning carried with the first tool call for stateless replay. */
    val responseReasoning: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    val responseProviderId: String? = null,
    /** Measured file changes for the activity row; never sent as tool arguments. */
    val codeChanges: List<ToolCodeChange> = emptyList(),
    /** Captured presentation metadata; never part of provider tool arguments. */
    val workspaceId: String? = null,
    val workspaceName: String? = null,
)

@Serializable
data class ToolCodeChange(val path: String, val added: Int, val removed: Int)

/** A user-attached image; bytes live in the ImageStore, keyed by [name]. */
@Serializable
data class ImageRef(
    val name: String,
    val mime: String,
    val imageId: String? = null,
    val sourceUrl: String? = null,
    val caption: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bytes: Long? = null,
    val origin: String? = null,
    val originalWidth: Int? = null,
    val originalHeight: Int? = null,
    val originalMime: String? = null,
    val originalBytes: Long? = null,
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
    /** Provider-reported output and measured model request time; zero means unavailable. */
    val outputTokens: Int = 0,
    val generationMs: Long = 0,
    val firstTokenMs: Long = 0,
    val streamMs: Long = 0,
    val images: List<ImageRef> = emptyList(),
    /** Resolved image bytes for the current request; never persisted. */
    val imageData: List<ImageData> = emptyList(),
    /** The agent turn this message belongs to (used for undo checkpoints). */
    val turnId: String? = null,
    /** Database id + timestamp when loaded from storage; null/0 for in-flight. */
    val id: String? = null,
    val createdAt: Long = 0,
    val artifact: com.androidharness.app.artifacts.ChatArtifact? = null,
    val generation: GenerationMetadata = GenerationMetadata(),
    /**
     * True for the volatile-tail message injected per request to carry
     * runtime context (environment, todos, memory) without polluting the
     * stable cached prefix. Never persisted to the database; excluded from
     * recentMemoryQuery so it cannot change the memory-recall query.
     */
    val ephemeral: Boolean = false,
)
