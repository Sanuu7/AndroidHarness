package com.androidharness.app.data.audio

import java.io.File

/**
 * Groq's speech API, kept as a thin alias over [OpenAiAudioClient] with the
 * Groq preset filled in. New code goes through [VoiceGateway]; this exists so
 * Groq's documented model/voice quirks stay in one readable place.
 */
object GroqTtsClient {
    val PRESET = VoicePresets.presets.first { it.id == VoicePresets.GROQ }

    /** Used until the account's model list is fetched. */
    const val DEFAULT_MODEL = "playai-tts"

    /** Groq has no voices endpoint, so these catalogs are the documented values per model family. */
    val ORPHEUS_VOICES = listOf("autumn", "diana", "hannah", "austin", "daniel", "troy")

    /** Voices offered for PlayAI English. */
    val VOICES = OpenAiAudioClient.voicesFor(PRESET, "playai-tts")

    /** PlayAI rejects longer input; Orpheus is much stricter (200 chars). */
    const val MAX_INPUT_CHARS = OpenAiAudioClient.MAX_INPUT_CHARS
    const val ORPHEUS_MAX_INPUT_CHARS = OpenAiAudioClient.ORPHEUS_MAX_INPUT_CHARS

    /** TTS models in GET /models carry one of these in their id. */
    private val SPEECH_MODEL = Regex("tts|orpheus|playai", RegexOption.IGNORE_CASE)

    /** Groq errors carry a machine code and, for model terms, a direct link. */
    internal fun apiError(body: String, status: Int) = OpenAiAudioClient.apiError(body, status, "Groq")

    suspend fun synthesizeCached(
        cacheDir: File,
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        speed: Float,
    ): Result<File> = OpenAiAudioClient.synthesizeCached(
        cacheDir, PRESET, apiKey, text, model, voice, speed,
    )

    suspend fun synthesize(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        speed: Float,
    ): Result<ByteArray> = OpenAiAudioClient.synthesize(PRESET, apiKey, text, model, voice, speed)

    /** Lists the account's speech models so new ones appear without an app update. */
    suspend fun fetchSpeechModels(apiKey: String): Result<List<String>> {
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("Groq API key is missing"))
        return OpenAiAudioClient.listModels(PRESET.resolvedBaseUrl(), apiKey)
            .map { ids -> ids.filter(::isSpeechModel).sorted() }
    }

    fun isSpeechModel(id: String): Boolean = SPEECH_MODEL.containsMatchIn(id)

    /** Documented voices for a model family; empty means the UI asks for a custom id. */
    fun voicesFor(model: String): List<String> = OpenAiAudioClient.voicesFor(PRESET, model)

    fun defaultVoiceFor(model: String): String? = OpenAiAudioClient.defaultVoiceFor(PRESET, model)

    fun maxInputChars(model: String): Int = OpenAiAudioClient.maxInputChars(PRESET, model)

    /** Orpheus only returns wav; PlayAI defaults to mp3. */
    fun responseFormat(model: String): String = OpenAiAudioClient.responseFormat(PRESET, model)

    fun modelLabel(model: String): String = OpenAiAudioClient.modelLabel(PRESET, model)

    /** Orpheus streams WAV with 0xFFFFFFFF sizes; Android refuses to play those. */
    fun fixStreamingWav(bytes: ByteArray): ByteArray = OpenAiAudioClient.fixStreamingWav(bytes)
}
