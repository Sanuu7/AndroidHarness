package com.androidharness.app.browser

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Debug-only host window for the instrumentation tests: gives an attached,
 * hardware-accelerated WebView (the preview sheet's rendering path) a real
 * window. Compiled into the debug variant only, so it never ships.
 */
class TestHostActivity : Activity() {
    lateinit var container: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)
    }

    /** Adds a sheet-like WebView (attached, hardware accelerated). */
    fun addSheetLikeWebView(): WebView {
        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            @Suppress("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
        container.addView(wv)
        return wv
    }
}
