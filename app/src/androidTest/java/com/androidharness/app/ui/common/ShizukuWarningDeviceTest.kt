package com.androidharness.app.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.data.env.ShizukuState
import com.androidharness.app.data.env.shizukuWarningController
import com.androidharness.app.ui.chat.ChatListTestActivity
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the production popup and preferences without stopping the user's server. */
@RunWith(AndroidJUnit4::class)
class ShizukuWarningDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun checkboxPermanentlySuppressesWorkspaceWarning() {
        val preferences = instrumentation.targetContext.getSharedPreferences(
            "shizuku_warning_device_test", Context.MODE_PRIVATE,
        )
        preferences.edit().clear().commit()
        try {
            val controller = shizukuWarningController(preferences)
            controller.onStatus(ShizukuState.GRANTED)
            controller.checkWorkspace(ShizukuState.NOT_RUNNING)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { MaterialTheme { ShizukuDisabledWarning(controller) } }
                }
                awaitNode("Shizuku is disabled")
                // Accessibility appears before the platform dialog fade finishes.
                instrumentation.waitForIdleSync()
                SystemClock.sleep(350)
                val preview = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(instrumentation.targetContext.cacheDir, "shizuku-warning-preview.png").outputStream().use {
                    assertTrue(preview.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                preview.recycle()
                click("OK")
                awaitCondition("warning was not dismissed") { !controller.visible.value }
                controller.checkWorkspace(ShizukuState.RUNNING_NO_PERMISSION)
                awaitNode("Shizuku is disabled")
                click("Never show this message again")
                click("OK")
                awaitCondition("checkbox preference was not saved") {
                    preferences.getBoolean("never_show_again", false)
                }
                assertFalse(controller.visible.value)
            }
            // A new controller reads the stored choice, just as on app restart.
            val afterRestart = shizukuWarningController(preferences)
            afterRestart.checkWorkspace(ShizukuState.NOT_RUNNING)
            assertFalse(afterRestart.visible.value)
        } finally {
            preferences.edit().clear().commit()
        }
    }

    private fun awaitNode(text: String): AccessibilityNodeInfo {
        var match: AccessibilityNodeInfo? = null
        awaitCondition("Missing popup text: $text") {
            match = instrumentation.uiAutomation.rootInActiveWindow?.let { findNode(it, text) }
            match != null
        }
        return requireNotNull(match)
    }

    private fun findNode(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> findNode(child, text)?.let { return it } }
        }
        return null
    }

    private fun click(text: String) {
        var node = awaitNode(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Cannot click $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(32)
        }
        throw AssertionError(message)
    }
}
