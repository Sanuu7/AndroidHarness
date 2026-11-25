package com.androidharness.app.browser

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Gives the background browser a real window that nobody sees: a Presentation
 * on a private virtual display owned by the app. A WebView with no window
 * draws nothing, so a screenshot of it comes out blank. In this window it
 * renders, runs animations and reports itself visible like the preview does,
 * whether or not Harness is on screen.
 *
 * Main thread only.
 */
internal class OffscreenBrowserHost(private val appContext: Context) {
    private var frames: HandlerThread? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var container: FrameLayout? = null

    /** Puts [view] in the offscreen window. False when the window could not be made. */
    fun host(view: WebView): Boolean {
        val root = ensureWindow() ?: return false
        if (view.parent === root) return true
        (view.parent as? ViewGroup)?.removeView(view)
        root.removeAllViews()
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return true
    }

    /** Takes [view] out of the offscreen window, for example before the preview sheet shows it. */
    fun release(view: WebView) {
        if (view.parent === container) container?.removeView(view)
    }

    fun close() {
        runCatching { container?.removeAllViews() }
        runCatching { presentation?.dismiss() }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { frames?.quitSafely() }
        presentation = null; display = null; reader = null; container = null; frames = null
    }

    private fun ensureWindow(): FrameLayout? {
        container?.let { if (presentation?.isShowing == true) return it }
        close()
        return runCatching {
            val metrics = appContext.resources.displayMetrics
            val width = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
            val height = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
            val thread = HandlerThread("harness-offscreen-browser").apply { start() }
            frames = thread
            // Frames are only needed so the window keeps drawing; drop each one.
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, Handler(thread.looper))
            }
            reader = imageReader
            val manager = appContext.getSystemService(DisplayManager::class.java)
            val virtual = manager.createVirtualDisplay(
                "harness-browser", width, height, metrics.densityDpi, imageReader.surface, 0,
            ) ?: error("No virtual display")
            display = virtual
            val root = FrameLayout(appContext)
            val window = Presentation(appContext, virtual.display).apply {
                setContentView(root)
                setCancelable(false)
                show()
            }
            presentation = window
            container = root
            root
        }.onFailure { close() }.getOrNull()
    }
}
