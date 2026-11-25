package com.androidharness.app.browser

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.data.ImageStore
import com.androidharness.app.workspace.FileFs
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only behaviour of [BrowserController]: the pieces a JVM test cannot
 * reach because they are the real WebView's own rendering, history and
 * promise staging. Each case here was a QA report first.
 *
 * Runs with `./gradlew :app:connectedDebugAndroidTest` against a connected
 * phone; there is no emulator available in this environment.
 */
@RunWith(AndroidJUnit4::class)
class BrowserControllerDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun workspace(): Pair<File, FileFs> {
        val dir = File(context.cacheDir, "browser-device-test").apply {
            deleteRecursively()
            mkdirs()
        }
        return dir to FileFs(dir)
    }

    private fun controller(fs: FileFs): BrowserController =
        BrowserController(context, ImageStore(context)).apply { currentWorkspace = fs }

    /**
     * The QA repro said captures were blank "regardless of DOM state". A page
     * that has been scrolled is the state the earlier fixes never covered: the
     * capture translated the canvas by the DOM scroll offset, and the software
     * draw of the viewport then had nothing left on screen.
     */
    @Test
    fun backgroundScreenshotAfterScrollCapturesTheScrolledSection() = runBlocking {
        val (_, fs) = workspace()
        fs.resolve("tall.html").writeText(
            """
            <html><head><style>html,body{margin:0;padding:0}</style></head><body>
            <div style="height:900px;background:#ff0000"></div>
            <div style="height:900px;background:#0000ff"></div>
            </body></html>
            """.trimIndent(),
        )
        val controller = controller(fs)
        controller.navigate("tall.html", fs)
        controller.scroll("down", 1_400)

        // No activity and no preview: the browser renders in its offscreen window.
        val result = com.androidharness.app.tools.BrowserScreenshotTool(controller).execute(
            kotlinx.serialization.json.JsonObject(emptyMap()), com.androidharness.app.tools.ToolContext(fs))
        assertTrue(result.output, result.ok)
        val bytes = requireNotNull(fs.resolve(".harness/screenshots").list().firstOrNull()?.openInputStream()).use { it.readBytes() }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val blue = pixels.count { android.graphics.Color.blue(it) > 200 && android.graphics.Color.red(it) < 60 }
        bitmap.recycle()
        assertTrue("Background capture was blank or the wrong section", blue.toDouble() / pixels.size > 0.3)
        controller.close()
    }

    @Test fun backgroundSmoothScrollingAndAnchorsMove() = runBlocking {
        val (_, fs) = workspace()
        fs.resolve("smooth.html").writeText("""
            <html><head><style>html{scroll-behavior:smooth}body{margin:0}</style></head><body>
            <a id="go" href="#target">Jump</a><div style="height:3000px"></div>
            <h2 id="target">Target</h2><div style="height:3000px"></div></body></html>
        """.trimIndent())
        val controller = controller(fs)
        try {
            controller.navigate("smooth.html", fs)
            controller.evalUser("window.scrollTo({top:1400,behavior:'smooth'}); window.scrollY")
            // The offscreen page is visible, so the smooth scroll animates like it does on screen.
            val settled = kotlinx.coroutines.withTimeoutOrNull(3_000) {
                while (controller.extractState().scrollY <= 500) kotlinx.coroutines.delay(100)
                true
            }
            assertTrue("Smooth scroll never arrived", settled == true)
            controller.evalUser("window.scrollTo(0,0)")
            val jumped = controller.click(selector = "#go")
            assertTrue("Anchor did not move: $jumped", jumped.scrollY > 1000)
        } finally { controller.close() }
    }

    /**
     * The QA repro stepped back after a link click, which is the path the
     * 2026-09-17 history work was written for; a plain two-navigate sequence
     * passes, so the click case needs its own coverage.
     */
    @Test
    fun backAfterClickDrivenNavigationReturnsToTheLinkingPage() = runBlocking {
        val (_, fs) = workspace()
        fs.resolve("home.html").writeText(
            "<html><body><h1>HOME</h1><a id='go' href='detail.html'>open detail</a></body></html>",
        )
        fs.resolve("detail.html").writeText("<html><body><h1>DETAIL</h1></body></html>")
        val controller = controller(fs)

        controller.navigate("home.html", fs)
        val clicked = controller.click(selector = "#go")
        assertTrue("click did not navigate: ${clicked.url}", clicked.url.endsWith("detail.html"))
        val historyBefore = controller.debugHistoryDump()

        val state = controller.back()
        assertTrue(
            "back() did not reach home: url=${state.url} error=${state.error} " +
                "history-before=[$historyBefore] history-after=[${controller.debugHistoryDump()}]",
            state.url.endsWith("home.html"),
        )
        assertTrue("back() stayed on detail: ${state.textSummary.take(60)}", state.textSummary.contains("HOME"))
    }

    /**
     * The QA repro: a fully rendered page still captured as a blank image, so
     * the agent "sees" an empty screen for every screenshot.
     */
    @Test
    fun backgroundScreenshotIsNotBlank() = runBlocking {
        val (_, fs) = workspace()
        fs.resolve("red.html").writeText(
            """
            <html><head><style>
            html,body{margin:0;padding:0;background:#ff0000;height:100%;}
            </style></head><body><div style="background:#ff0000;width:100%;height:100%"></div></body></html>
            """.trimIndent(),
        )
        val controller = controller(fs)
        controller.navigate("red.html", fs)

        val shot = requireNotNull(controller.screenshot(fs))
        val bitmap = requireNotNull(BitmapFactory.decodeFile(shot.cachedFile.path))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val red = pixels.count { android.graphics.Color.red(it) > 200 && android.graphics.Color.green(it) < 60 }
        bitmap.recycle()
        assertTrue("Background capture is blank", red.toDouble() / pixels.size > 0.8)
        controller.close()
    }

    /**
     * The QA repro: back() reported the page it started on and never moved.
     * history.back() from JS worked, so the entries exist; the step itself is
     * what fails.
     */
    @Test
    fun backReturnsToPreviousWorkspacePage() = runBlocking {
        val (_, fs) = workspace()
        fs.resolve("a.html").writeText("<html><body><h1>PAGE A</h1></body></html>")
        fs.resolve("b.html").writeText("<html><body><h1>PAGE B</h1></body></html>")
        val controller = controller(fs)

        controller.navigate("a.html", fs)
        controller.navigate("b.html", fs)
        val state = controller.back()

        assertTrue("back() did not reach A: ${state.url}", state.url.endsWith("a.html"))
        assertTrue("back() stayed on B: ${state.title} / ${state.textSummary.take(60)}", state.textSummary.contains("PAGE A"))
    }

    /**
     * The QA repro: a promise that rejects (or cannot be serialized) was
     * reported as "the page navigated away before the promise settled".
     */
    @Test
    fun rejectedPromiseReportsTheRealError() = runBlocking {
        val (_, fs) = workspace()
        fs.resolve("p.html").writeText("<html><body>promise</body></html>")
        val controller = controller(fs)
        controller.navigate("p.html", fs)

        val outcome = controller.evalUser("return await Promise.reject(new TypeError('boom'))")
        assertFalse("rejected promise reported as success", outcome.ok)
        assertTrue(
            "wrong error surfaced: ${outcome.error}",
            outcome.error?.contains("boom") == true,
        )
    }

    /**
     * BigInt cannot go through JSON.stringify, which is how the value crosses
     * the evaluateJavascript bridge. The failure must name that, not blame a
     * navigation that never happened.
     */
    @Test
    fun unserializableValueReportsSerializationNotNavigation() = runBlocking {
        val (_, fs) = workspace()
        fs.resolve("p.html").writeText("<html><body>promise</body></html>")
        val controller = controller(fs)
        controller.navigate("p.html", fs)

        val outcome = controller.evalUser("return await Promise.resolve(10n ** 40n)")
        if (!outcome.ok) {
            val error = outcome.error.orEmpty()
            assertFalse(
                "serialization failure was blamed on a navigation: $error",
                error.contains("navigated away"),
            )
            assertTrue("unhelpful error: $error", error.contains("BigInt", ignoreCase = true))
        }
    }

    /**
     * A page that really does navigate while a promise is pending still has to
     * report the navigation, so the fix for the case above must not swallow it.
     */
    @Test
    fun genuineNavigationDuringPromiseStillReportsNavigation() = runBlocking {
        val (_, fs) = workspace()
        fs.resolve("p.html").writeText("<html><body>promise</body></html>")
        fs.resolve("next.html").writeText("<html><body>next</body></html>")
        val controller = controller(fs)
        controller.navigate("p.html", fs)

        val outcome = controller.evalUser(
            "setTimeout(() => { location.href = '/ws/next.html'; }, 50); " +
                "return await new Promise(() => {});",
        )
        assertFalse("expected failure", outcome.ok)
        assertEquals(
            "the page navigated away before the promise settled",
            outcome.error,
        )
    }
}
