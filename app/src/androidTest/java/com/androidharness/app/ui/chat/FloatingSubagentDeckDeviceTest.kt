package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import com.androidharness.app.R
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.ui.chat.components.FloatingSubagentDock
import com.androidharness.app.ui.chat.components.SubagentBubblePosition
import com.androidharness.app.ui.chat.components.SubagentBubblePositionStore
import com.androidharness.app.ui.subagent.SubagentScreen
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FloatingSubagentDeckDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun textButtonLeavesComposerClearAndExpandsOnlyOnTapAtNormalAndLargeText(): Unit = runBlocking {
        val service = instrumentation.uiAutomation.serviceInfo
        service.flags = service.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = service
        for ((scale, theme) in listOf(1f to ThemeMode.LIGHT, 1.5f to ThemeMode.DARK)) {
            val sid = c.sessions.createSession("Floating subagents fixture")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val gate = CompletableDeferred<Unit>()
            var viewModel: ChatViewModel? = null
            val names = listOf("Build checker", "UI reviewer", "Test runner")
            val calls = names.mapIndexed { i, name -> ToolCallData("deck-$i-${System.nanoTime()}", "task",
                """{"name":"$name","prompt":"${listOf("Find why the APK build fails.", "Review the chat layout on a small screen.", "Run the focused regression tests.")[i]}"}""") }
            c.runManager.controls.update(sid) { it.copy(queue = listOf(QueuedPrompt(text = "Keep this queued message"))) }
            c.sessions.addMessage(sid, ChatMessage(Role.USER, "Check the build and review the UI."), "deck-turn")
            c.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT, text = "I’ll check the configuration while the subagents work.", toolCalls = calls), "deck-turn")
            val fixtureJobs = calls.mapIndexed { i, call ->
                val job = scope.launch {
                val run = SubagentRun(sid, "deck-turn", call, names[i],
                    listOf("Find why the APK build fails.", "Review the chat layout on a small screen.", "Run the focused regression tests.")[i],
                    "Fixture model", "fixture", false)
                c.runManager.subagents.foreground(run, { event ->
                    if (event is AgentEvent.SubagentMessageCommitted) c.sessions.addMessage(sid, event.message, "deck-turn")
                }) { events ->
                    events(AgentEvent.SubagentStep(call.id, if (i == 0) "Read app/build.gradle.kts" else "Read ChatScreen.kt"))
                    events(AgentEvent.SubagentStep(call.id, if (i == 0) "Check D8 and SDK paths" else "Inspect composer spacing"))
                    val update = if (i == 0) "Found the SDK configuration. Checking which build tools are available." else "Checking whether the controls leave enough room for messages."
                    events(AgentEvent.SubagentMessageCommitted(call.id, ChatMessage(Role.ASSISTANT, update, toolCallId = call.id)))
                    if (i == 0) gate.await() else if (i == 1) awaitCancellation()
                    ToolResult(true, if (i == 2) "12 focused tests passed." else "Build configuration checked.")
                }
                }
                withTimeout(10000) { c.runManager.subagents.forSession(sid).first { list -> list.any { it.id == call.id } } }
                job
            }
            try {
                withTimeout(15000) { c.runManager.subagents.forSession(sid).first { list -> list.size == 3 && list.first { it.id == calls[2].id }.status == SubagentStatus.COMPLETED } }
                instrumentation.runOnMainSync { viewModel = ChatViewModel(c, sid) }
                withTimeout(10000) { viewModel!!.state.first { it.messages.any { message -> message.toolCalls.size == 3 } } }
                ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                            HarnessTheme(theme, dynamicColor = false) {
                                var opened by remember { mutableStateOf<String?>(null) }
                                val holder = rememberSaveableStateHolder()
                                if (opened != null) SubagentScreen(c, sid, opened!!, onBack = { opened = null })
                                else holder.SaveableStateProvider("main-chat") {
                                    ChatScreen(viewModel!!, {}, { _, _ -> }, {}, {}, onOpenSubagent = { opened = it })
                                }
                            }
                        }
                    } }
                    await("Expand subagent popup")
                    await("Subagents")
                    assertNull("New agents should not open the panel", find("2 running"))
                    val bubble = Rect().also(await("Expand subagent popup")::getBoundsInScreen)
                    val pixels = c.appContext.resources.displayMetrics.density
                    assertTrue("Text button grew into a bar: $bubble", bubble.width() <= 180 * pixels)
                    assertTrue("Expected a text button instead of an icon: $bubble", bubble.width() > bubble.height())
                    assertTrue("Text button was too tall: $bubble", bubble.height() <= 50 * pixels)
                    val queue = Rect().also(await("Keep this queued message")::getBoundsInScreen)
                    assertTrue("Bubble covered the composer accessories: $bubble, queue=$queue", bubble.bottom < queue.top)
                    val popup = Rect().also(requireNotNull(await("Expand subagent popup").window?.root)::getBoundsInScreen)
                    assertTrue("An invisible popup window still blocked the chat: $popup", popup.width() <= bubble.width() + 2)
                    screenshot("bubble-$scale-collapsed")
                    tap("Expand subagent popup"); await("2 running")
                    screenshot("bubble-$scale-expanded")
                    tap("UI reviewer"); await("Running in the background")
                    gate.complete(Unit)
                    withTimeout(5000) { c.runManager.subagents.forSession(sid).first { list -> list.first { it.id == calls[0].id }.status == SubagentStatus.COMPLETED } }
                    // Another agent finishing must not replace the selected agent's details.
                    await("Checking whether the controls leave enough room for messages.")
                    tap("Open chat"); await("Results return to the main agent.")
                    await("Checking whether the controls leave enough room for messages.")
                    screenshot("bubble-$scale-full-chat")
                    tap("Main chat"); await("Running in the background")
                    tap("Stop")
                    withTimeout(5000) { c.runManager.subagents.forSession(sid).first { list -> list.first { it.id == calls[1].id }.status == SubagentStatus.STOPPED } }
                    tap("Back to subagents"); tap("Test runner"); await("12 focused tests passed.")
                    SystemClock.sleep(2200)
                    await("12 focused tests passed.")
                    tap("Collapse subagent popup"); awaitGone("Expand subagent popup")
                    // Tapping the transcript dismisses the nonmodal panel and reaches the underlying UI.
                    val message = await(c.appContext.getString(R.string.ui_message_input))
                    val messageBounds = Rect().also(message::getBoundsInScreen)
                    tapAt(messageBounds.centerX().toFloat(), messageBounds.centerY().toFloat())
                    assertNull("Typing left the details panel open", find("All finished"))
                    find("Expand subagent popup")?.let { node ->
                        val orb = Rect().also(node::getBoundsInScreen)
                        val input = Rect().also(await(c.appContext.getString(R.string.ui_message_input))::getBoundsInScreen)
                        assertTrue("Keyboard pushed the bubble over the typing box: $orb, input=$input", orb.bottom < input.top)
                    }
                    screenshot("bubble-$scale-typing")
                    instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    SystemClock.sleep(300)
                    assertNull("Completed work reappeared after typing", find("Expand subagent popup"))

                }
            } finally {
                instrumentation.runOnMainSync { viewModel?.viewModelScope?.cancel() }
                c.runManager.subagents.stopSessionAndJoin(sid)
                fixtureJobs.forEach { it.cancel() }; fixtureJobs.joinAll(); scope.cancel()
                c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            }
        }
    }

    @Test fun finishedBubbleFadesWithoutReappearingOnReopenAndNewWorkRevivesIt(): Unit = runBlocking {
        val sid = c.sessions.createSession("Bubble lifecycle fixture")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val job = scope.launch { c.runManager.subagents.foreground(fixture(sid, "first"), {}) { gate.await(); ToolResult(true, "Saved result") } }
        try {
            withTimeout(10000) { c.runManager.subagents.forSession(sid).first { it.any { run -> run.status.active } } }
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                showDock(scenario, sid, sid)
                await("Expand subagent popup")
                gate.complete(Unit); job.join()
                awaitGone("Expand subagent popup")
                assertEquals("Saved result", c.runManager.subagents.list(sid).single().output)
                scenario.recreate(); showDock(scenario, sid, sid)
                SystemClock.sleep(600)
                assertNull("Reopening history revived the bubble", find("Expand subagent popup"))
                val next = CompletableDeferred<Unit>()
                val second = scope.launch { c.runManager.subagents.foreground(fixture(sid, "second"), {}) { next.await(); ToolResult(false, "Fixture failure") } }
                await("Expand subagent popup")
                next.complete(Unit); second.join()
                awaitGone("Expand subagent popup")
            }
        } finally {
            c.runManager.subagents.stopSessionAndJoin(sid); scope.cancel()
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
        }
    }

    @Test fun draggingRemembersThePositionAcrossChatsAndScreenRecreation(): Unit = runBlocking {
        val first = c.sessions.createSession("Bubble position A")
        val second = c.sessions.createSession("Bubble position B")
        val store = SubagentBubblePositionStore(c.appContext)
        val before = store.read()
        store.save(SubagentBubblePosition())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val jobs = listOf(first, second).map { sid -> scope.launch {
            c.runManager.subagents.foreground(fixture(sid, sid), {}) { awaitCancellation() }
        } }
        try {
            for (sid in listOf(first, second)) withTimeout(10000) { c.runManager.subagents.forSession(sid).first { it.any { run -> run.status.active } } }
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                showDock(scenario, first, second)
                val original = Rect().also(await("Expand subagent popup")::getBoundsInScreen)
                val display = c.appContext.resources.displayMetrics
                drag(original.centerX().toFloat(), original.centerY().toFloat(), display.widthPixels * .22f, display.heightPixels * .22f)
                val moved = Rect().also(await("Expand subagent popup")::getBoundsInScreen)
                assertTrue("Bubble did not move: $original -> $moved", moved.left < original.left - 60 && moved.top < original.top - 60)
                assertTrue("Dragging opened the panel", find("Collapse subagent popup") == null)
                val saved = SubagentBubblePositionStore(c.appContext).read()
                assertTrue(saved.x < .9f && saved.y < .9f)
                tap("Expand subagent popup")
                val expanded = awaitBubbleAt("Collapse subagent popup", moved)
                screenshot("bubble-dragged-expanded")
                assertTrue("Opening moved the dragged bubble: $moved -> $expanded", kotlin.math.abs(moved.left - expanded.left) <= 2 && kotlin.math.abs(moved.top - expanded.top) <= 2)
                fits("Collapse subagent popup")
                tap("Collapse subagent popup")
                tap("Second chat")
                val shared = awaitBubbleAt("Expand subagent popup", moved)
                assertTrue("Second chat forgot the position: $moved -> $shared", kotlin.math.abs(moved.left - shared.left) <= 2 && kotlin.math.abs(moved.top - shared.top) <= 2)
                scenario.recreate(); showDock(scenario, first, second)
                val restored = awaitBubbleAt("Expand subagent popup", moved)
                assertTrue("Recreated screen forgot the position", kotlin.math.abs(moved.left - restored.left) <= 2 && kotlin.math.abs(moved.top - restored.top) <= 2)
                assertEquals(saved, SubagentBubblePositionStore(c.appContext).read())
            }
        } finally {
            for (sid in listOf(first, second)) c.runManager.subagents.stopSessionAndJoin(sid)
            jobs.forEach { it.cancel() }; jobs.joinAll(); scope.cancel(); store.save(before)
            for (sid in listOf(first, second)) c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
        }
    }

    private fun fixture(session: String, id: String) = SubagentRun(session, id, ToolCallData(id, "task", "{}"),
        "Fixture worker", "Fixture task", "model", "fixture", false)

    private fun showDock(scenario: ActivityScenario<ChatListTestActivity>, first: String, second: String) {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        scenario.onActivity { activity -> activity.setContent { HarnessTheme {
            var sid by remember { mutableStateOf(first) }
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row { TextButton(onClick = { sid = first }) { Text("First chat") }; TextButton(onClick = { sid = second }) { Text("Second chat") } }
                Spacer(Modifier.weight(1f))
                FloatingSubagentDock(sid, c.runManager, {})
                Text("Composer accessories")
                OutlinedTextField("", {}, Modifier.fillMaxWidth(), label = { Text("Message") })
            }
        } } }
    }

    private fun drag(x: Float, y: Float, endX: Float, endY: Float) {
        val start = SystemClock.uptimeMillis()
        for (step in 0..20) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(),
                if (step == 0) MotionEvent.ACTION_DOWN else if (step == 20) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,
                x + (endX - x) * step / 20, y + (endY - y) * step / 20, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle(); SystemClock.sleep(20)
        }
        instrumentation.runOnMainSync { }; SystemClock.sleep(400)
    }

    private fun awaitGone(text: String) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) { if (find(text) == null) { SystemClock.sleep(250); if (find(text) == null) return }; SystemClock.sleep(80) }
        error("Popup did not disappear: $text")
    }

    private fun awaitBubbleAt(label: String, expected: Rect): Rect {
        val deadline = SystemClock.uptimeMillis() + 5000
        var actual = Rect()
        while (SystemClock.uptimeMillis() < deadline) {
            find(label, bubbleOnly = true)?.let { node ->
                actual = Rect().also(node::getBoundsInScreen)
                if (kotlin.math.abs(expected.left - actual.left) <= 2 && kotlin.math.abs(expected.top - actual.top) <= 2) return actual
            }
            SystemClock.sleep(80)
        }
        error("Bubble position did not settle: expected $expected, actual $actual")
    }

    private fun find(text: String, bubbleOnly: Boolean = false): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString()?.split('\n')?.contains(text) == true || node.contentDescription?.toString() == text ||
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == text })) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (!bubbleOnly || bounds.width() >= 46 * c.appContext.resources.displayMetrics.density) return node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.firstNotNullOfOrNull(::visit)
            ?: instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun await(text: String, bubbleOnly: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) { find(text, bubbleOnly)?.let { return it }; SystemClock.sleep(80) }
        val dump = StringBuilder()
        fun describe(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 24) return
            dump.append("  ".repeat(depth)).append("${node.className}: text=${node.text} desc=${node.contentDescription} visible=${node.isVisibleToUser}\n")
            for (i in 0 until node.childCount) describe(node.getChild(i), depth + 1)
        }
        instrumentation.uiAutomation.windows.mapNotNull { it.root }.forEach { describe(it, 0) }
        File(c.appContext.cacheDir, "subagent-missing-ui.txt").writeText(dump.toString())
        error("Missing UI: $text")
    }
    private fun tap(text: String) {
        var node: AccessibilityNodeInfo? = reachable(text)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Could not tap $text", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        // Active text shimmer is continuous; wait for input handling, then assert the resulting UI.
        instrumentation.runOnMainSync { }; SystemClock.sleep(300)
    }
    private fun reachable(text: String): AccessibilityNodeInfo {
        repeat(8) {
            find(text)?.let { return it }
            fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isScrollable) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { scrollable(it)?.let { found -> return found } }
                return null
            }
            val root = find("Collapse subagent popup")?.window?.root
            if (root == null || scrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) return await(text)
            SystemClock.sleep(200)
        }
        return await(text)
    }
    private fun tapAt(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 30 else 0, action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
        }
        instrumentation.runOnMainSync { }; SystemClock.sleep(400)
    }
    private fun swipe(y: Float) {
        val width = c.appContext.resources.displayMetrics.widthPixels
        val start = SystemClock.uptimeMillis()
        for (step in 0..12) {
            val event = MotionEvent.obtain(start, start + step * 18L,
                if (step == 0) MotionEvent.ACTION_DOWN else if (step == 12) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,
                width * (.8f - .6f * step / 12), y, 0)
            instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
        }
        instrumentation.runOnMainSync { }; SystemClock.sleep(500)
    }
    private fun fits(text: String) {
        val bounds = Rect().also(await(text)::getBoundsInScreen)
        val display = c.appContext.resources.displayMetrics
        assertTrue("$text clipped: $bounds", bounds.left >= 0 && bounds.right <= display.widthPixels && bounds.top >= 0 && bounds.bottom <= display.heightPixels)
    }
    private fun screenshot(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(c.appContext.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
