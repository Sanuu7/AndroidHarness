package com.androidharness.app.llm

import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

@Serializable
data class ProviderHeader(val name: String, val value: String, val enabled: Boolean = true) {
    override fun toString(): String = "ProviderHeader(name=$name, enabled=$enabled)"
}

/** One policy for the editor, encrypted storage, backups, and every provider transport. */
object ProviderHeaders {
    const val MAX_HEADERS = 20
    private val token = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    private val managed = setOf("authorization", "proxy-authorization", "x-api-key", "x-goog-api-key",
        "host", "content-type", "content-length", "connection", "transfer-encoding", "upgrade", "trailer")

    fun error(headers: List<ProviderHeader>): String? {
        if (headers.size > MAX_HEADERS) return "Use at most $MAX_HEADERS custom headers."
        val names = mutableSetOf<String>()
        headers.forEach { header ->
            val name = header.name.trim()
            if (name.length !in 1..256 || !token.matches(name)) return "Enter a valid header name."
            val lower = name.lowercase(Locale.ROOT)
            if (lower in managed) return "Authentication and connection headers are managed by the app."
            if (!names.add(lower)) return "Each header name must be unique."
            if (header.value.length > 8192 || header.value.any { it != '\t' && it !in ' '..'~' }) {
                return "Header values must be a single line of HTTP text (up to 8192 characters)."
            }
        }
        return null
    }

    fun normalized(headers: List<ProviderHeader>): List<ProviderHeader> {
        require(error(headers) == null) { error(headers).orEmpty() }
        return headers.map { it.copy(name = it.name.trim()) }
    }

    fun editable(config: ProviderConfig): Boolean = config.id != HarnessProvider.ID &&
        !com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(config.id) &&
        !com.androidharness.app.local.LocalModelCatalog.isLocal(config.id) && !config.baseUrl.startsWith("local://")

    fun apply(
        builder: Request.Builder,
        config: ProviderConfig,
        attempt: Int = 1,
        keySlot: Int = 0,
        group: String? = null,
        sessionId: String? = null,
    ): Request.Builder {
        if (editable(config)) normalized(config.headers).filter { it.enabled }.forEach { builder.header(it.name, it.value) }
        // The debug console reads these tags back; providers stay unaware of capture.
        builder.tag(
            com.androidharness.app.llm.debug.DebugTag::class.java,
            com.androidharness.app.llm.debug.DebugTag(config.id, config.name, config.model, config.type.name,
                attempt, keySlot, group, sessionId),
        )
        return builder.tag(
            com.androidharness.app.llm.debug.DebugStreamKey::class.java,
            com.androidharness.app.llm.debug.DebugStreamKey(java.util.UUID.randomUUID().toString()),
        )
    }

    /** Custom headers can contain credentials; never forward them through a redirect. */
    fun client(config: ProviderConfig, client: OkHttpClient): OkHttpClient {
        val routed = ProviderNetwork.client(client, config.id)
        return if (editable(config) && config.headers.any { it.enabled }) routed.newBuilder()
            .followRedirects(false).followSslRedirects(false).build() else routed
    }
}
