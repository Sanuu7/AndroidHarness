package com.androidharness.app.assistant

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The assistant's light: a soft pool of the theme's color resting on the bottom edge, under
 * the pill. It rises with the pill, drifts slowly while the assistant waits, sinks lower while
 * the user types or reads, breathes while an answer is written, and swells with the voice.
 * When the assistant opens, one thread of light also runs from the bottom up both sides of the
 * screen and meets at the top, once.
 *
 * The pool is two radial gradients built once; a frame only moves their matrices. The thread is
 * the screen's outline stroked three times with one small sweep gradient. No layers or shaders
 * to compile, so it costs the same on a budget phone as on a flagship.
 */

/** How long the opening thread takes to reach the top. */
private const val TRACE_MS = 1_350

/** How far behind each head the thread's tail reaches, in degrees around the screen center. */
private const val TRAIL = 80f

/** One color stop every three degrees, fine enough that the head stays crisp. */
private const val TRACE_STEPS = 120

/** Quick away from the bottom, slowing as the two heads meet at the top. */
private val TraceEase = CubicBezierEasing(0.35f, 0f, 0.15f, 1f)

/** How tall the pool stands at rest. */
private val PoolHeight = 300.dp

/** A bell curve that reaches exactly zero at its edge, so the light has no rim or band. */
private val PoolStops = FloatArray(17) { it / 16f }
private val PoolCurve = PoolStops.map { s -> ((exp(-3.4f * s * s) - exp(-3.4f)) / (1f - exp(-3.4f))).coerceIn(0f, 1f) }

/** The theme's hue at full color, so dark themes' pastel tones still read as light. */
private fun vivid(color: Color, hueShift: Float = 0f): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = (hsv[0] + hueShift + 360f) % 360f
    hsv[1] = maxOf(hsv[1], 0.6f)
    hsv[2] = 1f
    return android.graphics.Color.HSVToColor(hsv)
}

private fun pool(color: Int) = Paint().apply {
    isDither = true
    shader = RadialGradient(0f, 0f, 1f,
        PoolCurve.map { a -> android.graphics.Color.argb((a * 255f).roundToInt(), android.graphics.Color.red(color),
            android.graphics.Color.green(color), android.graphics.Color.blue(color)) }.toIntArray(),
        PoolStops, Shader.TileMode.CLAMP)
}

/**
 * [fade] shows and hides the light with the panel, [rise] is the pill's entrance (0 to 1, with
 * its spring's small overshoot), and [level] the microphone's loudness.
 */
@Composable
internal fun HarnessHalo(
    showKey: Int,
    engaged: Boolean,
    working: Boolean,
    reduceMotion: Boolean,
    level: () -> Float,
    fade: () -> Float,
    rise: () -> Float,
) {
    val scheme = MaterialTheme.colorScheme
    val paints = remember(scheme) { listOf(pool(vivid(scheme.primary)), pool(vivid(scheme.tertiary, hueShift = 12f))) }
    val spot = remember { Matrix() }
    val head = remember(scheme) { vivid(scheme.primary) }
    val tail = remember(scheme) { vivid(scheme.tertiary, hueShift = 12f) }
    val corner = rememberScreenCorner()

    // The opening thread, once per call.
    val trace = remember { Animatable(1f) }
    LaunchedEffect(showKey) {
        if (reduceMotion) trace.snapTo(1f)
        else { trace.snapTo(0f); trace.animateTo(1f, tween(TRACE_MS, easing = TraceEase)) }
    }

    // Full height while it waits, low and quiet once the user is typing or reading.
    val stand by animateFloatAsState(if (engaged && !working) 0.42f else 1f,
        spring(dampingRatio = 1f, stiffness = if (reduceMotion) 400f else 50f), label = "haloStand")
    // Follows the voice smoothly, read only while drawing.
    val voice = remember { Animatable(0f) }
    LaunchedEffect(showKey) {
        snapshotFlow { level().coerceIn(0f, 1f) }.collectLatest { voice.animateTo(it, spring(dampingRatio = 1f, stiffness = 160f)) }
    }
    val loop = rememberInfiniteTransition(label = "halo")
    val drift by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(if (reduceMotion) 120_000 else 16_000, easing = LinearEasing)), label = "haloDrift")
    val breath by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "haloBreath")
    val busy by animateFloatAsState(if (working && !reduceMotion) 1f else 0f, tween(600), label = "haloBusy")

    Spacer(Modifier.fillMaxSize().drawWithCache {
        val w = size.width
        val h = size.height
        val r = (corner ?: 24.dp.toPx()).coerceIn(0f, minOf(w, h) / 2f)
        // The stroke is centered on the glass, so only its inner half shows: a wide faint glow,
        // a softer band and a fine bright line.
        // A round rect, not a path: the GPU strokes it directly instead of drawing a mask on the CPU.
        val edge = RectF(0f, 0f, w, h)
        val threads = listOf(18.dp to 0.2f, 8.dp to 0.45f, 2.5.dp to 1f).map { (width, alpha) ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = width.toPx(); isDither = true } to alpha
        }
        val stops = FloatArray(TRACE_STEPS + 1) { it / TRACE_STEPS.toFloat() }
        val colors = IntArray(TRACE_STEPS + 1)
        onDrawBehind {
            val shown = fade().coerceIn(0f, 1f)
            if (shown <= 0.003f) return@onDrawBehind
            drawIntoCanvas { c ->
                val canvas = c.nativeCanvas
                val turn = drift * 2f * PI.toFloat()
                val pulse = 1f - busy * 0.22f * (0.5f + 0.5f * sin(breath * 2f * PI.toFloat()))
                val tall = PoolHeight.toPx() * stand * rise().coerceIn(0f, 1.2f) * (1f + 0.45f * voice.value)
                if (tall >= 1f) {
                    // The two colors sway past each other, never far from the middle.
                    canvas.spot(spot, paints[0], w / 2f + w * 0.13f * sin(turn), h, w * 0.78f, tall, 0.62f * shown * pulse)
                    canvas.spot(spot, paints[1], w / 2f - w * 0.15f * sin(turn + 1.9f), h, w * 0.6f, tall * 0.8f, 0.5f * shown * pulse)
                }
                val p = trace.value
                if (p < 1f) {
                    // Angles run clockwise from three o'clock, so the bottom is 90 and the top 270.
                    val travel = 180f * p
                    val left = 90f + travel
                    val right = 90f - travel
                    for (i in colors.indices) {
                        val at = 360f * stops[i]
                        val k = maxOf(thread(around(left - at), travel), thread(around(at - right), travel))
                        colors[i] = mix(tail, head, k, k)
                    }
                    val shader = SweepGradient(w / 2f, h / 2f, colors, stops)
                    // In quickly, out softly as the heads meet.
                    val strength = shown * (p / 0.06f).coerceAtMost(1f) * (1f - smooth((p - 0.72f) / 0.28f))
                    for ((paint, alpha) in threads) {
                        paint.shader = shader
                        paint.alpha = (alpha * strength * 255f).roundToInt().coerceIn(0, 255)
                        canvas.drawRoundRect(edge, r, r, paint)
                    }
                }
            }
        }
    })
}

private fun around(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

/** Brightness [behind] degrees back from a head that has come [travel] degrees from the bottom. */
private fun thread(behind: Float, travel: Float): Float {
    if (behind > travel || behind > TRAIL) return 0f
    val k = 1f - behind / TRAIL
    // Fades out over the last stretch before the starting point, so the bottom has no hard end.
    val start = ((travel - behind) / 12f).coerceIn(0f, 1f)
    return k * k * start
}

private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

/** [from] to [to] by [t], with [alpha] as opacity. */
private fun mix(from: Int, to: Int, t: Float, alpha: Float): Int {
    fun ch(a: Int, b: Int) = (a + (b - a) * t).roundToInt().coerceIn(0, 255)
    return android.graphics.Color.argb((alpha.coerceIn(0f, 1f) * 255f).roundToInt(),
        ch(android.graphics.Color.red(from), android.graphics.Color.red(to)),
        ch(android.graphics.Color.green(from), android.graphics.Color.green(to)),
        ch(android.graphics.Color.blue(from), android.graphics.Color.blue(to)))
}

/** The display's own corner radius, so the thread follows the glass. */
@Composable
private fun rememberScreenCorner(): Float? {
    val view = LocalView.current
    return remember(view) {
        if (Build.VERSION.SDK_INT >= 31) view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?.toFloat()
        else null
    }
}

/** One flattened pool of light centered on the bottom edge. */
private fun Canvas.spot(matrix: Matrix, paint: Paint, x: Float, bottom: Float, rx: Float, ry: Float, alpha: Float) {
    if (alpha <= 0.003f) return
    matrix.setScale(rx, ry)
    matrix.postTranslate(x, bottom)
    paint.shader.setLocalMatrix(matrix)
    paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
    drawRect(x - rx, bottom - ry, x + rx, bottom, paint)
}
