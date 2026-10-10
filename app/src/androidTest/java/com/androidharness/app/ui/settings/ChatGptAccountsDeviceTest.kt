package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.agent.*
import com.androidharness.app.chatgpt.*
import com.androidharness.app.core.*
import com.androidharness.app.llm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ChatGptAccountsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun accountControlsSelectToggleAndChooseFallbackAtPhoneWidth() {
        var ui by mutableStateOf(ChatGptAccountState(accounts = listOf(
            ChatGptAccount("chatgpt:primary", "alex@example.test · 1", true, listOf(ModelEntry("sol", displayName = "GPT Sol"))),
            ChatGptAccount("chatgpt:backup", "backup@example.test · 2", true, listOf(ModelEntry("luna", displayName = "GPT Luna"))),
            ChatGptAccount("chatgpt:expired", "other@example.test · 3", false, emptyList()),
        )))
        var selected by mutableStateOf("chatgpt:primary")
        var model by mutableStateOf("sol")
        val manage = mutableListOf<String>()
        val activity = instrumentation.startActivitySync(Intent(c.appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync { activity.setContent {
                val dark = InstrumentationRegistry.getArguments().getString("dark") == "true"
                com.androidharness.app.ui.theme.HarnessTheme(themeMode = if (dark)
                    com.androidharness.app.data.ThemeMode.DARK else com.androidharness.app.data.ThemeMode.LIGHT) {
                    Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                        .verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text("Connected accounts", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))
                        ChatGptAccountsPanel(ui, selected, model, null, null, onAdd = { manage += "add" },
                            onCancelSignIn = {}, onAutoSwitch = { ui = ui.copy(autoSwitch = it) },
                            onSelect = { selected = it.providerId; model = it.models.first().id },
                            onFallback = { account, enabled, fallback -> ui = ui.copy(accounts = ui.accounts.map {
                                if (it.providerId == account.providerId) it.copy(useForAutoSwitch = enabled, fallbackModel = fallback) else it
                            }) }, onRefresh = { manage += "refresh" }, onCheckModels = { manage += "check" },
                            onReconnect = { manage += "reconnect" }, onSignOut = { manage += "signout" }, onUsage = { manage += "usage" })
                    } }
                }
            } }
            await("Auto-switch at usage limit")
            screenshot("chatgpt-accounts-default")
            tap("Auto-switch at usage limit")
            assertTrue(ui.autoSwitch)
            screenshot("chatgpt-accounts-switching")
            scrollTo("backup@example.test · 2"); tap("backup@example.test · 2")
            instrumentation.waitForIdleSync()
            assertEquals("chatgpt:backup", selected); assertEquals("luna", model)
            scrollTo("Fallback model for backup@example.test · 2")
            tap("Fallback model for backup@example.test · 2"); tap("GPT Luna", last = true)
            instrumentation.waitForIdleSync()
            assertEquals("luna", ui.accounts[1].fallbackModel)
            screenshot("chatgpt-accounts-fallback")
            scrollTo("Manage backup@example.test · 2"); tap("Manage backup@example.test · 2")
            await("Refresh models"); screenshot("chatgpt-accounts-menu")
            tap("Refresh models"); assertEquals("refresh", manage.last())
            scrollTo("Limits & usage in ChatGPT"); tap("Limits & usage in ChatGPT")
            assertEquals("usage", manage.last())
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun quotaSwitchPreservesToolsAndQueuedMessageAndRecordsActualModel() = runBlocking {
        val originalProvider = ProviderFactory.chatGptProvider
        val originalModels = ModelCatalog.chatGptModels
        val a = ProviderConfig("chatgpt:fixture-a", "ChatGPT · Primary", ProviderType.OPENAI_RESPONSES, ChatGptProtocol.RESOURCE, "sol")
        val b = a.copy(id = "chatgpt:fixture-b", name = "ChatGPT · Backup", model = "luna")
        val attempts = mutableListOf<Pair<String, String>>()
        val secondAccount = CountDownLatch(1)
        val continueResponse = CountDownLatch(1)
        val aCount = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val model = Json.parseToJsonElement(body).jsonObject.getValue("model").jsonPrimitive.content
            val account = request.header("Authorization")!!.removePrefix("Bearer ")
            synchronized(attempts) { attempts += account to model }
            val quota = account == "fixture-a" && aCount.incrementAndGet() == 2
            val output = if (account == "fixture-a") {
                """[{"type":"reasoning","id":"reason1","encrypted_content":"opaque-primary","summary":[]},{"type":"function_call","id":"item1","call_id":"read1","name":"read_file","namespace":"harness","arguments":"{\"path\":\"saved.txt\"}"}]"""
            } else {
                secondAccount.countDown(); check(continueResponse.await(30, TimeUnit.SECONDS))
                assertFalse("Other account's encrypted reasoning must stay private", body.contains("opaque-primary"))
                assertTrue(body.contains("Saved work"))
                """[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Completed with Luna"}]}]"""
            }
            val content = if (quota) """{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}"""
                else (if (account == "fixture-a") "" else "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Completed with Luna\"}\n\n") +
                    "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":$output,\"usage\":{\"input_tokens\":20,\"output_tokens\":4}}}\n\n"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (quota) 429 else 200).message("fixture")
                .body(content.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val raw = ChatGptProvider({ id, _ -> if (id == a.id) "fixture-a" else "fixture-b" }, client)
        ProviderFactory.chatGptProvider = ChatGptAutoSwitch(raw, { true }, { _, tried, _, _, _ -> b.takeIf { it.id !in tried } })
        ModelCatalog.chatGptModels = { listOf(ModelEntry(if (it == a.id) "sol" else "luna")) }
        val root = File(c.appContext.filesDir, "chatgpt-queue-${System.nanoTime()}").apply { mkdirs() }
        File(root, "saved.txt").writeText("Saved work")
        val previousProject = c.workspace.currentProjectOnce()
        val project = c.workspace.addShellProject(root.absolutePath)
        val session = c.sessions.createSession("Account switching", project.id)
        try {
            c.runManager.startRun(session, "Read saved.txt and finish", emptyList(), a, "managed", PermissionMode.FULL_AUTO,
                AgentMode.ACT, 2000, 100000, ThinkingLevel.OFF, 5, workspaceOverride = c.workspace.fsFor(project))
            assertTrue(withContext(Dispatchers.IO) { secondAccount.await(30, TimeUnit.SECONDS) })
            val restored = TaskControlStore(File(c.appContext.filesDir, "task-controls")).flow(session).value
            assertEquals(b.id, restored.provider?.id)
            assertEquals(setOf(a.id, b.id), restored.usedProviderIds)
            c.runManager.inject(session, "Explain what you checked")
            withTimeout(10000) { while (c.runManager.controls.flow(session).value.queue.isEmpty()) delay(50) }
            continueResponse.countDown()
            try { withTimeout(30000) { while (c.runManager.isRunning(session) || c.runManager.controls.flow(session).value.queue.isNotEmpty() ||
                synchronized(attempts) { attempts.size < 4 }) delay(50) } }
            catch (e: TimeoutCancellationException) {
                error("Queue failed: ${c.runManager.controls.flow(session).value}; requests=${synchronized(attempts) { attempts.toList() }}")
            }
            val saved = c.runManager.controls.flow(session).value
            assertEquals(b.id, saved.provider?.id); assertEquals("luna", saved.provider?.model)
            val history = c.sessions.messages(session)
            assertEquals(1, history.count { it.role == Role.USER && it.text == "Read saved.txt and finish" })
            assertEquals(1, history.count { it.role == Role.TOOL && it.toolCallId == "read1" })
            assertEquals(1, history.count { it.role == Role.USER && it.text == "Explain what you checked" })
            assertTrue(history.any { it.role == Role.SYSTEM && it.text.contains("switched to") })
            assertEquals(listOf("fixture-a" to "sol", "fixture-a" to "sol", "fixture-b" to "luna", "fixture-b" to "luna"),
                synchronized(attempts) { attempts.toList() })
            assertTrue(saved.queue.isEmpty())
            val usage = c.sessions.usageEventsFor(session).first()
            assertEquals(listOf("sol", "luna", "luna"), usage.map { it.model })
            assertEquals(listOf(a.name, b.name, b.name), usage.map { it.providerName })
        } finally {
            continueResponse.countDown()
            c.runManager.stopAndJoin(session)
            ProviderFactory.chatGptProvider = originalProvider
            ModelCatalog.chatGptModels = originalModels
            c.sessions.session(session)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project)
            c.workspace.setActiveProject(previousProject.id)
            root.deleteRecursively()
        }
    }

    @Test fun allAccountsLimitedPauseWithoutDrainingOrLosingQueue() = runBlocking {
        val previousProvider = ProviderFactory.chatGptProvider
        val previousModels = ModelCatalog.chatGptModels
        val previousSettings = c.settings.settings.first()
        val previousProject = c.workspace.currentProjectOnce()
        val root = File(c.appContext.cacheDir, "all-limited-${System.nanoTime()}").apply { mkdirs() }
        val project = c.workspace.addShellProject(root.path)
        val session = c.sessions.createSession("All accounts limited", project.id)
        val a = ProviderConfig("chatgpt:limited-a", "ChatGPT · Primary", ProviderType.OPENAI_RESPONSES, ChatGptProtocol.RESOURCE, "sol")
        val b = a.copy(id = "chatgpt:limited-b", name = "ChatGPT · Backup", model = "luna")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempts = mutableListOf<String>()
        val raw = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>,
                tools: List<ToolSchema>, options: RequestOptions) = kotlinx.coroutines.flow.flow {
                attempts += config.id
                if (config.id == a.id) { entered.complete(Unit); release.await() }
                emit(StreamEvent.Failure("Usage limit reached", 429, ChatGptAutoSwitch.USAGE_LIMIT, false))
            }
        }
        ProviderFactory.chatGptProvider = ChatGptAutoSwitch(raw, { true }, { _, tried, _, _, _ -> b.takeIf { it.id !in tried } })
        ModelCatalog.chatGptModels = { listOf(ModelEntry("sol"), ModelEntry("luna")) }
        try {
            c.settings.setActiveSelection(a.id, a.model)
            c.runManager.startRun(session, "Continue saved work", emptyList(), a, "managed", PermissionMode.FULL_AUTO,
                AgentMode.ACT, 2000, 100000, ThinkingLevel.OFF, 5, workspaceOverride = c.workspace.fsFor(project))
            withTimeout(10000) { entered.await() }
            c.runManager.inject(session, "Run this afterward")
            withTimeout(10000) { while (c.runManager.controls.flow(session).value.queue.isEmpty()) delay(50) }
            release.complete(Unit)
            withTimeout(10000) { while (c.runManager.isRunning(session)) delay(50) }
            val saved = c.runManager.controls.flow(session).value
            assertEquals(listOf(a.id, b.id), attempts)
            assertEquals("paused", saved.status); assertTrue(saved.resumable)
            assertEquals(b, saved.provider)
            assertEquals(listOf("Run this afterward"), saved.queue.map { it.text })
            assertTrue(saved.reason!!.contains("No other ChatGPT account"))
            assertEquals(saved.queue, TaskControlStore(File(c.appContext.filesDir, "task-controls")).flow(session).value.queue)
        } finally {
            release.complete(Unit)
            c.runManager.stopAndJoin(session)
            ProviderFactory.chatGptProvider = previousProvider
            ModelCatalog.chatGptModels = previousModels
            c.sessions.session(session)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project); c.workspace.setActiveProject(previousProject.id)
            c.settings.restorePortable(previousSettings)
            c.settings.setActiveProvider(previousSettings.activeProviderId)
            c.settings.setActiveModel(previousSettings.activeModel)
            root.deleteRecursively()
        }
    }

    private fun findAll(node: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        return (if (node.isVisibleToUser && test(node)) listOf(node) else emptyList()) + (0 until node.childCount).flatMap { findAll(node.getChild(it), test) }
    }
    private fun freshRoot(): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }
    private fun await(text: String, last: Boolean = false): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) {
            val nodes = findAll(freshRoot()) { it.text?.toString() == text || it.contentDescription?.toString() == text }
            (if (last) nodes.lastOrNull() else nodes.firstOrNull())?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing control: $text")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        val bounds = android.graphics.Rect(); target?.getBoundsInScreen(bounds)
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            screenshot("chatgpt-accounts-tap-failure")
            "Control cannot be tapped: text=${node.text}; bounds=$bounds; enabled=${target?.isEnabled}; visible=${target?.isVisibleToUser}; actions=${target?.actionList}"
        }
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 3000)
    }
    private fun tap(text: String, last: Boolean = false) = click(await(text, last))
    private fun scrollTo(text: String) {
        repeat(12) {
            val root = freshRoot()
            val node = findAll(root) { it.text?.toString() == text || it.contentDescription?.toString() == text }.firstOrNull()
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                instrumentation.waitForIdleSync(); SystemClock.sleep(400)
                return
            }
            findAll(root) { it.isScrollable }.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(250)
        }
        repeat(12) {
            val root = freshRoot()
            if (findAll(root) { it.text?.toString() == text || it.contentDescription?.toString() == text }.isNotEmpty()) return
            findAll(root) { it.isScrollable }.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(250)
        }
        await(text)
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(250)
        val folder = File(c.appContext.getExternalFilesDir(null), "chatgpt-account-screenshots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
