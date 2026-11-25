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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidharness.app.data.ProgressBarStyle
import com.androidharness.app.ui.theme.LocalProgressBarStyle

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

/** Compact progress bars share a fixed-height slot so changing style doesn't move content. */
@Composable
fun ThinLinearProgress(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    AppLinearProgressIndicator(modifier = modifier, color = color)
}

/** Uses the saved appearance choice in the existing tool-card slot. */
@Composable
fun ToolCallProgress(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    AppLinearProgressIndicator(modifier = modifier, color = color)
}

/** Determinate variant of [ThinLinearProgress]. */
@Composable
fun ThinLinearProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    AppLinearProgressIndicator(progress = progress, modifier = modifier, color = color)
}

@Composable
fun AppLinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.secondaryContainer,
) = StyledLinearProgress(null, modifier, color, trackColor)

@Composable
fun AppLinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.secondaryContainer,
) = StyledLinearProgress(progress, modifier, color, trackColor)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StyledLinearProgress(progress: (() -> Float)?, modifier: Modifier, color: Color, trackColor: Color) {
    val squiggly = LocalProgressBarStyle.current == ProgressBarStyle.SQUIGGLY
    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        Box(modifier.height(10.dp), contentAlignment = Alignment.Center) {
            when {
                squiggly && progress != null -> LinearWavyProgressIndicator(progress = progress,
                    modifier = Modifier.fillMaxWidth(), color = color, trackColor = trackColor)
                squiggly -> LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth(), color = color, trackColor = trackColor)
                progress != null -> LinearProgressIndicator(progress = progress,
                    modifier = Modifier.fillMaxWidth(), color = color, trackColor = trackColor)
                else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = color, trackColor = trackColor)
            }
        }
    }
}

/** Determinate ring that follows the saved flat or squiggly appearance choice. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppCircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    if (LocalProgressBarStyle.current == ProgressBarStyle.SQUIGGLY) {
        CircularWavyProgressIndicator(progress = progress, modifier = modifier, color = color, trackColor = trackColor)
    } else {
        CircularProgressIndicator(progress = progress, modifier = modifier, color = color, trackColor = trackColor,
            strokeWidth = 4.dp, gapSize = 4.dp)
    }
}
