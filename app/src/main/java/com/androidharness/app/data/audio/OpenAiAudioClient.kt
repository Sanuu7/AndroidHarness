package com.androidharness.app.data.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The OpenAI Audio wire format, which most providers speak: multipart
 * /audio/transcriptions in, JSON /audio/speech out. Groq, OpenAI, Together,
 * Fireworks and local servers (Kokoro, XTTS, faster-whisper) all work through
 * this one client as long as the base URL and model ids are right.
 */
object OpenAiAudioClient {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val wavMedia = "audio/wav".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val urlPattern = Regex("""https?://[^\s"'<>]+""")

    /** A provider error with its machine code and, for model terms, a direct link. */
    class VoiceApiException(
        override val message: String,
        val code: String? = null,
        val actionUrl: String? = null,
        val status: Int? = null,
    ) : IOException(message)

    internal fun apiError(body: String, status: Int, fallbackName: String): VoiceApiException {
        val error = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
        }.getOrNull()
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull
            ?: "“$fallbackName” request failed (HTTP $status)"
        val code = runCatching { error?.get("code")?.jsonPrimitive?.contentOrNull }.getOrNull()
        val url = urlPattern.find(message)?.value?.trimEnd('.', ',', ')', ']')
        return VoiceApiException(message, code, url, status)
    }

    /** Key check that works for any OpenAI-compatible endpoint. */
    suspend fun validateKey(
        baseUrl: String,
        apiKey: String,
        client: OkHttpClient = httpClient,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) return@withContext Result.failure(IllegalArgumentException("API key cannot be blank"))
        val url = "${baseUrl.trimEnd('/')}/models"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $trimmed")
            .get()
            .build()
        try {
            execute(client, request).use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(
                    apiError(response.body?.string().orEmpty(), response.code, "Voice")
                        .let { safeError(it, trimmed) },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(safeError(e, trimmed))
        }
    }

    /** Model ids the endpoint reports; empty list when it has no /models route. */
    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/models")
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}") }
                .get()
                .build()
            try {
                execute(httpClient, request).use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Result.failure(apiError(body, response.code, "Voice"))
                    } else {
                        val ids = runCatching {
                            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                                .orEmpty()
                        }.getOrDefault(emptyList())
                        Result.success(ids)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun transcribe(
        audio: File,
        baseUrl: String,
        apiKey: String,
        model: String,
        language: String? = null,
        client: OkHttpClient = httpClient,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (model.isBlank()) return@withContext Result.failure(IllegalStateException("Choose a transcription model"))
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody("audio/m4a".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
        language?.takeIf { it.isNotBlank() }?.let { builder.addFormDataPart("language", it) }
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/audio/transcriptions")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}") }
            .post(builder.build())
            .build()
        try {
            execute(client, request).use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Result.failure(safeError(apiError(body, response.code, "Transcription"), apiKey))
                } else {
                    val text = runCatching {
                        json.parseToJsonElement(body).jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()?.trim()
                    if (text == null) Result.failure(IllegalStateException("The provider returned an invalid transcription."))
                    else Result.success(text)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(safeError(e, apiKey))
        }
    }

    /**
     * Returns a cached audio file for this exact provider/voice/speed/text,
     * generating it once. Cache names hash the input so replaying costs nothing.
     */
    suspend fun synthesizeCached(
        cacheDir: File,
        provider: VoiceProviderConfig,
        apiKey: String,
        text: String,
        model: String,
        voice: String,
        speed: Float,
    ): Result<File> = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, "tts").apply { mkdirs() }
        val format = responseFormat(provider, model)
        // v3: keys include the provider so switching providers never replays
        // another vendor's audio.
        val key = sha256("v3|${provider.id}|$model|$voice|$speed|$text")
        val file = File(dir, "$key.$format")
        if (file.exists() && file.length() > 0) return@withContext Result.success(file)
        synthesize(provider, apiKey, text, model, voice, speed).mapCatching { bytes ->
            val temp = File(dir, "$key.part")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            trimCache(dir)
            file
        }
    }

    suspend fun synthesize(
        provider: VoiceProviderConfig,
        apiKey: String,
        text: String,
        model: String,
        voice: String,
        speed: Float,
    ): Result<ByteArray> {
        if (text.isBlank() || text.none(Char::isLetterOrDigit)) {
            return Result.failure(IllegalArgumentException("Nothing to speak"))
        }
        return when (provider.protocol) {
            VoiceProtocol.OPENAI_AUDIO -> synthesizeOpenAi(provider, apiKey, text, model, voice, speed)
            VoiceProtocol.ELEVENLABS -> ElevenLabsClient.synthesize(provider, apiKey, text, model, voice, speed)
            VoiceProtocol.CARTESIA -> CartesiaClient.synthesize(provider, apiKey, text, model, voice, speed)
            VoiceProtocol.DEEPGRAM -> DeepgramVoiceClient.synthesize(provider, apiKey, text, model, voice, speed)
            VoiceProtocol.ASSEMBLYAI ->
                Result.failure(UnsupportedOperationException("AssemblyAI does not offer text to speech"))
        }
    }

    private suspend fun synthesizeOpenAi(
        provider: VoiceProviderConfig,
        apiKey: String,
        text: String,
        model: String,
        voice: String,
        speed: Float,
    ): Result<ByteArray> {
        val chosenModel = model.ifBlank { provider.ttsModel }
        // Edge TTS through a router takes an empty model id on purpose: the
        // request is routed by the "provider/" prefix alone.
        val emptyModelAllowed = provider.id == VoicePresets.EDGE
        if (chosenModel.isBlank() && !emptyModelAllowed) {
            return Result.failure(IllegalStateException("Choose a speech model"))
        }
        val payload = buildJsonObject {
            put("model", chosenModel)
            put("input", text)
            put("voice", voice.ifBlank { provider.ttsVoice })
            put("response_format", responseFormat(provider, chosenModel))
            put("speed", speed.coerceIn(0.5f, 2f))
        }.toString()
        val request = Request.Builder()
            .url("${provider.resolvedBaseUrl()}/audio/speech")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}") }
            .post(payload.toRequestBody(jsonMedia))
            .build()
        return try {
            execute(httpClient, request).use { response ->
                if (!response.isSuccessful) {
                    Result.failure(safeError(apiError(response.body?.string().orEmpty(), response.code, provider.name), apiKey))
                } else {
                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        Result.failure(IOException("${provider.name} returned no audio"))
                    } else {
                        Result.success(if (responseFormat(provider, chosenModel) == "wav") fixStreamingWav(bytes) else bytes)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(safeError(e, apiKey))
        }
    }

    /** Requests of one clip: Orpheus caps at ~200 chars, the rest allow more. */
    const val MAX_INPUT_CHARS = 3_500
    const val ORPHEUS_MAX_INPUT_CHARS = 190

    fun maxInputChars(provider: VoiceProviderConfig, model: String): Int = when {
        model.contains("orpheus", true) -> ORPHEUS_MAX_INPUT_CHARS
        else -> MAX_INPUT_CHARS
    }

    /** Orpheus only returns wav; everything else defaults to mp3. */
    fun responseFormat(provider: VoiceProviderConfig, model: String): String = when {
        provider.protocol == VoiceProtocol.ELEVENLABS -> "mp3"
        model.contains("orpheus", true) -> "wav"
        else -> "mp3"
    }

    /** Documented voices for a model family; empty means the UI asks for a custom id. */
    fun voicesFor(provider: VoiceProviderConfig, model: String): List<String> = when (provider.protocol) {
        VoiceProtocol.OPENAI_AUDIO -> when (provider.id) {
            VoicePresets.GROQ -> when {
                model.contains("orpheus", true) -> listOf("autumn", "diana", "hannah", "austin", "daniel", "troy")
                model.contains("playai", true) && !model.contains("arabic", true) -> PLAYAI_VOICES
                else -> emptyList()
            }
            VoicePresets.OPENAI -> listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse")
            VoicePresets.EDGE -> VoicePresets.edgeVoices
            else -> emptyList()
        }
        VoiceProtocol.ELEVENLABS -> emptyList() // fetched from the account
        VoiceProtocol.CARTESIA -> CARTESIA_VOICES.keys.toList()
        VoiceProtocol.DEEPGRAM -> DEMO_VOICES
        VoiceProtocol.ASSEMBLYAI -> emptyList()
    }

    fun defaultVoiceFor(provider: VoiceProviderConfig, model: String): String? =
        voicesFor(provider, model).firstOrNull() ?: provider.ttsVoice.ifBlank { null }

    fun modelLabel(provider: VoiceProviderConfig, model: String): String = when {
        provider.id == VoicePresets.EDGE -> provider.name
        model.isBlank() -> provider.ttsModel.ifBlank { provider.name }
        model.contains("orpheus", true) && model.contains("arabic", true) -> "Orpheus Arabic"
        model.contains("orpheus", true) -> "Orpheus English"
        model == "playai-tts" -> "PlayAI TTS"
        model == "playai-tts-arabic" -> "PlayAI Arabic"
        else -> model
    }

    private fun safeError(error: Throwable, apiKey: String): Throwable {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) return error
        val message = error.message ?: return error
        if (!message.contains(trimmed)) return error
        return if (error is VoiceApiException) {
            VoiceApiException(message.replace(trimmed, "[redacted]"), error.code, error.actionUrl, error.status)
        } else {
            IllegalStateException(message.replace(trimmed, "[redacted]"))
        }
    }

    private suspend fun execute(client: OkHttpClient, request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response)
                    else response.close()
                }
            })
        }

    /**
     * Orpheus streams WAV and leaves the RIFF/data sizes at 0xFFFFFFFF. Android's
     * parser trusts those fields and refuses the file, so stamp the real sizes.
     */
    internal fun fixStreamingWav(bytes: ByteArray): ByteArray {
        if (bytes.size < 44) return bytes
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return bytes
        if (String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return bytes
        val out = bytes.copyOf()
        fun readU32(offset: Int): Long =
            (out[offset].toLong() and 0xFF) or
                ((out[offset + 1].toLong() and 0xFF) shl 8) or
                ((out[offset + 2].toLong() and 0xFF) shl 16) or
                ((out[offset + 3].toLong() and 0xFF) shl 24)
        fun writeU32(offset: Int, value: Long) {
            for (i in 0 until 4) out[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
        var offset = 12
        var dataOffset = -1
        while (offset + 8 <= out.size) {
            val id = String(out, offset, 4, Charsets.US_ASCII)
            val size = readU32(offset + 4)
            if (id == "data") {
                dataOffset = offset
                break
            }
            if (size == 0xFFFFFFFFL) break
            val next = offset + 8 + size
            if (next <= offset || next > out.size) break
            offset = next.toInt()
        }
        if (dataOffset < 0) return out
        val riffSize = out.size - 8L
        val dataSize = out.size - dataOffset - 8L
        if (readU32(4) != riffSize) writeU32(4, riffSize)
        if (readU32(dataOffset + 4) != dataSize) writeU32(dataOffset + 4, dataSize)
        return out
    }

    private fun trimCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= 200) return
        files.sortedBy { it.lastModified() }
            .take(files.size - 200)
            .forEach { runCatching { it.delete() } }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private val PLAYAI_VOICES = listOf(
        "Fritz-PlayAI", "Arista-PlayAI", "Atlas-PlayAI", "Celeste-PlayAI",
        "Cheyenne-PlayAI", "Chip-PlayAI", "Deedee-PlayAI", "Eleanor-PlayAI",
        "Faye-PlayAI", "Gail-PlayAI", "Indigo-PlayAI", "Mamaw-PlayAI",
        "Mason-PlayAI", "Quinn-PlayAI", "Ruby-PlayAI", "Thunder-PlayAI",
        "Tiffany-PlayAI",
    )

    /** Well-known Cartesia Sonic voice ids, offered as a shortcut. */
    private val CARTESIA_VOICES = mapOf(
        "a0e99841-438c-4a64-b679-ae501e7d6091" to "Barbershop Man",
        "b7d50908-b17c-442d-ad8d-810c63997ed9" to "California Girl",
        "694f9389-aac1-45b6-b726-9d9369183238" to "British Lady",
        "638efaaa-4d0c-442e-b701-3fae16aad012" to "Indian Lady",
    )

    /** Deepgram Aura demo voice ids. */
    private val DEMO_VOICES = listOf(
        "aura-2-thalia-en", "aura-2-orpheus-en", "aura-2-andromeda-en",
        "aura-asteria-en", "aura-luna-en", "aura-stella-en",
    )
}
