package com.androidharness.app.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

class VoiceInputController(
    private val context: Context,
) {
    var isListening by mutableStateOf(false)
        private set

    var rmsDb by mutableFloatStateOf(0f)
        private set

    private var recognizer: SpeechRecognizer? = null
    private var onResultCallback: ((text: String, isFinal: Boolean) -> Unit)? = null

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(onResult: (text: String, isFinal: Boolean) -> Unit) {
        if (!isAvailable) return
        stopListening()

        onResultCallback = onResult
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
                    isListening = true
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    rmsDb = rmsdB
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    cleanup()
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrBlank()) {
                        onResultCallback?.invoke(text, true)
                    }
                    cleanup()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrBlank()) {
                        onResultCallback?.invoke(text, false)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            sr.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            cleanup()
        }
    }

    fun stopListening() {
        isListening = false
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            // Ignore
        }
        cleanup()
    }

    private fun cleanup() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            // Ignore
        }
        recognizer = null
        rmsDb = 0f
    }
}

@Composable
fun rememberVoiceInputController(): VoiceInputController {
    val context = LocalContext.current
    val controller = remember(context) { VoiceInputController(context) }
    DisposableEffect(controller) {
        onDispose {
            controller.stopListening()
        }
    }
    return controller
}
