package com.androidharness.app.ui.chat.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidharness.app.ui.common.HarnessActivityLogo
import com.androidharness.app.ui.common.LogoShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val WORDMARK = "Android Harness"

/** Material's emphasized decelerate curve: quick start, long soft landing. */
private val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * Plays at the start of a Chat turn, in the exact spot and size of the
 * activity row it hands over to: the small AH glyph pops into the row's
 * circle and "Android Harness" unrolls beside it. The wordmark stays out,
 * shimmering, for as long as the model has not answered, so the row never
 * claims to be thinking before it is. Once [started] turns true it rolls
 * back into the glyph, the AH holds on its own for a moment, then morphs
 * into the thinking sparkle so the live row takes over without a jump.
 */
@Composable
internal fun ChatTurnIntro(started: Boolean, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val pop = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    var shape by remember { mutableStateOf(LogoShape.Monogram) }
    val finished by rememberUpdatedState(onFinished)
    val modelStarted by rememberUpdatedState(started)
    LaunchedEffect(Unit) {
        launch { pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f)) }
        delay(120)
        reveal.animateTo(1f, tween(420, easing = Emphasized))
        delay(650)
        snapshotFlow { modelStarted }.first { it }
        reveal.animateTo(0f, tween(320, easing = Emphasized))
        delay(520)
        shape = LogoShape.Sparkles
        delay(260)
        finished()
    }
    val spin by rememberInfiniteTransition(label = "Intro arc").animateFloat(
        0f, 360f, infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Restart), label = "Intro arc angle")
    Row(
        // Matches the live row's header: 4dp inset, 40dp tall.
        modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 4.dp).semantics { contentDescription = WORDMARK },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(28.dp)
                .graphicsLayer {
                    val p = pop.value
                    alpha = p.coerceIn(0f, 1f)
                    scaleX = 0.5f + 0.5f * p
                    scaleY = 0.5f + 0.5f * p
                }
                .background(scheme.surfaceContainerHigh.copy(alpha = .65f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // The same thin working arc the live row draws, so the handoff is seamless.
            Canvas(Modifier.fillMaxSize()) {
                drawArc(scheme.onSurfaceVariant.copy(alpha = .35f), spin - 90f, 95f, false,
                    topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                    size = size.copy(width = size.width - 2.dp.toPx(), height = size.height - 2.dp.toPx()),
                    style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round))
            }
            HarnessActivityLogo(shape, scheme.primary, Modifier.size(15.dp))
        }
        // Unrolls out of the glyph and rolls back into it: a left-anchored clip, not a scale.
        Box(Modifier.unroll { reveal.value }) {
            ChatShimmerText(WORDMARK, running = true)
        }
    }
}

/** Shows the leftmost [progress] of the content, fading the edge in with it. */
private fun Modifier.unroll(progress: () -> Float): Modifier = clipToBounds().layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    val p = progress().coerceIn(0f, 1f)
    layout((placeable.width * p).roundToInt(), placeable.height) {
        placeable.placeWithLayer(0, 0) {
            alpha = p
            translationX = -(1f - p) * 6.dp.toPx()
        }
    }
}
