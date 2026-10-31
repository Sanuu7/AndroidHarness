package com.androidharness.app.ui.learn

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.LocalDesktopLayout
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.settings.SettingsScreen
import com.androidharness.app.ui.theme.HarnessGlassMode
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.ui.theme.LocalHarnessGlass
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses the bundled guide and production settings navigation, without any model/API calls. */
@RunWith(AndroidJUnit4::class)
class LearnGuideDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun phoneGuideSearchBookmarksProgressAndConfigurationRestoreWork() {
        val prefs = instrumentation.targetContext.getSharedPreferences("learn_guide", 0)
        val completed = prefs.getStringSet("completed", emptySet()).orEmpty().toSet()
        val saved = prefs.getStringSet("saved", emptySet()).orEmpty().toSet()
        prefs.edit().remove("completed").remove("saved").commit()
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                mount(scenario)
                await("Learn AndroidHarness")
                screenshot("learn-settings-entry-dark")
                tap("Learn AndroidHarness")
                await("What would you like to learn?")
                screenshot("learn-library-dark")
                search("buy subscription")
                tap("API keys, credits, and subscriptions")
                await("Where to go")
                screenshot("learn-api-lesson-dark")
                tap("Save this lesson")
                assertTrue(prefs.getStringSet("saved", emptySet()).orEmpty().contains("api-billing"))
                reveal("Mark as learned")
                tap("Mark as learned")
                assertTrue(prefs.getStringSet("completed", emptySet()).orEmpty().contains("api-billing"))
                scenario.recreate()
                mount(scenario)
                // The selected lesson and scroll position survive Activity recreation.
                await("Learned · tap to undo")
                reveal("All lessons", forward = false)
                tap("All lessons")
                await("What would you like to learn?")
                tap("Saved")
                await("API keys, credits, and subscriptions")
                screenshot("learn-saved-dark")
            }
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                mount(scenario, largeText = true, dark = false)
                tap("Learn AndroidHarness")
                search("mocking module")
                tap("Charts, diagrams, widgets, and mockups")
                await("Where to go")
                screenshot("learn-mockups-large-text-light")
                reveal("Good to know")
                await("Good to know")
                screenshot("learn-limits-large-text-light")
            }
        } finally {
            prefs.edit().putStringSet("completed", completed).putStringSet("saved", saved).commit()
        }
    }

    @Test fun desktopLinksReturnToTheSameLessonAndGlassStaysOff() {
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitUntil { val d = instrumentation.targetContext.resources.displayMetrics; d.widthPixels > d.heightPixels }
            var closed = false
            mount(scenario, desktop = true, onClose = { closed = true })
            tap("Learn AndroidHarness")
            search("remote MCP server")
            tap("Connect a remote MCP server")
            screenshot("learn-desktop-reader")
            tap("Close panel")
            assertTrue("The wide-layout close button closes Settings even while reading a lesson", closed)
            reveal("Add a remote MCP server")
            tap("Add a remote MCP server")
            await("Add server")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Add a remote MCP server")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("What would you like to learn?")
            screenshot("learn-desktop-search")
        }
    }

    private fun mount(scenario: ActivityScenario<ChatListTestActivity>, largeText: Boolean = false, dark: Boolean = true, desktop: Boolean = false,
        onClose: () -> Unit = {}) {
        scenario.onActivity { activity -> activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 1.5f else 1f), LocalDesktopLayout provides desktop) {
                // A previously saved enabled preference must not reactivate a hidden feature.
                HarnessTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false,
                    glassEnabled = true, liquidGlass = true) {
                    assertEquals(HarnessGlassMode.OFF, LocalHarnessGlass.current.mode)
                    SettingsScreen(container, onBack = onClose)
                }
            }
        } }
    }

    private fun search(query: String) {
        await("What would you like to learn?")
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val fields = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) fields += node
            repeat(node.childCount) { collect(node.getChild(it)) }
        }
        collect(root)
        // Wide settings has a category search first; the guide's editable field follows it.
        val field = fields.lastOrNull()
        if (field == null) {
            val tree = StringBuilder()
            fun describe(node: AccessibilityNodeInfo?, depth: Int = 0) {
                if (node == null) return
                tree.append(" ".repeat(depth)).append(node.className).append(" text=").append(node.text)
                    .append(" desc=").append(node.contentDescription).append(" actions=").append(node.actionList).append('\n')
                repeat(node.childCount) { describe(node.getChild(it), depth + 1) }
            }
            describe(root)
            File(instrumentation.targetContext.getExternalFilesDir(null), "learn-search-tree.txt").writeText(tree.toString())
        }
        assertNotNull("Guide search input", field)
        assertTrue(field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
    }

    private fun tap(text: String) {
        repeat(4) {
            val found = await(text)
            var target = found
            while (!target.isClickable && target.parent != null) target = target.parent
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync(); SystemClock.sleep(220); return
            }
            val bounds = Rect().also(target::getBoundsInScreen)
            val height = instrumentation.targetContext.resources.displayMetrics.heightPixels
            if (!bounds.isEmpty && bounds.centerY() in 100 until height - 100) {
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0).also { event ->
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
                    }
                }
                instrumentation.waitForIdleSync(); SystemClock.sleep(220); return
            }
            swipe(forward = true)
        }
        screenshot("learn-tap-failure")
        fail("Tap $text")
    }

    private fun await(text: String): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        waitUntil { node = find(instrumentation.uiAutomation.rootInActiveWindow) {
            it.isVisibleToUser && (it.text?.toString()?.split('\n')?.contains(text) == true || it.contentDescription?.toString() == text)
        }; node != null }
        return requireNotNull(node)
    }

    private fun reveal(text: String, forward: Boolean = true) {
        repeat(24) {
            if (find(instrumentation.uiAutomation.rootInActiveWindow) { node ->
                node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)
            } != null) return
            swipe(forward)
        }
        screenshot("learn-unreachable-${text.replace(' ', '-')}")
        val tree = StringBuilder()
        fun describe(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null) return
            val bounds = Rect().also(node::getBoundsInScreen)
            tree.append(" ".repeat(depth)).append(node.className).append(" visible=").append(node.isVisibleToUser)
                .append(" scroll=").append(node.isScrollable).append(" bounds=").append(bounds)
                .append(" text=").append(node.text).append(" desc=").append(node.contentDescription).append('\n')
            repeat(node.childCount) { describe(node.getChild(it), depth + 1) }
        }
        describe(instrumentation.uiAutomation.rootInActiveWindow)
        File(instrumentation.targetContext.getExternalFilesDir(null), "learn-unreachable-${text.replace(' ', '-')}.txt").writeText(tree.toString())
        fail("Guide control unreachable: $text")
    }

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        repeat(node.childCount) { index -> find(node.getChild(index), predicate)?.let { return it } }
        return null
    }

    private fun swipe(forward: Boolean) {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val x = metrics.widthPixels * .73f // Scroll the reader pane in both phone and desktop layouts.
        // Overlapping, slow drags avoid skipping a small control between page-sized accessibility jumps.
        val start = metrics.heightPixels * if (forward) .73f else .43f
        val end = metrics.heightPixels * if (forward) .43f else .73f
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also {
                it.source = InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.uiAutomation.injectInputEvent(it, true)
                it.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, start)
        repeat(12) { step -> SystemClock.sleep(33); send(MotionEvent.ACTION_MOVE, start + (end - start) * (step + 1) / 12f) }
        SystemClock.sleep(120)
        send(MotionEvent.ACTION_UP, end)
        SystemClock.sleep(240)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; SystemClock.sleep(50) }
        throw AssertionError("Learn guide UI did not reach the expected state")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
