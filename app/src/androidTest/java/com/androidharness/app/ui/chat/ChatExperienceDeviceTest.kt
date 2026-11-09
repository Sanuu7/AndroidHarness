package com.androidharness.app.ui.chat

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.agent.*
import com.androidharness.app.llm.*
import com.androidharness.app.tools.*
import com.androidharness.app.workspace.FileFs
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatExperienceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun userImageAttachmentReachesChatVisionProvider(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "shared/user-image-${System.nanoTime()}").apply { mkdirs() }
        val source = File(root, "sample.gif")
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = androidx.core.content.FileProvider.getUriForFile(c.appContext, c.appContext.packageName + ".update", source)
        val stored = requireNotNull(c.images.import(uri))
        val ref = ImageRef(stored.file.name, stored.mime)
        val chat = c.sessions.createSession("User image", conversation = ConversationConfig(ConversationKind.CHAT))
        var observed = emptyList<ChatMessage>()
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                observed = messages.toList()
                emit(StreamEvent.TextDelta("Received")); emit(StreamEvent.Done("stop"))
            }
        }
        val engine = AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
        val runs = RunManager(c.appContext, engine, c.sessions, c.checkpoints, c.workspace, c.linuxEnv, c.settings, TodoStore(), c.providers)
        try {
            assertEquals("image/png", stored.mime)
            val config = ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.com", "gpt-4o")
            runs.startRun(chat, "What is in my picture?", listOf(ref), config, "fixture", PermissionMode.FULL_AUTO,
                AgentMode.ACT, 1024, 32000, ThinkingLevel.OFF, 10, notifyOnFinish = false)
            kotlinx.coroutines.withTimeout(30_000) { while (runs.isRunning(chat)) kotlinx.coroutines.delay(50) }
            val sent = observed.last { it.role == Role.USER }.imageData.single()
            assertEquals("image/png", sent.mime)
            val bytes = android.util.Base64.decode(sent.base64, android.util.Base64.DEFAULT)
            assertEquals(0x89.toByte(), bytes[0]); assertEquals('P'.code.toByte(), bytes[1])
            assertTrue(c.sessions.messages(chat).any { it.role == Role.USER && it.images == listOf(ref) })
            val reported = config.copy(model = "deepseek-chat")
            engine.run(chat, "reported-vision", reported, "fixture", listOf(ChatMessage(Role.USER, "Describe", images = listOf(ref))),
                { PermissionMode.FULL_AUTO }, mutableSetOf(), FileFs(root),
                RequestOptions(modelCatalogs = mapOf(reported.id to listOf(ModelEntry(reported.model, imageInput = true)))),
                32000, AgentMode.ACT, conversation = ConversationConfig(ConversationKind.CHAT)).toList()
            assertEquals(1, observed.last { it.role == Role.USER }.imageData.size)
        } finally {
            runs.stopAndJoin(chat)
            c.sessions.session(chat)?.let { c.sessions.deleteSession(it) }
            c.images.delete(ref.name); root.deleteRecursively()
        }
    }

    @Test fun chatFollowUpSendsPreviouslyShownImageToVisionProvider(): Unit = runBlocking {
        val url = "https://example.com/chat-image-fixture.png"
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val cache = File(c.appContext.cacheDir, "remote-images/$key.img").apply { parentFile?.mkdirs() }
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        cache.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val root = File(c.appContext.cacheDir, "chat-image-${System.nanoTime()}").apply { mkdirs() }
        val chat = c.sessions.createSession("Image context", conversation = ConversationConfig(ConversationKind.CHAT))
        var observed = emptyList<ChatMessage>()
        var inspectionHistory = emptyList<ChatMessage>()
        var round = 0
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                if (round++ == 0) {
                    observed = messages.toList()
                    emit(StreamEvent.ToolCallBatch(listOf(ToolCallData("inspect", "view_chat_image", "{\"url\":\"$url\"}"))))
                    emit(StreamEvent.Done("tool_calls"))
                } else {
                    inspectionHistory = messages.toList()
                    emit(StreamEvent.TextDelta("The picture is red.")); emit(StreamEvent.Done("stop"))
                }
            }
        }
        try {
            val engine = AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills,
                chatOnlyTools = listOf(ViewChatImageTool(c.images)))
            val history = listOf(ChatMessage(Role.USER, "Show a picture"), ChatMessage(Role.ASSISTANT, "![sample]($url)"),
                ChatMessage(Role.USER, "What color is it?"))
            engine.run(chat, "image-context", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT,
                "https://example.com", "gpt-4o"), "fixture", history, { PermissionMode.FULL_ACCESS }, mutableSetOf(),
                FileFs(root), RequestOptions(), 100_000, AgentMode.ACT, conversation = ConversationConfig(ConversationKind.CHAT)).toList()
            assertEquals(1, observed.last { it.role == Role.USER }.imageData.size)
            assertTrue(observed.last { it.role == Role.USER }.text.contains(url))
            assertTrue(inspectionHistory.any { it.role == Role.TOOL && it.toolName == "view_chat_image" && it.imageData.isNotEmpty() })
        } finally {
            c.sessions.session(chat)?.let { c.sessions.deleteSession(it) }
            cache.delete(); root.deleteRecursively()
        }
    }

    @Test fun chatEngineKeepsToolsAutomaticAndRejectsProjectToolsWithoutEnvironmentSetup(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "chat-engine-${System.nanoTime()}").apply { mkdirs() }
        val chat = c.sessions.createSession("New chat", conversation = ConversationConfig(ConversationKind.CHAT))
        var round = 0
        var advertised = emptyList<String>()
        var prompt = ""
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                advertised = tools.map { it.name }; prompt = systemPrompt
                if (round++ == 0) emit(StreamEvent.ToolCallBatch(listOf(
                    ToolCallData("text", "read_chat_text", "{}"),
                    ToolCallData("artifact", "create_artifact", "{\"title\":\"Idea\",\"html\":\"<html><body>Idea preview</body></html>\"}"),
                    ToolCallData("blocked-file", "read_file", "{\"path\":\"/private\"}"),
                    ToolCallData("blocked-shell", "shell", "{\"command\":\"id\"}"))))
                else emit(StreamEvent.TextDelta("Here is the idea and its visual."))
                emit(StreamEvent.Done(if (round == 1) "tool_calls" else "stop"))
            }
        }
        try {
            c.sessions.addMessage(chat, ChatMessage(Role.USER, "Brainstorm a small garden"))
            val githubClient = okhttp3.OkHttpClient()
            val engine = AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills,
                cavemanSettings = { AppSettings(cavemanInstalled = true) },
                universalMemory = c.memory,
                chatOnlyTools = listOf(ReadChatTextTool(c.sessions::messages), BrowserReadTool(c.browser),
                    ViewChatImageTool(c.images),
                    SearchChatsTool { _, _, _, _ -> emptyList() },
                    RecentChatsTool(c.sessions.sessions),
                    ReadChatSnippetTool(c.sessions::messages, c.sessions::session),
                    OfferCodeHandoffTool(),
                    GitHubApiTool(githubClient) { null }, GitHubSearchTool(githubClient) { null }, GitHubFileTool(githubClient) { null }))
            val events = engine.run(chat, "chat-engine-turn", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT,
                "http://localhost", "fixture"), "", c.sessions.messages(chat), { PermissionMode.FULL_ACCESS }, mutableSetOf("read_file", "shell"),
                FileFs(root), RequestOptions(), 100_000, AgentMode.ACT, pinnedInstructions = "CODE_ONLY_PINNED",
                conversation = ConversationConfig(ConversationKind.CHAT)).toList()
            assertEquals(ChatExperience.tools, advertised.toSet())
            assertTrue(prompt.startsWith(ChatExperience.SYSTEM_PROMPT))
            assertTrue(prompt.contains("Current local date: ${java.time.LocalDate.now()}"))
            assertFalse(prompt.contains(root.path)); assertFalse(prompt.contains("CODE_ONLY_PINNED"))
            assertFalse(events.any { it is AgentEvent.EnvironmentNeeded || it is AgentEvent.ApprovalNeeded || it is AgentEvent.Error })
            val results = events.filterIsInstance<AgentEvent.ToolMessageCommitted>().map { it.message }
            assertTrue(results.single { it.toolName == "read_chat_text" }.text.contains("Brainstorm a small garden"))
            assertTrue(results.single { it.toolName == "create_artifact" }.isError)
            assertTrue(results.none { it.artifact != null })
            assertFalse(advertised.any { it in setOf("create_artifact", "read_artifact") })
            assertTrue(results.single { it.toolName == "read_file" }.isError)
            assertTrue(results.single { it.toolName == "shell" }.isError)
        } finally { c.sessions.session(chat)?.let { c.sessions.deleteSession(it) }; root.deleteRecursively() }
    }

    @Test fun chatHasQuietThoughtsNoRawResultsAndOnlyConditionalCodeHandoff(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val project = c.workspace.currentProjectOnce()
        val chat = c.sessions.createSession("New chat", project.id, ConversationConfig(ConversationKind.CHAT))
        val code = c.sessions.createSession("Code comparison", project.id)
        val stores = mutableListOf<ViewModelStore>()
        lateinit var chatVm: ChatViewModel
        val selected = mutableStateOf(ConversationKind.CHAT)
        val scale = mutableStateOf(1f)
        val theme = mutableStateOf(ThemeMode.LIGHT)
        val turn = "chat-experience-fixture"
        for (sid in listOf(chat, code)) {
            c.sessions.addMessage(sid, ChatMessage(Role.USER, "Help me brainstorm an idea"), turn)
            c.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT, thinking = "Consider the audience and keep the first experiment small.", thinkingMs = 3000,
                toolCalls = listOf(ToolCallData("search", "web_search", "{\"query\":\"brainstorming\"}"))), turn)
            c.sessions.addMessage(sid, ChatMessage(Role.TOOL, "RAW_PRIVATE_TOOL_OUTPUT", toolCallId = "search", toolName = "web_search"), turn)
            c.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT, "Start with one small idea you can test this week."), turn)
        }
        c.sessions.addMessage(chat, ChatMessage(Role.USER, RunRecovery.CONTINUE_PROMPT,
            id = RunRecovery.CHAT_CONTINUATION_ID_PREFIX + "fixture"), turn)
        try {
            assertEquals("Help me brainstorm an idea", c.sessions.session(chat)?.title)
            assertTrue(c.sessions.messages(chat).any { it.text == RunRecovery.CONTINUE_PROMPT })
            assertFalse(c.sessions.searchMessages("authoritative", true).any { it.sessionId == chat })
            assertTrue(c.sessions.searchMessagesInSession(chat, "authoritative").isEmpty())
            assertFalse(c.sessions.searchMessages("RAW_PRIVATE_TOOL_OUTPUT", true).any { it.sessionId == chat })
            assertTrue(c.sessions.searchMessages("RAW_PRIVATE_TOOL_OUTPUT", true).any { it.sessionId == code })
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    chatVm = ChatViewModel(c, chat); val codeVm = ChatViewModel(c, code)
                    stores += ViewModelStore().apply { put("chat", chatVm); put("code", codeVm) }
                    activity.setContent { HarnessTheme(themeMode = theme.value, dynamicColor = false) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                            key(selected.value) { ChatScreen(if (selected.value == ConversationKind.CHAT) chatVm else codeVm,
                                {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) }
                        }
                    } }
                }
                await("Start with one small idea you can test this week.")
                assertFalse(chatVm.state.value.messages.any { it.text == RunRecovery.CONTINUE_PROMPT })
                await("Thoughts")
                assertNull(find("Searched the web"))
                assertNull(find("Work summary")); assertNull(find("RAW_PRIVATE_TOOL_OUTPUT"))
                assertNull(find(RunRecovery.CONTINUE_PROMPT))
                assertNull(find("Chat tools off")); assertNull(find("Continue in Code"))
                screenshot("chat-reply")
                tap("Thoughts"); await("Consider the audience and keep the first experiment small.")
                screenshot("chat-thoughts-expanded")
                scenario.onActivity { scale.value = 1.5f; theme.value = ThemeMode.DARK }
                await("Consider the audience and keep the first experiment small.")
                screenshot("chat-thoughts-large-dark")
                scenario.onActivity { scale.value = 1f; theme.value = ThemeMode.LIGHT }
                tap("Thoughts")
                val count = c.sessions.sessions.first().size
                val offerTurn = "chat-offer-fixture"
                c.sessions.addMessage(chat, ChatMessage(Role.USER, "Apply the chosen idea to my project"), offerTurn)
                c.sessions.addMessage(chat, ChatMessage(Role.ASSISTANT,
                    toolCalls = listOf(ToolCallData("offer", "offer_code_handoff", "{\"reason\":\"Project edits need Code\"}"))), offerTurn)
                c.sessions.addMessage(chat, ChatMessage(Role.TOOL, "Offered", toolCallId = "offer", toolName = "offer_code_handoff"), offerTurn)
                c.sessions.addMessage(chat, ChatMessage(Role.ASSISTANT, "You can continue in Code to apply the changes."), offerTurn)
                val deadline = SystemClock.uptimeMillis() + 8000
                while (find("Choose a workspace") == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                val handoff = clickableText("Choose a workspace")
                assertEquals("Offering does not create a Code conversation", count, c.sessions.sessions.first().size)
                assertTrue(handoff.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("Choose where to build. Your Chat plan comes along.")
                assertEquals(count, c.sessions.sessions.first().size)
                tap("Close workspace picker")
                scenario.onActivity { selected.value = ConversationKind.CODE }
                await("Work summary")
                screenshot("code-summary-preserved")
            }
        } finally {
            instrumentation.runOnMainSync { stores.forEach { it.clear() } }
            for (id in listOf(chat, code)) c.sessions.session(id)?.let { c.sessions.deleteSession(it) }
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun firstMessageNamesChatAndRepairsLegacyPlaceholdersWithoutChangingCodeOrCustomTitles(): Unit = runBlocking {
        val project = c.workspace.currentProjectOnce()
        val chat = c.sessions.createSession("New chat", project.id, ConversationConfig(ConversationKind.CHAT))
        val custom = c.sessions.createSession("My chosen title", project.id, ConversationConfig(ConversationKind.CHAT))
        val code = c.sessions.createSession("New chat", project.id)
        try {
            for (id in listOf(chat, custom, code)) c.sessions.addMessage(id, ChatMessage(Role.USER, "Plan a garden"))
            assertEquals("Plan a garden", c.sessions.session(chat)?.title)
            assertEquals("My chosen title", c.sessions.session(custom)?.title)
            assertEquals("New chat", c.sessions.session(code)?.title)
            c.sessions.renameSession(chat, "New chat")
            c.sessions.repairChatTitles()
            assertEquals("Plan a garden", c.sessions.session(chat)?.title)
            c.sessions.addMessage(chat, ChatMessage(Role.USER, "Second message"))
            assertEquals("Plan a garden", c.sessions.session(chat)?.title)
        } finally { for (id in listOf(chat, custom, code)) c.sessions.session(id)?.let { c.sessions.deleteSession(it) } }
    }

    @Test fun expandedThinkingFollowsNewActivityAndReopensAtTheLatestEntry(): Unit = runBlocking {
        val messages = mutableStateOf(emptyList<ChatMessage>())
        val thought = mutableStateOf("Initial thought")
        val live = mutableStateOf(true)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme(dynamicColor = false) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
                    com.androidharness.app.ui.chat.components.ChatActivity(messages.value, live = live.value, streamingThinking = thought.value, autoExpand = false)
                }
            } } }
            await("Thinking"); assertNull(find("Initial thought"))
            tap("Thinking"); await("Initial thought")
            for (index in 1..12) {
                scenario.onActivity {
                    val call = ToolCallData("search-$index", if (index % 2 == 0) "web_fetch" else "web_search", "RAW_ARGUMENTS")
                    messages.value = messages.value + ChatMessage(Role.ASSISTANT, "Progress update $index", thinking = "Thinking round $index", toolCalls = listOf(call)) +
                        ChatMessage(Role.TOOL, "RAW_RESULTS", toolCallId = call.id)
                    thought.value = "Latest thought $index"
                }
                await("Latest thought $index")
            }
            await("Progress update 12")
            assertNull(find("Initial thought")); assertNull(find("RAW_ARGUMENTS")); assertNull(find("RAW_RESULTS"))
            val latest = android.graphics.Rect().also { await("Latest thought 12").getBoundsInScreen(it) }
            assertTrue("The latest thought is within the visible inner viewport", latest.top > 0 && latest.bottom < instrumentation.targetContext.resources.displayMetrics.heightPixels)
            screenshot("chat-nested-activity-auto-follow")
            // A finished tool is only a brief phase; ongoing model work returns to Thinking.
            SystemClock.sleep(2_250)
            tap("Thinking"); awaitGone("Latest thought 12")
            tap("Thinking"); await("Latest thought 12")
            scenario.onActivity { live.value = false }
            await("Thoughts"); await("Latest thought 12")
            screenshot("chat-nested-completed-latest")
        }
    }

    @Test fun commentarySurvivesEveryToolRoundAndSourcesOpenTheReturnedPage(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val project = c.workspace.currentProjectOnce()
        val chat = c.sessions.createSession("New chat", project.id, ConversationConfig(ConversationKind.CHAT))
        val store = ViewModelStore()
        val turn = "news-commentary-turn"
        val intro = "Let me look at the news today."
        val followup = "I found the report. I will read it before summarizing."
        val search = ToolCallData("news-search", "web_search", "{\"query\":\"news today\"}")
        val fetch = ToolCallData("news-read", "web_fetch", "{\"url\":\"https://example.com/report\"}")
        try {
            c.sessions.addMessage(chat, ChatMessage(Role.USER, "What is the news today?"), turn)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val vm = ChatViewModel(c, chat); store.put("chat", vm)
                    activity.setContent { HarnessTheme(dynamicColor = false) { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } }
                }
                await("What is the news today?")
                c.sessions.addMessage(chat, ChatMessage(Role.ASSISTANT, intro, thinking = "Check the date and use reliable sources.", thinkingMs = 1000, toolCalls = listOf(search)), turn)
                await("Thoughts")
                assertNull(find(intro))
                tap("Thoughts"); await(intro)
                c.sessions.addMessage(chat, ChatMessage(Role.TOOL, "1. Example report\n   https://example.com/report\n   RAW_SEARCH_SNIPPET", toolCallId = search.id, toolName = search.name), turn)
                await("Searched the web"); await(intro)
                c.sessions.addMessage(chat, ChatMessage(Role.ASSISTANT, followup, thinking = "Use the original report for detail.", thinkingMs = 2000, toolCalls = listOf(fetch)), turn)
                await("Thoughts"); await(followup)
                c.sessions.addMessage(chat, ChatMessage(Role.TOOL, "RAW_PAGE_CONTENT", toolCallId = fetch.id, toolName = fetch.name), turn)
                c.sessions.addMessage(chat, ChatMessage(Role.ASSISTANT, "The report describes a new discovery.\n{\"type\":\"callout\",\"tone\":\"info\",\"title\":\"Today’s lead story\",\"text\":\"A new discovery was announced.\"}\n[Example report](https://example.com/report)"), turn)
                await("Read web pages"); await(followup); await("Today’s lead story")
                assertNull(find("{\"type\":\"callout\""))
                screenshot("chat-nested-chronological-rich-reply")
                tap("Thoughts")
                awaitGone(intro); awaitGone(followup); awaitGone("Read web pages")
                await("Today’s lead story")
                var sourceNode = await("Sources · 1")
                while (!sourceNode.isClickable && sourceNode.parent != null) sourceNode = sourceNode.parent
                val sourceBounds = android.graphics.Rect().also { sourceNode.getBoundsInScreen(it) }
                val answerBounds = android.graphics.Rect().also { await("The report describes a new discovery.").getBoundsInScreen(it) }
                assertTrue("Sources align with the answer", sourceBounds.left <= answerBounds.left + 4)
                assertEquals(1, nodes().count { it.isVisibleToUser && it.text?.toString() == "Sources · 1" })
                assertNull(find("RAW_SEARCH_SNIPPET")); assertNull(find("RAW_PAGE_CONTENT")); assertNull(find("Work summary"))
                screenshot("chat-nested-collapsed-reply")
                tap("Sources · 1"); await("Example report"); await("Read page")
                screenshot("chat-news-sources")
                // Recreate the screen: commentary and source provenance survive persisted history.
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                scenario.recreate()
                scenario.onActivity { activity ->
                    val vm = ChatViewModel(c, chat); store.put("chat-recreated", vm)
                    activity.setContent { HarnessTheme(dynamicColor = false) { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } }
                }
                await("Thoughts"); await("Sources · 1")
                assertNull(find(followup))
                tap("Thoughts"); await(followup); await("Read web pages")
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(chat)?.let { c.sessions.deleteSession(it) }
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun individualToolRowsFinishWithoutMovingAndSourcesAreCompact(): Unit = runBlocking {
        val result = mutableStateOf<ChatMessage?>(null)
        val search = ToolCallData("phase-search", "web_search", "RAW_ARGUMENTS")
        var opened = ""
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme(dynamicColor = false) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
                    com.androidharness.app.ui.chat.components.ChatToolActivity(search, result.value, result.value == null) { opened = it }
                    if (result.value != null) com.androidharness.app.ui.chat.components.ChatSources(listOf(ChatMessage(Role.ASSISTANT, toolCalls = listOf(search)), result.value!!)) { opened = it }
                }
            } } }
            await("Searching the web")
            scenario.onActivity { result.value = ChatMessage(Role.TOOL, "1. Example report\n   https://example.com/report\n   RAW_SNIPPET", toolCallId = search.id) }
            await("Searched the web"); await("Sources · 1")
            assertNull(find("RAW_ARGUMENTS")); assertNull(find("RAW_SNIPPET")); assertNull(find("example.com"))
            tap("Sources · 1"); await("Example report"); await("Found in search")
            tap("Example report"); assertEquals("https://example.com/report", opened)
            tap("Searched the web"); await("Example report")
            screenshot("chat-compact-sources-and-tool-result")
        }
    }

    @Test fun thinkingPreferencePersistsAndManualExpansionSurvivesCompletion(): Unit = runBlocking {
        val original = c.settings.settings.first().chatKeepThinkingCollapsed
        val live = mutableStateOf(true)
        val font = mutableStateOf(1f)
        try {
            c.settings.setChatKeepThinkingCollapsed(true)
            assertTrue(SettingsRepository(c.appContext).settings.first().chatKeepThinkingCollapsed)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme(dynamicColor = false) {
                    val prefs by c.settings.settings.collectAsState(initial = AppSettings())
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, font.value)) {
                        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                            com.androidharness.app.ui.chat.components.ChatActivity(emptyList(), live = live.value,
                                streamingThinking = "A private reasoning detail", autoExpand = !prefs.chatKeepThinkingCollapsed)
                            com.androidharness.app.ui.settings.ChatOptionsSection(prefs, {}, {},
                                onKeepThinkingCollapsed = { value -> runBlocking { c.settings.setChatKeepThinkingCollapsed(value) } },
                                onImageFrequency = { value -> runBlocking { c.settings.setChatImageFrequency(value) } },
                                onInstructions = { c.settings.setChatInstructions(it) })
                        }
                    }
                } } }
                await("Keep thinking collapsed"); await("Thinking")
                assertNull(find("A private reasoning detail"))
                tap("Thinking"); await("A private reasoning detail")
                scenario.onActivity { live.value = false; font.value = 1.5f }
                await("A private reasoning detail")
                screenshot("chat-thinking-setting-large-text")
                scenario.onActivity { live.value = true }
                tap("Keep thinking collapsed")
                assertFalse(c.settings.settings.first().chatKeepThinkingCollapsed)
                await("A private reasoning detail")
            }
        } finally { c.settings.setChatKeepThinkingCollapsed(original) }
    }

    @Test fun answerVersionsPersistAndRestoreTheSelectedToolHistory(): Unit = runBlocking {
        val name = "chat-versions-${System.nanoTime()}.db"
        fun open() = androidx.room.Room.databaseBuilder(c.appContext, com.androidharness.app.data.db.AppDatabase::class.java, name)
            .addMigrations(com.androidharness.app.data.db.AppDatabase.MIGRATION_21_22,
                com.androidharness.app.data.db.AppDatabase.MIGRATION_22_23).build()
        var database = open()
        try {
            var repo = SessionRepository(database)
            val sid = repo.createSession("Versions", conversation = ConversationConfig(ConversationKind.CHAT))
            val firstQuestion = repo.addMessage(sid, ChatMessage(Role.USER, "Explain this"), "turn-one")
            database.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(c.appContext.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE chat_answer_versions")
                // Recreate the actual v21 memory schema before testing the full upgrade path.
                it.execSQL("DROP TABLE memories")
                it.execSQL("CREATE TABLE memories (id TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, category TEXT NOT NULL, " +
                    "scope TEXT NOT NULL, projectId TEXT, sourceSessionId TEXT, pinned INTEGER NOT NULL, enabled INTEGER NOT NULL, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL, useCount INTEGER NOT NULL)")
                for (column in listOf("scope", "projectId", "enabled")) it.execSQL("CREATE INDEX index_memories_$column ON memories($column)")
                it.version = 21
            }
            database = open(); repo = SessionRepository(database)
            assertEquals("Explain this", repo.messages(sid).single().text)
            val search = ToolCallData("saved-source", "web_search", "{}")
            repo.addMessage(sid, ChatMessage(Role.ASSISTANT, thinking = "Original thought", toolCalls = listOf(search)), "turn-one")
            repo.addMessage(sid, ChatMessage(Role.TOOL, "1. Original source\nhttps://example.com", toolCallId = search.id), "turn-one")
            repo.addMessage(sid, ChatMessage(Role.ASSISTANT, "Original answer", thinkingMs = 1200), "turn-one")
            repo.archiveChatAnswer(sid, firstQuestion)
            repo.truncateFrom(sid, firstQuestion)
            val secondQuestion = repo.addMessage(sid, ChatMessage(Role.USER, "Explain this"), "turn-two")
            repo.addMessage(sid, ChatMessage(Role.ASSISTANT, "Alternative answer"), "turn-two")
            database.close(); database = open(); repo = SessionRepository(database)
            assertEquals(1, repo.answerVersions(sid).first().size)
            val versionRepo = repo
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme(dynamicColor = false) {
                    val messages by versionRepo.messagesFlow(sid).collectAsState(initial = emptyList())
                    val scope = rememberCoroutineScope()
                    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
                        androidx.compose.material3.Text(messages.lastOrNull()?.text.orEmpty())
                        com.androidharness.app.ui.chat.components.ChatAnswerVersions(sid, messages, versionRepo, true) { expected, version ->
                            scope.launch { versionRepo.selectChatAnswer(sid, expected, version) }
                        }
                    }
                } } }
                await("Alternative answer"); await("2 of 2")
                tap("Previous answer"); await("Original answer"); await("1 of 2")
                screenshot("chat-answer-version-switcher")
                tap("Next answer"); await("Alternative answer"); await("2 of 2")
            }
            repo.selectChatAnswer(sid, secondQuestion, firstQuestion)
            assertEquals("Original answer", repo.messages(sid).last().text)
            assertEquals("example.com", ChatActivityPresentation.sources(repo.messages(sid)).single().host)
            assertEquals(2, repo.answerVersions(sid).first().size)
            val before = repo.messages(sid)
            assertTrue(runCatching { repo.selectChatAnswer(sid, "stale-id", secondQuestion) }.isFailure)
            assertEquals(before, repo.messages(sid))
            val other = repo.createSession("Other", conversation = ConversationConfig(ConversationKind.CHAT))
            val otherQuestion = repo.addMessage(other, ChatMessage(Role.USER, "Explain this"), "other")
            assertTrue(runCatching { repo.selectChatAnswer(other, otherQuestion, firstQuestion) }.isFailure)
            assertEquals(1, repo.messages(other).size)
            repo.clearMessages(sid)
            assertTrue(repo.answerVersions(sid).first().isEmpty())
        } finally { database.close(); c.appContext.deleteDatabase(name) }
    }

    @Test fun memoryReceiptEditsAndDeletesOnlyItsSavedEntry(): Unit = runBlocking {
        val originalEnabled = c.settings.settings.first().memoryEnabled
        val root = File(c.appContext.cacheDir, "chat-receipt-${System.nanoTime()}").apply { mkdirs() }
        val text = "Receipt preference ${System.nanoTime()}"
        val changed = "Updated $text"
        var memoryId: String? = null
        try {
            c.settings.setMemoryEnabled(true)
            val result = RememberTool(c.memory).execute(kotlinx.serialization.json.buildJsonObject {
                put("text", kotlinx.serialization.json.JsonPrimitive(text))
                put("scope", kotlinx.serialization.json.JsonPrimitive("global"))
            }, ToolContext(FileFs(root)))
            val messages = listOf(ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("receipt", "remember", "{}"))),
                ChatMessage(Role.TOOL, result.output, toolCallId = "receipt"))
            memoryId = ChatActivityPresentation.savedMemoryIds(messages).single()
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme(dynamicColor = false) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
                        com.androidharness.app.ui.chat.components.ChatMemoryReceipt(messages, c, false)
                    }
                } } }
                tap("Memory saved"); tap(text)
                val input = await(text)
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, changed)
                }))
                tap("Save"); awaitGone("Save"); await(changed)
                assertEquals(changed, c.memory.memories.first().single { it.id == memoryId }.text)
                screenshot("chat-memory-receipt-edited")
                assertNull("Supplied-memory metadata stays out of the transcript", find("Memory used · 1"))
                tap(changed); tap("Delete"); await("Delete this memory?"); tap("Delete")
                await("This saved memory is no longer available here. Older receipts can be managed in Settings → Memory.")
                assertFalse(c.memory.memories.first().any { it.id == memoryId })
            }
        } finally {
            memoryId?.let { c.memory.remove(it) }
            c.settings.setMemoryEnabled(originalEnabled)
            root.deleteRecursively()
        }
    }

    @Test fun toolSourcesExposeAllResultsAndRecoveryKeepsActionsReachable(): Unit = runBlocking {
        val search = ToolCallData("all-sources", "web_search", "{}")
        val result = ChatMessage(Role.TOOL, (1..7).joinToString("\n") { "$it. Report $it\nhttps://source$it.example/report" }, toolCallId = search.id)
        var continued = false
        var retried = false
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme(dynamicColor = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                        com.androidharness.app.ui.chat.components.ChatRecovery("Connection interrupted", { continued = true }, { retried = true })
                        com.androidharness.app.ui.chat.components.ChatToolActivity(search, result, false) { }
                    }
                }
            } } }
            await("Connection interrupted"); tap("Continue reply"); tap("Retry answer")
            assertTrue(continued); assertTrue(retried)
            tap("Searched the web")
            await("Report 1")
            // Scroll the tool disclosure to reach the all-sources action after its four previews.
            repeat(5) {
                if (find("Sources · 7") == null) nodes().lastOrNull { it.isVisibleToUser && it.isScrollable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(150)
            }
            tap("Sources · 7"); await("Sources"); await("source1.example"); await("Found in search")
            repeat(5) {
                if (find("Report 7") == null) nodes().lastOrNull { it.isVisibleToUser && it.isScrollable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(150)
            }
            await("Report 7")
            screenshot("chat-all-tool-sources-large-text")
        }
    }

    @Test fun collapsedThinkingMetricsTickAtTheRightEdgeAndUseReportedTokens(): Unit = runBlocking {
        val timing = mutableStateOf(ThinkingTiming(startedAt = ThinkingTiming.now() - 2_000))
        val thought = mutableStateOf("abcd".repeat(50))
        val reported = mutableStateOf<Int?>(null)
        val font = mutableStateOf(1f)
        val live = mutableStateOf(true)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme(dynamicColor = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, font.value)) {
                    androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                            com.androidharness.app.ui.chat.components.ChatActivity(emptyList(), live = live.value,
                                autoExpand = false, streamingThinking = thought.value, showThinkingMetrics = true,
                                thinkingTiming = timing.value, reasoningTokens = reported.value)
                        }
                    }
                }
            } } }
            fun metric() = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.contains("tokens") == true }
            val deadline = SystemClock.uptimeMillis() + 8000
            while (metric() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            val first = requireNotNull(metric()).text.toString()
            assertTrue(first.contains("~50 tokens"))
            assertNull(find(thought.value))
            SystemClock.sleep(1200)
            assertNotEquals("The collapsed timer must tick without new tokens", first, metric()?.text?.toString())
            val titleBounds = android.graphics.Rect().also { await("Thinking").getBoundsInScreen(it) }
            val metricBounds = android.graphics.Rect().also { requireNotNull(metric()).getBoundsInScreen(it) }
            assertTrue("Metrics are on the far right", metricBounds.left > titleBounds.right)
            val width = instrumentation.targetContext.resources.displayMetrics.widthPixels
            assertTrue("Metrics stay within the right edge", metricBounds.right <= width && metricBounds.right > width * .8)
            scenario.onActivity { thought.value += "abcd".repeat(50); font.value = 1.5f }
            val updatedDeadline = SystemClock.uptimeMillis() + 8000
            while (metric()?.text?.toString()?.contains("~100 tokens") != true && SystemClock.uptimeMillis() < updatedDeadline) SystemClock.sleep(100)
            assertTrue(requireNotNull(metric()).text.toString().contains("~100 tokens"))
            scenario.onActivity { timing.value = timing.value.stop(ThinkingTiming.now()); reported.value = 142; live.value = false }
            val reportDeadline = SystemClock.uptimeMillis() + 8000
            while (metric()?.text?.toString()?.contains("142 tokens") != true && SystemClock.uptimeMillis() < reportDeadline) SystemClock.sleep(100)
            val frozen = requireNotNull(metric()).text.toString()
            assertTrue(frozen.contains("142 tokens")); assertFalse(frozen.contains("~"))
            SystemClock.sleep(1200)
            assertEquals("The timer stops when thinking ends", frozen, metric()?.text?.toString())
            screenshot("chat-collapsed-thinking-live-metrics-large-text")
        }
    }

    @Test fun codingThinkingShowsLiveAndSavedMetricsWithoutDoubleCounting(): Unit = runBlocking {
        val timing = mutableStateOf(ThinkingTiming(startedAt = ThinkingTiming.now() - 2000))
        val live = mutableStateOf(true)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme(dynamicColor = false) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                        com.androidharness.app.ui.chat.components.ThinkingBlock("abcd".repeat(50), live = live.value,
                            timing = timing.value, reasoningTokens = if (live.value) null else 1500)
                    }
                }
            } } }
            await("Thinking"); await("abcd".repeat(50))
            assertTrue("Expanded reasoning keeps the live token count visible", nodes().any { it.isVisibleToUser && it.text?.toString()?.contains("~50 tokens") == true })
            tap("Thinking")
            fun metric() = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.contains("tokens") == true }
            val deadline = SystemClock.uptimeMillis() + 8000
            while (metric() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            val first = requireNotNull(metric()).text.toString()
            assertTrue(first.contains("~50 tokens"))
            SystemClock.sleep(1200)
            assertNotEquals(first, metric()?.text?.toString())
            scenario.onActivity { timing.value = ThinkingTiming(elapsedMs = 5000); live.value = false }
            await("5s · 1.5k tokens")
            SystemClock.sleep(1200)
            await("5s · 1.5k tokens")
            tap("Thoughts"); await("abcd".repeat(50)); await("5s · 1.5k tokens")
            screenshot("code-expanded-thinking-metrics")
        }
    }

    @Test fun downButtonReachesTheAnswerBottomInsteadOfTheQuestion(): Unit = runBlocking {
        val sid = c.sessions.createSession("Down button fixture", conversation = ConversationConfig(ConversationKind.CHAT))
        val store = ViewModelStore()
        try {
            c.sessions.addMessage(sid, ChatMessage(Role.USER, "Question at the top"), "down-turn")
            c.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT,
                (1..50).joinToString("\n\n") { "Paragraph $it. " + "Read the answer carefully. ".repeat(5) } + "\n\nFINAL ANSWER END", generation = GenerationMetadata(suppliedMemories = listOf(SuppliedMemory("fixture-memory", "An internal saved preference.")))), "down-turn")
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val vm = ChatViewModel(c, sid); store.put("down", vm)
                    activity.setContent { HarnessTheme(dynamicColor = false) { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } }
                }
                await("Question at the top")
                tap("Scroll to latest")
                await("FINAL ANSWER END")
                val display = instrumentation.targetContext.resources.displayMetrics
                val x = display.widthPixels * .5f
                val top = display.heightPixels * .3f
                val bottom = display.heightPixels * .7f
                val begun = SystemClock.uptimeMillis()
                instrumentation.sendPointerSync(android.view.MotionEvent.obtain(begun, begun, android.view.MotionEvent.ACTION_DOWN, x, top, 0))
                repeat(12) { index ->
                    val at = SystemClock.uptimeMillis()
                    instrumentation.sendPointerSync(android.view.MotionEvent.obtain(begun, at, android.view.MotionEvent.ACTION_MOVE, x, top + (bottom - top) * (index + 1) / 12, 0))
                    SystemClock.sleep(16)
                }
                instrumentation.sendPointerSync(android.view.MotionEvent.obtain(begun, SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, x, bottom, 0))
                await("Scroll to latest"); tap("Scroll to latest"); await("FINAL ANSWER END")
                SystemClock.sleep(500)
                await("FINAL ANSWER END")
                assertNull(find("Question at the top"))
                assertNull("The memory-used receipt is hidden from the transcript", find("Memory used · 1"))
                assertNull(find("An internal saved preference."))
                screenshot("chat-down-button-actual-bottom")
            }
        } finally { instrumentation.runOnMainSync { store.clear() }; c.sessions.session(sid)?.let { c.sessions.deleteSession(it) } }
    }

    @Test fun rapidUpwardFlingsStayInHistoryWhileTheReplyKeepsStreaming() = exerciseRapidUpwardFlings(ConversationKind.CHAT)

    @Test fun codeRapidUpwardFlingsStayInHistoryWhileTheReplyKeepsStreaming() = exerciseRapidUpwardFlings(ConversationKind.CODE)

    private fun exerciseRapidUpwardFlings(kind: ConversationKind): Unit = runBlocking {
        val sid = c.sessions.createSession("Fast scroll fixture", conversation = ConversationConfig(kind))
        val store = ViewModelStore()
        // Publish deterministic deltas through the same live state mirrored by the production screen.
        @Suppress("UNCHECKED_CAST")
        val live = c.runManager.live(sid) as kotlinx.coroutines.flow.MutableStateFlow<RunManager.LiveRunState>
        val reply = (1..45).joinToString("\n\n") { "Streaming paragraph $it. " + "Readable answer text. ".repeat(5) }
        var updates: kotlinx.coroutines.Job? = null
        fun tailVisible() = nodes().any { it.isVisibleToUser && it.text?.toString()?.startsWith("STREAM TAIL") == true }
        fun waitFor(description: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8000
            while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertTrue(description, condition())
        }
        try {
            c.sessions.addMessage(sid, ChatMessage(Role.USER, "Write a long answer"), "fast-scroll-turn")
            live.value = RunManager.LiveRunState(sid, running = true, turnId = "fast-scroll-turn",
                liveMessageId = "fast-scroll-live", streamingText = reply + "\n\nSTREAM TAIL 0")
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val vm = ChatViewModel(c, sid); store.put("fast-scroll", vm)
                    activity.setContent { HarnessTheme(dynamicColor = false) { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } }
                }
                if (kind == ConversationKind.CHAT) {
                    await("Write a long answer")
                    tap("Scroll to latest")
                }
                waitFor("The live answer can be followed at the bottom", ::tailVisible)
                updates = launch(kotlinx.coroutines.Dispatchers.Default) {
                    var tick = 0
                    while (true) {
                        kotlinx.coroutines.delay(16)
                        tick++
                        live.value = live.value.copy(streamingText = reply + "\n\n" + "New token ".repeat(tick) + "\n\nSTREAM TAIL $tick")
                    }
                }
                repeat(4) {
                    if (it > 0) {
                        tap("Scroll to latest")
                        waitFor("The down button still reaches a streaming answer's bottom", ::tailVisible)
                    }
                    val display = instrumentation.targetContext.resources.displayMetrics
                    val x = display.widthPixels * .5f
                    val from = display.heightPixels * .35f
                    val to = display.heightPixels * .65f
                    val down = SystemClock.uptimeMillis()
                    fun event(action: Int, y: Float) {
                        val motion = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                        try { instrumentation.sendPointerSync(motion) } finally { motion.recycle() }
                    }
                    event(android.view.MotionEvent.ACTION_DOWN, from)
                    repeat(3) { step ->
                        SystemClock.sleep(16)
                        event(android.view.MotionEvent.ACTION_MOVE, from + (to - from) * (step + 1) / 3)
                    }
                    event(android.view.MotionEvent.ACTION_UP, to)
                    waitFor("A fast upward fling leaves the latest content", { find("Scroll to latest") != null && !tailVisible() })
                    SystemClock.sleep(800)
                    assertFalse("Streaming must not pull a reader back to the bottom", tailVisible())
                    assertNotNull(find("Scroll to latest"))
                }
                screenshot("${kind.name.lowercase()}-fast-upward-fling-stays-in-history")
            }
        } finally {
            updates?.cancel()
            updates?.join()
            live.value = RunManager.LiveRunState(sid)
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
        }
    }

    @Test fun previousAnswerVersionsRemainBrowsableAfterAFollowUpWithoutChangingHistory(): Unit = runBlocking {
        val sid = c.sessions.createSession("Old versions fixture", conversation = ConversationConfig(ConversationKind.CHAT))
        try {
            val originalQuestion = c.sessions.addMessage(sid, ChatMessage(Role.USER, "First question"), "original-version")
            c.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT, "Original saved answer"), "original-version")
            c.sessions.archiveChatAnswer(sid, originalQuestion)
            c.sessions.truncateFrom(sid, originalQuestion)
            c.sessions.addMessage(sid, ChatMessage(Role.USER, "First question"), "current-version")
            c.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT, "Current answer"), "current-version")
            c.sessions.addMessage(sid, ChatMessage(Role.USER, "A later question"), "later-turn")
            c.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT, "Later answer stays"), "later-turn")
            val before = c.sessions.messages(sid)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme(dynamicColor = false) {
                    androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                            val density = LocalDensity.current
                            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                                com.androidharness.app.ui.chat.components.ChatAnswerVersions(sid, before, c.sessions, true,
                                    turnId = "current-version", readOnly = true, onSelect = { _, _ -> fail("Browsing older answers must not replace history") })
                            }
                        }
                    }
                } } }
                await("2 of 2"); tap("Previous answer"); await("Saved answers"); await("Original saved answer")
                tap("Next answer"); await("Current answer")
                assertEquals(before, c.sessions.messages(sid))
                screenshot("chat-browse-older-answer-version")
            }
        } finally { c.sessions.session(sid)?.let { c.sessions.deleteSession(it) } }
    }

    @Test fun quoteActionUsesTheSelectedTextInsteadOfStaleClipboardContents() {
        val automation = instrumentation.uiAutomation
        val oldFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val quoteText = "Explain this particular sentence."
        val chosen = mutableStateOf("")
        val manager = c.appContext.getSystemService(android.content.ClipboardManager::class.java)
        var previous: android.content.ClipData? = null
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    previous = manager.primaryClip
                    manager.setPrimaryClip(android.content.ClipData.newPlainText("stale", "WRONG OLD CLIPBOARD"))
                    activity.setContent { HarnessTheme(dynamicColor = false) {
                        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
                                com.androidharness.app.ui.chat.components.QuoteReplySelection({ chosen.value = it }) {
                                    com.androidharness.app.ui.chat.components.AssistantText(quoteText)
                                }
                                if (chosen.value.isNotEmpty()) androidx.compose.material3.Text("Quoted result: " + chosen.value)
                            }
                        }
                    } }
                }
                val rect = android.graphics.Rect().also { await(quoteText).getBoundsInScreen(it) }
                val begun = SystemClock.uptimeMillis()
                instrumentation.sendPointerSync(android.view.MotionEvent.obtain(begun, begun, android.view.MotionEvent.ACTION_DOWN, rect.centerX().toFloat(), rect.centerY().toFloat(), 0))
                SystemClock.sleep(650)
                instrumentation.sendPointerSync(android.view.MotionEvent.obtain(begun, SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, rect.centerX().toFloat(), rect.centerY().toFloat(), 0))
                await("Quote"); tap("Select all"); await("Quote"); tap("Quote")
                await("Quoted result: $quoteText")
                assertEquals(quoteText, chosen.value)
                screenshot("chat-quote-selected-text")
            } finally { instrumentation.runOnMainSync {
                previous?.let(manager::setPrimaryClip) ?: manager.clearPrimaryClip()
                automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            } }
        }
    }

    @Test fun attachmentFailuresStayVisibleAndBlockSendingUntilRemoved(): Unit = runBlocking {
        val sid = c.sessions.createSession("Attachment fixture", conversation = ConversationConfig(ConversationKind.CHAT))
        val store = ViewModelStore()
        val root = File(c.appContext.cacheDir, "shared/readiness-${System.nanoTime()}").apply { mkdirs() }
        lateinit var vm: ChatViewModel
        fun uri(file: File) = androidx.core.content.FileProvider.getUriForFile(c.appContext, c.appContext.packageName + ".update", file)
        fun waitFor(predicate: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8000
            while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            assertTrue(predicate())
        }
        try {
            val ready = File(root, "ready.txt").apply { writeText("A readable attachment") }
            val binary = File(root, "unsupported.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
            val huge = File(root, "too-large.txt").apply { java.io.RandomAccessFile(this, "rw").use { it.setLength(FileAttachments.COPY_BYTE_LIMIT + 1) } }
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    vm = ChatViewModel(c, sid); store.put("attachments", vm)
                    activity.setContent { HarnessTheme(dynamicColor = false) {
                        val state by vm.state.collectAsState()
                        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                                com.androidharness.app.ui.chat.components.ChatAttachmentReadiness(state, vm::removeAttachment, vm::removeFileAttachment, vm::removeAttachmentImport)
                            }
                        }
                    } }
                }
                waitFor { vm.state.value.conversation.isChat }
                vm.attachFile(uri(ready)); waitFor { vm.state.value.fileAttachments.any { it.name == ready.name } && vm.state.value.attachmentImports.isEmpty() }
                await(FileAttachments.humanBytes(ready.length()) + " · Ready")
                vm.attachFile(uri(binary)); waitFor { vm.state.value.attachmentImports.any { it.status == AttachmentImportStatus.UNSUPPORTED } }
                assertFalse(vm.send("Do not send an unsupported attachment"))
                val binaryText = File(root, "binary.txt").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
                vm.attachFile(uri(binaryText)); waitFor { vm.state.value.attachmentImports.any { it.name == binaryText.name && it.status == AttachmentImportStatus.UNSUPPORTED } }
                val missing = File(root, "missing.txt")
                vm.attachFile(uri(missing)); waitFor { vm.state.value.attachmentImports.any { it.name == missing.name && it.status == AttachmentImportStatus.FAILED } }
                vm.attachFile(uri(huge)); waitFor { vm.state.value.attachmentImports.any { it.status == AttachmentImportStatus.TOO_LARGE } }
                assertTrue(c.sessions.messages(sid).isEmpty())
                screenshot("chat-attachment-readiness-errors")
                vm.state.value.attachmentImports.forEach { vm.removeAttachmentImport(it.id) }
                assertNull(vm.attachmentBlockReason())
                assertEquals("A readable attachment", vm.state.value.fileAttachments.single().inlineText)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            root.deleteRecursively()
        }
    }

    @Test fun memoryReceiptRecordsOnlyFactsActuallySuppliedAndKeepsTheirOriginalText(): Unit = runBlocking {
        val prefs = c.settings.settings.first()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(c.appContext, com.androidharness.app.data.db.AppDatabase::class.java).build()
        val repo = com.androidharness.app.data.MemoryRepository(db, c.settings)
        val root = File(c.appContext.cacheDir, "memory-receipt-${System.nanoTime()}").apply { mkdirs() }
        try {
            c.settings.setMemoryEnabled(true)
            val saved = requireNotNull(repo.add("Keep replies concise.", MemoryCategory.PREFERENCE, MemoryScope.GLOBAL, null))
            var suppliedPrompt = ""
            val provider = object : LlmProvider {
                override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                    messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                    suppliedPrompt = systemPrompt
                    emit(StreamEvent.TextDelta("A concise answer.")); emit(StreamEvent.Done("stop"))
                }
            }
            val engine = AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills, universalMemory = repo)
            val events = engine.run("memory-fixture", "memory-turn", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT,
                "https://example.invalid", "fixture"), "fixture", listOf(ChatMessage(Role.USER, "Explain briefly")),
                { PermissionMode.FULL_AUTO }, mutableSetOf(), FileFs(root), RequestOptions(), 100000, AgentMode.ACT,
                conversation = ConversationConfig(ConversationKind.CHAT)).toList()
            val message = events.filterIsInstance<AgentEvent.AssistantCommitted>().single().message
            assertTrue(suppliedPrompt.contains(saved.text))
            assertEquals(listOf(SuppliedMemory(saved.id, saved.text)), message.generation.suppliedMemories)
            repo.update(saved, "A different preference.", MemoryCategory.PREFERENCE, MemoryScope.GLOBAL, null)
            assertEquals(saved.text, GenerationMetadata.decode(GenerationMetadata.encode(message.generation)).suppliedMemories.single().text)
            c.settings.setMemoryEnabled(false)
            val absent = mutableListOf<com.androidharness.app.data.db.MemoryEntity>()
            assertNull(repo.promptBlock(null) { absent += it })
            assertTrue(absent.isEmpty())
        } finally { c.settings.setMemoryEnabled(prefs.memoryEnabled); db.close(); root.deleteRecursively() }
    }

    @Test fun contextCardMatchesTheConceptAndHandoffWaitsForConfirmation(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val project = c.workspace.currentProjectOnce()
        val chat = c.sessions.createSession("Reading tracker", project.id,
            ConversationConfig(ConversationKind.CHAT, draftText = "Keep this draft"))
        val store = ViewModelStore()
        val theme = mutableStateOf(ThemeMode.LIGHT)
        val opened = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val turn = "context-card-fixture"
        try {
            c.sessions.addMessage(chat, ChatMessage(Role.USER, "Build the reading tracker we discussed."), turn)
            c.sessions.addMessage(chat, ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("offer", "offer_code_handoff",
                """{"title":"Build your reading tracker","reason":"Create the screens and connect local storage."}"""))), turn)
            c.sessions.addMessage(chat, ChatMessage(Role.TOOL, "Offered", toolCallId = "offer", toolName = "offer_code_handoff"), turn)
            c.sessions.addMessage(chat, ChatMessage(Role.ASSISTANT, "The plan is ready. Start with a small reading list, progress tracking, and a simple daily goal."), turn)
            val count = c.sessions.sessions.first().size
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val vm = ChatViewModel(c, chat); store.put("context-card", vm)
                    activity.setContent { HarnessTheme(themeMode = theme.value, dynamicColor = false) {
                        ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}, onNavigateToSession = { opened.set(it) })
                    } }
                }
                awaitCard("Build your reading tracker")
                screenshot("context-card-light")
                assertTrue(awaitCard("Build your reading tracker").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("Choose where to build. Your Chat plan comes along.")
                screenshot("context-picker-light")
                assertEquals(count, c.sessions.sessions.first().size)
                tap("Close workspace picker")
                scenario.onActivity { theme.value = ThemeMode.DARK }
                awaitCard("Build your reading tracker")
                instrumentation.waitForIdleSync()
                screenshot("context-card-dark")
                assertTrue(awaitCard("Build your reading tracker").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("Choose where to build. Your Chat plan comes along.")
                screenshot("context-picker-dark")
                val confirm = clickableText("Continue in Code")
                assertTrue(confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val deadline = SystemClock.uptimeMillis() + 8000
                while (opened.get() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                val id = requireNotNull(opened.get())
                val code = requireNotNull(c.sessions.session(id))
                assertEquals(count + 1, c.sessions.sessions.first().size)
                assertEquals(project.id, code.projectId)
                assertEquals(chat, ConversationConfig.decode(code.conversationJson).sourceChatId)
                assertFalse(ConversationConfig.decode(code.conversationJson).isChat)
                assertTrue(code.compactionSummary.contains("Build the reading tracker we discussed."))
                assertFalse(code.compactionSummary.contains("offer_code_handoff"))
                assertFalse(c.runManager.isRunning(id))
                assertFalse(c.sessions.messages(id).any { it.role == Role.USER })
                assertEquals("Keep this draft", ConversationConfig.decode(c.sessions.session(chat)?.conversationJson).draftText)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            opened.get()?.let { c.sessions.session(it)?.let { session -> c.sessions.deleteSession(session) } }
            c.sessions.session(chat)?.let { c.sessions.deleteSession(it) }
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun contextPickerSupportsLargeTextAndSelectingAnotherWorkspace(): Unit = runBlocking {
        val show = mutableStateOf(false)
        val chosen = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val projects = listOf(
            com.androidharness.app.data.db.ProjectEntity("first", "My workspace", "APP", null, 0L),
            com.androidharness.app.data.db.ProjectEntity("second", "Reading tracker with a longer workspace name", "APP", null, 0L),
        )
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    Box(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        com.androidharness.app.ui.chat.components.ChatCodeHandoffCard(
                            ChatExperience.CodeHandoffOffer("Build your reading tracker", "Create the screens and connect local storage."),
                            true, { show.value = true }, Modifier.widthIn(max = 320.dp))
                    }
                    if (show.value) com.androidharness.app.ui.chat.components.ChatCodeHandoffSheet(projects, "first", true,
                        { show.value = false }, { chosen.set(it); show.value = false })
                }
            } } }
            awaitCard("Build your reading tracker")
            screenshot("context-card-large-text")
            assertTrue(awaitCard("Build your reading tracker").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val row = await("Reading tracker with a longer workspace name")
            var selectable = row
            while (!selectable.isClickable && selectable.parent != null) selectable = selectable.parent
            assertTrue(selectable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            screenshot("context-picker-large-text")
            val confirm = clickableText("Continue in Code")
            assertTrue(confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals("second", chosen.get())
        }
    }

    private fun awaitCard(title: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        do {
            find(title)?.let { return clickableText(title) }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        screenshot("failure"); throw AssertionError("Expected Context card: $title; visible: ${nodes().mapNotNull { it.text?.toString() }}")
    }

    private fun clickableText(text: String): AccessibilityNodeInfo {
        for (match in nodes().filter { it.isVisibleToUser && it.text?.toString() == text }) {
            var node = match
            while (!node.isClickable && node.parent != null) node = node.parent
            if (node.isClickable) return node
        }
        throw AssertionError("Expected clickable control: $text")
    }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) { add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        val roots = instrumentation.uiAutomation.windows.mapNotNull { it.root }
        if (roots.isEmpty()) instrumentation.uiAutomation.rootInActiveWindow?.let(::visit) else roots.forEach(::visit)
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun await(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        do { find(text)?.let { return it }; SystemClock.sleep(100) } while (SystemClock.uptimeMillis() < deadline)
        screenshot("failure"); throw AssertionError("Expected: $text; visible: ${nodes().mapNotNull { it.text?.toString() }}")
    }
    private fun awaitGone(text: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do { if (find(text) == null) return; SystemClock.sleep(50) } while (SystemClock.uptimeMillis() < deadline)
        screenshot("failure"); throw AssertionError("Still visible after collapse: $text")
    }
    private fun tap(text: String) {
        var node = await(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        val folder = File(c.appContext.getExternalFilesDir(null), "chat-experience").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()!!.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
