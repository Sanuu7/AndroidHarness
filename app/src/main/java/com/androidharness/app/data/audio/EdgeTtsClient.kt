package com.androidharness.app.data.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Microsoft Edge's Read Aloud service, used directly: no Azure subscription, no
 * key, no third-party router.
 *
 * This is NOT an HTTP endpoint. Synthesis is a WebSocket session: connect, send
 * `speech.config`, send SSML, collect binary audio frames until `turn.end`.
 * The voice catalogue is a separate plain HTTP call ([VOICE_LIST_URL]).
 *
 * Authentication is a derived token called `Sec-MS-GEC`, computed from the
 * current time. Two details matter and are easy to get wrong:
 *  - the timestamp is Windows file time (unix + 11644473600) in 100 ns ticks,
 *    rounded DOWN to a 300 second window, then multiplied by 10^7;
 *  - the digest is uppercase hex, concatenated with the fixed client token.
 * A wrong token is rejected with HTTP 403 at the handshake.
 */
object EdgeTtsClient {

    /** Public client token every Edge read-aloud client uses. Not a secret. */
    private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    /**
     * Synthesis WebSocket. Measured against the live service: the old
     * speech.platform.bing.com host now answers 401, while this one completes
     * the handshake with the "1-" version prefix.
     */
    private const val WSS_URL = "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1"

    /**
     * Voice catalogue. Still served from the original host — the equivalent path
     * on api.msedgeservices.com returns 403.
     */
    const val VOICE_LIST_URL =
        "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list" +
            "?trustedclienttoken=$TRUSTED_TOKEN"

    /** Every voice in the catalogue reports this codec. */
    const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

    /** Pinned client build string; the service accepts this format today. */
    /**
     * Chromium build advertised to the service. Microsoft bumps this with each
     * Edge release and rejects clients claiming an old build, so
     * [EdgeVersionProvider] refreshes it at runtime.
     */
    @Volatile
    var chromiumVersion: String = FALLBACK_CHROMIUM_VERSION

    /** Used until, or if, the live Edge build number cannot be fetched. */
    const val FALLBACK_CHROMIUM_VERSION = "156.0.4314.80"

    /**
     * The "1-" generation prefix is what the live service accepts; "2-" is
     * refused with 403 even with an otherwise valid token (measured
     * 2026-11-11).
     */
    internal fun gecVersion(): String = "1-$chromiumVersion"

    /** Seconds between the Windows epoch (1601) and the Unix epoch (1970). */
    private const val WINDOWS_EPOCH_OFFSET_SECONDS = 11_644_473_600L

    /** The service rotates the token window every five minutes. */
    private const val GEC_WINDOW_SECONDS = 300L

    private const val HANDSHAKE_TIMEOUT_MS = 15_000L
    private const val TURN_TIMEOUT_MS = 45_000L

    /**
     * PlayAI-style input limits do not apply, but very long text produces a long
     * session; the controller already chunks replies, this is a safety net.
     */
    const val MAX_INPUT_CHARS = 4_000

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // the WebSocket manages its own idle time
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /**
     * `Sec-MS-GEC` for a given instant. Pure so a unit test can pin the clock and
     * assert a known digest.
     */
    internal fun gecToken(nowMillis: Long): String {
        val unixSeconds = nowMillis / 1000
        val rounded = unixSeconds - (unixSeconds % GEC_WINDOW_SECONDS)
        val windowsTicks = (rounded + WINDOWS_EPOCH_OFFSET_SECONDS) * 10_000_000L
        return sha256Hex("$windowsTicks$TRUSTED_TOKEN").uppercase(Locale.US)
    }

    /** Wire timestamp, always in the GMT form the service expects. */
    internal fun timestamp(nowMillis: Long): String {
        val format = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US,
        ).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return format.format(Date(nowMillis))
    }

    /**
     * `pt-BR-FranciscaNeural` becomes
     * `pt-BR-FranciscaNeural` → `(pt-BR, FranciscaNeural)`, which is what SSML
     * requires for [voiceTag].
     */
    internal fun voiceTag(shortName: String): String {
        val parts = shortName.split('-')
        if (parts.size < 3) return "Microsoft Server Speech Text to Speech Voice (en-US, AriaNeural)"
        val locale = parts[0] + "-" + parts[1]
        val name = parts.drop(2).joinToString("-")
        return "Microsoft Server Speech Text to Speech Voice ($locale, $name)"
    }

    /** Escapes the five XML entities SSML must not receive raw. */
    internal fun escapeSsml(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    /** SSML document for one clip. [speed] is the app's 0.5..2.0 multiplier. */
    internal fun ssml(text: String, shortName: String, speed: Float): String {
        val percent = ((speed.coerceIn(0.5f, 2f) - 1f) * 100).toInt()
        val sign = if (percent >= 0) "+" else ""
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='${voiceTag(shortName)}'>" +
            "<prosody rate='$sign$percent%' pitch='+0Hz'>${escapeSsml(text)}</prosody>" +
            "</voice></speak>"
    }

    /** Builds the authenticated WebSocket URL for one session. */
    internal fun sessionUrl(nowMillis: Long): String =
        "$WSS_URL?Ocp-Apim-Subscription-Key=$TRUSTED_TOKEN" +
            "&Sec-MS-GEC=${gecToken(nowMillis)}" +
            "&Sec-MS-GEC-Version=${gecVersion()}" +
            "&ConnectionId=${UUID.randomUUID().toString().replace("-", "")}"

    /**
     * Headers the Read Aloud endpoint expects. [Origin] identifies the Edge
     * extension and is the difference between a 101 and a 403: the service
     * checks it even though the parameter is not a browser-issued request.
     */
    internal fun handshakeHeaders(): Map<String, String> {
        val major = chromiumVersion.substringBefore('.')
        return mapOf(
            "Pragma" to "no-cache",
            "Cache-Control" to "no-cache",
            "Origin" to "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
            "Accept-Encoding" to "gzip, deflate, br",
            "Accept-Language" to "en-US,en;q=0.9",
            "User-Agent" to (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36 " +
                    "Edg/$major.0.0.0"
                ),
        )
    }

    /**
     * Synthesizes one clip. Returns MP3 bytes, or a failure describing what the
     * service rejected.
     *
     * [onForbidden] runs once if the handshake is refused with 403, so the
     * caller can refresh the advertised build and retry: Microsoft bumps Edge
     * regularly and an outdated build number is the usual reason for a refusal.
     */
    suspend fun synthesize(
        text: String,
        shortName: String,
        speed: Float = 1f,
        nowMillis: Long = System.currentTimeMillis(),
        onForbidden: (suspend () -> Unit)? = null,
        retried: Boolean = false,
    ): Result<ByteArray> {
        if (text.isBlank() || text.none(Char::isLetterOrDigit)) {
            return Result.failure(IllegalArgumentException("Nothing to speak"))
        }
        if (shortName.isBlank()) {
            return Result.failure(IllegalStateException("Choose an Edge voice"))
        }
        val clip = text.take(MAX_INPUT_CHARS)
        return withContext(Dispatchers.IO) {
            val first = try {
                requestAudio(clip, shortName, speed, nowMillis)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            val failure = first.exceptionOrNull()
            val forbidden = failure is EdgeApiException && failure.status == 403
            if (!forbidden || retried || onForbidden == null) return@withContext first
            // A refused handshake usually means the advertised build is stale:
            // refresh it and try once more.
            onForbidden.invoke()
            synthesize(clip, shortName, speed, nowMillis, onForbidden = null, retried = true)
        }
    }

    /** A handshake or protocol failure carrying the HTTP status when known. */
    class EdgeApiException(message: String, val status: Int?) : java.io.IOException(message)

    private suspend fun requestAudio(
        text: String,
        shortName: String,
        speed: Float,
        nowMillis: Long,
    ): Result<ByteArray> = suspendCancellableCoroutine { continuation ->
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val audio = ByteArrayOutputStream()
        val request = Request.Builder()
            .url(sessionUrl(nowMillis))
            .apply { handshakeHeaders().forEach { (name, value) -> header(name, value) } }
            .build()
        var socket: WebSocket? = null
        var finished = false

        fun finish(result: Result<ByteArray>) {
            if (finished) return
            finished = true
            runCatching { socket?.cancel() }
            if (continuation.isActive) continuation.resume(result)
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                // 1. Session config: chooses the audio format for everything that follows.
                webSocket.send(
                    "X-Timestamp:${timestamp(nowMillis)}\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        """{"context":{"synthesis":{"audio":{"metadataoptions":{""" +
                        """"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},""" +
                        """"outputFormat":"$OUTPUT_FORMAT"}}}}""",
                )
                // 2. The text itself, as SSML for the chosen voice.
                webSocket.send(
                    "X-RequestId:$requestId\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${timestamp(nowMillis)}Z\r\n" +
                        "Path:ssml\r\n\r\n" +
                        ssml(text, shortName, speed),
                )
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary frames carry audio behind a 2-byte big-endian header length.
                val frame = bytes.toByteArray()
                val audioPart = audioFromFrame(frame)
                if (audioPart != null) audio.write(audioPart, 0, audioPart.size)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    val bytes = audio.toByteArray()
                    finish(
                        if (bytes.isEmpty()) {
                            Result.failure(IOException("Edge returned no audio"))
                        } else {
                            Result.success(bytes)
                        },
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code
                val message = when (status) {
                    403 -> "Edge recusou a conexão (403) com a versão $chromiumVersion. " +
                        "Se persistir, a Microsoft mudou o protocolo."
                    401 -> "Edge pediu autenticação (401)."
                    451 -> "Edge bloqueou por região (451)."
                    null -> t.message ?: "Edge connection failed"
                    else -> "Edge connection failed (HTTP $status)"
                }
                finish(Result.failure(EdgeApiException(message, status)))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish(Result.failure(IOException("Edge closed the session early ($code $reason)")))
            }
        }

        val opened = httpClient.newWebSocket(request, listener)
        socket = opened
        continuation.invokeOnCancellation {
            runCatching { opened.cancel() }
        }

        // A session that never opens or never finishes must surface an error
        // instead of hanging: the upstream clients hang forever on a rejected
        // handshake, which is worse than a clean failure.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val done = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS + TURN_TIMEOUT_MS) {
                while (!finished) {
                    kotlinx.coroutines.delay(250)
                }
                true
            }
            if (done == null) {
                finish(Result.failure(IOException("Edge timed out without returning audio")))
            }
        }
    }

    /** Strips the 2-byte header-length prefix and its header block. */
    internal fun audioFromFrame(frame: ByteArray): ByteArray? {
        if (frame.size < 2) return null
        val headerLength = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        val start = 2 + headerLength
        if (headerLength <= 0 || start >= frame.size) return null
        return frame.copyOfRange(start, frame.size)
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
