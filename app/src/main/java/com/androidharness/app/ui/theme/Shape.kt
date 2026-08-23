package com.androidharness.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii for the flat, professional look.
 *
 * Tighter than the M3 Expressive defaults: cards and sheets sit at 12–16dp
 * (calmer than the old 22–28dp squircles), small controls at 6–8dp.
 * True pills (composer, chips) are still created inline with CircleShape.
 */
val HarnessShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
