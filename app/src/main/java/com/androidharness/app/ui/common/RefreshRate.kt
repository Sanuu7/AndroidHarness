package com.androidharness.app.ui.common

import android.content.Context
import android.os.Build
import android.view.WindowManager

object RefreshRate {
    /** Id of the fastest display mode at the current resolution, or 0 (no preference) if unknown. */
    fun fastestModeId(context: Context): Int {
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= 30) context.display
        else (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        display ?: return 0
        return fastestModeId(display)
    }

    /** The same, for a window whose context has no display of its own, such as a service's. */
    fun fastestModeId(display: android.view.Display): Int {
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }?.modeId ?: 0
    }
}
