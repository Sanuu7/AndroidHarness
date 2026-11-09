package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.llm.*
import com.androidharness.app.tools.*
import com.androidharness.app.ui.settings.ChatHistoryAccessOption
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatHistoryDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun searchFindsTitlesAndOlderMessagesAndCardsJumpAcrossBothModes(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val ids = mutableListOf<String>()
        val store = ViewModelStore()
        try {
            for (sourceKind in ConversationKind.entries) {
                val targetKind = if (sourceKind == ConversationKind.CHAT) ConversationKind.CODE else ConversationKind.CHAT
                val unique = "history${System.nanoTime()}"
                val target = c.sessions.createSession("Archive $unique", conversation = ConversationConfig(targetKind)).also(ids::add)
                val anchorText = "Exact older message $unique"
                val anchor = c.sessions.addMessage(target, ChatMessage(Role.USER, anchorText), "anchor")
                repeat(85) { i ->
                    c.sessions.addMessage(target, ChatMessage(Role.USER, "Later question $i"), "later-$i")
                    c.sessions.addMessage(target, ChatMessage(Role.ASSISTANT, "Later answer $i"), "later-$i")
                }
                val hidden = c.sessions.addMessage(target, ChatMessage(Role.TOOL, unique, toolCallId = "internal"), "hidden")
                assertTrue(c.sessions.searchRecallMessages("Archive $unique", false, 64, 0).any { it.sessionId == target })
                val hits = c.sessions.searchRecallMessages("Exact older message $unique", false, 64, 0)
                assertEquals(anchor, hits.single().messageId)
                assertEquals(targetKind, hits.single().kind)
                assertFalse(c.sessions.searchRecallMessages(unique, true, 200, 0).any { it.messageId == hidden })
                val source = c.sessions.createSession("Find a conversation", conversation = ConversationConfig(sourceKind)).also(ids::add)
                val tool = SearchChatsTool(c.sessions::searchRecallMessages)
                val result = tool.execute(buildJsonObject { put("query", "Exact older message $unique") },
                    ToolContext(FileFs(c.appContext.cacheDir), sessionId = source))
                val turn = "lookup-$source"
                c.sessions.addMessage(source, ChatMessage(Role.USER, "Find my older conversation"), turn)
                c.sessions.addMessage(source, ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("lookup", "search_chats", "{}"))), turn)
                c.sessions.addMessage(source, ChatMessage(Role.TOOL, result.output, toolName = "search_chats", toolCallId = "lookup",
                    generation = GenerationMetadata(chatHistory = result.chatHistory)), turn)
                c.sessions.addMessage(source, ChatMessage(Role.ASSISTANT, "I found the matching conversation."), turn)
                assertEquals(anchor, c.sessions.messages(source).first { it.role == Role.TOOL }.generation.chatHistory!!.matches.single().messageId)

                val opened = java.util.concurrent.atomic.AtomicReference<Pair<String, String?>?>()
                ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val current = mutableStateOf(ChatViewModel(c, source).also { store.put(source, it) })
                        val message = mutableStateOf<String?>(null)
                        activity.setContent { HarnessTheme(dynamicColor = false) {
                            key(current.value) { ChatScreen(current.value, {}, { _, _ -> }, {}, {}, onOpenSubagent = {},
                                searchMessageId = message.value, onNavigateToMessage = { sid, mid ->
                                    opened.set(sid to mid)
                                    current.value = ChatViewModel(c, sid).also { store.put(sid, it) }
                                    message.value = mid
                                }) }
                        } }
                    }
                    await("Open matching message")
                    screenshot("history-card-${sourceKind.name.lowercase()}")
                    tap("Open matching message")
                    val deadline = SystemClock.uptimeMillis() + 10_000
                    while (opened.get() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                    assertEquals(target to anchor, opened.get())
                    while (nodes().any { it.text?.contains("Open matching message") == true } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                    assertFalse(nodes().any { it.text?.contains("Open matching message") == true })
                    await(anchorText)
                    screenshot("history-jump-${targetKind.name.lowercase()}")
                }
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            ids.forEach { c.sessions.session(it)?.let { session -> c.sessions.deleteSession(session) } }
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun bothSwitchesPersistIndependently(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        try {
            c.settings.setChatSearchCodeChats(true); c.settings.setCodeSearchChatChats(true)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme(dynamicColor = false) {
                    val settings by c.settings.settings.collectAsState(initial = saved.copy(chatSearchCodeChats = true, codeSearchChatChats = true))
                    val scope = rememberCoroutineScope()
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ChatHistoryAccessOption(true, settings.chatSearchCodeChats) { value -> scope.launch { c.settings.setChatSearchCodeChats(value) } }
                        ChatHistoryAccessOption(false, settings.codeSearchChatChats) { value -> scope.launch { c.settings.setCodeSearchChatChats(value) } }
                    }
                } } }
                tap("Let Chat search Code chats")
                withTimeout(5_000) { c.settings.settings.first { !it.chatSearchCodeChats } }
                assertTrue(c.settings.settings.first().codeSearchChatChats)
                tap("Let Code search Chat chats")
                withTimeout(5_000) { c.settings.settings.first { !it.codeSearchChatChats } }
                val reloaded = SettingsRepository(c.appContext).settings.first()
                assertFalse(reloaded.chatSearchCodeChats); assertFalse(reloaded.codeSearchChatChats)
                screenshot("history-settings")
            }
        } finally {
            c.settings.setChatSearchCodeChats(saved.chatSearchCodeChats)
            c.settings.setCodeSearchChatChats(saved.codeSearchChatChats)
        }
    }

    @Test fun historyToolsExecuteAndKeepCardReceiptsInBothModes(): Unit = runBlocking {
        val ids = mutableListOf<String>()
        try {
            for (kind in ConversationKind.entries) {
                val conversation = ConversationConfig(kind)
                val sid = c.sessions.createSession("History engine test", conversation = conversation).also(ids::add)
                var round = 0
                val provider = object : LlmProvider {
                    override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                        assertTrue(tools.any { it.name == "search_chats" })
                        if (round++ == 0) emit(StreamEvent.ToolCallReady(ToolCallData("lookup", "search_chats", """{"query":"topic"}""")))
                        else emit(StreamEvent.TextDelta("Found it."))
                        emit(StreamEvent.Done(if (round == 1) "tool_calls" else "stop"))
                    }
                }
                val historyTool = SearchChatsTool { _, _, _, _ -> listOf(MessageHit("message", "saved", "Prior topic", "topic result", 1)) }
                val engine = AgentEngine({ provider }, ToolRegistry(emptyList()), c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills,
                    chatOnlyTools = listOf(historyTool))
                val events = engine.run(sid, "lookup-turn", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "http://localhost", "fixture"),
                    "", listOf(ChatMessage(Role.USER, "Find the topic in my past chats")), { PermissionMode.FULL_ACCESS }, mutableSetOf(),
                    FileFs(c.appContext.cacheDir), RequestOptions(), 100_000, AgentMode.ACT, repoMapEnabled = false, conversation = conversation).toList()
                assertFalse(events.any { it is AgentEvent.Error || it is AgentEvent.ApprovalNeeded })
                val result = events.filterIsInstance<AgentEvent.ToolMessageCommitted>().single { it.message.toolName == "search_chats" }.message
                assertFalse(result.isError)
                assertEquals("message", result.generation.chatHistory!!.matches.single().messageId)
            }
        } finally { ids.forEach { c.sessions.session(it)?.let { session -> c.sessions.deleteSession(session) } } }
    }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun walk(node: AccessibilityNodeInfo) { add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::walk) }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::walk)
    }
    private fun await(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            nodes().firstOrNull { it.text?.contains(text) == true }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing '$text': ${nodes().mapNotNull { it.text }.joinToString()}")
    }
    private fun tap(text: String) {
        var node = await(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(c.appContext.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
