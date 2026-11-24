package com.androidharness.app.local

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.MainActivity
import com.androidharness.app.ui.settings.LocalModelSettingsSheet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalThinkingSettingsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::nodes).orEmpty() }
    private fun click(text: String) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val found = root?.let { nodes(it).firstOrNull { n -> n.text?.toString() == text } }
            if (found != null) {
                var node: AccessibilityNodeInfo = found
                while (!node.isClickable && node.parent != null) node = requireNotNull(node.parent)
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                return
            }
            root?.let { nodes(it).firstOrNull { n -> n.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            SystemClock.sleep(100)
        }
        error("Missing local thinking setting: $text")
    }
    @Test fun reasoningOverridePersistsPerModelAndDefaultsReset() = runBlocking {
        val context = instrumentation.targetContext
        val root = File(context.noBackupFilesDir, "local-models").apply { mkdirs() }
        val id = "custom-11112222333344445555666677778888"
        val spec = LocalModelSpec(id, "Thinking settings test", "", "", "fixture.gguf", 24, "", 1, 1024,
            "Settings-only fixture, not an inference model", custom = true, maxContext = 512, localFile = true)
        val registry = CustomModelRegistry(root)
        check(registry.models.none { it.id == id })
        val model = File(root, "$id.gguf")
        check(!model.exists())
        registry.add(spec); model.writeBytes(ByteArray(24))
        val manager = LocalModelManager(context)
        try {
            withTimeout(5000) { manager.installed.first { id in it } }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun open() {
                    val generation = java.util.UUID.randomUUID()
                    scenario.onActivity { activity -> activity.setContent { MaterialTheme { androidx.compose.runtime.key(generation) { LocalModelSettingsSheet(spec, manager, onDismiss = {}) } } } }
                }
                open(); click(context.getString(com.androidharness.app.R.string.lms_thinking_always)); click("Save")
                withTimeout(5000) { while (manager.limits(id).thinkingMode != LocalThinkingMode.THINKING) kotlinx.coroutines.delay(50) }
                assertEquals(LocalThinkingMode.THINKING, LocalModelManager(context).limits(id).thinkingMode)
                open(); click(context.getString(com.androidharness.app.R.string.lms_thinking_never)); click("Save")
                withTimeout(5000) { while (manager.limits(id).thinkingMode != LocalThinkingMode.DIRECT) kotlinx.coroutines.delay(50) }
                open(); click(context.getString(com.androidharness.app.R.string.settings_action_reset_defaults)); click("Save")
                withTimeout(5000) { while (manager.limits(id).thinkingMode != LocalThinkingMode.AUTO) kotlinx.coroutines.delay(50) }
            }
        } finally { model.delete(); File(root, "$id.json").delete(); CustomModelRegistry(root).remove(id) }
    }
}
