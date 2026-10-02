package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.data.AppSettings
import com.androidharness.app.ui.chat.ChatListTestActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises production controls with temporary state, without changing user preferences. */
@RunWith(AndroidJUnit4::class)
class AgentBehaviorDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun choicesApplyCancelPreservesValuesAndRowsAreAccessible() {
        val state = mutableStateOf(AppSettings(maxContextTokens = 200_000, maxIterations = 75))
        var instructionsOpened = false
        launch(state, dark = true, fontScale = 1f) { instructionsOpened = true }.use {
            awaitNode("Default permission mode")
            screenshot("agent-behavior-dark.png")
            click("Default permission mode")
            awaitNode("Confirm everything")
            screenshot("agent-behavior-permissions.png")
            click("Full access")
            awaitCondition { state.value.permissionMode == PermissionMode.FULL_ACCESS }
            awaitNode("Run without approvals, workspace limits or command safeguards.")
            click("Default permission mode")
            click("Cancel")
            assertEquals(PermissionMode.FULL_ACCESS, state.value.permissionMode)
            click("Subagent action tools")
            awaitCondition { state.value.subagentFullAccess }
            click("Max context window")
            // Values previously set from chat controls must remain selectable.
            awaitNode("200K tokens")
            click("Cancel")
            assertEquals(200_000, state.value.maxContextTokens)
            click("Max context window")
            click("400K tokens")
            awaitCondition { state.value.maxContextTokens == 400_000 }
            click("Tool-call iteration limit")
            awaitNode("75 rounds")
            click("25 rounds")
            awaitCondition { state.value.maxIterations == 25 }
            click("Project instructions (AGENTS.md)")
            awaitCondition { instructionsOpened }
        }
    }

    @Test
    fun narrowScreenWithLargeTextKeepsAllControlsReachable() {
        val state = mutableStateOf(AppSettings())
        launch(state, dark = false, fontScale = 1.5f).use {
            awaitNode("Default permission mode")
            screenshot("agent-behavior-large-text.png")
            click("Tool-call iteration limit")
            click("100 rounds")
            awaitCondition { state.value.maxIterations == 100 }
            click("Default permission mode")
            click("Confirm everything")
            awaitCondition { state.value.permissionMode == PermissionMode.CONFIRM_ALL }
            click("Project instructions (AGENTS.md)")
        }
    }

    private fun launch(
        state: MutableState<AppSettings>,
        dark: Boolean,
        fontScale: Float,
        onInstructions: () -> Unit = {},
    ): ActivityScenario<ChatListTestActivity> {
        val scenario = ActivityScenario.launch(ChatListTestActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        Surface(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Column(
                                    Modifier.widthIn(max = 360.dp).fillMaxHeight()
                                        .statusBarsPadding().navigationBarsPadding()
                                        .verticalScroll(rememberScrollState()).padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(20.dp),
                                ) {
                                    Text("Agent behavior", style = MaterialTheme.typography.titleLarge)
                                    AgentBehaviorSection(
                                        settings = state.value,
                                        onPermissionMode = { state.value = state.value.copy(permissionMode = it) },
                                        onSubagentTools = { state.value = state.value.copy(subagentFullAccess = it) },
                                        onContextLimit = { state.value = state.value.copy(maxContextTokens = it) },
                                        onIterationLimit = { state.value = state.value.copy(maxIterations = it) },
                                        onProjectInstructions = onInstructions,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return scenario
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun awaitNode(text: String): AccessibilityNodeInfo {
        var match: AccessibilityNodeInfo? = null
        awaitCondition {
            match = instrumentation.uiAutomation.rootInActiveWindow?.let { findNode(it, text) }
            match != null
        }
        return requireNotNull(match)
    }

    private fun findNode(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.split('\n')?.contains(text) == true || node.contentDescription?.toString() == text) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { findNode(it, text)?.let { found -> return found } }
        }
        return null
    }

    private fun swipe(forward: Boolean) {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val start = metrics.heightPixels * if (forward) 0.75f else 0.3f
        val end = metrics.heightPixels * if (forward) 0.3f else 0.75f
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, start)
        for (step in 1..12) {
            SystemClock.sleep(16)
            send(MotionEvent.ACTION_MOVE, start + (end - start) * step / 12f)
        }
        send(MotionEvent.ACTION_UP, end)
        SystemClock.sleep(400)
    }

    private fun click(text: String) {
        repeat(16) { attempt ->
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val match = root?.let { findNode(it, text) }
            if (match?.isVisibleToUser == true) {
                var node = requireNotNull(match)
                while (!node.isClickable && node.parent != null) node = node.parent
                assertTrue("Cannot click $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                SystemClock.sleep(200)
                return
            }
            swipe(forward = attempt < 8)
        }
        throw AssertionError("Unreachable control: $text")
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(32)
        }
        throw AssertionError("Agent behavior UI did not reach the expected state")
    }
}
