package com.androidharness.app.llm.debug

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Records provider traffic for the in-app console.
 *
 * Two rules keep this out of the way of a live chat:
 *  - a streaming (`text/event-stream`) body is never read here. `peekBody`
 *    blocks until it has the requested bytes, which on an SSE stream means
 *    waiting for the whole answer. Streams are tapped at the reader later.
 *  - nothing is buffered when capture is off, and request bodies are only
 *    read when the user also enabled body capture.
 *
 * The interceptor is a pass-through when [DebugCapture.enabled] is false.
 */
class DebugInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!DebugCapture.enabled) return chain.proceed(chain.request())
        val request = chain.request()
        val tag = request.tag(DebugTag::class.java)
        val streamKey = request.tag(DebugStreamKey::class.java)?.value.orEmpty()
        val startedAt = System.currentTimeMillis()
        val startedNanos = System.nanoTime()
        val rawHeaders = (0 until request.headers.size).map { request.headers.name(it) to request.headers.value(it) }
        val secrets = DebugRedact.secretsOf(rawHeaders)
        val path = request.url.encodedPath
        val probe = request.method == "GET" && path.endsWith("/models")
        val requestBody = if (DebugCapture.captureBodies) requestBody(request, secrets) else null

        fun record(response: Response?, failure: Exception?) {
            val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
            val contentType = response?.header("Content-Type").orEmpty()
            val stream = contentType.contains("text/event-stream", ignoreCase = true)
            val failed = response != null && !response.isSuccessful
            val errorText = if (failed && !stream) preview(response!!, ERROR_BODY_CHARS, secrets) else null
            val bodyPreview = if (response != null && DebugCapture.captureBodies && !failed && !stream) {
                preview(response, RESPONSE_BODY_CHARS, secrets)
            } else {
                null
            }
            val truncated = (errorText?.second ?: false) || (bodyPreview?.second ?: false)
            DebugCapture.store.record(
                DebugTrace(
                    id = DebugCapture.nextTraceId(),
                    startedAt = startedAt,
                    providerId = tag?.providerId.orEmpty(),
                    providerName = tag?.providerName.orEmpty(),
                    model = tag?.model.orEmpty(),
                    protocol = tag?.protocol.orEmpty(),
                    method = request.method,
                    url = DebugRedact.url(request.url.toString()),
                    requestHeaders = DebugRedact.headers(rawHeaders),
                    requestBody = requestBody?.text,
                    requestBodyElided = requestBody?.elided ?: false,
                    requestBodyOmitted = requestBody?.omitted ?: 0,
                    requestBodyValidJson = requestBody?.validJson ?: true,
                    statusCode = response?.code,
                    streamKey = streamKey,
                    attempt = tag?.attempt ?: 1,
                    keySlot = tag?.keySlot ?: 0,
                    group = tag?.group,
                    sessionId = tag?.sessionId,
                    responseHeaders = response?.headers?.let { headers ->
                        (0 until headers.size).map { headers.name(it) to headers.value(it) }
                    }?.let(DebugRedact::headers).orEmpty(),
                    responsePreview = bodyPreview?.first,
                    errorBody = errorText?.first,
                    error = failure?.let { it.message ?: it::class.java.simpleName },
                    durationMs = duration,
                    previewOnly = stream,
                    truncated = truncated,
                    probe = probe,
                    stream = stream,
                )
            )
        }

        return try {
            val response = chain.proceed(request)
            record(response, null)
            response
        } catch (e: IOException) {
            record(null, e)
            throw e
        } catch (e: RuntimeException) {
            record(null, e)
            throw e
        }
    }

    /**
     * Reads the buffered request body through a copy, so the real request still
     * has its bytes. One-shot and duplex bodies are skipped because reading them
     * would empty the request.
     */
    private fun requestBody(request: Request, secrets: List<String>): DebugBodies.Body? {
        val body = request.body ?: return null
        if (body.isOneShot() || body.isDuplex()) return null
        val length = body.contentLength()
        if (length > MAX_REQUEST_BYTES) return DebugBodies.Body("[not captured: $length bytes]", elided = true, validJson = false)
        return try {
            val buffer = Buffer()
            body.writeTo(buffer)
            val text = DebugRedact.body(buffer.readUtf8(), secrets)
            DebugBodies.shrink(text, REQUEST_BODY_CHARS)
        } catch (_: Exception) {
            null
        }
    }

    private fun preview(response: Response, limit: Int, secrets: List<String>): Pair<String, Boolean> {
        val peeked = try {
            response.peekBody(limit.toLong()).string()
        } catch (_: Exception) {
            return "" to false
        }
        val declared = response.body?.contentLength() ?: -1
        val cut = (declared > limit) || peeked.length >= limit
        return DebugCapture.cap(DebugRedact.body(peeked, secrets), limit).let { (text, capped) ->
            (text ?: "") to (cut || capped)
        }
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 16_000_000L
        const val REQUEST_BODY_CHARS = 128_000
        const val RESPONSE_BODY_CHARS = 128_000
        const val ERROR_BODY_CHARS = 8_000
    }
}
