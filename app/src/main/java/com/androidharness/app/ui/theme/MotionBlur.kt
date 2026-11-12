package com.androidharness.app.ui.theme

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Scroll motion blur: a vertical-only gaussian smear, sized from how far each
 * frame of scrolling travels. Slow reading stays sharp, a flick smears the
 * content the way a camera pan does, and the smear settles as the fling decays.
 *
 * A vertical-only blur is the honest approximation of motion blur for a
 * vertically scrolling list, and RenderEffect blurs cost one GPU pass instead
 * of a shader per pixel.
 */
@Immutable
internal data class MotionBlurStyle(val enabled: Boolean = false, val intensity: Float = .5f) {
    val strength: Float get() = if (intensity.isFinite()) intensity.coerceIn(0f, 1f) else .5f
}

internal val LocalMotionBlur = staticCompositionLocalOf { MotionBlurStyle() }

/** Attach to (or outside of) a vertical scroller. Nested scroll reports every scrolled pixel. */
@Composable
internal fun Modifier.motionBlur(style: MotionBlurStyle = LocalMotionBlur.current): Modifier {
    if (!style.enabled || style.strength <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    val density = LocalDensity.current
    val radius = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val plan = remember(density, style.strength) {
        MotionBlurPlan(
            // Under ~4px per frame nothing is smeared; that is ordinary reading.
            thresholdPx = with(density) { 5.dp.toPx() },
            maxRadiusPx = with(density) { (1f + 7f * style.strength).dp.toPx() },
            strength = style.strength,
        )
    }
    val settle = remember(radius, scope) { MotionBlurSettle(scope, radius) }
    val connection = remember(plan, radius, settle) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy.isFinite()) {
                    val target = ((abs(dy) - plan.thresholdPx).coerceAtLeast(0f) * plan.strength / 6f)
                        .coerceAtMost(plan.maxRadiusPx)
                    if (target > radius.floatValue) radius.floatValue = target
                    if (radius.floatValue > 0f) settle.start()
                }
                return Offset.Zero
            }
        }
    }
    DisposableEffect(settle) { onDispose { settle.cancel() } }
    return this
        .nestedScroll(connection)
        .graphicsLayer {
            val r = radius.floatValue
            renderEffect = if (r >= .5f) RenderEffect.createBlurEffect(0f, r, Shader.TileMode.CLAMP).asComposeRenderEffect() else null
        }
}

private class MotionBlurPlan(val thresholdPx: Float, val maxRadiusPx: Float, val strength: Float)

/** Lets the smear fade once the fling's per-frame travel drops under the threshold. */
private class MotionBlurSettle(private val scope: CoroutineScope, private val radius: MutableFloatState) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (radius.floatValue > .05f) {
                withFrameNanos { radius.floatValue *= .78f }
            }
            radius.floatValue = 0f
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
