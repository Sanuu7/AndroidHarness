package com.androidharness.app.ui.common

import android.content.Context
import android.os.Build
import android.view.Display
import android.view.WindowManager

/** Display modes at the current resolution, so switching rate never changes the screen size. */
object RefreshRate {
    private fun display(context: Context): Display? {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 30) context.display
        else (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }

    private fun modes(context: Context): List<Display.Mode> {
        val display = display(context) ?: return emptyList()
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
    }

    /** The fastest refresh rate the screen offers, in Hz, or 60 when it offers nothing faster. */
    fun maxHz(context: Context): Int =
        modes(context).maxOfOrNull { Math.round(it.refreshRate) }?.coerceAtLeast(60) ?: 60

    /** Rates worth offering: 60, 90 once the screen reaches 120, 120 above that, and the maximum. */
    fun options(context: Context): List<Int> {
        val max = maxHz(context)
        if (max <= 60) return emptyList()
        return listOfNotNull(60, 90.takeIf { max >= 120 }, 120.takeIf { max > 120 }, max).distinct()
    }

    /**
     * The display mode id and frame rate hint to put on the window for [hz]
     * (0 = fastest). A rate the panel has no exact mode for is passed as a hint
     * and the system picks the nearest mode.
     */
    fun request(context: Context, hz: Int): Pair<Int, Float> {
        val modes = modes(context)
        val target = if (hz <= 0) maxHz(context) else hz
        val exact = modes.filter { Math.round(it.refreshRate) == target }.maxByOrNull { it.refreshRate }
        return if (exact != null) exact.modeId to 0f else 0 to target.toFloat()
    }
}
