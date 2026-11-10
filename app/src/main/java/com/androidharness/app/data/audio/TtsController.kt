package com.androidharness.app.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.annotation.StringRes
import com.androidharness.app.R
import com.androidharness.app.core.response.RichResponse
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.KeyStoreManager
import com.androidharness.app.data.SettingsRepository
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

enum class TtsPhase { IDLE, PREPARING, SPEAKING }

/** Which message is being read, so only its button shows a stop state. */
data class TtsState(
    val messageId: String? = null,
    val phase: TtsPhase = TtsPhase.IDLE,
)

/** One selectable Android TTS voice. */
data class TtsVoiceOption(val id: String, val label: String)

/** A failure worth showing. Terms errors carry the console link Groq returned. */
data class TtsError(
    val message: String,
    val termsRequired: Boolean = false,
    val actionUrl: String? = null,
)

/**
 * Owns both output engines and the single media player, so starting a new
 * reply always stops the previous one. The UI only calls [toggle]/[autoSpeak]
 * and renders [state]; it never touches TextToSpeech or MediaPlayer.
 */
class TtsController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val keys: KeyStoreManager,
    private val voices: VoiceProviderRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<TtsError>(extraBufferCapacity = 4)
    val errors: SharedFlow<TtsError> = _errors.asSharedFlow()

    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    private var systemTts: TextToSpeech? = null
    private var systemInit: CompletableDeferred<Boolean>? = null
    private var playbackGeneration = 0
    private var pendingUtterances = 0

    /** Auto-speak fires once per message even across rotations and recompositions. */
    private val autoSpoken = LinkedHashSet<String>()
    private val autoQueue = ArrayDeque<Pair<String, String>>()

    fun toggle(messageId: String, text: String) {
        val current = _state.value
        autoQueue.clear()
        if (current.messageId == messageId && current.phase != TtsPhase.IDLE) stop()
        else speak(messageId, text)
    }

    /**
     * Queues one assistant text block for automatic reading. Blocks arrive
     * while earlier audio is still playing, so they wait in order instead of
     * interrupting each other.
     */
    fun enqueueAuto(messageId: String, text: String) {
        if (text.isBlank() || !autoSpoken.add(messageId)) return
        if (autoSpoken.size > 500) autoSpoken.remove(autoSpoken.first())
        autoQueue.addLast(messageId to text)
        if (_state.value.phase == TtsPhase.IDLE) playNextAuto()
    }

    private fun playNextAuto() {
        while (true) {
            val next = autoQueue.removeFirstOrNull() ?: return
            // Rich-only blocks can reduce to nothing to say; skip them so the
            // queue keeps moving.
            if (!SpeechText.isSpeakable(SpeechText.forSpeech(RichResponse.plainText(next.second)))) continue
            speak(next.first, next.second)
            return
        }
    }

    /** Speaks a sample with the currently saved engine/voice/speed. */
    fun preview() {
        autoQueue.clear()
        speak(PREVIEW_ID, context.getString(R.string.settings_tts_test_sentence))
    }

    fun stop() {
        autoQueue.clear()
        halt()
    }

    private fun halt() {
        job?.cancel()
        job = null
        playbackGeneration++
        pendingUtterances = 0
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        runCatching { systemTts?.stop() }
        abandonFocus()
        _state.value = TtsState()
    }

    fun release() {
        stop()
        runCatching { systemTts?.shutdown() }
        systemTts = null
        systemInit = null
        scope.cancel()
    }

    /** TTS models the selected provider can use; empty when it has no key. */
    suspend fun speechModels(providerId: String?): List<String> {
        val provider = voices.byId(providerId) ?: return emptyList()
        val key = keys.voiceApiKey(provider.id).orEmpty()
        return VoiceGateway.ttsModels(provider, key)
    }

    /** Voices of the configured provider, or of the installed engine for "system". */
    suspend fun providerVoices(providerId: String?, model: String): List<TtsVoiceOption> {
        val provider = voices.byId(providerId) ?: return emptyList()
        if (provider.protocol == VoiceProtocol.ELEVENLABS) {
            val key = keys.voiceApiKey(provider.id).orEmpty()
            val remote = ElevenLabsClient.voices(provider.resolvedBaseUrl(), key).getOrNull()
            if (!remote.isNullOrEmpty()) return remote.map { TtsVoiceOption(it.id, it.name) }
        }
        return VoiceGateway.voicesFor(provider, model).map { TtsVoiceOption(it, it) }
    }

    /** Voices of the installed engine that fit the app's language. */
    suspend fun systemVoices(): List<TtsVoiceOption> {
        val tts = ensureSystemTts() ?: return emptyList()
        val locale = appLocale()
        return tts.voices.orEmpty()
            .filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language }
            .sortedWith(
                compareByDescending<Voice> { it.locale.country == locale.country }
                    .thenByDescending { it.quality }
                    .thenBy { it.name },
            )
            .map { TtsVoiceOption(it.name, "${it.name.replace('_', ' ')} · ${it.locale.displayName}") }
    }

    private fun speak(messageId: String, text: String) {
        val plain = SpeechText.forSpeech(RichResponse.plainText(text))
        if (!SpeechText.isSpeakable(plain)) return
        halt()
        _state.value = TtsState(messageId, TtsPhase.PREPARING)
        job = scope.launch {
            val saved = settings.settings.first()
            try {
                val provider = if (saved.ttsEngine == AppSettings.TTS_ENGINE_SYSTEM) null else voices.byId(saved.ttsEngine)
                if (saved.ttsEngine != AppSettings.TTS_ENGINE_SYSTEM && provider == null) {
                    report(R.string.tts_error_unavailable)
                } else if (provider != null) {
                    speakProvider(messageId, plain, saved, provider)
                } else {
                    speakSystem(messageId, plain, saved)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError(e)
            }
        }
    }

    private suspend fun speakSystem(messageId: String, text: String, saved: AppSettings): Boolean {
        val tts = ensureSystemTts() ?: return report(R.string.tts_error_unavailable)
        applySystemVoice(tts, saved)
        val limit = TextToSpeech.getMaxSpeechInputLength().coerceAtMost(OpenAiAudioClient.MAX_INPUT_CHARS)
        val chunks = TtsTextChunker.chunk(text, limit)
        if (chunks.isEmpty()) {
            finish()
            return false
        }

        val generation = ++playbackGeneration
        pendingUtterances = chunks.size
        _state.value = TtsState(messageId, TtsPhase.SPEAKING)
        requestFocus()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = completeUtterance(generation)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = failUtterance(generation)
            override fun onError(utteranceId: String?, errorCode: Int) = failUtterance(generation)
        })
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (tts.speak(chunk, mode, null, "$generation-$index") == TextToSpeech.ERROR) {
                return report(R.string.tts_error_playback)
            }
        }
        return true
    }

    private suspend fun speakProvider(
        messageId: String,
        text: String,
        saved: AppSettings,
        provider: VoiceProviderConfig,
    ): Boolean = coroutineScope {
        val apiKey = keys.voiceApiKey(provider.id).orEmpty()
        if (apiKey.isBlank()) return@coroutineScope report(R.string.tts_error_no_key)
        val selection = saved.voiceSelections[provider.id]
        val model = selection?.ttsModel?.ifBlank { null } ?: provider.ttsModel
        val voice = selection?.ttsVoice?.ifBlank { null }
            ?: VoiceGateway.defaultVoiceFor(provider, model)
            ?: provider.ttsVoice
        val chunks = TtsTextChunker.chunk(text, VoiceGateway.maxInputChars(provider, model))
        if (chunks.isEmpty()) {
            finish()
            return@coroutineScope false
        }
        requestFocus()
        // Some vendors cap a request at ~200 characters (Orpheus), so a reply is
        // several clips. Keep two requests in flight: the next clip is generated
        // while the current one plays, instead of leaving a silent gap.
        val permits = Semaphore(2)
        val audio = chunks.map { chunk ->
            async(Dispatchers.IO) {
                permits.withPermit {
                    VoiceGateway.synthesizeCached(
                        context.cacheDir, provider, apiKey, chunk, model, voice, saved.ttsSpeed,
                    )
                }
            }
        }
        var failure: Throwable? = null
        var started = false
        audio.forEachIndexed { _, deferred ->
            val result = deferred.await()
            val file = result.getOrElse { error ->
                // A single clip a provider refuses must not mute the rest of the
                // reply; credential and terms failures still stop at once.
                if (error.stopsPlayback()) return@coroutineScope reportError(error)
                android.util.Log.w("HarnessTts", "Skipping speech clip: ${error.message}")
                if (failure == null) failure = error
                return@forEachIndexed
            }
            if (!started) {
                _state.value = TtsState(messageId, TtsPhase.SPEAKING)
                started = true
            }
            if (!play(file)) return@coroutineScope report(R.string.tts_error_playback)
        }
        if (!started) {
            failure?.let { return@coroutineScope reportError(it) }
            finish()
            return@coroutineScope false
        }
        finish()
        true
    }

    /** Missing credentials or unaccepted terms make every clip fail. */
    private fun Throwable.stopsPlayback(): Boolean {
        val api = this as? OpenAiAudioClient.VoiceApiException ?: return false
        return api.code == "model_terms_required" || api.status == 401 || api.status == 403
    }

    private fun completeUtterance(generation: Int) {
        scope.launch {
            if (generation != playbackGeneration) return@launch
            pendingUtterances -= 1
            if (pendingUtterances <= 0) finish()
        }
    }

    private fun failUtterance(generation: Int) {
        // Engine callbacks can arrive off the main thread; state and player
        // teardown stay on the controller's dispatcher.
        if (generation != playbackGeneration) return
        scope.launch {
            if (generation == playbackGeneration) report(R.string.tts_error_playback)
        }
    }

    private fun finish() {
        playbackGeneration++
        _state.value = TtsState()
        abandonFocus()
        if (autoQueue.isNotEmpty()) scope.launch { playNextAuto() }
    }

    private suspend fun play(file: File): Boolean = suspendCancellableCoroutine { continuation ->
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { done ->
                if (player === done) player = null
                runCatching { done.release() }
                if (continuation.isActive) continuation.resume(true)
            }
            mp.setOnErrorListener { _, what, extra ->
                android.util.Log.w("HarnessTts", "MediaPlayer error what=$what extra=$extra file=${file.name} bytes=${file.length()}")
                if (player === mp) player = null
                runCatching { mp.release() }
                if (continuation.isActive) continuation.resume(false)
                true
            }
            continuation.invokeOnCancellation {
                runCatching { mp.stop() }
                runCatching { mp.release() }
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            runCatching { mp.release() }
            if (continuation.isActive) continuation.resume(false)
        }
    }

    private suspend fun ensureSystemTts(): TextToSpeech? {
        val existing = systemTts
        if (existing != null) {
            val ready = systemInit?.let { withTimeoutOrNull(10_000) { it.await() } } ?: false
            return existing.takeIf { ready }
        }
        val init = CompletableDeferred<Boolean>()
        systemInit = init
        systemTts = TextToSpeech(context) { status -> init.complete(status == TextToSpeech.SUCCESS) }
        val ready = withTimeoutOrNull(10_000) { init.await() } ?: false
        return systemTts?.takeIf { ready }
    }

    private fun applySystemVoice(tts: TextToSpeech, saved: AppSettings) {
        val selected = saved.ttsSystemVoice
            ?.let { name -> tts.voices?.firstOrNull { it.name == name } }
        if (selected != null) {
            runCatching { tts.voice = selected }
        } else {
            val locale = appLocale()
            val result = tts.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
        }
        tts.setSpeechRate(saved.ttsSpeed.coerceIn(0.5f, 2f))
    }

    private fun appLocale(): Locale {
        val configuration = context.resources.configuration
        return configuration.locales.takeIf { it.size() > 0 }?.get(0) ?: Locale.getDefault()
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
            }
            .build()
        focusRequest = request
        runCatching { audioManager?.requestAudioFocus(request) }
    }

    private fun abandonFocus() {
        focusRequest?.let { runCatching { audioManager?.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private fun report(@StringRes message: Int): Boolean =
        reportError(TtsError(context.getString(message)))

    private fun reportError(error: Throwable): Boolean {
        // Keep the provider message in logcat; the toast truncates it.
        android.util.Log.w("HarnessTts", "Speech failed: ${error.message}", error)
        val message = error.message ?: context.getString(R.string.tts_error_groq)
        val api = error as? OpenAiAudioClient.VoiceApiException
        val terms = api != null && (
            api.code == "model_terms_required" ||
                message.contains("terms acceptance", ignoreCase = true) ||
                message.contains("accept the terms", ignoreCase = true)
            )
        return reportError(
            TtsError(
                message = message,
                termsRequired = terms,
                actionUrl = api?.actionUrl,
            ),
        )
    }

    private fun reportError(error: TtsError): Boolean {
        _errors.tryEmit(error)
        stop()
        return false
    }

    companion object {
        const val PREVIEW_ID = "__tts_preview__"
    }
}
