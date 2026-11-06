package com.androidharness.app.ui.common

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SwipeDismissibleSnackbarDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val host = SnackbarHostState()
    private lateinit var scope: CoroutineScope
    private val results = ConcurrentLinkedQueue<SnackbarResult>()
    private val controlClicks = AtomicInteger()

    @Test fun swipesLeftRightAndDownDismissEachQueuedPopupAndExposeControls() = withHost { scenario ->
        show(scenario, "Run stopped")
        scenario.onActivity {
            scope.launch { results.add(host.showSnackbar("Next popup", duration = SnackbarDuration.Indefinite)) }
        }
        swipe("Run stopped", -100f, 0f)
        await { results.size == 1 && find("Next popup") != null }
        SystemClock.sleep(250)
        // The queued popup must get fresh gesture state.
        swipe("Next popup", 100f, 0f)
        await { results.size == 2 && host.currentSnackbarData == null }
        show(scenario, "Third popup")
        swipe("Third popup", 0f, 65f)
        await { results.size == 3 && find("Third popup") == null }
        assertTrue(results.all { it == SnackbarResult.Dismissed })
        click("Covered control")
        click("Composer control")
        await { controlClicks.get() == 2 }
    }

    @Test fun shortAndCancelledDragsReturnToRestAndUndoStillWorks() = withHost { scenario ->
        show(scenario, "Archived", action = "Undo")
        val before = bounds("Archived")
        swipe("Archived", 30f, 0f)
        SystemClock.sleep(500)
        assertTrue(results.isEmpty())
        assertTrue(kotlin.math.abs(bounds("Archived").centerX() - before.centerX()) < 4)
        swipe("Archived", 0f, 90f, cancel = true)
        SystemClock.sleep(500)
        assertTrue(results.isEmpty())
        assertTrue(kotlin.math.abs(bounds("Archived").centerY() - before.centerY()) < 4)
        click("Undo")
        await { results.size == 1 }
        assertEquals(SnackbarResult.ActionPerformed, results.single())
    }

    @Test fun untouchedPopupStillTimesOut() = withHost { scenario ->
        show(scenario, "Temporary notice", duration = SnackbarDuration.Short)
        await { results.size == 1 && find("Temporary notice") == null }
        assertEquals(SnackbarResult.Dismissed, results.single())
    }

    private fun withHost(test: (ActivityScenario<ChatListTestActivity>) -> Unit) {
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                scope = rememberCoroutineScope()
                HarnessTheme(ThemeMode.DARK, dynamicColor = false) {
                    Column(Modifier.fillMaxSize().systemBarsPadding(), verticalArrangement = Arrangement.Bottom) {
                        Box(Modifier.fillMaxWidth().height(220.dp)) {
                            Button(onClick = { controlClicks.incrementAndGet() }, Modifier.align(Alignment.BottomCenter)) {
                                Text("Covered control")
                            }
                            SwipeDismissibleSnackbarHost(host, Modifier.align(Alignment.BottomCenter))
                        }
                        Button(onClick = { controlClicks.incrementAndGet() }) { Text("Composer control") }
                    }
                }
            } }
            test(scenario)
        }
    }

    private fun show(scenario: ActivityScenario<ChatListTestActivity>, message: String,
        action: String? = null, duration: SnackbarDuration = SnackbarDuration.Indefinite) {
        scenario.onActivity { scope.launch { results.add(host.showSnackbar(message, actionLabel = action, duration = duration)) } }
        await { find(message) != null }
        SystemClock.sleep(250) // Let the host's entry animation finish before touching it.
    }

    private fun swipe(label: String, dx: Float, dy: Float, cancel: Boolean = false) {
        val rect = bounds(label)
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, progress: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                rect.centerX() + dx * density * progress, rect.centerY() + dy * density * progress, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        for (step in 1..12) { SystemClock.sleep(16); send(MotionEvent.ACTION_MOVE, step / 12f) }
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, 1f)
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.text?.toString() == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }

    private fun bounds(label: String) = Rect().also { requireNotNull(find(label)).getBoundsInScreen(it) }
    private fun click(label: String) {
        await { find(label) != null }
        val rect = bounds(label)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                rect.centerX().toFloat(), rect.centerY().toFloat(), 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(50) }
        assertTrue("Snackbar did not reach the expected state", condition())
    }
}
