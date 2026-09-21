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
    fun screenshotAfterScrollShowsVisibleContent() = runBlocking {
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

        val shot = controller.screenshot(fs)
        assertTrue("screenshot returned null", shot != null)
        val bitmap = BitmapFactory.decodeFile(shot!!.cachedFile.absolutePath)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val blue = pixels.count { android.graphics.Color.blue(it) > 200 && android.graphics.Color.red(it) < 60 }
        val white = pixels.count { it == android.graphics.Color.WHITE }
        val fraction = blue.toDouble() / pixels.size
        assertTrue(
            "scrolled capture is blank (white=${"%.1f".format(white * 100.0 / pixels.size)}%, " +
                "blue=${"%.1f".format(fraction * 100)}%, ${shot.sizeBytes} bytes)",
            fraction > 0.33,
        )
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
    fun screenshotCapturesRenderedPage() = runBlocking {
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

        val shot = controller.screenshot(fs)
        assertTrue("screenshot returned null", shot != null)
        val bitmap = BitmapFactory.decodeFile(shot!!.cachedFile.absolutePath)
        assertTrue("captured JPEG did not decode", bitmap != null)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val red = pixels.count { android.graphics.Color.red(it) > 200 && android.graphics.Color.green(it) < 60 }
        val fraction = red.toDouble() / pixels.size

        // Loose on purpose: the point is "content was rasterized at all", so a
        // page that is at least a third red proves the draw produced the DOM.
        assertTrue(
            "captured image is blank/blank-ish: red=${"%.1f".format(fraction * 100)}% " +
                "(${shot.sizeBytes} bytes, ${bitmap.width}x${bitmap.height})",
            fraction > 0.33,
        )
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
