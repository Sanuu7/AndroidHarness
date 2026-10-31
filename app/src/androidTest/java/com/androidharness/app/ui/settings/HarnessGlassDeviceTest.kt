package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.chat.ChatScreen
import com.androidharness.app.ui.chat.ChatViewModel
import androidx.lifecycle.ViewModelStore
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.ui.theme.LocalHarnessGlassScene
import com.androidharness.app.ui.theme.recordHarnessGlassScene
import com.androidharness.app.ui.theme.harnessGlassChrome
import com.androidharness.app.ui.theme.LocalHarnessGlassChromeBackdrop
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.androidharness.app.ui.theme.HarnessGlassScene
import com.androidharness.app.ui.theme.LocalHarnessGlassBackdrop
import com.androidharness.app.ui.common.ExpressiveDropdownMenu
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@RunWith(AndroidJUnit4::class)
class HarnessGlassDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository get() = (context.applicationContext as HarnessApp).container.settings

    @org.junit.Before fun requiresAvailableGlassFeature() {
        // These are opt-in experiment tests; LearnGuideDeviceTest verifies the hidden/off state.
        assumeTrue(com.androidharness.app.data.FeatureAvailability.HARNESS_GLASS)
    }

    @Test fun providerConnectionFormKeepsItsControlsWorkingOnSharedGlass() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val provider = com.androidharness.app.llm.ProviderConfig("glass-form", "Glass connection",
            com.androidharness.app.llm.ProviderType.OPENAI_COMPAT, "https://example.com", "alpha")
        var saved = false
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                host.setContent {
                    HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false, glassEnabled = true, liquidGlass = true) {
                        ProviderSheet(provider, { com.androidharness.app.llm.ProviderKeyPool(listOf(com.androidharness.app.llm.ProviderApiKey(value = "")), rotate = false) }, {},
                            onSave = { _, _, _, _, _, _ -> saved = true })
                    }
                }
            }
            await("Connect Custom endpoint")
            val label = context.getString(com.androidharness.app.R.string.provider_rotate_keys)
            fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isScrollable) return node
                for (i in 0 until node.childCount) scrollable(node.getChild(i))?.let { return it }
                return null
            }
            repeat(8) {
                instrumentation.uiAutomation.clearCache()
                if (find(instrumentation.uiAutomation.rootInActiveWindow, label) == null) {
                    scrollable(instrumentation.uiAutomation.rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(200)
                }
            }
            tap(label)
            awaitChecked(label)
            assertFalse("Editing the glass form never saves without the user's Save action", saved)
        }
    }

    @Test fun glassModelAndProviderSheetsKeepBrowsingSeparateFromSelection() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val first = com.androidharness.app.llm.ProviderConfig("glass-one", "Glass Provider One",
            com.androidharness.app.llm.ProviderType.OPENAI_COMPAT, "https://example.com", "alpha")
        val second = first.copy(id = "glass-two", name = "Glass Provider Two", model = "beta")
        val open = mutableStateOf(true)
        var selected = ""
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                host.setContent {
                    HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false, glassEnabled = true, liquidGlass = true, glassTransparency = 1f) {
                        Box(Modifier.fillMaxSize().recordHarnessGlassScene(LocalHarnessGlassScene.current).background(Color(0xFF773D28))) {
                            Column { repeat(25) { Text("Conversation behind the model picker $it", Modifier.padding(12.dp), color = Color.White) } }
                        }
                        if (open.value) com.androidharness.app.ui.chat.components.ModelPickerSheet(
                            providers = listOf(first, second), activeProviderId = first.id, activeModel = "alpha",
                            catalogs = mapOf(first.id to listOf(com.androidharness.app.llm.ModelEntry("alpha", displayName = "Alpha model")),
                                second.id to listOf(com.androidharness.app.llm.ModelEntry("beta", displayName = "Beta model"))),
                            onDismiss = { open.value = false }, onSelect = { id, model -> selected = "$id/$model" },
                            onRefreshCatalog = { null }, onManageProviders = {})
                    }
                }
            }
            await("Alpha model")
            SystemClock.sleep(500)
            screenshot("model-picker-clear-glass").recycle()
            tap("Switch provider")
            await("Glass Provider Two")
            screenshot("provider-picker-clear-glass").recycle()
            tap("Glass Provider Two")
            screenshot("model-picker-after-provider").recycle()
            tap(context.getString(com.androidharness.app.R.string.ui_choose_from, "Beta model", "Glass Provider Two"))
            assertEquals("Browsing must not change the active model", "", selected)
            tap("Use model")
            assertEquals("glass-two/beta", selected)
        }
    }

    @Test fun embeddedWebContentStaysInteractiveUnderGlassOverlays() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val response = "```harness-ui\n" + buildJsonObject {
            put("type", "preview"); put("title", "Embedded glass fixture")
            put("html", """<div style="color:white;background:#244b79;padding:24px;height:360px"><h2>Live embedded content</h2><button onclick="this.textContent='Clicked'">Try preview</button><p>Web content stays outside the glass capture.</p></div>""")
        } + "\n```"
        repeat(3) {
            val menuOpen = mutableStateOf(false)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    host.setContent {
                        HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false, glassEnabled = true, liquidGlass = true) {
                            com.androidharness.app.ui.theme.HarnessCanvasBackdrop(com.androidharness.app.data.HarnessCanvasSettings(enabled = false), true, Modifier.fillMaxSize()) {
                            val rasterScene = requireNotNull(com.androidharness.app.ui.theme.rememberHarnessGlassScene())
                            val rasterBackdrop = rememberCombinedBackdrop(requireNotNull(LocalHarnessGlassBackdrop.current), rasterScene)
                            Box(Modifier.fillMaxSize().safeDrawingPadding().recordHarnessGlassScene(LocalHarnessGlassScene.current)) {
                                Column(Modifier.fillMaxSize().recordHarnessGlassScene(rasterScene, fadeTop = 96f, fadeBottom = 140f)
                                    .verticalScroll(rememberScrollState()).padding(20.dp)) {
                                    com.androidharness.app.ui.chat.rich.RichResponseContent(response, false, null, null)
                                    com.androidharness.app.ui.chat.MarkdownText("Equation: $" + "E=mc^2" + "$\n\n```mermaid\ngraph TD\n A[Start] --> B[Ready]\n```")
                                }
                                androidx.compose.runtime.CompositionLocalProvider(LocalHarnessGlassChromeBackdrop provides rasterBackdrop) {
                                repeat(8) { lensIndex ->
                                Box(Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(start = 20.dp, end = 20.dp, top = (80 + lensIndex * 65).dp)
                                    .fillMaxWidth().height(56.dp).harnessGlassChrome(Color(0xFF20242A), RoundedCornerShape(28.dp))) {
                                    Text("Glass overlay", Modifier.padding(16.dp), color = Color.White)
                                }
                                }
                                }
                                Box(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(20.dp)) {
                                    Text("Preview menu", Modifier.clickable { menuOpen.value = true }.padding(12.dp))
                                    ExpressiveDropdownMenu(menuOpen.value, { menuOpen.value = false }) {
                                        DropdownMenuItem(text = { Text("Preview actions") }, onClick = { menuOpen.value = false })
                                    }
                                }
                            }
                            }
                        }
                    }
                }
                await("Embedded glass fixture")
                SystemClock.sleep(2200)
                screenshot("embedded-before-click-$it").recycle()
                await("Try preview").performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                val button = Rect().also { await("Try preview").getBoundsInScreen(it) }
                val now = SystemClock.uptimeMillis()
                listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
                    android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, button.exactCenterX(), button.exactCenterY(), 0).let { event ->
                        instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
                    }
                }
                await("Clicked")
                screenshot("embedded-web-glass-$it").recycle()
                tap("Preview menu")
                await("Preview actions")
                screenshot("embedded-web-popup-$it").recycle()
                tap("Preview actions")
                await("Clicked")
            }
        }
    }

    @Test fun welcomeSuggestionsAndRichTabsKeepTheirActionsWithAdjustableGlass() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val rich = mutableStateOf(false)
        val chat = mutableStateOf(false)
        val transparency = mutableStateOf(.5f)
        var selected = ""
        val response = """```harness-ui
            {"type":"card","title":"Glass response","text":"A readable card on glass.","children":[{"type":"tabs","title":"Explore","tabs":[{"title":"First","children":[{"type":"text","text":"First tab content"}]},{"title":"Second","children":[{"type":"text","text":"Second tab content"}]}]},{"type":"profile","name":"Glass profile","description":"Profile details remain readable.","sources":[{"title":"Fixture source","url":"https://example.com"}]}]}
            ```""".trimIndent()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                host.setContent {
                    HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false, glassEnabled = true,
                        liquidGlass = true, glassTransparency = transparency.value) {
                        if (rich.value) Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
                            com.androidharness.app.ui.chat.rich.RichResponseContent(response, false, null, null)
                        } else com.androidharness.app.ui.chat.components.EmptyState(true, { selected = it }, {}, chatOnly = chat.value,
                            modifier = Modifier.fillMaxSize().safeDrawingPadding())
                    }
                }
            }
            tap("Create a Python hello world and run it")
            assertEquals("Create a Python hello world and run it", selected)
            screenshot("welcome-code-glass").recycle()
            instrumentation.runOnMainSync { chat.value = true }
            tap("Explain a tricky topic simply")
            assertEquals("Explain a tricky topic simply", selected)
            screenshot("welcome-chat-glass").recycle()
            instrumentation.runOnMainSync { rich.value = true; transparency.value = 0f }
            await("Glass profile")
            tap("Second")
            await("Second tab content")
            val opaque = screenshot("rich-glass-opaque")
            instrumentation.runOnMainSync { transparency.value = 1f }
            SystemClock.sleep(400)
            await("Second tab content")
            val clear = screenshot("rich-glass-clear")
            var different = 0
            for (y in 200 until clear.height - 200 step 8) for (x in 40 until clear.width - 40 step 8) {
                if (opaque.getPixel(x, y) != clear.getPixel(x, y)) different++
            }
            opaque.recycle(); clear.recycle()
            assertTrue("Transparency must change rendered rich cards while preserving the selected tab", different > 100)
        }
    }

    @Test fun floatingConversationInputAndJumpToLatestWorkWithoutCanvas() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val container = (context.applicationContext as HarnessApp).container
        val savedCanvas = repository.settings.first().harnessCanvas
        val sid = container.sessions.createSession("Glass chrome fixture", conversation = ConversationConfig(kind = ConversationKind.CHAT))
        val store = ViewModelStore()
        var screen: HarnessGlassScene? = null
        try {
            repository.updateHarnessCanvas { it.copy(enabled = false) }
            container.sessions.addMessage(sid, ChatMessage(Role.USER, "Show the glass conversation controls."), "glass-turn")
            val reply = (1..60).joinToString("\n\n") { "Conversation paragraph $it is behind the floating glass controls." } +
                "\n\n```harness-ui\n{\"type\":\"callout\",\"title\":\"End of glass response\",\"text\":\"The final reply remains above the composer.\"}\n```"
            container.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT, reply), "glass-turn")
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val vm = ChatViewModel(container, sid)
                    store.put("glass-chat", vm)
                    host.setContent {
                        HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false, glassEnabled = true, liquidGlass = true, glassTransparency = 1f) {
                            val scene = LocalHarnessGlassScene.current
                            androidx.compose.runtime.SideEffect { screen = scene }
                            Box(Modifier.fillMaxSize().recordHarnessGlassScene(LocalHarnessGlassScene.current)) {
                                ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {})
                            }
                        }
                    }
                }
                await("Message input")
                val navigation = Rect().also { await("Open navigation").getBoundsInScreen(it) }
                scenario.onActivity { host ->
                    val safe = requireNotNull(host.window.decorView.rootWindowInsets).getInsets(
                        android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout())
                    assertTrue("Header controls stay below the camera/status-bar safe area", navigation.top >= safe.top)
                    assertTrue("Header controls clear a side cutout", navigation.left >= safe.left)
                }
                screenshot("send-disabled-clear-glass").recycle()
                fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.isEditable && node.isVisibleToUser) return node
                    for (i in 0 until node.childCount) editable(node.getChild(i))?.let { return it }
                    return null
                }
                val input = requireNotNull(editable(instrumentation.uiAutomation.rootInActiveWindow))
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                SystemClock.sleep(300)
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Glass draft")
                }))
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                SystemClock.sleep(300)
                await("Glass draft")
                await("Scroll to latest")
                screenshot("floating-controls-over-conversation-no-canvas").recycle()
                scenario.onActivity { assertFalse("Closed chat menus must not keep the screen capture active", requireNotNull(screen).captureRequired) }
                InstrumentationRegistry.getArguments().getString("glassProfile")?.let { label ->
                    profileGlassScroll(scenario, label)
                    if (InstrumentationRegistry.getArguments().getString("glassCompareCapture") == "true") {
                        var release: (() -> Unit)? = null
                        scenario.onActivity { release = requireNotNull(screen).retainCapture() }
                        try { profileGlassScroll(scenario, "$label-forced-capture") }
                        finally { scenario.onActivity { release?.invoke() } }
                        profileGlassScroll(scenario, "$label-repeat")
                    }
                }
                tap("Scroll to latest")
                await("End of glass response")
                await("Glass draft")
                screenshot("floating-input-and-jump-no-canvas").recycle()
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            container.sessions.session(sid)?.let { container.sessions.deleteSession(it) }
            repository.updateHarnessCanvas { savedCanvas }
        }
    }

    @Test fun popupSamplesTheConversationAcrossWindowsWithoutCanvas() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val blue = mutableStateOf(false)
        val open = mutableStateOf(true)
        var screen: HarnessGlassScene? = null
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                host.setContent {
                    HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false, glassEnabled = true, liquidGlass = true) {
                        val scene = LocalHarnessGlassScene.current
                        androidx.compose.runtime.SideEffect { screen = scene }
                        Box(Modifier.fillMaxSize().safeDrawingPadding().recordHarnessGlassScene(LocalHarnessGlassScene.current)
                            .background(if (blue.value) Color(0xFF164B9B) else Color(0xFF9B241B))) {
                            Column {
                                repeat(28) { Text("Conversation behind the menu · line $it", Modifier.padding(8.dp)) }
                            }
                            Box(Modifier.padding(start = 32.dp, top = 120.dp)) {
                                ExpressiveDropdownMenu(open.value, { open.value = false }) {
                                    DropdownMenuItem(text = { Text("Glass overlay sample") }, onClick = {})
                                    DropdownMenuItem(text = { Text("Underlying text stays behind") }, onClick = {})
                                }
                            }
                        }
                    }
                }
            }
            val bounds = Rect().also { await("Glass overlay sample").getBoundsInScreen(it) }
            SystemClock.sleep(600)
            scenario.onActivity { assertTrue("A visible popup retains the scene", requireNotNull(screen).captureRequired) }
            val red = screenshot("overlay-conversation-red-no-canvas")
            scenario.onActivity { open.value = false; blue.value = true }
            SystemClock.sleep(400)
            scenario.onActivity { assertFalse("A dismissed popup stops the full-screen capture", requireNotNull(screen).captureRequired) }
            scenario.onActivity { open.value = true }
            await("Glass overlay sample")
            SystemClock.sleep(600)
            scenario.onActivity { assertTrue("Reopening reactivates capture with fresh content", requireNotNull(screen).captureRequired) }
            val changed = screenshot("overlay-conversation-blue-no-canvas")
            var different = 0
            for (y in bounds.top until bounds.bottom step 3) for (x in bounds.left until bounds.right step 3) {
                if (red.getPixel(x, y) != changed.getPixel(x, y)) different++
            }
            red.recycle(); changed.recycle()
            assertTrue("The menu must sample the actual screen, not only the theme gradient", different > 150)
        }
    }

    @Test fun frostedDefaultsLiquidRefractionAndPersistentSwitchesWorkWithoutRestartingNavigation() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val saved = repository.settings.first()
        try {
            repository.setHarnessGlassEnabled(false)
            repository.setHarnessLiquidGlass(false)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                fun show(host: ChatListTestActivity) {
                    host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    host.setContent {
                    val settings by repository.settings.collectAsState(initial = saved.copy(harnessGlassEnabled = false, harnessLiquidGlass = false))
                    HarnessTheme(themeMode = ThemeMode.DARK, dynamicColor = false,
                        glassEnabled = settings.harnessGlassEnabled, liquidGlass = settings.harnessLiquidGlass,
                        glassTransparency = settings.harnessGlassTransparency) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            HarnessGlassSection(settings, repository)
                        }
                    }
                } }
                scenario.onActivity(::show)
                await("Frosted glass")
                awaitDisabled("Liquid Glass")
                tap("Frosted glass")
                withTimeout(8_000) { repository.settings.first { it.harnessGlassEnabled } }
                assertFalse(repository.settings.first().harnessLiquidGlass)
                SystemClock.sleep(700)
                val preview = Rect().also { await("Glass preview").getBoundsInScreen(it) }
                val frosted = screenshot("frosted")
                tap("Liquid Glass")
                withTimeout(8_000) { repository.settings.first { it.harnessLiquidGlass } }
                SystemClock.sleep(700)
                val liquid = screenshot("liquid")
                var different = 0
                for (y in preview.top until preview.bottom step 4) for (x in preview.left until preview.right step 4) {
                    if (frosted.getPixel(x, y) != liquid.getPixel(x, y)) different++
                }
                frosted.recycle(); liquid.recycle()
                assertTrue("The preview must visibly change from blur to refraction", different > 100)
                await("Transparency")
                fun rangeControl(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.rangeInfo != null && node.isVisibleToUser) return node
                    for (i in 0 until node.childCount) rangeControl(node.getChild(i))?.let { return it }
                    return null
                }
                val slider = requireNotNull(rangeControl(instrumentation.uiAutomation.rootInActiveWindow))
                assertTrue(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, .8f)
                }))
                withTimeout(8_000) { repository.settings.first { kotlin.math.abs(it.harnessGlassTransparency - .8f) < .01f } }
                scenario.recreate(); scenario.onActivity(::show)
                awaitChecked("Frosted glass")
                awaitChecked("Liquid Glass")
                await("80%")
                tap("Liquid Glass")
                withTimeout(8_000) { repository.settings.first { !it.harnessLiquidGlass } }
                assertTrue(repository.settings.first().harnessGlassEnabled)
                tap("Frosted glass")
                withTimeout(8_000) { repository.settings.first { !it.harnessGlassEnabled } }
                awaitDisabled("Liquid Glass")
                screenshot("off").recycle()
            }
        } finally {
            repository.setHarnessGlassEnabled(saved.harnessGlassEnabled)
            repository.setHarnessLiquidGlass(saved.harnessLiquidGlass)
            repository.setHarnessGlassTransparency(saved.harnessGlassTransparency)
        }
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isVisibleToUser && (node.text?.toString()?.lineSequence()?.any { it == label } == true || node.contentDescription?.toString() == label)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }
    private fun await(label: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 10_000
        do {
            instrumentation.uiAutomation.clearCache()
            find(instrumentation.uiAutomation.rootInActiveWindow, label)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < end)
        screenshot("missing-${label.replace(Regex("[^A-Za-z0-9]"), "-")}").recycle()
        throw AssertionError("Missing $label")
    }
    private fun switch(label: String): AccessibilityNodeInfo {
        var node = await(label)
        while (!node.isCheckable && node.parent != null) node = node.parent
        return node
    }
    private fun awaitChecked(label: String) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end) {
            if (switch(label).isChecked) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Switch did not restore: $label")
    }
    private fun awaitDisabled(label: String) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end) {
            if (!switch(label).isEnabled) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Switch did not become disabled: $label")
    }
    private fun tap(label: String) {
        var node = await(label)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(300)
    }
    /** Opt-in, repeatable on-device comparison; never scrolls or edits the user's release app. */
    private fun profileGlassScroll(scenario: ActivityScenario<ChatListTestActivity>, label: String) {
        val thread = android.os.HandlerThread("glass-frame-metrics").apply { start() }
        val rows = java.util.Collections.synchronizedList(mutableListOf<LongArray>())
        val metrics = intArrayOf(android.view.FrameMetrics.TOTAL_DURATION, android.view.FrameMetrics.GPU_DURATION,
            android.view.FrameMetrics.DRAW_DURATION, android.view.FrameMetrics.LAYOUT_MEASURE_DURATION,
            android.view.FrameMetrics.COMMAND_ISSUE_DURATION, android.view.FrameMetrics.DEADLINE)
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            rows.add(LongArray(metrics.size) { frame.getMetric(metrics[it]) })
        }
        val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val x = screen.width / 2
        val high = screen.height / 3
        val low = screen.height * 2 / 3
        screen.recycle()
        fun swipe(up: Boolean) {
            val start = if (up) low else high
            val end = if (up) high else low
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "input swipe $x $start $x $end 320")).use { it.readBytes() }
            SystemClock.sleep(450)
        }
        swipe(true); swipe(false)
        scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(thread.looper)) }
        try {
            repeat(12) { swipe(it % 4 < 2) }
        } finally {
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely(); thread.join()
        }
        val samples = synchronized(rows) { rows.toList() }
        assertTrue("Scrolling must produce enough hardware frames to compare", samples.size > 100)
        val result = org.json.JSONObject().put("label", label).put("frames", samples.size)
        listOf("total", "gpu", "draw", "layout", "command", "deadline").forEachIndexed { i, name ->
            val sorted = samples.map { it[i] / 1_000_000.0 }.sorted()
            result.put(name, org.json.JSONObject().put("p50_ms", sorted[sorted.size / 2])
                .put("p90_ms", sorted[(sorted.size * .9).toInt()]).put("p95_ms", sorted[(sorted.size * .95).toInt()]))
        }
        // Some vendor builds report DEADLINE as an absolute timestamp; retain it as
        // raw evidence, but do not derive a jank percentage from that value.
        File(context.getExternalFilesDir(null), "glass-qa/profile-$label.json").writeText(result.toString(2))
        android.util.Log.i("HarnessGlassProfile", result.toString())
    }

    private fun screenshot(name: String): Bitmap {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = File(context.getExternalFilesDir(null), "glass-qa").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
}
