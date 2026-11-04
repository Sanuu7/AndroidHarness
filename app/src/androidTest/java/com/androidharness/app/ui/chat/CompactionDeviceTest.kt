package com.androidharness.app.ui.chat

import androidx.lifecycle.viewModelScope
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Bitmap
import com.androidharness.app.ui.theme.HarnessTheme
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.data.db.*
import com.androidharness.app.llm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okio.buffer
import okio.source
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CompactionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun chatOverrideChangesLiveRunCompactsAndContinuesWithoutAQueuedPrompt() = runBlocking {
        val saved = c.settings.settings.first()
        // Keep this live-setting test in the foreground, independent of fresh-install notification policy.
        val scenario = ActivityScenario.launch(ChatListTestActivity::class.java)
        val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
        val server = FixtureServer(requests).apply { mode = "tool-stall" }
        val root = java.io.File(c.appContext.cacheDir, "compact-setting-${System.nanoTime()}").apply { mkdirs() }
        val project = c.workspace.addShellProject(root.path, activate = false)
        val provider = c.providers.add("Threshold fixture", ProviderType.OPENAI_COMPAT,
            "http://127.0.0.1:${server.port}/v1", "fixture-model", "fixture")
        val sid = c.sessions.createSession("Threshold chat", project.id)
        val other = c.sessions.createSession("Independent chat", project.id)
        var vm: ChatViewModel? = null
        try {
            c.settings.setAutoCompactTokens(900_000)
            repeat(20) { c.sessions.addMessage(sid, ChatMessage(Role.USER, "Recorded goal $it " + "x".repeat(8_000))); delay(2) }
            val originalIds = c.sessions.messages(sid).map { it.id }
            val chat = onMain { ChatViewModel(c, sid) }.also { vm = it }
            withTimeout(15_000) { chat.state.first { it.conversationReady && it.autoCompactTokens == 900_000 } }
            c.runManager.startRun(sid, "Continue the saved task", emptyList(), provider, "fixture", PermissionMode.CONFIRM_RISKY,
                AgentMode.ACT, 2_000, 1_000_000, ThinkingLevel.OFF, 4, workspaceOverride = c.workspace.forSession(sid), notifyOnFinish = false)
            withTimeout(15_000) { while (requests.isEmpty()) delay(20) }
            assertTrue(c.sessions.session(sid)!!.compactionSummary.isEmpty())
            assertTrue(onMain { chat.send("/autocompact 35K") })
            withTimeout(10_000) { c.runManager.controls.flow(sid).first { it.autoCompactTokens == 35_000 } }
            assertTrue(c.runManager.controls.flow(sid).value.queue.isEmpty())
            assertNull(c.runManager.controls.flow(other).value.autoCompactTokens)
            assertEquals(900_000, c.settings.settings.first().autoCompactTokens)
            server.release.countDown()
            withTimeout(60_000) { c.runManager.live(sid).first { !it.running } }
            withTimeout(15_000) { while (c.runManager.isRunning(sid)) delay(10) }
            assertNull(c.runManager.live(sid).value.error)
            val finished = c.runManager.controls.flow(sid).value
            assertTrue("status=${finished.status}; reason=${finished.reason}; cancelled=${c.runManager.live(sid).value.cancelled}; requests=${requests.size}; messages=${c.sessions.messages(sid).takeLast(3).map { it.role to it.text.take(150) }}", finished.completed)
            assertEquals("Preserve the goal and tool outcomes", c.sessions.session(sid)!!.compactionSummary)
            assertTrue(c.sessions.messages(sid).map { it.id }.containsAll(originalIds))
            assertTrue(c.sessions.messages(sid).any { it.role == Role.TOOL && it.toolCallId == "fixture-list" })
            assertFalse(c.sessions.messages(sid).any { it.text.startsWith("/autocompact") })
            val finalMessages = requests.last()["messages"]!!.jsonArray.map { it.jsonObject }
            assertTrue(finalMessages.any { it["content"]?.jsonPrimitive?.content?.contains("Auto-compacted context") == true })
            assertTrue(finalMessages.any { it["role"]?.jsonPrimitive?.content == "tool" })
            assertTrue(onMain { chat.send("/autocompact 258K") })
            withTimeout(10_000) { c.runManager.controls.flow(sid).first { it.autoCompactTokens == 258_000 } }
            assertEquals(258_000, TaskControlStore(java.io.File(c.appContext.filesDir, "task-controls")).flow(sid).value.autoCompactTokens)
            assertFalse(onMain { chat.send("/autocompact 999999999999M") })
            assertEquals(258_000, c.runManager.controls.flow(sid).value.autoCompactTokens)
            assertTrue(onMain { chat.send("/autocompact default") })
            withTimeout(10_000) { c.runManager.controls.flow(sid).first { it.autoCompactTokens == null } }
            assertTrue(onMain { chat.send("/autocompact") })
            assertTrue(chat.state.value.modeToast.orEmpty().contains("900"))
        } finally {
            server.release.countDown()
            c.runManager.stopAndJoin(sid)
            vm?.let { chat -> onMain { androidx.lifecycle.ViewModelStore().apply { put("fixture", chat); clear() } } }
            for (id in listOf(sid, other)) c.sessions.session(id)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project); root.deleteRecursively()
            c.providers.delete(provider.id); server.close(); c.settings.restorePortable(saved)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
            scenario.close()
        }
    }

    @Test fun chatUses250kAndAutomaticallyCompactsBeforeTheLimit() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
        val server = FixtureServer(requests)
        val provider = c.providers.add("Chat context fixture", ProviderType.OPENAI_COMPAT,
            "http://127.0.0.1:${server.port}/v1", "fixture-model", "fixture")
        val sid = c.sessions.createSession("Chat context", conversation = ConversationConfig(ConversationKind.CHAT))
        suspend fun send(text: String) {
            c.runManager.startRun(sid, text, emptyList(), provider, "fixture", PermissionMode.CONFIRM_ALL,
                AgentMode.ACT, 2_000, 64_000, ThinkingLevel.OFF, 2, notifyOnFinish = false)
            withTimeout(60_000) { c.runManager.live(sid).first { !it.running } }
            withTimeout(60_000) { while (c.runManager.isRunning(sid)) delay(10) }
            assertNull(c.runManager.live(sid).value.error)
            val record = c.runManager.controls.flow(sid).value
            assertTrue("status=${record.status}, completed=${record.completed}, reason=${record.reason}, requests=${requests.size}, prompt=${record.initialPrompt}", record.completed)
        }
        try {
            repeat(20) { c.sessions.addMessage(sid, ChatMessage(Role.USER, "Goal $it " + "x".repeat(40_000))) }
            send("First question")
            assertEquals(250_000, c.runManager.controls.flow(sid).value.maxContext)
            assertTrue(c.sessions.session(sid)!!.compactionSummary.isEmpty())
            repeat(4) { c.sessions.addMessage(sid, ChatMessage(Role.USER, "More $it " + "y".repeat(40_000))) }
            val originals = c.sessions.messages(sid).map { it.id }
            server.mode = "partial-error"
            c.runManager.startRun(sid, "Question during failure", emptyList(), provider, "fixture", PermissionMode.CONFIRM_ALL,
                AgentMode.ACT, 2_000, 64_000, ThinkingLevel.OFF, 2, notifyOnFinish = false)
            withTimeout(60_000) { c.runManager.live(sid).first { !it.running } }
            withTimeout(60_000) { while (c.runManager.isRunning(sid)) delay(10) }
            assertTrue(c.runManager.live(sid).value.error.orEmpty().contains("Compaction failed"))
            assertTrue(c.sessions.session(sid)!!.compactionSummary.isEmpty())
            assertTrue(c.sessions.messages(sid).map { it.id }.containsAll(originals))
            server.mode = "stop"
            send("Continue with the latest question")
            assertEquals("Preserve the goal and tool outcomes", c.sessions.session(sid)!!.compactionSummary)
            assertTrue(c.sessions.messages(sid).map { it.id }.containsAll(originals))
            assertTrue(c.sessions.historyFor(sid).second.first().text.contains("Preserve the goal"))
            assertTrue(requests.last()["messages"]!!.jsonArray.any { it.jsonObject["content"]?.jsonPrimitive?.content == "Continue with the latest question" })
        } finally {
            c.runManager.stopAndJoin(sid)
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.providers.delete(provider.id); server.close()
        }
    }

    @Test fun atomicSummaryRetainsRecentMessagesAcrossReopeningAndNeverDeletesHistory() = runBlocking {
        val context = instrumentation.targetContext
        val name = "compaction-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            var repo = SessionRepository(db)
            val sid = repo.createSession("Compaction persistence")
            repeat(12) { repo.addMessage(sid, ChatMessage(role = Role.USER, text = "message-$it")); delay(2) }
            val original = repo.messages(sid)
            val snapshot = repo.compactionSnapshot(sid)
            val before = repo.commitCompaction(sid, "Keep the goal and decisions", snapshot, 8)
            assertTrue(before <= original[4].createdAt)
            db.close(); db = Room.databaseBuilder(context, AppDatabase::class.java, name).build(); repo = SessionRepository(db)
            val history = repo.historyFor(sid).second
            assertEquals("message-4", history[1].text); assertEquals("message-11", history.last().text)
            assertTrue(history.first().text.contains("Keep the goal"))
            assertEquals(original.map { it.id }, repo.messages(sid).take(12).map { it.id })
            val resumed = RunRecovery.context(history, TaskRecord(contextAfter = before))
            assertTrue(resumed.first().text.contains("Keep the goal")); assertEquals("message-11", resumed.last().text)
            repo.addMessage(sid, ChatMessage(role = Role.USER, text = "new message"))
            val next = repo.compactionSnapshot(sid)
            repo.commitCompaction(sid, "Newer summary", next)
            assertEquals(1, repo.historyFor(sid).second.size)
            assertEquals(15, repo.messages(sid).size)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun changedHistoryRejectsCommitWithoutChangingCutoffOrLosingNewMessage() = runBlocking {
        val context = instrumentation.targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = SessionRepository(db); val sid = repo.createSession("Concurrent edit")
            repo.addMessage(sid, ChatMessage(role = Role.USER, text = "old"))
            val snapshot = repo.compactionSnapshot(sid)
            repo.addMessage(sid, ChatMessage(role = Role.USER, text = "arrived while summarizing"))
            assertTrue(runCatching { repo.commitCompaction(sid, "stale summary", snapshot) }.isFailure)
            assertEquals("", repo.session(sid)!!.compactionSummary); assertEquals(0L, repo.session(sid)!!.compactionBefore)
            assertEquals(listOf("old", "arrived while summarizing"), repo.historyFor(sid).second.map { it.text })
        } finally { db.close() }
    }

    @Test fun manualCompactionUsesBoundedRequestsAndFailureRetryAndStopKeepHistory() = runBlocking {
        val saved = c.settings.settings.first()
        val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
        val server = FixtureServer(requests)
        val provider = c.providers.add("Compaction fixture", ProviderType.OPENAI_COMPAT,
            "http://127.0.0.1:${server.port}/v1", "fixture-model", "fixture")
        c.providers.saveCatalog(provider.id, listOf(ModelEntry("fixture-model")))
        c.providers.providers.first { it.any { p -> p.id == provider.id } }
        c.settings.setModelSelectionScope(true); c.settings.setActiveSelection(provider.id, provider.model)
        c.settings.setPlanningModel(null, null); c.settings.setExecutionModel(null, null)
        val sid = c.sessions.createSession("Compaction fixture chat")
        repeat(4) { c.sessions.addMessage(sid, ChatMessage(role = Role.USER, text = "Goal-$it: preserve the implementation and saved files.")) }
        c.sessions.addMessage(sid, ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(ToolCallData("legacy", "shell", "{}"))))
        c.sessions.addMessage(sid, ChatMessage(role = Role.TOOL, toolCallId = "legacy", toolName = "shell", text = "keep this outcome\n" + "recorded tool output\n".repeat(3_500)))
        val originals = c.sessions.messages(sid)
        val vm = onMain { ChatViewModel(c, sid) }
        var scenario: ActivityScenario<ChatListTestActivity>? = null
        try {
            withTimeout(10000) { vm.state.first { it.modelSelectionReady && it.activeProvider?.id == provider.id } }
            server.mode = "partial-error"
            assertTrue(onMain { vm.send("/compact") }); awaitIdle(vm)
            assertTrue(vm.state.value.error.orEmpty().contains("422"))
            assertEquals("", c.sessions.session(sid)!!.compactionSummary)
            assertEquals(originals.map { it.id }, c.sessions.messages(sid).take(originals.size).map { it.id })
            assertTrue(c.sessions.messages(sid).last().isError)
            assertTrue(c.sessions.messages(sid).last().text.contains("Retry /compact"))
            val failureId = c.sessions.messages(sid).last().id
            withTimeout(10000) { vm.state.first { it.messages.lastOrNull()?.id == failureId } }
            scenario = ActivityScenario.launch(ChatListTestActivity::class.java)
            scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                ChatScreen(vm, onOpenDrawer = {}, onOpenFile = { _, _ -> }, onNewChat = {},
                    onOpenTerminal = {}, onOpenSubagent = {})
            } } }
            // Saved failure remains visible after dismissing the transient snackbar.
            onMain { vm.dismissError() }
            awaitNode("Retry")
            tapNode("Details")
            awaitNode("HTTP 422", contains = true)
            screenshot("compaction-failure-details")
            server.mode = "stop"
            val requestCount = requests.size
            tapNode("Retry")
            withTimeout(15000) { c.sessions.sessionFlow(sid).first { !it?.compactionSummary.isNullOrBlank() } }
            awaitIdle(vm)
            assertNull(vm.state.value.error)
            assertTrue(requests.size - requestCount >= 4)
            assertEquals("Preserve the goal and tool outcomes", c.sessions.session(sid)!!.compactionSummary)
            assertTrue(requests.all { body -> body["tools"] == null && body["messages"]!!.jsonArray.drop(1).all {
                val m = it.jsonObject; m["role"]!!.jsonPrimitive.content == "user" &&
                    m["tool_calls"] == null && m["content"]!!.jsonPrimitive.content.length <= HistoryCompactor.MAX_INPUT_CHARS
            } })
            assertEquals(originals.map { it.id }, c.sessions.messages(sid).take(originals.size).map { it.id })
            // A second attempt on fresh history can be stopped without committing a partial summary.
            repeat(3) { c.sessions.addMessage(sid, ChatMessage(role = Role.USER, text = "Next goal $it")) }
            server.mode = "stall"
            val before = c.sessions.session(sid)!!
            val start = requests.size
            assertTrue(onMain { vm.send("/compact") })
            withTimeout(10000) { while (requests.size == start) delay(20) }
            assertFalse(onMain { vm.send("Do not consume this draft") })
            onMain { vm.stop() }; server.release.countDown(); awaitIdle(vm)
            assertEquals(before.compactionSummary, c.sessions.session(sid)!!.compactionSummary)
            assertEquals(before.compactionBefore, c.sessions.session(sid)!!.compactionBefore)
            assertTrue(c.sessions.messages(sid).last().text.contains("Compaction stopped"))
            // A dangling screen must recover even when its chat was deleted before /compact.
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            assertTrue(onMain { vm.send("/compact") }); awaitIdle(vm)
            assertTrue(vm.state.value.error.orEmpty().contains("no longer available"))
        } finally {
            scenario?.close()
            onMain { vm.viewModelScope.cancel() }
            c.runManager.stopAndJoin(sid)
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.providers.delete(provider.id); server.close()
            c.settings.restorePortable(saved); c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun automaticCompactionPreservesRecentContextAndStopsOnFailedSummary() = runBlocking {
        val saved = c.settings.settings.first()
        val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
        val server = FixtureServer(requests)
        val root = java.io.File(c.appContext.cacheDir, "auto-compact-${System.nanoTime()}").apply { mkdirs() }
        val project = c.workspace.addShellProject(root.path, activate = false)
        val provider = c.providers.add("Auto compaction fixture", ProviderType.OPENAI_COMPAT,
            "http://127.0.0.1:${server.port}/v1", "fixture-model", "fixture")
        val sid = c.sessions.createSession("Auto compaction", project.id)
        try {
            repeat(20) { c.sessions.addMessage(sid, ChatMessage(role = Role.USER, text = "Recorded goal $it " + "x".repeat(12_000))); delay(2) }
            val original = c.sessions.messages(sid)
            server.mode = "partial-error"
            c.runManager.startRun(sid, "Continue failed summary", config = provider, apiKey = "fixture", imageRefs = emptyList(), thinking = ThinkingLevel.OFF,
                permissionMode = PermissionMode.CONFIRM_ALL, mode = AgentMode.ACT, maxContextTokens = 64_000,
                maxOutputTokens = 2_000, maxIterations = 2, workspaceOverride = c.workspace.forSession(sid))
            withTimeout(20000) { c.runManager.live(sid).first { !it.running && it.error != null } }
            assertEquals(1, requests.size)
            assertTrue(c.sessions.session(sid)!!.compactionSummary.isEmpty())
            assertTrue(c.sessions.messages(sid).last().text.contains("Compaction failed"))
            c.runManager.stopAndJoin(sid)
            server.mode = "stop"
            c.runManager.startRun(sid, "Continue after retry", config = provider, apiKey = "fixture", imageRefs = emptyList(), thinking = ThinkingLevel.OFF,
                permissionMode = PermissionMode.CONFIRM_ALL, mode = AgentMode.ACT, maxContextTokens = 64_000,
                maxOutputTokens = 2_000, maxIterations = 2, workspaceOverride = c.workspace.forSession(sid))
            withTimeout(30000) { c.runManager.live(sid).first { !it.running } }
            assertNull(c.runManager.live(sid).value.error)
            val record = c.runManager.controls.flow(sid).value
            assertTrue("status=${record.status}, reason=${record.reason}, prompt=${record.initialPrompt}, cancelled=${c.runManager.live(sid).value.cancelled}, requests=${requests.size}, summary=${c.sessions.session(sid)?.compactionSummary}", record.completed)
            val modelHistory = c.sessions.historyFor(sid).second
            assertTrue(modelHistory.first().text.contains("Preserve the goal"))
            assertTrue(modelHistory.any { it.text.startsWith("Recorded goal 19") })
            assertTrue(modelHistory.any { it.text == "Continue after retry" })
            assertEquals(original.map { it.id }, c.sessions.messages(sid).take(original.size).map { it.id })
            // The final production request has both the summary and recent user turns.
            val finalMessages = requests.last()["messages"]!!.jsonArray.map { it.jsonObject }
            assertTrue(finalMessages.any { it["content"]?.jsonPrimitive?.content == "Continue after retry" })
            assertTrue(finalMessages.any { it["content"]?.jsonPrimitive?.content?.contains("Auto-compacted context") == true })
        } finally {
            c.runManager.stopAndJoin(sid)
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project); root.deleteRecursively()
            c.providers.delete(provider.id); server.close(); c.settings.restorePortable(saved)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    private fun find(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow,
        predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private suspend fun awaitNode(text: String, contains: Boolean = false): AccessibilityNodeInfo = withTimeout(15000) {
        var result: AccessibilityNodeInfo? = null
        while (result == null) {
            result = find { it.isVisibleToUser && if (contains) it.text?.toString()?.contains(text) == true else it.text?.toString() == text }
            if (result == null) delay(50)
        }
        result
    }
    private suspend fun tapNode(text: String) {
        var node = awaitNode(text)
        while (!node.isClickable) node = requireNotNull(node.parent)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun screenshot(name: String) {
        val folder = java.io.File(c.appContext.getExternalFilesDir(null), "compaction-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            java.io.File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    private suspend fun awaitIdle(vm: ChatViewModel) = withTimeout(15000) {
        // launch is dispatched on main immediately; completion is the observed busy transition.
        vm.state.first { !it.busy }
        delay(50)
    }
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return result!!.getOrThrow()
    }
    private class FixtureServer(private val requests: MutableList<JsonObject>) : AutoCloseable {
        private val socket = ServerSocket(0)
        val port get() = socket.localPort
        @Volatile var mode = "stop"
        private var toolSent = false
        val release = CountDownLatch(1)
        private val worker = Thread {
            while (!socket.isClosed) try {
                socket.accept().use { client ->
                    client.soTimeout = 10000
                    val source = client.getInputStream().source().buffer()
                    val line = source.readUtf8Line().orEmpty()
                    var length = 0L
                    while (true) {
                        val h = source.readUtf8Line() ?: break
                        if (h.isBlank()) break
                        if (h.startsWith("Content-Length:", true)) length = h.substringAfter(':').trim().toLong()
                    }
                    val body = if (length > 0) source.readUtf8(length) else ""
                    val chat = line.contains("/chat/completions")
                    if (chat) requests += Json.parseToJsonElement(body).jsonObject
                    val currentMode = mode
                    if (chat && currentMode in setOf("stall", "tool-stall")) release.await(10, TimeUnit.SECONDS)
                    val response = if (!chat) """{"data":[{"id":"fixture-model"}]}""" else when {
                        currentMode == "tool-stall" && !toolSent -> {
                            toolSent = true
                            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"fixture-list","type":"function","function":{"name":"list_dir","arguments":"{\"path\":\".\"}"}}]}}]}""" + "\n\n" +
                                """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""" + "\n\ndata: [DONE]\n\n"
                        }
                        currentMode == "partial-error" -> "data: {\"choices\":[{\"delta\":{\"content\":\"partial summary\"}}]}\n\ndata: {\"error\":{\"message\":\"invalid request\",\"code\":422}}\n\n"
                        else -> "data: {\"choices\":[{\"delta\":{\"content\":\"Preserve the goal and tool outcomes\"}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10}}\n\ndata: [DONE]\n\n"
                    }
                    val bytes = response.toByteArray()
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: ${if (chat) "text/event-stream" else "application/json"}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            } catch (_: Exception) { /* cancellation closes an in-flight fixture request */ }
        }.apply { isDaemon = true; start() }
        override fun close() { release.countDown(); socket.close(); worker.join(1000) }
    }
}
