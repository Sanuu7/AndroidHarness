package com.androidharness.app.ui.chat

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.CheckStatus
import com.androidharness.app.agent.VerificationCheck
import com.androidharness.app.ui.chat.components.VerificationCard
import com.androidharness.app.ui.chat.components.WebPreviewSheet
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MobileWorkflowDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as HarnessApp

    @Test fun previewElementSelectionAndVerificationRender() {
        val root = File(app.cacheDir, "workflow-${System.nanoTime()}").apply { mkdirs() }
        File(root, "index.html").writeText("""<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font-family:sans-serif;padding:24px}button{padding:24px;background:#3355bb;color:white;border:0;border-radius:12px}</style><h1>Workflow preview</h1><button id="cta" onclick="window.clicked=true">Select this button</button>""")
        val fs = FileFs(root)
        app.container.browser.prepareWorkspacePreview("index.html", fs)
        app.container.browser.clearTrack()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            lateinit var host: ChatListTestActivity
            scenario.onActivity { activity ->
                host = activity
                activity.setContent { HarnessTheme {
                    Column(Modifier.padding(16.dp)) {
                        VerificationCard(listOf(VerificationCheck("npm run build", CheckStatus.PASSED), VerificationCheck("npm test", CheckStatus.STALE)))
                    }
                    WebPreviewSheet(initialTarget = "index.html", workspace = fs, browserController = app.container.browser, onDismiss = {})
                } }
            }
            await("preview load") { windowWebView()?.progress == 100 }
            val view = findWebViewOnMain(host)
            val raw = AtomicReference<String?>()
            await("DOM ready") {
                view.evaluateJavascript("JSON.stringify({url:location.href,ready:document.readyState,button:!!document.querySelector('#cta'),body:document.body?.innerText})") { raw.set(it) }
                raw.get()?.contains("true") == true
            }
            screenshot("workflow-preview.png")
            tapNode("More browser actions")
            tapNode("Select element to fix")
            await("selection banner") { hasNode("Tap the element you want to change") }
            val point = AtomicReference<String?>()
            instrumentation.runOnMainSync { view.evaluateJavascript("(function(){const r=document.querySelector('#cta').getBoundingClientRect();return JSON.stringify({x:r.x+r.width/2,y:r.y+r.height/2});})()") { point.set(it) } }
            await("button bounds") { point.get() != null }
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(com.androidharness.app.browser.BrowserController.decodeJsJson(point.get()!!)!!).jsonObject
            val position = IntArray(2)
            var scale = 1f
            instrumentation.runOnMainSync { view.getLocationOnScreen(position); @Suppress("DEPRECATION") run { scale = view.scale } }
            tap(position[0] + obj.getValue("x").jsonPrimitive.double.toFloat() * scale,
                position[1] + obj.getValue("y").jsonPrimitive.double.toFloat() * scale)
            await("selection dialog") { hasNode("Fix selected element") }
            assertTrue(hasNode("#cta"))
            await("screenshot captured") { hasNode("Includes element details, styles, and available page context.") }
            assertTrue(File(root, ".harness/screenshots").listFiles()?.any { it.extension == "jpg" } == true)
            screenshot("workflow-selection.png")
            tapNode("Cancel")
            val clicked = AtomicReference<String?>()
            instrumentation.runOnMainSync { view.evaluateJavascript("String(window.clicked === true)") { clicked.set(it) } }
            await("click suppression") { clicked.get() != null }
            assertEquals("\"false\"", clicked.get())
            scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                Column(Modifier.padding(16.dp)) {
                    VerificationCard(listOf(VerificationCheck("npm run build", CheckStatus.PASSED), VerificationCheck("npm test", CheckStatus.STALE)))
                    VerificationCard(emptyList())
                }
            } } }
            await("verification card") { hasNode("Recheck needed") && hasNode("Verification") }
            screenshot("workflow-verification.png")
        }
        root.deleteRecursively()
    }

    @Test fun staticProjectLaunchAndDiffQuestions() = runBlocking {
        val c = app.container
        val previous = c.workspace.currentProjectOnce()
        val root = File(app.cacheDir, "project-workflow-${System.nanoTime()}").apply { mkdirs() }
        File(root, "index.html").writeText("<html><meta name='viewport' content='width=device-width,initial-scale=1'><h1>Static project ready</h1></html>")
        File(root, "sample.txt").writeText("after\n")
        val project = c.workspace.addShellProject(root.absolutePath)
        val sid = c.sessions.createSession("Workflow fixture", project.id)
        c.sessions.recordFileChange(sid, "sample.txt", 1, 1, true, true, "before\n")
        val question = AtomicReference<String?>()
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                    com.androidharness.app.ui.buildtest.BuildTestScreen(c, {}, { _, _ -> }, {}, {})
                } } }
                await("project launch card") { hasNode("Run & preview") }
                screenshot("workflow-launch.png")
                tapNode("Open preview")
                await("static preview") { hasNode("Static project ready") }
                screenshot("workflow-static.png")
                tapNode("More browser actions")
                tapNode("Close")
                await("preview dismissed") { hasNode("Run & preview") }
            }
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                    com.androidharness.app.ui.files.ChangesScreen(c, sid, {}, onAskAgent = { question.set(it) })
                } } }
                instrumentation.waitForIdleSync()
                tapNode("sample.txt")
                tapNode("Ask about changes")
                await("question dialog") { hasNode("Ask about this file") }
                screenshot("workflow-diff-question.png")
                tapNode("Cancel")
                tapNode("Review 1 sections")
                tapNode("Ask about section 1")
                await("section dialog") { hasNode("Ask about this section") }
                screenshot("workflow-section-question.png")
                fun editable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    root ?: return null
                    if (root.isEditable) return root
                    for (i in 0 until root.childCount) editable(root.getChild(i))?.let { return it }
                    return null
                }
                val input = requireNotNull(editable(instrumentation.uiAutomation.rootInActiveWindow))
                input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Why was this changed?")
                })
                tapNode("Ask agent")
                await("question callback") { question.get() != null }
                assertTrue(question.get()!!.contains("before"))
                assertTrue(question.get()!!.contains("after"))
                assertTrue(question.get()!!.contains("Do not edit"))
            }
        } finally {
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project)
            c.workspace.setActiveProject(previous.id)
            root.deleteRecursively()
            c.terminal.stopTerminal()
        }
    }

    @Test fun npmRunOpensReportedPreview() = runBlocking {
        val c = app.container
        val previous = c.workspace.currentProjectOnce()
        val root = File(app.cacheDir, "npm-workflow-${System.nanoTime()}").apply { mkdirs() }
        File(root, "package.json").writeText("""{"scripts":{"dev":"node server.js","test":"node -e \\\"process.exit(0)\\\""}}""")
        File(root, "server.js").writeText("""const http=require('http');http.createServer((q,r)=>r.end('<html><h1>Npm preview ready</h1></html>')).listen(18765,'127.0.0.1',()=>console.log('http://localhost:18765/'));""")
        val project = c.workspace.addShellProject(root.absolutePath)
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                    com.androidharness.app.ui.buildtest.BuildTestScreen(c, {}, { _, _ -> }, {}, {})
                } } }
                await("detected npm script") { hasNode("Script: dev") }
                screenshot("workflow-npm-launch.png")
                tapNode("Run project")
                await("npm automatic preview") { hasNode("Npm preview ready") }
                screenshot("workflow-npm-preview.png")
                tapNode("More browser actions")
                tapNode("Close")
                tapNode("Stop")
                await("server stopped") { !c.terminal.state.value.busy }
                val stoppedAt = SystemClock.uptimeMillis() + 5000
                var listening = true
                while (listening && SystemClock.uptimeMillis() < stoppedAt) {
                    listening = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", 18765), 100) }; true }.getOrDefault(false)
                    SystemClock.sleep(100)
                }
                assertFalse("Stop left the project server running", listening)
            }
        } finally {
            c.terminal.stopTerminal()
            c.workspace.deleteProject(project)
            c.workspace.setActiveProject(previous.id)
            root.deleteRecursively()
        }
    }

    private fun screenshot(name: String) {
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "workflow-qa").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun node(text: String, root: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        root ?: return null
        if (root.text?.toString() == text || root.contentDescription?.toString() == text) return root
        for (i in 0 until root.childCount) node(text, root.getChild(i))?.let { return it }
        return null
    }
    private fun hasNode(text: String) = node(text) != null
    private fun tapNode(text: String) {
        val deadline = SystemClock.uptimeMillis() + 15000
        var found = node(text)
        while (found == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(150)
            found = node(text)
        }
        var target = requireNotNull(found) { "Missing UI element: $text" }
        while (!target.isClickable && target.parent != null) target = target.parent
        if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val bounds = android.graphics.Rect()
            target.getBoundsInScreen(bounds)
            tap(bounds.exactCenterX(), bounds.exactCenterY())
        }
        SystemClock.sleep(350)
    }
    private fun tap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            SystemClock.sleep(80)
        }
    }
    @Suppress("DEPRECATION")
    private fun windowWebView(): WebView? {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val roots = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<*>
        return roots.filterIsInstance<View>().firstNotNullOfOrNull { findWebView(it) }
    }
    private fun findWebViewOnMain(host: ChatListTestActivity): WebView {
        var view: WebView? = null
        instrumentation.runOnMainSync { view = windowWebView() ?: findWebView(host.window.decorView) }
        return requireNotNull(view)
    }
    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(100)
        }
        screenshot("workflow-failure.png")
        fun describe(root: AccessibilityNodeInfo?): String {
            root ?: return ""
            return "${root.text} ${root.contentDescription}\n" + (0 until root.childCount).joinToString("") { describe(root.getChild(it)) }
        }
        error("Timed out: $message\n${describe(instrumentation.uiAutomation.rootInActiveWindow)}")
    }
}
