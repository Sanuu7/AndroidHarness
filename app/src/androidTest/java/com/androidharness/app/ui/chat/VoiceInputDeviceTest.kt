package com.androidharness.app.ui.chat

import android.Manifest
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real Android microphone/encoder, with a controlled transcription service. No paid credentials. */
@RunWith(AndroidJUnit4::class)
class VoiceInputDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun recordedAacReachesTranscriptionAndItsTemporaryFileIsRemoved() = exercise(cancel = false)
    @Test fun cancellingTranscriptionCancelsWorkAndCannotOverwriteANewRecording() = exercise(cancel = true)

    private fun exercise(cancel: Boolean) = runBlocking<Unit> {
        val previousKey = c.keys.voiceApiKey("groq")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val entered = CompletableDeferred<File>()
        val finish = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val output = AtomicReference<String?>()
        lateinit var controller: VoiceInputController
        var created = false
        instrumentation.uiAutomation.grantRuntimePermission(c.appContext.packageName, Manifest.permission.RECORD_AUDIO)
        c.keys.putVoiceApiKey("groq", "voice-device-fixture")
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    controller = VoiceInputController(activity, c, scope) { file, key, model ->
                        assertEquals("voice-device-fixture", key)
                        assertEquals("whisper-large-v3-turbo", model)
                        val extractor = MediaExtractor()
                        try {
                            extractor.setDataSource(file.path)
                            assertTrue(extractor.trackCount > 0)
                            assertEquals("audio/mp4a-latm", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
                        } finally { extractor.release() }
                        entered.complete(file)
                        try { finish.await(); Result.success("Recorded voice fixture") }
                        finally { if (!currentCoroutineContext().isActive) cancelled.complete(Unit) }
                    }
                    created = true
                    assertTrue(controller.startGroqRecording())
                }
                await { controller.recordingDurationMs >= 1200 && controller.levels.isNotEmpty() }
                scenario.onActivity {
                    controller.lockGroqRecording()
                    assertEquals(GroqRecordState.LOCKED, controller.groqRecordState)
                    controller.stopAndTranscribeGroq("whisper-large-v3-turbo") { output.set(it) }
                }
                val recording = withTimeout(5000) { entered.await() }
                assertTrue(recording.length() >= 1024)
                if (cancel) {
                    scenario.onActivity { controller.cancelGroqRecording() }
                    withTimeout(1500) { cancelled.await() }
                    assertFalse(recording.exists())
                    scenario.onActivity { assertTrue(controller.startGroqRecording(locked = true)) }
                    finish.complete(Unit)
                    SystemClock.sleep(250)
                    assertNull(output.get())
                    assertEquals(GroqRecordState.LOCKED, controller.groqRecordState)
                } else {
                    finish.complete(Unit)
                    await { output.get() == "Recorded voice fixture" && controller.groqRecordState == GroqRecordState.IDLE }
                    assertFalse(recording.exists())
                }
                scenario.onActivity { controller.releaseAll() }
            }
        } finally {
            if (created) instrumentation.runOnMainSync { controller.releaseAll() }
            scope.cancel()
            if (previousKey == null) c.keys.removeVoiceApiKey("groq") else c.keys.putVoiceApiKey("groq", previousKey)
        }
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(40)
        }
        fail("Voice recording did not reach the expected state")
    }
}
