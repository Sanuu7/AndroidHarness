package com.androidharness.app.ui.settings

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.agent.*
import com.androidharness.app.chatgpt.ChatGptProvider
import com.androidharness.app.chatgpt.ChatGptThinking
import com.androidharness.app.ui.chat.components.MainHeader
import com.androidharness.app.core.*
import com.androidharness.app.llm.*
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatGptDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun thinkingMenuShowsOnlySupportedChatGptLevels() {
        val picked = java.util.concurrent.atomic.AtomicReference<ThinkingLevel>()
        val model = ModelEntry("gpt-6.1-sol")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                MainHeader(sessionTitle = "ChatGPT check", busy = false, pickerLabel = model.id, mode = AgentMode.ACT,
                    thinkingLevel = ChatGptThinking.selected(model, ThinkingLevel.ULTRA)!!,
                    thinkingLevels = ChatGptThinking.levels(model), permissionMode = PermissionMode.CONFIRM_RISKY,
                    canUndo = false, onOpenDrawer = {}, onPickModel = {}, onOpenTerminal = {}, onSetThinking = picked::set,
                    onSetPermission = {}, onSetMode = {}, onOpenContext = {}, onOpenUndo = {}, onOpenFiles = {})
            } } }
            clickText("Thinking level")
            waitForText("Low")
            waitForText("High")
            waitForText("Max")
            val root = instrumentation.uiAutomation.rootInActiveWindow
            assertNull(find(root, "Off"))
            assertNull(find(root, "Minimal"))
            assertNull(find(root, "Ultra"))
            clickText("High")
            assertEquals(ThinkingLevel.HIGH, picked.get())
            println("CHATGPT_THINKING_PICKER_OK: supported choices only and High selection works")
        }
    }

    /** Explicitly requested live check after the user signs in; never reads or prints credentials. */
    @Test fun signedInAccountReadsFileWithLiveModel() = runBlocking {
        assumeTrue("Pass liveChatGpt=true after signing in", InstrumentationRegistry.getArguments().getString("liveChatGpt") == "true")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
        var config = withTimeout(10_000) { container.providers.providers.first { list -> list.any { com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(it.id) } } }
            .first { com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(it.id) }
        report("CHATGPT_CACHED_MODEL_IDS: ${container.chatGpt.state.value.accounts.first { it.providerId == config.id }.models.map { it.id }}")
        if (InstrumentationRegistry.getArguments().getString("refreshChatGpt") == "true") {
            println("CHATGPT_APP_INTERNET_PERMISSION: ${instrumentation.targetContext.checkSelfPermission(android.Manifest.permission.INTERNET)}")
            println("CHATGPT_APP_DNS_OK: ${java.net.InetAddress.getAllByName("api.openai.com").size} addresses")
            val fresh = container.chatGpt.refreshModels(config.id)
            report("CHATGPT_FRESH_MODEL_IDS: ${fresh.map { it.id }}")
        }
        InstrumentationRegistry.getArguments().getString("chatGptModel")?.let { chosen ->
            check(container.chatGpt.state.value.accounts.first { it.providerId == config.id }.models.any { it.id == chosen }) { "Requested model is not available to this account." }
            config = config.copy(model = chosen)
        }
        val root = File(instrumentation.targetContext.cacheDir, "chatgpt-live-check-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(root, "fixture.txt").writeText("ANDROIDHARNESS_CHATGPT_TOOL_OK")
            val events = mutableListOf<AgentEvent>()
            withTimeout(180_000) {
                container.engine.run(sessionId = "chatgpt-live-fixture", turnId = "chatgpt-live-turn", config = config,
                    apiKey = "managed-oauth", history = listOf(ChatMessage(Role.USER, "Use read_file to read fixture.txt. Reply only with the exact file contents.")),
                    permissionMode = { PermissionMode.CONFIRM_RISKY }, sessionAllowedTools = mutableSetOf(), workspace = FileFs(root),
                    options = RequestOptions(thinking = ThinkingLevel.OFF), maxContextTokens = 128_000, mode = AgentMode.ACT,
                    maxIterations = 3, repoMapEnabled = false,
                ).collect { events += it }
            }
            assertFalse("${events.filterIsInstance<AgentEvent.Error>()}; failureType=${(ProviderFactory.chatGptProvider as? ChatGptProvider)?.lastFailureType}", events.any { it is AgentEvent.Error })
            val detail = "model=${config.model}; tools=${events.filterIsInstance<AgentEvent.ToolStarted>().map { it.call.name }}; replies=${events.filterIsInstance<AgentEvent.AssistantCommitted>().map { it.message.text.take(800) }}; stream=${(ProviderFactory.chatGptProvider as? ChatGptProvider)?.lastResponseSummary}; finished=${events.filterIsInstance<AgentEvent.Finished>()}"
            report("CHATGPT_LIVE_RESULT: $detail")
            assertTrue(detail, events.filterIsInstance<AgentEvent.ToolStarted>().any { it.call.name == "read_file" })
            assertTrue(events.filterIsInstance<AgentEvent.ToolFinished>().any { it.result.ok && it.result.output.contains("ANDROIDHARNESS_CHATGPT_TOOL_OK") })
            assertTrue(events.filterIsInstance<AgentEvent.AssistantCommitted>().any { it.message.text.contains("ANDROIDHARNESS_CHATGPT_TOOL_OK") })
            println("CHATGPT_LIVE_TOOL_OK: model=${config.model}, read_file succeeded, response completed")
        } finally { root.deleteRecursively() }
        } finally { scenario.close() }
    }

    @Test fun connectedAccountsIsFirstAndShowsSignIn() {
        assertEquals(SettingsPage.CONNECTED_ACCOUNTS, matchingSettingsEntries("").first().page)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme { SettingsScreen(container, onBack = {}) } } }
            clickText("Connected accounts")
            waitForText("Continue with ChatGPT")
            waitForText("Manage usage")
            println("CHATGPT_SETTINGS_OK: first settings section opens and shows sign-in and usage controls")
        }
    }

    @Test fun subscriptionProviderExecutesRealReadToolAndReplaysResult() = runBlocking {
        val root = File(instrumentation.targetContext.cacheDir, "chatgpt-tool-device-check-${System.nanoTime()}").apply { mkdirs() }
        val previous = ProviderFactory.chatGptProvider
        var requests = 0
        try {
            File(root, "fixture.txt").writeText("Subscription tool round trip works")
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
                val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                assertFalse(body.containsKey("max_output_tokens"))
                assertEquals("namespace", body.getValue("tools").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
                val content = if (requests++ == 0) {
                    """data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call","call_id":"device-call","name":"read_file","namespace":"harness","arguments":"{\"path\":\"fixture.txt\"}"}]}}

"""
                } else {
                    val result = body.getValue("input").jsonArray.map { it.jsonObject }.single { it["type"]?.jsonPrimitive?.contentOrNull == "function_call_output" }
                    assertTrue(result.getValue("output").jsonPrimitive.content.contains("Subscription tool round trip works"))
                    """data: {"type":"response.output_text.delta","delta":"Read the file successfully."}

data: {"type":"response.completed","response":{"status":"completed","output":[]}}

"""
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(content.toResponseBody("text/event-stream".toMediaType())).build()
            }.build()
            ProviderFactory.chatGptProvider = ChatGptProvider({ _, _ -> "fixture-access" }, client)
            val config = ProviderConfig("chatgpt:device-fixture", "ChatGPT · fixture", ProviderType.OPENAI_RESPONSES, "https://api.openai.com/v1", "fixture-model")
            val events = mutableListOf<AgentEvent>()
            withTimeout(20_000) {
                container.engine.run(sessionId = "chatgpt-device-fixture", turnId = "chatgpt-device-turn", config = config,
                    apiKey = "managed-oauth", history = listOf(ChatMessage(Role.USER, "Read fixture.txt")),
                    permissionMode = { PermissionMode.CONFIRM_RISKY }, sessionAllowedTools = mutableSetOf(),
                    workspace = FileFs(root), options = RequestOptions(), maxContextTokens = 128_000, mode = AgentMode.ACT,
                    maxIterations = 3, repoMapEnabled = false,
                ).collect { events += it }
            }
            assertFalse(events.filterIsInstance<AgentEvent.Error>().toString(), events.any { it is AgentEvent.Error })
            assertTrue(events.filterIsInstance<AgentEvent.ToolFinished>().single().result.ok)
            assertEquals(2, requests)
            assertTrue(events.filterIsInstance<AgentEvent.AssistantCommitted>().any { it.message.text == "Read the file successfully." })
            println("CHATGPT_TOOL_ROUND_TRIP_OK: native read_file ran, result was replayed, final answer completed")
        } finally { ProviderFactory.chatGptProvider = previous; root.deleteRecursively() }
    }

    private fun find(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let { find(it, text)?.let { found -> return found } }
        return null
    }
    private fun report(value: String) {
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\n$value\n") })
    }
    private fun waitForText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, text) }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing settings control: $text")
    }
    private fun clickText(text: String) {
        var node: AccessibilityNodeInfo? = waitForText(text)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
}
