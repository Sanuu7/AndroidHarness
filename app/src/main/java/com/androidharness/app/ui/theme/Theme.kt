package com.androidharness.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.data.ProgressBarStyle
import com.androidharness.app.data.ThemePalette

val LocalProgressBarStyle = staticCompositionLocalOf { ProgressBarStyle.FLAT }

/**
 * App theme: Material You dynamic color on Android 12+, with a Google-blue–seeded
 * palette as the fallback, selectable tonal palettes and Google Sans Flex typography.
 *
 * Motion uses the standard (non-expressive) scheme: the app ships its own subtle
 * spring/tween specs via [defaultSpatialSpec] & friends, and the standard scheme
 * keeps M3 components' internal transitions (button shape morphs, indicator
 * behavior) calm instead of playful.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HarnessTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    progressBarStyle: ProgressBarStyle = ProgressBarStyle.FLAT,
    colorPalette: ThemePalette = ThemePalette.BLUE,
    customPalette: com.androidharness.app.data.CustomThemePalette? = null,
    canvasSeed: Long? = null,
    glassEnabled: Boolean = false,
    liquidGlass: Boolean = false,
    glassTransparency: Float = .5f,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val context = LocalContext.current
    val personal = remember(customPalette, canvasSeed, dark) {
        when {
            canvasSeed != null -> canvasColorScheme(canvasSeed, dark)
            customPalette != null -> customPalette.colorScheme(dark)
            else -> null
        }
    }
    val base = when {
        personal != null -> personal
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> colorPalette.colorScheme(dark)
    }
    // AMOLED blacks out the surface roles but keeps the accent roles, so a
    // wallpaper-derived primary survives the switch to true black.
    val colorScheme = if (themeMode == ThemeMode.AMOLED) base.amoledSurfaces() else base
    val glass = HarnessGlassStyle(HarnessGlassMode.resolve(
        glassEnabled && com.androidharness.app.data.FeatureAvailability.HARNESS_GLASS,
        liquidGlass, Build.VERSION.SDK_INT), colorScheme.surface, glassTransparency)

    CompositionLocalProvider(
        LocalStatusColors provides (if (dark) StatusDark else StatusLight),
        LocalProgressBarStyle provides progressBarStyle,
    ) {
        MaterialTheme(
            colorScheme = if (glass.mode == HarnessGlassMode.OFF) colorScheme else colorScheme.copy(
                background = androidx.compose.ui.graphics.Color.Transparent,
                surface = androidx.compose.ui.graphics.Color.Transparent,
            ),
            typography = HarnessTypography,
            shapes = HarnessShapes,
            motionScheme = MotionScheme.standard(),
            content = { HarnessGlassRoot(glass, content) },
        )
    }
}
