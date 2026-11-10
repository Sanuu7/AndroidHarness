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
 * Deepgram: /v1/listen transcribes, /v1/speak (Aura) synthesizes. Both take the
 * raw audio body with a Token auth header, not Bearer.
 */
object DeepgramVoiceClient {

    private val jsonMedia = "application/json; charset=utf-8".toMediaTypeOrNull()
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun validateKey(baseUrl: String, apiKey: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("API key cannot be blank"))
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/auth/token")
            .header("Authorization", "Token ${apiKey.trim()}")
            .get()
            .build()
        try {
            execute(request).use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(
                    OpenAiAudioClient.apiError(response.body?.string().orEmpty(), response.code, "Deepgram"),
                )
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
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("Deepgram API key is missing"))
        val chosen = model.ifBlank { "nova-3" }
        val url = "${baseUrl.trimEnd('/')}/listen?model=$chosen&smart_format=true&punctuate=true"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token ${apiKey.trim()}")
            .post(audio.asRequestBody("audio/mp4".toMediaTypeOrNull()))
            .build()
        try {
            execute(request).use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Result.failure(OpenAiAudioClient.apiError(body, response.code, "Deepgram"))
                } else {
                    // {"results":{"channels":[{"alternatives":[{"transcript":"…"}]}]}}
                    val transcript = runCatching {
                        json.parseToJsonElement(body).jsonObject["results"]?.jsonObject
                            ?.get("channels")?.jsonArray
                            ?.firstOrNull()?.jsonObject
                            ?.get("alternatives")?.jsonArray
                            ?.firstOrNull()?.jsonObject
                            ?.get("transcript")?.jsonPrimitive?.contentOrNull
                    }.getOrNull()?.trim().orEmpty()
                    Result.success(transcript)
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
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("Deepgram API key is missing"))
        val chosenModel = model.ifBlank { provider.ttsModel.ifBlank { voice } }.ifBlank { "aura-2-thalia-en" }
        val payload = buildJsonObject { put("text", text) }.toString()
        val request = Request.Builder()
            .url("${provider.resolvedBaseUrl()}/speak?model=$chosenModel")
            .header("Authorization", "Token ${apiKey.trim()}")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        return try {
            execute(request).use { response ->
                if (!response.isSuccessful) {
                    Result.failure(OpenAiAudioClient.apiError(response.body?.string().orEmpty(), response.code, "Deepgram"))
                } else {
                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) Result.failure(IOException("Deepgram returned no audio"))
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
