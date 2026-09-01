package com.androidharness.app.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Minimal loading indicator: three small dots pulsing in sequence.
 *
 * Replaces the M3 Expressive morphing-shape `LoadingIndicator`, which reads as
 * playful. This one is quiet, compact, and fits into the small status slots of
 * rows and chips.
 */
@Composable
fun DotLoading(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    dotSize: Dp = 4.dp,
) {
    val transition = rememberInfiniteTransition(label = "dot loading")
    val alpha0 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(0),
        ),
        label = "dot 0",
    )
    val alpha1 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(140),
        ),
        label = "dot 1",
    )
    val alpha2 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(280),
        ),
        label = "dot 2",
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(dotSize)
                .graphicsLayer { alpha = alpha0 }
                .clip(CircleShape)
                .background(color),
        )
        Box(
            Modifier
                .size(dotSize)
                .graphicsLayer { alpha = alpha1 }
                .clip(CircleShape)
                .background(color),
        )
        Box(
            Modifier
                .size(dotSize)
                .graphicsLayer { alpha = alpha2 }
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * Thin 3dp progress line with round caps. Replaces the wavy M3 Expressive
 * `LinearWavyProgressIndicator`.
 */
@Composable
fun ThinLinearProgress(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    LinearProgressIndicator(
        modifier = modifier
            .height(3.dp)
            .clip(RoundedCornerShape(50)),
        color = color,
        trackColor = color.copy(alpha = 0.15f),
    )
}

/** Determinate variant of [ThinLinearProgress]. */
@Composable
fun ThinLinearProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier
            .height(3.dp)
            .clip(RoundedCornerShape(50)),
        color = color,
        trackColor = color.copy(alpha = 0.15f),
    )
}
