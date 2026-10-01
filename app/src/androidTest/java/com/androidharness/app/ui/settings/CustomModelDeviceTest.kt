package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.androidharness.app.HarnessApp
import com.androidharness.app.local.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in network test downloads a 105 MB chat model, runs it on CPU, then deletes it. */
@RunWith(AndroidJUnit4::class)
class CustomModelDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 420_000) fun pastedHuggingFaceFileDownloadsPersistsAndProducesLocalReply() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveLocalModels") == "true")
        val context = instrumentation.targetContext
        val manager = (context.applicationContext as HarnessApp).container.localModels
        val selected = AtomicReference<LocalModelSpec?>()
        val link = "https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/blob/main/SmolLM2-135M-Instruct-Q4_K_M.gguf"
        var downloadedId: String? = null
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                    CustomModelDialog(manager, onDismiss = {}, onDownload = { selected.set(it) })
                } } }
                val field = awaitNode { it.isEditable }
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, link)
                }))
                click("Find model files")
                awaitNode(60_000) { it.text?.toString() == "SmolLM2-135M-Instruct-Q4_K_M.gguf" }
                instrumentation.waitForIdleSync(); SystemClock.sleep(350)
                val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(context.cacheDir, "custom-model-picker.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
                click("Choose file")
                val deadline = SystemClock.uptimeMillis() + 60_000
                while (selected.get() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
                val model = requireNotNull(selected.get()) { "Picker did not return checked model." }
                downloadedId = model.id
                runBlocking {
                    manager.downloadCustom(model)
                    withTimeout(240_000) {
                        val result = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("local-model-${model.id}")
                            .first { jobs -> jobs.isNotEmpty() && jobs.all { it.state.isFinished } }
                        assertEquals(result.first().outputData.getString("error"), WorkInfo.State.SUCCEEDED, result.first().state)
                    }
                    assertTrue(model.id in manager.installed.value)
                    val restored = CustomModelRegistry(File(context.noBackupFilesDir, "local-models"))
                    assertEquals(model, restored.models.first { it.id == model.id })
                    assertTrue(manager.configs().any { it.id == "local-model:${model.id}" })
                    manager.saveLimits(model.id, LocalModelLimits(context = 512, input = 384, output = 128, threads = 2))
                    val reply = StringBuilder()
                    val result = withTimeout(90_000) {
                        manager.generate(model.id, arrayOf("system", "user"),
                            arrayOf("You are a helpful assistant.".toByteArray(), "Say hello in one short sentence.".toByteArray()), 32) {
                            reply.append(it.toString(Charsets.UTF_8))
                        }
                    }
                    assertTrue("No generated tokens", result[1] > 0)
                    assertTrue("No reply text", reply.isNotBlank())
                    println("CUSTOM_LOCAL_REPLY: $reply")
                }
            }
        } finally {
            downloadedId?.let { id -> runBlocking { manager.remove(id) } }
        }
    }

    private fun click(text: String) {
        var node = awaitNode { it.text?.toString() == text }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Cannot click $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun awaitNode(timeout: Long = 10_000, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, predicate)?.let { match -> return match } }
            SystemClock.sleep(50)
        }
        throw AssertionError("Expected UI element did not appear")
    }
    private fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let { find(it, predicate)?.let { found -> return found } }
        return null
    }
}
