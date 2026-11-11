package com.androidharness.app.llm.debug

/**
 * Identity attached to every provider request by `ProviderHeaders.apply`.
 *
 * The debug interceptor reads the tag back off the request, so no provider has
 * to know that capture exists. A request without a tag is still recorded; it
 * simply shows up with an empty provider name.
 */
class DebugTag(
    val providerId: String,
    val providerName: String,
    val model: String,
    val protocol: String,
    /** Which attempt of one turn this request is: 1 for the first try, then retries and failover. */
    val attempt: Int = 1,
    /** Index of the saved key used, so rotated keys stay distinguishable without logging them. */
    val keySlot: Int = 0,
    /** Shared by every attempt of one chat turn; null when the caller does not supply one. */
    val group: String? = null,
    /** The chat this request belongs to, so the log can be browsed per conversation. */
    val sessionId: String? = null,
)

/**
 * Correlates the request a provider built with the stream that is read from it.
 *
 * The interceptor records the trace when response headers arrive, but the body
 * is consumed later by `ProviderFactory.sseJson`, on another thread. The key is
 * the only piece both sides can read without changing the wire request.
 */
class DebugStreamKey(val value: String)

/**
 * One captured provider exchange.
 *
 * Every field is already redacted by [DebugInterceptor] and [DebugRedact]
 * before it lands here, so anything holding a [DebugTrace] may copy it out
 * without a second pass.
 */
data class DebugTrace(
    val id: Long,
    val startedAt: Long,
    val providerId: String,
    val providerName: String,
    val model: String,
    val protocol: String,
    val method: String,
    val url: String,
    val requestHeaders: List<Pair<String, String>>,
    val requestBody: String? = null,
    /** The stored request body is a representation: long string leaves were shortened. */
    val requestBodyElided: Boolean = false,
    /** Characters dropped from the request body, shown in the note. */
    val requestBodyOmitted: Int = 0,
    /** False only when a non-JSON body had to be cut; JSON bodies stay parseable. */
    val requestBodyValidJson: Boolean = true,
    val statusCode: Int? = null,
    val responseHeaders: List<Pair<String, String>> = emptyList(),
    val responsePreview: String? = null,
    /** The original body of a failed response; never null when the status is a failure and a body existed. */
    val errorBody: String? = null,
    val error: String? = null,
    val durationMs: Long = 0,
    /** True when [responsePreview] holds only the first bytes, because the body kept streaming. */
    val previewOnly: Boolean = false,
    val truncated: Boolean = false,
    /** A catalog or reachability probe rather than a chat turn. */
    val probe: Boolean = false,
    /** The response is server-sent events. */
    val stream: Boolean = false,
    /** Links this exchange to the stream reader that consumes it. */
    val streamKey: String = "",
    /** Raw SSE lines, appended by `ProviderFactory.sseJson` once the stream ends. */
    val streamBody: String? = null,
    val streamEvents: Int = 0,
    /** Compact token summary from the last usage chunk, when the provider sends one. */
    val streamUsage: String? = null,
    val attempt: Int = 1,
    val keySlot: Int = 0,
    val group: String? = null,
    /** Chat id this exchange came from; null for probes and one-off calls. */
    val sessionId: String? = null,
) {
    val failed: Boolean get() = error != null || (statusCode != null && statusCode >= 400)

    /** Provider-side request id, useful when asking a provider about a failed call. */
    val providerTraceId: String?
        get() = TRACE_HEADERS.firstNotNullOfOrNull { name ->
            responseHeaders.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second?.takeIf { it.isNotBlank() }
        }

    /** Plain-text dump, identical to what the detail sheet shows and copies. */
    fun asText(): String = buildString {
        appendLine("${method} $url")
        appendLine("Provider: ${providerName.ifBlank { providerId }} (${protocol.ifBlank { "unknown" }})")
        appendLine("Model: ${model.ifBlank { "unknown" }}")
        appendLine("Started: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(startedAt))}")
        appendLine("Duration: ${durationMs}ms")
        if (attempt > 1 || keySlot > 0) {
            appendLine("Attempt: $attempt, key slot: ${keySlot + 1}")
        }
        sessionId?.let { appendLine("Chat: $it") }
        group?.let { appendLine("Turn group: $it") }
        when {
            error != null -> appendLine("Error: $error")
            statusCode != null -> appendLine("Status: $statusCode")
        }
        providerTraceId?.let { appendLine("Provider trace: $it") }
        when {
            requestBodyElided && requestBodyValidJson ->
                appendLine("Note: request body elided ($requestBodyOmitted characters in long fields); JSON structure kept for replay")
            requestBodyElided ->
                appendLine("Note: non-JSON request body cut ($requestBodyOmitted characters)")
            truncated && stream ->
                appendLine("Note: stream truncated, the answer continues past the capture limit")
            truncated -> appendLine("Note: response body truncated")
        }
        if (stream && streamBody == null) {
            appendLine("Note: stream not captured (enable bodies before the turn starts)")
        }
        appendLine()
        appendLine("Request headers:")
        requestHeaders.forEach { (name, value) -> appendLine("  $name: $value") }
        requestBody?.let {
            appendLine()
            appendLine("Request body${if (requestBodyElided) " (elided)" else ""}:")
            appendLine(it)
        }
        responseHeaders.takeIf { it.isNotEmpty() }?.let { headers ->
            appendLine()
            appendLine("Response headers:")
            headers.forEach { (name, value) -> appendLine("  $name: $value") }
        }
        errorBody?.let {
            appendLine()
            appendLine("Error body:")
            appendLine(it)
        }
        responsePreview?.let {
            appendLine()
            appendLine("Response body:")
            appendLine(it)
        }
        streamUsage?.let {
            appendLine()
            appendLine("Usage: $it")
        }
        streamBody?.let {
            appendLine()
            appendLine("Stream ($streamEvents events):")
            appendLine(it)
        }
    }.trimEnd()

    private companion object {
        /** Headers that carry a provider-side request id. */
        val TRACE_HEADERS = listOf("x-ds-trace-id", "x-request-id", "request-id", "cf-ray", "x-amzn-requestid")
    }
}
