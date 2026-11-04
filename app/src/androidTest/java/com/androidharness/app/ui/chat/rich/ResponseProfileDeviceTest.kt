package com.androidharness.app.ui.chat.rich

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.tools.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.chat.LocalChatImagesEnabled
import com.androidharness.app.ui.chat.components.AssistantText
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class ResponseProfileDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container
    private val image = "https://example.com/profile-fixture-portrait.png"
    private val source = "https://example.com/profile-fixture-biography"
    private val answer get() = """A concise introduction.

```harness-ui
{"type":"profile","name":"Example scientist","subtitle":"Astronomer and science educator","description":"A demonstration of a sourced knowledge card with a portrait and important details together in the conversation.","image":"$image","image_source":"$source","facts":[{"label":"Known for","value":"Making astronomy accessible","source":"$source"},{"label":"Field","value":"Astronomy and public education","source":"$source"}],"sources":[{"title":"Source biography","url":"$source"}]}
```
"""

    @Test fun nativeProfileShowsImageAndFactsOpensSourcesAndRespectsImagesOff() {
        val cache = seedPortrait()
        val dark = mutableStateOf(false)
        val images = mutableStateOf(true)
        var opened: String? = null
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent {
                    HarnessTheme(themeMode = if (dark.value) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                        CompositionLocalProvider(LocalChatImagesEnabled provides images.value) {
                            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                AssistantText(answer, onOpenUrl = { opened = it })
                            }
                        }
                    }
                } }
                await("Example scientist")
                assertNull(find { it.text?.toString() == "Making astronomy accessible" })
                screenshot("profile-photo-top")
                tap("Details"); tap("Sources")
                scrollTo("Source biography"); await("Making astronomy accessible"); await("Source biography")
                assertNull(find { it.text?.toString()?.contains("harness-ui") == true })
                screenshot("profile-light")
                tap("Source biography"); assertEquals(source, opened)
                scrollBackToPortrait()
                tapPortrait()
                await(c.appContext.getString(com.androidharness.app.R.string.chat_image_close)); screenshot("profile-expanded-image")
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                SystemClock.sleep(300)
                scenario.onActivity { dark.value = true; images.value = false }
                instrumentation.waitForIdleSync(); SystemClock.sleep(300)
                await("Example scientist"); await("Making astronomy accessible")
                assertNull(find { it.contentDescription?.toString() == "Example scientist" })
                screenshot("profile-dark-no-image")
            }
        } finally { cache.delete() }
    }

    @Test fun multipleInlinePicturesRenderAroundRichContentAndGalleryCanPassTwelve() {
        val cache = seedPortrait()
        val images = mutableStateOf(true)
        val before = (1..2).joinToString("\n\n") { "![Inline before $it]($image)" }
        val after = (1..2).joinToString("\n\n") { "![Inline after $it]($image)" }
        val gallery = """{"type":"gallery","title":"Picture collection","images":[${(1..13).joinToString(",") { """{"url":"$image","caption":"Collection $it"}""" }}]}"""
        val reply = "$before\n\n$answer\n\n```harness-ui\n$gallery\n```\n\n$after"
        fun reach(label: String) {
            repeat(24) {
                instrumentation.uiAutomation.clearCache()
                if (find { it.text?.toString() == label || it.contentDescription?.toString() == label } != null) {
                    await(label)
                    return
                }
                find { it.isScrollable && it.className?.toString() != "androidx.viewpager.widget.ViewPager" }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(200)
            }
            await(label)
        }
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent {
                    HarnessTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                        CompositionLocalProvider(LocalChatImagesEnabled provides images.value) {
                            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                AssistantText(reply, onOpenUrl = {})
                            }
                        }
                    }
                } }
                await("Inline before 1")
                reach("Inline before 2"); reach("Example scientist")
                reach("1 / 13")
                repeat(12) { page -> tap(c.appContext.getString(com.androidharness.app.R.string.rich_next)); await("${page + 2} / 13") }
                reach("Inline after 1"); reach("Inline after 2")
                assertNull(find { it.text?.toString()?.contains("harness-ui") == true })
                scenario.onActivity { images.value = false }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(300)
                instrumentation.uiAutomation.clearCache()
                assertNull(find { it.contentDescription?.toString()?.startsWith("Inline ") == true })
            }
        } finally { cache.delete() }
    }

    @Test fun largeSystemTextKeepsProfileFactsAndSourcesReadable() {
        val old = android.provider.Settings.System.getFloat(c.appContext.contentResolver, android.provider.Settings.System.FONT_SCALE, 1f)
        val cache = seedPortrait()
        try {
            shell("settings put system font_scale 1.5"); SystemClock.sleep(700)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent { HarnessTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        AssistantText(answer, onOpenUrl = {})
                    }
                } } }
                await("Example scientist"); screenshot("profile-large-text")
                tap("Details"); tap("Sources")
                scrollTo("Source biography"); screenshot("profile-large-text-facts")
            }
        } finally { shell("settings put system font_scale $old"); cache.delete() }
    }

    @Test fun webFetchAutomaticallyRendersAnActualJavascriptPageAndCountsItOnce(): Unit = runBlocking {
        val dir = File(c.appContext.cacheDir, "profile-fallback-${System.nanoTime()}").apply { mkdirs() }
        val html = "<html><title>Rendered fixture</title><main id='text'></main><script>document.getElementById('text').innerText='A useful article rendered by JavaScript, with real readable information from the document.'</script></html>"
        File(dir, "article.html").writeText(html)
        val controller = com.androidharness.app.browser.BrowserController(c.appContext, c.images)
        val client = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body("<html><body>Please enable JavaScript to continue.</body></html>".toResponseBody("text/html".toMediaType())).build() }.build()
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use {
                val budget = SearchSourceBudget(20, 1, 1)
                val tool = WebFetchTool(client, renderedReader = { BrowserReadTool(controller, it) }).forBudget(budget)
                val args = buildJsonObject { put("url", com.androidharness.app.browser.BrowserController.localFileUrl("article.html")) }
                val ctx = ToolContext(FileFs(dir))
                val result = withTimeout(30_000) { tool.execute(args, ctx) }
                assertTrue(result.output, result.ok); assertTrue(result.output.contains("real readable information"))
                assertEquals("browser_read", result.research?.backend); assertEquals(1, budget.pagesRead)
                assertTrue(tool.execute(args, ctx).ok); assertEquals(1, budget.pagesRead)
            }
        } finally { controller.close(); dir.deleteRecursively() }
    }

    private fun seedPortrait(): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(image.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(c.appContext.cacheDir, "remote-images/$hash.img").apply { parentFile?.mkdirs() }
        val bitmap = Bitmap.createBitmap(200, 248, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(65, 88, 130))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(218, 228, 244) }
        canvas.drawCircle(100f, 82f, 41f, paint); canvas.drawOval(34f, 133f, 166f, 285f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        return file
    }
    private fun find(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun await(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.clearCache()
            find { it.text?.toString() == text || it.contentDescription?.toString() == text }?.let { return it }
            SystemClock.sleep(100)
        }
        screenshot("missing-${text.take(20)}"); error("Missing profile text: $text")
    }
    private fun scrollBackToPortrait() {
        repeat(16) {
            instrumentation.uiAutomation.clearCache()
            if (find { it.contentDescription?.toString() == "Example scientist" } != null) return
            find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(250)
        }
    }
    private fun tapPortrait() {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.clearCache()
            val image = find { it.contentDescription?.toString() == "Example scientist" }
            if (image != null) {
                var node: AccessibilityNodeInfo = image
                while (!node.isClickable && node.parent != null) node = node.parent
                val rect = Rect().also(node::getBoundsInScreen)
                if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) shell("input tap ${rect.centerX()} ${rect.centerY()}")
                return
            }
            SystemClock.sleep(100)
        }
        error("Portrait did not load")
    }
    private fun tap(text: String) {
        var node = await(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        val rect = Rect().also(node::getBoundsInScreen)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) shell("input tap ${rect.centerX()} ${rect.centerY()}")
        SystemClock.sleep(300)
    }
    private fun scrollTo(text: String) {
        repeat(16) {
            if (find { it.text?.toString() == text } != null) return
            find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(250)
        }
        await(text)
    }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(400)
        val dir = File(c.appContext.getExternalFilesDir(null), "profile-card-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
