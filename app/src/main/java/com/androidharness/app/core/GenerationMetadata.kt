package com.androidharness.app.core

import com.androidharness.app.llm.ProviderConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Opaque provider output. Keep signatures and item order intact; never replay to another endpoint. */
@Serializable
data class ProviderMessageState(
    val providerId: String,
    val endpointHash: String,
    val model: String,
    val format: String,
    val items: List<JsonObject> = emptyList(),
    val contentDigest: String? = null,
) {
    fun matches(config: ProviderConfig, expectedFormat: String, message: ChatMessage? = null): Boolean =
        providerId == config.id && endpointHash == sha256(config.baseUrl.trimEnd('/')) && model == config.model && format == expectedFormat &&
            (message == null || contentDigest == null || contentDigest == digest(message))

    fun boundTo(message: ChatMessage): ProviderMessageState = copy(contentDigest = digest(message))

    companion object {
        fun of(config: ProviderConfig, format: String, items: List<JsonObject>) = ProviderMessageState(
            config.id, sha256(config.baseUrl.trimEnd('/')), config.model, format, items)
        private fun digest(message: ChatMessage): String {
            val content = kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(message.text))
                message.toolCalls.forEach {
                    add(kotlinx.serialization.json.JsonPrimitive(it.id))
                    add(kotlinx.serialization.json.JsonPrimitive(it.name))
                    add(kotlinx.serialization.json.JsonPrimitive(it.argumentsJson))
                }
            }.toString()
            return sha256(content)
        }
        private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
data class SuppliedMemory(val id: String, val text: String)

@Serializable
data class GenerationMetadata(
    val state: ProviderMessageState? = null,
    /** Null means the provider did not report a separate reasoning count. Zero is a real count. */
    val reasoningTokens: Int? = null,
    val reasoningSetting: String? = null,
    val suppliedMemories: List<SuppliedMemory> = emptyList(),
    val execution: List<com.androidharness.app.data.env.ExecutionReceipt> = emptyList(),
    /** Catalog id of the model that produced this answer, for provenance. */
    val model: String? = null,
    /** Short next-question chips offered under the answer. */
    val followUps: List<String> = emptyList(),
    val research: ResearchReceipt? = null,
    val settings: SettingsReceipt? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(value: String?): GenerationMetadata = value?.let {
            runCatching { json.decodeFromString<GenerationMetadata>(it) }.getOrNull()
        } ?: GenerationMetadata()
        fun encode(value: GenerationMetadata): String? = value.takeUnless { it == GenerationMetadata() }
            ?.let { json.encodeToString(serializer(), it) }
    }
}
