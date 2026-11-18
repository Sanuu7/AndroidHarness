package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.browser.BrowserController
import com.androidharness.app.browser.BrowserToolActivity
import com.androidharness.app.core.*
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.chat.components.*
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BrowserToolCardDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container
    private val dark get() = InstrumentationRegistry.getArguments().getString("dark") == "true"

    @Test fun browserPageAndScreenshotsStayHiddenUntilWatchAtNormalAndLargeText(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "browser-card-${System.nanoTime()}").apply { mkdirs() }
        File(root, "index.html").writeText("<html><meta name='viewport' content='width=device-width,initial-scale=1'><h1>Browser watch fixture</h1><button>Fixture button</button></html>")
        val image = File(c.images.imagesDir, "browser-card-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        try {
            for (scale in listOf(1f, 1.5f)) {
                val controller = BrowserController(c.appContext, c.images)
                val running = mutableStateOf(true)
                val approval = mutableStateOf(false)
                val result = mutableStateOf<ChatMessage?>(null)
                val grouped = mutableStateOf(false)
                val call = ToolCallData("browser-card-$scale", "browser_screenshot", "{}")
                val url = BrowserController.localFileUrl("index.html")
                val completed = ChatMessage(role = Role.TOOL, toolCallId = call.id, toolName = call.name,
                    text = "Screenshot captured", images = listOf(ImageRef(image.name, "image/png")))
                val siblings = listOf(call, ToolCallData("read-a", "read_file", "{}"), ToolCallData("read-b", "read_file", "{}"))
                try {
                    ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                        scenario.onActivity { activity -> activity.setContent {
                            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                                HarnessTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                                    var watching by remember { mutableStateOf(false) }
                                    val watch: (String?) -> Unit = { target -> assertEquals(url, target); watching = true }
                                    Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                                        Column(Modifier.padding(16.dp)) {
                                            if (grouped.value) ToolGroupCard(siblings, siblings.associate { it.id to completed.copy(toolCallId = it.id,
                                                images = if (it.id == call.id) completed.images else emptyList()) }, emptySet(), { _, _ -> },
                                                onWatchBrowser = watch, browserTargets = mapOf(call.id to url))
                                            else ToolCallCard(call, result.value, running.value, { _, _ -> },
                                                awaitingApproval = approval.value, onWatchBrowser = watch, browserTarget = url)
                                        }
                                    }
                                    if (watching) WebPreviewSheet(initialTarget = url, workspace = FileFs(root), browserController = controller,
                                        agentActivity = BrowserToolActivity.from(call, result.value, running.value, url), agentRunning = running.value,
                                        onStopAgent = { running.value = false }, onDismiss = { watching = false })
                                }
                            }
                        } }
                        await("Capturing screenshot…"); assertFits("Watch browser")
                        assertNull(find { it.className?.toString()?.contains("WebView") == true })
                        screenshot("browser-card-$scale-running")
                        tap("Watch browser"); await("Browser watch fixture")
                        assertNotNull(find { it.className?.toString()?.contains("WebView") == true })
                        screenshot("browser-card-$scale-watch")
                        tap("Stop agent"); await("Not completed · Capture screenshot")
                        assertFalse(running.value)
                        tap("More browser actions"); tap("Close"); await("Watch browser")
                        instrumentation.runOnMainSync { result.value = completed }
                        await("Capture screenshot"); SystemClock.sleep(300)
                        assertNull(find { it.contentDescription?.toString() == image.name })
                        assertNull(find { it.className?.toString()?.contains("WebView") == true })
                        screenshot("browser-card-$scale-completed")
                        instrumentation.runOnMainSync { result.value = completed.copy(isError = true, text = "Capture failed") }
                        await("Failed"); assertFits("Watch browser")
                        instrumentation.runOnMainSync { result.value = null; approval.value = true }
                        await("Needs approval"); assertFits("Watch browser")
                        screenshot("browser-card-$scale-approval")
                        instrumentation.runOnMainSync { result.value = completed; grouped.value = true }
                        await("Ran 3 tools"); SystemClock.sleep(300)
                        assertNull(find { it.contentDescription?.toString() == image.name })
                        tap("Expand"); tap("Capture screenshot"); await("Watch browser")
                        assertNull(find { it.contentDescription?.toString() == image.name })
                    }
                } finally { controller.close() }
            }
        } finally { image.delete(); root.deleteRecursively() }
    }

    @Test fun savedChatBrowserActivityOpensOnlyThroughWatch(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "browser-chat-${System.nanoTime()}").apply { mkdirs() }
        File(root, "index.html").writeText("<html><h1>Saved browser page</h1></html>")
        val previous = c.workspace.currentProjectOnce()
        val project = c.workspace.addShellProject(root.path)
        val sid = c.sessions.createSession("Browser activity fixture", project.id)
        val turn = java.util.UUID.randomUUID().toString()
        val call = ToolCallData("saved-browser", "browser_get_dom", "{}")
        c.sessions.addMessage(sid, ChatMessage(role = Role.USER, text = "Inspect the page"), turn)
        c.sessions.addMessage(sid, ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(call)), turn)
        c.sessions.addMessage(sid, ChatMessage(role = Role.TOOL, toolCallId = call.id, toolName = call.name,
            text = "URL: ${BrowserController.localFileUrl("index.html")}\nTitle: Saved browser page"), turn)
        c.sessions.addMessage(sid, ChatMessage(role = Role.ASSISTANT, text = "Page inspected."), turn)
        var vm: ChatViewModel? = null
        try {
            instrumentation.runOnMainSync { vm = ChatViewModel(c, sid) }
            withTimeout(10000) { vm!!.state.first { it.messages.any { m -> m.text == "Page inspected." } } }
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                    ChatScreen(vm!!, {}, { _, _ -> }, {}, {}, onOpenSubagent = {})
                } } }
                await("Work summary"); tap("Expand activity"); tap("Read the page once"); tap("Read page content"); await("Watch browser")
                assertNull(find { it.className?.toString()?.contains("WebView") == true })
                tap("Watch browser"); await("Saved browser page")
                screenshot("browser-saved-chat-watch")
                tap("More browser actions"); tap("Close"); await("Watch browser")
                assertNull(find { it.className?.toString()?.contains("WebView") == true })
            }
        } finally {
            instrumentation.runOnMainSync { vm?.viewModelScope?.cancel() }
            c.sessions.sessions.first().firstOrNull { it.id == sid }?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project); c.workspace.setActiveProject(previous.id); root.deleteRecursively()
        }
    }

    private fun find(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && match(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun await(text: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) {
            // A tappable row merges its texts into one label, such as "Capture screenshot, Workspace page".
            find { node -> listOfNotNull(node.text, node.contentDescription).any { it.toString() == text || text in it.split(", ") } }?.let { return it }
            find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        screenshot("browser-card-failed")
        error("Missing browser UI: $text")
    }
    private fun tap(text: String) {
        val node = await(text)
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        instrumentation.waitForIdleSync(); SystemClock.sleep(300)
        val bounds = Rect().also(await(text)::getBoundsInScreen)
        val now = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync(); SystemClock.sleep(250)
    }
    private fun assertFits(text: String) {
        val rect = Rect().also(await(text)::getBoundsInScreen)
        val screen = c.appContext.resources.displayMetrics
        assertTrue("$text outside screen: $rect", rect.left >= 0 && rect.right <= screen.widthPixels && rect.top >= 0 && rect.bottom <= screen.heightPixels)
    }
    private fun screenshot(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val prefix = if (dark) "dark" else "light"
        File(c.appContext.cacheDir, "$prefix-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
