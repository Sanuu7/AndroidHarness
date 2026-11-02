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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.androidharness.app.R
import com.androidharness.app.data.audio.GroqWhisperClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class LiveHearing {
    /** Microphone closed: paused, thinking or speaking. */
    OFF,
    /** Open and waiting for speech. */
    WAITING,
    /** Speech is coming in. */
    HEARING,
    /** Groq is turning the finished utterance into text. */
    TRANSCRIBING,
}

/**
 * Keeps the microphone open between utterances so Live Mode needs no taps.
 * Each finished utterance goes to [onUtterance] with the microphone already
 * closed; the session reopens it with [resume] once the reply has been spoken.
 *
 * The system recognizer ends a session on every pause or silence timeout, so
 * it is restarted in a loop. Groq Whisper has no endpointing of its own:
 * speech is recorded and cut after [silenceMs] of quiet, measured against a
 * noise floor that follows the room.
 */
class LiveListener(
    private val context: Context,
    private val scope: CoroutineScope,
    private val groqKey: () -> String?,
    private val transcribe: suspend (File, String, String) -> Result<String> = { file, key, model ->
        GroqWhisperClient.transcribe(file, key, model)
    },
) {
    var hearing by mutableStateOf(LiveHearing.OFF)
        private set
    var partial by mutableStateOf("")
        private set
    /** Input level, 0..1, for the visualizer. */
    var level by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var groq = false
    private var whisperModel = ""
    private var silenceMs = 1_200
    private var onUtterance: (String) -> Unit = {}

    /** Bumped whenever the microphone closes, so late callbacks from an old session are ignored. */
    private var generation = 0
    private var recognizer: SpeechRecognizer? = null
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var loop: Job? = null
    private var networkFailures = 0

    fun start(useGroq: Boolean, model: String, silence: Int, onUtterance: (String) -> Unit) {
        groq = useGroq
        whisperModel = model
        silenceMs = silence
        this.onUtterance = onUtterance
        resume()
    }

    fun resume() {
        close()
        error = null
        networkFailures = 0
        if (groq) openRecorder() else openRecognizer()
    }

    fun pause() = close()

    fun release() {
        close()
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun close() {
        generation++
        loop?.cancel()
        loop = null
        runCatching { recognizer?.cancel() }
        stopRecorder(keepFile = false)
        hearing = LiveHearing.OFF
        partial = ""
        level = 0f
    }

    private fun deliver(text: String) {
        close()
        onUtterance(text)
    }

    // ------------------------------------------------------------------
    // System recognizer
    // ------------------------------------------------------------------

    private fun openRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            error = context.getString(R.string.live_error_no_recognizer)
            return
        }
        val session = generation
        val sr = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
            ?: run { error = context.getString(R.string.live_error_no_recognizer); return }
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            private fun current() = session == generation
            override fun onReadyForSpeech(params: Bundle?) { if (current()) hearing = LiveHearing.WAITING }
            override fun onBeginningOfSpeech() { if (current()) hearing = LiveHearing.HEARING }
            override fun onRmsChanged(rmsdB: Float) { if (current()) level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                if (!current()) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { partial = it }
            }
            override fun onResults(results: Bundle?) {
                if (!current()) return
                networkFailures = 0
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    ?: partial.trim()
                if (text.isNotEmpty()) deliver(text) else restartRecognizer(session, 0)
            }
            override fun onError(code: Int) {
                if (!current()) return
                when (code) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        error = context.getString(R.string.live_error_permission)
                        close()
                    }
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> {
                        networkFailures++
                        if (networkFailures >= 3) {
                            error = context.getString(R.string.live_error_recognizer_network)
                            close()
                        } else restartRecognizer(session, 1_000)
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        runCatching { recognizer?.destroy() }
                        recognizer = null
                        restartRecognizer(session, 400)
                    }
                    // No match, speech timeout and client errors just mean nobody spoke.
                    else -> {
                        val heard = partial.trim()
                        if (heard.isNotEmpty() && code == SpeechRecognizer.ERROR_NO_MATCH) deliver(heard)
                        else restartRecognizer(session, 150)
                    }
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs.toLong())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs.toLong())
        }
        partial = ""
        runCatching { sr.startListening(intent) }.onFailure { restartRecognizer(session, 500) }
    }

    private fun restartRecognizer(session: Int, delayMs: Long) {
        hearing = LiveHearing.WAITING
        partial = ""
        level = 0f
        loop?.cancel()
        loop = scope.launch {
            delay(delayMs)
            if (session == generation) openRecognizer()
        }
    }

    // ------------------------------------------------------------------
    // Groq Whisper
    // ------------------------------------------------------------------

    private fun openRecorder() {
        if (groqKey().isNullOrBlank()) {
            error = context.getString(R.string.live_error_no_groq_key)
            return
        }
        if (!startRecorder()) return
        val session = generation
        hearing = LiveHearing.WAITING
        loop = scope.launch {
            var noiseFloor = 600f
            var voicedMs = 0L
            var speaking = false
            var lastVoiceAt = 0L
            var segmentStart = SystemClock.elapsedRealtime()
            while (isActive && session == generation) {
                delay(FRAME_MS)
                val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0).toFloat()
                val now = SystemClock.elapsedRealtime()
                level = (amplitude / 12_000f).coerceIn(0f, 1f)
                val threshold = maxOf(MIN_SPEECH_AMPLITUDE, noiseFloor * 2.5f)
                if (amplitude > threshold) {
                    voicedMs += FRAME_MS
                    lastVoiceAt = now
                    if (voicedMs >= START_SPEECH_MS && !speaking) {
                        speaking = true
                        hearing = LiveHearing.HEARING
                    }
                } else if (!speaking) {
                    voicedMs = 0
                    noiseFloor = (noiseFloor * 0.95f + amplitude * 0.05f).coerceAtMost(8_000f)
                }
                val quietFor = now - lastVoiceAt
                when {
                    speaking && (quietFor >= silenceMs || now - segmentStart >= MAX_SEGMENT_MS) -> {
                        finishSegment(session, voicedMs)
                        return@launch
                    }
                    // Nothing said for a while: start a fresh file so it never grows.
                    !speaking && now - segmentStart >= IDLE_SEGMENT_MS -> {
                        stopRecorder(keepFile = false)
                        if (!startRecorder()) return@launch
                        segmentStart = now
                    }
                }
            }
        }
    }

    private suspend fun finishSegment(session: Int, voicedMs: Long) {
        val file = recordFile
        val stopped = runCatching { recorder?.stop() }.isSuccess
        stopRecorder(keepFile = true)
        level = 0f
        if (!stopped || file == null || voicedMs < MIN_UTTERANCE_MS) {
            file?.delete()
            if (session == generation) openRecorder()
            return
        }
        val key = groqKey()
        if (key.isNullOrBlank()) {
            file.delete()
            error = context.getString(R.string.live_error_no_groq_key)
            close()
            return
        }
        hearing = LiveHearing.TRANSCRIBING
        val result = try {
            withContext(Dispatchers.IO) { transcribe(file, key, whisperModel) }
        } catch (e: CancellationException) {
            throw e
        } finally {
            file.delete()
        }
        if (session != generation) return
        val text = result.getOrNull()?.trim().orEmpty()
        result.exceptionOrNull()?.let { error = it.message ?: context.getString(R.string.live_error_transcription) }
        if (text.isNotEmpty() && !isLikelyHallucination(text, voicedMs)) deliver(text) else openRecorder()
    }

    private fun startRecorder(): Boolean {
        val dir = File(context.cacheDir, "audio_recordings").apply { mkdirs() }
        val file = File(dir, "live_${System.currentTimeMillis()}.m4a")
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
            else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            mr.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioChannels(1)
            mr.setAudioSamplingRate(16_000)
            mr.setAudioEncodingBitRate(64_000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            recordFile = file
            true
        } catch (e: Exception) {
            runCatching { mr.release() }
            file.delete()
            error = context.getString(R.string.live_error_microphone, e.message.orEmpty())
            hearing = LiveHearing.OFF
            false
        }
    }

    private fun stopRecorder(keepFile: Boolean) {
        recorder?.let { mr ->
            runCatching { mr.reset() }
            runCatching { mr.release() }
        }
        recorder = null
        if (!keepFile) recordFile?.delete()
        recordFile = null
    }

    companion object {
        private const val FRAME_MS = 50L
        private const val MIN_SPEECH_AMPLITUDE = 1_500f
        private const val START_SPEECH_MS = 150L
        private const val MIN_UTTERANCE_MS = 350L
        private const val IDLE_SEGMENT_MS = 20_000L
        private const val MAX_SEGMENT_MS = 120_000L

        /** Whisper invents these from breath or room noise; drop them when little speech was heard. */
        private val hallucinations = setOf("thank you", "thanks for watching", "you", "bye", "thank you for watching")

        internal fun isLikelyHallucination(text: String, voicedMs: Long): Boolean =
            voicedMs < 1_200 && text.lowercase().trim().trimEnd('.', '!', '?') in hallucinations
    }
}
