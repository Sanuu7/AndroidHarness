package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.llm.*
import com.androidharness.app.tools.TodoWriteTool
import com.androidharness.app.ui.chat.components.FloatingTaskDock
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class TodoProgressDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun aNewListOpensForFiveSecondsThenFoldsIntoThePill(): Unit = runBlocking {
        val store = TodoStore()
        val sid = UUID.randomUUID().toString()
        store.beginRun(sid)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            host(scenario, store, sid)
            SystemClock.sleep(500)
            assertNull(find("Task list"))
            val written = SystemClock.uptimeMillis()
            store.setAll(listOf(TodoItem("Plan the change", TodoItem.Status.IN_PROGRESS), TodoItem("Write the code")))
            awaitPill(open = true)
            SystemClock.sleep((written + 3_500 - SystemClock.uptimeMillis()).coerceAtLeast(0))
            val checkedAt = SystemClock.uptimeMillis() - written
            val node = (0 until 5).firstNotNullOfOrNull { pill() ?: run { SystemClock.sleep(80); null } }
            assertTrue("A new list stays open for five seconds (checked at ${checkedAt}ms): ${node?.actionList?.map { it.label }}",
                node?.actionList?.any { it.label?.toString() == "Collapse task list" } == true)
            awaitPill(open = false, timeoutMs = 4000)
            screenshot("todo-pill-created-folded")
            awaitText("Task list")
            // The pill can be dragged; it stays on screen.
            SystemClock.sleep(900)
            drag("Task list", -300f, -400f)
            assertWithinScreen("Task list")
            // A finished drag leaves the pill working: a tap still opens the list.
            assertTrue(requireNotNull(pill()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitPill(open = true)
        }
    }

    @Test fun taskUpdatesPrecedeParallelWorkAndRefreshEveryRequest(): Unit = runBlocking {
        val store = TodoStore()
        val sid = UUID.randomUUID().toString()
        store.beginRun(sid)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val subagents = AtomicInteger()
        val root = File(c.appContext.cacheDir, "todo-progress-$sid").apply { mkdirs() }
        File(root, "fixture.txt").writeText("Verified fixture input")
        var round = 0
        fun todo(id: String, statuses: List<String>) = ToolCallData(id, "todo_write",
            """{"todos":[{"content":"Inspect the task progress update path","status":"${statuses[0]}"},{"content":"Improve the task list layout on a narrow phone","status":"${statuses[1]}"},{"content":"Verify the result with larger text","status":"${statuses[2]}"}]}""")
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                if (systemPrompt.contains("research subagent")) {
                    assertEquals("Progress must be published before delegated work starts", TodoItem.Status.IN_PROGRESS, store.todos.value.firstOrNull()?.status)
                    if (subagents.incrementAndGet() == 2) entered.complete(Unit)
                    release.await()
                    emit(StreamEvent.TextDelta("Inspected the fixture")); emit(StreamEvent.Done("stop"))
                    return@flow
                }
                when (++round) {
                    1 -> {
                        emit(StreamEvent.ToolCallReady(todo("start", listOf("in_progress", "pending", "pending"))))
                        repeat(2) { emit(StreamEvent.ToolCallReady(ToolCallData("research-$it", "task", """{"prompt":"Inspect fixture.txt","title":"Research $it"}"""))) }
                    }
                    2 -> {
                        assertTrue(systemPrompt.contains("[in_progress] Inspect the task progress update path"))
                        emit(StreamEvent.ToolCallReady(todo("advance", listOf("completed", "in_progress", "pending"))))
                    }
                    3, 4, 5 -> {
                        assertTrue(systemPrompt.contains("[completed] Inspect the task progress update path"))
                        assertTrue(systemPrompt.contains("[in_progress] Improve the task list layout"))
                        assertFalse(systemPrompt.contains("Progress check:"))
                        emit(StreamEvent.ToolCallReady(ToolCallData("read-$round", "read_file", """{"path":"fixture.txt"}""")))
                    }
                    6 -> {
                        assertTrue(systemPrompt.contains("Progress check:"))
                        emit(StreamEvent.ToolCallReady(todo("verify", listOf("completed", "completed", "in_progress"))))
                    }
                    else -> {
                        assertFalse(systemPrompt.contains("Progress check:"))
                        assertTrue(systemPrompt.contains("[in_progress] Verify the result with larger text"))
                        emit(StreamEvent.TextDelta("Verification still needs a real device check."))
                        emit(StreamEvent.Done("stop")); return@flow
                    }
                }
                emit(StreamEvent.Done("tool_calls"))
            }
        }
        val engine = AgentEngine({ provider }, c.registry.withExtra(listOf(TodoWriteTool(store))), c.checkpoints,
            c.images, c.linuxEnv, c.shizuku, c.skills, todoStore = store)
        val config = ProviderConfig("todo-fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.test", "fixture-model")
        val run = async(Dispatchers.Default) {
            engine.run(sid, UUID.randomUUID().toString(), config, "fixture", listOf(ChatMessage(role = Role.USER, text = "Fix task progress")),
                { PermissionMode.FULL_AUTO }, mutableSetOf(), FileFs(root), RequestOptions(), 32000, AgentMode.ACT,
                maxIterations = 10, repoMapEnabled = false).toList()
        }
        try {
            withTimeout(15000) { entered.await() }
            assertTrue("Agent is still working while task progress is visible", run.isActive)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                host(scenario, store, sid)
                // Opening a chat with a list already running shows the pill quietly.
                awaitText("Task list")
                SystemClock.sleep(600)
                assertFalse("List must not open by itself on reopen", pillOpen())
                screenshot("todo-pill-collapsed")
                click("Task list")
                awaitText("0 of 3 completed"); awaitText("Verify the result with larger text")
                screenshot("todo-pill-expanded")
                assertWithinScreen("Collapse task list")
                // Opened by hand, it stays open past the automatic timeout.
                SystemClock.sleep(3_800)
                assertTrue("Opened by hand stays open", pillOpen())
                assertTrue(requireNotNull(pill()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitPill(open = false)

                release.complete(Unit)
                val events = withTimeout(20000) { run.await() }
                assertFalse(events.any { it is AgentEvent.Error })
                assertTrue(events.filterIsInstance<AgentEvent.ToolMessageCommitted>().all { !it.message.isError })
                assertEquals("An answer must not silently complete unfinished tasks", TodoItem.Status.IN_PROGRESS, store.todos.value.last().status)
                // An update leaves the list closed; the pill shows the new count instead.
                awaitPillState("2 of 3 completed")
                assertFalse("Updates must not open the list", pillOpen())
                screenshot("todo-pill-update")

                store.setAll(store.todos.value.map { it.copy(status = TodoItem.Status.COMPLETED) })
                // The pill names the task that just finished, still without opening the list.
                awaitPillState("Verify the result with larger text")
                assertFalse("Finishing must not open the list", pillOpen())
                screenshot("todo-pill-finished-task")
                awaitPillState("All tasks completed", TaskPeekTiming.ANNOUNCE + 3000)
                screenshot("todo-pill-completed")
                awaitGone("Task list", TaskPeekTiming.DONE + 4000)
            }
        } finally { release.complete(Unit); run.cancelAndJoin(); root.deleteRecursively() }
    }

    private object TaskPeekTiming {
        const val CREATED = 5_000L
        const val ANNOUNCE = 2_200L
        const val DONE = 3_500L
    }

    private fun host(scenario: ActivityScenario<ChatListTestActivity>, store: TodoStore, sid: String) {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        scenario.onActivity { activity -> activity.setContent { HarnessTheme {
            val todos by store.todos.collectAsState()
            Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().navigationBarsPadding().statusBarsPadding()) {
                Text("Agent is working", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                FloatingTaskDock(sid, todos)
                OutlinedTextField("", {}, placeholder = { Text("Message") }, modifier = Modifier.fillMaxWidth().padding(12.dp))
            } }
        } } }
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.firstNotNullOfOrNull(::visit)
            ?: instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    /** The pill's tap action says what a tap will do, so it reports whether the list is open. */
    private fun pill(): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.text == null && node.contentDescription?.toString() == "Task list") return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.firstNotNullOfOrNull(::visit)
    }
    private fun pillOpen(): Boolean = pill()?.actionList
        ?.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == "Collapse task list" } == true
    private fun awaitPill(open: Boolean, timeoutMs: Long = 3000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) { if (pillOpen() == open) return; SystemClock.sleep(100) }
        val n = pill()
        error("Task list never became ${if (open) "open" else "closed"}: state=${n?.stateDescription} actions=${n?.actionList?.map { "${it.id}:${it.label}" }}")
    }
    private fun awaitPillState(part: String, timeoutMs: Long = 5000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (pill()?.stateDescription?.toString()?.contains(part) == true) return
            SystemClock.sleep(80)
        }
        error("Pill never showed '$part': ${pill()?.stateDescription}")
    }
    private fun awaitGone(text: String, timeoutMs: Long = 3000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) { if (find(text) == null) return; SystemClock.sleep(100) }
        error("Still showing: $text")
    }
    private fun drag(text: String, dx: Float, dy: Float) {
        val bounds = Rect().also(awaitText(text)::getBoundsInScreen)
        val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, px: Float, py: Float, t: Long) {
            val e = android.view.MotionEvent.obtain(start, t, action, px, py, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(e, true)) } finally { e.recycle() }
        }
        send(android.view.MotionEvent.ACTION_DOWN, x, y, start)
        for (i in 1..12) send(android.view.MotionEvent.ACTION_MOVE, x + dx * i / 12, y + dy * i / 12, start + i * 16L)
        send(android.view.MotionEvent.ACTION_UP, x + dx, y + dy, start + 13 * 16L)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
        val moved = Rect().also(awaitText(text)::getBoundsInScreen)
        assertTrue("Pill did not move: $bounds -> $moved", moved.top < bounds.top)
    }
    private fun awaitText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) { find(text)?.let { return it }; SystemClock.sleep(100) }
        error("Task list UI missing: $text")
    }
    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = awaitText(text)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(requireNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun assertWithinScreen(text: String) {
        val bounds = Rect().also(awaitText(text)::getBoundsInScreen)
        val screen = c.appContext.resources.displayMetrics
        assertTrue("$text outside screen: $bounds", bounds.left >= 0 && bounds.top >= 0 && bounds.right <= screen.widthPixels && bounds.bottom <= screen.heightPixels)
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(c.appContext.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
