package com.androidharness.app.tools.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persisted marker that a server connected successfully at least once
 * (via a run, a Test, or an OAuth completion). Only app-side servers from
 * mcp-servers.json are ever recorded; workspace `.harness/mcp.json` servers
 * stay lazy on purpose so a cloned repo's config never spawns commands at
 * app start (security-battery D1).
 */
@Serializable
data class McpStoredStatus(
    val toolCount: Int = 0,
    val connectedAtMs: Long = 0,
)

/**
 * Pure decision logic for startup auto-reconnect: reconnect exactly the
 * enabled app-side servers that connected successfully before. A past
 * failure never disqualifies a server, networks come up after the app
 * does, and a fresh attempt each launch is the whole point.
 */
object McpReconnectPolicy {

    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), McpStoredStatus.serializer())

    /** Enabled, previously-connected servers to reconnect at startup. */
    fun candidates(
        servers: List<McpServerConfig>,
        stored: Map<String, McpStoredStatus>,
    ): List<McpServerConfig> = servers.filter { it.enabled && stored.containsKey(it.name) }

    fun parseStored(text: String): Map<String, McpStoredStatus> =
        runCatching { json.decodeFromString(mapSerializer, text) }.getOrDefault(emptyMap())

    fun serializeStored(stored: Map<String, McpStoredStatus>): String =
        json.encodeToString(mapSerializer, stored)
}
