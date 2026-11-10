package com.androidharness.app.ui.chat.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

private const val SEGMENTS = 40
private const val TAIL = 0.38f
private const val LEAD = 0.04f

/**
 * A solid outline with an optional bright arc travelling around it, like the
 * text shimmer but along the border.
 *
 * [intensity] fades the arc in and out (0 draws the plain [base] outline) and
 * [phase] moves its head around the shape once per 0..1 cycle. Both are read in
 * the draw phase, so animating them never recomposes the caller. [arcs] spreads
 * evenly spaced copies of the arc around larger shapes.
 */
internal fun Modifier.shimmerOutline(
    shape: Shape,
    base: Color,
    glow: Color,
    width: Dp = 1.5.dp,
    arcs: Int = 1,
    intensity: () -> Float,
    phase: () -> Float,
): Modifier = drawWithContent {
    drawContent()
    val stroke = width.toPx()
    val strength = intensity().coerceIn(0f, 1f)
    inset(stroke / 2f) {
        val brush = if (strength < 0.01f) SolidColor(base) else {
            val head = phase()
            Brush.sweepGradient(
                colors = List(SEGMENTS + 1) { step ->
                    lerp(base, glow, arcIntensity(step / SEGMENTS.toFloat(), head, arcs) * strength)
                },
                center = center,
            )
        }
        drawOutline(shape.createOutline(size, layoutDirection, this), brush, style = Stroke(stroke))
    }
}

/** Sharp head, fading tail: 1 at an arc head, 0 outside its tail. */
private fun arcIntensity(position: Float, head: Float, arcs: Int): Float {
    var best = 0f
    for (arc in 0 until arcs.coerceAtLeast(1)) {
        var delta = position - (head + arc.toFloat() / arcs.coerceAtLeast(1))
        delta -= floor(delta + 0.5f)
        val value = if (delta <= 0f) {
            val t = (1f + delta / TAIL).coerceAtLeast(0f)
            t * t
        } else (1f - delta / LEAD).coerceAtLeast(0f)
        if (value > best) best = value
    }
    return best
}
