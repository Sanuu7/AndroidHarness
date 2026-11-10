package com.androidharness.app.data.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * Groq's Whisper endpoint, kept as a thin alias over [OpenAiAudioClient] with
 * the Groq preset. New code goes through [VoiceGateway].
 */
object GroqWhisperClient {

    private val PRESET = VoicePresets.presets.first { it.id == VoicePresets.GROQ }

    data class ValidationResult(val success: Boolean, val error: String? = null)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(
        audio: File,
        apiKey: String,
        model: String,
        client: OkHttpClient = httpClient,
    ): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) return@withContext Result.failure(IllegalStateException("Groq API key is missing"))
        if (!audio.exists() || audio.length() == 0L) {
            return@withContext Result.failure(IllegalArgumentException("Nothing to transcribe"))
        }
        try {
            OpenAiAudioClient.transcribe(
                audio = audio,
                baseUrl = PRESET.resolvedBaseUrl(),
                apiKey = trimmed,
                model = model.ifBlank { "whisper-large-v3" },
                client = client,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun validateApiKey(
        apiKey: String,
        client: OkHttpClient = httpClient,
    ): ValidationResult = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) return@withContext ValidationResult(false, "API key cannot be blank")
        OpenAiAudioClient.validateKey(PRESET.resolvedBaseUrl(), trimmed, client).fold(
            onSuccess = { ValidationResult(true) },
            onFailure = { ValidationResult(false, it.message) },
        )
    }
}
