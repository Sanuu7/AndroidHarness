package com.androidharness.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.androidharness.app.R

/**
 * Google Sans Flex, bundled locally so the typeface renders identically on every
 * device (no Play Services / network dependency). It is a variable font, so asking
 * for Medium/Bold/SemiBold renders from its weight axis rather than fake-bold.
 */
val GoogleSans = FontFamily(Font(R.font.google_sans_flex_variable))

/**
 * Tuned M3 Expressive type scale on Google Sans Flex.
 *
 * Skipped letter-spacing on large display/headline roles reads more like Google's
 * app typography; body/label roles keep small positive tracking for readability.
 * The *Emphasized roles (SemiBold) are what gives headers and greetings their
 * confident Google weight.
 */
internal val HarnessTypography: Typography = run {
    val base = Typography()
    fun t(
        b: TextStyle,
        weight: FontWeight? = null,
        size: androidx.compose.ui.unit.TextUnit? = null,
        lineHeight: androidx.compose.ui.unit.TextUnit? = null,
        letterSpacing: androidx.compose.ui.unit.TextUnit? = null,
    ) = TextStyle(
        fontFamily = GoogleSans,
        fontWeight = weight ?: b.fontWeight,
        fontSize = size ?: b.fontSize,
        lineHeight = lineHeight ?: b.lineHeight,
        letterSpacing = letterSpacing ?: b.letterSpacing,
    )

    Typography(
        displayLarge = t(base.displayLarge, size = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
        displayMedium = t(base.displayMedium, size = 45.sp, lineHeight = 52.sp, letterSpacing = (-0.25).sp),
        displaySmall = t(base.displaySmall, size = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.25).sp),
        headlineLarge = t(base.headlineLarge, size = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.25).sp),
        headlineMedium = t(base.headlineMedium, size = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.25).sp),
        headlineSmall = t(base.headlineSmall, size = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.25).sp),
        titleLarge = t(base.titleLarge, size = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.25).sp),
        titleMedium = t(base.titleMedium, weight = FontWeight.Medium, size = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
        titleSmall = t(base.titleSmall, weight = FontWeight.Medium, size = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        bodyLarge = t(base.bodyLarge, size = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
        bodyMedium = t(base.bodyMedium, size = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
        bodySmall = t(base.bodySmall, size = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
        labelLarge = t(base.labelLarge, weight = FontWeight.Medium, size = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        labelMedium = t(base.labelMedium, weight = FontWeight.Medium, size = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
        labelSmall = t(base.labelSmall, weight = FontWeight.Medium, size = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
        // Enhanced, semi-bold variants used for hero text and headers.
        displaySmallEmphasized = t(base.displaySmallEmphasized, weight = FontWeight.SemiBold, size = 36.sp, lineHeight = 44.sp),
        headlineLargeEmphasized = t(base.headlineLargeEmphasized, weight = FontWeight.SemiBold, size = 32.sp, lineHeight = 40.sp),
        headlineMediumEmphasized = t(base.headlineMediumEmphasized, weight = FontWeight.SemiBold, size = 28.sp, lineHeight = 36.sp),
        headlineSmallEmphasized = t(base.headlineSmallEmphasized, weight = FontWeight.SemiBold, size = 24.sp, lineHeight = 32.sp),
        titleLargeEmphasized = t(base.titleLargeEmphasized, weight = FontWeight.SemiBold, size = 22.sp, lineHeight = 28.sp),
        titleMediumEmphasized = t(base.titleMediumEmphasized, weight = FontWeight.SemiBold, size = 16.sp, lineHeight = 24.sp),
        titleSmallEmphasized = t(base.titleSmallEmphasized, weight = FontWeight.SemiBold, size = 14.sp, lineHeight = 20.sp),
        bodyLargeEmphasized = t(base.bodyLargeEmphasized, weight = FontWeight.Medium, size = 16.sp, lineHeight = 24.sp),
        bodyMediumEmphasized = t(base.bodyMediumEmphasized, weight = FontWeight.Medium, size = 14.sp, lineHeight = 20.sp),
        bodySmallEmphasized = t(base.bodySmallEmphasized, weight = FontWeight.Medium, size = 12.sp, lineHeight = 16.sp),
        labelLargeEmphasized = t(base.labelLargeEmphasized, weight = FontWeight.SemiBold, size = 14.sp, lineHeight = 20.sp),
        labelMediumEmphasized = t(base.labelMediumEmphasized, weight = FontWeight.SemiBold, size = 12.sp, lineHeight = 16.sp),
        labelSmallEmphasized = t(base.labelSmallEmphasized, weight = FontWeight.SemiBold, size = 11.sp, lineHeight = 16.sp),
    )
}