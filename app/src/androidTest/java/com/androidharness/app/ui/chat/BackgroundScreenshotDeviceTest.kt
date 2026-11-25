package com.androidharness.app.ui.chat

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.tools.BrowserScreenshotTool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * browser_screenshot captures the page without the preview: with the chat on
 * screen and with the app in the background. The preview stays closed.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundScreenshotDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun screenshotNeedsNoPreviewOrForegroundApp(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "shot-preview-${System.nanoTime()}").apply { mkdirs() }
        File(root, "page.html").writeText(
            "<html><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<body style='background:#c00'><h1>Screenshot fixture</h1></body></html>",
        )
        val fs = FileFs(root)
        val html = "<html><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<body style='background:#c00'><h1>Screenshot fixture</h1></body></html>"
        // Serve the page like `python3 -m http.server` does in a real chat.
        val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val body = html.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            }
        }
        val project = c.workspace.addShellProject(root.path, activate = false)
        val sid = c.sessions.createProjectSession(project.id)
        var vm: ChatViewModel? = null
        val tool = BrowserScreenshotTool(c.browser)
        suspend fun shoot(label: String) {
            val result = tool.execute(JsonObject(emptyMap()), ToolContext(fs))
            val seen = nodes().mapNotNull { (it.className?.toString()?.substringAfterLast('.') ?: "") + ":" + (it.text ?: it.contentDescription ?: "") }.take(60)
            assertTrue("$label: ${result.output}\nOn screen: $seen", result.ok)
        }
        try {
            instrumentation.runOnMainSync { vm = ChatViewModel(c, sid) }
            withTimeout(10000) { vm!!.state.first { !it.isLoadingMessages } }
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                    ChatScreen(vm!!, {}, { _, _ -> }, {}, {}, onOpenSubagent = {})
                } } }
                await("Open GitHub")
                c.browser.navigate("http://localhost:$port/", fs)

                // Chat on screen, preview closed: captured offscreen, and the sheet stays closed.
                shoot("sheet closed")
                assertTrue(find("Web & HTML Preview") == null && nodes().none { it.className?.toString()?.contains("WebView") == true })

                // The app in the background.
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                shoot("app in background")
            }
        } finally {
            runCatching { server.close() }
            instrumentation.runOnMainSync { vm?.viewModelScope?.cancel() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project); root.deleteRecursively()
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun find(text: String) = nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun await(text: String): AccessibilityNodeInfo {
        eventually { find(text) != null }
        return requireNotNull(find(text))
    }
    private fun tap(text: String) {
        val bounds = Rect().also(await(text)::getBoundsInScreen)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }
    private fun eventually(check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < end) { if (check()) return; SystemClock.sleep(60) }
        assertTrue("Timed out. On screen: " + nodes().mapNotNull { (it.className?.toString()?.substringAfterLast('.') ?: "") + ":" + (it.text ?: it.contentDescription ?: "") }.take(80), check())
    }
}
