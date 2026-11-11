package com.androidharness.app.llm.debug

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Reads and writes the request log through [DebugLogDatabase].
 *
 * The list a user browses is durable, so a session from yesterday can still be
 * found by chat and time range; the in-memory ring only feeds the live view.
 */
class DebugLogRepository(
    private val dao: DebugTraceDao,
    private val titleFor: suspend (String) -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val headersSerializer = ListSerializer(ListSerializer(String.serializer()))

    /** One chat this log has entries for, newest activity first. */
    data class Chat(val sessionId: String, val title: String, val entries: Int)

    suspend fun save(traces: List<DebugTrace>) {
        if (traces.isEmpty()) return
        runCatching { dao.upsertAll(traces.map(::toEntity)) }
    }

    suspend fun load(
        since: Long,
        sessionId: String? = null,
        failedOnly: Boolean = false,
        limit: Int = 200,
    ): List<DebugTrace> = runCatching {
        dao.query(since, sessionId, failedOnly, limit).map(::toTrace)
    }.getOrDefault(emptyList())

    /** Chats with activity since [since], titled from the chat database when possible. */
    suspend fun chats(since: Long): List<Chat> = runCatching {
        dao.sessions(since).map { raw ->
            val id = raw.removePrefix("live-")
            Chat(id, titleFor(id) ?: id.take(8), 0)
        }.distinctBy { it.sessionId }
    }.getOrDefault(emptyList())

    suspend fun clear() {
        runCatching { dao.clear() }
    }

    /** Drops rows past the retention window and beyond the row cap. */
    suspend fun prune(now: Long = System.currentTimeMillis()) {
        runCatching {
            dao.pruneBefore(now - RETENTION_MS)
            dao.trimTo(MAX_ROWS)
        }
    }

    private fun toEntity(trace: DebugTrace) = DebugTraceEntity(
        id = trace.id,
        startedAt = trace.startedAt,
        sessionId = normalizeSession(trace.sessionId)?.takeIf { it.isNotBlank() },
        providerId = trace.providerId,
        providerName = trace.providerName,
        model = trace.model,
        protocol = trace.protocol,
        method = trace.method,
        url = trace.url,
        requestHeaders = encodeHeaders(trace.requestHeaders),
        requestBody = DebugCrypto.encrypt(trace.requestBody),
        requestBodyElided = trace.requestBodyElided,
        requestBodyOmitted = trace.requestBodyOmitted,
        requestBodyValidJson = trace.requestBodyValidJson,
        statusCode = trace.statusCode,
        responseHeaders = encodeHeaders(trace.responseHeaders),
        responsePreview = DebugCrypto.encrypt(trace.responsePreview),
        errorBody = DebugCrypto.encrypt(trace.errorBody),
        error = trace.error,
        durationMs = trace.durationMs,
        previewOnly = trace.previewOnly,
        truncated = trace.truncated,
        probe = trace.probe,
        stream = trace.stream,
        streamBody = DebugCrypto.encrypt(trace.streamBody),
        streamEvents = trace.streamEvents,
        streamUsage = trace.streamUsage,
        attempt = trace.attempt,
        keySlot = trace.keySlot,
        turnGroup = trace.group,
        failed = trace.failed,
    )

    private fun toTrace(entity: DebugTraceEntity) = DebugTrace(
        id = entity.id,
        startedAt = entity.startedAt,
        providerId = entity.providerId,
        providerName = entity.providerName,
        model = entity.model,
        protocol = entity.protocol,
        method = entity.method,
        url = entity.url,
        requestHeaders = decodeHeaders(entity.requestHeaders),
        requestBody = DebugCrypto.decrypt(entity.requestBody),
        requestBodyElided = entity.requestBodyElided,
        requestBodyOmitted = entity.requestBodyOmitted,
        requestBodyValidJson = entity.requestBodyValidJson,
        statusCode = entity.statusCode,
        responseHeaders = decodeHeaders(entity.responseHeaders),
        responsePreview = DebugCrypto.decrypt(entity.responsePreview),
        errorBody = DebugCrypto.decrypt(entity.errorBody),
        error = entity.error,
        durationMs = entity.durationMs,
        previewOnly = entity.previewOnly,
        truncated = entity.truncated,
        probe = entity.probe,
        stream = entity.stream,
        streamKey = "",
        streamBody = DebugCrypto.decrypt(entity.streamBody),
        streamEvents = entity.streamEvents,
        streamUsage = entity.streamUsage,
        attempt = entity.attempt,
        keySlot = entity.keySlot,
        group = entity.turnGroup,
        sessionId = entity.sessionId,
    )

    private fun encodeHeaders(headers: List<Pair<String, String>>): String =
        runCatching { json.encodeToString(headersSerializer, headers.map { listOf(it.first, it.second) }) }
            .getOrDefault("[]")

    private fun decodeHeaders(value: String): List<Pair<String, String>> =
        runCatching { json.decodeFromString(headersSerializer, value) }
            .getOrDefault(emptyList())
            .map { it.firstOrNull().orEmpty() to it.getOrElse(1) { "" } }

    companion object {
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        /** Bodies and streams are capped at 128k each, so the row budget stays bounded. */
        const val MAX_ROWS = 200

        /** Live mode prefixes the chat id; the log groups it with the chat it came from. */
        fun normalizeSession(value: String?): String? = value?.removePrefix("live-")
    }
}
