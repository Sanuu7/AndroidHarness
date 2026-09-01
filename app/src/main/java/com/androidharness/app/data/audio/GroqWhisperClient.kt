package com.androidharness.app.data.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object GroqWhisperClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    data class ValidationResult(val success: Boolean, val error: String? = null)

    suspend fun validateApiKey(apiKey: String): ValidationResult = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) {
            return@withContext ValidationResult(false, "API key cannot be blank")
        }
        try {
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .header("Authorization", "Bearer $trimmed")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    ValidationResult(true)
                } else {
                    val body = resp.body?.string().orEmpty()
                    val msg = runCatching {
                        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    }.getOrNull() ?: "Verification failed (HTTP ${resp.code})"
                    ValidationResult(false, msg)
                }
            }
        } catch (e: Exception) {
            ValidationResult(false, e.message ?: "Failed to connect to Groq API")
        }
    }

    suspend fun transcribe(
        audioFile: File,
        apiKey: String,
        model: String = "whisper-large-v3",
    ): Result<String> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Groq API key is missing"))
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(IllegalArgumentException("Audio recording is empty"))
        }

        try {
            val fileBody = audioFile.asRequestBody("audio/m4a".toMediaTypeOrNull())
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, fileBody)
                .addFormDataPart("model", model)
                .addFormDataPart("temperature", "0")
                .addFormDataPart("response_format", "json")
                .build()

            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/transcriptions")
                .header("Authorization", "Bearer $trimmedKey")
                .post(requestBody)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val err = runCatching {
                        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    }.getOrNull() ?: "Groq transcription failed (HTTP ${resp.code})"
                    return@withContext Result.failure(RuntimeException(err))
                }

                val text = runCatching {
                    json.parseToJsonElement(body).jsonObject["text"]?.jsonPrimitive?.content
                }.getOrNull()?.trim().orEmpty()

                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
