package com.androidharness.app.ui.chat

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.androidharness.app.AppContainer
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.audio.GroqWhisperClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class VoiceInputMode {
    INBUILT,
    GROQ_WHISPER,
}

enum class GroqRecordState {
    IDLE,
    HOLDING,
    LOCKED,
    TRANSCRIBING,
}

class VoiceInputController(
    private val context: Context,
    private val container: AppContainer,
    private val scope: CoroutineScope,
    /**
     * Transcription lambda, injectable for tests. Production leaves it null and
     * goes through [com.androidharness.app.data.audio.VoiceGateway], which picks
     * the client from the provider's protocol.
     */
    private val transcribe: (suspend (File, String, String) -> Result<String>)? = null,
) {
    // Inbuilt state
    var isListeningInbuilt by mutableStateOf(false)
        private set

    // Groq Whisper state
    var groqRecordState by mutableStateOf(GroqRecordState.IDLE)
        private set

    var rmsDb by mutableFloatStateOf(0f)
        private set

    var recordingDurationMs by mutableLongStateOf(0L)
        private set

    var levels by mutableStateOf<List<Float>>(emptyList())
        private set

    var cancelArmed by mutableStateOf(false)

    var errorMessage by mutableStateOf<String?>(null)
        private set

    val isListening: Boolean
        get() = isListeningInbuilt || groqRecordState == GroqRecordState.HOLDING || groqRecordState == GroqRecordState.LOCKED

    val isTranscribing: Boolean
        get() = groqRecordState == GroqRecordState.TRANSCRIBING

    private var recognizer: SpeechRecognizer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null
    private var amplitudeJob: Job? = null
    private var transcriptionJob: Job? = null
    private var nativeSilenceJob: Job? = null
    private var recordStartTime = 0L
    // Silence tracking (native + Groq). Speech = rmsDb > 8 dB (native) or amplitude > 0.03 (Groq).
    private var nativeHasSpoken = false
    private var nativeLastSpeechTime = 0L
    private var groqHasSpoken = false
    private var groqLastSpeechTime = 0L
    private var groqLastPeak = 0
    private var groqSilenceFired = false

    val isNativeAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    // -----------------------------------------------------------------------
    // Native Inbuilt SpeechRecognizer
    // -----------------------------------------------------------------------

    fun startNativeListening(onResult: (text: String, isFinal: Boolean) -> Unit, silenceTimeoutMs: Long? = null) {
        if (!isNativeAvailable) return
        stopNativeListening()
        errorMessage = null
        nativeHasSpoken = false
        nativeLastSpeechTime = SystemClock.elapsedRealtime()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // When the user stops talking for this long the recognizer
            // finishes on its own (auto-send for the inbuilt engine).
            silenceTimeoutMs
                ?.takeIf { it > 0 }
                ?.let { ms ->
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, ms)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, ms / 2)
                }
            // Same extras via the generic keys, in case the device only
            // honors those.
            silenceTimeoutMs
                ?.takeIf { it > 0 }
                ?.let { ms ->
                    putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", ms)
                    putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", ms / 2)
                }
        }

        try {
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListeningInbuilt = true
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    rmsDb = rmsdB
                    // SpeechRecognizer rms is dB (typically ~0 silence, >10 speech).
                    if (rmsdB > 8f) {
                        nativeHasSpoken = true
                        nativeLastSpeechTime = SystemClock.elapsedRealtime()
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListeningInbuilt = false
                }

                override fun onError(error: Int) {
                    isListeningInbuilt = false
                    cleanupNative()
                }

                override fun onResults(results: Bundle?) {
                    isListeningInbuilt = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrBlank()) {
                        onResult(text, true)
                    }
                    cleanupNative()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrBlank()) {
                        onResult(text, false)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            sr.startListening(intent)
            // Client-side silence fallback: many devices ignore the EXTRA_SILENCE*
            // intents, so the recognizer never finishes on its own. Watch rmsDb and
            // force stopListening() after the configured silence timeout.
            silenceTimeoutMs
                ?.takeIf { it > 0 }
                ?.let { timeout ->
                    nativeSilenceJob = scope.launch {
                        while (isActive && isListeningInbuilt) {
                            if (nativeHasSpoken && SystemClock.elapsedRealtime() - nativeLastSpeechTime >= timeout) {
                                runCatching { recognizer?.stopListening() }
                                break
                            }
                            delay(100)
                        }
                    }
                }
        } catch (e: Exception) {
            isListeningInbuilt = false
            cleanupNative()
        }
    }

    fun stopNativeListening() {
        isListeningInbuilt = false
        nativeSilenceJob?.cancel()
        nativeSilenceJob = null
        try {
            recognizer?.stopListening()
        } catch (_: Exception) { }
        cleanupNative()
    }

    private fun cleanupNative() {
        nativeSilenceJob?.cancel()
        nativeSilenceJob = null
        try {
            recognizer?.destroy()
        } catch (_: Exception) { }
        recognizer = null
        rmsDb = 0f
    }

    // -----------------------------------------------------------------------
    // Groq Whisper Recording & Transcription
    // -----------------------------------------------------------------------

    fun startGroqRecording(locked: Boolean = false, autoSilenceTimeoutMs: Long = 0L, onSilenceTimeout: (() -> Unit)? = null): Boolean {
        if (groqRecordState != GroqRecordState.IDLE) return false
        errorMessage = null
        cancelArmed = false
        levels = emptyList()
        groqHasSpoken = false
        groqSilenceFired = false
        groqLastPeak = 0
        groqLastSpeechTime = SystemClock.elapsedRealtime()

        val audioDir = File(context.cacheDir, "audio_recordings").apply { mkdirs() }
        val audioFile = File(audioDir, "rec_${System.currentTimeMillis()}.m4a")
        currentAudioFile = audioFile

        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            mediaRecorder = mr
            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            groqRecordState = if (locked) GroqRecordState.LOCKED else GroqRecordState.HOLDING
            recordStartTime = SystemClock.elapsedRealtime()
            recordingDurationMs = 0L

            amplitudeJob = scope.launch {
                while (isActive && (groqRecordState == GroqRecordState.HOLDING || groqRecordState == GroqRecordState.LOCKED)) {
                    val maxAmp = runCatching { mediaRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    // Normalize amplitude 0..32767 to 0f..1f. Speech floor ~0.03
                    // (some OEMs return cumulative peak and never drop — handled below).
                    val scaled = (maxAmp / 32767f).coerceIn(0f, 1f)
                    rmsDb = scaled
                    levels = (levels + scaled).takeLast(40)
                    recordingDurationMs = SystemClock.elapsedRealtime() - recordStartTime
                    // Silence detection for auto-send (locked hands-free).
                    if (autoSilenceTimeoutMs > 0 && onSilenceTimeout != null && groqRecordState == GroqRecordState.LOCKED && !groqSilenceFired) {
                        val now = SystemClock.elapsedRealtime()
                        // Speech: loud sample OR new peak (cumulative-device fallback).
                        val isSpeech = scaled > 0.03f
                        val isNewPeak = maxAmp > groqLastPeak
                        if (isSpeech || isNewPeak) {
                            groqLastSpeechTime = now
                            if (maxAmp > 983) groqHasSpoken = true
                        }
                        if (isNewPeak) groqLastPeak = maxAmp
                        if (groqHasSpoken && now - groqLastSpeechTime >= autoSilenceTimeoutMs) {
                            groqSilenceFired = true
                            withContext(Dispatchers.Main) { onSilenceTimeout() }
                        }
                    }
                    delay(60)
                }
            }
            return true
        } catch (e: Exception) {
            cleanupGroqRecorder()
            errorMessage = "Failed to start recording: ${e.message}"
            return false
        }
    }

    fun lockGroqRecording() {
        if (groqRecordState == GroqRecordState.HOLDING) {
            groqRecordState = GroqRecordState.LOCKED
            cancelArmed = false
        }
    }

    fun cancelGroqRecording() {
        transcriptionJob?.cancel()
        transcriptionJob = null
        cleanupGroqRecorder()
        groqRecordState = GroqRecordState.IDLE
        cancelArmed = false
    }

    fun stopAndTranscribeGroq(
        model: String,
        onTranscription: (text: String) -> Unit,
    ) = stopAndTranscribe(model, onTranscription)

    /**
     * Stops the recorder and sends the clip to the configured speech-to-text
     * provider. [providerId] is the id saved in settings; a blank id falls back
     * to the first configured provider that supports transcription.
     */
    fun stopAndTranscribe(
        model: String,
        onTranscription: (text: String) -> Unit,
        providerId: String? = null,
    ) {
        if (groqRecordState != GroqRecordState.HOLDING && groqRecordState != GroqRecordState.LOCKED) {
            return
        }

        val duration = SystemClock.elapsedRealtime() - recordStartTime
        val file = currentAudioFile

        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
            // Stopped too quickly before frames written
            cleanupGroqRecorder()
            groqRecordState = GroqRecordState.IDLE
            return
        }

        cleanupGroqRecorder(keepFile = true)

        if (file == null || !file.exists() || file.length() < 1024 || duration < 300) {
            file?.delete()
            groqRecordState = GroqRecordState.IDLE
            return
        }

        groqRecordState = GroqRecordState.TRANSCRIBING
        transcriptionJob = scope.launch {
            try {
                val all = container.voiceProviders.current()
                val provider = container.voiceProviders.resolve(all, providerId, forTts = false)
                if (provider == null) {
                    withContext(Dispatchers.Main) {
                        groqRecordState = GroqRecordState.IDLE
                        errorMessage = "No speech-to-text provider is configured in Settings"
                    }
                    return@launch
                }
                val apiKey = container.voiceProviders.apiKeyFor(provider.id).orEmpty()
                if (apiKey.isBlank()) {
                    withContext(Dispatchers.Main) {
                        groqRecordState = GroqRecordState.IDLE
                        errorMessage = "${provider.name} API key not configured in Settings"
                    }
                    return@launch
                }
                val selection = container.settings.settings.first().voiceSelections[provider.id]
                val chosenModel = model.ifBlank {
                    selection?.sttModel?.ifBlank { null } ?: provider.sttModel
                }
                val injected = transcribe
                val result = if (injected != null) {
                    injected(file, apiKey, chosenModel)
                } else {
                    com.androidharness.app.data.audio.VoiceGateway.transcribe(
                        provider = provider,
                        apiKey = apiKey,
                        audio = file,
                        model = chosenModel,
                    )
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main) {
                    groqRecordState = GroqRecordState.IDLE
                    result.onSuccess { text ->
                        if (text.isNotBlank()) onTranscription(text)
                    }.onFailure { err ->
                        errorMessage = err.message ?: "Transcription failed"
                    }
                }
            } finally {
                file.delete()
                if (currentAudioFile == file) currentAudioFile = null
            }
        }
    }

    private fun cleanupGroqRecorder(keepFile: Boolean = false) {
        amplitudeJob?.cancel()
        amplitudeJob = null
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) { }
        mediaRecorder = null
        rmsDb = 0f
        recordingDurationMs = 0L

        if (!keepFile) {
            currentAudioFile?.delete()
            currentAudioFile = null
        }
    }

    fun releaseAll() {
        stopNativeListening()
        cancelGroqRecording()
    }
}

@Composable
fun rememberVoiceInputController(
    container: AppContainer,
): VoiceInputController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context, container) {
        VoiceInputController(context, container, scope)
    }
    DisposableEffect(controller) {
        onDispose {
            controller.releaseAll()
        }
    }
    return controller
}
