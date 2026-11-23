package com.androidharness.app.ui.chat

import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.Modifier
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.llm.*
import com.androidharness.app.ui.chat.components.*
import com.androidharness.app.ui.theme.HarnessTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReasoningDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun engineKeepsReasoningDurationAndUsageSeparateFromAnswerStreaming() = runBlocking {
        val c = (context.applicationContext as com.androidharness.app.HarnessApp).container
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = kotlinx.coroutines.flow.flow {
                emit(StreamEvent.ThinkingDelta("Check the request"))
                kotlinx.coroutines.delay(150)
                emit(StreamEvent.TextDelta("Answer"))
                kotlinx.coroutines.delay(350)
                emit(StreamEvent.Usage(10, 50, reasoningTokens = 42))
                emit(StreamEvent.Done("stop"))
            }
        }
        val engine = AgentEngine({ provider }, com.androidharness.app.tools.ToolRegistry(emptyList()),
            c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
        val events = mutableListOf<AgentEvent>()
        engine.run("thinking-metrics-fixture", "turn",
            ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://fixture.invalid", "fixture-model"),
            "fixture", listOf(ChatMessage(Role.USER, "Question")), { PermissionMode.CONFIRM_ALL }, mutableSetOf(),
            com.androidharness.app.workspace.FileFs(context.cacheDir), RequestOptions(), 32_000, AgentMode.ACT,
            repoMapEnabled = false).collect { events += it }
        val message = events.filterIsInstance<AgentEvent.AssistantCommitted>().single().message
        assertEquals(42, message.generation.reasoningTokens)
        assertEquals(42, events.filterIsInstance<AgentEvent.Usage>().single().reasoningTokens)
        assertTrue("Reasoning must have a measured duration", message.thinkingMs >= 100)
        assertTrue("Answer streaming must be excluded", message.generationMs - message.thinkingMs >= 300)
        val progress = events.filterIsInstance<AgentEvent.ThinkingProgress>()
        assertNotNull(progress.first().timing.startedAt)
        assertNull(progress.last().timing.startedAt)
        assertEquals(message.thinkingMs, progress.last().timing.elapsedMs)
    }

    @Test fun thinkingMetricsAdvanceLiveThenFreezeAndReplaceEstimatesWithReportedUsage() {
        val text = mutableStateOf("1234")
        val timing = mutableStateOf(ThinkingTiming().start(ThinkingTiming.now() - 2_000))
        val live = mutableStateOf(true)
        val tokens = mutableStateOf<Int?>(null)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { HarnessTheme {
                Surface(Modifier.fillMaxSize()) { Column {
                    ThinkingBlock(text.value, live = live.value, timing = timing.value, reasoningTokens = tokens.value)
                    ThinkingBlock("Saved summary", thinkingMs = 5_000, reasoningTokens = 120)
                    ThinkingBlock("", reasoningTokens = 7)
                } }
            } } }
            await("5s · 120 tokens")
            await("7 tokens") // Hidden reasoning has usage, without invented duration or text.
            val initial = await("~1 tokens (estimated)", contains = true).text.toString()
            SystemClock.sleep(1_200)
            val advanced = await("~1 tokens (estimated)", contains = true).text.toString()
            assertNotEquals("Elapsed time must update without new text deltas", initial, advanced)
            scenario.onActivity { text.value = "1234567890123456" }
            await("~4 tokens (estimated)", contains = true)
            scenario.onActivity {
                timing.value = timing.value.stop(ThinkingTiming.now())
                live.value = false
                tokens.value = 42
            }
            val finished = await("42 tokens", contains = true).text.toString()
            assertFalse(finished.contains("estimated"))
            SystemClock.sleep(1_200)
            assertEquals("The clock must stop when reasoning ends", finished, await("42 tokens", contains = true).text.toString())
            screenshot("thinking-live-and-saved-metrics")
        }
    }

    @Test fun longChatModelKeepsThinkingAndSearchVisibleAtLargeText() {
        val label = "A long provider / dots-3-note-preview-2026-10-28"
        val scale = mutableStateOf(1f)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { HarnessTheme {
                val density = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, scale.value),
                ) {
                    Surface(Modifier.fillMaxSize()) {
                        Column {
                            MainHeader("Long model", false, label, AgentMode.ACT,
                                thinkingLevel = ThinkingLevel.MAX,
                                thinkingLevels = listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.MAX),
                                permissionMode = PermissionMode.CONFIRM_ALL, canUndo = false,
                                conversation = ConversationConfig(ConversationKind.CHAT),
                                onOpenDrawer = {}, onPickModel = {}, onOpenTerminal = {}, onSetThinking = {},
                                onSetPermission = {}, onSetMode = {}, onOpenContext = {}, onOpenUndo = {},
                                onOpenFiles = {}, onOpenGitHub = {})
                        }
                    }
                }
            } } }
            for (font in listOf(1f, 1.5f)) {
                scenario.onActivity { scale.value = font }
                val model = Rect().also(await(label)::getBoundsInScreen)
                val thinking = Rect().also(await("Thinking level")::getBoundsInScreen)
                val search = Rect().also(await("Auto")::getBoundsInScreen)
                assertTrue("Model retains visible space", model.width() > 40)
                assertTrue("Thinking stays after the model", thinking.centerX() > model.centerX())
                assertTrue("Search stays after thinking", search.centerX() > thinking.centerX())
                assertTrue("Header stays on one row", kotlin.math.abs(model.centerY() - search.centerY()) < 24)
                screenshot("long-chat-header-$font")
            }
        }
    }

    @Test fun thinkingPopupStaysCenteredOnItsButtonAndInsetFromWindowEdges() {
        val label = mutableStateOf("deepseek-v4-1-flash")
        val direction = mutableStateOf(androidx.compose.ui.unit.LayoutDirection.Ltr)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { HarnessTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalLayoutDirection provides direction.value,
                    com.androidharness.app.ui.LocalDesktopLayout provides
                        (InstrumentationRegistry.getArguments().getString("desktop") == "true"),
                ) {
                    Surface(Modifier.fillMaxSize()) {
                        Column {
                            MainHeader("Popup position", false, label.value, AgentMode.ACT,
                                thinkingLevel = ThinkingLevel.MAX,
                                thinkingLevels = listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.MAX),
                                permissionMode = PermissionMode.CONFIRM_ALL, canUndo = false,
                                onOpenDrawer = {}, onPickModel = {}, onOpenTerminal = {}, onSetThinking = {},
                                onSetPermission = {}, onSetMode = {}, onOpenContext = {}, onOpenUndo = {},
                                onOpenFiles = {}, onOpenGitHub = {}, onCustomizeThinking = {}, onAddThinkingLevel = {})
                        }
                    }
                }
            } } }
            val margin = (12 * context.resources.displayMetrics.density).toInt()
            val screenWidth = context.resources.displayMetrics.widthPixels
            for (rtl in listOf(false, true)) {
                for ((index, model) in listOf("deepseek-v4-1-flash", "M", "A very long model name with a dated version").withIndex()) {
                    scenario.onActivity {
                        label.value = model
                        direction.value = if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
                    }
                    await(model)
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(100)
                    var anchor = await("Thinking level")
                    while (!anchor.isClickable) anchor = requireNotNull(anchor.parent)
                    val anchorBounds = Rect().also { anchor.getBoundsInScreen(it) }
                    tap("Thinking level")
                    await("Thinking options")
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(300)
                    val popup = Rect().also { await("Thinking options").getBoundsInScreen(it) }
                    screenshot("popup-position-${if (rtl) "rtl" else "ltr"}-$index")
                    val expectedLeft = (anchorBounds.centerX() - popup.width() / 2)
                        .coerceIn(margin, screenWidth - margin - popup.width())
                    assertTrue("Popup must keep its left inset: $popup", popup.left >= margin - 1)
                    assertTrue("Popup must keep its right inset: $popup", popup.right <= screenWidth - margin + 1)
                    assertEquals("Popup should center on $model (RTL=$rtl): $popup / $anchorBounds", expectedLeft.toDouble(), popup.left.toDouble(), 2.0)
                    // Accessibility expands the badge's 36dp layout to a 48dp touch target.
                    assertTrue("Popup must open below the visible badge: $popup / $anchorBounds",
                        popup.top >= anchorBounds.bottom - margin)
                    tap("High")
                    awaitHidden("Switch model…")
                }
            }
        }
    }

    @Test fun modelSelectionDetectsLevelsForNormalMenuAndCustomEditorWithoutReplacingOverrides() = runBlocking {
        val c = (context.applicationContext as com.androidharness.app.HarnessApp).container
        val original = c.settings.settings.first()
        val socket = java.net.ServerSocket(0)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val responseDelay = java.util.concurrent.atomic.AtomicLong()
        val body = """{"data":[{"id":"new-model","effort":{"supported_levels":["low","high","max"],"default_level":"high"}}]}"""
        val responseBody = java.util.concurrent.atomic.AtomicReference(body)
        val server = Thread {
            while (!socket.isClosed) try {
                socket.accept().use { client ->
                    client.soTimeout = 5000
                    val reader = client.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    if (request.startsWith("GET /v1/models ")) requests.incrementAndGet()
                    Thread.sleep(responseDelay.get())
                    val bytes = responseBody.get().toByteArray()
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
        val config = c.providers.add("Detected thinking fixture", ProviderType.OPENAI_COMPAT,
            "http://127.0.0.1:${socket.localPort}/v1", "new-model", "fixture")
        val sid = c.sessions.createSession("Detected reasoning fixture")
        var vm: ChatViewModel? = null
        try {
            c.providers.addCustomModel(config.id, config.model)
            c.providers.saveCatalog(config.id, listOf(ModelEntry(config.model)))
            c.settings.setModelSelectionScope(true)
            c.settings.setActiveSelection(config.id, config.model)
            instrumentation.runOnMainSync { vm = ChatViewModel(c, sid) }
            val model = requireNotNull(vm)
            withTimeout(15000) { model.state.first { it.catalogs[config.id]?.singleOrNull()?.reasoningEfforts != null } }
            val entry = model.state.value.catalogs.getValue(config.id).single()
            assertTrue(entry.custom)
            assertEquals(listOf("low", "high", "max"), entry.reasoningEfforts)
            val editing = mutableStateOf(false)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { HarnessTheme {
                    val state by model.state.collectAsState()
                    val options = RequestOptions(thinking = state.thinkingLevel, modelCatalogs = state.catalogs,
                        customReasoning = state.reasoningConfigs, customThinkingLevels = state.customThinkingLevels)
                    val resolved = options.resolveThinking(config)
                    Surface(Modifier.fillMaxSize()) { Column {
                        MainHeader("Detected reasoning", false, config.model, AgentMode.ACT,
                            thinkingLevel = resolved.level, thinkingLevels = resolved.spec.levels,
                            permissionMode = PermissionMode.CONFIRM_ALL, canUndo = false,
                            onOpenDrawer = {}, onPickModel = {}, onOpenTerminal = {}, onSetThinking = model::setThinkingLevel,
                            onSetPermission = {}, onSetMode = {}, onOpenContext = {}, onOpenUndo = {},
                            onOpenFiles = {}, onOpenGitHub = {}, thinkingResolved = resolved, onCustomizeThinking = { editing.value = true },
                            onSetCustomThinking = { model.setCustomThinking(config, it) })
                        if (editing.value) CustomReasoningSheet(config, state.reasoningConfigs[CustomReasoning.key(config)],
                            ThinkingSpecs.forProvider(config, entry), onDismiss = { editing.value = false },
                            onSave = { model.saveReasoningConfig(config, it); editing.value = false },
                            onDetectLevels = { model.detectReasoningLevels(config) })
                    } }
                } } }
                tap("Thinking level"); await("Low"); await("High"); tap("Max")
                withTimeout(10000) { model.state.first { it.thinkingLevel == ThinkingLevel.MAX } }
                awaitHidden("Switch model…")
                tap("Thinking level"); tap("Customize thinking")
                await("Detected from provider"); await("Low"); await("High"); await("Max")
                screenshot("detected-levels-editor")
                responseDelay.set(800)
                tap("Auto-detect levels"); await("Checking provider…")
                await("Detected 3 levels. Save to apply.")
                assertEquals(2, requests.get())
                responseDelay.set(0)
                screenshot("force-detected-editor")
                tap("Close thinking settings")
            }
            val opts = RequestOptions(thinking = model.state.value.thinkingLevel, modelCatalogs = model.state.value.catalogs)
            assertEquals("max", OpenAiCompatProvider(okhttp3.OkHttpClient(), ProviderFactory.json)
                .buildRequestBody(config, "fixture", emptyList(), emptyList(), opts)["reasoning_effort"]!!.jsonPrimitive.content)
            assertNull(model.state.value.reasoningConfigs[CustomReasoning.key(config)])
            c.providers.refreshCatalog(config.id, automatic = true)
            assertEquals(2, requests.get())
            val custom = CustomReasoningConfig(listOf("My level"), """{"reasoning_effort":"high"}""")
            c.settings.setReasoningConfig(CustomReasoning.key(config), custom)
            assertNull(c.providers.refreshCatalog(config.id))
            assertEquals(custom, c.settings.settings.first().reasoningConfigs[CustomReasoning.key(config)])
            assertEquals(3, requests.get())
            responseBody.set("""{"data":[{"id":"new-model","effort":{"supported_levels":["low","high","max","extreme"]}}]}""")
            assertEquals(listOf("Low", "High", "Max", "Extreme"), model.detectReasoningLevels(config))
            assertEquals(4, requests.get())
            assertEquals(custom, c.settings.settings.first().reasoningConfigs[CustomReasoning.key(config)])
            responseBody.set("{}")
            c.providers.update(config, null)
            assertNotNull(c.providers.refreshCatalog(config.id, automatic = true))
            assertEquals(listOf("low", "high", "max", "extreme"), c.providers.catalog(config.id).single().reasoningEfforts)
            assertEquals(custom, c.settings.settings.first().reasoningConfigs[CustomReasoning.key(config)])
        } finally {
            instrumentation.runOnMainSync { vm?.viewModelScope?.cancel() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.providers.delete(config.id)
            c.settings.restorePortable(original)
            socket.close(); server.join(1000)
        }
    }

    @Test fun forceDetectionKeepsCelAndKeepsTheDraftWhenRetryFails() {
        val config = ProviderConfig("force-detection-fixture", "Fixture", ProviderType.OPENAI_COMPAT,
            "https://fixture.invalid/v1", "new-model")
        val mapping = """{"reasoning_effort": reasoningLevel}"""
        val draft = CustomReasoningConfig(listOf("Low", "High"), mapping)
        val detected = listOf("Minimal", "Low", "Medium", "High", "X-High", "Max", "Ultra", "Extreme")
        val saved = mutableStateOf<CustomReasoningConfig?>(null)
        val firstCheck = kotlinx.coroutines.CompletableDeferred<Unit>()
        var checks = 0
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { HarnessTheme {
                Surface(Modifier.fillMaxSize()) {
                    CustomReasoningSheet(config, draft, ThinkingSpecs.forProvider(config), onDismiss = {},
                        onSave = { saved.value = it }, onDetectLevels = {
                            checks++
                            if (checks > 1) error("This provider does not report reasoning levels for this model.")
                            firstCheck.await()
                            detected
                        })
                }
            } } }
            tap("Auto-detect levels"); await("Checking provider…")
            firstCheck.complete(Unit)
            await("Detected 8 levels. Save to apply.")
            reveal("Extreme"); reveal("X-High", backwards = true)
            screenshot("force-detection-all-levels")
            reveal("Auto-detect levels", backwards = true)
            tap("Auto-detect levels")
            await("This provider does not report reasoning levels for this model.")
            reveal("Extreme"); reveal("X-High", backwards = true)
            tap("Save")
            val until = SystemClock.uptimeMillis() + 10000
            while (saved.value == null && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
            assertEquals(detected, saved.value?.levels)
            assertEquals(mapping, saved.value?.mapping)
            assertEquals(2, checks)
        }
    }

    @Test fun migrationReopenBackupAndForkKeepReasoningWithoutLosingOldChats() = runBlocking {
        val name = "reasoning-${System.nanoTime()}.db"
        val importedName = "reasoning-import-${System.nanoTime()}.db"
        val backup = File(context.cacheDir, "$name.json")
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var imported: AppDatabase? = null
        try {
            var sessions = SessionRepository(db)
            val sid = sessions.createSession("Keep existing history")
            sessions.addMessage(sid, ChatMessage(Role.USER, "Existing question"), "turn")
            db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("ALTER TABLE messages DROP COLUMN generationJson")
                it.version = 17
            }
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_21_22, AppDatabase.MIGRATION_20_21, AppDatabase.MIGRATION_17_18, AppDatabase.MIGRATION_19_20, AppDatabase.MIGRATION_18_19).build()
            sessions = SessionRepository(db)
            assertEquals("Existing question", sessions.messages(sid).single().text)
            assertNull(sessions.messages(sid).single().generation.state)
            val c = ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-6.1-sol")
            val assistant = ChatMessage(Role.ASSISTANT, "Answer", thinking = "Visible summary", thinkingMs = 5_000, turnId = "turn")
            val state = ProviderMessageState.of(c, "responses",
                listOf(Json.parseToJsonElement("""{"type":"reasoning","id":"r1","encrypted_content":"fixture-opaque"}""").jsonObject)).boundTo(assistant)
            sessions.addMessage(sid, assistant.copy(generation = GenerationMetadata(state, 42, "Thinking: Max")), "turn")
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            sessions = SessionRepository(db)
            val restored = sessions.messages(sid).last()
            assertEquals("Answer", restored.text)
            assertEquals(42, restored.generation.reasoningTokens)
            assertEquals(5_000L, restored.thinkingMs)
            assertTrue(restored.generation.state!!.matches(c, "responses", restored))
            val fork = sessions.forkSession(sid, restored.id!!)
            assertEquals(restored.generation, sessions.messages(fork).last().generation)
            ChatBackupManager(db, context).exportTo(Uri.fromFile(backup))
            imported = Room.databaseBuilder(context, AppDatabase::class.java, importedName).build()
            run {
                val manager = ChatBackupManager(imported, context)
                val target = com.androidharness.app.workspace.WorkspaceManager(context, imported.dao()).currentProjectOnce()
                val preview = manager.prepareImport(Uri.fromFile(backup))
                val all = preview.chats.map { it.info.id }.toSet()
                manager.importPrepared(preview, manager.missingWorkspaces(preview, all, com.androidharness.app.data.DuplicatePolicy.SKIP, true).associate { it.id to target.id })
            }
            assertEquals(restored.generation, SessionRepository(imported).messages(sid).last().generation)
            assertEquals(2, SessionRepository(imported).messages(sid).size)
        } finally {
            db.close(); imported?.close()
            context.deleteDatabase(name); context.deleteDatabase(importedName); backup.delete()
        }
    }

    @Test fun pickerShowsNativeLevelsAndAutomaticAndSeparatesReasoningFromTextTokens() {
        val original = ModelsDev.providers().associate { it.id to ModelsDev.modelsFor(it.id) }
        val originalProviders = ModelsDev.providers()
        ModelsDev.replaceForTesting(emptyMap())
        val selected = mutableStateOf(ThinkingLevel.MAX)
        val provider = mutableStateOf(ProviderConfig("fixture", "DeepSeek", ProviderType.OPENAI_COMPAT,
            "https://api.deepseek.com/v1", "deepseek-v4-flash"))
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { HarnessTheme {
                    Surface(Modifier.fillMaxSize()) { Column {
                        val resolved = ThinkingSpecs.resolve(provider.value, selected.value)
                        androidx.compose.runtime.CompositionLocalProvider(com.androidharness.app.ui.LocalDesktopLayout provides
                            (InstrumentationRegistry.getArguments().getString("desktop") == "true")) {
                        MainHeader("Reasoning controls", true, provider.value.model, AgentMode.ACT,
                            thinkingLevel = resolved.level, thinkingLevels = resolved.spec.levels,
                            permissionMode = PermissionMode.CONFIRM_ALL, canUndo = false,
                            onOpenDrawer = {}, onPickModel = {}, onOpenTerminal = {}, onSetThinking = { selected.value = it },
                            onSetPermission = {}, onSetMode = {}, onOpenContext = {}, onOpenUndo = {},
                            onOpenFiles = {}, onOpenGitHub = {}, thinkingResolved = resolved)
                        }
                        ThinkingBlock("Short summary")
                    } }
                } } }
                await("Thinking")
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "5K reasoning tokens"))
                tap("Thinking level")
                await("Low"); await("High"); await("Max")
                await("Applies to your next message")
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Ultra"))
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Medium"))
                tap("High")
                instrumentation.waitForIdleSync()
                assertEquals(ThinkingLevel.HIGH, selected.value)
                val dismissedBy = SystemClock.uptimeMillis() + 5_000
                while (find(instrumentation.uiAutomation.rootInActiveWindow, "Switch model…") != null && SystemClock.uptimeMillis() < dismissedBy)
                    SystemClock.sleep(50)
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Switch model…"))
                await("High")
                screenshot("native-levels")
                scenario.onActivity { provider.value = provider.value.copy(baseUrl = "https://custom.example/v1", model = "custom-model") }
                await("Thinking")
                tap("Thinking level")
                await("Levels aren't reported for this model.")
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Low"))
                screenshot("automatic")
            }
        } finally { ModelsDev.replaceForTesting(original, originalProviders) }
    }

    @Test fun customEditorSavesNormalizedLevelsAndEvaluatesCelOnAndroid() = runBlocking {
        val prefs = SettingsRepository(context)
        val original = prefs.settings.first()
        val config = ProviderConfig("custom-reasoning-fixture", "DeepSeek", ProviderType.OPENAI_COMPAT,
            "https://custom.example/v1", "new-reasoning-model")
        val key = CustomReasoning.key(config)
        val custom = mutableStateOf<CustomReasoningConfig?>(CustomReasoningConfig(listOf("low", "HIGH", "max")))
        val selected = mutableStateOf("max")
        val saved = mutableStateOf(false)
        val editing = mutableStateOf(false)
        val githubOpens = mutableStateOf(0)
        val adding = mutableStateOf(false)
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { HarnessTheme {
                    Surface(Modifier.fillMaxSize()) { Column {
                        val options = RequestOptions(thinking = ThinkingLevel.HIGH,
                            customReasoning = custom.value?.let { mapOf(key to it) }.orEmpty(),
                            customThinkingLevels = mapOf(key to selected.value))
                        val resolved = options.resolveThinking(config)
                        androidx.compose.runtime.CompositionLocalProvider(com.androidharness.app.ui.LocalDesktopLayout provides
                            (InstrumentationRegistry.getArguments().getString("desktop") == "true")) {
                        MainHeader("Custom reasoning", false, "DeepSeek", AgentMode.ACT,
                            thinkingLevel = resolved.level, thinkingLevels = resolved.spec.levels,
                            permissionMode = PermissionMode.CONFIRM_ALL, canUndo = false,
                            onOpenDrawer = {}, onPickModel = {}, onOpenTerminal = {}, onSetThinking = {},
                            onSetPermission = {}, onSetMode = {}, onOpenContext = {}, onOpenUndo = {},
                            onOpenFiles = {}, onOpenGitHub = { githubOpens.value++ }, thinkingResolved = resolved,
                            onCustomizeThinking = { adding.value = false; editing.value = true },
                            onAddThinkingLevel = { adding.value = true; editing.value = true },
                            onSetCustomThinking = { selected.value = CustomReasoning.wire(it) })
                        }
                        if (editing.value) CustomReasoningSheet(config, custom.value, ThinkingSpecs.forProvider(config), selectedLevel = selected.value, startAdding = adding.value,
                            onDismiss = { editing.value = false }, onSave = { custom.value = it; saved.value = true; editing.value = false })
                    } }
                } } }
                await("Max")
                val actionLabel = if (InstrumentationRegistry.getArguments().getString("desktop") == "true") "Conversation actions" else "More options"
                val actionBounds = Rect().also { await("Open GitHub").getBoundsInScreen(it) }
                val menuBounds = Rect().also { await(actionLabel).getBoundsInScreen(it) }
                val thinkingBounds = Rect().also { await("Thinking level").getBoundsInScreen(it) }
                assertEquals(thinkingBounds.centerY().toDouble(), actionBounds.centerY().toDouble(), 12.0)
                assertTrue(actionBounds.left > thinkingBounds.right)
                if (InstrumentationRegistry.getArguments().getString("desktop") != "true") {
                    val filesBounds = Rect().also { await("Workspace files").getBoundsInScreen(it) }
                    assertEquals(filesBounds.centerY().toDouble(), menuBounds.centerY().toDouble(), 12.0)
                    assertTrue(menuBounds.bottom <= actionBounds.top)
                }
                screenshot("custom-header")
                tap("Open GitHub")
                instrumentation.waitForIdleSync()
                assertEquals(1, githubOpens.value)
                tap(actionLabel); tap("GitHub")
                instrumentation.waitForIdleSync()
                assertEquals(2, githubOpens.value)
                tap("Thinking level")
                val addBounds = Rect().also { await("Add thinking level").getBoundsInScreen(it) }
                val switchBounds = Rect().also { await("Switch model…").getBoundsInScreen(it) }
                assertTrue("Add thinking level must sit directly above Switch model", addBounds.bottom <= switchBounds.top)
                screenshot("thinking-dropdown")
                tap("Customize thinking")
                await("Reasoning levels")
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "CEL expression (optional)"))
                tap("About reasoning levels"); await(CustomReasoning.LEVEL_HELP); tap("Got it")
                screenshot("custom-editor")
                reveal("Advanced request mapping"); tap("Advanced request mapping")
                reveal("Using inherited model/provider mapping")
                reveal("Edit inherited mapping"); tap("Edit inherited mapping")
                reveal("Using custom CEL mapping")
                reveal("Use inherited mapping"); tap("Use inherited mapping")
                reveal("Using inherited model/provider mapping")
                reveal("\"reasoning_effort\": \"max\"", contains = true)
                screenshot("inherited-mapping-preview")
                tap("Save")
                val until = SystemClock.uptimeMillis() + 15_000
                while (!saved.value && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
                assertTrue("CEL must compile and evaluate on Android before saving", saved.value)
                assertEquals(listOf("Low", "High", "Max"), custom.value!!.levels)
                assertEquals("", custom.value!!.mapping)
                prefs.setReasoningConfig(key, custom.value)
                assertEquals(custom.value, prefs.settings.first().reasoningConfigs[key])
                tap("Thinking level"); tap("High")
                awaitHidden("Switch model…")
                val chosen = RequestOptions(customReasoning = mapOf(key to custom.value!!), customThinkingLevels = mapOf(key to selected.value))
                val request = OpenAiCompatProvider(okhttp3.OkHttpClient(), ProviderFactory.json)
                    .buildRequestBody(config, "fixture", emptyList(), emptyList(), chosen)
                assertEquals("high", request["reasoning_effort"]!!.jsonPrimitive.content)
                tap("Thinking level"); tap("Customize thinking"); tap("Use defaults")
                assertNull(custom.value)
                saved.value = false
                tap("Thinking level"); tap("Add thinking level")
                fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.isEditable) return node
                    for (i in 0 until node.childCount) editable(node.getChild(i))?.let { return it }
                    return null
                }
                val input = editable(await("Level 2 name"))
                    ?: editable(instrumentation.uiAutomation.rootInActiveWindow) ?: error("No level name input")
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "max")
                }))
                await("max")
                tap("Done editing level"); tap("Save")
                val addUntil = SystemClock.uptimeMillis() + 10000
                while (!saved.value && SystemClock.uptimeMillis() < addUntil) SystemClock.sleep(100)
                assertEquals(listOf("On", "Max"), custom.value!!.levels)
                tap("Thinking level"); tap("Customize thinking")
                tap("Level 1 actions"); tap("Move down")
                tap("Level 2 actions"); tap("Remove")
                tap("Save")
                val reorderUntil = SystemClock.uptimeMillis() + 10000
                while (custom.value?.levels?.size != 1 && SystemClock.uptimeMillis() < reorderUntil) SystemClock.sleep(100)
                assertEquals(listOf("Max"), custom.value!!.levels)
            }
        } finally { prefs.restorePortable(original) }
    }

    @Test fun customLevelSelectionKeepsEachChatAndSharedScopeIndependent() = runBlocking {
        val prefs = SettingsRepository(context)
        val original = prefs.settings.first()
        val name = "custom-levels-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val key = CustomReasoning.key(ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://fixture.invalid/v1", "model"))
        try {
            val sessions = SessionRepository(db)
            val providers = (context.applicationContext as com.androidharness.app.HarnessApp).container.providers
            val chats = ChatModelRepository(prefs, providers, sessions)
            val a = sessions.createSession("Reasoning A")
            val b = sessions.createSession("Reasoning B")
            prefs.setModelSelectionScope(false)
            chats.initialize(a); chats.initialize(b)
            chats.setCustomThinking(a, key, "max")
            chats.setCustomThinking(b, key, "low")
            assertEquals("max", chats.observe(a).first().selection.customThinkingLevels[key])
            assertEquals("low", chats.observe(b).first().selection.customThinkingLevels[key])
            chats.setShared(a, true)
            assertEquals("max", prefs.settings.first().customThinkingLevels[key])
            chats.setCustomThinking(null, key, "high")
            assertEquals("high", chats.observe(b).first().selection.customThinkingLevels[key])
            chats.setShared(a, false)
            assertEquals("max", chats.observe(a).first().selection.customThinkingLevels[key])
            assertEquals("low", chats.observe(b).first().selection.customThinkingLevels[key])
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals("max", ChatModelSelection.decode(SessionRepository(db).session(a)!!.modelSelectionJson)!!.customThinkingLevels[key])
        } finally { db.close(); context.deleteDatabase(name); prefs.restorePortable(original) }
    }

    private fun reveal(text: String, contains: Boolean = false, backwards: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            find(root, text, contains)?.let { return it }
            fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isScrollable && !node.isEditable) return node
                for (i in 0 until node.childCount) scrollable(node.getChild(i))?.let { return it }
                return null
            }
            scrollable(root)?.performAction(if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
        screenshot("editor-content-missing")
        error("Missing editor content: $text")
    }

    private fun find(node: AccessibilityNodeInfo?, text: String, contains: Boolean = false): AccessibilityNodeInfo? {
        if (node == null) return null
        val values = listOf(node.text?.toString(), node.contentDescription?.toString(),
            if (android.os.Build.VERSION.SDK_INT >= 28) node.paneTitle?.toString() else null)
        if (values.any { it != null && if (contains) it.contains(text, ignoreCase = true) else it == text }) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text, contains)?.let { return it }
        return null
    }
    private fun await(text: String, contains: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            find(instrumentation.uiAutomation.rootInActiveWindow, text, contains)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing UI: $text")
    }
    private fun tap(text: String) {
        var node: AccessibilityNodeInfo? = await(text)
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        val bounds = Rect().also { await(text).getBoundsInScreen(it) }
        instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").close()
    }
    private fun awaitHidden(text: String) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            if (find(instrumentation.uiAutomation.rootInActiveWindow, text) == null) return
            SystemClock.sleep(50)
        }
        error("UI did not close: $text")
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val dir = File(context.getExternalFilesDir(null), "qa/reasoning").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
