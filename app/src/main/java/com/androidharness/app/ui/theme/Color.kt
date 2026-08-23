package com.androidharness.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Google-blue–seeded Material 3 palettes.
 *
 * These are the fallback schemes used when wallpaper dynamic color is unavailable or
 * disabled (pre-Android 12 devices, or dynamicColor off). The seed is the Google Blue
 * (#0B57D0) used across Google's Material 3 apps.
 *
 * The tertiary ramp is deliberately a neutral slate instead of the default purple —
 * the UI does not use tertiary for branding, and a purple accent leaking through
 * chips/badges is exactly what made the old design feel noisy.
 */

internal val LightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4E3FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF555F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E2F9),
    onSecondaryContainer = Color(0xFF121C2B),
    tertiary = Color(0xFF4A5560),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD9DFE6),
    onTertiaryContainer = Color(0xFF141B22),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFAFAFC),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF75777F),
    outlineVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F4FA),
    surfaceContainer = Color(0xFFEDEEF4),
    surfaceContainerHigh = Color(0xFFE7E9EF),
    surfaceContainerHighest = Color(0xFFE2E2E9),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    inversePrimary = Color(0xFFA8C7FA),
    scrim = Color(0xFF000000),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062168),
    primaryContainer = Color(0xFF0B57D0),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBCC7DC),
    onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF3C4758),
    onSecondaryContainer = Color(0xFFD8E3F9),
    tertiary = Color(0xFFB9C2CD),
    onTertiary = Color(0xFF232B33),
    tertiaryContainer = Color(0xFF39424C),
    onTertiaryContainer = Color(0xFFD8DEE6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C21),
    surfaceContainer = Color(0xFF1D2025),
    surfaceContainerHigh = Color(0xFF272A30),
    surfaceContainerHighest = Color(0xFF32353B),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2E3036),
    inversePrimary = Color(0xFF0B57D0),
    scrim = Color(0xFF000000),
)

/**
 * The only hardcoded hues in the app: status colors.
 *
 * M3 has no success/warning roles, and status must read the same regardless of the
 * (dynamic, wallpaper-derived) accent — a green "done" and a red "failed" are
 * universal. Everything else in the UI uses theme roles only.
 */
class StatusColors(val success: Color, val warning: Color)

internal val StatusLight = StatusColors(
    success = Color(0xFF157A3E),
    warning = Color(0xFF9A6A00),
)

internal val StatusDark = StatusColors(
    success = Color(0xFF83D69F),
    warning = Color(0xFFF0C97A),
)

val LocalStatusColors = staticCompositionLocalOf { StatusLight }
