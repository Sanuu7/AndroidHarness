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
import androidx.compose.ui.platform.LocalContext
import com.androidharness.app.data.ThemeMode

/**
 * App theme: Material You dynamic color on Android 12+, with a Google-blue–seeded
 * palette as the fallback and Google Sans Flex typography.
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
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(
        LocalStatusColors provides (if (dark) StatusDark else StatusLight),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = HarnessTypography,
            shapes = HarnessShapes,
            motionScheme = MotionScheme.standard(),
            content = content,
        )
    }
}
