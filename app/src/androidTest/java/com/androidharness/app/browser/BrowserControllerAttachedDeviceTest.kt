package com.androidharness.app.browser

import android.graphics.BitmapFactory
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.data.ImageStore
import com.androidharness.app.workspace.FileFs
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same browser actions against a WebView that lives in a real window, the
 * way the preview sheet's WebView does. Attached hardware views take a
 * different drawing path from the headless one, and the QA round that reported
 * blank screenshots and a stuck browser_back was watching the agent work, so
 * these are the conditions to cover.
 */
@RunWith(AndroidJUnit4::class)
class BrowserControllerAttachedDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun workspace(): FileFs {
        val dir = File(context.cacheDir, "browser-attached-test").apply {
            deleteRecursively()
            mkdirs()
        }
        return FileFs(dir)
    }

    private fun controller(fs: FileFs): BrowserController =
        BrowserController(context, ImageStore(context)).apply { currentWorkspace = fs }

    @Test
    fun screenshotOfBoundSheetWebViewIsNotBlank() = runBlocking {
        val fs = workspace()
        fs.resolve("red.html").writeText(
            """
            <html><head><style>html,body{margin:0;padding:0;background:#ff0000;height:100%}</style></head>
            <body><div style="width:100%;height:100%;background:#ff0000"></div></body></html>
            """.trimIndent(),
        )
        val controller = controller(fs)

        ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val wv = activity.addSheetLikeWebView()
                // Same wiring the preview sheet uses: intercept the workspace
                // origin, then hand the view to the controller.
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? =
                        controller.interceptWorkspaceRequest(request?.url)

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        controller.notePageStarted()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        controller.notePageFinished(url)
                    }
                }
                controller.bindActiveWebView(wv)
            }

            val state = controller.navigate("red.html", fs)
            assertTrue("navigate did not load the page: ${state.url}", state.url.contains("red.html"))

            val shot = controller.screenshot(fs)
            assertTrue("screenshot returned null", shot != null)
            val bitmap = BitmapFactory.decodeFile(shot!!.cachedFile.absolutePath)
            assertTrue("captured JPEG did not decode", bitmap != null)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val red = pixels.count { Color.red(it) > 200 && Color.green(it) < 60 }
            val white = pixels.count { it == Color.WHITE }
            assertTrue(
                "attached capture is blank: red=${"%.1f".format(red * 100.0 / pixels.size)}%, " +
                    "white=${"%.1f".format(white * 100.0 / pixels.size)}%, ${shot.sizeBytes} bytes, " +
                    "${bitmap.width}x${bitmap.height}",
                red.toDouble() / pixels.size > 0.33,
            )
        }
    }

    @Test
    fun backWorksWithTheSheetWebViewBound() = runBlocking {
        val fs = workspace()
        fs.resolve("a.html").writeText("<html><body><h1>PAGE A</h1></body></html>")
        fs.resolve("b.html").writeText("<html><body><h1>PAGE B</h1></body></html>")
        val controller = controller(fs)

        ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val wv = activity.addSheetLikeWebView()
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? =
                        controller.interceptWorkspaceRequest(request?.url)

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        controller.notePageStarted()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        controller.notePageFinished(url)
                    }
                }
                controller.bindActiveWebView(wv)
            }

            controller.navigate("a.html", fs)
            controller.navigate("b.html", fs)
            val state = controller.back()
            assertTrue("back() did not reach A: ${state.url}", state.url.endsWith("a.html"))
            assertTrue(
                "back() stayed on B: ${state.title} / ${state.textSummary.take(60)}",
                state.textSummary.contains("PAGE A"),
            )
        }
    }
}
