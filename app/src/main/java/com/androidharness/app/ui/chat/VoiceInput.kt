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
    private var recordStartTime = 0L

    val isNativeAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    // -----------------------------------------------------------------------
    // Native Inbuilt SpeechRecognizer
    // -----------------------------------------------------------------------

    fun startNativeListening(onResult: (text: String, isFinal: Boolean) -> Unit) {
        if (!isNativeAvailable) return
        stopNativeListening()
        errorMessage = null

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
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
        } catch (e: Exception) {
            isListeningInbuilt = false
            cleanupNative()
        }
    }

    fun stopNativeListening() {
        isListeningInbuilt = false
        try {
            recognizer?.stopListening()
        } catch (_: Exception) { }
        cleanupNative()
    }

    private fun cleanupNative() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) { }
        recognizer = null
        rmsDb = 0f
    }

    // -----------------------------------------------------------------------
    // Groq Whisper Recording & Transcription
    // -----------------------------------------------------------------------

    fun startGroqRecording(locked: Boolean = false): Boolean {
        if (groqRecordState != GroqRecordState.IDLE) return false
        errorMessage = null
        cancelArmed = false
        levels = emptyList()

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
            mediaRecorder = mr
            groqRecordState = if (locked) GroqRecordState.LOCKED else GroqRecordState.HOLDING
            recordStartTime = SystemClock.elapsedRealtime()
            recordingDurationMs = 0L

            amplitudeJob = scope.launch {
                while (isActive && (groqRecordState == GroqRecordState.HOLDING || groqRecordState == GroqRecordState.LOCKED)) {
                    val maxAmp = runCatching { mediaRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    // Normalize amplitude 0..32767 to 0f..1f
                    val scaled = (maxAmp / 32767f).coerceIn(0f, 1f)
                    rmsDb = scaled
                    levels = (levels + scaled).takeLast(40)
                    recordingDurationMs = SystemClock.elapsedRealtime() - recordStartTime
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
        cleanupGroqRecorder()
        groqRecordState = GroqRecordState.IDLE
        cancelArmed = false
    }

    fun stopAndTranscribeGroq(
        model: String,
        onTranscription: (text: String) -> Unit,
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

        val apiKey = container.keys.groqApiKey()
        if (apiKey.isNullOrBlank()) {
            file.delete()
            errorMessage = "Groq API key not configured in Settings"
            groqRecordState = GroqRecordState.IDLE
            return
        }

        groqRecordState = GroqRecordState.TRANSCRIBING
        scope.launch {
            val result = GroqWhisperClient.transcribe(
                audioFile = file,
                apiKey = apiKey,
                model = model,
            )
            withContext(Dispatchers.Main) {
                file.delete()
                groqRecordState = GroqRecordState.IDLE
                result.onSuccess { text ->
                    if (text.isNotBlank()) {
                        onTranscription(text)
                    }
                }.onFailure { err ->
                    errorMessage = err.message ?: "Transcription failed"
                }
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
