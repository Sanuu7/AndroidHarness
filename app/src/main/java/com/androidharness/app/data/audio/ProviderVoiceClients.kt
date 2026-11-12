package com.androidharness.app.data.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cartesia Sonic: POST /tts/bytes with a version header. Output only; the app
 * falls back to another provider for speech-to-text.
 */
object CartesiaClient {

    /** Cartesia pins api_version per release; a wrong one is rejected outright. */
    private const val API_VERSION = "2024-06-10"

    private val jsonMedia = "application/json; charset=utf-8".toMediaTypeOrNull()
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    /** The account's voices: GET /voices with the Cartesia key. */
    suspend fun voices(baseUrl: String, apiKey: String): Result<List<Pair<String, String>>> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("API key is missing"))
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/voices?limit=100")
                .header("X-API-Key", apiKey.trim())
                .header("Cartesia-Version", API_VERSION)
                .get()
                .build()
            try {
                execute(request).use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Result.failure(OpenAiAudioClient.apiError(body, response.code, "Cartesia"))
                    } else {
                        // {"data":[{"id":"...","name":"..."}, ...]}
                        val list = runCatching {
                            val root = json.parseToJsonElement(body).jsonObject
                            val arr = root["data"] as? kotlinx.serialization.json.JsonArray
                                ?: json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonArray
                                ?: return@runCatching emptyList()
                            arr.mapNotNull { item ->
                                val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                id to (obj["name"]?.jsonPrimitive?.content ?: id)
                            }
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

    suspend fun validateKey(baseUrl: String, apiKey: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("API key cannot be blank"))
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/voices?limit=1")
            .header("X-API-Key", apiKey.trim())
            .header("Cartesia-Version", API_VERSION)
            .get()
            .build()
        try {
            execute(request).use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(
                    OpenAiAudioClient.apiError(response.body?.string().orEmpty(), response.code, "Cartesia"),
                )
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
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("Cartesia API key is missing"))
        val voiceId = voice.ifBlank { provider.ttsVoice }
        if (voiceId.isBlank()) return Result.failure(IllegalStateException("Choose a Cartesia voice"))
        val chosenModel = model.ifBlank { provider.ttsModel.ifBlank { "sonic-2" } }
        val payload = buildJsonObject {
            put("model_id", chosenModel)
            put("transcript", text)
            put("voice", buildJsonObject { put("mode", "id"); put("id", voiceId) })
            put("output_format", buildJsonObject {
                put("container", "mp3")
                put("sample_rate", 44100)
                put("bit_rate", 128_000)
            })
            put("speed", speed.coerceIn(0.5f, 2f))
        }.toString()
        val request = Request.Builder()
            .url("${provider.resolvedBaseUrl()}/tts/bytes")
            .header("X-API-Key", apiKey.trim())
            .header("Cartesia-Version", API_VERSION)
            .post(payload.toRequestBody(jsonMedia))
            .build()
        return try {
            execute(request).use { response ->
                if (!response.isSuccessful) {
                    Result.failure(OpenAiAudioClient.apiError(response.body?.string().orEmpty(), response.code, "Cartesia"))
                } else {
                    val bytes = response.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) Result.failure(IOException("Cartesia returned no audio"))
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

/**
 * AssemblyAI is transcription-only: upload the audio, then poll the transcript
 * job. Both steps are required, so the client hides them behind one call.
 */
object AssemblyAiClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun validateKey(baseUrl: String, apiKey: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("API key cannot be blank"))
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v2/transcript?limit=1")
            .header("Authorization", apiKey.trim())
            .get()
            .build()
        try {
            execute(request).use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(
                    OpenAiAudioClient.apiError(response.body?.string().orEmpty(), response.code, "AssemblyAI"),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun transcribe(
        audioBytes: ByteArray,
        fileName: String,
        baseUrl: String,
        apiKey: String,
        model: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("AssemblyAI API key is missing"))
        val root = baseUrl.trimEnd('/')
        try {
            // 1. Upload, which returns a URL to hand to the transcript job.
            val upload = Request.Builder()
                .url("$root/v2/upload")
                .header("Authorization", apiKey.trim())
                .header("Content-Type", "application/octet-stream")
                .post(audioBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                .build()
            val uploadUrl = execute(upload).use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        OpenAiAudioClient.apiError(body, response.code, "AssemblyAI"),
                    )
                }
                runCatching {
                    json.parseToJsonElement(body).jsonObject["upload_url"]?.jsonPrimitive?.content
                }.getOrNull() ?: return@withContext Result.failure(
                    IllegalStateException("AssemblyAI did not return an upload URL"),
                )
            }

            // 2. Submit the transcription job.
            val payload = buildJsonObject {
                put("audio_url", uploadUrl)
                if (model.isNotBlank() && model != "universal") put("speech_model", model)
            }.toString()
            val jobId = execute(
                Request.Builder()
                    .url("$root/v2/transcript")
                    .header("Authorization", apiKey.trim())
                    .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build(),
            ).use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        OpenAiAudioClient.apiError(body, response.code, "AssemblyAI"),
                    )
                }
                runCatching {
                    json.parseToJsonElement(body).jsonObject["id"]?.jsonPrimitive?.content
                }.getOrNull() ?: return@withContext Result.failure(
                    IllegalStateException("AssemblyAI did not return a transcript id"),
                )
            }

            // 3. Poll until the job leaves queued/processing.
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(1_500)
                val body = execute(
                    Request.Builder()
                        .url("$root/v2/transcript/$jobId")
                        .header("Authorization", apiKey.trim())
                        .get()
                        .build(),
                ).use { it.body?.string().orEmpty() }
                val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@withContext Result.failure(IllegalStateException("AssemblyAI returned an invalid response"))
                when (obj["status"]?.jsonPrimitive?.content) {
                    "completed" -> {
                        val text = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                        return@withContext Result.success(text)
                    }
                    "error" -> return@withContext Result.failure(
                        IllegalStateException(
                            obj["error"]?.jsonPrimitive?.content ?: "AssemblyAI transcription failed",
                        ),
                    )
                }
            }
            Result.failure(IOException("AssemblyAI transcription timed out"))
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
