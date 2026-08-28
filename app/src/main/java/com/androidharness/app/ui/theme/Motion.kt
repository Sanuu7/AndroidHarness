package com.androidharness.app.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Single source of truth for motion.
 *
 * Subtle & snappy: movement uses fast springs with no bounce, fades and scales
 * use short eased tweens. Nothing wiggles, morphs, or overshoots, the UI
 * should feel precise and get out of the way.
 *
 * The helper names are stable API; every animation site in the app reads
 * through these, so retuning here changes the whole app's feel at once.
 */
fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
    spring(dampingRatio = 0.86f, stiffness = 700f)

fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> =
    spring(dampingRatio = 0.9f, stiffness = 1100f)

fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> =
    spring(dampingRatio = 0.9f, stiffness = 380f)

fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> =
    tween(durationMillis = 220, easing = FastOutSlowInEasing)

fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
    tween(durationMillis = 150, easing = FastOutSlowInEasing)

fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> =
    tween(durationMillis = 350, easing = FastOutSlowInEasing)
