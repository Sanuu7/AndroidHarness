package com.androidharness.app.data.audio

import kotlinx.serialization.Serializable

/**
 * Wire protocol a voice provider speaks. Voice endpoints are not part of the
 * chat provider abstraction ([com.androidharness.app.llm.ProviderType]), so
 * they get their own enum. OpenAI Audio is the de-facto standard: Groq,
 * OpenAI, Together, Fireworks and most local servers all speak it.
 */
@Serializable
enum class VoiceProtocol(val label: String) {
    /** /audio/transcriptions (multipart) and /audio/speech (JSON), like OpenAI. */
    OPENAI_AUDIO("OpenAI Audio"),
    ELEVENLABS("ElevenLabs"),
    DEEPGRAM("Deepgram"),
    CARTESIA("Cartesia"),
    ASSEMBLYAI("AssemblyAI"),

    /**
     * Microsoft Edge Read Aloud: a WebSocket session, not an HTTP endpoint, and
     * it needs no key. [EdgeTtsClient] speaks this protocol.
     */
    EDGE("Edge"),
}

/**
 * One configured voice provider. The same record can serve speech-to-text,
 * text-to-speech, or both; [supportsStt] / [supportsTts] say which.
 *
 * Keys are NOT stored here: they live in [com.androidharness.app.data.KeyStoreManager]
 * under a per-provider slot, so provider JSON can be exported in backups.
 */
@Serializable
data class VoiceProviderConfig(
    val id: String,
    val name: String,
    val protocol: VoiceProtocol,
    val baseUrl: String,
    /** Speech-to-text model id, e.g. "whisper-large-v3-turbo". */
    val sttModel: String = "",
    /** Text-to-speech model id, e.g. "playai-tts". */
    val ttsModel: String = "",
    /** Default voice id for [ttsModel]. */
    val ttsVoice: String = "",
    val supportsStt: Boolean = true,
    val supportsTts: Boolean = true,
    /** True only for the user-created endpoint; presets are managed by the app. */
    val custom: Boolean = false,
    /** Shown as "Get API key" next to the key field. */
    val keyUrl: String = "",
    /**
     * False for free endpoints that authenticate some other way (Edge TTS needs
     * no key at all). The UI hides the key card and requests go out without an
     * Authorization header.
     */
    val requiresKey: Boolean = true,
) {
    fun resolvedBaseUrl(): String = baseUrl.trim().trimEnd('/')
}

/**
 * Built-in providers shipped with the app. Users can edit every field and add
 * any number of custom endpoints on top of these.
 */
object VoicePresets {

    const val GROQ = "groq"
    const val OPENAI = "openai"
    const val ELEVENLABS = "elevenlabs"
    const val DEEPGRAM = "deepgram"
    const val CARTESIA = "cartesia"
    const val ASSEMBLYAI = "assemblyai"
    /** Microsoft Edge's free online reader, reached through an OpenAI-compatible router. */
    const val EDGE = "edge"

    const val KEY_URL_GROQ = "https://console.groq.com/keys"
    const val KEY_URL_OPENAI = "https://platform.openai.com/api-keys"
    const val KEY_URL_ELEVENLABS = "https://elevenlabs.io/app/settings/api-keys"
    const val KEY_URL_DEEPGRAM = "https://console.deepgram.com/"
    const val KEY_URL_CARTESIA = "https://play.cartesia.ai/console"
    const val KEY_URL_ASSEMBLYAI = "https://www.assemblyai.com/dashboard/api-keys"

    /**
     * Fallback voice list, used only when the live catalogue cannot be fetched.
     * These are real ShortNames from Microsoft's voices endpoint; the full list
     * carries 300+ entries across 140 locales, so this keeps the common ones.
     */
    val edgeVoices = listOf(
        "pt-BR-FranciscaNeural", "pt-BR-AntonioNeural", "pt-BR-ThalitaMultilingualNeural",
        "pt-PT-RaquelNeural", "pt-PT-DuarteNeural",
        "en-US-AriaNeural", "en-US-GuyNeural", "en-US-JennyNeural",
        "en-US-AvaNeural", "en-US-AndrewNeural", "en-US-EmmaNeural", "en-US-BrianNeural",
        "en-GB-SoniaNeural", "en-GB-RyanNeural",
        "es-ES-ElviraNeural", "es-ES-AlvaroNeural", "es-MX-DaliaNeural", "es-MX-JorgeNeural",
        "fr-FR-DeniseNeural", "fr-FR-HenriNeural",
        "de-DE-KatjaNeural", "de-DE-ConradNeural",
        "it-IT-ElsaNeural", "it-IT-DiegoNeural",
        "ja-JP-NanamiNeural", "ja-JP-KeitaNeural",
        "zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural",
    )

    /** Models offered when the provider's own model list cannot be fetched. */
    val groqSttModels = listOf("whisper-large-v3", "whisper-large-v3-turbo")
    val openAiSttModels = listOf("whisper-1", "gpt-4o-transcribe", "gpt-4o-mini-transcribe")
    val deepgramSttModels = listOf("nova-3", "nova-2", "enhanced", "base")
    val assemblyAiSttModels = listOf("universal", "slam-1")
    val elevenLabsSttModels = listOf("scribe_v1")

    val groqTtsModels = listOf("playai-tts", "playai-tts-arabic", "canopylabs/orpheus-v1-english")
    val openAiTtsModels = listOf("gpt-4o-mini-tts", "tts-1", "tts-1-hd")
    val cartesiaTtsModels = listOf("sonic-3", "sonic-2", "sonic-turbo")
    val deepgramTtsModels = listOf("aura-2-thalia-en", "aura-2-orpheus-en", "aura-asteria-en")
    val elevenLabsTtsModels = listOf("eleven_multilingual_v2", "eleven_turbo_v2_5", "eleven_flash_v2_5")

    val presets: List<VoiceProviderConfig> = listOf(
        VoiceProviderConfig(
            id = GROQ,
            name = "Groq",
            protocol = VoiceProtocol.OPENAI_AUDIO,
            baseUrl = "https://api.groq.com/openai/v1",
            sttModel = "whisper-large-v3",
            ttsModel = "playai-tts",
            ttsVoice = "Fritz-PlayAI",
            keyUrl = KEY_URL_GROQ,
        ),
        VoiceProviderConfig(
            id = EDGE,
            name = "Edge (free)",
            // Speaks the Read Aloud WebSocket protocol directly: no router, no key.
            protocol = VoiceProtocol.EDGE,
            baseUrl = EdgeTtsClient.VOICE_LIST_URL,
            sttModel = "",
            ttsModel = "",
            ttsVoice = "pt-BR-FranciscaNeural",
            supportsStt = false,
            requiresKey = false,
        ),
        VoiceProviderConfig(
            id = OPENAI,
            name = "OpenAI",
            protocol = VoiceProtocol.OPENAI_AUDIO,
            baseUrl = "https://api.openai.com/v1",
            sttModel = "whisper-1",
            ttsModel = "gpt-4o-mini-tts",
            ttsVoice = "alloy",
            keyUrl = KEY_URL_OPENAI,
        ),
        VoiceProviderConfig(
            id = ELEVENLABS,
            name = "ElevenLabs",
            protocol = VoiceProtocol.ELEVENLABS,
            baseUrl = "https://api.elevenlabs.io/v1",
            sttModel = "scribe_v1",
            ttsModel = "eleven_multilingual_v2",
            ttsVoice = "21m00Tcm4TlvDq8ikWAM",
            keyUrl = KEY_URL_ELEVENLABS,
        ),
        VoiceProviderConfig(
            id = DEEPGRAM,
            name = "Deepgram",
            protocol = VoiceProtocol.DEEPGRAM,
            baseUrl = "https://api.deepgram.com/v1",
            sttModel = "nova-3",
            ttsModel = "aura-2-thalia-en",
            ttsVoice = "aura-2-thalia-en",
            keyUrl = KEY_URL_DEEPGRAM,
        ),
        VoiceProviderConfig(
            id = CARTESIA,
            name = "Cartesia",
            protocol = VoiceProtocol.CARTESIA,
            baseUrl = "https://api.cartesia.ai",
            sttModel = "",
            ttsModel = "sonic-3",
            ttsVoice = "a0e99841-438c-4a64-b679-ae501e7d6091",
            supportsStt = false,
            keyUrl = KEY_URL_CARTESIA,
        ),
        VoiceProviderConfig(
            id = ASSEMBLYAI,
            name = "AssemblyAI",
            protocol = VoiceProtocol.ASSEMBLYAI,
            baseUrl = "https://api.assemblyai.com",
            sttModel = "universal",
            ttsModel = "",
            ttsVoice = "",
            supportsTts = false,
            keyUrl = KEY_URL_ASSEMBLYAI,
        ),
    )

    fun byId(id: String?): VoiceProviderConfig? =
        id?.let { wanted -> presets.firstOrNull { it.id == wanted } }

    /** Fallback model lists for a preset, keyed by [VoiceProviderConfig.id]. */
    fun sttModelsFor(provider: VoiceProviderConfig): List<String> = when (provider.id) {
        GROQ -> groqSttModels
        OPENAI -> openAiSttModels
        DEEPGRAM -> deepgramSttModels
        ASSEMBLYAI -> assemblyAiSttModels
        ELEVENLABS -> elevenLabsSttModels
        else -> emptyList()
    }

    fun ttsModelsFor(provider: VoiceProviderConfig): List<String> = when (provider.id) {
        GROQ -> groqTtsModels
        OPENAI -> openAiTtsModels
        CARTESIA -> cartesiaTtsModels
        DEEPGRAM -> deepgramTtsModels
        ELEVENLABS -> elevenLabsTtsModels
        // The router wants an empty model id here; the voice carries the choice.
        EDGE -> listOf("")
        else -> emptyList()
    }

    /** Voices a preset documents, for providers whose API cannot list them. */
    fun voicesFor(provider: VoiceProviderConfig): List<String> = when (provider.id) {
        EDGE -> edgeVoices
        else -> emptyList()
    }
}
