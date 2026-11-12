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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ElevenLabs: /v1/text-to-speech/{voice_id} for output and /v1/speech-to-text
 * (Scribe) for input. Not OpenAI-compatible, so it gets its own thin client.
 */
object ElevenLabsClient {

    private val jsonMedia = "application/json; charset=utf-8".toMediaTypeOrNull()
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    data class VoiceEntry(val id: String, val name: String)

    suspend fun validateKey(baseUrl: String, apiKey: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("API key cannot be blank"))
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/user/subscription")
                .header("xi-api-key", apiKey.trim())
                .get()
                .build()
            try {
                execute(request).use { response ->
                    if (response.isSuccessful) Result.success(Unit)
                    else Result.failure(
                        OpenAiAudioClient.apiError(response.body?.string().orEmpty(), response.code, "ElevenLabs"),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Text-to-speech models the account can use. GET /v1/models with the key. */
    suspend fun models(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("API key is missing"))
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/models")
                .header("xi-api-key", apiKey.trim())
                .get()
                .build()
            try {
                execute(request).use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Result.failure(OpenAiAudioClient.apiError(body, response.code, "ElevenLabs"))
                    } else {
                        // An array, or an object with a "models" array.
                        val ids = runCatching {
                            val element = json.parseToJsonElement(body)
                            val array = when (element) {
                                is kotlinx.serialization.json.JsonArray -> element
                                is kotlinx.serialization.json.JsonObject ->
                                    element["models"] as? kotlinx.serialization.json.JsonArray
                                else -> null
                            }
                            array?.mapNotNull { item ->
                                val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                                obj["model_id"]?.jsonPrimitive?.contentOrNull
                                    ?: obj["id"]?.jsonPrimitive?.contentOrNull
                            }.orEmpty()
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

    /** The account's own voices plus the shared "premade" library. */
    suspend fun voices(baseUrl: String, apiKey: String): Result<List<VoiceEntry>> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("API key is missing"))
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/voices")
                .header("xi-api-key", apiKey.trim())
                .get()
                .build()
            try {
                execute(request).use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Result.failure(OpenAiAudioClient.apiError(body, response.code, "ElevenLabs"))
                    } else {
                        val list = runCatching {
                            json.parseToJsonElement(body).jsonObject["voices"]?.jsonArray
                                ?.mapNotNull { element ->
                                    val obj = element.jsonObject
                                    val id = obj["voice_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                                    VoiceEntry(id, obj["name"]?.jsonPrimitive?.contentOrNull ?: id)
                                }
                                .orEmpty()
                        }.getOrDefault(emptyList())
                        Result.success(list)
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
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("ElevenLabs API key is missing"))
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody("audio/mp4".toMediaTypeOrNull()))
            .addFormDataPart("model_id", model.ifBlank { "scribe_v1" })
            .build()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/speech-to-text")
            .header("xi-api-key", apiKey.trim())
            .post(body)
            .build()
        try {
            execute(request).use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Result.failure(OpenAiAudioClient.apiError(raw, response.code, "ElevenLabs"))
                } else {
                    val text = runCatching {
                        json.parseToJsonElement(raw).jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()?.trim()
                    if (text == null) Result.failure(IllegalStateException("ElevenLabs returned an invalid transcription."))
                    else Result.success(text)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal suspend fun synthesize(
        provider: VoiceProviderConfig,
        apiKey: String,
        text: String,
        model: String,
        voice: String,
        speed: Float,
    ): Result<ByteArray> {
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("ElevenLabs API key is missing"))
        val voiceId = voice.ifBlank { provider.ttsVoice }
        if (voiceId.isBlank()) return Result.failure(IllegalStateException("Choose an ElevenLabs voice"))
        val payload = buildJsonObject {
            put("text", text)
            put("model_id", model.ifBlank { provider.ttsModel.ifBlank { "eleven_multilingual_v2" } })
            put("output_format", "mp3_44100_128")
        }.toString()
        val url = "${provider.resolvedBaseUrl()}/text-to-speech/$voiceId" +
            "?optimize_streaming_latency=2&output_format=mp3_44100_128"
        val request = Request.Builder()
            .url(url)
            .header("xi-api-key", apiKey.trim())
            .post(payload.toRequestBody(jsonMedia))
            .build()
        return try {
            execute(request).use { response ->
                if (!response.isSuccessful) {
                    Result.failure(OpenAiAudioClient.apiError(response.body?.string().orEmpty(), response.code, "ElevenLabs"))
                } else {
                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) Result.failure(IOException("ElevenLabs returned no audio"))
                    else Result.success(bytes)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
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
}
