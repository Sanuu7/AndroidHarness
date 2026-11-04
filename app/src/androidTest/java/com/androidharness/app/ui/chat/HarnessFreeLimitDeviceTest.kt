package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.llm.*
import com.androidharness.app.ui.chat.components.ProviderFailureNotice
import com.androidharness.app.ui.chat.components.ChatRecovery
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okio.buffer
import okio.source
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class HarnessFreeLimitDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun quotaNoticeOpensProvidersAndContinueUsesTheChosenProviderInCodeAndChat() = runBlocking {
        val saved = c.settings.settings.first()
        val server = FixtureServer()
        val target = c.providers.add("Quota fixture provider", ProviderType.OPENAI_COMPAT,
            "http://127.0.0.1:${server.port}/v1", "fixture-model", "fixture")
        c.providers.saveCatalog(target.id, listOf(ModelEntry("fixture-model")))
        try {
            c.settings.setModelSelectionScope(true)
            c.settings.setFallbackPolicy(FallbackPolicy())
            for (kind in listOf(ConversationKind.CODE, ConversationKind.CHAT)) {
                c.settings.setActiveSelection(HarnessProvider.ID, HarnessProvider.DEFAULT_MODEL)
                val sid = c.sessions.createSession("Quota notice fixture", conversation = ConversationConfig(kind))
                val turn = "quota-turn-$sid"
                val message = HarnessFreeLimit.message(HarnessFreeLimit.Notice(HarnessFreeLimit.Kind.QUOTA))
                c.sessions.addMessage(sid, ChatMessage(Role.USER, "Continue the saved task", id = "quota-user-$sid"), turn)
                val workspace = c.workspace.forSession(sid)
                c.runManager.controls.update(sid) { it.copy(status = "paused", reason = message,
                    workspacePath = workspace.displayPath, workspacePaths = listOf(workspace.displayPath),
                    provider = HarnessProvider.config, initialPrompt = "Continue the saved task",
                    initialPromptId = "quota-user-$sid", turnId = turn, maxOutput = 1_000) }
                (c.runManager.live(sid) as MutableStateFlow).update { it.copy(error = message) }
                val vm = onMain { ChatViewModel(c, sid) }
                try {
                    withTimeout(15_000) { vm.state.first { it.conversationReady && it.modelSelectionReady && it.activeProviderId == HarnessProvider.ID } }
                    ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                        scenario.onActivity { activity -> activity.setContent {
                            HarnessTheme(ThemeMode.DARK, dynamicColor = false) {
                                ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {})
                            }
                        } }
                        await("Harness free quota limit has been exhausted")
                        assertNull(find("PROMOTION_MODEL_LIMIT_REACHED"))
                        screenshot("harness-free-quota-${kind.name.lowercase()}")
                        tap("Switch Provider")
                        await("Providers")
                        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                            if (node.isEditable) return node
                            for (i in 0 until node.childCount) node.getChild(i)?.let { editable(it)?.let { match -> return match } }
                            return null
                        }
                        val search = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow?.let(::editable))
                        assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, target.name)
                        }))
                        instrumentation.waitForIdleSync()
                        tap(target.name)
                        checkNotNull(withTimeoutOrNull(15_000) { vm.state.first { it.activeProviderId == target.id && !it.modelSelectionBusy } }) {
                            "Provider selection failed: active=${vm.state.value.activeProviderId}, expected=${target.id}, error=${vm.state.value.error}"
                        }
                        assertNull(find("Harness free quota limit has been exhausted"))
                        onMain { vm.resumeInterruptedTask() }
                        checkNotNull(withTimeoutOrNull(30_000) { while (!c.runManager.controls.flow(sid).value.completed) delay(30); true }) {
                            "Continue failed: error=${vm.state.value.error}, record=${c.runManager.controls.flow(sid).value.reason}"
                        }
                        assertEquals(target.id, c.runManager.controls.flow(sid).value.provider?.id)
                        assertNull(c.runManager.live(sid).value.error)
                        assertTrue(c.sessions.messages(sid).any { it.text == "Continued with the chosen provider." })
                    }
                } finally {
                    c.runManager.stopAndJoin(sid)
                    onMain { ViewModelStore().apply { put("quota", vm); clear() } }
                    c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
                }
            }
            assertEquals(2, server.requests.get())
        } finally {
            c.providers.delete(target.id); server.close(); c.settings.restorePortable(saved)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun harnessFreeLimitsHideRecoveryCardsButOtherProvider429KeepsThem() {
        val reason = mutableStateOf(HarnessFreeLimit.message(HarnessFreeLimit.Notice(HarnessFreeLimit.Kind.QUOTA)))
        val provider = mutableStateOf(HarnessProvider.config)
        var resumed = 0
        var settingsOpened = 0
        var continued = 0
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                HarnessTheme(ThemeMode.LIGHT, dynamicColor = false) {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.Text("Conversation remains available")
                        TaskProgressCard(TaskRecord(status = "paused", reason = reason.value, provider = provider.value), false,
                            onResume = { resumed++ }, onSettings = { settingsOpened++ })
                        ChatRecovery(reason.value, onContinue = { continued++ }, onRetry = {}, provider = provider.value)
                    }
                }
            } }
            await("Conversation remains available")
            for (kind in listOf(HarnessFreeLimit.Kind.QUOTA, HarnessFreeLimit.Kind.TEMPORARY)) {
                onMain { reason.value = HarnessFreeLimit.message(HarnessFreeLimit.Notice(kind)) }
                instrumentation.waitForIdleSync()
                assertNull(find(c.appContext.getString(com.androidharness.app.R.string.ui_resume_task)))
                assertNull(find(c.appContext.getString(com.androidharness.app.R.string.ui_context_limits)))
                assertNull(find(c.appContext.getString(com.androidharness.app.R.string.chat_continue_reply)))
                assertNull(find(c.appContext.getString(com.androidharness.app.R.string.chat_response_interrupted)))
                assertNull(find(reason.value))
            }
            onMain {
                reason.value = "HTTP 429: {\"error\":{\"code\":\"PROMOTION_MODEL_LIMIT_REACHED\",\"message\":\"Come back later\"}}"
            }
            instrumentation.waitForIdleSync()
            assertNull(find(c.appContext.getString(com.androidharness.app.R.string.ui_resume_task)))
            assertNull(find(reason.value))
            onMain {
                provider.value = ProviderConfig("other", "Other provider", ProviderType.OPENAI_COMPAT, "https://example.com", "model")
                reason.value = "HTTP 429: Another provider is rate limited"
            }
            await(reason.value)
            tap(c.appContext.getString(com.androidharness.app.R.string.ui_resume_task))
            tap(c.appContext.getString(com.androidharness.app.R.string.ui_context_limits))
            tap(c.appContext.getString(com.androidharness.app.R.string.chat_continue_reply))
            assertEquals(1, resumed)
            assertEquals(1, settingsOpened)
            assertEquals(1, continued)
        }
    }

    @Test fun largeTextTemporaryNoticeShowsOnlyTheReportedDelayAndWaitDismissesIt() {
        val error = mutableStateOf<String?>(HarnessFreeLimit.message(
            HarnessFreeLimit.Notice(HarnessFreeLimit.Kind.TEMPORARY, 90)))
        var switches = 0
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    HarnessTheme(ThemeMode.LIGHT, dynamicColor = false) {
                        val snackbar = remember { SnackbarHostState() }
                        ProviderFailureNotice("large-font", error.value, false, snackbar,
                            onDismiss = { error.value = null }, onSwitchProvider = { switches++ })
                    }
                }
            } }
            await("Harness free models are temporarily limited")
            await("The service suggests trying again in about 2 minutes.")
            screenshot("harness-free-quota-large-text")
            tap("I’ll wait")
            assertNull(error.value)
            assertEquals(0, switches)
        }
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && !node.isEditable && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { match -> return match } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun await(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) { find(text)?.let { return it }; SystemClock.sleep(50) }
        error("Missing UI: $text")
    }
    private fun tap(text: String) {
        var node = await(text)
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        instrumentation.waitForIdleSync()
        node = await(text)
        while (!node.isClickable) node = requireNotNull(node.parent)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(250)
    }
    private fun screenshot(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(c.appContext.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return result!!.getOrThrow()
    }
    private class FixtureServer : AutoCloseable {
        private val socket = ServerSocket(0)
        val port get() = socket.localPort
        val requests = AtomicInteger()
        private val worker = Thread {
            while (!socket.isClosed) try {
                socket.accept().use { client ->
                    client.soTimeout = 10_000
                    val source = client.getInputStream().source().buffer()
                    val line = source.readUtf8Line().orEmpty()
                    var length = 0L
                    while (true) {
                        val header = source.readUtf8Line() ?: break
                        if (header.isBlank()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toLong()
                    }
                    if (length > 0) source.skip(length)
                    val chat = line.startsWith("POST ")
                    if (chat) requests.incrementAndGet()
                    val body = if (chat) "data: {\"choices\":[{\"delta\":{\"content\":\"Continued with the chosen provider.\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                        else """{"data":[{"id":"fixture-model"}]}"""
                    val bytes = body.toByteArray()
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: ${if (chat) "text/event-stream" else "application/json"}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close(); worker.join(1_000) }
    }
}
