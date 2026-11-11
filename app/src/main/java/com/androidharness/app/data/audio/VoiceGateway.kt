package com.androidharness.app.data.audio

import java.io.File

/**
 * One entry point the controllers call, so speech-to-text and text-to-speech
 * never need to know which vendor is configured. Every member takes the
 * resolved [VoiceProviderConfig] plus its key.
 */
object VoiceGateway {

    suspend fun validateKey(provider: VoiceProviderConfig, apiKey: String): Result<Unit> =
        when (provider.protocol) {
            VoiceProtocol.OPENAI_AUDIO -> OpenAiAudioClient.validateKey(provider.resolvedBaseUrl(), apiKey)
            VoiceProtocol.ELEVENLABS -> ElevenLabsClient.validateKey(provider.resolvedBaseUrl(), apiKey)
            VoiceProtocol.DEEPGRAM -> DeepgramVoiceClient.validateKey(provider.resolvedBaseUrl(), apiKey)
            VoiceProtocol.CARTESIA -> CartesiaClient.validateKey(provider.resolvedBaseUrl(), apiKey)
            VoiceProtocol.ASSEMBLYAI -> AssemblyAiClient.validateKey(provider.resolvedBaseUrl(), apiKey)
            // Edge needs no credential, so there is nothing to validate.
            VoiceProtocol.EDGE -> Result.success(Unit)
        }

    /** Model ids the provider can serve, or the shipped preset list as a fallback. */
    suspend fun sttModels(provider: VoiceProviderConfig, apiKey: String): List<String> {
        val preset = VoicePresets.sttModelsFor(provider)
        return when (provider.protocol) {
            VoiceProtocol.OPENAI_AUDIO -> {
                val remote = runCatching {
                    if (apiKey.isBlank() && provider.requiresKey) emptyList()
                    else OpenAiAudioClient.listModels(provider.resolvedBaseUrl(), apiKey).getOrDefault(emptyList())
                }.getOrDefault(emptyList())
                (remote.filter { VOICE_STT_HINT.containsMatchIn(it) } + preset).distinct()
            }
            // Deepgram reports both directions from one endpoint.
            VoiceProtocol.DEEPGRAM -> {
                val remote = runCatching {
                    if (apiKey.isBlank()) emptyList()
                    else DeepgramVoiceClient.models(provider.resolvedBaseUrl(), apiKey).getOrDefault(emptyList())
                }.getOrDefault(emptyList())
                (remote.filter { VOICE_STT_HINT.containsMatchIn(it) } + preset).distinct()
            }
            else -> preset
        }
    }

    suspend fun ttsModels(provider: VoiceProviderConfig, apiKey: String): List<String> {
        val preset = VoicePresets.ttsModelsFor(provider)
        return when (provider.protocol) {
            VoiceProtocol.OPENAI_AUDIO -> {
                val remote = runCatching {
                    if (apiKey.isBlank() && provider.requiresKey) emptyList()
                    else OpenAiAudioClient.listModels(provider.resolvedBaseUrl(), apiKey).getOrDefault(emptyList())
                }.getOrDefault(emptyList())
                (remote.filter { TTS_HINT.containsMatchIn(it) } + preset).distinct()
            }
            VoiceProtocol.DEEPGRAM -> {
                val remote = runCatching {
                    if (apiKey.isBlank()) emptyList()
                    else DeepgramVoiceClient.models(provider.resolvedBaseUrl(), apiKey).getOrDefault(emptyList())
                }.getOrDefault(emptyList())
                (remote.filter { TTS_HINT.containsMatchIn(it) } + preset).distinct()
            }
            VoiceProtocol.ELEVENLABS -> {
                val remote = runCatching {
                    if (apiKey.isBlank()) emptyList()
                    else ElevenLabsClient.models(provider.resolvedBaseUrl(), apiKey).getOrDefault(emptyList())
                }.getOrDefault(emptyList())
                (remote + preset).distinct()
            }
            else -> preset
        }
    }

    suspend fun transcribe(
        provider: VoiceProviderConfig,
        apiKey: String,
        audio: File,
        model: String,
    ): Result<String> = when (provider.protocol) {
        VoiceProtocol.OPENAI_AUDIO ->
            OpenAiAudioClient.transcribe(audio, provider.resolvedBaseUrl(), apiKey, model)
        VoiceProtocol.ELEVENLABS ->
            ElevenLabsClient.transcribe(audio, provider.resolvedBaseUrl(), apiKey, model)
        VoiceProtocol.DEEPGRAM ->
            DeepgramVoiceClient.transcribe(audio, provider.resolvedBaseUrl(), apiKey, model)
        VoiceProtocol.ASSEMBLYAI -> {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { audio.readBytes() }
            AssemblyAiClient.transcribe(bytes, audio.name, provider.resolvedBaseUrl(), apiKey, model)
        }
        VoiceProtocol.CARTESIA ->
            Result.failure(UnsupportedOperationException("Cartesia does not offer speech to text"))
        VoiceProtocol.EDGE ->
            Result.failure(UnsupportedOperationException("Edge does not offer speech to text"))
    }

    suspend fun synthesizeCached(
        cacheDir: File,
        provider: VoiceProviderConfig,
        apiKey: String,
        text: String,
        model: String,
        voice: String,
        speed: Float,
        onEdgeForbidden: (suspend () -> Unit)? = null,
    ): Result<File> = OpenAiAudioClient.synthesizeCached(
        cacheDir = cacheDir,
        provider = provider,
        apiKey = apiKey,
        text = text,
        model = model,
        voice = voice,
        speed = speed,
        onEdgeForbidden = onEdgeForbidden,
    )

    fun voicesFor(provider: VoiceProviderConfig, model: String): List<String> =
        OpenAiAudioClient.voicesFor(provider, model).ifEmpty { VoicePresets.voicesFor(provider) }

    fun defaultVoiceFor(provider: VoiceProviderConfig, model: String): String? =
        OpenAiAudioClient.defaultVoiceFor(provider, model)

    fun maxInputChars(provider: VoiceProviderConfig, model: String): Int =
        OpenAiAudioClient.maxInputChars(provider, model)

    fun modelLabel(provider: VoiceProviderConfig, model: String): String =
        OpenAiAudioClient.modelLabel(provider, model)

    /** Model ids that look like speech endpoints in a generic /models list. */
    private val TTS_HINT = Regex("tts|orpheus|playai|speech|audio|voice|sonic|aura", RegexOption.IGNORE_CASE)

    /** Model ids that look like transcription endpoints. */
    private val VOICE_STT_HINT = Regex("whisper|stt|transcribe|transcription|scribe|listen|nova|universal", RegexOption.IGNORE_CASE)
}
